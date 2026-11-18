package com.github.klboke.kkrepo.server.ohpm;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.klboke.kkrepo.cache.InMemorySharedCache;
import com.github.klboke.kkrepo.core.BlobReference;
import com.github.klboke.kkrepo.core.BlobStorage;
import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.persistence.jdbc.api.AssetDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.ProxyStateDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetBlobRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetRecord;
import com.github.klboke.kkrepo.protocol.ohpm.OhpmException;
import com.github.klboke.kkrepo.protocol.ohpm.OhpmIntegrity;
import com.github.klboke.kkrepo.protocol.ohpm.OhpmPackageId;
import com.github.klboke.kkrepo.server.cache.NexusLikeCacheController;
import com.github.klboke.kkrepo.server.cache.NexusLikeCacheInfo;
import com.github.klboke.kkrepo.server.maven.BlobStorageRegistry;
import com.github.klboke.kkrepo.server.maven.HttpRemoteFetcher;
import com.github.klboke.kkrepo.server.maven.MavenResponse;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntime;
import com.github.klboke.kkrepo.server.raw.RawHostedService;
import com.github.klboke.kkrepo.server.securityscan.ArtifactDownloadPolicy;
import com.github.klboke.kkrepo.server.transaction.TransientTransactionRetry;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

class OhpmProxyServiceTest {
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final OhpmPackageId PACKAGE = OhpmPackageId.parse("@scope/pkg");
  private static final String PATH = "@scope/pkg/-/@scope/pkg-1.0.0.har";
  private static final byte[] HAR = new byte[] {1, 2, 3};
  private final AssetDao assets = mock(AssetDao.class);
  private final RawHostedService writer = mock(RawHostedService.class);
  private final BlobStorageRegistry storages = mock(BlobStorageRegistry.class);
  private final BlobStorage storage = mock(BlobStorage.class);
  private final ArtifactDownloadPolicy policy = mock(ArtifactDownloadPolicy.class);
  private final HttpRemoteFetcher fetcher = mock(HttpRemoteFetcher.class);
  private final ProxyStateDao state = mock(ProxyStateDao.class);
  private final NexusLikeCacheController cache = mock(NexusLikeCacheController.class);
  private final TransientTransactionRetry transactions = mock(TransientTransactionRetry.class);
  private final RepositoryRuntime runtime = mock(RepositoryRuntime.class);
  private final Map<String, AssetRecord> records = new HashMap<>();
  private final Map<Long, AssetBlobRecord> blobs = new HashMap<>();
  private final Map<String, byte[]> content = new HashMap<>();
  private final List<Path> staging = new ArrayList<>();
  private final List<RawHostedService.PreparedFile> preparedUploads = new ArrayList<>();
  private final com.github.klboke.kkrepo.server.coordination.FencedLeaseManager.Lease lease =
      mock(com.github.klboke.kkrepo.server.coordination.FencedLeaseManager.Lease.class);
  private final AtomicInteger sequence = new AtomicInteger();
  private final InMemorySharedCache shared = new InMemorySharedCache(JSON, 100, null);
  private String token = "0";
  private OhpmProxyService service;

