package com.github.klboke.kkrepo.server.securityscan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.klboke.kkrepo.security.scan.ScanEnums.SubjectKind;
import com.github.klboke.kkrepo.security.scan.ScanEnums.ScanCompleteness;
import com.github.klboke.kkrepo.security.scan.ScanEnums.TargetClassification;
import com.github.klboke.kkrepo.security.scan.ScanSubject;
import com.github.klboke.kkrepo.security.scan.ScannerArtifactType;
import com.github.klboke.kkrepo.security.scan.ScannerContract.Capabilities;
import com.github.klboke.kkrepo.security.scan.ScannerContract.CatalogRequest;
import com.github.klboke.kkrepo.security.scan.ScannerContract.CatalogResponse;
import com.github.klboke.kkrepo.security.scan.ScannerContract.CancellationResponse;
import com.github.klboke.kkrepo.security.scan.ScannerContract.MatchRequest;
import com.github.klboke.kkrepo.security.scan.ScannerContract.MatchResponse;
import com.github.klboke.kkrepo.security.scan.ScannerContract.OciScanRequest;
import com.github.klboke.kkrepo.security.scan.ScannerContract.OciScanResponse;
import com.github.klboke.kkrepo.security.scan.ScannerContract.Readiness;
import com.github.klboke.kkrepo.security.scan.ScannerContract.ResourceLimits;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class HttpSecurityScannerAdapterTest {
  private HttpServer server;

  @AfterEach
  void stopServer() {
    if (server != null) server.stop(0);
  }

  @ParameterizedTest
  @CsvSource({
      "org/acme/demo/1.0/demo-1.0.jar,JAR",
      "packages/demo-1.0.0.tar.gz,TAR_GZ",
      "flat/demo/1.0.0/demo.1.0.0.NUPKG,NUPKG",
      "release/no-extension,UNKNOWN",
      "release/invalid.jar?download=true,UNKNOWN"
  })
  void derivesOnlyClosedArtifactTypes(String path, ScannerArtifactType expected) {
    ScanSubject subject = new ScanSubject(
        SubjectKind.ASSET_BLOB,
        1L,
        2L,
        3L,
        "sha256:" + "a".repeat(64),
        "a".repeat(64),
        42L,
        "MAVEN2",
        "artifact",
        "application/octet-stream",
        TargetClassification.ARCHIVE,
        List.of(),
        Map.of("path", path));

    assertEquals(expected, HttpSecurityScannerAdapter.artifactType(subject));
  }

  @Test
  void leavesOnlyBoundedTransportGraceAroundTheScannerDeadline() {
    assertEquals(Duration.ofSeconds(35), HttpSecurityScannerAdapter.requestTimeout(30));
    assertEquals(Duration.ofSeconds(6), HttpSecurityScannerAdapter.requestTimeout(0));
  }

  @Test
  void requiresTheSharedCredentialWheneverScanningIsEnabled() {
    SecurityScanningProperties properties = new SecurityScanningProperties();
    properties.setEnabled(true);
    HttpSecurityScannerAdapter adapter =
        new HttpSecurityScannerAdapter(new ObjectMapper(), properties);

    IllegalStateException failure =
        assertThrows(IllegalStateException.class, adapter::validateConfiguration);

    assertTrue(failure.getMessage().contains("service-credential"));
    properties.setEnabled(false);
    adapter.validateConfiguration();
  }

  @Test
  void routesEveryOperationForOneRunToTheSameStableReplica() {
    SecurityScanningProperties properties = new SecurityScanningProperties();
    properties.getAdapter().setBaseUrls(List.of(
        "http://scanner-0.scanner-headless:8080",
        "http://scanner-1.scanner-headless:8080"));
    HttpSecurityScannerAdapter adapter =
        new HttpSecurityScannerAdapter(new ObjectMapper(), properties);

    URI first = adapter.baseUriForRun("run-a");

    assertEquals(first, adapter.baseUriForRun("run-a"));
    assertNotEquals(first, adapter.baseUriForRun("run-b"));
    assertNotEquals(
        HttpSecurityScannerAdapter.routeIndex("run-a", 2),
        HttpSecurityScannerAdapter.routeIndex("run-b", 2));
  }

  @Test
  void broadcastsCancellationUntilTheOwningReplicaConfirmsIt() throws Exception {
    ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    AtomicInteger firstCalls = new AtomicInteger();
    AtomicInteger secondCalls = new AtomicInteger();
    HttpServer first = standaloneServer(exchange -> {
      firstCalls.incrementAndGet();
      respond(
          exchange,
          200,
          mapper.writeValueAsBytes(new CancellationResponse("run", false)));
    });
    HttpServer second = standaloneServer(exchange -> {
      secondCalls.incrementAndGet();
      respond(
          exchange,
          200,
          mapper.writeValueAsBytes(new CancellationResponse("run", true)));
    });
    try {
      SecurityScanningProperties properties = new SecurityScanningProperties();
      properties.getAdapter().setBaseUrls(List.of(
          "http://127.0.0.1:" + first.getAddress().getPort(),
          "http://127.0.0.1:" + second.getAddress().getPort()));
      properties.getAdapter().setServiceCredential("secret");
      HttpSecurityScannerAdapter adapter =
          new HttpSecurityScannerAdapter(mapper, properties);

      assertTrue(adapter.cancel("run").cancelled());
      assertEquals(1, firstCalls.get());
      assertEquals(1, secondCalls.get());
    } finally {
      first.stop(0);
      second.stop(0);
    }
  }

  @Test
  void streamsEveryContractOperationAndSendsOnlyBoundedHeaders() throws Exception {
    ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    Map<String, Capture> captures = new LinkedHashMap<>();
    Map<String, Object> responses = Map.of(
        "/v1/capabilities",
        new Capabilities(
            "v1", "adapter", "1", List.of("CATALOG"), List.of("PACKAGE"),
            4096, 4096, "cap"),
        "/v1/readiness",
        new Readiness(
            true, "READY", "grype", "1", "db", Instant.EPOCH, Instant.EPOCH, Map.of()),
        "/v1/catalog",
        catalog(),
        "/v1/match",
        match(),
        "/v1/oci/scan",
        new OciScanResponse(catalog(), match(), List.of("linux/amd64"), List.of()),
        "/v1/runs/run/cancel",
        new CancellationResponse("run", true));
    start(exchange -> {
      captures.put(exchange.getRequestURI().getPath(), new Capture(
          exchange.getRequestHeaders().getFirst("X-KKRepo-Artifact-Type"),
          exchange.getRequestHeaders().getFirst("X-KKRepo-Scanner-Credential"),
          exchange.getRequestBody().readAllBytes()));
      respond(exchange, 200, mapper.writeValueAsBytes(
          responses.get(exchange.getRequestURI().getPath())));
    });
    HttpSecurityScannerAdapter adapter = adapter(mapper, 64 * 1024);
    ResourceLimits limits = limits();
    ScanSubject subject = subject("org/acme/demo/1/demo-1.jar");

    assertEquals("adapter", adapter.capabilities().adapterName());
    assertTrue(adapter.readiness().ready());
    CatalogResponse catalog = adapter.catalog(
        new CatalogRequest("v1", "run", "catalog-key", subject, "config", limits),
        () -> new ByteArrayInputStream("artifact".getBytes()));
    MatchResponse match = adapter.match(
        new MatchRequest("v1", "run", "match-key", "b".repeat(64), "config", limits),
        () -> new ByteArrayInputStream("{}".getBytes()));
    OciScanRequest ociRequest = new OciScanRequest(
        "v1", "run", "oci-key", "https://registry", "repo/image",
        "sha256:" + "a".repeat(64), List.of("linux/amd64"), "token", "config", limits);

    assertEquals("CycloneDX", catalog.specName());
    assertEquals("db", match.vulnerabilityDatabaseRevision());
    assertEquals(List.of("linux/amd64"), adapter.scanOci(ociRequest).scannedPlatforms());
    assertTrue(adapter.cancel("run").cancelled());
    assertEquals("JAR", captures.get("/v1/catalog").artifactType());
    assertEquals("secret", captures.get("/v1/catalog").credential());
    assertEquals("artifact", new String(captures.get("/v1/catalog").body()));
    assertEquals("{}", new String(captures.get("/v1/match").body()));
    assertTrue(captures.get("/v1/oci/scan").body().length > 0);
  }

  @Test
  void classifiesHttpJsonTransportAndBoundedResponseFailures() throws Exception {
    ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    start(exchange -> respond(exchange, 503, "{}".getBytes()));
    ScannerAdapterException unavailable = assertThrows(
        ScannerAdapterException.class,
        () -> adapter(mapper, 4096).capabilities());
    assertEquals("SCANNER_HTTP_503", unavailable.code());
    assertTrue(unavailable.retryable());
    stopServer();
    server = null;

    start(exchange -> respond(exchange, 400, "{}".getBytes()));
    ScannerAdapterException rejected = assertThrows(
        ScannerAdapterException.class,
        () -> adapter(mapper, 4096).readiness());
    assertEquals("SCANNER_HTTP_400", rejected.code());
    assertFalse(rejected.retryable());
    stopServer();
    server = null;

    start(exchange -> respond(exchange, 200, "not-json".getBytes()));
    ScannerAdapterException invalidJson = assertThrows(
        ScannerAdapterException.class,
        () -> adapter(mapper, 4096).capabilities());
    assertEquals("SCANNER_INVALID_JSON", invalidJson.code());
    stopServer();
    server = null;

    start(exchange -> respond(exchange, 200, new byte[1025]));
    ScannerAdapterException tooLarge = assertThrows(
        ScannerAdapterException.class,
        () -> adapter(mapper, 1024).capabilities());
    assertEquals("SCANNER_REPORT_TOO_LARGE", tooLarge.code());
  }

  @Test
  void validatesBaseUrlsAndTranslatesInputAndConnectionFailures() throws Exception {
    ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    for (String invalid : List.of(
        "file:///tmp/scanner",
        "http://user@localhost",
        "http://localhost?query=1",
        "http://localhost#fragment")) {
      SecurityScanningProperties properties = new SecurityScanningProperties();
      properties.getAdapter().setBaseUrl(invalid);
      assertThrows(
          IllegalArgumentException.class,
          () -> new HttpSecurityScannerAdapter(mapper, properties));
    }

    start(exchange -> respond(exchange, 200, mapper.writeValueAsBytes(catalog())));
    HttpSecurityScannerAdapter adapter = adapter(mapper, 4096);
    ScannerAdapterException inputFailure = assertThrows(
        ScannerAdapterException.class,
        () -> adapter.catalog(
            new CatalogRequest(
                "v1", "run", "key", subject(null), "config", limits()),
            () -> {
              throw new IOException("cannot open");
            }));
    assertEquals("SCANNER_IO", inputFailure.code());
    assertTrue(inputFailure.retryable());
    stopServer();
    server = null;

    SecurityScanningProperties unavailable = new SecurityScanningProperties();
    unavailable.getAdapter().setBaseUrl("http://127.0.0.1:1/");
    ScannerAdapterException connectionFailure = assertThrows(
        ScannerAdapterException.class,
        () -> new HttpSecurityScannerAdapter(mapper, unavailable).readiness());
    assertEquals("SCANNER_IO", connectionFailure.code());
  }

  private HttpSecurityScannerAdapter adapter(ObjectMapper mapper, long maxOutputBytes) {
    SecurityScanningProperties properties = new SecurityScanningProperties();
    properties.getAdapter().setBaseUrl(
        "http://127.0.0.1:" + server.getAddress().getPort() + "/");
    properties.getAdapter().setServiceCredential("secret");
    properties.setMaxOutputBytes(maxOutputBytes);
    return new HttpSecurityScannerAdapter(mapper, properties);
  }

  private void start(Handler handler) throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/", exchange -> {
      try {
        handler.handle(exchange);
      } finally {
        exchange.close();
      }
    });
    server.start();
  }

  private static HttpServer standaloneServer(Handler handler) throws IOException {
    HttpServer value = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    value.createContext("/", exchange -> {
      try {
        handler.handle(exchange);
      } finally {
        exchange.close();
      }
    });
    value.start();
    return value;
  }

  private static void respond(HttpExchange exchange, int status, byte[] body)
      throws IOException {
    exchange.getResponseHeaders().set("Content-Type", "application/json");
    exchange.sendResponseHeaders(status, body.length);
    exchange.getResponseBody().write(body);
  }

  private static ResourceLimits limits() {
    return new ResourceLimits(4096, 100, 8192, 4096, 2, 30);
  }

  private static ScanSubject subject(String path) {
    Map<String, Object> attributes = path == null ? Map.of() : Map.of("path", path);
    return new ScanSubject(
        SubjectKind.ASSET_BLOB, 1, 2L, 3L, "sha256:" + "a".repeat(64),
        "a".repeat(64), 8, "MAVEN2", "artifact", null,
        TargetClassification.ARCHIVE, List.of(), attributes);
  }

  private static CatalogResponse catalog() {
    return new CatalogResponse(
        "adapter", "1", "syft", "1", "cap", "a".repeat(64),
        ScanCompleteness.COMPLETE, "CycloneDX", "1.5", 0, 0,
        "{\"bomFormat\":\"CycloneDX\"}".getBytes(), List.of(), Map.of());
  }

  private static MatchResponse match() {
    return new MatchResponse(
        "adapter", "1", "grype", "1", "db", Instant.EPOCH, "cap",
        ScanCompleteness.COMPLETE, "{}".getBytes(), List.of(), Map.of());
  }

  private record Capture(String artifactType, String credential, byte[] body) {}

  @FunctionalInterface
  private interface Handler {
    void handle(HttpExchange exchange) throws IOException;
  }
}
