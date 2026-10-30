package com.github.klboke.kkrepo.server.kkguard.dependencytrack;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.klboke.kkrepo.server.kkguard.KkGuardProperties;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(30)
class HttpDependencyTrackClientTest {
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final String API_KEY = "secret-api-key-value";

  private DependencyTrackStub stub;
  private DependencyTrackClient client;

  @BeforeEach
  void setUp() throws IOException {
    stub = new DependencyTrackStub();
    client = client(Duration.ofSeconds(5));
  }

  @AfterEach
  void tearDown() {
    stub.close();
  }

  @Test
  void lookupUploadFindingsAndViolationsFollowTheDependencyTrackApi() throws Exception {
    stub.respond("GET", "/api/v1/project/lookup", 200,
        "{\"uuid\":\"11111111-1111-1111-1111-111111111111\",\"name\":\"orders\","
            + "\"version\":\"main\"}");
    stub.respond("PUT", "/api/v1/bom", 200, "{\"token\":\"22222222-2222-2222-2222-222222222222\"}");
    stub.respond("GET", "/api/v1/bom/token/22222222-2222-2222-2222-222222222222", 200,
        "{\"processing\":false}");
    stub.respond("GET", "/api/v1/finding/project/11111111-1111-1111-1111-111111111111", 200, """
        [{"component":{"uuid":"c1","name":"lodash","version":"4.17.20",
           "purl":"pkg:npm/lodash@4.17.20"},
          "vulnerability":{"vulnId":"CVE-2021-23337","source":"NVD","severity":"HIGH",
             "cvssV3BaseScore":7.2,"description":"command injection",
             "references":[{"url":"https://nvd.example/CVE-2021-23337"}]},
          "analysis":{"state":"NOT_SET","isSuppressed":false}}]
        """);
    stub.respond("GET", "/api/v1/violation/project/11111111-1111-1111-1111-111111111111", 200, """
        [{"type":"SECURITY","component":{"name":"lodash","version":"4.17.20",
           "purl":"pkg:npm/lodash@4.17.20"},
          "policyCondition":{"policy":{"name":"No high severity"},
             "subject":"SEVERITY","value":"HIGH"},
          "analysis":{"state":"NOT_SET"}}]
        """);

    var project = client.findProject("orders", "main").orElseThrow();
    assertEquals("11111111-1111-1111-1111-111111111111", project.uuid());
    assertEquals("main", project.version());
    assertTrue(stub.query("GET", "/api/v1/project/lookup").contains("name=orders"));
    assertTrue(stub.query("GET", "/api/v1/project/lookup").contains("version=main"));

    byte[] sbom = "{\"bomFormat\":\"CycloneDX\"}".getBytes(StandardCharsets.UTF_8);
    String token = client.uploadBom(project.uuid(), sbom);
    assertEquals("22222222-2222-2222-2222-222222222222", token);
    JsonNode upload = JSON.readTree(stub.body("PUT", "/api/v1/bom"));
    assertEquals(project.uuid(), upload.path("project").asText());
    assertEquals(false, upload.path("autoCreate").asBoolean(true));
    assertEquals(new String(sbom, StandardCharsets.UTF_8),
        new String(Base64.getDecoder().decode(upload.path("bom").asText()),
            StandardCharsets.UTF_8));
    assertEquals(API_KEY, stub.header("PUT", "/api/v1/bom", "X-Api-Key"));
    assertFalse(client.isProcessing(token));

    List<DependencyTrackClient.Finding> findings = client.listFindings(project.uuid());
    assertEquals(1, findings.size());
    DependencyTrackClient.Finding finding = findings.get(0);
    assertEquals("pkg:npm/lodash@4.17.20", finding.componentRef());
    assertEquals("CVE-2021-23337", finding.vulnerabilityId());
    assertEquals("HIGH", finding.severity());
    assertEquals(0, finding.cvssScore().compareTo(new java.math.BigDecimal("7.2")));
    assertEquals("https://nvd.example/CVE-2021-23337", finding.url());

    List<DependencyTrackClient.Violation> violations = client.listViolations(project.uuid());
    assertEquals(1, violations.size());
    assertEquals("SECURITY", violations.get(0).type());
    assertEquals("No high severity", violations.get(0).policyName());
    assertEquals("SEVERITY HIGH", violations.get(0).condition());
  }

