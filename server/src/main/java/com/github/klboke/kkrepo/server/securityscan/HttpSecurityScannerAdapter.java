package com.github.klboke.kkrepo.server.securityscan;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.klboke.kkrepo.security.scan.ScanSubject;
import com.github.klboke.kkrepo.security.scan.ScannerArtifactType;
import com.github.klboke.kkrepo.security.scan.ScannerContract;
import com.github.klboke.kkrepo.security.scan.ScannerContract.Adapter;
import com.github.klboke.kkrepo.security.scan.ScannerContract.Capabilities;
import com.github.klboke.kkrepo.security.scan.ScannerContract.CatalogRequest;
import com.github.klboke.kkrepo.security.scan.ScannerContract.CatalogResponse;
import com.github.klboke.kkrepo.security.scan.ScannerContract.InputStreamSource;
import com.github.klboke.kkrepo.security.scan.ScannerContract.MatchRequest;
import com.github.klboke.kkrepo.security.scan.ScannerContract.MatchResponse;
import com.github.klboke.kkrepo.security.scan.ScannerContract.OciScanRequest;
import com.github.klboke.kkrepo.security.scan.ScannerContract.OciScanResponse;
import com.github.klboke.kkrepo.security.scan.ScannerContract.Readiness;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Locale;
import org.springframework.stereotype.Component;

/** Streaming HTTP implementation of the versioned scanner contract. */
@Component
public class HttpSecurityScannerAdapter implements Adapter {
  private final ObjectMapper objectMapper;
  private final SecurityScanningProperties properties;
  private final HttpClient client;
  private final URI baseUri;

  public HttpSecurityScannerAdapter(
      ObjectMapper objectMapper, SecurityScanningProperties properties) {
    this.objectMapper = objectMapper;
    this.properties = properties;
    this.baseUri = validateBaseUri(properties.getAdapter().getBaseUrl());
    this.client = HttpClient.newBuilder()
        .connectTimeout(properties.getAdapter().getConnectTimeout())
        .followRedirects(HttpClient.Redirect.NEVER)
        .build();
  }

  @Override
  public Capabilities capabilities() {
    return get("/v1/capabilities", Capabilities.class);
  }

  @Override
  public Readiness readiness() {
    return get("/v1/readiness", Readiness.class);
  }

  @Override
  public CatalogResponse catalog(CatalogRequest request, InputStreamSource input)
      throws IOException {
    HttpRequest.Builder builder =
        binaryRequest("/v1/catalog", request.limits().timeoutSeconds(), input)
            .header("Content-Type", contentType(request.subject().mediaType()))
            .header("X-KKRepo-API-Version", request.apiVersion())
            .header("X-KKRepo-Run-ID", request.runId())
            .header("Idempotency-Key", request.idempotencyKey())
            .header("X-KKRepo-Target", request.subject().classification().name())
            .header("X-KKRepo-Expected-SHA256", request.subject().sha256())
            .header("X-KKRepo-Expected-Size", Long.toString(request.subject().size()))
            .header("X-KKRepo-Profile-Digest", request.profileConfigurationDigest())
            .header("X-KKRepo-Max-Archive-Entries",
                Integer.toString(request.limits().maxArchiveEntries()))
            .header("X-KKRepo-Max-Uncompressed-Bytes",
                Long.toString(request.limits().maxUncompressedBytes()))
            .header("X-KKRepo-Max-Single-File-Bytes",
                Long.toString(request.limits().maxSingleFileBytes()))
            .header("X-KKRepo-Max-Nested-Depth",
                Integer.toString(request.limits().maxNestedDepth()))
            .header("X-KKRepo-Max-Input-Bytes",
                Long.toString(request.limits().maxInputBytes()))
            .header("X-KKRepo-Timeout-Seconds",
                Integer.toString(request.limits().timeoutSeconds()));
    ScannerArtifactType artifactType = artifactType(request.subject());
    builder.header("X-KKRepo-Artifact-Type", artifactType.wireValue());
    HttpRequest httpRequest = builder.build();
    return send(httpRequest, CatalogResponse.class);
  }

  @Override
  public MatchResponse match(MatchRequest request, InputStreamSource sbom)
      throws IOException {
    HttpRequest httpRequest = binaryRequest("/v1/match", request.limits().timeoutSeconds(), sbom)
        .header("Content-Type", "application/vnd.cyclonedx+json")
        .header("X-KKRepo-API-Version", request.apiVersion())
        .header("X-KKRepo-Run-ID", request.runId())
        .header("Idempotency-Key", request.idempotencyKey())
        .header("X-KKRepo-SBOM-SHA256", request.sbomSha256())
        .header("X-KKRepo-Profile-Digest", request.profileConfigurationDigest())
        .header("X-KKRepo-Max-Input-Bytes",
            Long.toString(request.limits().maxInputBytes()))
        .header("X-KKRepo-Max-Archive-Entries",
            Integer.toString(request.limits().maxArchiveEntries()))
        .header("X-KKRepo-Max-Uncompressed-Bytes",
            Long.toString(request.limits().maxUncompressedBytes()))
        .header("X-KKRepo-Max-Single-File-Bytes",
            Long.toString(request.limits().maxSingleFileBytes()))
        .header("X-KKRepo-Max-Nested-Depth",
            Integer.toString(request.limits().maxNestedDepth()))
        .header("X-KKRepo-Timeout-Seconds",
            Integer.toString(request.limits().timeoutSeconds()))
        .build();
    return send(httpRequest, MatchResponse.class);
  }

