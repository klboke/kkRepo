package com.github.klboke.kkrepo.server.kkguard.dependencytrack;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.klboke.kkrepo.server.kkguard.KkGuardProperties;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link DependencyTrackClient} over {@code java.net.http}.
 *
 * <p>The client is stateless apart from the immutable base URL, API key and HTTP client, so it can
 * be shared by every worker thread on a replica. Failures are surfaced as
 * {@link DependencyTrackException} with a sanitized message: the API key is never part of an
 * exception, a log line or a response body.
 */
public final class HttpDependencyTrackClient implements DependencyTrackClient {
  private static final Logger log = LoggerFactory.getLogger(HttpDependencyTrackClient.class);
  private static final String API_KEY_HEADER = "X-Api-Key";
  private static final int MAX_ERROR_BODY = 256;
  private final URI baseUri;
  private final String apiKey;
  private final HttpClient httpClient;
  private final ObjectMapper objectMapper;
  private final Duration requestTimeout;
  private final int maxResponseBytes;
  private final int maxFindings;

  private HttpDependencyTrackClient(
      URI baseUri, String apiKey, HttpClient httpClient, ObjectMapper objectMapper,
      Duration requestTimeout, int maxResponseBytes, int maxFindings) {
    this.baseUri = baseUri;
    this.apiKey = apiKey;
    this.httpClient = httpClient;
    this.objectMapper = objectMapper;
    this.requestTimeout = requestTimeout;
    this.maxResponseBytes = maxResponseBytes;
    this.maxFindings = maxFindings;
  }

  /** Builds a client from operator configuration; blank settings yield an unconfigured client. */
  public static DependencyTrackClient create(
      KkGuardProperties.DependencyTrack settings, ObjectMapper objectMapper) {
    if (settings == null || !settings.isConfigured()) {
      return new HttpDependencyTrackClient(
          null, "", HttpClient.newHttpClient(), objectMapper, Duration.ofSeconds(30),
          1024 * 1024, 1);
    }
    URI baseUri = normalize(settings.getUrl());
    HttpClient client = buildHttpClient(settings);
    return new HttpDependencyTrackClient(
        baseUri, settings.getApiKey(), client, objectMapper, settings.getRequestTimeout(),
        64 * 1024 * 1024, settings.getMaxFindings());
  }

  @Override
  public boolean isConfigured() {
    return baseUri != null && !apiKey.isEmpty();
  }

  @Override
  public Optional<Project> findProject(String name, String version) {
    String path = "/api/v1/project/lookup?name=" + encode(name) + "&version=" + encode(version);
    Response response = send("GET", path, null);
    if (response.status() == 404) {
      return Optional.empty();
    }
    requireSuccess("lookup project", response);
    JsonNode node = parse("lookup project", response.body());
    return Optional.of(project(node));
  }

  @Override
  public Project createProject(String name, String version) {
    String body = objectMapper.createObjectNode()
        .put("name", name)
        .put("version", version)
        .put("active", true).toString();
    Response response = send("PUT", "/api/v1/project", body);
    requireSuccess("create project", response);
    return project(parse("create project", response.body()));
  }

  @Override
  public String uploadBom(String projectUuid, byte[] bom) {
    String body = objectMapper.createObjectNode()
        .put("project", projectUuid)
        .put("bom", Base64.getEncoder().encodeToString(bom))
        .put("autoCreate", false).toString();
    Response response = send("PUT", "/api/v1/bom", body);
    requireSuccess("upload BOM", response);
    String token = text(parse("upload BOM", response.body()), "token");
    if (token == null || token.isBlank()) {
      throw new DependencyTrackException("Dependency-Track did not return a processing token", 200);
    }
    return token;
  }

  @Override
  public boolean isProcessing(String token) {
    Response response = send("GET", "/api/v1/bom/token/" + encode(token), null);
    if (response.status() == 404) {
      // An unknown token means the analysis state cannot be read. Treating it as "finished" would
      // complete the scan with zero findings, i.e. a silent false negative.
      throw new DependencyTrackException(
          "Dependency-Track does not know BOM token " + token + "; analysis state is unknown");
    }
    requireSuccess("read BOM processing state", response);
    return parse("read BOM processing state", response.body()).path("processing").asBoolean(false);
  }

  @Override
  public List<Finding> listFindings(String projectUuid) {
    Response response = send("GET", "/api/v1/finding/project/" + encode(projectUuid), null);
    if (response.status() == 404) {
      // The project UUID came from Dependency-Track itself, so a 404 means the project is gone or
      // the API is misrouted. Reporting "no findings" would complete the scan at zero findings, a
      // silent false negative, so this fails the scan instead.
      throw new DependencyTrackException(
          "Dependency-Track does not know project " + projectUuid + "; findings are unknown", 404);
    }
    requireSuccess("list findings", response);
    JsonNode root = parse("list findings", response.body());
    if (!root.isArray()) {
      throw new DependencyTrackException("Dependency-Track returned an unexpected findings payload");
    }
    List<Finding> findings = new ArrayList<>(Math.min(root.size(), maxFindings));
    for (JsonNode node : root) {
      if (findings.size() >= maxFindings) {
        break;
      }
      findings.add(finding(node));
    }
    return findings;
  }

