package com.github.klboke.kkrepo.server.cargo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.klboke.kkrepo.core.BlobStorage;
import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.core.RepositoryType;
import com.github.klboke.kkrepo.persistence.jdbc.api.AssetDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.ComponentDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.ProxyStateDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetBlobRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetRecord;
import com.github.klboke.kkrepo.protocol.cargo.CargoPath;
import com.github.klboke.kkrepo.server.cache.AssetMetadataCache;
import com.github.klboke.kkrepo.server.cache.CachedAssetMetadata;
import com.github.klboke.kkrepo.server.maven.BlobStorageRegistry;
import com.github.klboke.kkrepo.server.maven.HttpRemoteFetcher;
import com.github.klboke.kkrepo.server.maven.MavenResponse;
import com.github.klboke.kkrepo.server.maven.ProxyNegativeCache;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntime;
import com.github.klboke.kkrepo.server.support.InMemorySharedCache;
import java.io.IOException;
import java.io.InputStream;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import org.junit.jupiter.api.Test;

class CargoProxyServiceTest {
  private static final String REMOTE = "https://index.crates.io/";
  private static final TypeReference<Map<String, Object>> JSON_MAP = new TypeReference<>() {
  };

  @Test
  void sparseUnavailableStatusIsStoredInNegativeCache() {
    AssetDao assetDao = mock(AssetDao.class);
    when(assetDao.findAssetByPath(anyLong(), anyString())).thenReturn(Optional.empty());
    ProxyStateDao proxyState = mock(ProxyStateDao.class);
    when(proxyState.isBlocked(anyLong(), org.mockito.ArgumentMatchers.any(Instant.class))).thenReturn(false);
    CountingFetcher fetcher = new CountingFetcher(new HttpRemoteFetcher.Result(
        451, Map.of(), InputStream.nullInputStream()));
    CargoProxyService service = new CargoProxyService(
        assetDao,
        mock(ComponentDao.class),
        mock(BlobStorageRegistry.class),
        mock(CargoAssetWriter.class),
        mock(CargoAssetReader.class),
        proxyState,
        fetcher,
        new ProxyNegativeCache(new InMemorySharedCache(), true, 1440),
        new AssetMetadataCache(new InMemorySharedCache(), false, 0, 0),
        new ObjectMapper());

    assertThrows(CargoExceptions.CargoNotFoundException.class,
        () -> service.index(runtime(), "missing-crate", false));
    assertThrows(CargoExceptions.CargoNotFoundException.class,
        () -> service.index(runtime(), "missing-crate", false));

    assertEquals(1, fetcher.calls);
  }

  @Test
  void missingDownloadKeepsCargoApiNotFoundShapeWhenSparseIndexIsMissing() {
    AssetDao assetDao = mock(AssetDao.class);
    when(assetDao.findAssetByPath(anyLong(), anyString())).thenReturn(Optional.empty());
    ProxyStateDao proxyState = mock(ProxyStateDao.class);
    when(proxyState.isBlocked(anyLong(), org.mockito.ArgumentMatchers.any(Instant.class))).thenReturn(false);
    CountingFetcher fetcher = new CountingFetcher(new HttpRemoteFetcher.Result(
        451, Map.of(), InputStream.nullInputStream()));
    CargoProxyService service = new CargoProxyService(
        assetDao,
        mock(ComponentDao.class),
        mock(BlobStorageRegistry.class),
        mock(CargoAssetWriter.class),
        mock(CargoAssetReader.class),
        proxyState,
        fetcher,
        new ProxyNegativeCache(new InMemorySharedCache(), true, 1440),
        new AssetMetadataCache(new InMemorySharedCache(), false, 0, 0),
        new ObjectMapper());

    CargoExceptions.CargoNotFoundException error = assertThrows(CargoExceptions.CargoNotFoundException.class,
        () -> service.download(runtime(), "missing-crate", "1.0.0", false));

    assertFalse(error instanceof CargoExceptions.CargoIndexNotFoundException);
    assertEquals("missing-crate 1.0.0", error.getMessage());
  }

  @Test
  void proxyConfigAdvertisesNexusCompatibleDownloadBase() throws Exception {
    ObjectMapper mapper = new ObjectMapper();
    CargoProxyService service = new CargoProxyService(
        null, null, null, null, null, null, null, null, null, mapper);

    MavenResponse response = service.get(
        runtime(),
        new CargoPath(CargoPath.Kind.CONFIG, "config.json", null, null),
        "http://localhost/repository/cargo-proxy",
        new CargoSearchQuery("", 10, 1),
        false);

    Map<String, Object> config = mapper.readValue(
        new String(response.body().readAllBytes(), StandardCharsets.UTF_8),
        JSON_MAP);
    assertEquals("http://localhost/repository/cargo-proxy/crates", config.get("dl"));
    assertEquals("http://localhost/repository/cargo-proxy/", config.get("api"));
    assertFalse(config.containsKey("auth-required"));
  }

