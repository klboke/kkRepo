package com.github.klboke.kkrepo.server.nuget;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.core.RepositoryType;
import com.github.klboke.kkrepo.server.maven.MavenExceptions;
import com.github.klboke.kkrepo.server.maven.MavenResponse;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntime;
import com.github.klboke.kkrepo.server.raw.RawProxyService;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class NugetUpstreamResourcesTest {
  private static final ObjectMapper MAPPER = new ObjectMapper();

  @ParameterizedTest
  @ValueSource(strings = {"/3.5.0", "/3.0.0", "/3.0.0-rc", "/3.0.0-beta", ""})
  void discoversSupportedVersionsAndUsesMetadataCache(String version) throws Exception {
    RecordingProxy proxy = new RecordingProxy(index("SearchQueryService" + version, "https://search.example/find"));
    try (InputStream body = get(proxy, "https://feed.example/custom.json", "query?q=a%2Bb&take=3", false).body()) {
      assertEquals("{\"totalHits\":0,\"data\":[]}", new String(body.readAllBytes(), StandardCharsets.UTF_8));
    }
    assertEquals(List.of("https://feed.example/custom.json", "https://search.example/find?q=a%2Bb&take=3"), proxy.urls);
    assertTrue(proxy.indexClosed);
    assertFalse(proxy.head.get(0));
    assertTrue(proxy.paths.get(0).startsWith("_nuget/index/"));
    assertTrue(proxy.paths.get(1).startsWith("_nuget/query/"));
  }

  @Test
  void prefersVersionSupportingPackageTypesAndPreservesEndpointQuery() {
    RecordingProxy proxy = new RecordingProxy("""
        {"resources":[
          {"@type":"SearchQueryService/3.0.0-rc","@id":"https://search.example/old"},
          {"@type":"SearchQueryService/3.5.0","@id":"https://search.example/new?tenant=one%2Ftwo"}]}
        """);
    get(proxy, "https://feed.example/index.json?secret=index-only", "query?q=&packageType=DotnetTool", true);
    assertEquals("https://search.example/new?tenant=one%2Ftwo&q=&packageType=DotnetTool", proxy.urls.get(1));
    assertEquals(List.of(false, true), proxy.head);
  }

  @ParameterizedTest
  @ValueSource(strings = {"https://feed.example/repo", "https://feed.example/repo/"})
  void resolvesRepositoryRootAndKeepsItsQueryOnIndexOnly(String base) {
    RecordingProxy proxy = new RecordingProxy(index("SearchAutocompleteService/3.5.0", "https://other.example/suggest"));
    proxy.rootUrl = base + "?token=private";
    get(proxy, base + "?token=private", "autocomplete?id=demo&prerelease=true&semVerLevel=2.0.0", true);
    assertEquals(base + "?token=private", proxy.urls.get(0));
    assertEquals("https://feed.example/repo/index.json?token=private", proxy.urls.get(1));
    assertEquals("https://other.example/suggest?id=demo&prerelease=true&semVerLevel=2.0.0", proxy.urls.get(2));
  }

  @ParameterizedTest
  @ValueSource(strings = {"https://feed.example/nuget/v3", "https://feed.example/INDEX.JSON", "https://feed.example/nuget/v3/"})
  void fetchesConfiguredServiceIndexesVerbatim(String configured) {
    RecordingProxy proxy = new RecordingProxy(index("SearchQueryService", "https://search.example/query"));
    get(proxy, configured + "?token=private", "query", true);
    assertEquals(List.of(configured + "?token=private", "https://search.example/query"), proxy.urls);
  }

  @Test
  void root404FallsBackButTransportAndAuthenticationFailuresDoNot() {
    RecordingProxy proxy = new RecordingProxy(index("SearchQueryService", "https://search.example/query"));
    proxy.rootUrl = "https://feed.example/root";
    proxy.rootFailure = new MavenExceptions.MavenNotFoundException("missing");
    get(proxy, proxy.rootUrl, "query", true);
    assertEquals(List.of(proxy.rootUrl, proxy.rootUrl + "/index.json", "https://search.example/query"), proxy.urls);
    proxy.urls.clear();
    proxy.validated.clear(); // An unproven extensionless URL must still be tried verbatim.
    proxy.rootFailure = new MavenExceptions.BadUpstreamException("Upstream returned 401");
    assertThrows(MavenExceptions.BadUpstreamException.class, () -> get(proxy, proxy.rootUrl, "query", true));
    assertEquals(List.of(proxy.rootUrl), proxy.urls);
  }

  @ParameterizedTest
  @ValueSource(strings = {"Upstream returned 503", "Upstream IO error"})
  void provenRootFallbackSurvivesRootFailureOnAnotherReplica(String failure) {
    String configured = "https://feed.example/root?token=private";
    RecordingProxy first = new RecordingProxy(index("SearchQueryService", "https://search.example/query"));
    first.rootUrl = configured;
    get(first, configured, "query", true);
    RecordingProxy replica = new RecordingProxy(first.index);
    replica.validated.addAll(first.validated);
    replica.rootUrl = configured;
    replica.rootFailure = new MavenExceptions.BadUpstreamException(failure);
    get(replica, configured, "query", true);
    assertEquals(List.of("https://feed.example/root/index.json?token=private", "https://search.example/query"), replica.urls);
    replica.urls.clear();
    replica.rootUrl = "https://feed.example/different?token=private";
    assertThrows(MavenExceptions.BadUpstreamException.class, () -> get(replica, replica.rootUrl, "query", true));
    assertEquals(List.of(replica.rootUrl), replica.urls);
  }

  @Test
  void isolatesQueriesAndResourceChangesFromOldNegativeCache() {
    RecordingProxy proxy = new RecordingProxy(index("SearchQueryService", "https://search.example/query"));
    get(proxy, "https://feed.example/index.json", "query?q=one", true);
    get(proxy, "https://feed.example/index.json", "query?q=two", true);
    assertNotEquals(proxy.paths.get(1), proxy.paths.get(3));
    assertNotEquals("query?q=one", proxy.paths.get(1));
    proxy.index = index("SearchQueryService", "https://replacement.example/query");
    get(proxy, "https://feed.example/index.json", "query?q=one", true);
    assertNotEquals(proxy.paths.get(1), proxy.paths.get(5));
    get(proxy, "https://another-feed.example/index.json", "query?q=one", true);
    assertNotEquals(proxy.paths.get(0), proxy.paths.get(6));
  }

  @ParameterizedTest
  @ValueSource(strings = {"", "null", "{", "{}", "{\"resources\":{}}", "{\"resources\":[]}",
      "{\"resources\":[{\"@type\":\"SearchQueryService/99.0.0\",\"@id\":\"https://search.example/query\"}]}"})
  void invalidOrUnsupportedDiscoveryFailsWithoutGuessingEndpoint(String index) {
    RecordingProxy proxy = new RecordingProxy(index);
    assertThrows(MavenExceptions.BadUpstreamException.class,
        () -> get(proxy, "https://feed.example/index.json", "query", false));
    assertEquals(1, proxy.urls.size());
    assertTrue(proxy.indexClosed);
  }

  @ParameterizedTest
  @ValueSource(strings = {"/query", "file:///secret", "https://user:secret@search.example/query",
      "https://search.example/query#fragment", "https://", "not a URL", ""})
  void rejectsInvalidResourceUrls(String endpoint) {
    RecordingProxy proxy = new RecordingProxy(index("SearchQueryService", endpoint));
    assertThrows(MavenExceptions.BadUpstreamException.class,
        () -> get(proxy, "https://feed.example/index.json", "query", false));
    assertEquals(1, proxy.urls.size());
  }

  @Test
  void rejectsOversizedIndexAndClosesBody() {
    RecordingProxy proxy = new RecordingProxy(" ".repeat(1024 * 1024 + 1));
    assertThrows(MavenExceptions.BadUpstreamException.class,
        () -> get(proxy, "https://feed.example/index.json", "query", false));
    assertTrue(proxy.indexClosed);
  }

  private static MavenResponse get(RecordingProxy proxy, String upstream, String path, boolean head) {
    RepositoryRuntime runtime = new RepositoryRuntime(1L, "nuget", RepositoryFormat.NUGET,
        RepositoryType.PROXY, "nuget-proxy", true, 1L, null, null, null, true,
        upstream, 1440, 5, true, null, List.of());
    return NugetUpstreamResources.get(proxy, MAPPER, runtime, path, head);
  }

  private static String index(String type, String endpoint) {
    return "{\"resources\":[{\"@type\":\"" + type + "\",\"@id\":\"" + endpoint + "\"}]}";
  }

  private static final class RecordingProxy extends RawProxyService {
    private String index;
    private String rootUrl;
    private RuntimeException rootFailure;
    private boolean indexClosed;
    private final List<String> urls = new ArrayList<>();
    private final List<String> paths = new ArrayList<>();
    private final List<Boolean> head = new ArrayList<>();
    private final java.util.Set<String> validated = new java.util.HashSet<>();

    RecordingProxy(String index) {
      super(null, null, null, null, null, null, null, null);
      this.index = index;
    }

    @Override
    public boolean hasValidatedMetadataFromUrlHidden(RepositoryRuntime runtime, String path, String url, String validationId) {
      return validated.contains(runtime.proxyRemoteUrl() + "\n" + url);
    }

    @Override
    public MavenResponse getMetadataFromUrlHidden(
        RepositoryRuntime runtime, String path, String url, boolean head,
        String validationId, java.util.function.UnaryOperator<java.io.InputStream> validator) {
      MavenResponse response = getMetadataFromUrlHidden(runtime, path, url, false);
      var checked = validator.apply(response.body());
      validated.add(runtime.proxyRemoteUrl() + "\n" + url);
      return MavenResponse.ok(checked, response.contentLength(), "application/json", null, null);
    }

    @Override
    public MavenResponse getMetadataFromUrlHidden(RepositoryRuntime runtime, String path, String url, boolean headOnly) {
      urls.add(url);
      paths.add(path);
      head.add(headOnly);
      boolean discovery = path.startsWith("_nuget/index/");
      if (url.equals(rootUrl) && rootFailure != null) throw rootFailure;
      byte[] bytes = (url.equals(rootUrl) ? "<html>Repository browser</html>" : discovery ? index : "{\"totalHits\":0,\"data\":[]}").getBytes(StandardCharsets.UTF_8);
      if (headOnly) return MavenResponse.noBody(200, bytes.length, "application/json", null, null);
      return MavenResponse.ok(new ByteArrayInputStream(bytes) {
        @Override
        public void close() throws IOException {
          if (discovery) indexClosed = true;
          super.close();
        }
      }, bytes.length, "application/json", null, null);
    }
  }
}