  @BeforeEach
  void setup() throws Exception {
    when(runtime.id()).thenReturn(1L);
    when(runtime.name()).thenReturn("proxy");
    when(runtime.format()).thenReturn(RepositoryFormat.OHPM);
    when(runtime.isProxy()).thenReturn(true);
    when(runtime.online()).thenReturn(true);
    when(runtime.proxyRemoteUrl()).thenReturn("https://upstream.example/ohpm");
    when(runtime.proxyRemoteBearerToken()).thenReturn("test-token");
    when(runtime.strictRedirectHosts()).thenReturn(Set.of());
    when(runtime.metadataMaxAgeMinutesOrDefault()).thenReturn(5);
    when(runtime.contentMaxAgeMinutesOrDefault()).thenReturn(60);
    when(runtime.autoBlockOrDefault()).thenReturn(true);
    when(cache.currentDurableToken(eq(1L), any())).thenAnswer(call -> token);
    when(cache.isDurablyStale(eq(1L), any(), any(), anyInt(), any())).thenAnswer(call -> {
      NexusLikeCacheInfo info = call.getArgument(2);
      int ttl = call.getArgument(3);
      return info == null || !token.equals(info.cacheToken()) || ttl == 0;
    });
    when(assets.findAssetByPath(eq(1L), anyString())).thenAnswer(call -> Optional.ofNullable(records.get(call.getArgument(1))));
    when(assets.findAssetByPathForUpdate(eq(1L), anyString())).thenAnswer(call -> Optional.ofNullable(records.get(call.getArgument(1))));
    when(assets.findBlobById(anyLong())).thenAnswer(call -> Optional.ofNullable(blobs.get(call.getArgument(0))));
    when(assets.tryInsertAsset(any())).thenAnswer(call -> {
      AssetRecord record = call.getArgument(0);
      if (records.containsKey(record.path())) return OptionalLong.empty();
      long id = sequence.incrementAndGet();
      records.put(record.path(), copy(record, id, record.assetBlobId(), record.lastUpdatedAt(), record.attributes()));
      return OptionalLong.of(id);
    });
    when(assets.touchAssetLastUpdatedAndAttributes(anyLong(), any(), anyMap())).thenAnswer(call -> {
      long id = call.getArgument(0);
      var record = records.values().stream().filter(value -> value.id() == id).findFirst().orElseThrow();
      records.put(record.path(), copy(record, id, record.assetBlobId(), call.getArgument(1), call.getArgument(2)));
      return 1;
    });
    when(transactions.execute(anyString(), any())).thenAnswer(call -> ((Supplier<?>) call.getArgument(1)).get());
    when(storages.forBlobStoreId(anyLong())).thenReturn(storage);
    when(storage.get(any())).thenAnswer(call -> {
      BlobReference reference = call.getArgument(0);
      return Optional.of(new ByteArrayInputStream(content.get(reference.objectKey())));
    });
    when(writer.prepareInternalFile(any(), anyString(), any())).thenAnswer(call -> {
      String path = call.getArgument(1);
      Path file = call.getArgument(2);
      staging.add(file);
      byte[] bytes = Files.readAllBytes(file);
      var prepared = mock(RawHostedService.PreparedFile.class);
      preparedUploads.add(prepared);
      when(prepared.publish(any(), anyString(), anyString(), nullable(String.class), any(), anyString(), eq(true)))
          .thenAnswer(publication -> {
            long id = sequence.incrementAndGet();
            String key = "blob-" + id;
            var digest = OhpmIntegrity.compute(new ByteArrayInputStream(bytes));
            blobs.put(id, new AssetBlobRecord(id, 1L, "blob://test/" + key, null, key, null,
                "sha1", "sha256-" + id, "md5", bytes.length, "application/octet-stream",
                "proxy", null, Instant.now(), Instant.now(),
                Map.of("sha512", HexFormat.of().formatHex(Base64.getDecoder().decode(digest.value().substring(7))))));
            content.put(key, bytes);
            records.put(path, new AssetRecord(id, 1L, null, id, RepositoryFormat.OHPM, path, null,
                "pkg", "file", "application/octet-stream", (long) bytes.length, null, Instant.now(), Map.of()));
            return true;
          });
      return prepared;
    });
    var coordination = mock(OhpmFetchCoordinator.class);
    when(coordination.acquire(anyString())).thenReturn(lease);
    var repositories = mock(com.github.klboke.kkrepo.server.maven.RepositoryRuntimeRegistry.class);
    when(repositories.resolveFreshById(1L)).thenReturn(Optional.of(runtime));
    service = new OhpmProxyService(assets, writer, storages, policy, fetcher, state, cache,
        shared, transactions, JSON, 1024, true, 5, mock(OhpmVersionReader.class), coordination, repositories);
  }

  @Test
  void expiredOwnerCannotPublishAndClosesItsPreparedObject() throws Exception {
    when(fetcher.fetch(any())).thenReturn(result(200, metadata(HAR), "meta"), result(200, HAR, "file"));
    doThrow(new OhpmException(503, "lease lost")).when(lease).assertHeld();
    assertEquals(503, assertThrows(OhpmException.class,
        () -> service.download(runtime, PACKAGE, PATH, false)).status());
    assertFalse(records.containsKey(PATH));
    verify(preparedUploads.getFirst()).close();
    verify(preparedUploads.getFirst(), never()).publish(any(), anyString(), anyString(), any(), any(), anyString(), anyBoolean());
    verify(lease).close();
  }

