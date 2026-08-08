package com.github.klboke.kkrepo.server.conda;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.core.RepositoryType;
import com.github.klboke.kkrepo.persistence.jdbc.api.CondaRegistryDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.PersistenceHashes;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetBlobRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.ComponentRecord;
import com.github.klboke.kkrepo.protocol.conda.CondaPathParser;
import com.github.klboke.kkrepo.server.cache.CachedAssetMetadata;
import com.github.klboke.kkrepo.server.maven.MavenExceptions;
import com.github.klboke.kkrepo.server.maven.MavenResponse;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntime;
import com.github.klboke.kkrepo.server.raw.RawProxyService;
import com.github.luben.zstd.Zstd;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;

class CondaServiceTest {
  private static final String MD5 = "0123456789abcdef0123456789abcdef";
  private static final String SHA_A = "a".repeat(64);
  private static final String SHA_B = "b".repeat(64);

  private final ObjectMapper mapper = new ObjectMapper();
  private CondaRegistryDao registry;
  private CondaArchiveInspector inspector;
  private CondaAssetSupport assets;
  private RawProxyService proxy;
  private CondaService service;
  private Map<String, InternalAsset> internalAssets;

  @BeforeEach
  void setUp() {
    registry = mock(CondaRegistryDao.class);
    inspector = new CondaArchiveInspector(mapper);
    assets = mock(CondaAssetSupport.class);
    proxy = mock(RawProxyService.class);
    internalAssets = new ConcurrentHashMap<>();
    doCallRealMethod().when(registry).visitPackages(
        anyLong(), anyString(), anyString(), anyString(), any());
    doCallRealMethod().when(registry).visitPreferredPackages(
        any(), anyString(), anyString(), anyString(), any());
    doCallRealMethod().when(registry).visitPackagesByChannel(
        anyLong(), anyString(), any());
    doCallRealMethod().when(registry).visitPreferredPackagesByChannel(
        any(), anyString(), any());
    doCallRealMethod().when(registry).findPreferredPackage(
        any(), anyString(), anyString(), anyString());
    doCallRealMethod().when(registry).findPreferredPackageFilenames(
        any(), anyString(), anyString(), any());
    doCallRealMethod().when(registry).currentRepositoryRevisions(any());
    doAnswer(invocation -> {
      RepositoryRuntime runtime = invocation.getArgument(0);
      String path = invocation.getArgument(1);
      Path file = invocation.getArgument(2);
      String contentType = invocation.getArgument(3);
      internalAssets.put(internalKey(runtime, path),
          new InternalAsset(Files.readAllBytes(file), contentType, Instant.now()));
      return null;
    }).when(assets).storeGenerated(
        any(RepositoryRuntime.class), anyString(), any(Path.class), anyString(), any());
    when(assets.findInternal(any(RepositoryRuntime.class), anyString())).thenAnswer(invocation -> {
      RepositoryRuntime runtime = invocation.getArgument(0);
      String path = invocation.getArgument(1);
      InternalAsset asset = internalAssets.get(internalKey(runtime, path));
      return asset == null ? Optional.empty() : Optional.of(cached(runtime, path, asset));
    });
    when(assets.serveInternal(
        any(RepositoryRuntime.class), anyString(), anyBoolean())).thenAnswer(invocation -> {
          RepositoryRuntime runtime = invocation.getArgument(0);
          String path = invocation.getArgument(1);
          boolean headOnly = invocation.getArgument(2);
          InternalAsset asset = internalAssets.get(internalKey(runtime, path));
          if (asset == null) throw new MavenExceptions.MavenNotFoundException(path);
          if (headOnly) {
            return MavenResponse.noBody(
                200, asset.body().length, asset.contentType(), digest(asset.body()),
                asset.updatedAt());
          }
          return MavenResponse.ok(
              new ByteArrayInputStream(asset.body()), asset.body().length, asset.contentType(),
              digest(asset.body()), asset.updatedAt());
        });
    when(registry.tryAcquireLease(anyString(), anyString(), any())).thenAnswer(invocation -> {
      String key = invocation.getArgument(0);
      String owner = invocation.getArgument(1);
      Instant expiresAt = invocation.getArgument(2);
      return Optional.of(new CondaRegistryDao.Lease(
          key, owner, 1, expiresAt, Instant.now()));
    });
    when(registry.renewLease(anyString(), anyString(), anyLong(), any())).thenReturn(true);
    service = new CondaService(
        registry,
        inspector,
        new CondaMetadataCodec(mapper),
        new CondaComponentFactory(),
        assets,
        new CondaLeaseManager(registry),
        proxy);
  }

  @Test
  void publishesHostedPackageBuildsAllRepodataVariantsAndTombstonesOnDelete()
      throws Exception {
    RepositoryRuntime hosted = runtime(1, "conda-hosted", RepositoryType.HOSTED, null, List.of());
    String path = "team/release/linux-64/demo-1.2.3-py312_0.tar.bz2";
    byte[] archive = CondaTestArchive.legacy(
        "demo", "1.2.3", "py312_0", 0, "linux-64");
    AssetRecord asset = asset(hosted, path, archive.length, 10L, 20L, 30L);
    AtomicReference<CondaRegistryDao.PackageRecord> published = new AtomicReference<>();
    when(assets.find(hosted, path)).thenReturn(Optional.empty());
    when(registry.findPackage(
        hosted.id(), "team/release", "linux-64", "demo-1.2.3-py312_0.tar.bz2"))
        .thenReturn(Optional.empty());
    when(assets.store(
        eq(hosted), eq(path), anyString(), any(Path.class), anyString(), any(),
        eq("alice"), eq("127.0.0.1"), any()))
        .thenReturn(asset);
    CondaAssetSupport.StagedAsset staged = new CondaAssetSupport.StagedAsset(
        ".conda/staging/test/package", blob(archive, sha256(archive)));
    when(assets.stage(
        eq(hosted), eq(path), any(Path.class), eq("application/x-tar"), any(),
        eq("alice"), eq("127.0.0.1"), anyString(), eq((long) archive.length)))
        .thenReturn(staged);
    when(assets.promote(
        eq(hosted), eq(path), anyString(), eq(staged), eq("application/x-tar"),
        eq("alice"), eq("127.0.0.1"), any()))
        .thenReturn(asset);
    when(registry.saveHostedPackage(any())).thenAnswer(invocation -> {
      CondaRegistryDao.PackageRecord record = invocation.getArgument(0);
      CondaRegistryDao.PackageRecord stored = record.withRevision(1, Instant.now());
      published.set(stored);
      return stored;
    });

    MavenResponse created = service.put(
        hosted,
        path,
        new ByteArrayInputStream(archive),
        "application/octet-stream",
        "alice",
        "127.0.0.1");

    assertEquals(201, created.status());
    CondaRegistryDao.PackageRecord record = published.get();
    assertEquals("team/release", record.channel());
    assertEquals("linux-64", record.subdir());
    assertEquals("demo", record.name());
    assertEquals(archive.length, record.size());
    assertFalse(record.metadata().containsKey("base_url"));
    ArgumentCaptor<Path> buffered = ArgumentCaptor.forClass(Path.class);
    verify(assets).stage(
        eq(hosted), eq(path), buffered.capture(), eq("application/x-tar"), any(),
        eq("alice"), eq("127.0.0.1"), anyString(), eq((long) archive.length));
    verify(assets).promote(
        eq(hosted), eq(path), eq("team/release/linux-64/demo/1.2.3/"
            + "demo-1.2.3-py312_0.tar.bz2"), eq(staged), eq("application/x-tar"),
        eq("alice"), eq("127.0.0.1"), any());
    verify(assets).discard(hosted, staged);
    assertFalse(Files.exists(buffered.getValue()));

    when(registry.listPackages(hosted.id(), "team/release", "linux-64"))
        .thenReturn(List.of(record));
    when(registry.listTombstones(hosted.id(), "team/release", "linux-64"))
        .thenReturn(List.of());
    when(registry.findChannelState(hosted.id(), "team/release", "linux-64"))
        .thenReturn(Optional.of(new CondaRegistryDao.ChannelState(
            hosted.id(), "team/release", "linux-64", null, null, 1, Instant.EPOCH,
            Instant.EPOCH)));

    JsonNode full = json(service.get(
        hosted, "team/release/linux-64/repodata.json", false));
    JsonNode current = json(service.get(
        hosted, "team/release/linux-64/current_repodata.json", false));
    assertEquals(full, current);
    assertTrue(full.path("packages").has("demo-1.2.3-py312_0.tar.bz2"));
    assertEquals(1, full.path("info").path("repodata_version").intValue());
    verify(registry, times(2)).listPackages(
        hosted.id(), "team/release", "linux-64");
    verify(assets, times(1)).storeGenerated(
        eq(hosted), anyString(), any(Path.class), anyString(), any());

    when(registry.tombstoneAndDeletePackage(
        eq(hosted.id()), eq("team/release"), eq("linux-64"),
        eq("demo-1.2.3-py312_0.tar.bz2"), eq("client-delete"), eq(0L), any()))
        .thenReturn(Optional.of(record));
    assertEquals(204, service.delete(hosted, path).status());
    verify(assets).delete(hosted, path);

    RepositoryRuntime proxyRuntime = runtime(
        2, "conda-proxy", RepositoryType.PROXY, "https://repo.example/channels/", List.of());
    assertThrows(MavenExceptions.MethodNotAllowed.class, () -> service.put(
        proxyRuntime, path, new ByteArrayInputStream(archive), null, null, null));
  }

