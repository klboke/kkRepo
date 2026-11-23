package com.github.klboke.kkrepo.server.composer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.klboke.kkrepo.core.RepositoryType;
import com.github.klboke.kkrepo.persistence.jdbc.api.AssetDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.ProxyStateDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetRecord;
import com.github.klboke.kkrepo.protocol.composer.ComposerPathParser;
import com.github.klboke.kkrepo.server.maven.HttpRemoteFetcher;
import com.github.klboke.kkrepo.server.maven.MavenExceptions;
import com.github.klboke.kkrepo.server.maven.MavenResponse;
import com.github.klboke.kkrepo.server.maven.ProxyNegativeCache;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntime;
import com.github.klboke.kkrepo.server.proxy.RepositoryOutboundBlock.Blocked;
import com.github.klboke.kkrepo.server.raw.RawProxyService;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ComposerProxyServiceTest {
  @Test
  void manualBlockServesStaleDiscoverableMetadataAndKeepsMissesLocal() throws Exception {
    var assets = mock(ComposerAssetSupport.class);
    var fetcher = mock(HttpRemoteFetcher.class);
    var state = mock(ProxyStateDao.class);
    var service = new ComposerProxyService(new ObjectMapper(), mock(AssetDao.class), assets,
        fetcher, mock(ProxyNegativeCache.class), state, mock(RawProxyService.class));
    var runtime = ComposerHostedServiceTest.runtime("composer-proxy", RepositoryType.PROXY, List.of());
    var path = new ComposerPathParser().parse("packages.json");
    var cached = mock(AssetRecord.class);
    when(cached.assetBlobId()).thenReturn(null);
    when(cached.lastUpdatedAt()).thenReturn(Instant.EPOCH);
    when(assets.find(runtime, "packages.json")).thenReturn(Optional.of(cached), Optional.empty());
    when(assets.readBytes(runtime, "packages.json")).thenReturn(
        "{\"metadata-url\":\"/p2/%package%.json\"}".getBytes(StandardCharsets.UTF_8));
    when(fetcher.fetchWithBodyRetry(any(), anyString(), any())).thenThrow(new Blocked());

    assertEquals(200, service.get(runtime, path, "https://kkrepo.test/repository/composer-proxy", null, true).status());
    assertThrows(MavenExceptions.MavenNotFoundException.class,
        () -> service.get(runtime, path, "https://kkrepo.test/repository/composer-proxy", null, true));
    verify(state, org.mockito.Mockito.never()).recordSuccess(
        org.mockito.ArgumentMatchers.eq(runtime.id()), any());
  }

  @Test
  void servesMigratedCachedDistWithoutPrivateRouteRecord() {
    ComposerAssetSupport assets = mock(ComposerAssetSupport.class);
    RawProxyService rawProxy = mock(RawProxyService.class);
    ComposerProxyService service = new ComposerProxyService(
        new ObjectMapper(),
        mock(AssetDao.class),
        assets,
        mock(HttpRemoteFetcher.class),
        mock(ProxyNegativeCache.class),
        mock(ProxyStateDao.class),
        rawProxy);
    RepositoryRuntime runtime = ComposerHostedServiceTest.runtime(
        "composer-proxy", RepositoryType.PROXY, List.of());
    String path = "symfony/polyfill-php84/v1.38.1/symfony-polyfill-php84-v1.38.1.zip";
    AssetRecord asset = mock(AssetRecord.class);
    MavenResponse expected = MavenResponse.noBody(200, 123, "application/zip", "sha1", null);
    when(assets.find(runtime, path)).thenReturn(Optional.of(asset));
    when(assets.serve(runtime, path, false)).thenReturn(expected);

    MavenResponse actual = service.get(
        runtime, new ComposerPathParser().parse(path), "http://localhost/repository/composer-proxy", null, false);

    assertSame(expected, actual);
    verify(assets).serve(runtime, path, false);
    verifyNoInteractions(rawProxy);
  }
}