  @Test
  void proxyConfigHonorsAuthenticationRequirementHint() throws Exception {
    ObjectMapper mapper = new ObjectMapper();
    CargoProxyService service = new CargoProxyService(
        null, null, null, null, null, null, null, null, null, mapper);

    MavenResponse response = service.get(
        runtime(true),
        new CargoPath(CargoPath.Kind.CONFIG, "config.json", null, null),
        "http://localhost/repository/cargo-proxy",
        new CargoSearchQuery("", 10, 1),
        false);

    Map<String, Object> config = mapper.readValue(
        new String(response.body().readAllBytes(), StandardCharsets.UTF_8),
        JSON_MAP);
    assertEquals(true, config.get("auth-required"));
  }

  @Test
  @SuppressWarnings("unchecked")
  void proxySearchUsesRemoteApiInsteadOfOnlyCachedComponents() throws Exception {
    AssetDao assetDao = mock(AssetDao.class);
    when(assetDao.findAssetByPath(anyLong(), anyString())).thenReturn(Optional.empty());
    ProxyStateDao proxyState = mock(ProxyStateDao.class);
    when(proxyState.isBlocked(anyLong(), org.mockito.ArgumentMatchers.any(Instant.class))).thenReturn(false);
    CountingFetcher fetcher = new CountingFetcher(
        jsonResult("""
            {"dl":"https://static.crates.io/crates","api":"https://crates.io"}
            """),
        jsonResult("""
            {"crates":[{"name":"itoa","max_version":"1.0.15","description":"fast integers"}],"meta":{"total":1}}
            """));
    CargoProxyService service = new CargoProxyService(
        assetDao,
        mock(ComponentDao.class),
        mock(BlobStorageRegistry.class),
        mock(CargoAssetWriter.class),
        mock(CargoAssetReader.class),
        proxyState,
        fetcher,
        new ProxyNegativeCache(new InMemorySharedCache(), true, 1440),
        new AssetMetadataCache(new InMemorySharedCache(), false, 0, 0),
        new ObjectMapper());

    MavenResponse response = service.get(
        runtime(),
        new CargoPath(CargoPath.Kind.SEARCH, "api/v1/crates", null, null),
        "http://localhost/repository/cargo-proxy",
        new CargoSearchQuery("itoa", 10, 1),
        false);

    Map<String, Object> body = new ObjectMapper().readValue(
        new String(response.body().readAllBytes(), StandardCharsets.UTF_8),
        JSON_MAP);
    List<Map<String, Object>> crates = (List<Map<String, Object>>) body.get("crates");
    assertEquals("itoa", crates.getFirst().get("name"));
    assertEquals(2, fetcher.calls);
    assertTrue(fetcher.urls.get(1).contains("https://crates.io/api/v1/crates"));
    assertTrue(fetcher.urls.get(1).contains("q=itoa"));
    assertTrue(fetcher.urls.get(1).contains("per_page=10"));
  }

  @Test
  void downloadServesFreshCachedCrateBeforeRemoteIndexLookup() throws Exception {
    String path = CargoAssetWriter.cratePath("itoa", "1.0.15");
    AssetDao assetDao = mock(AssetDao.class);
    when(assetDao.findAssetByPath(50L, path)).thenReturn(Optional.of(asset(path, 11L)));
    when(assetDao.findBlobById(11L)).thenReturn(Optional.of(blob(11L)));
    ProxyStateDao proxyState = mock(ProxyStateDao.class);
    CountingFetcher fetcher = new CountingFetcher();
    CargoAssetReader reader = mock(CargoAssetReader.class);
    when(reader.exists(any(CachedAssetMetadata.class))).thenReturn(true);
    when(reader.serveSnapshot(any(CachedAssetMetadata.class), eq(false), eq(path)))
        .thenReturn(MavenResponse.ok(
            new ByteArrayInputStream("cached crate".getBytes(StandardCharsets.UTF_8)),
            12,
            "application/x-tar",
            "sha1",
            Instant.now()));
    CargoProxyService service = new CargoProxyService(
        assetDao,
        mock(ComponentDao.class),
        mock(BlobStorageRegistry.class),
        mock(CargoAssetWriter.class),
        reader,
        proxyState,
        fetcher,
        new ProxyNegativeCache(new InMemorySharedCache(), true, 1440),
        new AssetMetadataCache(new InMemorySharedCache(), false, 0, 0),
        new ObjectMapper());

    MavenResponse response = service.download(runtimeWithCredentials(), "itoa", "1.0.15", false);

    assertEquals("cached crate", new String(response.body().readAllBytes(), StandardCharsets.UTF_8));
    assertEquals(0, fetcher.calls);
  }