  @Override
  public List<Violation> listViolations(String projectUuid) {
    Response response =
        send("GET", "/api/v1/violation/project/" + encode(projectUuid), null);
    if (response.status() == 404) {
      // Same reasoning as listFindings: an unknown project is not "zero violations".
      throw new DependencyTrackException(
          "Dependency-Track does not know project " + projectUuid + "; violations are unknown", 404);
    }
    requireSuccess("list violations", response);
    JsonNode root = parse("list violations", response.body());
    if (!root.isArray()) {
      throw new DependencyTrackException(
          "Dependency-Track returned an unexpected violations payload");
    }
    List<Violation> violations = new ArrayList<>(Math.min(root.size(), maxFindings));
    for (JsonNode node : root) {
      if (violations.size() >= maxFindings) {
        break;
      }
      violations.add(violation(node));
    }
    return violations;
  }

  private Response send(String method, String path, String body) {
    if (!isConfigured()) {
      throw new DependencyTrackException("Dependency-Track is not configured");
    }
    HttpRequest.Builder builder = HttpRequest.newBuilder(resolve(path))
        .timeout(requestTimeout)
        .header(API_KEY_HEADER, apiKey)
        .header("Accept", "application/json");
    if (body == null) {
      builder.method(method, HttpRequest.BodyPublishers.noBody());
    } else {
      builder.header("Content-Type", "application/json")
          .method(method, HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
    }
    HttpResponse<InputStream> response;
    try {
      response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream());
    } catch (IOException e) {
      throw new DependencyTrackException("Dependency-Track request failed: " + safe(e), e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new DependencyTrackException("Dependency-Track request was interrupted", e);
    }
    try (InputStream input = response.body()) {
      String payload = readBounded(input);
      return new Response(response.statusCode(), payload);
    } catch (IOException e) {
      throw new DependencyTrackException("Failed to read Dependency-Track response", e);
    }
  }

  private URI resolve(String path) {
    String base = baseUri.toString();
    if (base.endsWith("/")) {
      base = base.substring(0, base.length() - 1);
    }
    return URI.create(base + path);
  }

  private void requireSuccess(String operation, Response response) {
    int status = response.status();
    if (status >= 200 && status < 300) {
      return;
    }
    throw new DependencyTrackException(
        "Dependency-Track " + operation + " failed with HTTP " + status + describe(response.body()),
        status);
  }

  private JsonNode parse(String operation, String body) {
    try {
      return objectMapper.readTree(body == null ? "" : body);
    } catch (IOException e) {
      throw new DependencyTrackException(
          "Dependency-Track " + operation + " returned invalid JSON", e);
    }
  }

  private String readBounded(InputStream input) throws IOException {
    byte[] buffer = new byte[8192];
    java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
    int read;
    while ((read = input.read(buffer)) >= 0) {
      if (out.size() + read > maxResponseBytes) {
        throw new DependencyTrackException("Dependency-Track response exceeds the size limit");
      }
      out.write(buffer, 0, read);
    }
    return out.toString(StandardCharsets.UTF_8);
  }

  private static Project project(JsonNode node) {
    String uuid = text(node, "uuid");
    if (uuid == null || uuid.isBlank()) {
      throw new DependencyTrackException("Dependency-Track project is missing its UUID");
    }
    return new Project(uuid, text(node, "name"), text(node, "version"));
  }

  private static Finding finding(JsonNode node) {
    JsonNode component = node.path("component");
    JsonNode vulnerability = node.path("vulnerability");
    JsonNode analysis = node.path("analysis");
    String purl = text(component, "purl");
    String name = text(component, "name");
    String version = text(component, "version");
    String group = text(component, "group");
    String reference = purl != null && !purl.isBlank() ? purl : coordinate(group, name, version);
    BigDecimal cvss = decimal(vulnerability, "cvssV3BaseScore");
    if (cvss == null) {
      cvss = decimal(vulnerability, "cvssV2BaseScore");
    }
    return new Finding(
        reference, purl, name, version, text(vulnerability, "vulnId"),
        upper(text(vulnerability, "severity")), cvss, text(vulnerability, "source"),
        text(vulnerability, "description"), firstText(vulnerability, "references"),
        analysis.path("isSuppressed").asBoolean(false));
  }

  private static Violation violation(JsonNode node) {
    JsonNode component = node.path("component");
    JsonNode condition = node.path("policyCondition");
    String purl = text(component, "purl");
    String reference = purl != null && !purl.isBlank()
        ? purl
        : coordinate(
            text(component, "group"), text(component, "name"), text(component, "version"));
    String policyName = text(condition.path("policy"), "name");
    String subject = text(condition, "subject");
    String value = text(condition, "value");
    String conditionText = subject == null ? null
        : value == null ? subject : subject + " " + value;
    return new Violation(
        upper(text(node, "type")), reference, policyName, conditionText,
        upper(text(node.path("analysis"), "state")));
  }

  private static String coordinate(String group, String name, String version) {
    StringBuilder builder = new StringBuilder();
    if (group != null && !group.isBlank()) {
      builder.append(group).append(':');
    }
    builder.append(name == null ? "" : name);
    if (version != null && !version.isBlank()) {
      builder.append('@').append(version);
    }
    return builder.toString();
  }

  private static String text(JsonNode node, String field) {
    if (node == null) {
      return null;
    }
    JsonNode value = node.get(field);
    if (value == null || value.isNull() || !value.isValueNode()) {
      return null;
    }
    String text = value.asText();
    return text == null || text.isBlank() ? null : text;
  }

  private static String firstText(JsonNode node, String field) {
    JsonNode value = node == null ? null : node.get(field);
    if (value == null || value.isNull()) {
      return null;
    }
    if (value.isArray() && !value.isEmpty()) {
      return text(value.get(0), "url") != null ? text(value.get(0), "url") : value.get(0).asText();
    }
    return value.isValueNode() ? value.asText() : null;
  }

  private static BigDecimal decimal(JsonNode node, String field) {
    JsonNode value = node == null ? null : node.get(field);
    if (value == null || value.isNull() || !value.isNumber()) {
      return null;
    }
    return value.decimalValue();
  }

  private static String upper(String value) {
    return value == null ? null : value.toUpperCase(java.util.Locale.ROOT);
  }

  private static String encode(String value) {
    return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
  }

  private static String describe(String body) {
    if (body == null || body.isBlank()) {
      return "";
    }
    String sanitized = body.replaceAll("\\s+", " ").trim();
    if (sanitized.length() > MAX_ERROR_BODY) {
      sanitized = sanitized.substring(0, MAX_ERROR_BODY);
    }
    return ": " + sanitized;
  }

  private static String safe(IOException exception) {
    String message = exception.getMessage();
    return message == null || message.isBlank()
        ? exception.getClass().getSimpleName() : message;
  }

  private static URI normalize(String url) {
    String value = url.trim();
    URI uri;
    try {
      uri = URI.create(value.endsWith("/") ? value.substring(0, value.length() - 1) : value);
    } catch (IllegalArgumentException e) {
      throw new DependencyTrackException("kkrepo.kkg.dependency-track.url is not a valid URL", e);
    }
    if (uri.getScheme() == null || uri.getHost() == null) {
      throw new DependencyTrackException(
          "kkrepo.kkg.dependency-track.url must be an absolute http(s) URL");
    }
    String scheme = uri.getScheme().toLowerCase(java.util.Locale.ROOT);
    if (!scheme.equals("http") && !scheme.equals("https")) {
      throw new DependencyTrackException(
          "kkrepo.kkg.dependency-track.url must use http or https");
    }
    return uri;
  }

  private static HttpClient buildHttpClient(KkGuardProperties.DependencyTrack settings) {
    HttpClient.Builder builder = HttpClient.newBuilder()
        .connectTimeout(settings.getConnectTimeout())
        .followRedirects(HttpClient.Redirect.NEVER);
    if (!settings.isVerifyTls()) {
      // Relaxing trust for a private Dependency-Track instance must stay scoped to this client.
      // The JDK forbids disabling HTTPS endpoint identification per client, so only the trust
      // chain is relaxed here: hostname verification remains active, and this method never
      // touches a JVM-wide system property (that would also disarm the Maven proxy and the Nexus
      // migration client in the same process). A certificate whose SAN does not match the
      // configured host still fails, which is deliberate.
      builder.sslContext(trustAllContext());
      log.warn(
          "kkrepo.kkg.dependency-track.verify-tls=false trusts any certificate chain for the "
              + "kkGuard Dependency-Track client only; hostname verification stays enabled");
    }
    return builder.build();
  }

  private static SSLContext trustAllContext() {
    try {
      TrustManager[] managers = {new X509TrustManager() {
        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType) {}

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType) {}

        @Override
        public X509Certificate[] getAcceptedIssuers() {
          return new X509Certificate[0];
        }
      }};
      SSLContext context = SSLContext.getInstance("TLS");
      context.init(null, managers, null);
      return context;
    } catch (GeneralSecurityException e) {
      throw new DependencyTrackException("Failed to build the TLS context", e);
    }
  }

  private record Response(int status, String body) {}
}
