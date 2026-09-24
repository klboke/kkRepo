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
              + NugetResourceLinkToken.issue(group.id(), runtime().id(), path + "?sig=a%2Bb%2Fc&expires=123", NugetResourceLinkToken.identity(INDEX, proxy.flat)) : ""));
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

  @ParameterizedTest
  @ValueSource(strings = {"v3-flatcontainer/arp.projects/index.json",
      "v3-flatcontainer/arp.projects/1.10.21/arp.projects.1.10.21.nupkg",
      "v3-flatcontainer/arp.projects/1.10.21/arp.projects.nuspec",
      "v3/registration5-semver1/arp.projects/index.json",
      "v3/registration5-semver1/arp.projects/page.json",
      "v3/registration5-semver2/arp.projects/1.10.21.json"})
  void everyDirectResourceEntryPointPreservesOpaqueQueries(String path) throws Exception {
    RecordingProxy proxy = new RecordingProxy();
    NugetService service = new NugetService(null, proxy, null, MAPPER);
    MockHttpServletRequest request = new MockHttpServletRequest();
    String query = "variant=fips&_kkrepoNugetSource=upstream%2Bvalue";
    request.setQueryString(query);
    String prefix = path.startsWith("v3-flatcontainer/") ? "v3-flatcontainer/"
        : NugetUpstreamResources.registrationPrefix(path);
    String endpoint = path.startsWith("v3-flatcontainer/") ? FLAT : REG;
    for (boolean head : List.of(false, true)) {
      MavenResponse response = service.get(runtime(), path, BASE, request, head);
      if (response.body() != null) response.body().close();
      assertEquals(endpoint + path.substring(prefix.length()) + "?" + query, proxy.urls.getLast());
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"_kkrepoNugetSource=upstream%2Bsignature", "a=1&%5FkkrepoNugetSource=opaque&&b=2&_kkrepoNugetSource=second"})
  void sourceNamedQueriesRemainOpaqueThroughProxyAndGroupLinks(String query) throws Exception {
    RecordingProxy proxy = new RecordingProxy();
    String suffix = "arp.projects/1.10.21/arp.projects.1.10.21.nupkg";
    proxy.metadata = "{\"packageContent\":\"" + FLAT + suffix + "?" + query + "\"}";
    NugetService service = new NugetService(null, proxy, null, MAPPER);
    for (RepositoryRuntime entry : List.of(runtime(), group(2L, runtime()))) {
      URI link = URI.create(json(service.get(entry, "v3/registration5-semver1/arp.projects/1.10.21.json",
          BASE, null, false)).path("packageContent").asText());
      MockHttpServletRequest request = new MockHttpServletRequest();
      request.setQueryString(link.getRawQuery());
      service.get(entry, "v3-flatcontainer/" + suffix, BASE, request, false).body().close();
      assertEquals(FLAT + suffix + "?" + query, proxy.urls.getLast());
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"cursor=1&key=k&sig=s", "sig=s&key=k&cursor=1", "cursor=1&&k%65y=%6B&sig=s&",
      "key=k&cursor=1&key=k&sig=s", "_kkrepoNugetQuery=upstream&cursor=1&key=k&sig=s"})
  void resourceOwnedQueryPairsKeepTheirExactPositionsAndEncoding(String query) throws Exception {
    RecordingProxy proxy = new RecordingProxy();
    proxy.flat = FLAT + "?key=k";
    proxy.registration = REG + "?key=k";
    NugetService service = new NugetService(null, proxy, null, MAPPER);
    for (String suffix : List.of("arp.projects/1.10.21/arp.projects.1.10.21.nupkg", "arp.projects/page.json")) {
      boolean content = suffix.endsWith(".nupkg");
      String remote = (content ? FLAT : REG) + suffix + "?" + query;
      String field = content ? "packageContent" : "@id";
      proxy.metadata = MAPPER.writeValueAsString(Map.of(field, remote));
      for (RepositoryRuntime entry : List.of(runtime(), group(2L, runtime()))) {
        URI link = URI.create(json(service.get(entry, "v3/registration5-semver1/arp.projects/1.10.21.json",
            BASE, null, false)).path(field).asText());
        assertFalse(link.getRawQuery().contains("key=k"));
        assertFalse(link.getRawQuery().contains("%6B"));
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setQueryString(link.getRawQuery());
        String path = link.getRawPath().substring(URI.create(BASE).getRawPath().length());
        for (boolean head : List.of(false, true)) {
          MavenResponse response = service.get(entry, path, BASE, request, head);
          if (response.body() != null) response.body().close();
          assertEquals(remote, proxy.urls.getLast());
        }
      }
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"repository", "index", "endpoint", "path", "query"})
  void encryptedQueriesCannotBeReusedForAnotherRequestOrResource(String changed) throws Exception {
    RecordingProxy proxy = new RecordingProxy();
    proxy.flat = FLAT + "?key=secret";
    String path = "v3-flatcontainer/arp.projects/1.10.21/arp.projects.1.10.21.nupkg";
    proxy.metadata = "{\"packageContent\":\"" + FLAT + path.substring("v3-flatcontainer/".length())
        + "?cursor=1&key=secret&sig=s\"}";
    NugetService service = new NugetService(null, proxy, null, MAPPER);
    URI link = URI.create(json(service.get(runtime(), "v3/registration5-semver1/arp.projects/1.10.21.json",
        BASE, null, false)).path("packageContent").asText());
    RepositoryRuntime current = changed.equals("repository") ? runtime(4L)
        : changed.equals("index") ? runtime(1L, "https://changed.example/index.json") : runtime();
    if (changed.equals("endpoint")) proxy.flat = FLAT + "?key=changed";
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setQueryString(changed.equals("query") ? link.getRawQuery().replace("cursor=1", "cursor=2") : link.getRawQuery());
    String requestedPath = changed.equals("path") ? path.replace("1.10.21", "1.10.22") : path;
    proxy.urls.clear();
    assertThrows(MavenExceptions.MavenNotFoundException.class,
        () -> service.get(current, requestedPath, BASE, request, false));
    assertEquals(List.of(current.proxyRemoteUrl()), proxy.urls);
    assertEquals(0, proxy.contentRequests);
  }

  @ParameterizedTest
  @ValueSource(strings = {"opaque", "v1.not!base64", "v1.cGxhaW50ZXh0", "v1.e2Flcy1nY20tdjF9YmFk"})
  void unrecognizedQueryEnvelopeNamesStayOpaque(String value) throws Exception {
    RecordingProxy proxy = new RecordingProxy();
    MockHttpServletRequest request = new MockHttpServletRequest();
    String query = "_kkrepoNugetQuery=" + value;
    request.setQueryString(query);
    new NugetService(null, proxy, null, MAPPER).get(runtime(), "v3-flatcontainer/arp.projects/index.json", BASE, request, false).body().close();
    assertEquals(FLAT + "arp.projects/index.json?" + query, proxy.urls.getLast());
  }

  @ParameterizedTest
  @ValueSource(strings = {"configured", "flat", "registration"})
  void oldGroupProofCannotSendQueriesToAReconfiguredResource(String changed) throws Exception {
    RecordingProxy proxy = new RecordingProxy();
    String registrationPath = "v3/registration5-semver1/arp.projects/1.10.21.json";
    proxy.metadata = "{\"@id\":\"" + REG + "arp.projects/1.10.21.json?sig=secret\","
        + "\"packageContent\":\"" + FLAT + "arp.projects/1.10.21/arp.projects.1.10.21.nupkg?sig=secret\"}";
    NugetService service = new NugetService(null, proxy, null, MAPPER);
    JsonNode document = json(service.get(group(2L, runtime()), registrationPath, BASE, null, false));
    URI link = URI.create(document.path(changed.equals("registration") ? "@id" : "packageContent").asText());
    if (changed.equals("flat")) proxy.flat = "https://different.example/flat/";
    if (changed.equals("registration")) proxy.registration = "https://different.example/registration/";
    RepositoryRuntime current = changed.equals("configured") ? runtime(1L, "https://different.example/index.json") : runtime();
    proxy.urls.clear();
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setQueryString(link.getRawQuery());
    String path = link.getRawPath().substring(URI.create(BASE).getRawPath().length());
    assertThrows(MavenExceptions.MavenNotFoundException.class,
        () -> service.get(group(2L, current), path, BASE, request, false));
    assertEquals(List.of(current.proxyRemoteUrl()), proxy.urls);
    assertEquals(0, proxy.contentRequests);
  }

  @Test
  void groupRejectsUnscopedQueriesWithoutReadingHostedOrProxyMembers() throws Exception {
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
    NugetService service = new NugetService(hosted, proxy, null, MAPPER);
    for (String resourcePath : List.of(path, path.replace(".nupkg", ".nuspec"),
        "v3-flatcontainer/arp.projects/index.json", "v3/registration5-semver1/arp.projects/index.json",
        "v3/registration5-semver2/arp.projects/page.json")) {
      assertThrows(MavenExceptions.MavenNotFoundException.class,
          () -> service.get(group, resourcePath, BASE, request, true));
    }
    org.mockito.Mockito.verifyNoInteractions(hosted);
    assertTrue(proxy.repositories.isEmpty());
    // Ordinary unsigned requests still follow the normal member order.
    request.setQueryString(null);
    assertEquals(200, service.get(group, path, BASE, request, true).status());
    verify(hosted).get(hostedRepository, path, true);
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
        + NugetResourceLinkToken.issue(group.id(), runtime().id(), leafPath + "?api-version=7", NugetResourceLinkToken.identity(INDEX, REG)) + "#leaf",
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
  @ValueSource(strings = {"99", "invalid", "0", "-1", "1&_kkrepoNugetSource=4", "1&%5FkkrepoNugetSource=1",
      "%", "1&%ZZ=secret", "1&_kkrepoNugetSource"})
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
        "v3/registration5-semver1/arp.projects/index.json?sig=a%2Bb&&", NugetResourceLinkToken.identity(INDEX, REG)) + "&");
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

  @Test
  void overlappingResourceBasesAreRewrittenByLinkKind() throws Exception {
    RecordingProxy proxy = new RecordingProxy();
    proxy.registration = "https://feed.example/api/";
    proxy.flat = proxy.registration + "flat/";
    proxy.metadata = """
        {"@id":"https://feed.example/api/demo/index.json",
         "parent":"https://feed.example/api/demo/index.json",
         "registration":"https://feed.example/api/dependency/index.json",
         "packageContent":"https://feed.example/api/flat/demo/1.0.0/demo.1.0.0.nupkg"}
        """;
    JsonNode result = json(get(proxy, "v3/registration5-semver1/demo/index.json", false));
    assertEquals(BASE + "v3-flatcontainer/demo/1.0.0/demo.1.0.0.nupkg", result.path("packageContent").asText());
    assertEquals(BASE + "v3/registration5-semver1/demo/index.json", result.path("@id").asText());
    assertEquals(BASE + "v3/registration5-semver1/demo/index.json", result.path("parent").asText());
    assertEquals(BASE + "v3/registration5-semver1/dependency/index.json", result.path("registration").asText());
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
    URI local = URI.create(result.path("@id").asText());
    assertTrue(local.toString().startsWith(BASE + "v3/registration5-semver1/arp.projects/index.json?api-version=7"));
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setQueryString(local.getRawQuery());
    new NugetService(null, proxy, null, MAPPER).get(runtime(), "v3/registration5-semver1/arp.projects/index.json",
        BASE, request, false).body().close();
    assertEquals(REG + "arp.projects/index.json?" + decoded + "=secret&api-version=7", proxy.urls.getLast());
  }

  @ParameterizedTest
  @ValueSource(strings = {"{", "[]", "null"})
  void registrationValidationRejectsBadJsonBeforeCachePublication(String malformed) {
    RecordingProxy proxy = new RecordingProxy();
    proxy.metadata = malformed;
    assertThrows(MavenExceptions.BadUpstreamException.class,
        () -> get(proxy, "v3/registration5-semver1/demo/index.json", false));
    assertTrue(proxy.closed);
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void registrationValidationBoundsBothStoredAndDecompressedBodies(boolean gzip) {
    RecordingProxy proxy = new RecordingProxy();
    proxy.gzipMetadata = gzip;
    proxy.metadata = "{\"description\":\"" + "x".repeat(32 * 1024 * 1024) + "\"}";
    assertThrows(MavenExceptions.BadUpstreamException.class,
        () -> get(proxy, "v3/registration5-semver1/demo/index.json", false));
    assertTrue(proxy.closed);
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void groupRegistrationMergesPagesInMemberOrderAndPreservesSignedPackageMetadata(boolean withQuery) throws Exception {
    RecordingProxy proxy = new RecordingProxy();
    String index = REG + "demo/index.json";
    String page = REG + "demo/page.json?api=one";
    proxy.responses.put("1 " + index, "{\"items\":[{\"@id\":\"" + page + "\"}]}");
    proxy.responses.put("1 " + page, "{\"items\":[" + registrationLeaf("1.0.0", "a") + "," + registrationLeaf("1.10.0", "a") + "]}");
    proxy.responses.put("5 " + index, "{\"items\":[{\"items\":[" + registrationLeaf("1.0.0", "b") + "," + registrationLeaf("1.9.0", "b") + "]}]}");
    if (!withQuery) proxy.responses.replaceAll((key, value) -> value.replace("?sig=a", "").replace("?sig=b", ""));
    RepositoryRuntime group = group(2L, group(3L, runtime()), runtime(5L));
    NugetService service = new NugetService(null, proxy, null, MAPPER);
    JsonNode document = json(service.get(group, "v3/registration5-semver2/demo/index.json", BASE, null, false));
    JsonNode merged = document.path("items").get(0);
    assertEquals(1, document.path("count").asInt());
    assertEquals(3, merged.path("count").asInt());
    assertEquals("1.0.0", merged.path("lower").asText());
    assertEquals("1.10.0", merged.path("upper").asText());
    List<String> versions = new ArrayList<>();
    for (JsonNode leaf : merged.path("items")) {
      String version = leaf.path("catalogEntry").path("version").asText();
      versions.add(version);
      String source = version.equals("1.9.0") ? "b" : "a";
      assertEquals(source, leaf.path("catalogEntry").path("description").asText());
      assertEquals("dep-" + source, leaf.path("catalogEntry").path("dependencyGroups").get(0)
          .path("dependencies").get(0).path("id").asText());
      URI link = URI.create(leaf.path("packageContent").asText());
      assertTrue(link.toString().startsWith(BASE));
      MockHttpServletRequest request = new MockHttpServletRequest();
      request.setQueryString(link.getRawQuery());
      proxy.repositories.clear();
      String path = link.getRawPath().substring(URI.create(BASE).getRawPath().length());
      service.get(group, path, BASE, request, true);
      long member = source.equals("a") ? 1L : 5L;
      assertEquals(List.of(member, member), proxy.repositories);
      assertEquals(FLAT + "demo/" + version + "/demo." + version + ".nupkg" + (withQuery ? "?sig=" + source : ""), proxy.urls.getLast());
    }
    assertEquals(List.of("1.0.0", "1.9.0", "1.10.0"), versions);
    assertTrue(proxy.urls.contains(page));
  }

  @Test
  void groupRegistrationDropsIncompleteMembersAndEnforcesSharedMetadataLimits() throws Exception {
    RecordingProxy proxy = new RecordingProxy();
    String index = REG + "demo/index.json", page = REG + "demo/page.json";
    proxy.responses.put("1 " + index, "{\"items\":[{\"items\":[" + registrationLeaf("1.0.0", "a")
        + "]},{\"@id\":\"" + page + "\"}]}");
    proxy.responses.put("1 " + page, "{}");
    proxy.responses.put("5 " + index, "{\"items\":[{\"items\":[" + registrationLeaf("1.9.0", "b") + "]}]}");
    NugetService service = new NugetService(null, proxy, null, MAPPER);
    JsonNode result = json(service.get(group(2L, runtime(), runtime(5L)),
        "v3/registration5-semver2/demo/index.json", BASE, null, false));
    assertEquals(1, result.path("items").get(0).path("items").size());
    assertEquals("1.9.0", result.path("items").get(0).path("items").get(0).path("catalogEntry").path("version").asText());
    for (var budget : List.of(new NugetUpstreamResources.RegistrationBudget(1, 4096),
        new NugetUpstreamResources.RegistrationBudget(10, 1))) {
      proxy.urls.clear();
      assertThrows(NugetUpstreamResources.RegistrationLimitException.class, () -> NugetUpstreamResources.registrationLeaves(
          proxy, MAPPER, runtime(), "v3/registration5-semver2/demo/index.json", BASE, 2L, budget));
      assertEquals(List.of(INDEX, index), proxy.urls);
    }
  }

  @Test
  void groupRegistrationHandlesEmptyMissingAndUnavailableMembers() throws Exception {
    RecordingProxy proxy = new RecordingProxy();
    NugetService service = new NugetService(null, proxy, null, MAPPER);
    String path = "v3/registration5-semver2/demo/index.json";
    JsonNode empty = json(service.get(group(2L), path, BASE, null, false));
    assertEquals(0, empty.path("count").asInt());
    assertTrue(empty.path("items").isEmpty());
    proxy.missingRepository = 1L;
    assertTrue(json(service.get(group(2L, runtime()), path, BASE, null, false)).path("items").isEmpty());
    proxy.missingRepository = null;
    proxy.resourceFailure = new MavenExceptions.BadUpstreamException("temporarily unavailable");
    assertSame(proxy.resourceFailure, assertThrows(MavenExceptions.BadUpstreamException.class,
        () -> service.get(group(2L, runtime()), path, BASE, null, false)));
  }

  @Test
  void groupRegistrationCannotReturnPartialResultsAfterExhaustingThePageBudget() {
    RecordingProxy proxy = new RecordingProxy();
    String index = REG + "demo/index.json";
    proxy.responses.put("1 " + index, "{\"items\":[{\"items\":[" + registrationLeaf("1.0.0", "a") + "]}]}");
    List<String> pages = new ArrayList<>();
    for (int i = 0; i < 512; i++) {
      String url = REG + "demo/page" + i + ".json";
      pages.add("{\"@id\":\"" + url + "\"}");
      proxy.responses.put("5 " + url, "{\"items\":[]}");
    }
    proxy.responses.put("5 " + index, "{\"items\":[" + String.join(",", pages) + "]}");
    NugetService service = new NugetService(null, proxy, null, MAPPER);
    assertThrows(NugetUpstreamResources.RegistrationLimitException.class,
        () -> service.get(group(2L, runtime(), runtime(5L)), "v3/registration5-semver2/demo/index.json", BASE, null, false));
  }

  @ParameterizedTest
  @ValueSource(strings = {"{}", "{\"items\":{}}", "{\"items\":[{}]}",
      "{\"items\":[{\"items\":[{}]}]}", "{\"items\":[{\"@id\":\"https://upstream.example/page\",\"items\":{}}]}"})
  void groupRegistrationRejectsMalformedStructuresBeforePublishingThem(String document) {
    RecordingProxy proxy = new RecordingProxy();
    proxy.metadata = document;
    NugetService service = new NugetService(null, proxy, null, MAPPER);
    assertThrows(MavenExceptions.BadUpstreamException.class,
        () -> service.get(group(2L, runtime()), "v3/registration5-semver2/demo/index.json", BASE, null, false));
    assertTrue(proxy.closed);
  }

  private static String registrationLeaf(String version, String source) {
    return "{\"@id\":\"" + REG + "demo/" + version + ".json\",\"catalogEntry\":{\"id\":\"demo\",\"version\":\""
        + version + "\",\"description\":\"" + source
        + "\",\"dependencyGroups\":[{\"dependencies\":[{\"id\":\"dep-" + source + "\",\"range\":\"[1.0.0,)\"}]}]},"
        + "\"packageContent\":\"" + FLAT + "demo/" + version + "/demo." + version + ".nupkg?sig=" + source + "\"}";
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
  void legacyVersionIndexesRemainReachableAfterDiscoveryWhenTheResourceIsUnavailable() {
    RecordingProxy proxy = new RecordingProxy();
    String path = "v3-flatcontainer/arp.projects/index.json";
    proxy.resourceFailure = new MavenExceptions.BadUpstreamException("Resource temporarily unavailable");
    proxy.legacyResponse = MavenResponse.noBody(200);
    for (boolean head : List.of(false, true)) {
      assertSame(proxy.legacyResponse, get(proxy, path, head));
      assertEquals(path, proxy.legacyPath);
      assertEquals(head, proxy.lastHead);
    }
    proxy.legacyResponse = null;
    assertSame(proxy.resourceFailure, assertThrows(MavenExceptions.BadUpstreamException.class, () -> get(proxy, path, false)));
    proxy.legacyPath = null;
    proxy.legacyResponse = MavenResponse.noBody(200);
    proxy.resourceFailure = new MavenExceptions.MavenNotFoundException("Authoritative 404/410");
    assertSame(proxy.resourceFailure, assertThrows(MavenExceptions.MavenNotFoundException.class, () -> get(proxy, path, false)));
    assertNull(proxy.legacyPath);
  }

  @Test
  void queryBearingRequestsNeverUseUnverifiedLegacyContentDuringOutages() {
    String path = "v3-flatcontainer/arp.projects/index.json";
    for (boolean discoveryFails : List.of(false, true)) {
      RecordingProxy proxy = new RecordingProxy();
      var failure = new MavenExceptions.BadUpstreamException("Resource temporarily unavailable");
      if (discoveryFails) proxy.discoveryFailure = failure;
      else proxy.resourceFailure = failure;
      proxy.legacyResponse = MavenResponse.noBody(200);
      assertSame(failure, assertThrows(MavenExceptions.BadUpstreamException.class,
          () -> get(proxy, path + "?variant=fips", false)));
      String proof = NugetResourceLinkToken.issue(2L, 1L, path + "?variant=fips", NugetResourceLinkToken.identity(INDEX, FLAT));
      MockHttpServletRequest request = new MockHttpServletRequest();
      request.setQueryString("variant=fips&_kkrepoNugetSource=" + proof);
      assertSame(failure, assertThrows(MavenExceptions.BadUpstreamException.class,
          () -> new NugetService(null, proxy, null, MAPPER).get(group(2L, runtime()), path, BASE, request, false)));
      assertNull(proxy.legacyPath);
    }
  }

  @Test
  void publicResourceIdentityRequiresTheDeploymentSecretAndWorksAcrossReplicas() {
    try (var secrets = org.mockito.Mockito.mockStatic(com.github.klboke.kkrepo.core.security.EncryptionSecrets.class)) {
      secrets.when(com.github.klboke.kkrepo.core.security.EncryptionSecrets::credentialSecret).thenReturn("replica-shared-secret");
      String identity = NugetResourceLinkToken.identity(INDEX, FLAT + "?password=short");
      String token = NugetResourceLinkToken.issue(2L, 1L, "path?variant=fips", identity);
      assertEquals(identity, NugetResourceLinkToken.identity(INDEX, FLAT + "?password=short"));
      assertEquals(1L, NugetResourceLinkToken.verify(2L, "path?variant=fips", token));
      secrets.when(com.github.klboke.kkrepo.core.security.EncryptionSecrets::credentialSecret).thenReturn("another-deployment-secret");
      assertNotEquals(identity, NugetResourceLinkToken.identity(INDEX, FLAT + "?password=short"));
      assertThrows(MavenExceptions.MavenNotFoundException.class,
          () -> NugetResourceLinkToken.verify(2L, "path?variant=fips", token));
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"page%20one.json", "page%2Fone.json", "page%25one.json", "page+one.json",
      "page%3Fone.json", "page%F0%9F%93%A6.json", "page//one.json", "page///one.json",
      "page/", "/page.json", "//page.json", "//page:one.json", "page//"})
  void rewrittenOpaquePathsRoundTripWithoutDecodingOrDoubleEncoding(String file) throws Exception {
    RecordingProxy proxy = new RecordingProxy();
    NugetService service = new NugetService(null, proxy, null, MAPPER);
    String suffix = file.startsWith("/") ? file : "arp.projects/" + file;
    proxy.metadata = "{\"@id\":\"" + REG + suffix + "?api-version=7\"}";
    for (RepositoryRuntime entry : List.of(runtime(), group(2L, runtime()))) {
      URI link = URI.create(json(service.get(entry, "v3/registration5-semver1/arp.projects/1.10.21.json",
          BASE, null, false)).path("@id").asText());
      String raw = link.getRawPath().substring(URI.create(BASE).getRawPath().length());
      assertEquals("v3/registration5-semver1/" + suffix, raw);
      MockHttpServletRequest request = new MockHttpServletRequest();
      request.setQueryString(link.getRawQuery());
      service.get(entry, raw, BASE, request, false).body().close();
      assertEquals(REG + suffix + "?api-version=7", proxy.urls.getLast());
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"%2e%2e/secret", "folder%2F..%2Fsecret", "%GG", "%00", "%ff"})
  void malformedEscapesAndEncodedTraversalNeverReachResourceFetch(String suffix) {
    RecordingProxy proxy = new RecordingProxy();
    assertThrows(MavenExceptions.MavenNotFoundException.class,
        () -> get(proxy, "v3/registration5-semver1/" + suffix, false));
    assertEquals(List.of(INDEX), proxy.urls);
  }

  @Test
  void definitiveDiscoveryMissDoesNotFallBackToLegacyPackages() {
    RecordingProxy proxy = new RecordingProxy();
    proxy.discoveryFailure = new MavenExceptions.MavenNotFoundException("missing index");
    proxy.legacyResponse = MavenResponse.noBody(200);
    assertSame(proxy.discoveryFailure, assertThrows(MavenExceptions.MavenNotFoundException.class,
        () -> get(proxy, "v3-flatcontainer/arp.projects/1.10.21/arp.projects.1.10.21.nupkg", true)));
    assertNull(proxy.legacyPath);
  }

  @Test
  void routingProofRejectsChangesToMemberGroupPathOrSignedQuery() throws Exception {
    RecordingProxy proxy = new RecordingProxy();
    NugetService service = new NugetService(null, proxy, null, MAPPER);
    String path = "v3-flatcontainer/arp.projects/1.10.21/arp.projects.1.10.21.nupkg";
    String token = NugetResourceLinkToken.issue(2L, 1L, path + "?sig=secret", NugetResourceLinkToken.identity(INDEX, FLAT));
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
      "https://devops.example/custom/%72egistrations2/", "https://devops.example/custom/unused/../registrations2/",
      "https://devops.example./custom/registrations2/"})
  void equivalentResourceUrisAreRewrittenAndCredentialsRemainHidden(String endpoint) throws Exception {
    RecordingProxy proxy = new RecordingProxy();
    proxy.registration = endpoint + "?signature=secret";
    proxy.metadata = "{\"@id\":\"" + REG + "arp.projects/index.json?sig%6Eature=secret&page=2\"}";
    JsonNode result = json(get(proxy, "v3/registration5-semver1/arp.projects/index.json", false));
    URI local = URI.create(result.path("@id").asText());
    assertTrue(local.toString().startsWith(BASE + "v3/registration5-semver1/arp.projects/index.json?page=2"));
    assertFalse(result.toString().contains("secret"));
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setQueryString(local.getRawQuery());
    new NugetService(null, proxy, null, MAPPER).get(runtime(), "v3/registration5-semver1/arp.projects/index.json",
        BASE, request, false).body().close();
    assertEquals(endpoint + "arp.projects/index.json?sig%6Eature=secret&page=2", proxy.urls.getLast());
  }

  @Test
  void absoluteDnsLinksStayLocalAndKeepResourceCredentialsServerSide() throws Exception {
    RecordingProxy proxy = new RecordingProxy();
    proxy.registration = REG + "?signature=registration-secret";
    proxy.flat = FLAT + "?signature=package-secret";
    proxy.metadata = "{\"@id\":\"" + REG.replace("devops.example", "DEVOPS.EXAMPLE.")
        + "arp.projects/page.json?signature=registration-secret&page=2\",\"packageContent\":\""
        + FLAT.replace("content.example", "CONTENT.EXAMPLE.")
        + "arp.projects/1.10.21/arp.projects.1.10.21.nupkg?signature=package-secret\"}";
    for (RepositoryRuntime entry : List.of(runtime(), group(2L, runtime()))) {
      NugetService service = new NugetService(null, proxy, null, MAPPER);
      JsonNode document = json(service.get(entry, "v3/registration5-semver1/arp.projects/1.10.21.json",
          BASE, null, false));
      assertFalse(document.toString().contains("secret"));
      for (String field : List.of("@id", "packageContent")) {
        assertTrue(document.path(field).asText().startsWith(BASE));
        URI link = URI.create(document.path(field).asText());
        String path = link.getRawPath().substring(URI.create(BASE).getRawPath().length());
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setQueryString(link.getRawQuery());
        service.get(entry, path, BASE, request, false).body().close();
        assertEquals(field.equals("@id")
            ? REG + "arp.projects/page.json?signature=registration-secret&page=2"
            : FLAT + "arp.projects/1.10.21/arp.projects.1.10.21.nupkg?signature=package-secret", proxy.urls.getLast());
      }
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"page//./one.json", "page/unused/..//one.json", "page//unused/../one.json", "page//%2E/one.json"})
  void removingDotSegmentsRetainsAdjacentEmptySegments(String file) throws Exception {
    RecordingProxy proxy = new RecordingProxy();
    proxy.metadata = "{\"@id\":\"" + REG + "arp.projects/" + file + "\"}";
    JsonNode result = json(get(proxy, "v3/registration5-semver1/arp.projects/1.10.21.json", false));
    assertEquals(BASE + "v3/registration5-semver1/arp.projects/page//one.json", result.path("@id").asText());
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
    return runtime(id, INDEX);
  }

  private static RepositoryRuntime runtime(long id, String configuredIndex) {
    return new RepositoryRuntime(id, "nuget-" + id, RepositoryFormat.NUGET, RepositoryType.PROXY,
        "nuget-proxy", true, 1L, null, null, null, true, configuredIndex, 1440, 5, true, null, List.of());
  }

  private static JsonNode json(MavenResponse response) throws IOException {
    try (var body = response.body()) { return MAPPER.readTree(body); }
  }

  private static final class RecordingProxy extends RawProxyService {
    private final List<String> urls = new ArrayList<>();
    private final List<String> paths = new ArrayList<>();
    private final List<Long> repositories = new ArrayList<>();
    private Long missingRepository;
    private RuntimeException discoveryFailure;
    private RuntimeException resourceFailure;
    private MavenResponse legacyResponse;
    private String legacyPath;
    private String flat = FLAT;
    private String registration = REG;
    private String registrationType = "RegistrationsBaseUrl/3.4.0";
    private String index;
    private String metadata = "{\"versions\":[\"1.10.21\"]}";
    private final Map<String, String> responses = new HashMap<>();
    private boolean closed;
    private boolean lastHead;
    private boolean gzipMetadata;
    private boolean cacheRegistration;
    private final Map<String, String> registrationCache = new HashMap<>();
    private int contentRequests;
    RecordingProxy() { super(null, null, null, null, null, null, null, null); }

    @Override
    public MavenResponse getMetadataFromUrlHidden(
        RepositoryRuntime runtime, String path, String url, boolean head,
        String validationId, java.util.function.UnaryOperator<java.io.InputStream> validator) {
      MavenResponse response = getMetadataFromUrlHidden(runtime, path, url, false);
      return MavenResponse.ok(validator.apply(response.body()), response.contentLength(), "application/json", null, null);
    }

    @Override
    public MavenResponse getMetadataFromUrlHidden(RepositoryRuntime runtime, String path, String url, boolean head) {
      if (discoveryFailure != null) throw discoveryFailure;
      if (!path.startsWith("_nuget/index/") && resourceFailure != null) throw resourceFailure;
      repositories.add(runtime.id());
      if (missingRepository != null && runtime.id() == missingRepository) throw new MavenExceptions.MavenNotFoundException(path);
      String data = path.startsWith("_nuget/index/") ? index == null
          ? "{\"resources\":[{\"@type\":\"PackageBaseAddress/3.0.0\",\"@id\":\"" + flat
              + "\"},{\"@type\":\"" + registrationType + "\",\"@id\":\"" + registration + "\"}]}" : index : metadata;
      data = responses.getOrDefault(runtime.id() + " " + url, data);
      if (cacheRegistration && path.startsWith("_nuget/registration/")) {
        String current = data;
        data = registrationCache.computeIfAbsent(path, ignored -> current);
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
        RepositoryRuntime runtime, String path, String url, boolean head) {
      repositories.add(runtime.id());
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