  @Test
  void downloadDoesNotAttachCrateCredentialsWhenUpstreamConfigDoesNotRequireAuth() throws Exception {
    String indexPath = "it/oa/itoa";
    String cratePath = CargoAssetWriter.cratePath("itoa", "1.0.15");
    AssetDao assetDao = mock(AssetDao.class);
    when(assetDao.findAssetByPath(50L, indexPath)).thenReturn(Optional.of(asset(indexPath, 21L)));
    when(assetDao.findAssetByPath(50L, "config.json")).thenReturn(Optional.empty());
    when(assetDao.findAssetByPath(50L, cratePath)).thenReturn(Optional.empty());
    when(assetDao.findBlobById(21L)).thenReturn(Optional.of(blob(21L)));
    ProxyStateDao proxyState = mock(ProxyStateDao.class);
    when(proxyState.isBlocked(anyLong(), any(Instant.class))).thenReturn(false);
    CargoAssetReader reader = mock(CargoAssetReader.class);
    when(reader.exists(any(CachedAssetMetadata.class))).thenReturn(true);
    when(reader.readText(any(CachedAssetMetadata.class), eq(indexPath))).thenReturn("""
        {"name":"itoa","vers":"1.0.15","cksum":"abc123","yanked":false}
        """);
    BlobStorageRegistry storageRegistry = mock(BlobStorageRegistry.class);
    when(storageRegistry.forBlobStoreId(1L)).thenReturn(mock(BlobStorage.class));
    CargoAssetWriter writer = mock(CargoAssetWriter.class);
    when(writer.writeProxyCrate(
        any(),
        any(),
        eq(1L),
        any(),
        any(),
        eq(cratePath),
        any(),
        anyString(),
        any(),
        eq("abc123")))
        .thenReturn(stored(cratePath, "crate body"));
    CountingFetcher fetcher = new CountingFetcher(
        jsonResult("""
            {"dl":"https://index.crates.io/crates","auth-required":false}
            """),
        new HttpRemoteFetcher.Result(
            200,
            Map.of("Content-Type", "application/x-tar"),
            new ByteArrayInputStream("crate body".getBytes(StandardCharsets.UTF_8))));
    CargoProxyService service = new CargoProxyService(
        assetDao,
        mock(ComponentDao.class),
        storageRegistry,
        writer,
        reader,
        proxyState,
        fetcher,
        new ProxyNegativeCache(new InMemorySharedCache(), true, 1440),
        new AssetMetadataCache(new InMemorySharedCache(), false, 0, 0),
        new ObjectMapper());

    service.download(runtimeWithCredentials(), "itoa", "1.0.15", false).body().close();

    assertEquals(2, fetcher.calls);
    assertNull(fetcher.requests.get(1).authorizationHeader());
  }

  @Test
  void invalidUpstreamIndexIsNotCached() {
    AssetDao assetDao = mock(AssetDao.class);
    when(assetDao.findAssetByPath(anyLong(), anyString())).thenReturn(Optional.empty());
    ProxyStateDao proxyState = mock(ProxyStateDao.class);
    when(proxyState.isBlocked(anyLong(), any(Instant.class))).thenReturn(false);
    when(proxyState.loadState(anyLong())).thenReturn(Optional.empty());
    CargoAssetWriter writer = mock(CargoAssetWriter.class);
    CountingFetcher fetcher = new CountingFetcher(new HttpRemoteFetcher.Result(
        200,
        Map.of("Content-Type", "text/html"),
        new ByteArrayInputStream("<html>login</html>".getBytes(StandardCharsets.UTF_8))));
    CargoProxyService service = new CargoProxyService(
        assetDao,
        mock(ComponentDao.class),
        mock(BlobStorageRegistry.class),
        writer,
        mock(CargoAssetReader.class),
        proxyState,
        fetcher,
        new ProxyNegativeCache(new InMemorySharedCache(), true, 1440),
        new AssetMetadataCache(new InMemorySharedCache(), false, 0, 0),
        new ObjectMapper());

    assertThrows(CargoExceptions.BadUpstreamException.class,
        () -> service.index(runtime(), "itoa", false));

    verify(writer, never()).writeMetadata(any(), any(), anyLong(), anyString(), any(), anyString(), anyString(), any(), any(), eq(true));
  }