  @Test
  void importsProxyInventoryPinsPackageUrlAndRejectsChecksumDrift() throws Exception {
    RepositoryRuntime proxyRuntime = runtime(
        2, "conda-proxy", RepositoryType.PROXY, "https://repo.example/channels/", List.of());
    String filename = "demo-2.0-py312_1.conda";
    String path = "main/linux-64/" + filename;
    byte[] artifact = "proxy-package".getBytes(StandardCharsets.UTF_8);
    String sha256 = sha256(artifact);
    byte[] repodata = ("""
        {"info":{"subdir":"linux-64","base_url":"https://packages.example/pool/"},
        "packages.conda":{"%s":{
          "name":"demo","version":"2.0","build":"py312_1","build_number":1,
          "subdir":"linux-64","size":%d,"sha256":"%s",
          "download_url":"https://must-not-leak.invalid/package"
        }}}
        """).formatted(filename, artifact.length, sha256).getBytes(StandardCharsets.UTF_8);
    cacheInternal(
        proxyRuntime,
        upstreamRepodataPath("main", "linux-64", "repodata.json.zst"),
        Zstd.compress(repodata));
    AtomicReference<CondaRegistryDao.PackageRecord> indexed = new AtomicReference<>();
    AtomicReference<String> packageBaseUrl = new AtomicReference<>();
    AtomicReference<String> inventorySha = new AtomicReference<>();
    when(registry.replaceProxyPackages(
        eq(proxyRuntime.id()), eq("main"), eq("linux-64"), anyString(), anyString(),
        any(CondaRegistryDao.PackageRecordSource.class), any()))
        .thenAnswer(invocation -> {
          inventorySha.set(invocation.getArgument(3));
          packageBaseUrl.set(invocation.getArgument(4));
          CondaRegistryDao.PackageRecordSource source = invocation.getArgument(5);
          java.util.ArrayList<CondaRegistryDao.PackageRecord> records = new java.util.ArrayList<>();
          source.visit(records::add);
          indexed.set(records.getFirst().withRevision(3, Instant.now()));
          return 3L;
        });
    when(registry.findPackage(proxyRuntime.id(), "main", "linux-64", filename))
        .thenAnswer(ignored -> Optional.ofNullable(indexed.get()));
    when(registry.findChannelState(proxyRuntime.id(), "main", "linux-64"))
        .thenAnswer(ignored -> Optional.of(new CondaRegistryDao.ChannelState(
            proxyRuntime.id(), "main", "linux-64", inventorySha.get(),
            packageBaseUrl.get(), 3, Instant.EPOCH, Instant.EPOCH)));
    when(proxy.getPinnedAssetFromUrlWithComponentAtBrowsePath(
        eq(proxyRuntime), eq(path),
        eq("https://packages.example/pool/" + filename), any(),
        eq("main/linux-64/demo/2.0/" + filename), eq(false)))
        .thenReturn(MavenResponse.ok(
            new ByteArrayInputStream(artifact), artifact.length,
            "application/octet-stream", sha256, Instant.EPOCH));
    when(assets.blob(proxyRuntime, path)).thenReturn(blob(artifact, sha256));

    MavenResponse response = service.get(proxyRuntime, path, false);

    assertEquals(200, response.status());
    assertEquals("proxy-package", new String(response.body().readAllBytes(), StandardCharsets.UTF_8));
    assertFalse(indexed.get().metadata().containsKey("base_url"));
    assertFalse(indexed.get().metadata().containsKey("download_url"));
    assertEquals("https://packages.example/pool/", packageBaseUrl.get());
    verify(proxy).getPinnedAssetFromUrlWithComponentAtBrowsePath(
        eq(proxyRuntime), eq(path),
        eq("https://packages.example/pool/" + filename), any(),
        eq("main/linux-64/demo/2.0/" + filename), eq(false));

    when(assets.blob(proxyRuntime, path)).thenReturn(blob(artifact, SHA_A));
    assertThrows(MavenExceptions.BadUpstreamException.class,
        () -> service.get(proxyRuntime, path, false));
    verify(assets).delete(proxyRuntime, path);
    verify(proxy, never()).getMetadataFromUrlUnindexed(
        eq(proxyRuntime), anyString(), anyString(), anyBoolean());
  }

  @Test
  void proxyRepodataCachesAndReturnsTheRequestedUpstreamRepresentationWithoutInventoryBuild()
      throws Exception {
    RepositoryRuntime proxyRuntime = runtime(
        2, "conda-proxy", RepositoryType.PROXY, "https://repo.example/channels/", List.of());
    CondaProxyInventoryScheduler scheduler = mock(CondaProxyInventoryScheduler.class);
    service = serviceWithScheduler(scheduler);
    byte[] upstream = "raw-bzip2-repodata".getBytes(StandardCharsets.UTF_8);
    when(proxy.getMetadataFromUrlUnindexed(
        eq(proxyRuntime), anyString(),
        eq("https://repo.example/channels/main/linux-64/repodata.json.bz2"), eq(false)))
        .thenReturn(MavenResponse.ok(
            new ByteArrayInputStream(upstream), upstream.length, "application/x-bzip2", null,
            Instant.EPOCH));

    MavenResponse response = service.get(
        proxyRuntime, "main/linux-64/repodata.json.bz2", false);

    assertEquals("raw-bzip2-repodata",
        new String(response.body().readAllBytes(), StandardCharsets.UTF_8));
    verify(proxy).getMetadataFromUrlUnindexed(
        eq(proxyRuntime), anyString(),
        eq("https://repo.example/channels/main/linux-64/repodata.json.bz2"), eq(false));
    verify(registry, never()).replaceProxyPackages(
        anyLong(), anyString(), anyString(), anyString(), any(),
        any(CondaRegistryDao.PackageRecordSource.class), any());
  }

  @Test
  void proxyJsonRepodataStreamsDecodedZstdWithoutCachingTheLargeJsonRepresentation()
      throws Exception {
    RepositoryRuntime proxyRuntime = runtime(
        2, "conda-proxy", RepositoryType.PROXY, "https://repo.example/channels/", List.of());
    byte[] upstream = "{\"info\":{\"subdir\":\"linux-64\"},\"packages\":{}}"
        .getBytes(StandardCharsets.UTF_8);
    byte[] compact = Zstd.compress(upstream);
    when(proxy.getMetadataFromUrlUnindexed(
        eq(proxyRuntime),
        eq(upstreamRepodataPath("main", "linux-64", "repodata.json.zst")),
        eq("https://repo.example/channels/main/linux-64/repodata.json.zst"),
        eq(false)))
        .thenAnswer(ignored -> MavenResponse.ok(
            new ByteArrayInputStream(compact), compact.length, "application/zstd", "zstd-etag",
            Instant.EPOCH));

    MavenResponse response = service.get(
        proxyRuntime, "main/linux-64/repodata.json", false);

    assertEquals("application/json", response.contentType());
    assertEquals(upstream.length, response.contentLength());
    assertEquals(
        new String(upstream, StandardCharsets.UTF_8),
        new String(response.body().readAllBytes(), StandardCharsets.UTF_8));
    assertFalse("zstd-etag".equals(response.etag()));
    MavenResponse head = service.get(
        proxyRuntime, "main/linux-64/repodata.json", true);
    assertFalse(head.hasBody());
    assertEquals(upstream.length, head.contentLength());
    verify(proxy, times(2)).getMetadataFromUrlUnindexed(
        eq(proxyRuntime),
        eq(upstreamRepodataPath("main", "linux-64", "repodata.json.zst")),
        eq("https://repo.example/channels/main/linux-64/repodata.json.zst"),
        eq(false));
    verify(proxy, never()).getMetadataFromUrlUnindexed(
        eq(proxyRuntime), anyString(),
        eq("https://repo.example/channels/main/linux-64/repodata.json"), anyBoolean());
  }

  @Test
  void proxyJsonRepodataFallsBackToDecodedBzip2BeforeRawJson() throws Exception {
    RepositoryRuntime proxyRuntime = runtime(
        2, "conda-proxy", RepositoryType.PROXY, "https://repo.example/channels/", List.of());
    byte[] upstream = "{\"info\":{\"subdir\":\"noarch\"},\"packages\":{}}"
        .getBytes(StandardCharsets.UTF_8);
    byte[] compact = bzip2(upstream);
    when(proxy.getMetadataFromUrlUnindexed(
        eq(proxyRuntime), anyString(),
        eq("https://repo.example/channels/main/noarch/repodata.json.zst"), eq(false)))
        .thenThrow(new MavenExceptions.MavenNotFoundException("zstd missing"));
    when(proxy.getMetadataFromUrlUnindexed(
        eq(proxyRuntime),
        eq(upstreamRepodataPath("main", "noarch", "repodata.json.bz2")),
        eq("https://repo.example/channels/main/noarch/repodata.json.bz2"), eq(false)))
        .thenReturn(MavenResponse.ok(
            new ByteArrayInputStream(compact), compact.length, "application/x-bzip2", "bz2-etag",
            Instant.EPOCH));

    MavenResponse response = service.get(
        proxyRuntime, "main/noarch/repodata.json", false);

    assertEquals(-1, response.contentLength());
    assertEquals(
        new String(upstream, StandardCharsets.UTF_8),
        new String(response.body().readAllBytes(), StandardCharsets.UTF_8));
    verify(proxy, never()).getMetadataFromUrlUnindexed(
        eq(proxyRuntime), anyString(),
        eq("https://repo.example/channels/main/noarch/repodata.json"), anyBoolean());
  }

  @Test
  void proxyJsonRepodataKeepsRawFallbackForLegacyUpstreams() throws Exception {
    RepositoryRuntime proxyRuntime = runtime(
        2, "conda-proxy", RepositoryType.PROXY, "https://repo.example/channels/", List.of());
    byte[] upstream = "{\"info\":{\"subdir\":\"linux-64\"},\"packages\":{}}"
        .getBytes(StandardCharsets.UTF_8);
    when(proxy.getMetadataFromUrlUnindexed(
        eq(proxyRuntime), anyString(),
        eq("https://repo.example/channels/main/linux-64/repodata.json.zst"), eq(false)))
        .thenThrow(new MavenExceptions.MavenNotFoundException("zstd missing"));
    when(proxy.getMetadataFromUrlUnindexed(
        eq(proxyRuntime), anyString(),
        eq("https://repo.example/channels/main/linux-64/repodata.json.bz2"), eq(false)))
        .thenThrow(new MavenExceptions.MavenNotFoundException("bzip2 missing"));
    when(proxy.getMetadataFromUrlUnindexed(
        eq(proxyRuntime),
        eq(upstreamRepodataPath("main", "linux-64")),
        eq("https://repo.example/channels/main/linux-64/repodata.json"), eq(false)))
        .thenReturn(MavenResponse.ok(
            new ByteArrayInputStream(upstream), upstream.length, "application/json", "json-etag",
            Instant.EPOCH));

    MavenResponse response = service.get(
        proxyRuntime, "main/linux-64/repodata.json", false);

    assertEquals(upstream.length, response.contentLength());
    assertEquals("json-etag", response.etag());
    assertEquals(
        new String(upstream, StandardCharsets.UTF_8),
        new String(response.body().readAllBytes(), StandardCharsets.UTF_8));
    verify(proxy).getMetadataFromUrlUnindexed(
        eq(proxyRuntime),
        eq(upstreamRepodataPath("main", "linux-64")),
        eq("https://repo.example/channels/main/linux-64/repodata.json"), eq(false));
  }

  @Test
  void firstProxyPackageWritesNexusBrowsePathWithoutInventoryProjection()
      throws Exception {
    RepositoryRuntime proxyRuntime = runtime(
        2, "conda-proxy", RepositoryType.PROXY, "https://repo.example/channels/", List.of());
    CondaProxyInventoryScheduler scheduler = mock(CondaProxyInventoryScheduler.class);
    service = serviceWithScheduler(scheduler);
    String path = "main/noarch/demo-1.0-0.conda";
    byte[] upstream = "package".getBytes(StandardCharsets.UTF_8);
    when(registry.findPackage(proxyRuntime.id(), "main", "noarch", "demo-1.0-0.conda"))
        .thenReturn(Optional.empty());
    when(proxy.getPinnedAssetFromUrlWithComponentAtBrowsePath(
        eq(proxyRuntime), eq(path), eq("https://repo.example/channels/" + path), any(),
        eq("main/noarch/demo/1.0/demo-1.0-0.conda"), eq(false)))
        .thenReturn(MavenResponse.ok(
            new ByteArrayInputStream(upstream), upstream.length,
            "application/vnd.conda.package.v2", null, Instant.EPOCH));

    MavenResponse response = service.get(proxyRuntime, path, false);

    assertEquals("package", new String(response.body().readAllBytes(), StandardCharsets.UTF_8));
    ArgumentCaptor<ComponentRecord> component = ArgumentCaptor.forClass(ComponentRecord.class);
    verify(proxy).getPinnedAssetFromUrlWithComponentAtBrowsePath(
        eq(proxyRuntime), eq(path), eq("https://repo.example/channels/" + path),
        component.capture(), eq("main/noarch/demo/1.0/demo-1.0-0.conda"), eq(false));
    assertEquals("main/noarch", component.getValue().namespace());
    assertEquals("demo", component.getValue().name());
    assertEquals("1.0", component.getValue().version());
    verify(scheduler, never()).schedule(anyString(), any(Runnable.class));
    verify(proxy, never()).getMetadataFromUrlUnindexed(
        eq(proxyRuntime), anyString(), anyString(), anyBoolean());
  }