  @Test
  void missingProjectsAreEmptyButMissingFindingsFail() {
    stub.respond("GET", "/api/v1/project/lookup", 404, "The project could not be found.");
    stub.respond("GET", "/api/v1/finding/project/absent", 404, "not found");
    stub.respond("GET", "/api/v1/violation/project/absent", 404, "not found");

    assertTrue(client.findProject("absent", "1.0").isEmpty());

    // A 404 on a project UUID that came from Dependency-Track means the snapshot cannot be read.
    // Returning an empty list would complete the scan at zero findings: a silent false negative.
    var findingsError = assertThrows(
        DependencyTrackException.class, () -> client.listFindings("absent"));
    assertEquals(404, findingsError.statusCode());
    var violationsError = assertThrows(
        DependencyTrackException.class, () -> client.listViolations("absent"));
    assertEquals(404, violationsError.statusCode());
  }

  @Test
  void projectCreationUsesTheProjectEndpointAndReturnsItsUuid() throws Exception {
    stub.respond("PUT", "/api/v1/project", 201,
        "{\"uuid\":\"33333333-3333-3333-3333-333333333333\",\"name\":\"billing\","
            + "\"version\":\"main\"}");

    var project = client.createProject("billing", "main");

    assertEquals("33333333-3333-3333-3333-333333333333", project.uuid());
    JsonNode request = JSON.readTree(stub.body("PUT", "/api/v1/project"));
    assertEquals("billing", request.path("name").asText());
    assertEquals("main", request.path("version").asText());
  }

  @Test
  void failuresAreSanitizedAndNeverExposeTheApiKey() {
    stub.respond("GET", "/api/v1/finding/project/broken", 500, "upstream failure");

    DependencyTrackException error = assertThrows(DependencyTrackException.class,
        () -> client.listFindings("broken"));

    assertEquals(500, error.statusCode());
    assertTrue(error.getMessage().contains("HTTP 500"));
    assertFalse(error.getMessage().contains(API_KEY));
  }

  @Test
  void malformedProjectPayloadsAreRejected() {
    stub.respond("GET", "/api/v1/project/lookup", 200, "{\"name\":\"no-uuid\"}");

    DependencyTrackException error = assertThrows(DependencyTrackException.class,
        () -> client.findProject("broken", "1"));

    assertTrue(error.getMessage().contains("UUID"));
  }

  @Test
  void unconfiguredClientsFailWithoutNetworkAccess() {
    KkGuardProperties.DependencyTrack settings = new KkGuardProperties.DependencyTrack();
    DependencyTrackClient unconfigured =
        HttpDependencyTrackClient.create(settings, JSON);

    assertFalse(unconfigured.isConfigured());
    DependencyTrackException error = assertThrows(DependencyTrackException.class,
        () -> unconfigured.listFindings("any"));
    assertTrue(error.getMessage().contains("not configured"));
  }

  @Test
  void baseUrlsWithTrailingSlashesOrContextPathsResolveCorrectly() {
    KkGuardProperties.DependencyTrack settings = new KkGuardProperties.DependencyTrack();
    settings.setUrl(stub.baseUrl() + "/");
    settings.setApiKey(API_KEY);
    DependencyTrackClient prefixed = HttpDependencyTrackClient.create(settings, JSON);
    stub.respond("GET", "/api/v1/finding/project/x", 200, "[]");

    assertTrue(prefixed.listFindings("x").isEmpty());
    assertEquals("/api/v1/finding/project/x", stub.lastPath("GET"));
  }

  @Test
  void unknownBomTokensAreAnErrorInsteadOfAnEmptyAnalysis() {
    stub.respond("GET", "/api/v1/bom/token/unknown-token", 404, "");

    DependencyTrackException error = assertThrows(DependencyTrackException.class,
        () -> client.isProcessing("unknown-token"));

    // Returning false here used to complete the scan with zero findings: a silent false negative.
    assertTrue(error.getMessage().contains("analysis state is unknown"));
  }

