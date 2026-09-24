package com.github.klboke.kkrepo.server.nuget;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.core.RepositoryType;
import com.github.klboke.kkrepo.server.maven.MavenExceptions;
import com.github.klboke.kkrepo.server.maven.MavenResponse;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntime;
import com.github.klboke.kkrepo.server.raw.RawProxyService;
import com.github.klboke.kkrepo.server.raw.RawHostedService;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;

class NugetPackageResourcesTest {
  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final String BASE = "https://kkrepo.example/repository/private/";
  private static final String INDEX = "https://devops.example/collection/_packaging/feed/nuget/v3/index.json?indexToken=private";
  private static final String FLAT = "https://content.example/custom/flat2/";
  private static final String REG = "https://devops.example/custom/registrations2/";

  @Test
  void versionsUseDiscoveredCrossHostResourceAndHiddenMetadataCache() throws Exception {
    RecordingProxy proxy = new RecordingProxy();
    JsonNode response = json(get(proxy, "v3-flatcontainer/Arp.Projects/index.json", false));
    assertEquals("1.10.21", response.path("versions").get(0).asText());
    assertEquals(List.of(INDEX, FLAT + "arp.projects/index.json"), proxy.urls);
    assertTrue(proxy.paths.getLast().startsWith("_nuget/versions/"));
    assertEquals(0, proxy.contentRequests);
  }

  @ParameterizedTest
  @ValueSource(strings = {"arp.projects.1.10.21.nupkg", "arp.projects.nuspec"})
  void packageBodiesKeepCanonicalStorageAndDownloadPolicyPath(String file) throws Exception {
    RecordingProxy proxy = new RecordingProxy();
    String path = "v3-flatcontainer/arp.projects/1.10.21/" + file;
    MavenResponse response = get(proxy, path, true);
    assertEquals(200, response.status());
    assertNull(response.body());
    assertEquals(path, proxy.paths.getLast());
    assertEquals(FLAT + "arp.projects/1.10.21/" + file, proxy.urls.getLast());
    assertTrue(proxy.lastHead);
    assertEquals(1, proxy.contentRequests);
  }

  @ParameterizedTest
  @ValueSource(strings = {"arp.projects.1.10.21.nupkg", "arp.projects.nuspec"})
  void packageRequestsForwardSignedQueriesWithoutAddingThemToAssetPaths(String file) throws Exception {
    RecordingProxy proxy = new RecordingProxy();
    proxy.flat = FLAT + "?tenant=feed";
    String path = "v3-flatcontainer/arp.projects/1.10.21/" + file;
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setQueryString("sig=a%2Bb%2Fc&expires=123");
    NugetService service = new NugetService(null, proxy, null, MAPPER);
    RepositoryRuntime group = new RepositoryRuntime(2L, "group", RepositoryFormat.NUGET, RepositoryType.GROUP,
        "nuget-group", true, 1L, null, null, null, true, null, null, null, null, null, List.of(runtime()));
    for (RepositoryRuntime repository : List.of(runtime(), group)) {
      for (boolean head : List.of(false, true)) {
        MavenResponse response = service.get(repository, path, BASE, request, head);
        if (response.body() != null) response.body().close();
        assertEquals(FLAT + "arp.projects/1.10.21/" + file + "?tenant=feed&sig=a%2Bb%2Fc&expires=123",
            proxy.urls.getLast());
        assertEquals(path, proxy.paths.getLast());
        assertEquals(head, proxy.lastHead);
      }
    }
  }

  @Test
  void groupKeepsHostedPathsCanonicalWhenFollowingASignedPackageLink() throws Exception {
    RecordingProxy proxy = new RecordingProxy();
    RawHostedService hosted = mock(RawHostedService.class);
    RepositoryRuntime hostedRepository = new RepositoryRuntime(3L, "hosted", RepositoryFormat.NUGET, RepositoryType.HOSTED,
        "nuget-hosted", true, 1L, null, null, null, true, null, null, null, null, null, List.of());
    RepositoryRuntime group = new RepositoryRuntime(2L, "group", RepositoryFormat.NUGET, RepositoryType.GROUP,
        "nuget-group", true, 1L, null, null, null, true, null, null, null, null, null,
        List.of(hostedRepository, runtime()));
    String path = "v3-flatcontainer/arp.projects/1.10.21/arp.projects.1.10.21.nupkg";
    when(hosted.get(hostedRepository, path, true)).thenThrow(new MavenExceptions.MavenNotFoundException(path));
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setQueryString("sig=signature");
    MavenResponse response = new NugetService(hosted, proxy, null, MAPPER).get(group, path, BASE, request, true);
    assertEquals(200, response.status());
    verify(hosted).get(hostedRepository, path, true);
    assertEquals(path, proxy.paths.getLast());
    assertEquals(FLAT + "arp.projects/1.10.21/arp.projects.1.10.21.nupkg?sig=signature", proxy.urls.getLast());
  }