  @Test
  void singleProxyGroupUsesTheMemberColdPathWithoutBuildingAnInventory() throws Exception {
    RepositoryRuntime member = runtime(
        8, "conda-proxy", RepositoryType.PROXY, "https://repo.example/channels/", List.of());
    RepositoryRuntime group = runtime(
        9, "conda-group", RepositoryType.GROUP, null, List.of(member));
    CondaProxyInventoryScheduler scheduler = mock(CondaProxyInventoryScheduler.class);
    service = serviceWithScheduler(scheduler);
    byte[] repodata = "raw-repodata".getBytes(StandardCharsets.UTF_8);
    byte[] archive = "raw-package".getBytes(StandardCharsets.UTF_8);
    String packagePath = "main/noarch/demo-1.0-0.conda";
    when(proxy.getMetadataFromUrlUnindexed(
        eq(member), anyString(),
        eq("https://repo.example/channels/main/noarch/repodata.json.bz2"), eq(false)))
        .thenReturn(MavenResponse.ok(
            new ByteArrayInputStream(repodata), repodata.length, "application/x-bzip2", null,
            Instant.EPOCH));
    when(proxy.getPinnedAssetFromUrlWithComponentAtBrowsePath(
        eq(member), eq(packagePath),
        eq("https://repo.example/channels/" + packagePath), any(),
        eq("main/noarch/demo/1.0/demo-1.0-0.conda"), eq(false)))
        .thenReturn(MavenResponse.ok(
            new ByteArrayInputStream(archive), archive.length,
            "application/vnd.conda.package.v2", null, Instant.EPOCH));

    MavenResponse metadataResponse = service.get(
        group, "main/noarch/repodata.json.bz2", false);
    MavenResponse packageResponse = service.get(group, packagePath, false);

    assertEquals("raw-repodata",
        new String(metadataResponse.body().readAllBytes(), StandardCharsets.UTF_8));
    assertEquals("raw-package",
        new String(packageResponse.body().readAllBytes(), StandardCharsets.UTF_8));
    verify(registry, never()).replaceProxyPackages(
        anyLong(), anyString(), anyString(), anyString(), any(),
        any(CondaRegistryDao.PackageRecordSource.class), any());
    verify(scheduler, never()).schedule(anyString(), any(Runnable.class));
  }

  @Test
  void mixedGroupMergesRawProxyRepodataWithoutSynchronousInventoryImport() throws Exception {
    RepositoryRuntime hosted = runtime(
        16, "conda-hosted", RepositoryType.HOSTED, null, List.of());
    RepositoryRuntime proxyMember = runtime(
        17, "conda-proxy", RepositoryType.PROXY,
        "https://repo.example/channels/", List.of());
    RepositoryRuntime group = runtime(
        18, "conda-group", RepositoryType.GROUP, null, List.of(hosted, proxyMember));
    CondaProxyInventoryScheduler scheduler = mock(CondaProxyInventoryScheduler.class);
    service = serviceWithScheduler(scheduler);
    String hostedFilename = "demo-1.0-0.tar.bz2";
    String proxyFilename = "proxy-only-2.0-0.tar.bz2";
    CondaRegistryDao.PackageRecord hostedRecord = record(
        hosted.id(), hostedFilename, SHA_A, "hosted wins");
    byte[] upstream = ("""
        {"info":{"subdir":"linux-64"},"packages":{
          "%s":{"name":"demo","version":"1.0","build":"0","build_number":0,
            "subdir":"linux-64","size":5,"md5":"%s","sha256":"%s",
            "summary":"proxy loses"},
          "%s":{"name":"proxy-only","version":"2.0","build":"0","build_number":0,
            "subdir":"linux-64","size":7,"md5":"%s","sha256":"%s",
            "summary":"proxy visible","base_url":"https://must-not-leak.invalid/",
            "nested":{"download_url":"https://must-not-leak.invalid/package"}}},
          "packages.conda":{},"removed":["retired-0.1-0.tar.bz2"]}
        """).formatted(hostedFilename, MD5, SHA_B, proxyFilename, MD5, SHA_B)
        .getBytes(StandardCharsets.UTF_8);
    when(registry.findChannelState(hosted.id(), "main", "linux-64"))
        .thenReturn(Optional.of(new CondaRegistryDao.ChannelState(
            hosted.id(), "main", "linux-64", null, null, 1,
            Instant.EPOCH, Instant.EPOCH)));
    when(registry.listPackages(hosted.id(), "main", "linux-64"))
        .thenReturn(List.of(hostedRecord));
    when(registry.listTombstones(hosted.id(), "main", "linux-64"))
        .thenReturn(List.of());
    cacheInternal(proxyMember, upstreamRepodataPath("main", "linux-64"), upstream);

    JsonNode repodata = json(service.get(
        group, "main/linux-64/repodata.json", false));

    assertEquals("hosted wins",
        repodata.path("packages").path(hostedFilename).path("summary").textValue());
    assertEquals("proxy visible",
        repodata.path("packages").path(proxyFilename).path("summary").textValue());
    assertFalse(repodata.path("packages").path(proxyFilename).has("base_url"));
    assertFalse(repodata.path("packages").path(proxyFilename).path("nested")
        .has("download_url"));
    assertEquals("retired-0.1-0.tar.bz2", repodata.path("removed").get(0).textValue());
    verify(registry, never()).replaceProxyPackages(
        anyLong(), anyString(), anyString(), anyString(), any(),
        any(CondaRegistryDao.PackageRecordSource.class), any());
    verify(scheduler, never()).schedule(anyString(), any(Runnable.class));
  }

  @Test
  void mixedGroupPrefersAndDecodesCachedZstdProxyRepodata() throws Exception {
    RepositoryRuntime hosted = runtime(
        26, "conda-hosted", RepositoryType.HOSTED, null, List.of());
    RepositoryRuntime proxyMember = runtime(
        27, "conda-proxy", RepositoryType.PROXY,
        "https://repo.example/channels/", List.of());
    RepositoryRuntime group = runtime(
        28, "conda-group", RepositoryType.GROUP, null, List.of(hosted, proxyMember));
    byte[] upstream = ("""
        {"info":{"subdir":"linux-64"},"packages":{
          "proxy-only-2.0-0.tar.bz2":{"name":"proxy-only","version":"2.0","build":"0",
            "build_number":0,"subdir":"linux-64","size":7,"md5":"%s","sha256":"%s"}},
          "packages.conda":{},"removed":[]}
        """).formatted(MD5, SHA_B).getBytes(StandardCharsets.UTF_8);
    when(registry.findChannelState(hosted.id(), "main", "linux-64"))
        .thenReturn(Optional.empty());
    when(registry.listTombstones(hosted.id(), "main", "linux-64"))
        .thenReturn(List.of());
    cacheInternal(
        proxyMember,
        upstreamRepodataPath("main", "linux-64", "repodata.json.zst"),
        Zstd.compress(upstream));

    JsonNode repodata = json(service.get(
        group, "main/linux-64/repodata.json", false));

    assertTrue(repodata.path("packages").has("proxy-only-2.0-0.tar.bz2"));
    verify(proxy, never()).getMetadataFromUrlUnindexed(
        eq(proxyMember), anyString(), anyString(), anyBoolean());
  }

  @Test
  void mixedGroupServesFirstCanonicalProxyPackageBeforeInventoryImport() throws Exception {
    RepositoryRuntime hosted = runtime(
        22, "conda-hosted", RepositoryType.HOSTED, null, List.of());
    RepositoryRuntime proxyMember = runtime(
        23, "conda-proxy", RepositoryType.PROXY,
        "https://repo.example/channels/", List.of());
    RepositoryRuntime group = runtime(
        24, "conda-group", RepositoryType.GROUP, null, List.of(hosted, proxyMember));
    CondaProxyInventoryScheduler scheduler = mock(CondaProxyInventoryScheduler.class);
    service = serviceWithScheduler(scheduler);
    String packagePath = "main/noarch/demo-1.0-0.conda";
    byte[] archive = "proxy-package".getBytes(StandardCharsets.UTF_8);
    when(registry.findPackage(anyLong(), eq("main"), eq("noarch"),
        eq("demo-1.0-0.conda"))).thenReturn(Optional.empty());
    when(proxy.getPinnedAssetFromUrlWithComponentAtBrowsePath(
        eq(proxyMember), eq(packagePath),
        eq("https://repo.example/channels/" + packagePath), any(),
        eq("main/noarch/demo/1.0/demo-1.0-0.conda"), eq(false)))
        .thenReturn(MavenResponse.ok(
            new ByteArrayInputStream(archive), archive.length,
            "application/vnd.conda.package.v2", null, Instant.EPOCH));

    MavenResponse response = service.get(group, packagePath, false);

    assertEquals("proxy-package",
        new String(response.body().readAllBytes(), StandardCharsets.UTF_8));
    verify(proxy).getPinnedAssetFromUrlWithComponentAtBrowsePath(
        eq(proxyMember), eq(packagePath),
        eq("https://repo.example/channels/" + packagePath), any(),
        eq("main/noarch/demo/1.0/demo-1.0-0.conda"), eq(false));
    verify(scheduler, never()).schedule(anyString(), any(Runnable.class));
    verify(registry, never()).replaceProxyPackages(
        anyLong(), anyString(), anyString(), anyString(), any(),
        any(CondaRegistryDao.PackageRecordSource.class), any());
  }

  @Test
  void uncommonFilenameKeepsDeferredInventoryFallback() throws Exception {
    RepositoryRuntime proxyRuntime = runtime(
        25, "conda-proxy", RepositoryType.PROXY, "https://repo.example/channels/", List.of());
    CondaProxyInventoryScheduler scheduler = mock(CondaProxyInventoryScheduler.class);
    service = serviceWithScheduler(scheduler);
    String path = "main/noarch/demo-beta@-0.conda";
    byte[] upstream = "package".getBytes(StandardCharsets.UTF_8);
    when(registry.findPackage(proxyRuntime.id(), "main", "noarch", "demo-beta@-0.conda"))
        .thenReturn(Optional.empty());
    when(proxy.getPinnedAssetFromUrlUnindexed(
        proxyRuntime, path, "https://repo.example/channels/" + path, false))
        .thenReturn(MavenResponse.ok(
            new ByteArrayInputStream(upstream), upstream.length,
            "application/vnd.conda.package.v2", null, Instant.EPOCH));

    MavenResponse response = service.get(proxyRuntime, path, false);

    assertEquals("package", new String(response.body().readAllBytes(), StandardCharsets.UTF_8));
    verify(proxy).getPinnedAssetFromUrlUnindexed(
        proxyRuntime, path, "https://repo.example/channels/" + path, false);
    verify(scheduler).schedule(eq("25:main:noarch"), any(Runnable.class));
  }