  @Test
  void disablingTlsVerificationStaysScopedToTheKkGuardClient() {
    String switchName = "jdk.internal.httpclient.disableHostnameVerification";
    String previous = System.getProperty(switchName);
    System.clearProperty(switchName);
    var defaultVerifier = javax.net.ssl.HttpsURLConnection.getDefaultHostnameVerifier();
    try {
      KkGuardProperties.DependencyTrack settings = new KkGuardProperties.DependencyTrack();
      settings.setUrl("https://dependency-track.internal.example");
      settings.setApiKey(API_KEY);
      settings.setVerifyTls(false);

      DependencyTrackClient created = HttpDependencyTrackClient.create(settings, JSON);

      assertTrue(created instanceof HttpDependencyTrackClient);
      // P0 regression: the switch is JVM-wide, so touching it would also disarm the Maven proxy and
      // the Nexus migration client running in the same process.
      assertEquals(null, System.getProperty(switchName));
      assertTrue(
          defaultVerifier == javax.net.ssl.HttpsURLConnection.getDefaultHostnameVerifier(),
          "the JVM-wide default hostname verifier must not be replaced");
    } finally {
      if (previous == null) {
        System.clearProperty(switchName);
      } else {
        System.setProperty(switchName, previous);
      }
    }
  }

  private DependencyTrackClient client(Duration timeout) {
    KkGuardProperties.DependencyTrack settings = new KkGuardProperties.DependencyTrack();
    settings.setUrl(stub.baseUrl());
    settings.setApiKey(API_KEY);
    settings.setRequestTimeout(timeout);
    settings.setConnectTimeout(timeout);
    return HttpDependencyTrackClient.create(settings, JSON);
  }

  /** Minimal Dependency-Track stand-in that records requests and serves canned responses. */
  private static final class DependencyTrackStub implements AutoCloseable {
    private final HttpServer server;
    private final Map<String, StubResponse> responses = new ConcurrentHashMap<>();
    private final Map<String, CopyOnWriteArrayList<Recorded>> requests = new ConcurrentHashMap<>();
    private final AtomicInteger sequence = new AtomicInteger();

    DependencyTrackStub() throws IOException {
      server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      server.createContext("/", this::handle);
      server.start();
    }

    String baseUrl() {
      return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    void respond(String method, String path, int status, String body) {
      responses.put(key(method, path), new StubResponse(status, body));
    }

    String body(String method, String path) {
      Recorded recorded = last(method, path);
      return recorded == null ? "" : recorded.body();
    }

    String query(String method, String path) {
      Recorded recorded = last(method, path);
      return recorded == null || recorded.query() == null ? "" : recorded.query();
    }

    String header(String method, String path, String name) {
      Recorded recorded = last(method, path);
      return recorded == null
          ? null
          : recorded.headers().get(name.toLowerCase(java.util.Locale.ROOT));
    }

    String lastPath(String method) {
      return requests.entrySet().stream()
          .filter(entry -> entry.getKey().startsWith(method + " "))
          .flatMap(entry -> entry.getValue().stream())
          .reduce((first, second) -> second)
          .map(Recorded::path)
          .orElse("");
    }

    private Recorded last(String method, String path) {
      CopyOnWriteArrayList<Recorded> recorded = requests.get(key(method, path));
      return recorded == null || recorded.isEmpty() ? null : recorded.get(recorded.size() - 1);
    }

    private void handle(HttpExchange exchange) throws IOException {
      String method = exchange.getRequestMethod();
      String path = exchange.getRequestURI().getPath();
      String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
      Map<String, String> headers = new ConcurrentHashMap<>();
      exchange.getRequestHeaders().forEach((name, values) -> {
        if (!values.isEmpty()) {
          headers.put(name.toLowerCase(java.util.Locale.ROOT), values.get(0));
        }
      });
      Recorded recorded = new Recorded(
          exchange.getRequestURI().getQuery(), path, body, headers);
      requests.computeIfAbsent(key(method, path), ignored -> new CopyOnWriteArrayList<>())
          .add(recorded);
      StubResponse response = responses.get(key(method, path));
      int status = response == null ? 404 : response.status();
      byte[] payload = (response == null ? "{\"message\":\"no stub\"}" : response.body())
          .getBytes(StandardCharsets.UTF_8);
      exchange.getResponseHeaders().add("Content-Type", "application/json");
      exchange.sendResponseHeaders(status, payload.length);
      exchange.getResponseBody().write(payload);
      exchange.close();
      sequence.incrementAndGet();
    }

    private static String key(String method, String path) {
      return method + " " + path;
    }

    @Override
    public void close() {
      server.stop(0);
    }

    private record StubResponse(int status, String body) {}

    private record Recorded(
        String query, String path, String body, Map<String, String> headers) {}
  }
}