  @Test
  void expiredOwnerCannotPopulateNegativeCache() throws Exception {
    when(fetcher.fetch(any())).thenReturn(result(200, metadata(HAR), "meta"),
        result(404, new byte[0], null), result(200, HAR, "file"));
    doThrow(new OhpmException(503, "lease lost")).when(lease).assertHeld();
    assertEquals(503, assertThrows(OhpmException.class,
        () -> service.download(runtime, PACKAGE, PATH, false)).status());
    doNothing().when(lease).assertHeld();
    assertArrayEquals(HAR, read(service.download(runtime, PACKAGE, PATH, false)));
    verify(fetcher, times(3)).fetch(any());
  }

  @Test
  void repositoryChangeDuringStoragePreparationCannotPublishOldUpstreamContent() throws Exception {
    when(fetcher.fetch(any())).thenReturn(result(200, metadata(HAR), "meta")).thenAnswer(call -> {
      when(runtime.proxyRemoteUrl()).thenReturn("https://changed.example/ohpm");
      return result(200, HAR, "file");
    });
    assertEquals(502, assertThrows(OhpmException.class,
        () -> service.download(runtime, PACKAGE, PATH, false)).status());
    assertFalse(records.containsKey(PATH));
    verify(preparedUploads.getFirst()).close();
  }

  @Test
  void cachesMetadataAndRewritesUnscopedUpstreamFilename() throws Exception {
    when(fetcher.fetch(any())).thenReturn(result(200, metadata(HAR), "v1"));
    var first = readMetadata();
    assertEquals("https://repo.example/repository/proxy/" + PATH,
        first.path("versions").path("1.0.0").path("dist").path("tarball").asText());
    assertFalse(first.toString().contains("upstream.example"));
    assertEquals(first, readMetadata());
    verify(fetcher).fetch(any());
    var request = ArgumentCaptor.forClass(HttpRemoteFetcher.Request.class);
    verify(fetcher).fetch(request.capture());
    assertEquals("test-token", request.getValue().authorizationHeader());
    assertEquals("https://upstream.example/ohpm/@scope/pkg", request.getValue().url());
  }

  @Test
  void revalidatesMetadataWithRemoteValidatorAndRefreshesDurableCache() throws Exception {
    when(runtime.metadataMaxAgeMinutesOrDefault()).thenReturn(0);
    when(fetcher.fetch(any())).thenReturn(result(200, metadata(HAR), "v1"), result(304, new byte[0], null));
    var expected = readMetadata();
    assertEquals(expected, readMetadata());
    var requests = ArgumentCaptor.forClass(HttpRemoteFetcher.Request.class);
    verify(fetcher, times(2)).fetch(requests.capture());
    assertEquals("v1", requests.getAllValues().get(1).etag());
    assertEquals("v1", records.get(PACKAGE.id()).attributes().get("remoteEtag"));
  }

  @Test
  void cachesVerifiedFileAndHeadNeverPopulatesAnEmptyBlob() throws Exception {
    when(fetcher.fetch(any())).thenReturn(result(200, metadata(HAR), "meta"), result(200, HAR, "file"));
    assertEquals(HAR.length, service.download(runtime, PACKAGE, PATH, true).contentLength());
    assertArrayEquals(HAR, read(service.download(runtime, PACKAGE, PATH, false)));
    verify(fetcher, times(2)).fetch(any());
    assertTrue(staging.stream().noneMatch(Files::exists));
    verify(policy, times(2)).beforeReadFromRepository(anyLong(), anyLong(), eq(1L));
    var component = ArgumentCaptor.forClass(
        com.github.klboke.kkrepo.persistence.jdbc.api.model.ComponentRecord.class);
    verify(preparedUploads.getFirst()).publish(any(), eq(PATH), eq("proxy"), any(),
        component.capture(), anyString(), eq(true));
    assertEquals(PATH, JSON.valueToTree(component.getValue().attributes()).path("dist").path("tarball").asText());
    assertFalse(component.getValue().attributes().toString().contains("upstream.example"));
  }

  @Test
  void changedIntegrityCannotServePreviouslyCachedFile() throws Exception {
    byte[] changed = new byte[] {7, 8};
    when(fetcher.fetch(any())).thenReturn(result(200, metadata(HAR), "meta1"), result(200, HAR, "file1"),
        result(200, metadata(changed), "meta2"), result(200, changed, "file2"));
    assertArrayEquals(HAR, read(service.download(runtime, PACKAGE, PATH, false)));
    when(runtime.metadataMaxAgeMinutesOrDefault()).thenReturn(0);
    assertArrayEquals(changed, read(service.download(runtime, PACKAGE, PATH, false)));
    verify(fetcher, times(4)).fetch(any());
  }