  @Test
  void groupMetadataAndPackageBytesStayPinnedToTheFirstMember() throws Exception {
    RepositoryRuntime first = runtime(10, "conda-first", RepositoryType.HOSTED, null, List.of());
    RepositoryRuntime second = runtime(11, "conda-second", RepositoryType.HOSTED, null, List.of());
    RepositoryRuntime group = runtime(
        12, "conda-group", RepositoryType.GROUP, null, List.of(first, second));
    String filename = "demo-1.0-0.tar.bz2";
    String path = "main/linux-64/" + filename;
    CondaRegistryDao.PackageRecord firstRecord = record(
        first.id(), filename, SHA_A, "first member");
    CondaRegistryDao.PackageRecord secondRecord = record(
        second.id(), filename, SHA_B, "second member");
    when(registry.listPackages(first.id(), "main", "linux-64"))
        .thenReturn(List.of(firstRecord));
    when(registry.listPackages(second.id(), "main", "linux-64"))
        .thenReturn(List.of(secondRecord));
    when(registry.listTombstones(anyLong(), eq("main"), eq("linux-64")))
        .thenReturn(List.of());
    when(registry.findChannelState(anyLong(), eq("main"), eq("linux-64")))
        .thenAnswer(invocation -> Optional.of(new CondaRegistryDao.ChannelState(
            invocation.getArgument(0), "main", "linux-64", null, null, 1,
            Instant.EPOCH, Instant.EPOCH)));
    when(registry.currentRepositoryRevision(anyLong())).thenAnswer(invocation -> switch (
        ((Long) invocation.getArgument(0)).intValue()) {
      case 10 -> 7L;
      case 11 -> 8L;
      case 12 -> 9L;
      default -> 0L;
    });
    AtomicReference<CondaRegistryDao.GroupSourceBinding> binding = new AtomicReference<>();
    doAnswer(invocation -> {
      binding.set(invocation.getArgument(0));
      return null;
    }).when(registry).upsertGroupSourceBinding(any());

    JsonNode repodata = json(service.get(group, "main/linux-64/repodata.json", false));

    assertEquals("first member",
        repodata.path("packages").path(filename).path("summary").textValue());
    assertNull(binding.get());
    clearInvocations(registry);

    when(registry.findGroupSourceBinding(group.id(), "main", "linux-64", filename))
        .thenAnswer(ignored -> Optional.ofNullable(binding.get()));
    when(registry.findPackage(first.id(), "main", "linux-64", filename))
        .thenReturn(Optional.of(firstRecord));
    byte[] bytes = "first".getBytes(StandardCharsets.UTF_8);
    when(assets.serve(first, path, false)).thenReturn(MavenResponse.ok(
        new ByteArrayInputStream(bytes), bytes.length, "application/octet-stream", SHA_A,
        Instant.EPOCH));

    MavenResponse artifact = service.get(group, path, false);

    assertEquals("first", new String(artifact.body().readAllBytes(), StandardCharsets.UTF_8));
    assertEquals(first.id(), binding.get().memberRepositoryId());
    assertEquals(SHA_A, binding.get().sha256());
    verify(assets).serve(first, path, false);
    verify(assets, never()).serve(second, path, false);
    verify(registry, never()).listPackages(anyLong(), anyString(), anyString());
  }

  @Test
  void groupTreatsMissingProxySubdirAsEmptyWhenHostedMemberHasRecords() throws Exception {
    RepositoryRuntime hosted = runtime(
        13, "conda-hosted", RepositoryType.HOSTED, null, List.of());
    RepositoryRuntime proxyMember = runtime(
        14, "conda-proxy", RepositoryType.PROXY,
        "https://repo.example/channels/", List.of());
    RepositoryRuntime group = runtime(
        15, "conda-group", RepositoryType.GROUP, null, List.of(hosted, proxyMember));
    String filename = "demo-1.0-0.tar.bz2";
    CondaRegistryDao.PackageRecord hostedRecord = record(
        hosted.id(), "team/release", filename, SHA_A, "hosted member");

    when(registry.currentRepositoryRevision(group.id())).thenReturn(3L);
    when(registry.listPackages(hosted.id(), "team/release", "linux-64"))
        .thenReturn(List.of(hostedRecord));
    when(registry.listTombstones(anyLong(), eq("team/release"), eq("linux-64")))
        .thenReturn(List.of());
    when(registry.findChannelState(hosted.id(), "team/release", "linux-64"))
        .thenReturn(Optional.of(new CondaRegistryDao.ChannelState(
            hosted.id(), "team/release", "linux-64", null, null, 1,
            Instant.EPOCH, Instant.EPOCH)));
    when(proxy.getMetadataFromUrlUnindexed(
        eq(proxyMember), anyString(),
        anyString(), eq(true)))
        .thenThrow(new MavenExceptions.MavenNotFoundException("missing proxy subdir"));

    JsonNode repodata = json(service.get(
        group, "team/release/linux-64/repodata.json", false));

    assertEquals("hosted member",
        repodata.path("packages").path(filename).path("summary").textValue());
    verify(proxy, times(3)).getMetadataFromUrlUnindexed(
        eq(proxyMember), anyString(), anyString(), eq(true));
  }

  @Test
  void groupRefreshesProxyInventoryBeforeServingAPersistedBinding() throws Exception {
    RepositoryRuntime hosted = runtime(
        19, "conda-hosted", RepositoryType.HOSTED, null, List.of());
    RepositoryRuntime member = runtime(
        20, "conda-proxy", RepositoryType.PROXY, "https://repo.example/channels/", List.of());
    RepositoryRuntime group = runtime(
        21, "conda-group", RepositoryType.GROUP, null, List.of(hosted, member));
    String filename = "demo-1.0-0.conda";
    String path = "main/linux-64/" + filename;
    byte[] bytes = "new-package".getBytes(StandardCharsets.UTF_8);
    String newSha = sha256(bytes);
    byte[] upstream = ("""
        {"info":{"subdir":"linux-64","base_url":"https://packages.example/pool/"},
         "packages.conda":{"%s":{"name":"demo","version":"1.0","build":"0",
         "build_number":0,"subdir":"linux-64","size":%d,"sha256":"%s"}}}
        """).formatted(filename, bytes.length, newSha).getBytes(StandardCharsets.UTF_8);
    AtomicLong memberRevision = new AtomicLong(7);
    AtomicReference<CondaRegistryDao.PackageRecord> current = new AtomicReference<>(
        proxyRecord(member.id(), filename, SHA_A, bytes.length, 7));
    AtomicReference<CondaRegistryDao.GroupSourceBinding> binding = new AtomicReference<>(
        new CondaRegistryDao.GroupSourceBinding(
            group.id(), "main", "linux-64", filename, member.id(), 7, SHA_A, 9,
            Instant.EPOCH, Instant.EPOCH));

    when(registry.currentRepositoryRevision(anyLong())).thenAnswer(invocation ->
        ((Long) invocation.getArgument(0)) == group.id() ? 9L : memberRevision.get());
    when(registry.findGroupSourceBinding(group.id(), "main", "linux-64", filename))
        .thenAnswer(ignored -> Optional.of(binding.get()));
    when(proxy.getMetadataFromUrlUnindexed(
        eq(member), anyString(),
        eq("https://repo.example/channels/main/linux-64/repodata.json"), eq(true)))
        .thenAnswer(ignored -> MavenResponse.ok(
            new ByteArrayInputStream(upstream), upstream.length, "application/json", null, null));
    cacheInternal(member, upstreamRepodataPath("main", "linux-64"), upstream);
    when(registry.replaceProxyPackages(
        eq(member.id()), eq("main"), eq("linux-64"), anyString(), anyString(),
        any(CondaRegistryDao.PackageRecordSource.class), any()))
        .thenAnswer(invocation -> {
          CondaRegistryDao.PackageRecordSource source = invocation.getArgument(5);
          java.util.ArrayList<CondaRegistryDao.PackageRecord> records = new java.util.ArrayList<>();
          source.visit(records::add);
          memberRevision.set(8);
          current.set(records.getFirst().withRevision(8, Instant.now()));
          return 8L;
        });
    when(registry.findPackage(member.id(), "main", "linux-64", filename))
        .thenAnswer(ignored -> Optional.of(current.get()));
    when(registry.listPackages(member.id(), "main", "linux-64"))
        .thenAnswer(ignored -> List.of(current.get()));
    when(registry.listTombstones(member.id(), "main", "linux-64")).thenReturn(List.of());
    when(registry.findChannelState(member.id(), "main", "linux-64"))
        .thenAnswer(ignored -> Optional.of(new CondaRegistryDao.ChannelState(
            member.id(), "main", "linux-64", "d".repeat(64),
            "https://packages.example/pool/", memberRevision.get(), Instant.EPOCH,
            Instant.EPOCH)));
    doAnswer(invocation -> {
      binding.set(invocation.getArgument(0));
      return null;
    }).when(registry).upsertGroupSourceBinding(any());
    when(proxy.getPinnedAssetFromUrlWithComponentAtBrowsePath(
        eq(member), eq(path), eq("https://packages.example/pool/" + filename), any(),
        eq("main/linux-64/demo/1.0/" + filename), eq(false)))
        .thenReturn(MavenResponse.ok(
            new ByteArrayInputStream(bytes), bytes.length, "application/octet-stream", newSha,
            Instant.EPOCH));
    when(assets.blob(member, path)).thenReturn(blob(bytes, newSha));

    MavenResponse response = service.get(group, path, false);

    assertEquals("new-package", new String(response.body().readAllBytes(), StandardCharsets.UTF_8));
    assertEquals(8, binding.get().memberRevision());
    assertEquals(newSha, binding.get().sha256());
  }

  @Test
  void channeldataIsStreamedOncePerRepositoryRevision() throws Exception {
    RepositoryRuntime hosted = runtime(30, "conda-hosted", RepositoryType.HOSTED, null, List.of());
    CondaRegistryDao.PackageRecord record = record(
        hosted.id(), "demo-1.0-0.tar.bz2", SHA_A, "demo");
    when(registry.currentRepositoryRevision(hosted.id())).thenReturn(4L);
    when(registry.listPackagesByChannel(hosted.id(), "main")).thenReturn(List.of(record));

    JsonNode first = json(service.get(hosted, "main/channeldata.json", false));
    JsonNode second = json(service.get(hosted, "main/channeldata.json", false));

    assertEquals(first, second);
    assertEquals("1.0", first.path("packages").path("demo").path("version").textValue());
    verify(registry, times(1)).listPackagesByChannel(hosted.id(), "main");
  }