  @Test
  void refreshingOnlyTheFlatResourceDoesNotReuseRegistrationLinksFromThePreviousResource() throws Exception {
    RecordingProxy proxy = new RecordingProxy();
    proxy.cacheRegistration = true;
    String packageSuffix = "arp.projects/1.10.21/arp.projects.1.10.21.nupkg";
    proxy.metadata = "{\"packageContent\":\"" + FLAT + packageSuffix + "\"}";
    String path = "v3/registration5-semver1/arp.projects/index.json";
    assertEquals(BASE + "v3-flatcontainer/" + packageSuffix, json(get(proxy, path, false)).path("packageContent").asText());
    // Discovery expires before the registration document. Its URL remains the same,
    // but the new document now points to the replacement PackageBaseAddress.
    proxy.flat = "https://replacement.example/flat2/";
    proxy.metadata = "{\"packageContent\":\"" + proxy.flat + packageSuffix + "\"}";
    assertEquals(BASE + "v3-flatcontainer/" + packageSuffix, json(get(proxy, path, false)).path("packageContent").asText());
    assertEquals(REG + "arp.projects/index.json", proxy.urls.getLast());
  }

  @Test
  void rewritesRegistrationPagesLeavesDependenciesAndPackageLinks() throws Exception {
    RecordingProxy proxy = new RecordingProxy();
    proxy.metadata = """
        {"@id":"%sarp.projects/index.json","items":[
          {"@id":"%sarp.projects/page/1.0.0/2.0.0.json","parent":"%sarp.projects/index.json"},
          {"@id":"%sarp.projects/1.10.21.json", "packageContent":"%sarp.projects/1.10.21/arp.projects.1.10.21.nupkg",
           "catalogEntry":{"id":"arp.projects","projectUrl":"https://project.example/",
             "dependencyGroups":[{"dependencies":[{"registration":"%sdependency/index.json"}]}]}}]}
        """.formatted(REG, REG, REG, REG, FLAT, REG);
    JsonNode result = json(get(proxy, "v3/registration5-semver1/arp.projects/index.json", false));
    assertEquals(BASE + "v3/registration5-semver1/arp.projects/index.json", result.path("@id").asText());
    assertEquals(BASE + "v3/registration5-semver1/arp.projects/page/1.0.0/2.0.0.json",
        result.path("items").get(0).path("@id").asText());
    assertEquals(BASE + "v3-flatcontainer/arp.projects/1.10.21/arp.projects.1.10.21.nupkg",
        result.path("items").get(1).path("packageContent").asText());
    assertFalse(result.toString().contains("devops.example"));
    assertFalse(result.toString().contains("content.example"));
    assertTrue(result.toString().contains("https://project.example/"));
    assertEquals(REG + "arp.projects/index.json", proxy.urls.getLast());
    assertTrue(proxy.closed);
  }

  @ParameterizedTest
  @ValueSource(strings = {"arp.projects/page/1.0.0/2.0.0.json", "arp.projects/1.10.21.json"})
  void followsRegistrationPageAndLeafThroughTheSameProxy(String suffix) throws Exception {
    RecordingProxy proxy = new RecordingProxy();
    proxy.metadata = "{\"@id\":\"" + REG + suffix + "\"}";
    JsonNode result = json(get(proxy, "v3/registration5-semver2/" + suffix, false));
    assertEquals(REG + suffix, proxy.urls.getLast());
    assertEquals(BASE + "v3/registration5-semver1/" + suffix, result.path("@id").asText());
    assertEquals(0, proxy.contentRequests);
  }

