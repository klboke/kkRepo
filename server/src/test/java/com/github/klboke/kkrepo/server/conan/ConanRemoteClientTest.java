package com.github.klboke.kkrepo.server.conan;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.core.RepositoryType;
import com.github.klboke.kkrepo.server.maven.HttpRemoteFetcher;
import com.github.klboke.kkrepo.server.maven.MavenResponse;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntime;
import com.github.klboke.kkrepo.server.raw.RawProxyService;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class ConanRemoteClientTest {
  private final RawProxyService proxy = mock(RawProxyService.class);
  private final HttpRemoteFetcher fetcher = mock(HttpRemoteFetcher.class);
  private final ConanRemoteClient client = new ConanRemoteClient(proxy, fetcher);

  @Test
  void returnsBoundedDiscoveryMetadataWithoutCredentials() {
    Instant modified = Instant.parse("2026-01-02T03:04:05Z");
    when(proxy.getMetadataFromUrlHidden(any(), anyString(), anyString(), anyBoolean()))
        .thenReturn(MavenResponse.ok(
            new ByteArrayInputStream("{\"ok\":true}".getBytes(StandardCharsets.UTF_8)),
            11, "application/json", "etag", modified));

    ConanRemoteClient.Discovery result =
        client.discovery(runtime(null, null, null), "v2/conans/search", "q=demo");

    assertArrayEquals("{\"ok\":true}".getBytes(StandardCharsets.UTF_8), result.bytes());
    assertEquals("application/json", result.contentType());
    assertEquals(modified, result.lastModified());
  }

  @Test
  void exchangesAndCachesBearerTokenForBasicUpstreams() throws Exception {
    when(fetcher.fetch(any())).thenReturn(result(200, "upstream-token"));
    when(proxy.getMetadataFromUrlHidden(any(), anyString(), anyString(), anyBoolean()))
        .thenReturn(MavenResponse.ok(
            new ByteArrayInputStream("{}".getBytes(StandardCharsets.UTF_8)),
            2, "application/json", null, Instant.EPOCH));
    RepositoryRuntime runtime = runtime("robot", "secret", null);

    client.discovery(runtime, "v2/conans/search", null);
    client.discovery(runtime, "v2/conans/search", null);

    verify(fetcher, times(1)).fetch(any());
    ArgumentCaptor<RepositoryRuntime> authenticated =
        ArgumentCaptor.forClass(RepositoryRuntime.class);
    verify(proxy, times(2)).getMetadataFromUrlHidden(
        authenticated.capture(), anyString(), anyString(), anyBoolean());
    assertEquals("upstream-token", authenticated.getAllValues().get(0).proxyRemoteBearerToken());
    assertEquals(null, authenticated.getAllValues().get(0).proxyRemoteUsername());
  }

  @Test
  void retriesDiscoveryWithAFreshTokenAfterAuthenticatedFailure() throws Exception {
    when(fetcher.fetch(any()))
        .thenReturn(result(200, "token-one"), result(200, "token-two"));
    when(proxy.getMetadataFromUrlHidden(any(), anyString(), anyString(), anyBoolean()))
        .thenThrow(new IllegalStateException("stale token"))
        .thenReturn(MavenResponse.ok(
            new ByteArrayInputStream("{}".getBytes(StandardCharsets.UTF_8)),
            2, "application/json", null, Instant.EPOCH));

    assertEquals(2, client.discovery(
        runtime("robot", "secret", null), "v2/conans/search", null).bytes().length);
    verify(fetcher, times(2)).fetch(any());
    verify(proxy, times(2)).getMetadataFromUrlHidden(
        any(), anyString(), anyString(), anyBoolean());
  }

  @Test
  void retriesFileFetchAfterClosingUnauthorizedResponse() throws Exception {
    TrackingInputStream firstBody = new TrackingInputStream(new byte[0]);
    HttpRemoteFetcher.Result unauthorized =
        new HttpRemoteFetcher.Result(401, Map.of(), firstBody);
    HttpRemoteFetcher.Result firstExchange = result(200, "token");
    HttpRemoteFetcher.Result secondExchange = result(200, "token");
    HttpRemoteFetcher.Result success = result(200, "payload");
    when(fetcher.fetch(any())).thenReturn(
        firstExchange, unauthorized, secondExchange, success);
    RepositoryRuntime runtime = runtime("robot", "secret", null);

    HttpRemoteFetcher.Result response = client.fetchFile(runtime, "file.tgz");

    assertSame(success, response);
    assertTrue(firstBody.closed);
    verify(fetcher, times(4)).fetch(any());
  }

  @Test
  void validatesDiscoveryAndAuthenticationResponses() throws Exception {
    when(proxy.getMetadataFromUrlHidden(any(), anyString(), anyString(), anyBoolean()))
        .thenReturn(MavenResponse.ok(InputStream.nullInputStream(), 33L * 1024 * 1024,
            "application/json", null, null));
    assertThrows(
        ConanExceptions.BadUpstream.class,
        () -> client.discovery(runtime(null, null, null), "search", null));

    when(proxy.getMetadataFromUrlHidden(any(), anyString(), anyString(), anyBoolean()))
        .thenReturn(MavenResponse.ok(
            boundedStream(32L * 1024 * 1024 + 1), 0, "application/json", null, null));
    assertThrows(
        ConanExceptions.BadUpstream.class,
        () -> client.discovery(runtime(null, null, null), "oversized", null));

    InputStream brokenDiscovery = new InputStream() {
      @Override
      public int read() throws IOException {
        throw new IOException("broken discovery");
      }
    };
    when(proxy.getMetadataFromUrlHidden(any(), anyString(), anyString(), anyBoolean()))
        .thenReturn(MavenResponse.ok(brokenDiscovery, 0, "application/json", null, null));
    assertThrows(
        ConanExceptions.BadUpstream.class,
        () -> client.discovery(runtime(null, null, null), "broken", null));

    when(fetcher.fetch(any())).thenReturn(result(401, "denied"));
    assertThrows(
        ConanExceptions.BadUpstream.class,
        () -> client.discovery(runtime("robot", "secret", null), "search", null));

    ConanRemoteClient blankTokenClient = new ConanRemoteClient(proxy, fetcher);
    when(fetcher.fetch(any())).thenReturn(result(200, "  "));
    assertThrows(
        ConanExceptions.BadUpstream.class,
        () -> blankTokenClient.discovery(runtime("other", "secret", null), "search", null));

    ConanRemoteClient controlTokenClient = new ConanRemoteClient(proxy, fetcher);
    when(fetcher.fetch(any())).thenReturn(result(200, "bad\\u0001token"));
    assertThrows(
        ConanExceptions.BadUpstream.class,
        () -> controlTokenClient.discovery(runtime("control", "secret", null), "search", null));

    ConanRemoteClient brokenTokenClient = new ConanRemoteClient(proxy, fetcher);
    InputStream brokenToken = new InputStream() {
      @Override
      public int read() throws IOException {
        throw new IOException("broken token");
      }
    };
    when(fetcher.fetch(any())).thenReturn(
        new HttpRemoteFetcher.Result(200, Map.of(), brokenToken));
    assertThrows(
        ConanExceptions.BadUpstream.class,
        () -> brokenTokenClient.discovery(runtime("io", "secret", null), "search", null));
  }

  private static InputStream boundedStream(long length) {
    return new InputStream() {
      private long remaining = length;

      @Override
      public int read() {
        if (remaining == 0) return -1;
        remaining--;
        return 0;
      }

      @Override
      public int read(byte[] bytes, int offset, int requested) {
        if (remaining == 0) return -1;
        int count = (int) Math.min(remaining, requested);
        remaining -= count;
        return count;
      }
    };
  }

  private static HttpRemoteFetcher.Result result(int status, String value) {
    return new HttpRemoteFetcher.Result(
        status, Map.of(), new ByteArrayInputStream(value.getBytes(StandardCharsets.UTF_8)));
  }

  private static RepositoryRuntime runtime(
      String username, String password, String bearerToken) {
    return new RepositoryRuntime(
        1L, "conan-proxy", RepositoryFormat.CONAN, RepositoryType.PROXY, "conan-proxy",
        true, 1L, null, null, null, true, "https://repo.example/conan", 1440, 60,
        true, username, password, bearerToken, null, null, null, null, null, List.of(), null);
  }

  private static final class TrackingInputStream extends ByteArrayInputStream {
    private boolean closed;

    private TrackingInputStream(byte[] bytes) {
      super(bytes);
    }

    @Override
    public void close() throws IOException {
      closed = true;
      super.close();
    }
  }
}