  @Test
  void repodataCacheIdentityIsScopedToTheRequestedChannelSubdir() throws Exception {
    RepositoryRuntime hosted = runtime(31, "conda-hosted", RepositoryType.HOSTED, null, List.of());
    CondaRegistryDao.PackageRecord record = record(
        hosted.id(), "demo-1.0-0.tar.bz2", SHA_A, "demo");
    AtomicLong noarchRevision = new AtomicLong(1);
    when(registry.findChannelState(eq(hosted.id()), eq("main"), anyString()))
        .thenAnswer(invocation -> {
          String subdir = invocation.getArgument(2);
          long revision = "noarch".equals(subdir) ? noarchRevision.get() : 7;
          return Optional.of(new CondaRegistryDao.ChannelState(
              hosted.id(), "main", subdir, null, null, revision,
              Instant.EPOCH, Instant.EPOCH));
        });
    when(registry.listPackages(hosted.id(), "main", "linux-64")).thenReturn(List.of(record));
    when(registry.listTombstones(hosted.id(), "main", "linux-64")).thenReturn(List.of());

    JsonNode first = json(service.get(hosted, "main/linux-64/repodata.json", false));
    noarchRevision.incrementAndGet();
    JsonNode second = json(service.get(hosted, "main/linux-64/repodata.json", false));

    assertEquals(first, second);
    // One pass renders packages and one renders packages.conda; the second request is a cache hit.
    verify(registry, times(2)).listPackages(hosted.id(), "main", "linux-64");
  }

  @Test
  void servesRootAndOptionalNoticesAndRejectsInvalidRuntimeAndPaths() throws Exception {
    RepositoryRuntime hosted = runtime(
        40, "conda<&\"hosted", RepositoryType.HOSTED, null, List.of());

    MavenResponse root = service.get(hosted, "", false);
    String html = new String(root.body().readAllBytes(), StandardCharsets.UTF_8);
    assertTrue(html.contains("conda&lt;&amp;&quot;hosted"));
    MavenResponse rootHead = service.get(hosted, "/", true);
    assertFalse(rootHead.hasBody());
    assertEquals(root.contentLength(), rootHead.contentLength());

    JsonNode notices = json(service.get(hosted, "main/notices.json", false));
    assertTrue(notices.path("notices").isArray());
    MavenResponse noticesHead = service.get(hosted, "main/notices.json", true);
    assertFalse(noticesHead.hasBody());
    assertEquals("application/json", noticesHead.contentType());

    assertThrows(MavenExceptions.MavenNotFoundException.class,
        () -> service.get(hosted, "main/linux-64/repodata_shards.msgpack.zst", false));
    assertThrows(MavenExceptions.MavenNotFoundException.class,
        () -> service.get(hosted, "main/linux-64/unknown.bin", false));
    assertThrows(MavenExceptions.MavenNotFoundException.class,
        () -> service.get(hosted, "main/linux-64/missing-1.0-0.conda", false));
    assertThrows(MavenExceptions.MethodNotAllowed.class,
        () -> service.get(null, "", false));
    assertThrows(MavenExceptions.MethodNotAllowed.class,
        () -> service.get(runtimeWith(
            41, "raw", RepositoryFormat.RAW, RepositoryType.HOSTED, true, "ALLOW"), "", false));
    assertThrows(MavenExceptions.MavenNotFoundException.class,
        () -> service.get(runtimeWith(
            42, "offline", RepositoryFormat.CONDA, RepositoryType.HOSTED, false, "ALLOW"),
            "", false));
  }

  @Test
  void sanitizesProxyChannelJsonAndFallsBackForOptionalMetadata() throws Exception {
    RepositoryRuntime proxyRuntime = runtime(
        43, "conda-proxy", RepositoryType.PROXY, "https://repo.example/channels/", List.of());
    when(proxy.getMetadataFromUrlUnindexed(
        eq(proxyRuntime), anyString(), anyString(), eq(true))).thenAnswer(invocation -> {
          String localPath = invocation.getArgument(1);
          String remoteUrl = invocation.getArgument(2);
          String body = remoteUrl.endsWith("notices.json")
              ? "{\"notices\":[],\"download_url\":\"secret\"}"
              : "{\"channeldata_version\":1,\"packages\":{},\"base_url\":\"secret\"}";
          cacheInternal(proxyRuntime, localPath, body.getBytes(StandardCharsets.UTF_8));
          return MavenResponse.noBody(200);
        });

    JsonNode channeldata = json(service.get(proxyRuntime, "main/channeldata.json", false));
    assertFalse(channeldata.has("base_url"));
    JsonNode notices = json(service.get(proxyRuntime, "main/notices.json", false));
    assertFalse(notices.has("download_url"));
    MavenResponse cachedHead = service.get(proxyRuntime, "main/channeldata.json", true);
    assertFalse(cachedHead.hasBody());

    RepositoryRuntime optional = runtime(
        44, "optional", RepositoryType.PROXY, "https://repo.example/channels/", List.of());
    when(proxy.getMetadataFromUrlUnindexed(
        eq(optional), anyString(), anyString(), eq(true)))
        .thenThrow(new MavenExceptions.MavenNotFoundException("optional metadata missing"));
    assertTrue(json(service.get(optional, "main/notices.json", false))
        .path("notices").isArray());
    when(registry.currentRepositoryRevision(optional.id())).thenReturn(0L);
    assertTrue(json(service.get(optional, "main/channeldata.json", false))
        .path("packages").isObject());
  }

  @Test
  void migrationRestoreIsIdempotentAndRejectsContentDrift() throws Exception {
    RepositoryRuntime hosted = runtime(
        45, "conda-hosted", RepositoryType.HOSTED, null, List.of());
    String path = "main/linux-64/demo-1.0-0.conda";
    byte[] archive = CondaTestArchive.modern("demo", "1.0", "0", 0, "linux-64");
    CondaArchiveInspector.InspectedPackage inspected = inspector.inspect(
        new ByteArrayInputStream(archive), "demo-1.0-0.conda", "linux-64");
    AssetRecord asset = asset(hosted, path, archive.length, 101, 102, 103);
    CondaAssetSupport.StagedAsset staged = new CondaAssetSupport.StagedAsset(
        ".conda/staging/migration/package", blob(archive, inspected.sha256()));
    AtomicReference<CondaRegistryDao.PackageRecord> stored = new AtomicReference<>();
    AtomicReference<Boolean> assetPresent = new AtomicReference<>(false);
    when(assets.find(hosted, path)).thenAnswer(
        ignored -> assetPresent.get() ? Optional.of(asset) : Optional.empty());
    when(assets.stage(
        eq(hosted), eq(path), eq(inspected.file()), anyString(), any(), any(), any(),
        eq(inspected.sha256()), eq(inspected.size())))
        .thenReturn(staged);
    when(assets.promote(
        eq(hosted), eq(path), anyString(), eq(staged), anyString(), any(), any(), any()))
        .thenAnswer(ignored -> {
          assetPresent.set(true);
          return asset;
        });
    when(registry.findPackage(hosted.id(), "main", "linux-64", "demo-1.0-0.conda"))
        .thenAnswer(ignored -> Optional.ofNullable(stored.get()));
    when(registry.saveHostedPackage(any())).thenAnswer(invocation -> {
      CondaRegistryDao.PackageRecord value = invocation.getArgument(0);
      CondaRegistryDao.PackageRecord persisted = value.withRevision(3, Instant.EPOCH);
      stored.set(persisted);
      return persisted;
    });
    when(assets.blob(hosted, path)).thenReturn(
        blob(archive, inspected.sha256()), blob(archive, SHA_B));

    try {
      CondaRegistryDao.PackageRecord first = service.restoreHostedPackageForMigration(
          hosted, new CondaPathParser().parse(path), inspected,
          "application/octet-stream", "migration", "127.0.0.1", null);
      CondaRegistryDao.PackageRecord second = service.restoreHostedPackageForMigration(
          hosted, new CondaPathParser().parse(path), inspected,
          "application/octet-stream", "migration", "127.0.0.1", Instant.EPOCH);
      assertEquals(first, second);
      assertThrows(IllegalStateException.class, () -> service.restoreHostedPackageForMigration(
          hosted, new CondaPathParser().parse(path), inspected,
          "application/octet-stream", "migration", "127.0.0.1", Instant.EPOCH));
      assertThrows(IllegalArgumentException.class, () -> service.restoreHostedPackageForMigration(
          hosted, null, inspected, null, null, null, null));
      assertThrows(IllegalArgumentException.class, () -> service.restoreHostedPackageForMigration(
          hosted, new CondaPathParser().parse("main/noarch/other-1.0-0.conda"),
          inspected, null, null, null, null));
      verify(assets, times(3)).discard(hosted, staged);
      verify(assets, times(1)).promote(
          eq(hosted), eq(path), anyString(), eq(staged), anyString(), any(), any(), any());
    } finally {
      CondaArchiveInspector.delete(inspected.file());
    }
  }

  @Test
  void enforcesWriteDeletePoliciesAndRollsBackUntransactionalPublishFailure() throws Exception {
    byte[] archive = CondaTestArchive.legacy("demo", "1.0", "0", 0, "linux-64");
    String path = "main/linux-64/demo-1.0-0.tar.bz2";
    RepositoryRuntime denied = runtimeWith(
        46, "denied", RepositoryFormat.CONDA, RepositoryType.HOSTED, true, "DENY");
    when(assets.find(denied, path)).thenReturn(Optional.empty());
    assertThrows(MavenExceptions.WritePolicyDenied.class, () -> service.put(
        denied, path, new ByteArrayInputStream(archive), null, "alice", "127.0.0.1"));
    assertThrows(MavenExceptions.WritePolicyDenied.class, () -> service.delete(denied, path));

    RepositoryRuntime once = runtimeWith(
        47, "once", RepositoryFormat.CONDA, RepositoryType.HOSTED, true, "ALLOW_ONCE");
    when(assets.find(once, path)).thenReturn(Optional.of(asset(once, path, 1, 1, 2, 3)));
    assertThrows(MavenExceptions.WritePolicyDenied.class, () -> service.put(
        once, path, new ByteArrayInputStream(archive), null, "alice", "127.0.0.1"));
    assertThrows(MavenExceptions.MethodNotAllowed.class,
        () -> service.delete(once, "main/linux-64/repodata.json"));

    RepositoryRuntime administrative = runtime(
        48, "admin", RepositoryType.HOSTED, null, List.of());
    when(registry.tombstoneAndDeletePackage(
        eq(administrative.id()), eq("main"), eq("linux-64"),
        eq("demo-1.0-0.tar.bz2"), eq("administrative-delete"), eq(0L), any()))
        .thenReturn(Optional.empty());
    assertEquals(404, service.deleteAdministrative(administrative, path, " ").status());

    RepositoryRuntime failing = runtimeWith(
        49, "failing", RepositoryFormat.CONDA, RepositoryType.HOSTED, true, "ALLOW");
    AssetRecord promoted = asset(failing, path, archive.length, 11, 12, 13);
    CondaAssetSupport.StagedAsset staged = new CondaAssetSupport.StagedAsset(
        ".conda/staging/failing/package", blob(archive, sha256(archive)));
    AtomicReference<Boolean> promotedState = new AtomicReference<>(false);
    when(assets.find(failing, path)).thenAnswer(
        ignored -> promotedState.get() ? Optional.of(promoted) : Optional.empty());
    when(assets.stage(
        eq(failing), eq(path), any(Path.class), eq("application/x-conda-test"), any(),
        eq("alice"), eq("127.0.0.1"), anyString(), eq((long) archive.length)))
        .thenReturn(staged);
    when(assets.promote(
        eq(failing), eq(path), anyString(), eq(staged), eq("application/x-conda-test"),
        eq("alice"), eq("127.0.0.1"), any()))
        .thenAnswer(ignored -> {
          promotedState.set(true);
          return promoted;
        });
    when(registry.saveHostedPackage(any())).thenThrow(new IllegalStateException("database failed"));
    assertThrows(IllegalStateException.class, () -> service.put(
        failing, path, new ByteArrayInputStream(archive), "application/x-conda-test",
        "alice", "127.0.0.1"));
    verify(assets).delete(failing, path);
    verify(assets).discard(failing, staged);
  }