  @Test
  void invalidUpstreamConfigIsNotCached() throws Exception {
    AssetDao assetDao = mock(AssetDao.class);
    when(assetDao.findAssetByPath(anyLong(), anyString())).thenReturn(Optional.empty());
    ProxyStateDao proxyState = mock(ProxyStateDao.class);
    when(proxyState.isBlocked(anyLong(), any(Instant.class))).thenReturn(false);
    when(proxyState.loadState(anyLong())).thenReturn(Optional.empty());
    CargoAssetWriter writer = mock(CargoAssetWriter.class);
    CountingFetcher fetcher = new CountingFetcher(new HttpRemoteFetcher.Result(
        200,
        Map.of("Content-Type", "text/html"),
        new ByteArrayInputStream("<html>login</html>".getBytes(StandardCharsets.UTF_8))));
    CargoProxyService service = new CargoProxyService(
        assetDao,
        mock(ComponentDao.class),
        mock(BlobStorageRegistry.class),
        writer,
        mock(CargoAssetReader.class),
        proxyState,
        fetcher,
        new ProxyNegativeCache(new InMemorySharedCache(), true, 1440),
        new AssetMetadataCache(new InMemorySharedCache(), false, 0, 0),
        new ObjectMapper());

    MavenResponse response = service.search(runtime(), new CargoSearchQuery("itoa", 10, 1), false);

    assertEquals("{\"crates\":[],\"meta\":{\"total\":0}}", new String(response.body().readAllBytes(), StandardCharsets.UTF_8));
    verify(writer, never()).writeMetadata(any(), any(), anyLong(), anyString(), any(), anyString(), anyString(), any(), any(), eq(false));
  }

  private static RepositoryRuntime runtime() {
    return runtime(null);
  }

  private static RepositoryRuntime runtime(Boolean requireAuthentication) {
    return new RepositoryRuntime(
        50,
        "cargo-proxy",
        RepositoryFormat.CARGO,
        RepositoryType.PROXY,
        "cargo-proxy",
        true,
        1L,
        null,
        null,
        null,
        true,
        REMOTE,
        0,
        0,
        true,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        requireAuthentication,
        List.of());
  }

  private static RepositoryRuntime runtimeWithCredentials() {
    return new RepositoryRuntime(
        50,
        "cargo-proxy",
        RepositoryFormat.CARGO,
        RepositoryType.PROXY,
        "cargo-proxy",
        true,
        1L,
        null,
        null,
        null,
        true,
        REMOTE,
        1440,
        1440,
        true,
        "robot",
        "secret",
        null,
        null,
        null,
        null,
        null,
        null,
        List.of());
  }

  private static AssetRecord asset(String path, long blobId) {
    return new AssetRecord(
        100L + blobId,
        50L,
        null,
        blobId,
        RepositoryFormat.CARGO,
        path,
        null,
        path,
        "crate",
        "application/x-tar",
        10L,
        null,
        Instant.now(),
        Map.of());
  }

  private static AssetBlobRecord blob(long blobId) {
    return new AssetBlobRecord(
        blobId,
        1L,
        "default@" + blobId,
        null,
        "objects/" + blobId,
        null,
        "sha1",
        "sha256",
        "md5",
        10L,
        "application/x-tar",
        "proxy",
        null,
        Instant.now(),
        Instant.now(),
        Map.of());
  }

  private static CargoAssetWriter.Stored stored(String path, String body) throws Exception {
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    Path file = Files.createTempFile("kkrepo-cargo-response-", ".crate");
    Files.write(file, bytes);
    return new CargoAssetWriter.Stored(
        asset(path, 31L),
        blob(31L),
        new CargoAssetWriter.Digests("md5", "sha1", "abc123", "sha512", bytes.length),
        true,
        file);
  }

  private static final class CountingFetcher extends HttpRemoteFetcher {
    private final Queue<Result> results;
    private final List<Request> requests = new ArrayList<>();
    private final List<String> urls = new ArrayList<>();
    private int calls;

    private CountingFetcher(Result... results) {
      super(null);
      this.results = new ArrayDeque<>(List.of(results));
    }

    @Override
    public Result fetch(Request req) throws IOException {
      calls++;
      requests.add(req);
      urls.add(req.url());
      return results.remove();
    }
  }

  private static HttpRemoteFetcher.Result jsonResult(String json) {
    return new HttpRemoteFetcher.Result(
        200,
        Map.of("Content-Type", "application/json"),
        new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)));
  }
}