  @Test
  void registrationHeadDescribesRewrittenBodyAndClosesUpstreamResponse() throws Exception {
    RecordingProxy proxy = new RecordingProxy();
    proxy.metadata = "{\"@id\":\"" + REG + "arp.projects/index.json\"}";
    MavenResponse get = get(proxy, "v3/registration5-semver1/arp.projects/index.json", false);
    int length;
    try (var body = get.body()) { length = body.readAllBytes().length; }
    MavenResponse head = get(proxy, "v3/registration5-semver1/arp.projects/index.json", true);
    assertNull(head.body());
    assertEquals(length, head.contentLength());
    assertTrue(proxy.closed);
    assertFalse(proxy.lastHead, "rewriting HEAD still needs the cached metadata body");
  }

  @Test
  void decodesGzippedRegistrationFromTheDurableMetadataCache() throws Exception {
    RecordingProxy proxy = new RecordingProxy();
    proxy.metadata = "{\"@id\":\"" + REG + "arp.projects/index.json\"}";
    proxy.gzipMetadata = true;
    JsonNode result = json(get(proxy, "v3/registration5-semver1/arp.projects/index.json", false));
    assertEquals(BASE + "v3/registration5-semver1/arp.projects/index.json", result.path("@id").asText());
    assertTrue(proxy.closed);
  }

  @Test
  void groupMemberUsesDiscoveryForVersionListsAndDownloads() throws Exception {
    RecordingProxy proxy = new RecordingProxy();
    NugetService service = new NugetService(null, proxy, null, MAPPER);
    RepositoryRuntime group = new RepositoryRuntime(2L, "group", RepositoryFormat.NUGET, RepositoryType.GROUP,
        "nuget-group", true, 1L, null, null, null, true, null, null, null, null, null, List.of(runtime()));
    JsonNode versions = json(service.get(group, "v3-flatcontainer/arp.projects/index.json", BASE, null, false));
    assertEquals("1.10.21", versions.path("versions").get(0).asText());
    service.get(group, "v3-flatcontainer/arp.projects/1.10.21/arp.projects.1.10.21.nupkg", BASE, null, true);
    assertEquals(1, proxy.contentRequests);
  }

  @Test
  void keepsResourceQueryButDoesNotForwardIndexCredentials() throws Exception {
    RecordingProxy proxy = new RecordingProxy();
    proxy.flat = FLAT + "?tenant=a%2Fb";
    get(proxy, "v3-flatcontainer/arp.projects/index.json", false).body().close();
    assertEquals(FLAT + "arp.projects/index.json?tenant=a%2Fb", proxy.urls.getLast());
    assertFalse(proxy.urls.getLast().contains("indexToken"));
  }

  @ParameterizedTest
  @ValueSource(strings = {"arp.projects/index.json", "arp.projects/page/1.0.0/2.0.0.json"})
  void registrationKeepsPageParametersButHidesResourceCredentials(String suffix) throws Exception {
    RecordingProxy proxy = new RecordingProxy();
    proxy.registration = REG + "?token=private";
    proxy.metadata = "{\"@id\":\"" + REG + suffix + "?token=private&api-version=7#page\"}";
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setQueryString("api-version=7");
    JsonNode result = json(new NugetService(null, proxy, null, MAPPER).get(runtime(),
        "v3/registration5-semver1/" + suffix, BASE, request, false));
    assertEquals(REG + suffix + "?token=private&api-version=7", proxy.urls.getLast());
    assertEquals(BASE + "v3/registration5-semver1/" + suffix + "?api-version=7#page",
        result.path("@id").asText());
  }

  @ParameterizedTest
  @ValueSource(strings = {"", "/3.0.0-beta", "/3.0.0-rc", "/3.4.0", "/3.6.0"})
  void supportsRegistrationResourceVersions(String version) throws Exception {
    RecordingProxy proxy = new RecordingProxy();
    proxy.registrationType = "RegistrationsBaseUrl" + version;
    proxy.metadata = "{}";
    get(proxy, "v3/registration5-semver1/arp.projects/index.json", false).body().close();
    assertEquals(REG + "arp.projects/index.json", proxy.urls.getLast());
  }

