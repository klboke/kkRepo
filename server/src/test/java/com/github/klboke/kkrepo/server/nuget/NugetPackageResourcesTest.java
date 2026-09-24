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
import java.net.URI;
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
      request.setQueryString("sig=a%2Bb%2Fc&expires=123"
          + (repository.type() == RepositoryType.GROUP ? "&_kkrepoNugetSource="
              + NugetResourceLinkToken.issue(group.id(), runtime().id(), path + "?sig=a%2Bb%2Fc&expires=123") : ""));
      for (boolean head : List.of(false, true)) {
        MavenResponse response = service.get(repository, path, BASE, request, head);
        if (response.body() != null) response.body().close();
        assertEquals(FLAT + "arp.projects/1.10.21/" + file + "?tenant=feed&sig=a%2Bb%2Fc&expires=123",
            proxy.urls.getLast());
        assertEquals(path, proxy.paths.getLast());
        assertEquals(FLAT + "arp.projects/1.10.21/" + file + "?tenant=feed", proxy.cacheSources.getLast());
        assertEquals(head, proxy.lastHead);
      }
    }
  }

  @Test
  void groupKeepsHostedPathsCanonicalAndDoesNotBroadcastUnscopedQueries() throws Exception {
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
    assertEquals(FLAT + "arp.projects/1.10.21/arp.projects.1.10.21.nupkg", proxy.urls.getLast());
  }

  @Test
  void signedLinksStayWithinTheOriginatingNestedGroupMember() throws Exception {
    RecordingProxy proxy = new RecordingProxy();
    RepositoryRuntime first = runtime(4L);
    proxy.missingRepository = first.id();
    RepositoryRuntime group = group(2L, first, group(3L, runtime()));
    NugetService service = new NugetService(null, proxy, null, MAPPER);
    String leafPath = "v3/registration5-semver1/arp.projects/1.10.21.json";
    String packagePath = "v3-flatcontainer/arp.projects/1.10.21/arp.projects.1.10.21.nupkg";
    proxy.metadata = "{\"@id\":\"" + REG + "arp.projects/1.10.21.json?api-version=7#leaf\","
        + "\"packageContent\":\"" + FLAT + "arp.projects/1.10.21/arp.projects.1.10.21.nupkg?sig=a%2Bb%2Fc&expires=123\"}";
    JsonNode leaf = json(service.get(group, leafPath, BASE, null, false));
    assertEquals(List.of(4L, 1L, 1L), proxy.repositories);
    assertEquals(BASE + leafPath + "?api-version=7&_kkrepoNugetSource="
        + NugetResourceLinkToken.issue(group.id(), runtime().id(), leafPath + "?api-version=7") + "#leaf",
        leaf.path("@id").asText());
    URI link = URI.create(leaf.path("packageContent").asText());
    assertEquals(URI.create(BASE + packagePath).getPath(), link.getPath());
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setQueryString(link.getRawQuery());
    for (boolean head : List.of(false, true)) {
      proxy.repositories.clear();
      MavenResponse response = new NugetService(null, proxy, null, MAPPER).get(group, packagePath, BASE, request, head);
      if (response.body() != null) response.body().close();
      assertEquals(List.of(1L, 1L), proxy.repositories);
      assertEquals(FLAT + "arp.projects/1.10.21/arp.projects.1.10.21.nupkg?sig=a%2Bb%2Fc&expires=123",
          proxy.urls.getLast());
    }
    // A failed origin must not send its signature to a different group member.
    proxy.repositories.clear();
    proxy.missingRepository = 1L;
    assertThrows(MavenExceptions.MavenNotFoundException.class,
        () -> service.get(group, packagePath, BASE, request, false));
    assertEquals(List.of(1L), proxy.repositories);
  }

  @ParameterizedTest
  @ValueSource(strings = {"99", "invalid", "0", "-1", "1&_kkrepoNugetSource=4", "1&%5FkkrepoNugetSource=1"})
  void invalidOrNonMemberSourcesNeverFetchAnUpstream(String source) {
    RecordingProxy proxy = new RecordingProxy();
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setQueryString("sig=secret&_kkrepoNugetSource=" + source);
    NugetService service = new NugetService(null, proxy, null, MAPPER);
    assertThrows(MavenExceptions.MavenNotFoundException.class,
        () -> service.get(group(2L, runtime()), "v3-flatcontainer/arp.projects/1.10.21/arp.projects.nuspec",
            BASE, request, false));
    assertTrue(proxy.repositories.isEmpty());
  }

  @Test
  void encodedSourceSelectorIsConsumedBeforeUpstreamFetch() throws Exception {
    RecordingProxy proxy = new RecordingProxy();
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setQueryString("sig=a%2Bb&&%5FkkrepoNugetSource=" + NugetResourceLinkToken.issue(2L, 1L,
        "v3/registration5-semver1/arp.projects/index.json?sig=a%2Bb&&") + "&");
    new NugetService(null, proxy, null, MAPPER).get(group(2L, runtime()),
        "v3/registration5-semver1/arp.projects/index.json", BASE, request, false).body().close();
    assertEquals(REG + "arp.projects/index.json?sig=a%2Bb&&", proxy.urls.getLast());
  }

  private static RepositoryRuntime group(long id, RepositoryRuntime... members) {
    return new RepositoryRuntime(id, "group-" + id, RepositoryFormat.NUGET, RepositoryType.GROUP,
        "nuget-group", true, 1L, null, null, null, true, null, null, null, null, null, List.of(members));
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
    assertEquals(BASE + "v3/registration5-semver2/" + suffix, result.path("@id").asText());
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
    String flavor = version.equals("/3.6.0") ? "semver2" : "semver1";
    get(proxy, "v3/registration5-" + flavor + "/arp.projects/index.json", false).body().close();
    assertEquals(REG + "arp.projects/index.json", proxy.urls.getLast());
  }

  @Test
  void registrationFlavorsSelectMatchingResourcesAndKeepTheirLocalLinks() throws Exception {
    RecordingProxy proxy = new RecordingProxy();
    proxy.index = "{\"resources\":[{\"@type\":\"PackageBaseAddress/3.0.0\",\"@id\":\"" + FLAT
        + "\"},{\"@type\":\"RegistrationsBaseUrl/3.4.0\",\"@id\":\"" + REG
        + "legacy/\"},{\"@type\":\"RegistrationsBaseUrl/3.6.0\",\"@id\":\"" + REG + "modern/\"}]}";
    NugetService service = new NugetService(null, proxy, null, MAPPER);
    JsonNode index = json(service.get(runtime(), "index.json", BASE, null, false));
    Map<String, String> advertised = new HashMap<>();
    index.path("resources").forEach(r -> advertised.put(r.path("@type").asText(), r.path("@id").asText()));
    for (String version : List.of("3.0.0", "3.4.0", "3.6.0")) {
      String flavor = version.equals("3.6.0") ? "semver2" : "semver1";
      assertEquals(BASE + "v3/registration5-" + flavor + "/", advertised.get("RegistrationsBaseUrl/" + version));
      String endpoint = REG + (version.equals("3.6.0") ? "modern/" : "legacy/");
      for (String suffix : List.of("arp.projects/index.json", "arp.projects/page/1/2.json", "arp.projects/1.10.21.json")) {
        proxy.metadata = "{\"@id\":\"" + endpoint + suffix + "\"}";
        String path = "v3/registration5-" + flavor + "/" + suffix;
        assertEquals(BASE + path, json(get(proxy, path, false)).path("@id").asText());
        assertEquals(endpoint + suffix, proxy.urls.getLast());
      }
    }
    proxy.index = null;
    proxy.registrationType = "RegistrationsBaseUrl/3.6.0";
    assertThrows(MavenExceptions.BadUpstreamException.class,
        () -> get(proxy, "v3/registration5-semver1/arp.projects/index.json", false));
  }

  @ParameterizedTest
  @ValueSource(strings = {"sig%6Eature", "signature", "sign+ature", "sign%20ature"})
  void encodedResourceCredentialNamesAreRemovedFromClientLinks(String key) throws Exception {
    RecordingProxy proxy = new RecordingProxy();
    proxy.registration = REG + "?" + key + "=secret";
    String decoded = java.net.URLDecoder.decode(key, StandardCharsets.UTF_8).replace(" ", "%20");
    proxy.metadata = "{\"@id\":\"" + REG + "arp.projects/index.json?" + decoded + "=secret&api-version=7\"}";
    JsonNode result = json(get(proxy, "v3/registration5-semver1/arp.projects/index.json", false));
    assertFalse(result.toString().contains("secret"));
    assertEquals(BASE + "v3/registration5-semver1/arp.projects/index.json?api-version=7", result.path("@id").asText());
  }

  @Test
  void packageCacheRemainsReachableWhenDiscoveryWasNeverCached() throws Exception {
    RecordingProxy proxy = new RecordingProxy();
    proxy.discoveryFailure = new MavenExceptions.BadUpstreamException("Upstream temporarily blocked");
    proxy.legacyResponse = MavenResponse.noBody(200);
    String path = "v3-flatcontainer/arp.projects/1.10.21/arp.projects.1.10.21.nupkg";
    assertSame(proxy.legacyResponse, get(proxy, path, true));
    assertEquals(path, proxy.legacyPath);
    assertTrue(proxy.lastHead);
    assertSame(proxy.legacyResponse, get(proxy, "v3-flatcontainer/arp.projects/index.json", false));
    assertEquals("v3-flatcontainer/arp.projects/index.json", proxy.legacyPath);
    proxy.legacyResponse = null;
    assertSame(proxy.discoveryFailure, assertThrows(MavenExceptions.BadUpstreamException.class, () -> get(proxy, path, true)));
    assertThrows(MavenExceptions.BadUpstreamException.class,
        () -> get(proxy, "v3-flatcontainer/arp.projects/index.json", false));
  }

  @Test
  void routingProofRejectsChangesToMemberGroupPathOrSignedQuery() throws Exception {
    RecordingProxy proxy = new RecordingProxy();
    NugetService service = new NugetService(null, proxy, null, MAPPER);
    String path = "v3-flatcontainer/arp.projects/1.10.21/arp.projects.1.10.21.nupkg";
    String token = NugetResourceLinkToken.issue(2L, 1L, path + "?sig=secret");
    for (String query : List.of("sig=secret&_kkrepoNugetSource=" + token.replaceFirst("1\\.", "4."),
        "sig=changed&_kkrepoNugetSource=" + token, "sig=secret&_kkrepoNugetSource=" + token + "x")) {
      MockHttpServletRequest request = new MockHttpServletRequest();
      request.setQueryString(query);
      assertThrows(MavenExceptions.MavenNotFoundException.class,
          () -> service.get(group(2L, runtime(), runtime(4L)), path, BASE, request, false));
    }
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setQueryString("sig=secret&_kkrepoNugetSource=" + token);
    assertThrows(MavenExceptions.MavenNotFoundException.class,
        () -> service.get(group(3L, runtime()), path, BASE, request, false));
    assertThrows(MavenExceptions.MavenNotFoundException.class,
        () -> service.get(group(2L, runtime()), path.replace("1.10.21", "1.10.22"), BASE, request, false));
    assertThrows(MavenExceptions.MavenNotFoundException.class,
        () -> service.get(group(2L, runtime(4L)), path, BASE, request, false));
    assertTrue(proxy.urls.isEmpty());
  }

  @ParameterizedTest
  @ValueSource(strings = {"https://DEVOPS.EXAMPLE:443/custom/registrations2/", "HTTPS://devops.example/custom/registrations2/",
      "https://devops.example/custom/%72egistrations2/", "https://devops.example/custom/unused/../registrations2/"})
  void equivalentResourceUrisAreRewrittenAndCredentialsRemainHidden(String endpoint) throws Exception {
    RecordingProxy proxy = new RecordingProxy();
    proxy.registration = endpoint + "?signature=secret";
    proxy.metadata = "{\"@id\":\"" + REG + "arp.projects/index.json?sig%6Eature=secret&page=2\"}";
    JsonNode result = json(get(proxy, "v3/registration5-semver1/arp.projects/index.json", false));
    assertEquals(BASE + "v3/registration5-semver1/arp.projects/index.json?page=2", result.path("@id").asText());
    assertFalse(result.toString().contains("secret"));
  }

  @ParameterizedTest
  @ValueSource(strings = {"tenant", "%74enant"})
  void clientQueriesCannotOverrideResourceOwnedFeedParameters(String key) {
    RecordingProxy proxy = new RecordingProxy();
    proxy.flat = FLAT + "?tenant=private";
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setQueryString(key + "=other");
    var error = assertThrows(MavenExceptions.BadRequestException.class,
        () -> new NugetService(null, proxy, null, MAPPER).get(runtime(),
            "v3-flatcontainer/arp.projects/1.10.21/arp.projects.nuspec", BASE, request, false));
    assertFalse(error.getMessage().contains("private"));
    assertEquals(List.of(INDEX), proxy.urls);
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
    return runtime(1L);
  }

  private static RepositoryRuntime runtime(long id) {
    return new RepositoryRuntime(id, "nuget-" + id, RepositoryFormat.NUGET, RepositoryType.PROXY,
        "nuget-proxy", true, 1L, null, null, null, true, INDEX, 1440, 5, true, null, List.of());
  }

  private static JsonNode json(MavenResponse response) throws IOException {
    try (var body = response.body()) { return MAPPER.readTree(body); }
  }

  private static final class RecordingProxy extends RawProxyService {
    private final List<String> urls = new ArrayList<>();
    private final List<String> paths = new ArrayList<>();
    private final List<String> cacheSources = new ArrayList<>();
    private final List<Long> repositories = new ArrayList<>();
    private Long missingRepository;
    private MavenExceptions.BadUpstreamException discoveryFailure;
    private MavenResponse legacyResponse;
    private String legacyPath;
    private String flat = FLAT;
    private String registration = REG;
    private String registrationType = "RegistrationsBaseUrl/3.4.0";
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
      if (discoveryFailure != null) throw discoveryFailure;
      repositories.add(runtime.id());
      if (missingRepository != null && runtime.id() == missingRepository) throw new MavenExceptions.MavenNotFoundException(path);
      String data = path.startsWith("_nuget/index/") ? index == null
          ? "{\"resources\":[{\"@type\":\"PackageBaseAddress/3.0.0\",\"@id\":\"" + flat
              + "\"},{\"@type\":\"" + registrationType + "\",\"@id\":\"" + registration + "\"}]}" : index : metadata;
      if (cacheRegistration && path.startsWith("_nuget/registration/")) {
        data = registrationCache.computeIfAbsent(path, ignored -> metadata);
      }
      return record(path, url, head, data);
    }

    @Override
    public java.util.Optional<MavenResponse> getLegacyNugetAsset(RepositoryRuntime runtime, String path, boolean head) {
      legacyPath = path;
      lastHead = head;
      return java.util.Optional.ofNullable(legacyResponse);
    }

    @Override
    public MavenResponse getAssetFromUrl(
        RepositoryRuntime runtime, String path, String url, String cacheSource, boolean head) {
      repositories.add(runtime.id());
      cacheSources.add(cacheSource);
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
