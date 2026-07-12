package com.github.klboke.kkrepo.server.composer;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.klboke.kkrepo.core.RepositoryType;
import com.github.klboke.kkrepo.persistence.mysql.dao.AssetDao;
import com.github.klboke.kkrepo.persistence.mysql.dao.ProxyStateDao;
import com.github.klboke.kkrepo.persistence.mysql.model.AssetRecord;
import com.github.klboke.kkrepo.protocol.composer.ComposerPathParser;
import com.github.klboke.kkrepo.server.maven.HttpRemoteFetcher;
import com.github.klboke.kkrepo.server.maven.MavenResponse;
import com.github.klboke.kkrepo.server.maven.ProxyNegativeCache;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntime;
import com.github.klboke.kkrepo.server.raw.RawProxyService;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ComposerProxyServiceTest {
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