  @Test
  void rejectsBrokenCompactMetadataAndInvalidProxyLocations() throws Exception {
    RepositoryRuntime proxyRuntime = runtime(
        50, "conda-proxy", RepositoryType.PROXY, "https://repo.example/channels/", List.of());
    when(proxy.getMetadataFromUrlUnindexed(
        eq(proxyRuntime), anyString(),
        eq("https://repo.example/channels/main/linux-64/repodata.json.zst"), eq(false)))
        .thenReturn(
            MavenResponse.noBody(200),
            MavenResponse.ok(new ByteArrayInputStream(new byte[] {1}),
                256L * 1024 * 1024 + 1, "application/zstd", null, Instant.EPOCH));
    assertThrows(MavenExceptions.BadUpstreamException.class,
        () -> service.get(proxyRuntime, "main/linux-64/repodata.json", false));
    assertThrows(MavenExceptions.BadUpstreamException.class,
        () -> service.get(proxyRuntime, "main/linux-64/repodata.json", false));

    RepositoryRuntime missingRemote = runtime(
        51, "missing-remote", RepositoryType.PROXY, null, List.of());
    service = serviceWithScheduler(mock(CondaProxyInventoryScheduler.class));
    assertThrows(MavenExceptions.BadUpstreamException.class,
        () -> service.get(missingRemote, "main/noarch/demo-1.0-0.conda", false));

    RepositoryRuntime invalidBase = runtime(
        52, "invalid-base", RepositoryType.PROXY, "https://repo.example/channels/", List.of());
    CondaRegistryDao.PackageRecord known = proxyRecord(
        invalidBase.id(), "demo-1.0-0.conda", SHA_A, 5, 1);
    byte[] cachedRepodata = "{}".getBytes(StandardCharsets.UTF_8);
    cacheInternal(
        invalidBase, upstreamRepodataPath("main", "linux-64"), cachedRepodata);
    when(registry.findPackage(invalidBase.id(), "main", "linux-64", known.filename()))
        .thenReturn(Optional.of(known));
    when(registry.findChannelState(invalidBase.id(), "main", "linux-64"))
        .thenReturn(Optional.of(new CondaRegistryDao.ChannelState(
            invalidBase.id(), "main", "linux-64", digest(cachedRepodata), "file:///tmp/", 1,
            Instant.EPOCH, Instant.EPOCH)));
    assertThrows(MavenExceptions.BadUpstreamException.class, () -> service.get(
        invalidBase, "main/linux-64/" + known.filename(), false));
  }

  @Test
  void autowiredConstructorAndCompactDecodeFailuresUseProtocolResponses() throws Exception {
    CondaService transactional = new CondaService(
        registry,
        inspector,
        new CondaMetadataCodec(mapper),
        new CondaComponentFactory(),
        assets,
        new CondaLeaseManager(registry),
        proxy,
        mock(CondaProxyInventoryScheduler.class),
        new CondaMetadataBuildLimiter(),
        new CondaPublishLimiter(),
        mock(PlatformTransactionManager.class));
    RepositoryRuntime hosted = runtime(
        53, "<conda&hosted>", RepositoryType.HOSTED, null, List.of());
    assertFalse(transactional.get(hosted, "", true).hasBody());

    RepositoryRuntime empty = runtime(
        54, "empty", RepositoryType.PROXY, "https://repo.example/channels/", List.of());
    byte[] emptyFrame = Zstd.compress(new byte[0]);
    when(proxy.getMetadataFromUrlUnindexed(
        eq(empty), anyString(),
        eq("https://repo.example/channels/main/noarch/repodata.json.zst"), eq(false)))
        .thenReturn(MavenResponse.ok(
            new ByteArrayInputStream(emptyFrame), emptyFrame.length,
            "application/zstd", null, Instant.EPOCH));
    assertThrows(MavenExceptions.BadUpstreamException.class,
        () -> service.get(empty, "main/noarch/repodata.json", false));

    RepositoryRuntime noEtag = runtime(
        55, "no-etag", RepositoryType.PROXY, "https://repo.example/channels/", List.of());
    byte[] json = "{\"packages\":{}}".getBytes(StandardCharsets.UTF_8);
    byte[] compressed = Zstd.compress(json);
    when(proxy.getMetadataFromUrlUnindexed(
        eq(noEtag), anyString(),
        eq("https://repo.example/channels/main/noarch/repodata.json.zst"), eq(false)))
        .thenReturn(MavenResponse.ok(
            new ByteArrayInputStream(compressed), compressed.length,
            "application/zstd", null, Instant.EPOCH));
    MavenResponse decoded = service.get(noEtag, "main/noarch/repodata.json", false);
    assertEquals(json.length, decoded.contentLength());
    assertEquals(new String(json, StandardCharsets.UTF_8),
        new String(decoded.body().readAllBytes(), StandardCharsets.UTF_8));
  }

  @Test
  void proxyRawMetadataReusesWinnerAndFreshLocalSnapshots() throws Exception {
    RepositoryRuntime fresh = runtime(
        56, "fresh", RepositoryType.PROXY, "https://repo.example/channels/", List.of());
    String localPath = upstreamRepodataPath("main", "noarch", "repodata.json.bz2");
    cacheInternal(fresh, localPath, "fresh".getBytes(StandardCharsets.UTF_8));
    assertEquals("fresh", new String(service.get(
        fresh, "main/noarch/repodata.json.bz2", false).body().readAllBytes(),
        StandardCharsets.UTF_8));
    verify(proxy, never()).getMetadataFromUrlUnindexed(
        eq(fresh), anyString(), anyString(), anyBoolean());

    RepositoryRuntime waiter = runtime(
        57, "waiter", RepositoryType.PROXY, "https://repo.example/channels/", List.of());
    when(registry.tryAcquireLease(anyString(), anyString(), any())).thenAnswer(invocation -> {
      cacheInternal(waiter, localPath, "winner".getBytes(StandardCharsets.UTF_8));
      return Optional.empty();
    });
    assertEquals("winner", new String(service.get(
        waiter, "main/noarch/repodata.json.bz2", false).body().readAllBytes(),
        StandardCharsets.UTF_8));

    RepositoryRuntime recheck = runtime(
        58, "recheck", RepositoryType.PROXY, "https://repo.example/channels/", List.of());
    when(registry.tryAcquireLease(anyString(), anyString(), any())).thenAnswer(invocation -> {
      cacheInternal(recheck, localPath, "rechecked".getBytes(StandardCharsets.UTF_8));
      return Optional.of(new CondaRegistryDao.Lease(
          invocation.getArgument(0), invocation.getArgument(1), 2,
          invocation.getArgument(2), Instant.now()));
    });
    assertEquals("rechecked", new String(service.get(
        recheck, "main/noarch/repodata.json.bz2", false).body().readAllBytes(),
        StandardCharsets.UTF_8));
  }

  @Test
  void staleRevisionsAbortMetadataBuildsAfterBoundedRetries() {
    RepositoryRuntime hosted = runtime(
        59, "unstable", RepositoryType.HOSTED, null, List.of());
    AtomicLong stateRevision = new AtomicLong();
    when(registry.findChannelState(hosted.id(), "main", "linux-64"))
        .thenAnswer(ignored -> Optional.of(new CondaRegistryDao.ChannelState(
            hosted.id(), "main", "linux-64", null, null,
            stateRevision.incrementAndGet(), Instant.EPOCH, Instant.EPOCH)));
    assertThrows(MavenExceptions.BadUpstreamException.class,
        () -> service.get(hosted, "main/linux-64/repodata.json", false));

    AtomicLong repositoryRevision = new AtomicLong();
    when(registry.currentRepositoryRevision(hosted.id()))
        .thenAnswer(ignored -> repositoryRevision.incrementAndGet());
    assertThrows(MavenExceptions.BadUpstreamException.class,
        () -> service.get(hosted, "main/channeldata.json", false));

    RepositoryRuntime empty = runtime(
        60, "empty-hosted", RepositoryType.HOSTED, null, List.of());
    assertThrows(MavenExceptions.MavenNotFoundException.class,
        () -> service.get(empty, "main/linux-64/repodata.json", false));
    assertEquals(200, service.get(empty, "main/noarch/repodata.json", false).status());
  }

  @Test
  void groupKnownProxyPackageBindsCachedMd5AssetAndPinsMember() throws Exception {
    RepositoryRuntime hosted = runtime(
        61, "hosted", RepositoryType.HOSTED, null, List.of());
    RepositoryRuntime member = runtime(
        62, "proxy", RepositoryType.PROXY, "https://repo.example/channels/", List.of());
    RepositoryRuntime group = runtime(
        63, "group", RepositoryType.GROUP, null, List.of(hosted, member));
    String filename = "demo-1.0-0.tar.bz2";
    String path = "main/linux-64/" + filename;
    byte[] bytes = "md5-package".getBytes(StandardCharsets.UTF_8);
    CondaRegistryDao.PackageRecord known = new CondaRegistryDao.PackageRecord(
        1L, member.id(), "main", "linux-64", filename, "demo", "1.0", "0", 0,
        "tar.bz2", Map.of(), SHA_A, MD5, null, bytes.length, null, null,
        CondaRegistryDao.SOURCE_PROXY, 4, Instant.EPOCH, Instant.EPOCH);
    byte[] repodata = "{}".getBytes(StandardCharsets.UTF_8);
    cacheInternal(member, upstreamRepodataPath("main", "linux-64"), repodata);
    when(registry.findChannelState(member.id(), "main", "linux-64"))
        .thenReturn(Optional.of(new CondaRegistryDao.ChannelState(
            member.id(), "main", "linux-64", digest(repodata), null, 4,
            Instant.EPOCH, Instant.EPOCH)));
    when(registry.findPackage(member.id(), "main", "linux-64", filename))
        .thenReturn(Optional.of(known));
    AssetRecord cached = new AssetRecord(
        71L, member.id(), null, 72L, RepositoryFormat.CONDA, path, new byte[32], filename,
        "package", "application/x-tar", (long) bytes.length, null, Instant.EPOCH, Map.of());
    when(assets.find(member, path)).thenReturn(Optional.of(cached));
    when(assets.blob(member, path)).thenReturn(blob(bytes, null));
    when(proxy.getPinnedAssetFromUrlWithComponentAtBrowsePath(
        eq(member), eq(path), eq("https://repo.example/channels/" + path), any(),
        eq("main/linux-64/demo/1.0/" + filename), eq(false)))
        .thenReturn(MavenResponse.ok(
            new ByteArrayInputStream(bytes), bytes.length, "application/x-tar", MD5,
            Instant.EPOCH));

    MavenResponse response = service.get(group, path, false);

    assertEquals("md5-package",
        new String(response.body().readAllBytes(), StandardCharsets.UTF_8));
    verify(assets).bindCachedPackage(eq(member), eq(cached), any(), any(),
        eq("main/linux-64/demo/1.0/" + filename));
    verify(registry).upsertGroupSourceBinding(any());
  }