  @Test
  void corruptUpstreamFileNeverEntersTheCache() throws Exception {
    when(fetcher.fetch(any())).thenReturn(result(200, metadata(HAR), null), result(200, new byte[] {9}, null));
    assertEquals(502, assertThrows(OhpmException.class,
        () -> service.download(runtime, PACKAGE, PATH, false)).status());
    verifyNoInteractions(writer);
    assertFalse(records.containsKey(PATH));
  }

  @Test
  void revalidatesContentAndFallsBackToVerifiedBytesOnOutage() throws Exception {
    when(fetcher.fetch(any())).thenReturn(result(200, metadata(HAR), "meta"), result(200, HAR, "file"),
        result(304, new byte[0], null), result(503, new byte[0], null));
    assertArrayEquals(HAR, read(service.download(runtime, PACKAGE, PATH, false)));
    when(runtime.contentMaxAgeMinutesOrDefault()).thenReturn(0);
    assertArrayEquals(HAR, read(service.download(runtime, PACKAGE, PATH, false)));
    assertArrayEquals(HAR, read(service.download(runtime, PACKAGE, PATH, false)));
    verify(state).recordFailure(eq(1L), eq(30L), anyString(), any());
  }

  @ParameterizedTest
  @ValueSource(ints = {404, 410, 451})
  void definitiveMissesNeverServeStaleMetadataAndKeepTheirStatus(int status) throws Exception {
    when(fetcher.fetch(any())).thenReturn(result(200, metadata(HAR), "meta"), result(status, new byte[0], null));
    readMetadata();
    when(runtime.metadataMaxAgeMinutesOrDefault()).thenReturn(0);
    for (int attempt = 0; attempt < 2; attempt++) {
      assertEquals(status, assertThrows(OhpmException.class, this::readMetadata).status());
    }
    verify(fetcher, times(2)).fetch(any());
    verify(state, never()).recordFailure(anyLong(), anyLong(), anyString(), any());
  }

  @Test
  void metadataOutageCanUseStaleButRemoteAuthenticationFailureCannot() throws Exception {
    when(fetcher.fetch(any())).thenReturn(result(200, metadata(HAR), "meta"), result(503, new byte[0], null),
        result(401, new byte[0], null));
    var expected = readMetadata();
    when(runtime.metadataMaxAgeMinutesOrDefault()).thenReturn(0);
    assertEquals(expected, readMetadata());
    assertEquals(502, assertThrows(OhpmException.class, this::readMetadata).status());
  }

  @Test
  void expiredNegativeEntryCannotResurrectRemovedMetadataDuringAnOutage() throws Exception {
    when(fetcher.fetch(any())).thenReturn(result(200, metadata(HAR), "meta"), result(410, new byte[0], null),
        result(503, new byte[0], null));
    readMetadata();
    when(runtime.metadataMaxAgeMinutesOrDefault()).thenReturn(0);
    assertEquals(410, assertThrows(OhpmException.class, this::readMetadata).status());
    shared.evictByPrefix("ohpm-proxy-negative", "1:");
    assertEquals(502, assertThrows(OhpmException.class, this::readMetadata).status());
  }

  @Test
  void expiredNegativeEntryCannotResurrectRemovedContentDuringAnOutage() throws Exception {
    when(fetcher.fetch(any())).thenReturn(result(200, metadata(HAR), "meta"), result(200, HAR, "file"),
        result(451, new byte[0], null), result(503, new byte[0], null));
    read(service.download(runtime, PACKAGE, PATH, false));
    when(runtime.contentMaxAgeMinutesOrDefault()).thenReturn(0);
    assertEquals(451, assertThrows(OhpmException.class, () -> service.download(runtime, PACKAGE, PATH, false)).status());
    shared.evictByPrefix("ohpm-proxy-negative", "1:");
    assertEquals(502, assertThrows(OhpmException.class, () -> service.download(runtime, PACKAGE, PATH, false)).status());
  }

