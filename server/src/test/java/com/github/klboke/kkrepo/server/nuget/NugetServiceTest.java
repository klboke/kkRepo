package com.github.klboke.kkrepo.server.nuget;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.core.RepositoryType;
import com.github.klboke.kkrepo.persistence.jdbc.api.AssetDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetRecord;
import com.github.klboke.kkrepo.server.maven.MavenExceptions;
import com.github.klboke.kkrepo.server.maven.MavenResponse;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntime;
import com.github.klboke.kkrepo.server.raw.RawHostedService;
import com.github.klboke.kkrepo.server.raw.RawProxyService;
import com.github.klboke.kkrepo.server.support.dao.AssetDaoAdapter;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class NugetServiceTest {
  private static final ObjectMapper MAPPER = new ObjectMapper();

  @Test
  void proxyRemoteUrlUsesNugetV3ServiceRoot() {
    RepositoryRuntime runtime = proxy("https://api.nuget.org/v3/index.json");

    String remoteUrl = NugetService.remoteUrlForPath(runtime,
        "v3-flatcontainer/newtonsoft.json/index.json");

    assertEquals("https://api.nuget.org/v3-flatcontainer/newtonsoft.json/index.json", remoteUrl);
  }

  @Test
  void proxyRemoteUrlKeepsRepositoryBaseRoot() {
    RepositoryRuntime runtime = proxy("http://localhost:28090/repository/nuget.org-proxy/");

    String remoteUrl = NugetService.remoteUrlForPath(runtime,
        "v3-flatcontainer/newtonsoft.json/index.json");

    assertEquals("http://localhost:28090/repository/nuget.org-proxy/v3-flatcontainer/newtonsoft.json/index.json",
        remoteUrl);
  }

  @Test
  void proxySearchDiscoversEndpointAndPreservesVisualStudioQueryParameters() throws Exception {
    RepositoryRuntime runtime = proxy("https://api.nuget.org/v3/index.json");
    FakeRawProxyService proxy = new FakeRawProxyService("{\"totalHits\":1,\"data\":[{\"id\":\"Serilog\"}]}");
    NugetService service = new NugetService(null, proxy, new FakeAssetDao(List.of()), MAPPER);
    MockHttpServletRequest request = new MockHttpServletRequest();
    String query = "q=&skip=0&take=26&prerelease=false&supportedFramework=net10.0&semVerLevel=2.0.0";
    request.setQueryString(query);

    JsonNode result = MAPPER.readTree(body(service.get(runtime, "query", "http://localhost/repository/nuget",
        request, false)));

    assertEquals("Serilog", result.path("data").get(0).path("id").asText());
    assertEquals("https://search.example/search?" + query, proxy.lastRemoteUrl);
  }

  @Test
  void autocompleteReturnsPackageIdsFromFlatContainerAssets() throws Exception {
    NugetService service = new NugetService(null, null, new FakeAssetDao(List.of(
        asset("v3-flatcontainer/newtonsoft.json/13.0.3/newtonsoft.json.13.0.3.nupkg"),
        asset("v3-flatcontainer/nunit/4.2.2/nunit.4.2.2.nupkg"))), MAPPER);
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/repository/nuget-hosted/autocomplete");
    request.setParameter("q", "newton");

    JsonNode body = MAPPER.readTree(body(service.get(hosted(), "autocomplete",
        "http://localhost:28090/repository/nuget-hosted", request, false)));

    assertEquals(1, body.get("totalHits").asInt());
    assertEquals("newtonsoft.json", body.get("data").get(0).asText());
  }

  @Test
  void queryReturnsNugetSearchServiceShape() throws Exception {
    NugetService service = new NugetService(null, null, new FakeAssetDao(List.of(
        asset("v3-flatcontainer/newtonsoft.json/12.0.1/newtonsoft.json.12.0.1.nupkg"),
        asset("v3-flatcontainer/newtonsoft.json/13.0.3/newtonsoft.json.13.0.3.nupkg"))), MAPPER);
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/repository/nuget-hosted/query");
    request.setParameter("q", "json");

    JsonNode body = MAPPER.readTree(body(service.get(hosted(), "query",
        "http://localhost:28090/repository/nuget-hosted", request, false)));

    assertEquals(1, body.get("totalHits").asInt());
    assertEquals("newtonsoft.json", body.get("data").get(0).get("id").asText());
    assertEquals("13.0.3", body.get("data").get(0).get("version").asText());
    assertTrue(body.get("data").get(0).get("registration").asText()
        .startsWith("http://localhost:28090/repository/nuget-hosted/v3/registration5-semver1/"));
  }

  @Test
  void versionAndRegistrationIndexesUseStoredFlatContainerVersions() throws Exception {
    NugetService service = new NugetService(null, null, new FakeAssetDao(List.of(
        asset("v3-flatcontainer/newtonsoft.json/12.0.1/newtonsoft.json.12.0.1.nupkg"),
        asset("v3-flatcontainer/newtonsoft.json/13.0.3/newtonsoft.json.13.0.3.nupkg"))), MAPPER);
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/repository/nuget-hosted/index.json");

    JsonNode versions = MAPPER.readTree(body(service.get(hosted(),
        "v3-flatcontainer/Newtonsoft.Json/index.json",
        "http://localhost:28090/repository/nuget-hosted", request, false)));

    assertEquals(List.of("12.0.1", "13.0.3"),
        MAPPER.convertValue(versions.get("versions"),
            MAPPER.getTypeFactory().constructCollectionType(List.class, String.class)));

    JsonNode registration = MAPPER.readTree(body(service.get(hosted(),
        "v3/registration5-semver1/Newtonsoft.Json/index.json",
        "http://localhost:28090/repository/nuget-hosted", request, false)));

    assertEquals("http://localhost:28090/repository/nuget-hosted/v3/registration5-semver1/newtonsoft.json/index.json",
        registration.get("@id").asText());
    assertEquals(1, registration.get("count").asInt());
    JsonNode page = registration.get("items").get(0);
    assertEquals(2, page.get("count").asInt());
    JsonNode item = page.get("items").get(1);
    assertEquals("13.0.3", item.get("catalogEntry").get("version").asText());
    assertEquals("Newtonsoft.Json", item.get("catalogEntry").get("id").asText());
    assertEquals(
        "http://localhost:28090/repository/nuget-hosted/v3-flatcontainer/newtonsoft.json/13.0.3/newtonsoft.json.13.0.3.nupkg",
        item.get("packageContent").asText());
  }

  @Test
  void generatedRegistrationsExcludeSemVer2OnlyVersionsFromLegacyHives() throws Exception {
    List<String> versions = List.of("1.0.0", "1.0.1-beta", "1.0.2-beta.1", "1.0.3+build", "1.0.4.0");
    NugetService service = new NugetService(null, null, new FakeAssetDao(versions.stream()
        .map(v -> asset("v3-flatcontainer/demo/" + v + "/demo." + v + ".nupkg")).toList()), MAPPER);
    for (RepositoryRuntime runtime : List.of(hosted(), group(hosted()))) {
      for (String flavor : List.of("semver1", "semver2")) {
        JsonNode registration = MAPPER.readTree(body(service.get(runtime,
            "v3/registration5-" + flavor + "/demo/index.json", "https://kkrepo.example/repository/nuget/", null, false)));
        List<String> actual = new ArrayList<>();
        registration.path("items").forEach(page -> page.path("items").forEach(item ->
            actual.add(item.path("catalogEntry").path("version").asText())));
        assertEquals(flavor.equals("semver1") ? List.of("1.0.0", "1.0.1-beta", "1.0.4.0") : versions, actual);
      }
    }
  }

  @Test
  void groupQueryMergesProxySearchAndUsesGroupBaseUrl() throws Exception {
    FakeRawProxyService proxy = new FakeRawProxyService("""
        {"totalHits":1,"data":[{"id":"Serilog","version":"3.1.1","versions":[{"version":"3.1.1"}]}]}
        """);
    NugetService service = new NugetService(null, proxy, new FakeAssetDao(List.of()), MAPPER);
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/repository/nuget-group/query");
    request.setParameter("q", "seri");

    JsonNode body = MAPPER.readTree(body(service.get(group(proxy("https://api.nuget.org/v3/index.json")),
        "query", "http://localhost:28090/repository/nuget-group", request, false)));

    assertEquals(1, body.get("totalHits").asInt());
    assertEquals("Serilog", body.get("data").get(0).get("id").asText());
    assertTrue(body.get("data").get(0).get("registration").asText()
        .startsWith("http://localhost:28090/repository/nuget-group/v3/registration5-semver1/"));
    assertEquals("https://search.example/search?q=seri", proxy.lastRemoteUrl);
  }

  @Test
  void groupAutocompleteMergesProxyIds() throws Exception {
    FakeRawProxyService proxy = new FakeRawProxyService("""
        {"totalHits":1,"data":["Serilog"]}
        """);
    NugetService service = new NugetService(null, proxy, new FakeAssetDao(List.of()), MAPPER);
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/repository/nuget-group/autocomplete");
    request.setParameter("q", "seri");

    JsonNode body = MAPPER.readTree(body(service.get(group(proxy("https://api.nuget.org/v3/index.json")),
        "autocomplete", "http://localhost:28090/repository/nuget-group", request, false)));

    assertEquals(1, body.get("totalHits").asInt());
    assertEquals("Serilog", body.get("data").get(0).asText());
    assertEquals("https://search.example/suggest?q=seri", proxy.lastRemoteUrl);
  }

  @Test
  void groupSearchPropagatesBadUpstreamWhenNoMemberReturnsResults() {
    FakeRawProxyService proxy = new FakeRawProxyService(
        new MavenExceptions.BadUpstreamException("upstream timed out"));
    NugetService service = new NugetService(null, proxy, new FakeAssetDao(List.of()), MAPPER);
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/repository/nuget-group/query");
    request.setParameter("q", "seri");

    MavenExceptions.BadUpstreamException error = assertThrows(MavenExceptions.BadUpstreamException.class,
        () -> service.get(group(proxy("https://api.nuget.org/v3/index.json")),
            "query", "http://localhost:28090/repository/nuget-group", request, false));

    assertEquals("upstream timed out", error.getMessage());
    assertEquals("https://search.example/search?q=seri", proxy.lastRemoteUrl);
  }

  @Test
  void putPackageStoresNupkgAsZipContentType() throws Exception {
    FakeRawHostedService hosted = new FakeRawHostedService();
    NugetService service = new NugetService(hosted, null, new FakeAssetDao(List.of()), MAPPER);

    MavenResponse response = service.putPackage(hosted(), "api/v2/package",
        new ByteArrayInputStream(nupkg("NuGet.ContentType", "1.2.3")),
        "application/octet-stream", "admin", "127.0.0.1");

    assertEquals(201, response.status());
    assertEquals("v3-flatcontainer/nuget.contenttype/1.2.3/nuget.contenttype.1.2.3.nupkg",
        hosted.packagePath);
    assertEquals("application/zip", hosted.packageContentType);
    assertEquals("v3-flatcontainer/nuget.contenttype/1.2.3/nuget.contenttype.nuspec",
        hosted.nuspecPath);
    assertEquals("application/xml", hosted.nuspecContentType);
  }

  @Test
  void putPackageAcceptsDotnetPushTrailingSlashEndpoint() throws Exception {
    FakeRawHostedService hosted = new FakeRawHostedService();
    NugetService service = new NugetService(hosted, null, new FakeAssetDao(List.of()), MAPPER);

    MavenResponse response = service.putPackage(hosted(), "api/v2/package/",
        new ByteArrayInputStream(nupkg("NuGet.TrailingSlash", "1.2.3")),
        "application/octet-stream", "admin", "127.0.0.1");

    assertEquals(201, response.status());
    assertEquals("v3-flatcontainer/nuget.trailingslash/1.2.3/nuget.trailingslash.1.2.3.nupkg",
        hosted.packagePath);
  }

  @Test
  void deletePackageReturnsNoContentWhenAnyStoredAssetWasDeleted() {
    FakeRawHostedService hosted = new FakeRawHostedService();
    hosted.deleteStatuses = new int[] {204, 404};
    NugetService service = new NugetService(hosted, null, new FakeAssetDao(List.of()), MAPPER);

    MavenResponse response = service.deletePackage(hosted(), "api/v2/package/Demo.Package/1.2.3");

    assertEquals(204, response.status());
    assertEquals(List.of(
        "v3-flatcontainer/demo.package/1.2.3/demo.package.1.2.3.nupkg",
        "v3-flatcontainer/demo.package/1.2.3/demo.package.nuspec"), hosted.deletedPaths);
  }

  @Test
  void deletePackageReturnsNotFoundWhenNoStoredAssetsWereDeleted() {
    FakeRawHostedService hosted = new FakeRawHostedService();
    hosted.deleteStatuses = new int[] {404, 404};
    NugetService service = new NugetService(hosted, null, new FakeAssetDao(List.of()), MAPPER);

    MavenResponse response = service.deletePackage(hosted(), "api/v2/package/Demo.Package/1.2.3");

    assertEquals(404, response.status());
  }

  private static String body(MavenResponse response) throws Exception {
    try (InputStream body = response.body()) {
      return new String(body.readAllBytes(), StandardCharsets.UTF_8);
    }
  }

  private static byte[] nupkg(String id, String version) throws Exception {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    try (ZipOutputStream zip = new ZipOutputStream(out)) {
      zip.putNextEntry(new ZipEntry(id + ".nuspec"));
      zip.write("""
          <?xml version="1.0"?>
          <package>
            <metadata>
              <id>%s</id>
              <version>%s</version>
            </metadata>
          </package>
          """.formatted(id, version).getBytes(StandardCharsets.UTF_8));
      zip.closeEntry();
    }
    return out.toByteArray();
  }

  private static RepositoryRuntime group(RepositoryRuntime... members) {
    return new RepositoryRuntime(
        2L,
        "nuget-group",
        RepositoryFormat.NUGET,
        RepositoryType.GROUP,
        "nuget-group",
        true,
        1L,
        null,
        null,
        null,
        true,
        null,
        1440,
        1440,
        true,
        null,
        List.of(members));
  }

  private static RepositoryRuntime hosted() {
    return new RepositoryRuntime(
        1L,
        "nuget-hosted",
        RepositoryFormat.NUGET,
        RepositoryType.HOSTED,
        "nuget-hosted",
        true,
        1L,
        null,
        null,
        null,
        true,
        null,
        1440,
        1440,
        true, null, List.of());
  }

  private static RepositoryRuntime proxy(String remoteUrl) {
    return new RepositoryRuntime(
        1L,
        "nuget-proxy",
        RepositoryFormat.NUGET,
        RepositoryType.PROXY,
        "nuget-proxy",
        true,
        1L,
        null,
        null,
        null,
        true,
        remoteUrl,
        1440,
        1440,
        true, null, List.of());
  }

  private static AssetRecord asset(String path) {
    return new AssetRecord(
        1L,
        1L,
        null,
        1L,
        RepositoryFormat.NUGET,
        path,
        new byte[] {1},
        path,
        "nuget",
        "application/octet-stream",
        1L,
        null,
        null,
        Map.of());
  }

  private static final class FakeAssetDao extends AssetDaoAdapter {
    private final List<AssetRecord> assets;

    FakeAssetDao(List<AssetRecord> assets) {
      super(null, null);
      this.assets = assets;
    }

    @Override
    public List<AssetRecord> listAssetsByPrefix(long repositoryId, String pathPrefix) {
      return assets.stream()
          .filter(asset -> asset.repositoryId() == repositoryId)
          .filter(asset -> asset.path().startsWith(pathPrefix))
          .toList();
    }
  }

  private static final class FakeRawHostedService extends RawHostedService {
    private String packagePath;
    private String packageContentType;
    private String nuspecPath;
    private String nuspecContentType;
    private int[] deleteStatuses = new int[] {204};
    private int deleteIndex;
    private final List<String> deletedPaths = new ArrayList<>();

    FakeRawHostedService() {
      super(null, null, null, null, null);
    }

    @Override
    public MavenResponse put(RepositoryRuntime runtime, String rawPath, InputStream body,
        String contentType, String createdBy, String createdByIp) {
      packagePath = rawPath;
      packageContentType = contentType;
      return MavenResponse.created();
    }

    @Override
    public MavenResponse putGenerated(RepositoryRuntime runtime, String rawPath, InputStream body,
        String contentType, String createdBy, String createdByIp) {
      nuspecPath = rawPath;
      nuspecContentType = contentType;
      return MavenResponse.created();
    }

    @Override
    public MavenResponse delete(RepositoryRuntime runtime, String rawPath) {
      deletedPaths.add(rawPath);
      int index = Math.min(deleteIndex, deleteStatuses.length - 1);
      deleteIndex++;
      return MavenResponse.noBody(deleteStatuses[index]);
    }
  }

  private static final class FakeRawProxyService extends RawProxyService {
    private final String json;
    private final RuntimeException failure;
    private String lastRemoteUrl;

    @Override
    public MavenResponse getMetadataFromUrlHidden(
        RepositoryRuntime runtime, String path, String url, boolean head,
        String validationId, java.util.function.UnaryOperator<java.io.InputStream> validator) {
      MavenResponse response = getMetadataFromUrlHidden(runtime, path, url, false);
      return MavenResponse.ok(validator.apply(response.body()), response.contentLength(), "application/json", null, null);
    }

    @Override
    public MavenResponse getMetadataFromUrlHidden(
        RepositoryRuntime runtime, String path, String remoteUrl, boolean headOnly) {
      if (path.startsWith("_nuget/index/")) {
        assertEquals("https://api.nuget.org/v3/index.json", remoteUrl);
        byte[] index = """
            {"version":"3.0.0","resources":[
              {"@type":"SearchQueryService/3.5.0","@id":"https://search.example/search"},
              {"@type":"SearchAutocompleteService/3.5.0","@id":"https://search.example/suggest"}]}
            """.getBytes(StandardCharsets.UTF_8);
        return MavenResponse.ok(new ByteArrayInputStream(index), index.length, "application/json", null, null);
      }
      return getAssetFromUrl(runtime, path, remoteUrl, headOnly);
    }

    FakeRawProxyService(String json) {
      super(null, null, null, null, null, null, null, null);
      this.json = json;
      this.failure = null;
    }

    FakeRawProxyService(RuntimeException failure) {
      super(null, null, null, null, null, null, null, null);
      this.json = null;
      this.failure = failure;
    }

    @Override
    public MavenResponse getAssetFromUrl(RepositoryRuntime runtime, String path, String remoteUrl, boolean headOnly) {
      lastRemoteUrl = remoteUrl;
      if (failure != null) {
        throw failure;
      }
      byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
      if (headOnly) {
        return MavenResponse.noBody(200, bytes.length, "application/json", null, null);
      }
      return MavenResponse.ok(new ByteArrayInputStream(bytes), bytes.length, "application/json", null, null);
    }
  }
}