  @Test
  void groupResolutionStopsAfterThreeStaleUnknownBindings() {
    RepositoryRuntime group = runtime(
        64, "stale-group", RepositoryType.GROUP, null, List.of());
    CondaRegistryDao.PackageRecord unknown = record(
        999, "demo-1.0-0.tar.bz2", SHA_A, "unknown member");
    when(registry.findPreferredPackage(
        any(), eq("main"), eq("linux-64"), eq(unknown.filename())))
        .thenReturn(Optional.of(unknown));

    assertThrows(MavenExceptions.BadUpstreamException.class, () -> service.get(
        group, "main/linux-64/" + unknown.filename(), false));
    verify(registry, times(3)).upsertGroupSourceBinding(any());
  }

  @Test
  void groupChanneldataStreamsPreferredRecords() throws Exception {
    RepositoryRuntime first = runtime(
        65, "first", RepositoryType.HOSTED, null, List.of());
    RepositoryRuntime second = runtime(
        66, "second", RepositoryType.HOSTED, null, List.of());
    RepositoryRuntime group = runtime(
        67, "channel-group", RepositoryType.GROUP, null, List.of(first, second));
    when(registry.currentRepositoryRevision(group.id())).thenReturn(3L);
    doAnswer(invocation -> {
      @SuppressWarnings("unchecked")
      java.util.function.Consumer<CondaRegistryDao.PackageRecord> consumer =
          invocation.getArgument(2);
      consumer.accept(new CondaRegistryDao.PackageRecord(
          null, first.id(), "main", "linux-64", "alpha-1.0-0.tar.bz2", "alpha",
          "1.0", "0", 0, "tar.bz2", Map.of("summary", "alpha"), SHA_A, MD5, SHA_A,
          5, null, null, CondaRegistryDao.SOURCE_HOSTED, 1, Instant.EPOCH, Instant.EPOCH));
      consumer.accept(new CondaRegistryDao.PackageRecord(
          null, second.id(), "main", "linux-64", "zeta-1.0-0.tar.bz2", "zeta",
          "1.0", "0", 0, "tar.bz2", Map.of("summary", "zeta"), SHA_B, MD5, SHA_B,
          5, null, null, CondaRegistryDao.SOURCE_HOSTED, 1, Instant.EPOCH, Instant.EPOCH));
      return null;
    }).when(registry).visitPreferredPackagesByChannel(any(), eq("main"), any());

    JsonNode channeldata = json(service.get(group, "main/channeldata.json", false));

    assertTrue(channeldata.path("packages").has("alpha"));
    assertTrue(channeldata.path("packages").has("zeta"));
  }

  @Test
  void servesHostedPackagesAndCompressedIndexesAndRejectsMetadataUploads() throws Exception {
    RepositoryRuntime hosted = runtime(
        68, "compressed-hosted", RepositoryType.HOSTED, null, List.of());
    assertThrows(MavenExceptions.MethodNotAllowed.class, () -> service.put(
        hosted, "main/noarch/repodata.json", new ByteArrayInputStream(new byte[0]),
        "application/json", "alice", "127.0.0.1"));

    when(registry.findChannelState(hosted.id(), "main", "noarch"))
        .thenReturn(Optional.of(new CondaRegistryDao.ChannelState(
            hosted.id(), "main", "noarch", null, null, 1, Instant.EPOCH, Instant.EPOCH)));
    when(registry.listPackages(hosted.id(), "main", "noarch")).thenReturn(List.of());
    when(registry.listTombstones(hosted.id(), "main", "noarch")).thenReturn(List.of());
    assertEquals("BZh", new String(service.get(
        hosted, "main/noarch/repodata.json.bz2", false).body().readNBytes(3),
        StandardCharsets.US_ASCII));
    assertTrue(service.get(
        hosted, "main/noarch/repodata.json.zst", false).contentLength() > 0);

    String filename = "demo-1.0-0.conda";
    String path = "main/noarch/" + filename;
    when(registry.findPackage(hosted.id(), "main", "noarch", filename))
        .thenReturn(Optional.of(proxyRecord(hosted.id(), filename, SHA_A, 5, 1)));
    when(assets.serve(hosted, path, true)).thenReturn(MavenResponse.noBody(200));
    assertEquals(200, service.get(hosted, path, true).status());

    RepositoryRuntime currentProxy = runtime(
        69, "current-proxy", RepositoryType.PROXY,
        "https://repo.example/channels/", List.of());
    byte[] json = "{\"packages\":{}}".getBytes(StandardCharsets.UTF_8);
    byte[] zstd = Zstd.compress(json);
    when(proxy.getMetadataFromUrlUnindexed(
        eq(currentProxy), anyString(),
        eq("https://repo.example/channels/main/noarch/current_repodata.json.zst"),
        eq(false))).thenReturn(MavenResponse.ok(
            new ByteArrayInputStream(zstd), zstd.length, "application/zstd", SHA_A,
            Instant.EPOCH));
    assertEquals(json.length, service.get(
        currentProxy, "main/noarch/current_repodata.json", true).contentLength());
  }

  @Test
  void failsClosedForUnavailableOrIdentitylessGroupProxyMetadata() {
    RepositoryRuntime first = runtime(
        70, "first-broken-proxy", RepositoryType.PROXY,
        "https://repo.example/channels/", List.of());
    RepositoryRuntime second = runtime(
        71, "second-broken-proxy", RepositoryType.PROXY,
        "https://repo.example/channels/", List.of());
    RepositoryRuntime unavailable = runtime(
        72, "unavailable-group", RepositoryType.GROUP, null, List.of(first, second));
    when(proxy.getMetadataFromUrlUnindexed(
        any(RepositoryRuntime.class), anyString(), anyString(), anyBoolean()))
        .thenThrow(new MavenExceptions.BadUpstreamException("upstream failed"));
    assertThrows(MavenExceptions.BadUpstreamException.class, () -> service.get(
        unavailable, "main/linux-64/repodata.json", false));

    clearInvocations(proxy);
    RepositoryRuntime identitylessFirst = runtime(
        73, "identityless-first", RepositoryType.PROXY,
        "https://repo.example/channels/", List.of());
    RepositoryRuntime identitylessSecond = runtime(
        74, "identityless-second", RepositoryType.PROXY,
        "https://repo.example/channels/", List.of());
    RepositoryRuntime identitylessGroup = runtime(
        75, "identityless-group", RepositoryType.GROUP, null,
        List.of(identitylessFirst, identitylessSecond));
    String localPath = upstreamRepodataPath("main", "linux-64", "repodata.json.zst");
    for (RepositoryRuntime member : List.of(identitylessFirst, identitylessSecond)) {
      CachedAssetMetadata invalid = new CachedAssetMetadata(
          1, member.id(), null, 1L, RepositoryFormat.CONDA, localPath,
          "repodata.json.zst", "conda-internal", "application/zstd", 1L,
          Instant.now(), Map.of(),
          new CachedAssetMetadata.CachedBlob(
              1, 1, "blob", "object", "", "", MD5, 1,
              "application/zstd", "proxy", null, Instant.now(), Instant.now(), Map.of()));
      when(assets.findInternal(member, localPath)).thenReturn(Optional.of(invalid));
    }
    assertThrows(MavenExceptions.BadUpstreamException.class, () -> service.get(
        identitylessGroup, "main/linux-64/repodata.json", false));
  }

  @Test
  void resolvesHostedGroupPackagesAndDeletesInvalidCachedProxyBindings() throws Exception {
    RepositoryRuntime hosted = runtime(
        76, "direct-hosted", RepositoryType.HOSTED, null, List.of());
    RepositoryRuntime group = runtime(
        77, "direct-group", RepositoryType.GROUP, null, List.of(hosted));
    String filename = "demo-1.0-0.tar.bz2";
    String path = "main/linux-64/" + filename;
    CondaRegistryDao.PackageRecord hostedRecord = record(
        hosted.id(), filename, SHA_A, "direct");
    when(registry.findPackage(hosted.id(), "main", "linux-64", filename))
        .thenReturn(Optional.of(hostedRecord));
    when(assets.serve(hosted, path, false)).thenReturn(MavenResponse.ok(
        new ByteArrayInputStream(new byte[] {1}), 1, "application/x-tar", SHA_A,
        Instant.EPOCH));
    assertEquals(200, service.get(group, path, false).status());
    verify(registry).upsertGroupSourceBinding(any());

    RepositoryRuntime proxyRuntime = runtime(
        78, "invalid-cache", RepositoryType.PROXY,
        "https://repo.example/channels/", List.of());
    CondaRegistryDao.PackageRecord known = proxyRecord(
        proxyRuntime.id(), "demo-1.0-0.conda", SHA_A, 5, 2);
    String proxyPath = "main/linux-64/" + known.filename();
    byte[] repodata = "{}".getBytes(StandardCharsets.UTF_8);
    cacheInternal(proxyRuntime, upstreamRepodataPath("main", "linux-64"), repodata);
    when(registry.findChannelState(proxyRuntime.id(), "main", "linux-64"))
        .thenReturn(Optional.of(new CondaRegistryDao.ChannelState(
            proxyRuntime.id(), "main", "linux-64", digest(repodata), null, 2,
            Instant.EPOCH, Instant.EPOCH)));
    when(registry.findPackage(proxyRuntime.id(), "main", "linux-64", known.filename()))
        .thenReturn(Optional.of(known));
    AssetRecord cached = new AssetRecord(
        80L, proxyRuntime.id(), null, 81L, RepositoryFormat.CONDA, proxyPath,
        new byte[32], known.filename(), "package", "application/x-conda", 1L,
        null, Instant.EPOCH, Map.of());
    when(assets.find(proxyRuntime, proxyPath)).thenReturn(Optional.of(cached));
    when(assets.blob(proxyRuntime, proxyPath)).thenReturn(blob(new byte[] {1}, SHA_B));
    when(proxy.getPinnedAssetFromUrlWithComponentAtBrowsePath(
        eq(proxyRuntime), eq(proxyPath), anyString(), any(), anyString(), eq(false)))
        .thenReturn(MavenResponse.ok(
            new ByteArrayInputStream(new byte[] {1}), 1, "application/x-conda", SHA_B,
            Instant.EPOCH));
    assertThrows(MavenExceptions.BadUpstreamException.class,
        () -> service.get(proxyRuntime, proxyPath, false));
    verify(assets, times(2)).delete(proxyRuntime, proxyPath);
  }