  @Test
  void inFlightDownloadCannotOverwriteALaterRemoval() throws Exception {
    var calls = new AtomicInteger();
    when(fetcher.fetch(any())).thenAnswer(call -> {
      return switch (calls.incrementAndGet()) {
        case 1 -> result(200, metadata(HAR), "meta");
        case 2 -> result(200, HAR, "file");
        case 3 -> {
          assertEquals(410, assertThrows(OhpmException.class,
              () -> service.download(runtime, PACKAGE, PATH, false)).status());
          yield result(200, HAR, "older");
        }
        case 4 -> result(410, new byte[0], null);
        default -> result(503, new byte[0], null);
      };
    });
    read(service.download(runtime, PACKAGE, PATH, false));
    when(runtime.contentMaxAgeMinutesOrDefault()).thenReturn(0);
    assertEquals(502, assertThrows(OhpmException.class, () -> service.download(runtime, PACKAGE, PATH, false)).status());
    shared.evictByPrefix("ohpm-proxy-negative", "1:");
    assertEquals(502, assertThrows(OhpmException.class, () -> service.download(runtime, PACKAGE, PATH, false)).status());
  }

  @Test
  void credentialsAndCacheInvalidationChangeCacheIdentity() throws Exception {
    when(fetcher.fetch(any())).thenAnswer(call -> result(200, metadata(HAR), "meta"));
    readMetadata();
    token = "1";
    readMetadata();
    when(runtime.proxyRemoteBearerToken()).thenReturn("rotated-token");
    readMetadata();
    verify(fetcher, times(3)).fetch(any());
    assertFalse(records.get(PACKAGE.id()).attributes().toString().contains("rotated-token"));
  }

  @Test
  void laterMetadataRefreshCannotBeOverwrittenByAnEarlierInFlightResponse() throws Exception {
    var calls = new AtomicInteger();
    when(fetcher.fetch(any())).thenAnswer(call -> {
      var body = (ObjectNode) JSON.readTree(metadata(HAR));
      if (calls.incrementAndGet() == 1) {
        readMetadata();
        body.put("description", "older");
      } else body.put("description", "newer");
      return result(200, JSON.writeValueAsBytes(body), "meta");
    });
    assertEquals("newer", readMetadata().path("description").asText());
    assertEquals("newer", readMetadata().path("description").asText());
  }

  @Test
  void transportBodyFailureCanUseStaleWithoutReplacingMetadata() throws Exception {
    when(fetcher.fetch(any())).thenReturn(result(200, metadata(HAR), "meta"),
        new HttpRemoteFetcher.Result(200, Map.of(), new InputStream() {
          @Override public int read() throws IOException { throw new IOException("connection reset"); }
        }));
    var expected = readMetadata();
    when(runtime.metadataMaxAgeMinutesOrDefault()).thenReturn(0);
    assertEquals(expected, readMetadata());
    verify(state).recordFailure(eq(1L), eq(30L), anyString(), any());
  }

  @Test
  void unapprovedMetadataDownloadHostIsRejectedBeforeCachePublication() throws Exception {
    var body = (ObjectNode) JSON.readTree(metadata(HAR));
    body.withObject("/versions/1.0.0/dist").put("tarball", "https://unapproved.example/pkg.har");
    when(fetcher.fetch(any())).thenReturn(result(200, JSON.writeValueAsBytes(body), null));
    assertEquals(502, assertThrows(OhpmException.class, this::readMetadata).status());
    assertTrue(records.isEmpty());
  }

  private com.fasterxml.jackson.databind.JsonNode readMetadata() throws Exception {
    return JSON.readTree(read(service.metadata(runtime, PACKAGE, "https://repo.example/repository/proxy", false)));
  }

  private static byte[] read(MavenResponse response) throws IOException {
    try (InputStream body = response.materializeBody().body()) { return body.readAllBytes(); }
  }

  @Test
  void interruptedDownloadUsesVerifiedStaleBytesButNeverPublishesAnIncompleteFile() throws Exception {
    when(fetcher.fetch(any())).thenReturn(result(200, metadata(HAR), "meta"))
        .thenThrow(new IOException("connection reset"));
    assertEquals(502, assertThrows(OhpmException.class,
        () -> service.download(runtime, PACKAGE, PATH, false)).status());
    verifyNoInteractions(writer);
    doReturn(result(200, HAR, "file")).when(fetcher).fetch(any());
    assertArrayEquals(HAR, read(service.download(runtime, PACKAGE, PATH, false)));
    when(runtime.contentMaxAgeMinutesOrDefault()).thenReturn(0);
    when(fetcher.fetch(any())).thenThrow(new IOException("connection reset"));
    assertArrayEquals(HAR, read(service.download(runtime, PACKAGE, PATH, false)));
    verify(writer).prepareInternalFile(any(), anyString(), any());
  }

