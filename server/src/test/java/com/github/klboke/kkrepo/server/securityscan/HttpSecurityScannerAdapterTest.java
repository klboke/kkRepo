package com.github.klboke.kkrepo.server.securityscan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.klboke.kkrepo.security.scan.ScanEnums.SubjectKind;
import com.github.klboke.kkrepo.security.scan.ScanEnums.ScanCompleteness;
import com.github.klboke.kkrepo.security.scan.ScanEnums.TargetClassification;
import com.github.klboke.kkrepo.security.scan.ScanSubject;
import com.github.klboke.kkrepo.security.scan.ScannerArtifactType;
import com.github.klboke.kkrepo.security.scan.ScannerContract;
import com.github.klboke.kkrepo.security.scan.ScannerContract.Capabilities;
import com.github.klboke.kkrepo.security.scan.ScannerContract.CatalogRequest;
import com.github.klboke.kkrepo.security.scan.ScannerContract.CatalogResponse;
import com.github.klboke.kkrepo.security.scan.ScannerContract.CancellationResponse;
import com.github.klboke.kkrepo.security.scan.ScannerContract.Component;
import com.github.klboke.kkrepo.security.scan.ScannerContract.MatchRequest;
import com.github.klboke.kkrepo.security.scan.ScannerContract.MatchResponse;
import com.github.klboke.kkrepo.security.scan.ScannerContract.Observation;
import com.github.klboke.kkrepo.security.scan.ScannerContract.OciScanRequest;
import com.github.klboke.kkrepo.security.scan.ScannerContract.OciScanResponse;
import com.github.klboke.kkrepo.security.scan.ScannerContract.Readiness;
import com.github.klboke.kkrepo.security.scan.ScannerContract.ResourceLimits;
import com.github.klboke.kkrepo.security.scan.ScannerContract.SnapshotExpectation;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
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
  void failoverDeadlineDerivesEveryAttemptFromOneMonotonicBudget() {
    AtomicLong clock = new AtomicLong();
    HttpSecurityScannerAdapter.FailoverDeadline deadline =
        new HttpSecurityScannerAdapter.FailoverDeadline(3, clock::get);

    HttpSecurityScannerAdapter.AttemptBudget initial = deadline.nextAttempt();
    assertEquals(Duration.ofSeconds(8), initial.transportTimeout());
    assertEquals(3, initial.scannerTimeoutSeconds());
    assertEquals(Duration.ofSeconds(5), deadline.remainingCancellationTimeout());

    clock.set(Duration.ofMillis(1_200).toNanos());
    HttpSecurityScannerAdapter.AttemptBudget fallback = deadline.nextAttempt();
    assertEquals(Duration.ofMillis(6_800), fallback.transportTimeout());
    assertEquals(2, fallback.scannerTimeoutSeconds());

    clock.set(Duration.ofSeconds(8).toNanos());
    ScannerAdapterException failure =
        assertThrows(ScannerAdapterException.class, deadline::nextAttempt);
    assertEquals("SCANNER_TIMEOUT", failure.code());
    assertNull(deadline.remainingCancellationTimeout());
  }

  @Test
  void transportDeadlineDerivesEveryRequestFromOneMonotonicBudget() {
    AtomicLong clock = new AtomicLong();
    HttpSecurityScannerAdapter.TransportDeadline deadline =
        new HttpSecurityScannerAdapter.TransportDeadline(
            Duration.ofSeconds(3), clock::get);

    assertEquals(Duration.ofSeconds(3), deadline.nextRequestTimeout());
    clock.set(Duration.ofMillis(1_200).toNanos());
    assertEquals(Duration.ofMillis(1_800), deadline.nextRequestTimeout());
    assertFalse(deadline.expired());

    clock.set(Duration.ofSeconds(3).toNanos());
    ScannerAdapterException failure = assertThrows(
        ScannerAdapterException.class, deadline::nextRequestTimeout);
    assertEquals("SCANNER_TIMEOUT", failure.code());
    assertTrue(deadline.expired());
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
  void usesAStablePreferredReplicaForEachRun() {
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
  void broadcastsCancellationToEveryConfiguredReplica() throws Exception {
    ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    AtomicInteger firstCalls = new AtomicInteger();
    AtomicInteger secondCalls = new AtomicInteger();
    HttpServer first = standaloneServer(exchange -> {
      firstCalls.incrementAndGet();
      respond(
          exchange,
          200,
          mapper.writeValueAsBytes(new CancellationResponse("run", true)));
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
  void broadcastsCancellationInParallelWithinOneOverallDeadline() throws Exception {
    ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    AtomicInteger calls = new AtomicInteger();
    List<HttpServer> replicas = new ArrayList<>();
    try {
      for (int index = 0; index < 3; index++) {
        replicas.add(standaloneServer(exchange -> {
          calls.incrementAndGet();
          java.util.concurrent.locks.LockSupport.parkNanos(
              Duration.ofMillis(800).toNanos());
          respond(
              exchange,
              200,
              mapper.writeValueAsBytes(new CancellationResponse("run", true)));
        }));
      }
      SecurityScanningProperties properties = new SecurityScanningProperties();
      properties.getAdapter().setBaseUrls(replicas.stream()
          .map(replica -> "http://127.0.0.1:" + replica.getAddress().getPort())
          .toList());
      properties.getAdapter().setServiceCredential("secret");
      HttpSecurityScannerAdapter adapter =
          new HttpSecurityScannerAdapter(mapper, properties);

      long startedNanos = System.nanoTime();
      assertTrue(adapter.cancel("run").cancelled());
      long elapsedMillis = Duration.ofNanos(System.nanoTime() - startedNanos).toMillis();

      assertEquals(3, calls.get());
      assertTrue(
          elapsedMillis < 1_800,
          "cancellation took " + elapsedMillis
              + " ms, which indicates serial per-replica waiting");
    } finally {
      replicas.forEach(replica -> replica.stop(0));
    }
  }

  @Test
  void cancellationTimesOutOnceForTheWholeBroadcast() throws Exception {
    ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    List<HttpServer> replicas = new ArrayList<>();
    try {
      for (int index = 0; index < 3; index++) {
        replicas.add(standaloneServer(exchange -> {
          java.util.concurrent.locks.LockSupport.parkNanos(
              Duration.ofSeconds(1).toNanos());
          respond(
              exchange,
              200,
              mapper.writeValueAsBytes(new CancellationResponse("run", true)));
        }));
      }
      SecurityScanningProperties properties = new SecurityScanningProperties();
      properties.getAdapter().setBaseUrls(replicas.stream()
          .map(replica -> "http://127.0.0.1:" + replica.getAddress().getPort())
          .toList());
      properties.getAdapter().setServiceCredential("secret");
      HttpSecurityScannerAdapter adapter =
          new HttpSecurityScannerAdapter(mapper, properties);

      long startedNanos = System.nanoTime();
      ScannerAdapterException failure = assertThrows(
          ScannerAdapterException.class,
          () -> adapter.cancel("run", Duration.ofMillis(250)));
      long elapsedMillis = Duration.ofNanos(System.nanoTime() - startedNanos).toMillis();

      assertEquals("SCANNER_TIMEOUT", failure.code());
      assertTrue(
          elapsedMillis < 1_000,
          "cancellation took " + elapsedMillis
              + " ms instead of respecting its shared 250 ms deadline");
    } finally {
      replicas.forEach(replica -> replica.stop(0));
    }
  }

  @Test
  void observesCapabilitiesAndReadinessFromAnotherHealthyReplica() throws Exception {
    ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    HttpServer unavailable = standaloneServer(exchange ->
        respond(exchange, 503, "{}".getBytes()));
    AtomicInteger capabilitiesCalls = new AtomicInteger();
    AtomicInteger readinessCalls = new AtomicInteger();
    HttpServer healthy = standaloneServer(exchange -> {
      String path = exchange.getRequestURI().getPath();
      if ("/v1/capabilities".equals(path)) {
        capabilitiesCalls.incrementAndGet();
        respond(exchange, 200, mapper.writeValueAsBytes(new Capabilities(
            "v1", "adapter", "1", List.of("CATALOG"), List.of("PACKAGE"),
            4096, 4096, "cap")));
      } else {
        readinessCalls.incrementAndGet();
        respond(exchange, 200, mapper.writeValueAsBytes(new Readiness(
            true, "READY", "grype", "1", "db", Instant.EPOCH, Instant.EPOCH, Map.of())));
      }
    });
    try {
      SecurityScanningProperties properties = new SecurityScanningProperties();
      properties.getAdapter().setBaseUrls(List.of(
          "http://127.0.0.1:" + unavailable.getAddress().getPort(),
          "http://127.0.0.1:" + healthy.getAddress().getPort()));
      properties.getAdapter().setServiceCredential("secret");
      HttpSecurityScannerAdapter adapter =
          new HttpSecurityScannerAdapter(mapper, properties);

      assertEquals("adapter", adapter.capabilities().adapterName());
      assertTrue(adapter.readiness().ready());
      assertEquals(1, capabilitiesCalls.get());
      assertEquals(1, readinessCalls.get());
    } finally {
      unavailable.stop(0);
      healthy.stop(0);
    }
  }

  @Test
  void observationSharesOneDeadlineAcrossEveryReplicaAndEndpoint() throws Exception {
    ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    AtomicInteger calls = new AtomicInteger();
    List<HttpServer> replicas = new ArrayList<>();
    try {
      for (int index = 0; index < 3; index++) {
        replicas.add(standaloneServer(exchange -> {
          calls.incrementAndGet();
          java.util.concurrent.locks.LockSupport.parkNanos(
              Duration.ofMillis(750).toNanos());
          respond(exchange, 503, "{}".getBytes());
        }));
      }
      SecurityScanningProperties properties = new SecurityScanningProperties();
      properties.getAdapter().setBaseUrls(replicas.stream()
          .map(replica -> "http://127.0.0.1:" + replica.getAddress().getPort())
          .toList());
      properties.getAdapter().setServiceCredential("secret");
      HttpSecurityScannerAdapter adapter =
          new HttpSecurityScannerAdapter(mapper, properties);

      long startedNanos = System.nanoTime();
      ScannerAdapterException failure = assertThrows(
          ScannerAdapterException.class,
          () -> adapter.observation(Duration.ofSeconds(1)));
      long elapsedMillis = Duration.ofNanos(System.nanoTime() - startedNanos).toMillis();

      assertEquals("SCANNER_TIMEOUT", failure.code());
      assertTrue(calls.get() >= 2, "observation should try a fallback within its remaining budget");
      assertTrue(
          elapsedMillis < 1_800,
          "observation took " + elapsedMillis
              + " ms instead of respecting its shared one-second deadline");
    } finally {
      replicas.forEach(replica -> replica.stop(0));
    }
  }

  @Test
  void observesCapabilitiesAndReadinessFromTheSameReadyReplica() throws Exception {
    ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    HttpServer rolling = standaloneServer(exchange -> {
      if ("/v1/capabilities".equals(exchange.getRequestURI().getPath())) {
        respond(exchange, 200, mapper.writeValueAsBytes(new Capabilities(
            "v1", "rolling-adapter", "1", List.of("CATALOG"), List.of("PACKAGE"),
            4096, 4096, "rolling-capability")));
      } else {
        respond(exchange, 200, mapper.writeValueAsBytes(new Readiness(
            false, "STARTING", "grype", "1", "old-db",
            Instant.EPOCH, Instant.EPOCH, Map.of())));
      }
    });
    HttpServer ready = standaloneServer(exchange -> {
      if ("/v1/capabilities".equals(exchange.getRequestURI().getPath())) {
        respond(exchange, 200, mapper.writeValueAsBytes(new Capabilities(
            "v1", "ready-adapter", "2", List.of("CATALOG", "MATCH"),
            List.of("PACKAGE"), 8192, 8192, "ready-capability")));
      } else {
        respond(exchange, 200, mapper.writeValueAsBytes(new Readiness(
            true, "READY", "grype", "2", "ready-db",
            Instant.EPOCH, Instant.EPOCH, Map.of())));
      }
    });
    try {
      SecurityScanningProperties properties = new SecurityScanningProperties();
      properties.getAdapter().setBaseUrls(List.of(
          "http://127.0.0.1:" + rolling.getAddress().getPort(),
          "http://127.0.0.1:" + ready.getAddress().getPort()));
      properties.getAdapter().setServiceCredential("secret");

      Observation observation =
          new HttpSecurityScannerAdapter(mapper, properties).observation();

      assertEquals("ready-adapter", observation.capabilities().adapterName());
      assertEquals("ready-capability", observation.capabilities().capabilityDigest());
      assertEquals("ready-db", observation.readiness().vulnerabilityDatabaseRevision());
      assertTrue(observation.readiness().ready());
    } finally {
      rolling.stop(0);
      ready.stop(0);
    }
  }

  @Test
  void skipsAReachableButNotReadyReplica() throws Exception {
    ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    AtomicInteger firstReadinessCalls = new AtomicInteger();
    HttpServer rolling = standaloneServer(exchange -> {
      firstReadinessCalls.incrementAndGet();
      respond(exchange, 200, mapper.writeValueAsBytes(new Readiness(
          false, "STARTING", "grype", "1", null, null, Instant.EPOCH, Map.of())));
    });
    HttpServer ready = standaloneServer(exchange ->
        respond(exchange, 200, mapper.writeValueAsBytes(new Readiness(
            true, "READY", "grype", "1", "db", Instant.EPOCH, Instant.EPOCH, Map.of()))));
    try {
      SecurityScanningProperties properties = new SecurityScanningProperties();
      properties.getAdapter().setBaseUrls(List.of(
          "http://127.0.0.1:" + rolling.getAddress().getPort(),
          "http://127.0.0.1:" + ready.getAddress().getPort()));
      properties.getAdapter().setServiceCredential("secret");

      assertTrue(new HttpSecurityScannerAdapter(mapper, properties).readiness().ready());
      assertEquals(1, firstReadinessCalls.get());
    } finally {
      rolling.stop(0);
      ready.stop(0);
    }
  }

  @Test
  void failsEveryExecutionOperationOverFromItsHashedReplica() throws Exception {
    ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    AtomicInteger primaryCalls = new AtomicInteger();
    AtomicInteger primaryCancellationCalls = new AtomicInteger();
    AtomicInteger fallbackCalls = new AtomicInteger();
    AtomicInteger inputOpens = new AtomicInteger();
    HttpServer unavailable = standaloneServer(exchange -> {
      if (exchange.getRequestURI().getPath().endsWith("/cancel")) {
        primaryCancellationCalls.incrementAndGet();
        respond(
            exchange,
            200,
            mapper.writeValueAsBytes(new CancellationResponse("ignored", false)));
        return;
      }
      primaryCalls.incrementAndGet();
      exchange.getRequestBody().readAllBytes();
      respond(exchange, 503, "{}".getBytes());
    });
    HttpServer healthy = standaloneServer(exchange -> {
      fallbackCalls.incrementAndGet();
      exchange.getRequestBody().readAllBytes();
      Object response = switch (exchange.getRequestURI().getPath()) {
        case "/v1/catalog" -> catalog();
        case "/v1/match" -> match();
        case "/v1/oci/scan" ->
            new OciScanResponse(catalog(), match(), List.of("linux/amd64"), List.of());
        default -> throw new IllegalStateException("Unexpected scanner path");
      };
      respond(exchange, 200, mapper.writeValueAsBytes(response));
    });
    try {
      SecurityScanningProperties properties = new SecurityScanningProperties();
      properties.getAdapter().setBaseUrls(List.of(
          "http://127.0.0.1:" + unavailable.getAddress().getPort(),
          "http://127.0.0.1:" + healthy.getAddress().getPort()));
      properties.getAdapter().setServiceCredential("secret");
      HttpSecurityScannerAdapter adapter =
          new HttpSecurityScannerAdapter(mapper, properties);
      String runId = java.util.stream.IntStream.range(0, 100)
          .mapToObj(value -> "failover-" + value)
          .filter(value -> HttpSecurityScannerAdapter.routeIndex(value, 2) == 0)
          .findFirst()
          .orElseThrow();
      ResourceLimits limits = limits();

      assertEquals(
          "CycloneDX",
          adapter.catalog(
              new CatalogRequest(
                  "v1", runId, "catalog-key", subject(null), "config", limits),
              () -> {
                inputOpens.incrementAndGet();
                return new ByteArrayInputStream("artifact".getBytes());
              }).specName());
      assertEquals(
          "db",
          adapter.match(
              new MatchRequest(
                  "v1", runId, "match-key", "b".repeat(64), "config", limits),
              () -> {
                inputOpens.incrementAndGet();
                return new ByteArrayInputStream("{}".getBytes());
              }).vulnerabilityDatabaseRevision());
      assertEquals(
          List.of("linux/amd64"),
          adapter.scanOci(new OciScanRequest(
              "v1",
              runId,
              "oci-key",
              "https://registry",
              "repo/image",
              "sha256:" + "a".repeat(64),
              List.of("linux/amd64"),
              "token",
              "config",
              limits)).scannedPlatforms());

      assertEquals(3, primaryCalls.get());
      assertEquals(3, primaryCancellationCalls.get());
      assertEquals(3, fallbackCalls.get());
      assertEquals(4, inputOpens.get(), "binary request bodies must be reopened for failover");
    } finally {
      unavailable.stop(0);
      healthy.stop(0);
    }
  }

  @Test
  void sharesOneScannerDeadlineAcrossFailoverAttempts() throws Exception {
    ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    AtomicInteger fallbackTimeoutSeconds = new AtomicInteger();
    HttpServer slowPrimary = standaloneServer(exchange -> {
      if (exchange.getRequestURI().getPath().endsWith("/cancel")) {
        respond(
            exchange,
            200,
            mapper.writeValueAsBytes(new CancellationResponse("deadline-run", true)));
        return;
      }
      exchange.getRequestBody().readAllBytes();
      java.util.concurrent.locks.LockSupport.parkNanos(Duration.ofMillis(1_200).toNanos());
      respond(exchange, 503, "{}".getBytes());
    });
    HttpServer fallback = standaloneServer(exchange -> {
      fallbackTimeoutSeconds.set(Integer.parseInt(
          exchange.getRequestHeaders().getFirst("X-KKRepo-Timeout-Seconds")));
      exchange.getRequestBody().readAllBytes();
      respond(exchange, 200, mapper.writeValueAsBytes(catalog()));
    });
    try {
      SecurityScanningProperties properties = new SecurityScanningProperties();
      properties.getAdapter().setBaseUrls(List.of(
          "http://127.0.0.1:" + slowPrimary.getAddress().getPort(),
          "http://127.0.0.1:" + fallback.getAddress().getPort()));
      properties.getAdapter().setServiceCredential("secret");
      HttpSecurityScannerAdapter adapter =
          new HttpSecurityScannerAdapter(mapper, properties);
      String runId = java.util.stream.IntStream.range(0, 100)
          .mapToObj(value -> "deadline-" + value)
          .filter(value -> HttpSecurityScannerAdapter.routeIndex(value, 2) == 0)
          .findFirst()
          .orElseThrow();
      ResourceLimits limits = new ResourceLimits(4096, 100, 8192, 4096, 2, 3);

      assertEquals(
          "CycloneDX",
          adapter.catalog(
              new CatalogRequest(
                  "v1", runId, "catalog-key", subject(null), "config", limits),
              () -> new ByteArrayInputStream("artifact".getBytes())).specName());

      assertTrue(fallbackTimeoutSeconds.get() > 0);
      assertTrue(
          fallbackTimeoutSeconds.get() < limits.timeoutSeconds(),
          "the fallback must receive only the scanner time left by the primary");
    } finally {
      slowPrimary.stop(0);
      fallback.stop(0);
    }
  }

  @Test
  void failsMatchAndOciOverUntilAReplicaHasTheRequestedSnapshot() throws Exception {
    ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    AtomicInteger mismatchedCalls = new AtomicInteger();
    AtomicInteger cancellationCalls = new AtomicInteger();
    AtomicInteger matchingCalls = new AtomicInteger();
    HttpServer mismatched = standaloneServer(exchange -> {
      String path = exchange.getRequestURI().getPath();
      if (path.endsWith("/cancel")) {
        cancellationCalls.incrementAndGet();
        respond(exchange, 200, mapper.writeValueAsBytes(
            new CancellationResponse("snapshot-run", true)));
        return;
      }
      mismatchedCalls.incrementAndGet();
      exchange.getRequestBody().readAllBytes();
      Object response = "/v1/oci/scan".equals(path)
          ? new OciScanResponse(catalog(), match("db-other"), List.of(), List.of())
          : match("db-other");
      respond(exchange, 200, mapper.writeValueAsBytes(response));
    });
    HttpServer matching = standaloneServer(exchange -> {
      matchingCalls.incrementAndGet();
      exchange.getRequestBody().readAllBytes();
      Object response = "/v1/oci/scan".equals(exchange.getRequestURI().getPath())
          ? new OciScanResponse(catalog(), match("db"), List.of(), List.of())
          : match("db");
      respond(exchange, 200, mapper.writeValueAsBytes(response));
    });
    try {
      SecurityScanningProperties properties = new SecurityScanningProperties();
      properties.getAdapter().setBaseUrls(List.of(
          "http://127.0.0.1:" + mismatched.getAddress().getPort(),
          "http://127.0.0.1:" + matching.getAddress().getPort()));
      properties.getAdapter().setServiceCredential("secret");
      HttpSecurityScannerAdapter adapter =
          new HttpSecurityScannerAdapter(mapper, properties);
      String runId = java.util.stream.IntStream.range(0, 100)
          .mapToObj(value -> "snapshot-" + value)
          .filter(value -> HttpSecurityScannerAdapter.routeIndex(value, 2) == 0)
          .findFirst()
          .orElseThrow();
      SnapshotExpectation expected =
          new SnapshotExpectation("adapter", "grype", "1", "db", "cap");

      MatchResponse match = adapter.match(
          new MatchRequest(
              "v1", runId, "match-key", "b".repeat(64), "config", limits(), expected),
          () -> new ByteArrayInputStream("{}".getBytes()));
      OciScanResponse oci = adapter.scanOci(new OciScanRequest(
          "v1",
          runId,
          "oci-key",
          "https://registry",
          "repo/image",
          "sha256:" + "a".repeat(64),
          List.of("linux/amd64"),
          "token",
          "config",
          limits(),
          expected));

      assertEquals("db", match.vulnerabilityDatabaseRevision());
      assertEquals("db", oci.match().vulnerabilityDatabaseRevision());
      assertEquals(2, mismatchedCalls.get());
      assertEquals(2, cancellationCalls.get());
      assertEquals(2, matchingCalls.get());
    } finally {
      mismatched.stop(0);
      matching.stop(0);
    }
  }

  @Test
  void cancelsADuplicateActiveRunBeforeFailingOver() throws Exception {
    ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    AtomicInteger primaryExecutionCalls = new AtomicInteger();
    AtomicInteger primaryCancellationCalls = new AtomicInteger();
    AtomicInteger fallbackCalls = new AtomicInteger();
    HttpServer primary = standaloneServer(exchange -> {
      String path = exchange.getRequestURI().getPath();
      if (path.endsWith("/cancel")) {
        primaryCancellationCalls.incrementAndGet();
        respond(
            exchange,
            200,
            mapper.writeValueAsBytes(new CancellationResponse("duplicate-run", true)));
        return;
      }
      primaryExecutionCalls.incrementAndGet();
      exchange.getRequestBody().readAllBytes();
      respond(exchange, 409, mapper.writeValueAsBytes(Map.of(
          "code", "SCANNER_RUN_ALREADY_ACTIVE",
          "message", "Scanner run is already active on this adapter",
          "retryable", true)));
    });
    HttpServer fallback = standaloneServer(exchange -> {
      fallbackCalls.incrementAndGet();
      assertEquals(
          1,
          primaryCancellationCalls.get(),
          "the ambiguous primary execution must be cancelled before fallback starts");
      exchange.getRequestBody().readAllBytes();
      respond(exchange, 200, mapper.writeValueAsBytes(catalog()));
    });
    try {
      SecurityScanningProperties properties = new SecurityScanningProperties();
      properties.getAdapter().setBaseUrls(List.of(
          "http://127.0.0.1:" + primary.getAddress().getPort(),
          "http://127.0.0.1:" + fallback.getAddress().getPort()));
      properties.getAdapter().setServiceCredential("secret");
      HttpSecurityScannerAdapter adapter =
          new HttpSecurityScannerAdapter(mapper, properties);
      String runId = java.util.stream.IntStream.range(0, 100)
          .mapToObj(value -> "duplicate-" + value)
          .filter(value -> HttpSecurityScannerAdapter.routeIndex(value, 2) == 0)
          .findFirst()
          .orElseThrow();

      CatalogResponse response = adapter.catalog(
          new CatalogRequest(
              "v1", runId, "catalog-key", subject(null), "config", limits()),
          () -> new ByteArrayInputStream("artifact".getBytes()));

      assertEquals("CycloneDX", response.specName());
      assertEquals(1, primaryExecutionCalls.get());
      assertEquals(1, primaryCancellationCalls.get());
      assertEquals(1, fallbackCalls.get());
    } finally {
      primary.stop(0);
      fallback.stop(0);
    }
  }

  @Test
  void doesNotFailExecutionOverAfterANonRetryableRejection() throws Exception {
    ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    AtomicInteger fallbackCalls = new AtomicInteger();
    HttpServer rejected = standaloneServer(exchange -> {
      exchange.getRequestBody().readAllBytes();
      respond(exchange, 400, "{}".getBytes());
    });
    HttpServer healthy = standaloneServer(exchange -> {
      fallbackCalls.incrementAndGet();
      respond(exchange, 200, mapper.writeValueAsBytes(catalog()));
    });
    try {
      SecurityScanningProperties properties = new SecurityScanningProperties();
      properties.getAdapter().setBaseUrls(List.of(
          "http://127.0.0.1:" + rejected.getAddress().getPort(),
          "http://127.0.0.1:" + healthy.getAddress().getPort()));
      properties.getAdapter().setServiceCredential("secret");
      HttpSecurityScannerAdapter adapter =
          new HttpSecurityScannerAdapter(mapper, properties);
      String runId = java.util.stream.IntStream.range(0, 100)
          .mapToObj(value -> "rejected-" + value)
          .filter(value -> HttpSecurityScannerAdapter.routeIndex(value, 2) == 0)
          .findFirst()
          .orElseThrow();

      ScannerAdapterException failure = assertThrows(
          ScannerAdapterException.class,
          () -> adapter.catalog(
              new CatalogRequest(
                  "v1", runId, "catalog-key", subject(null), "config", limits()),
              () -> new ByteArrayInputStream("artifact".getBytes())));

      assertEquals("SCANNER_HTTP_400", failure.code());
      assertEquals(0, fallbackCalls.get());
    } finally {
      rejected.stop(0);
      healthy.stop(0);
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
    start(exchange -> respond(exchange, 503, new byte[8192]));
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
  void boundsDecodedJsonComplexityAndIgnoresAdvisorySummaryGraphs() throws Exception {
    ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    String manyOperations = java.util.stream.IntStream.range(0, 1_100)
        .mapToObj(ignored -> "\"operation\"")
        .collect(java.util.stream.Collectors.joining(","));
    byte[] excessiveTokens = ("""
        {"apiVersion":"v1","adapterName":"adapter","adapterVersion":"1",
         "operations":[%s],"targetClassifications":[],"maxInputBytes":1,
         "maxOutputBytes":1,"capabilityDigest":"digest"}
        """.formatted(manyOperations)).getBytes();
    start(exchange -> respond(exchange, 200, excessiveTokens));

    ScannerAdapterException complexity = assertThrows(
        ScannerAdapterException.class,
        () -> adapter(mapper, 64 * 1024, 1_024).capabilities());
    assertEquals("SCANNER_RESPONSE_COMPLEXITY_LIMIT", complexity.code());
    stopServer();
    server = null;

    MatchResponse base = match();
    MatchResponse withAdvisorySummary = new MatchResponse(
        base.adapterName(),
        base.adapterVersion(),
        base.engineName(),
        base.engineVersion(),
        base.vulnerabilityDatabaseRevision(),
        base.vulnerabilityDatabaseUpdatedAt(),
        base.capabilityDigest(),
        base.completeness(),
        base.reportJson(),
        base.findings(),
        Map.of("arbitrary", Map.of("nested", List.of("not", "materialized"))));
    start(exchange -> respond(exchange, 200, mapper.writeValueAsBytes(withAdvisorySummary)));

    MatchResponse decoded = adapter(mapper, 64 * 1024, 4_096).match(
        new MatchRequest("v1", "run", "key", "b".repeat(64), "config", limits()),
        () -> new ByteArrayInputStream("{}".getBytes()));
    assertTrue(decoded.summary().isEmpty());
  }

  @Test
  void rejectsAReplicaProjectionBeyondTheSharedContractLimit() throws Exception {
    ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    List<Component> excessiveComponents = java.util.stream.IntStream.rangeClosed(
            0, ScannerContract.MAX_COMPONENT_PROJECTION_COUNT)
        .mapToObj(index -> new Component(
            "component-" + index,
            null,
            "library",
            null,
            "component-" + index,
            null,
            null,
            List.of(),
            List.of(),
            Map.of()))
        .toList();
    CatalogResponse excessive = new CatalogResponse(
        "adapter",
        "1",
        "syft",
        "1",
        "cap",
        "a".repeat(64),
        ScanCompleteness.PARTIAL,
        "CycloneDX",
        "1.6",
        excessiveComponents.size(),
        0,
        "{\"bomFormat\":\"CycloneDX\"}".getBytes(),
        excessiveComponents,
        Map.of());
    start(exchange -> respond(exchange, 200, mapper.writeValueAsBytes(excessive)));

    ScannerAdapterException projection = assertThrows(
        ScannerAdapterException.class,
        () -> adapter(mapper, 4 * 1024 * 1024, 500_000).catalog(
            new CatalogRequest("v1", "run", "key", subject(null), "config", limits()),
            () -> new ByteArrayInputStream("artifact".getBytes())));
    assertEquals("SCANNER_RESPONSE_PROJECTION_LIMIT", projection.code());
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

  private HttpSecurityScannerAdapter adapter(ObjectMapper mapper, long maxResponseBytes) {
    return adapter(mapper, maxResponseBytes, 262_144);
  }

  private HttpSecurityScannerAdapter adapter(
      ObjectMapper mapper, long maxResponseBytes, int maxResponseTokens) {
    SecurityScanningProperties properties = new SecurityScanningProperties();
    properties.getAdapter().setBaseUrl(
        "http://127.0.0.1:" + server.getAddress().getPort() + "/");
    properties.getAdapter().setServiceCredential("secret");
    properties.setMaxResponseBytes(maxResponseBytes);
    properties.setMaxResponseTokens(maxResponseTokens);
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
    return match("db");
  }

  private static MatchResponse match(String databaseRevision) {
    return new MatchResponse(
        "adapter", "1", "grype", "1", databaseRevision, Instant.EPOCH, "cap",
        ScanCompleteness.COMPLETE, "{}".getBytes(), List.of(), Map.of());
  }

  private record Capture(String artifactType, String credential, byte[] body) {}

  @FunctionalInterface
  private interface Handler {
    void handle(HttpExchange exchange) throws IOException;
  }
}