  @Test
  void recordsMissingNoarchProxyInventoryAsAnEmptyProjection() {
    RepositoryRuntime missing = runtime(
        79, "missing-noarch", RepositoryType.PROXY,
        "https://repo.example/channels/", List.of());
    when(proxy.getMetadataFromUrlUnindexed(
        eq(missing), anyString(), anyString(), anyBoolean()))
        .thenThrow(new MavenExceptions.MavenNotFoundException("metadata missing"));

    assertThrows(MavenExceptions.MavenNotFoundException.class, () -> service.get(
        missing, "main/noarch/demo-1.0-0.conda", false));

    verify(registry).replaceProxyPackages(
        eq(missing.id()), eq("main"), eq("noarch"), anyString(), eq(null), anyList(), any());
  }

  @Test
  void bindsCanonicalProxyCacheAndRetriesRelocatedPackagesAfterInventoryRefresh()
      throws Exception {
    service = serviceWithScheduler(mock(CondaProxyInventoryScheduler.class));
    RepositoryRuntime canonical = runtime(
        80, "canonical-cache", RepositoryType.PROXY,
        "https://repo.example/channels/", List.of());
    String filename = "demo-1.0-0.conda";
    String path = "main/linux-64/" + filename;
    AssetRecord cached = new AssetRecord(
        90L, canonical.id(), null, 91L, RepositoryFormat.CONDA, path, new byte[32],
        filename, "package", "application/x-conda", 5L, null, Instant.EPOCH, Map.of());
    when(assets.find(canonical, path)).thenReturn(Optional.of(cached));
    when(assets.blob(canonical, path)).thenReturn(blob(new byte[5], SHA_A));
    when(proxy.getPinnedAssetFromUrlWithComponentAtBrowsePath(
        eq(canonical), eq(path), anyString(), any(),
        eq("main/linux-64/demo/1.0/" + filename), eq(true)))
        .thenReturn(MavenResponse.noBody(200));

    assertEquals(200, service.get(canonical, path, true).status());
    verify(assets).bindCachedPackage(
        eq(canonical), eq(cached), any(), any(),
        eq("main/linux-64/demo/1.0/" + filename));

    RepositoryRuntime relocated = runtime(
        81, "relocated", RepositoryType.PROXY,
        "https://repo.example/channels/", List.of());
    CondaRegistryDao.PackageRecord refreshed = proxyRecord(
        relocated.id(), filename, SHA_A, 5, 3);
    byte[] repodata = ("""
        {"packages.conda":{"%s":{
          "name":"demo","version":"1.0","build":"0","build_number":0,
          "size":5,"subdir":"linux-64","sha256":"%s"}}}
        """).formatted(filename, SHA_A).getBytes(StandardCharsets.UTF_8);
    cacheInternal(relocated, upstreamRepodataPath("main", "linux-64"), repodata);
    when(registry.findPackage(relocated.id(), "main", "linux-64", filename))
        .thenReturn(Optional.empty(), Optional.of(refreshed));
    when(proxy.getPinnedAssetFromUrlWithComponentAtBrowsePath(
        eq(relocated), eq(path), anyString(), any(),
        eq("main/linux-64/demo/1.0/" + filename), eq(false)))
        .thenThrow(new MavenExceptions.MavenNotFoundException("canonical package missing"))
        .thenReturn(MavenResponse.ok(
            new ByteArrayInputStream(new byte[5]), 5, "application/x-conda", SHA_A,
            Instant.EPOCH));
    when(assets.blob(relocated, path)).thenReturn(blob(new byte[5], SHA_A));

    assertEquals(200, service.get(relocated, path, false).status());
    verify(registry).replaceProxyPackages(
        eq(relocated.id()), eq("main"), eq("linux-64"), anyString(), eq(null),
        any(CondaRegistryDao.PackageRecordSource.class), any());
  }

  @Test
  void rejectsGroupRecordsWithoutAContentIdentity() {
    RepositoryRuntime hosted = runtime(
        82, "checksumless-hosted", RepositoryType.HOSTED, null, List.of());
    RepositoryRuntime group = runtime(
        83, "checksumless-group", RepositoryType.GROUP, null, List.of(hosted));
    String filename = "demo-1.0-0.tar.bz2";
    CondaRegistryDao.PackageRecord checksumless = new CondaRegistryDao.PackageRecord(
        1L, hosted.id(), "main", "linux-64", filename, "demo", "1.0", "0", 0,
        "tar.bz2", Map.of(), SHA_A, null, null, 5, null, null,
        CondaRegistryDao.SOURCE_HOSTED, 1, Instant.EPOCH, Instant.EPOCH);
    when(registry.findPackage(hosted.id(), "main", "linux-64", filename))
        .thenReturn(Optional.of(checksumless));

    assertThrows(MavenExceptions.BadUpstreamException.class, () -> service.get(
        group, "main/linux-64/" + filename, false));
  }

  @Test
  void skipsInvalidGroupMembersAndPropagatesTheLastProxyFailure() {
    RepositoryRuntime offline = runtimeWith(
        84, "offline", RepositoryFormat.CONDA, RepositoryType.PROXY, false, "ALLOW_ONCE");
    RepositoryRuntime wrongFormat = runtimeWith(
        85, "raw", RepositoryFormat.RAW, RepositoryType.HOSTED, true, "ALLOW_ONCE");
    RepositoryRuntime first = runtime(
        86, "first-failing", RepositoryType.PROXY,
        "https://first.example/channels/", List.of());
    RepositoryRuntime second = runtime(
        87, "second-failing", RepositoryType.PROXY,
        "https://second.example/channels/", List.of());
    RepositoryRuntime group = runtime(
        88, "failing-group", RepositoryType.GROUP, null,
        List.of(offline, wrongFormat, first, second));
    when(proxy.getMetadataFromUrlUnindexed(
        any(RepositoryRuntime.class), anyString(), anyString(), anyBoolean()))
        .thenThrow(new MavenExceptions.BadUpstreamException("proxy metadata failed"));

    assertThrows(MavenExceptions.BadUpstreamException.class, () -> service.get(
        group, "main/linux-64/demo-1.0-0.conda", false));
  }

  private CondaService serviceWithScheduler(CondaProxyInventoryScheduler scheduler) {
    return new CondaService(
        registry,
        inspector,
        new CondaMetadataCodec(mapper),
        new CondaComponentFactory(),
        assets,
        new CondaLeaseManager(registry),
        proxy,
        scheduler,
        new CondaMetadataBuildLimiter(),
        new CondaPublishLimiter(),
        (org.springframework.transaction.support.TransactionTemplate) null);
  }

  private JsonNode json(MavenResponse response) throws Exception {
    assertEquals(200, response.status());
    return mapper.readTree(response.body());
  }

  private static RepositoryRuntime runtime(
      long id,
      String name,
      RepositoryType type,
      String remote,
      List<RepositoryRuntime> members) {
    return new RepositoryRuntime(
        id, name, RepositoryFormat.CONDA, type, "conda-" + type.name().toLowerCase(),
        true, 1L, "ALLOW_ONCE", null, null, true, remote, 60, 60, true, null, members);
  }

  private static RepositoryRuntime runtimeWith(
      long id,
      String name,
      RepositoryFormat format,
      RepositoryType type,
      boolean online,
      String writePolicy) {
    return new RepositoryRuntime(
        id, name, format, type, format.id() + "-" + type.name().toLowerCase(),
        online, 1L, writePolicy, null, null, true, null, 60, 60, true, null, List.of());
  }

  private static AssetRecord asset(
      RepositoryRuntime runtime,
      String path,
      long size,
      long id,
      long blobId,
      long componentId) {
    return new AssetRecord(
        id, runtime.id(), componentId, blobId, RepositoryFormat.CONDA, path, new byte[32],
        path.substring(path.lastIndexOf('/') + 1), "package", "application/octet-stream", size,
        null, Instant.EPOCH, Map.of());
  }

  private static AssetBlobRecord blob(byte[] bytes, String sha256) {
    return new AssetBlobRecord(
        1L, 1L, "blob", new byte[32], "object", new byte[32], "sha1", sha256, MD5,
        bytes.length, "application/octet-stream", "proxy", null,
        Instant.EPOCH, Instant.EPOCH, Map.of());
  }

  private static CondaRegistryDao.PackageRecord record(
      long repositoryId, String filename, String sha256, String summary) {
    return record(repositoryId, "main", filename, sha256, summary);
  }

  private static CondaRegistryDao.PackageRecord record(
      long repositoryId, String channel, String filename, String sha256, String summary) {
    LinkedHashMap<String, Object> metadata = new LinkedHashMap<>();
    metadata.put("summary", summary);
    metadata.put("depends", List.of());
    return new CondaRegistryDao.PackageRecord(
        1L, repositoryId, channel, "linux-64", filename, "demo", "1.0", "0", 0,
        "tar.bz2", metadata, SHA_A, MD5, sha256, 5, 10L, 20L,
        CondaRegistryDao.SOURCE_HOSTED, 1, Instant.EPOCH, Instant.EPOCH);
  }

  private static CondaRegistryDao.PackageRecord proxyRecord(
      long repositoryId, String filename, String sha256, long size, long revision) {
    return new CondaRegistryDao.PackageRecord(
        1L, repositoryId, "main", "linux-64", filename, "demo", "1.0", "0", 0,
        "conda", Map.of("depends", List.of()), SHA_A, MD5, sha256, size, null, null,
        CondaRegistryDao.SOURCE_PROXY, revision, Instant.EPOCH, Instant.EPOCH);
  }

  private static String sha256(byte[] bytes) throws Exception {
    return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
  }

  private static byte[] bzip2(byte[] bytes) throws Exception {
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    try (BZip2CompressorOutputStream compressed = new BZip2CompressorOutputStream(output)) {
      compressed.write(bytes);
    }
    return output.toByteArray();
  }

  private void cacheInternal(RepositoryRuntime runtime, String path, byte[] body) {
    internalAssets.put(
        internalKey(runtime, path), new InternalAsset(body, "application/json", Instant.now()));
  }

  private static String upstreamRepodataPath(String channel, String subdir) {
    return upstreamRepodataPath(channel, subdir, "repodata.json");
  }

  private static String upstreamRepodataPath(
      String channel, String subdir, String filename) {
    return ".conda/upstream/" + HexFormat.of().formatHex(PersistenceHashes.sha256(channel)) + "/"
        + subdir + "/" + filename;
  }

  private static String internalKey(RepositoryRuntime runtime, String path) {
    return runtime.id() + ":" + path;
  }

  private static CachedAssetMetadata cached(
      RepositoryRuntime runtime, String path, InternalAsset asset) {
    String sha256 = digest(asset.body());
    return new CachedAssetMetadata(
        1, runtime.id(), null, 1L, RepositoryFormat.CONDA, path,
        path.substring(path.lastIndexOf('/') + 1), "conda-internal", asset.contentType(),
        (long) asset.body().length, asset.updatedAt(), Map.of(),
        new CachedAssetMetadata.CachedBlob(
            1, 1, "blob", "object", "sha1", sha256, MD5, asset.body().length,
            asset.contentType(), "test", "127.0.0.1", asset.updatedAt(), asset.updatedAt(),
            Map.of()));
  }

  private static String digest(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private record InternalAsset(byte[] body, String contentType, Instant updatedAt) { }
}