  @Test
  void blockedUpstreamOnlyServesPreviouslyVerifiedContent() throws Exception {
    when(fetcher.fetch(any())).thenReturn(result(200, metadata(HAR), "meta"));
    readMetadata();
    when(state.isBlocked(eq(1L), any())).thenReturn(true);
    assertEquals(502, assertThrows(OhpmException.class,
        () -> service.download(runtime, PACKAGE, PATH, false)).status());
    verify(fetcher).fetch(any());
    when(state.isBlocked(eq(1L), any())).thenReturn(false);
    when(fetcher.fetch(any())).thenReturn(result(200, HAR, "file"));
    assertArrayEquals(HAR, read(service.download(runtime, PACKAGE, PATH, false)));
    when(runtime.contentMaxAgeMinutesOrDefault()).thenReturn(0);
    when(runtime.metadataMaxAgeMinutesOrDefault()).thenReturn(0);
    when(state.isBlocked(eq(1L), any())).thenReturn(true);
    assertArrayEquals(HAR, read(service.download(runtime, PACKAGE, PATH, false)));
    verify(fetcher, times(2)).fetch(any());
  }

  @Test
  void malformedUpstreamJsonIsRejectedWithoutPersistingMetadata() throws Exception {
    when(fetcher.fetch(any())).thenReturn(result(200, new byte[] {'{'}, null));
    assertEquals(502, assertThrows(OhpmException.class, this::readMetadata).status());
    assertTrue(records.isEmpty());
    verifyNoInteractions(writer);
  }

  @Test
  void cacheInvalidationDuringFileDownloadRejectsTheOldResponse() throws Exception {
    when(fetcher.fetch(any())).thenReturn(result(200, metadata(HAR), "meta"));
    readMetadata();
    when(fetcher.fetch(any())).thenAnswer(call -> {
      token = "1";
      return result(200, HAR, "file");
    });
    assertEquals(502, assertThrows(OhpmException.class,
        () -> service.download(runtime, PACKAGE, PATH, false)).status());
    verify(writer).prepareInternalFile(any(), anyString(), any());
    assertFalse(records.containsKey(PATH));
  }

  @Test
  void invalidationDuringMetadataFetchServesTheResponseWithoutCachingItUnderTheOldToken() throws Exception {
    when(fetcher.fetch(any())).thenAnswer(call -> {
      token = "1";
      return result(200, metadata(HAR), "meta");
    });
    assertTrue(readMetadata().path("versions").has("1.0.0"));
    assertFalse(records.get(PACKAGE.id()).attributes().containsKey("ohpmProxyMetadata"));
    assertTrue(readMetadata().path("versions").has("1.0.0"));
    assertTrue(records.get(PACKAGE.id()).attributes().containsKey("ohpmProxyMetadata"));
    verify(fetcher, times(2)).fetch(any());
  }

  @Test
  void nonProxyRuntimeCannotFetchOrPopulateTheProxyCache() {
    when(runtime.isProxy()).thenReturn(false);
    assertEquals(405, assertThrows(OhpmException.class, this::readMetadata).status());
    verifyNoInteractions(fetcher, writer);
  }

  private static byte[] metadata(byte[] bytes) throws IOException {
    var root = JSON.createObjectNode().put("name", PACKAGE.id()).put("_id", PACKAGE.id());
    root.putObject("dist-tags").put("latest", "1.0.0");
    root.putObject("versions").putObject("1.0.0").put("name", PACKAGE.id()).put("version", "1.0.0")
        .putObject("dist").put("integrity", OhpmIntegrity.compute(new ByteArrayInputStream(bytes)).value())
        .put("tarball", "https://upstream.example/ohpm/@scope/pkg/-/pkg-1.0.0.har");
    return JSON.writeValueAsBytes(root);
  }

  private static HttpRemoteFetcher.Result result(int status, byte[] bytes, String etag) {
    return new HttpRemoteFetcher.Result(status, etag == null ? Map.of() : Map.of("ETag", "\"" + etag + "\""),
        new ByteArrayInputStream(bytes));
  }

  private static AssetRecord copy(AssetRecord record, long id, Long blob, Instant updated, Map<String, Object> attributes) {
    return new AssetRecord(id, record.repositoryId(), record.componentId(), blob, record.format(), record.path(),
        record.pathHash(), record.name(), record.kind(), record.contentType(), record.size(), record.lastDownloadedAt(),
        updated, attributes);
  }
}
