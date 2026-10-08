package com.github.klboke.kkrepo.server.pub;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.core.RepositoryType;
import com.github.klboke.kkrepo.persistence.jdbc.api.AssetDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.ProxyStateDao;
import com.github.klboke.kkrepo.protocol.pub.PubPathParser;
import com.github.klboke.kkrepo.server.cache.AssetMetadataCache;
import com.github.klboke.kkrepo.server.maven.BlobStorageRegistry;
import com.github.klboke.kkrepo.server.maven.HttpRemoteFetcher;
import com.github.klboke.kkrepo.server.maven.ProxyNegativeCache;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntime;
import com.github.klboke.kkrepo.server.security.ProxyRedirectPolicy;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class PubMetadataRedirectTest {
  @Test
  void pubMetadataOptsIntoPublicContentTransportAndRetainsNotFoundOutcome() throws Exception {
    var captured = new AtomicReference<HttpRemoteFetcher.Request>();
    var fetcher = missingMetadata(captured);
    var service = new PubProxyService(mock(AssetDao.class), mock(BlobStorageRegistry.class), mock(PubAssetWriter.class),
        mock(PubAssetReader.class), mock(ProxyStateDao.class), fetcher, mock(ProxyNegativeCache.class),
        mock(AssetMetadataCache.class), new ObjectMapper());
    assertThrows(PubExceptions.PubNotFoundException.class, () -> service.get(
        runtime(RepositoryFormat.PUB, "https://pub.dev/"), new PubPathParser().parse("api/packages/demo"),
        "http://localhost/repository/pub/", false));
    assertEquals("https://pub.dev/api/packages/demo", captured.get().url());
    assertContent(captured.get());
  }

  private static void assertContent(HttpRemoteFetcher.Request request) {
    assertEquals(ProxyRedirectPolicy.PUBLIC_HTTPS, request.redirectPolicy());
    assertEquals(HttpRemoteFetcher.TimeoutProfile.METADATA, request.timeoutProfile());
    assertNull(request.requestBody());
  }

  private static HttpRemoteFetcher missingMetadata(AtomicReference<HttpRemoteFetcher.Request> captured) throws Exception {
    var fetcher = mock(HttpRemoteFetcher.class);
    doAnswer(call -> {
      captured.set(call.getArgument(0));
      HttpRemoteFetcher.ResultHandler<?> handler = call.getArgument(2);
      return handler.handle(new HttpRemoteFetcher.Result(404, Map.of(), InputStream.nullInputStream()));
    }).when(fetcher).fetchWithBodyRetry(any(), anyString(), any());
    return fetcher;
  }

  private static RepositoryRuntime runtime(RepositoryFormat format, String upstream) {
    return new RepositoryRuntime(1L, "content-proxy", format, RepositoryType.PROXY, "content-proxy", true,
        1L, "ALLOW_ONCE", null, null, true, upstream, 1440, 1440, true,
        null, null, null, null, null, null, null, null, List.of(), null, null, Set.of("*"), null);
  }
}