  @Test
  void missingResourceFailsExplicitlyWithoutGuessingAPath() {
    RecordingProxy proxy = new RecordingProxy();
    proxy.index = "{\"resources\":[]}";
    var error = assertThrows(MavenExceptions.BadUpstreamException.class,
        () -> get(proxy, "v3-flatcontainer/arp.projects/index.json", false));
    assertTrue(error.getMessage().contains("PackageBaseAddress"));
    assertEquals(List.of(INDEX), proxy.urls);
  }

  @ParameterizedTest
  @ValueSource(strings = {"null", "[]", "{", ""})
  void malformedRegistrationFailsAndClosesBody(String metadata) {
    RecordingProxy proxy = new RecordingProxy();
    proxy.metadata = metadata;
    assertThrows(MavenExceptions.BadUpstreamException.class,
        () -> get(proxy, "v3/registration5-semver1/arp.projects/index.json", false));
    assertTrue(proxy.closed);
  }

  @Test
  void rejectsTraversalBeforeFetchingContent() {
    RecordingProxy proxy = new RecordingProxy();
    assertThrows(MavenExceptions.MavenNotFoundException.class,
        () -> get(proxy, "v3/registration5-semver1/../secret.json", false));
    assertEquals(1, proxy.urls.size());
  }

  private static MavenResponse get(RecordingProxy proxy, String path, boolean head) {
    return new NugetService(null, proxy, null, MAPPER).get(runtime(), path, BASE, null, head);
  }

  private static RepositoryRuntime runtime() {
    return new RepositoryRuntime(1L, "nuget", RepositoryFormat.NUGET, RepositoryType.PROXY,
        "nuget-proxy", true, 1L, null, null, null, true, INDEX, 1440, 5, true, null, List.of());
  }

  private static JsonNode json(MavenResponse response) throws IOException {
    try (var body = response.body()) { return MAPPER.readTree(body); }
  }

  private static final class RecordingProxy extends RawProxyService {
    private final List<String> urls = new ArrayList<>();
    private final List<String> paths = new ArrayList<>();
    private String flat = FLAT;
    private String registration = REG;
    private String registrationType = "RegistrationsBaseUrl/3.6.0";
    private String index;
    private String metadata = "{\"versions\":[\"1.10.21\"]}";
    private boolean closed;
    private boolean lastHead;
    private boolean gzipMetadata;
    private boolean cacheRegistration;
    private final Map<String, String> registrationCache = new HashMap<>();
    private int contentRequests;
    RecordingProxy() { super(null, null, null, null, null, null, null, null); }

    @Override
    public MavenResponse getMetadataFromUrlHidden(RepositoryRuntime runtime, String path, String url, boolean head) {
      String data = path.startsWith("_nuget/index/") ? index == null
          ? "{\"resources\":[{\"@type\":\"PackageBaseAddress/3.0.0\",\"@id\":\"" + flat
              + "\"},{\"@type\":\"" + registrationType + "\",\"@id\":\"" + registration + "\"}]}" : index : metadata;
      if (cacheRegistration && path.startsWith("_nuget/registration/")) {
        data = registrationCache.computeIfAbsent(path, ignored -> metadata);
      }
      return record(path, url, head, data);
    }

    @Override
    public MavenResponse getAssetFromUrl(RepositoryRuntime runtime, String path, String url, boolean head) {
      contentRequests++;
      return record(path, url, head, "package");
    }

    private MavenResponse record(String path, String url, boolean head, String data) {
      paths.add(path);
      urls.add(url);
      lastHead = head;
      byte[] bytes = data.getBytes(StandardCharsets.UTF_8);
      if (gzipMetadata && !path.startsWith("_nuget/index/")) {
        try {
          ByteArrayOutputStream compressed = new ByteArrayOutputStream();
          try (GZIPOutputStream gzip = new GZIPOutputStream(compressed)) { gzip.write(bytes); }
          bytes = compressed.toByteArray();
        } catch (IOException e) {
          throw new AssertionError(e);
        }
      }
      if (head) return MavenResponse.noBody(200, bytes.length, "application/json", null, null);
      return MavenResponse.ok(new ByteArrayInputStream(bytes) {
        @Override public void close() throws IOException { closed = true; super.close(); }
      }, bytes.length, "application/json", null, null);
    }
  }
}
