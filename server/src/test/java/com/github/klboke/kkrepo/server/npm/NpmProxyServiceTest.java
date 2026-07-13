package com.github.klboke.kkrepo.server.npm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.core.RepositoryType;
import com.github.klboke.kkrepo.persistence.jdbc.api.ProxyStateDao;
import com.github.klboke.kkrepo.server.maven.HttpRemoteFetcher;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntime;
import com.github.klboke.kkrepo.server.support.dao.ProxyStateDaoAdapter;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import org.junit.jupiter.api.Test;

class NpmProxyServiceTest {
  private static final String REMOTE = "https://registry.npmjs.org";

  @Test
  void searchUsesSearchTimeoutProfile() {
    SequencedFetcher fetcher = new SequencedFetcher(result("""
        {"objects":[],"total":0,"time":"0ms"}
        """));
    RecordingProxyStateDao proxyState = new RecordingProxyStateDao();
    NpmProxyService service = service(fetcher, proxyState);

    Map<String, Object> response = service.search(runtime(), "left-pad", 20);

    assertEquals(0, response.get("total"));
    assertEquals(1, fetcher.calls);
    assertNull(fetcher.request.timeout());
    assertEquals(HttpRemoteFetcher.TimeoutProfile.SEARCH, fetcher.request.timeoutProfile());
    assertEquals(1, proxyState.successCount);
    assertEquals(0, proxyState.failureCount);
  }

  @Test
  void searchInvalidJsonIsNotBodyRetried() {
    SequencedFetcher fetcher = new SequencedFetcher(result("{bad-json"), result("""
        {"objects":[],"total":0,"time":"0ms"}
        """));
    RecordingProxyStateDao proxyState = new RecordingProxyStateDao();
    NpmProxyService service = service(fetcher, proxyState);

    assertThrows(NpmExceptions.BadUpstreamException.class, () -> service.search(runtime(), "left-pad", 20));

    assertEquals(1, fetcher.calls);
    assertEquals(1, proxyState.successCount);
    assertEquals(1, proxyState.failureCount);
  }

  private static NpmProxyService service(SequencedFetcher fetcher, RecordingProxyStateDao proxyState) {
    return new NpmProxyService(
        null,
        null,
        null,
        proxyState,
        fetcher,
        null,
        new ObjectMapper(),
        null,
        null,
        null);
  }

  private static RepositoryRuntime runtime() {
    return new RepositoryRuntime(
        10,
        "npm-proxy",
        RepositoryFormat.NPM,
        RepositoryType.PROXY,
        "npm-proxy",
        true,
        1L,
        null,
        "MIXED",
        "PERMISSIVE",
        true,
        REMOTE,
        1440,
        1440,
        true, null, List.of());
  }

  private static HttpRemoteFetcher.Result result(String body) {
    return new HttpRemoteFetcher.Result(
        200,
        Map.of(),
        new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)));
  }

  private static class SequencedFetcher extends HttpRemoteFetcher {
    private final Queue<HttpRemoteFetcher.Result> results = new ArrayDeque<>();
    private HttpRemoteFetcher.Request request;
    private int calls;

    SequencedFetcher(HttpRemoteFetcher.Result... results) {
      super(null);
      for (HttpRemoteFetcher.Result result : results) {
        this.results.add(result);
      }
    }

    @Override
    public HttpRemoteFetcher.Result fetch(HttpRemoteFetcher.Request req) {
      calls++;
      request = req;
      return results.isEmpty()
          ? new HttpRemoteFetcher.Result(500, Map.of(), InputStream.nullInputStream())
          : results.remove();
    }
  }

  private static class RecordingProxyStateDao extends ProxyStateDaoAdapter {
    int successCount;
    int failureCount;

    RecordingProxyStateDao() {
      super(null, 0);
    }

    @Override
    public boolean isBlocked(long repositoryId, Instant now) {
      return false;
    }

    @Override
    public Optional<ProxyRemoteState> loadState(long repositoryId) {
      return Optional.empty();
    }

    @Override
    public void recordSuccess(long repositoryId, Instant now) {
      successCount++;
    }

    @Override
    public ProxyRemoteState recordFailure(long repositoryId, long blockSeconds, String error, Instant now) {
      failureCount++;
      return new ProxyRemoteState(repositoryId, null, failureCount, null, now, error);
    }
  }
}
