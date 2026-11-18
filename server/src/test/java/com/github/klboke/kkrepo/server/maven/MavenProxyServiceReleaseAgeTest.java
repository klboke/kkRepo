package com.github.klboke.kkrepo.server.maven;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.github.klboke.kkrepo.core.BlobReference;
import com.github.klboke.kkrepo.core.BlobStorage;
import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.core.RepositoryType;
import com.github.klboke.kkrepo.persistence.jdbc.api.AssetDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.ProxyStateDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetBlobRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetRecord;
import com.github.klboke.kkrepo.protocol.maven.path.MavenPath;
import com.github.klboke.kkrepo.protocol.maven.path.MavenPathParser;
import com.github.klboke.kkrepo.server.cache.AssetMetadataCache;
import com.github.klboke.kkrepo.server.cache.CachedAssetMetadata;
import com.github.klboke.kkrepo.server.releaseage.ReleaseAgeDeniedException;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class MavenProxyServiceReleaseAgeTest {
  private static final Instant NOW = Instant.parse("2026-01-10T12:00:00Z");
  private static final int FORTY_EIGHT_HOURS = 48 * 60;
  private static final MavenPathParser PARSER = new MavenPathParser();
  private static final String METADATA_PATH = "com/acme/demo/maven-metadata.xml";
  private static final String METADATA = """
      <?xml version="1.0" encoding="UTF-8"?>
      <metadata>
        <groupId>com.acme</groupId>
        <artifactId>demo</artifactId>
        <versioning>
          <latest>1.2</latest>
          <release>1.2</release>
          <versions>
            <version>1.0</version>
            <version>1.1</version>
            <version>1.2</version>
            <version>2.0-SNAPSHOT</version>
          </versions>
          <lastUpdated>20260110120000</lastUpdated>
        </versioning>
      </metadata>
      """;

  @Test
  void metadataHidesQuarantinedReleasesButKeepsSnapshots() throws Exception {
    Fixture fixture = fixture();
    fixture.cached(METADATA_PATH, METADATA);
    fixture.pom("1.0", NOW.minus(Duration.ofHours(100)));
    fixture.pom("1.1", NOW.minus(Duration.ofHours(49)));
    fixture.pom("1.2", NOW.minus(Duration.ofHours(1)));

    String xml = body(fixture.service.get(runtime(FORTY_EIGHT_HOURS), path(METADATA_PATH), false));

    assertTrue(xml.contains("<version>1.0</version>"), xml);
    assertTrue(xml.contains("<version>1.1</version>"), xml);
    assertTrue(xml.contains("<version>2.0-SNAPSHOT</version>"), xml);
    assertTrue(!xml.contains("<version>1.2</version>"), xml);
    assertTrue(xml.contains("<release>1.1</release>"), xml);
    assertTrue(xml.contains("<latest>2.0-SNAPSHOT</latest>"), xml);
  }

  @Test
  void metadataChecksumsAreDerivedFromTheFilteredBytes() throws Exception {
    Fixture fixture = fixture();
    fixture.cached(METADATA_PATH, METADATA);
    fixture.cached(METADATA_PATH + ".sha1", "upstream-checksum-for-the-unfiltered-document");
    fixture.pom("1.0", NOW.minus(Duration.ofHours(100)));
    fixture.pom("1.1", NOW.minus(Duration.ofHours(49)));
    fixture.pom("1.2", NOW.minus(Duration.ofHours(1)));
    RepositoryRuntime runtime = runtime(FORTY_EIGHT_HOURS);

    byte[] filtered = body(fixture.service.get(runtime, path(METADATA_PATH), false))
        .getBytes(StandardCharsets.UTF_8);
    String sha1 = body(fixture.service.get(runtime, path(METADATA_PATH + ".sha1"), false));
    String sha256 = body(fixture.service.get(runtime, path(METADATA_PATH + ".sha256"), false));

    assertEquals(hex("SHA-1", filtered), sha1);
    assertEquals(hex("SHA-256", filtered), sha256);
  }

  @Test
  void metadataIsServedUnchangedWhenNothingIsHidden() throws Exception {
    Fixture fixture = fixture();
    fixture.cached(METADATA_PATH, METADATA);
    for (String version : List.of("1.0", "1.1", "1.2")) {
      fixture.pom(version, NOW.minus(Duration.ofHours(100)));
    }

    assertEquals(
        METADATA,
        body(fixture.service.get(runtime(FORTY_EIGHT_HOURS), path(METADATA_PATH), false)));
  }

  @Test
  void metadataIsNotFoundWhenEveryReleaseIsQuarantined() {
    Fixture fixture = fixture();
    fixture.cached(METADATA_PATH, METADATA.replace("<version>2.0-SNAPSHOT</version>", ""));
    for (String version : List.of("1.0", "1.1", "1.2")) {
      fixture.pom(version, NOW.minus(Duration.ofHours(1)));
    }

    assertThrows(
        MavenExceptions.MavenNotFoundException.class,
        () -> fixture.service.get(runtime(FORTY_EIGHT_HOURS), path(METADATA_PATH), false));
  }

  @Test
  void versionWithUnknownPublishTimeIsHiddenFromMetadata() throws Exception {
    Fixture fixture = fixture();
    fixture.cached(METADATA_PATH, METADATA);
    fixture.pom("1.0", NOW.minus(Duration.ofHours(100)));
    fixture.pom("1.1", NOW.minus(Duration.ofHours(100)));
    fixture.upstreamMissing("com/acme/demo/1.2/demo-1.2.pom");

    String xml = body(fixture.service.get(runtime(FORTY_EIGHT_HOURS), path(METADATA_PATH), false));

    assertTrue(!xml.contains("<version>1.2</version>"), xml);
  }

  @Test
  void groupLevelAndSnapshotMetadataAreNotFiltered() throws Exception {
    Fixture fixture = fixture();
    String groupLevel = "<metadata><plugins/></metadata>";
    fixture.cached("com/acme/maven-metadata.xml", groupLevel);
    String snapshotLevel = "<metadata><groupId>com.acme</groupId><artifactId>demo</artifactId>"
        + "<version>2.0-SNAPSHOT</version></metadata>";
    fixture.cached("com/acme/demo/2.0-SNAPSHOT/maven-metadata.xml", snapshotLevel);

    assertEquals(
        groupLevel,
        body(fixture.service.get(
            runtime(FORTY_EIGHT_HOURS), path("com/acme/maven-metadata.xml"), false)));
    assertEquals(
        snapshotLevel,
        body(fixture.service.get(
            runtime(FORTY_EIGHT_HOURS),
            path("com/acme/demo/2.0-SNAPSHOT/maven-metadata.xml"), false)));
  }

  @Test
  void artifactFilesChecksumsAndSignaturesAreForbiddenWhileQuarantined() {
    Fixture fixture = fixture();
    fixture.pom("1.2", NOW.minus(Duration.ofHours(1)));
    RepositoryRuntime runtime = runtime(FORTY_EIGHT_HOURS);

    for (String file : List.of(
        "demo-1.2.jar", "demo-1.2.jar.sha1", "demo-1.2.jar.asc", "demo-1.2-sources.jar",
        "demo-1.2.pom", "demo-1.2.module")) {
      String full = "com/acme/demo/1.2/" + file;
      ReleaseAgeDeniedException denied = assertThrows(
          ReleaseAgeDeniedException.class,
          () -> fixture.service.get(runtime, path(full), false), file);
      assertEquals(NOW.minus(Duration.ofHours(1)).plus(Duration.ofHours(48)), denied.eligibleAt());
      // Decided before the cache is consulted, so nothing cached can leak past the gate.
      verify(fixture.cache, never()).find(eq(10L), eq(full), any());
    }
    // A denial must never be remembered as "not found": the version matures later.
    verify(fixture.negativeCache, never()).rememberNotFound(any(RepositoryRuntime.class), any(MavenPath.class));
  }

  @Test
  void artifactFileIsServedOnceTheVersionHasMatured() throws Exception {
    Fixture fixture = fixture();
    fixture.pom("1.1", NOW.minus(Duration.ofHours(49)));
    fixture.cached("com/acme/demo/1.1/demo-1.1.jar", "jar-bytes");

    assertEquals(
        "jar-bytes",
        body(fixture.service.get(
            runtime(FORTY_EIGHT_HOURS), path("com/acme/demo/1.1/demo-1.1.jar"), false)));
  }

  @Test
  void snapshotsAreExemptAndNeverLookedUpUpstream() throws Exception {
    Fixture fixture = fixture();
    String snapshotJar = "com/acme/demo/2.0-SNAPSHOT/demo-2.0-20260110.115900-1.jar";
    fixture.cached(snapshotJar, "snapshot-bytes");

    assertEquals(
        "snapshot-bytes",
        body(fixture.service.get(runtime(FORTY_EIGHT_HOURS), path(snapshotJar), false)));
    verify(fixture.fetcher, never()).fetchWithBodyRetry(any(), any(), any());
  }

  @Test
  void unknownPublishTimeIsQuarantinedWithoutAnEligibleInstant() {
    Fixture fixture = fixture();
    fixture.upstreamFailing("com/acme/demo/1.2/demo-1.2.pom", 503);
    fixture.upstreamFailing("com/acme/demo/1.2/demo-1.2.jar", 503);

    ReleaseAgeDeniedException denied = assertThrows(
        ReleaseAgeDeniedException.class,
        () -> fixture.service.get(
            runtime(FORTY_EIGHT_HOURS), path("com/acme/demo/1.2/demo-1.2.jar"), false));

    assertNull(denied.eligibleAt());
  }

  @Test
  void upstreamWithoutLastModifiedIsQuarantinedToo() {
    Fixture fixture = fixture();
    fixture.upstreamFailing("com/acme/demo/1.2/demo-1.2.pom", 200);
    fixture.upstreamFailing("com/acme/demo/1.2/demo-1.2.jar", 200);

    ReleaseAgeDeniedException denied = assertThrows(
        ReleaseAgeDeniedException.class,
        () -> fixture.service.get(
            runtime(FORTY_EIGHT_HOURS), path("com/acme/demo/1.2/demo-1.2.jar"), false));

    assertNull(denied.eligibleAt());
  }

  @Test
  void versionThatDoesNotExistUpstreamIsNotQuarantined() {
    Fixture fixture = fixture();
    fixture.upstreamMissing("com/acme/demo/1.2/demo-1.2.pom");
    fixture.upstreamMissing("com/acme/demo/1.2/demo-1.2.jar");

    // A 404 stays a 404 (never a 403) so a client or group can fall through to the next repository.
    assertThrows(
        MavenExceptions.MavenNotFoundException.class,
        () -> fixture.service.get(
            runtime(FORTY_EIGHT_HOURS), path("com/acme/demo/1.2/demo-1.2.jar"), false));
  }

  @Test
  void publishTimeLearnedFromTheFormerUpstreamDoesNotAnswerForANewRemoteUrl() {
    Fixture fixture = fixture();
    fixture.cached("com/acme/demo/1.2/demo-1.2.jar", "jar-bytes");
    fixture.pom("1.2", NOW.minus(Duration.ofDays(10)));

    // Warm this node's publish-time cache against the original, long-published upstream.
    assertDoesNotThrow(() -> fixture.service.get(
        runtime(FORTY_EIGHT_HOURS, "https://repo.example/maven2/"),
        path("com/acme/demo/1.2/demo-1.2.jar"), false));

    // The administrator points the same repository at a different upstream whose pom is brand new.
    // A fresh node would HEAD it and refuse; this warm node must not reuse the former timestamp.
    fixture.pom("1.2", NOW.minus(Duration.ofHours(1)));
    assertThrows(
        ReleaseAgeDeniedException.class,
        () -> fixture.service.get(
            runtime(FORTY_EIGHT_HOURS, "https://other-upstream.example/maven2/"),
            path("com/acme/demo/1.2/demo-1.2.jar"), false));
  }

  @Test
  void versionWithdrawnUpstreamIsNotServedFromAnOlderCacheEntry() {
    Fixture fixture = fixture();
    // Cached before the policy was enabled; the upstream has since removed the version and its pom.
    fixture.cached("com/acme/demo/1.2/demo-1.2.jar", "withdrawn-bytes");
    fixture.upstreamMissing("com/acme/demo/1.2/demo-1.2.pom");
    fixture.upstreamMissing("com/acme/demo/1.2/demo-1.2.jar");

    assertThrows(
        MavenExceptions.MavenNotFoundException.class,
        () -> fixture.service.get(
            runtime(FORTY_EIGHT_HOURS), path("com/acme/demo/1.2/demo-1.2.jar"), false));
  }

  @Test
  void policyDisabledNeverContactsTheUpstreamForPublishTimes() throws Exception {
    Fixture fixture = fixture();
    fixture.cached("com/acme/demo/1.2/demo-1.2.jar", "jar-bytes");
    fixture.cached(METADATA_PATH, METADATA);

    assertEquals(
        "jar-bytes",
        body(fixture.service.get(runtime(0), path("com/acme/demo/1.2/demo-1.2.jar"), false)));
    assertEquals(METADATA, body(fixture.service.get(runtime(0), path(METADATA_PATH), false)));
    verify(fixture.fetcher, never()).fetchWithBodyRetry(any(), any(), any());
  }

  // ---------------------------------------------------------------------------------------------

  private static MavenPath path(String path) {
    return PARSER.parsePath(path);
  }

  private static String body(MavenResponse response) throws IOException {
    return new String(response.body().readAllBytes(), StandardCharsets.UTF_8);
  }

  private static String hex(String algorithm, byte[] bytes) throws Exception {
    return HexFormat.of().formatHex(MessageDigest.getInstance(algorithm).digest(bytes));
  }

  private static RepositoryRuntime runtime(int minimumReleaseAgeMinutes) {
    return runtime(minimumReleaseAgeMinutes, "https://repo.example/maven2/");
  }

  private static RepositoryRuntime runtime(int minimumReleaseAgeMinutes, String remoteUrl) {
    return new RepositoryRuntime(
        10L, "maven-central", RepositoryFormat.MAVEN2, RepositoryType.PROXY, "maven2-proxy", true,
        7L, null, "RELEASE", "STRICT", true, remoteUrl,
        60, 60, true, null, List.of(), minimumReleaseAgeMinutes);
  }

  private static Fixture fixture() {
    return new Fixture();
  }

  private static final class Fixture {
    final AssetMetadataCache cache = mock(AssetMetadataCache.class);
    final HttpRemoteFetcher fetcher = mock(HttpRemoteFetcher.class);
    final ProxyNegativeCache negativeCache = mock(ProxyNegativeCache.class);
    final BlobStorage storage = mock(BlobStorage.class);
    final Map<String, byte[]> blobs = new HashMap<>();
    final MavenProxyService service;

    Fixture() {
      BlobStorageRegistry registry = mock(BlobStorageRegistry.class);
      when(registry.forBlobStoreId(7L)).thenReturn(storage);
      when(storage.get(any())).thenAnswer(invocation -> {
        BlobReference ref = invocation.getArgument(0);
        byte[] bytes = blobs.get(ref.objectKey());
        return bytes == null
            ? Optional.empty()
            : Optional.of(new ByteArrayInputStream(bytes));
      });
      service = new MavenProxyService(
          mock(AssetDao.class), registry, mock(MavenAssetWriter.class), mock(ProxyStateDao.class),
          fetcher, negativeCache, cache, null, null, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    /** A fresh cached upstream response, so no upstream fetch is needed. */
    void cached(String path, String body) {
      blobs.put(path, body.getBytes(StandardCharsets.UTF_8));
      AssetRecord asset = new AssetRecord(
          1L, 10L, 3L, 2L, RepositoryFormat.MAVEN2, path, null, path, "X", "application/xml",
          (long) body.length(), null, NOW, Map.of());
      AssetBlobRecord blob = new AssetBlobRecord(
          2L, 7L, "blob://bucket/" + path, null, path, null, "sha1", "sha256", "md5",
          body.length(), "application/xml", "proxy", "upstream", Instant.EPOCH, Instant.EPOCH,
          Map.of());
      when(cache.find(eq(10L), eq(path), any()))
          .thenReturn(Optional.of(CachedAssetMetadata.of(asset, blob)));
    }

    /** Upstream HEAD of the version's pom reports this Last-Modified. */
    void pom(String version, Instant lastModified) {
      String path = "com/acme/demo/" + version + "/demo-" + version + ".pom";
      stubUpstream(path, 200, Map.of(
          "Last-Modified",
          DateTimeFormatter.RFC_1123_DATE_TIME.format(lastModified.atZone(ZoneOffset.UTC))));
    }

    void upstreamMissing(String path) {
      stubUpstream(path, 404, Map.of());
    }

    void upstreamFailing(String path, int status) {
      stubUpstream(path, status, Map.of());
    }

    private void stubUpstream(String path, int status, Map<String, String> headers) {
      try {
        doAnswer(invocation -> {
          @SuppressWarnings("unchecked")
          HttpRemoteFetcher.ResultHandler<Object> handler = invocation.getArgument(2);
          return handler.handle(new HttpRemoteFetcher.Result(
              status, headers, new ByteArrayInputStream(new byte[0])));
        }).when(fetcher).fetchWithBodyRetry(any(), eq(path), any());
      } catch (IOException e) {
        throw new IllegalStateException(e);
      }
    }
  }
}