  @Override
  public OciScanResponse scanOci(OciScanRequest request) throws IOException {
    byte[] body = objectMapper.writeValueAsBytes(request);
    HttpRequest.Builder builder = HttpRequest.newBuilder(resolve("/v1/oci/scan"))
        .timeout(Duration.ofSeconds(Math.max(1, request.limits().timeoutSeconds())))
        .header("Content-Type", "application/json")
        .header("X-KKRepo-API-Version", request.apiVersion())
        .header("X-KKRepo-Run-ID", request.runId())
        .header("Idempotency-Key", request.idempotencyKey())
        .POST(HttpRequest.BodyPublishers.ofByteArray(body));
    withServiceCredential(builder);
    HttpRequest httpRequest = builder.build();
    return send(httpRequest, OciScanResponse.class);
  }

  private <T> T get(String path, Class<T> type) {
    HttpRequest.Builder builder = HttpRequest.newBuilder(resolve(path))
        .timeout(Duration.ofSeconds(15))
        .header("Accept", "application/json")
        .GET();
    withServiceCredential(builder);
    HttpRequest request = builder.build();
    return send(request, type);
  }

  private HttpRequest.Builder binaryRequest(
      String path, int timeoutSeconds, InputStreamSource source) {
    HttpRequest.Builder builder = HttpRequest.newBuilder(resolve(path))
        .timeout(Duration.ofSeconds(Math.max(1, timeoutSeconds)))
        .header("Accept", "application/json")
        .POST(HttpRequest.BodyPublishers.ofInputStream(() -> {
          try {
            return source.open();
          } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
          }
        }));
    withServiceCredential(builder);
    return builder;
  }

  private <T> T send(HttpRequest request, Class<T> type) {
    try {
      HttpResponse<InputStream> response =
          client.send(request, HttpResponse.BodyHandlers.ofInputStream());
      try (InputStream body = response.body()) {
        int status = response.statusCode();
        byte[] bytes = readBounded(body, properties.getMaxOutputBytes());
        if (status < 200 || status >= 300) {
          boolean retryable = status == 429 || status == 502 || status == 503 || status == 504;
          throw new ScannerAdapterException(
              "SCANNER_HTTP_" + status,
              "Scanner adapter returned HTTP " + status,
              retryable);
        }
        try {
          return objectMapper.readValue(bytes, type);
        } catch (IOException e) {
          throw new ScannerAdapterException(
              "SCANNER_INVALID_JSON", "Scanner adapter returned invalid JSON", false, e);
        }
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new ScannerAdapterException(
          "SCANNER_INTERRUPTED", "Scanner request was interrupted", true, e);
    } catch (java.net.http.HttpTimeoutException e) {
      throw new ScannerAdapterException(
          "SCANNER_TIMEOUT", "Scanner request timed out", true, e);
    } catch (IOException e) {
      throw new ScannerAdapterException(
          "SCANNER_IO", "Scanner adapter is unavailable", true, e);
    }
  }

  static byte[] readBounded(InputStream input, long maxBytes) throws IOException {
    int limit = (int) Math.min(Integer.MAX_VALUE - 1L, Math.max(1, maxBytes));
    byte[] bytes = input.readNBytes(limit + 1);
    if (bytes.length > limit) {
      throw new ScannerAdapterException(
          "SCANNER_REPORT_TOO_LARGE", "Scanner response exceeded configured limit", false);
    }
    return bytes;
  }

  private URI resolve(String path) {
    return baseUri.resolve(path.startsWith("/") ? path.substring(1) : path);
  }

  private static URI validateBaseUri(String raw) {
    URI uri = URI.create(raw.endsWith("/") ? raw : raw + "/");
    String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
    if (!("http".equals(scheme) || "https".equals(scheme))
        || uri.getHost() == null
        || uri.getUserInfo() != null
        || uri.getQuery() != null
        || uri.getFragment() != null) {
      throw new IllegalArgumentException("Invalid security scanner adapter base URL");
    }
    return uri;
  }

  private static String contentType(String value) {
    return value == null || value.isBlank() ? "application/octet-stream" : value;
  }

  static ScannerArtifactType artifactType(ScanSubject subject) {
    Object pathValue = subject == null || subject.attributes() == null
        ? null : subject.attributes().get("path");
    return pathValue instanceof String path
        ? ScannerArtifactType.fromPath(path) : ScannerArtifactType.UNKNOWN;
  }

  private void withServiceCredential(HttpRequest.Builder builder) {
    String value = properties.getAdapter().getServiceCredential();
    if (value != null && !value.isBlank()) {
      builder.header("X-KKRepo-Scanner-Credential", value);
    }
  }
}
