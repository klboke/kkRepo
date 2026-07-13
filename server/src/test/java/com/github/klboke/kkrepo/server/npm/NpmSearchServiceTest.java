package com.github.klboke.kkrepo.server.npm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.core.RepositoryType;
import com.github.klboke.kkrepo.persistence.jdbc.api.ComponentDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.ComponentDao.ComponentSearchRow;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntime;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class NpmSearchServiceTest {

  @Test
  void hostedSearchBuildsScopedPackageResultAndTiming() {
    ComponentDao components = mock(ComponentDao.class);
    NpmHostedService hosted = mock(NpmHostedService.class);
    NpmProxyService proxy = mock(NpmProxyService.class);
    RepositoryRuntime runtime = hosted(1L, "npm-hosted");
    when(components.searchByRepositoryIds(List.of(1L), "client", 20))
        .thenReturn(List.of(row(11L, 1L, "scope", "client", "1.2.3")));

    Map<String, Object> response =
        new NpmSearchService(components, hosted, proxy)
            .search(runtime, "client", 20, "https://repo.example/repository/npm-hosted");

    assertEquals(1, response.get("total"));
    assertTrue(String.valueOf(response.get("time")).endsWith("ms"));
    Map<String, Object> item = objects(response).get(0);
    Map<String, Object> pkg = map(item.get("package"));
    assertEquals("@scope/client", pkg.get("name"));
    assertEquals("1.2.3", pkg.get("version"));
    assertEquals(
        "https://repo.example/repository/npm-hosted/@scope/client",
        map(pkg.get("links")).get("npm"));
    assertEquals(1.0, item.get("searchScore"));
  }

  @Test
  void proxySearchDelegatesWithoutRewritingResponse() {
    ComponentDao components = mock(ComponentDao.class);
    NpmHostedService hosted = mock(NpmHostedService.class);
    NpmProxyService proxy = mock(NpmProxyService.class);
    RepositoryRuntime runtime = proxy(2L, "npm-proxy");
    Map<String, Object> upstream = Map.of("objects", List.of(), "total", 42);
    when(proxy.search(runtime, "demo", 5)).thenReturn(upstream);

    Map<String, Object> response =
        new NpmSearchService(components, hosted, proxy)
            .search(runtime, "demo", 5, "https://repo.example/repository/npm-proxy");

    assertSame(upstream, response);
  }

  @Test
  void groupSearchPreservesMemberOrderDeduplicatesNamesAndHonorsLimit() {
    ComponentDao components = mock(ComponentDao.class);
    NpmHostedService hosted = mock(NpmHostedService.class);
    NpmProxyService proxy = mock(NpmProxyService.class);
    RepositoryRuntime hostedMember = hosted(1L, "hosted");
    RepositoryRuntime proxyMember = proxy(2L, "proxy");
    RepositoryRuntime group = group(10L, List.of(hostedMember, proxyMember));
    when(components.searchByRepositoryIds(List.of(1L), "demo", 2))
        .thenReturn(List.of(row(1L, 1L, null, "demo", "1.0.0")));
    when(proxy.search(proxyMember, "demo", 1))
        .thenReturn(Map.of(
            "objects", List.of(searchItem("demo"), searchItem("other")),
            "total", 2));

    Map<String, Object> response =
        new NpmSearchService(components, hosted, proxy)
            .search(group, "demo", 2, "https://repo.example/repository/npm-group");

    assertEquals(2, response.get("total"));
    assertEquals(List.of("demo", "other"), objects(response).stream()
        .map(item -> map(item.get("package")).get("name").toString())
        .toList());
    verify(proxy).search(proxyMember, "demo", 1);
  }

  @Test
  void malformedMemberObjectsAreSkippedAndLaterMembersStillContribute() {
    ComponentDao components = mock(ComponentDao.class);
    NpmHostedService hosted = mock(NpmHostedService.class);
    NpmProxyService proxy = mock(NpmProxyService.class);
    RepositoryRuntime first = proxy(1L, "first");
    RepositoryRuntime second = proxy(2L, "second");
    RepositoryRuntime group = group(10L, List.of(first, second));
    when(proxy.search(first, "demo", 3)).thenReturn(Map.of("objects", "not-an-array"));
    when(proxy.search(second, "demo", 3))
        .thenReturn(Map.of("objects", List.of(
            Map.of("score", 1),
            Map.of("package", Map.of()),
            searchItem("valid"))));

    Map<String, Object> response =
        new NpmSearchService(components, hosted, proxy)
            .search(group, "demo", 3, "https://repo.example/repository/npm-group");

    assertEquals(1, response.get("total"));
    assertEquals("valid", map(objects(response).get(0).get("package")).get("name"));
  }

  @Test
  void legacyIndexRecursesNestedGroupsAndEmitsEachPackageOnce() {
    ComponentDao components = mock(ComponentDao.class);
    NpmHostedService hosted = mock(NpmHostedService.class);
    NpmProxyService proxy = mock(NpmProxyService.class);
    RepositoryRuntime first = hosted(1L, "first");
    RepositoryRuntime second = hosted(2L, "second");
    RepositoryRuntime nested = group(11L, List.of(second));
    RepositoryRuntime root = group(10L, List.of(first, nested));
    when(components.searchByRepositoryIds(List.of(1L, 2L), "", 300))
        .thenReturn(List.of(
            row(1L, 1L, null, "demo", "1.0.0"),
            row(2L, 2L, null, "demo", "2.0.0"),
            row(3L, 2L, "scope", "client", "3.0.0"),
            row(4L, 99L, null, "orphan", "1.0.0")));
    when(hosted.packageRoot(eq(first), any()))
        .thenReturn(Optional.of(packageRoot("demo", "1.0.0")));
    when(hosted.packageRoot(eq(second), any()))
        .thenReturn(Optional.of(packageRoot("@scope/client", "3.0.0")));

    Map<String, Object> response = new NpmSearchService(components, hosted, proxy).legacyIndex(root);

    assertTrue(response.containsKey("_updated"));
    assertTrue(response.containsKey("demo"));
    assertTrue(response.containsKey("@scope/client"));
    assertEquals(3, response.size());
  }

  private static ComponentSearchRow row(
      long id,
      long repositoryId,
      String namespace,
      String name,
      String version) {
    return new ComponentSearchRow(
        id,
        repositoryId,
        "repo-" + repositoryId,
        RepositoryFormat.NPM,
        namespace,
        name,
        version,
        "package",
        Instant.parse("2026-07-13T10:00:00Z"),
        null);
  }

  private static Map<String, Object> packageRoot(String name, String version) {
    Map<String, Object> root = new LinkedHashMap<>();
    root.put("name", name);
    root.put("dist-tags", Map.of("latest", version));
    root.put("versions", Map.of(version, Map.of("name", name, "version", version)));
    return root;
  }

  private static Map<String, Object> searchItem(String name) {
    return Map.of("package", Map.of("name", name), "score", Map.of(), "searchScore", 1.0);
  }

  @SuppressWarnings("unchecked")
  private static List<Map<String, Object>> objects(Map<String, Object> response) {
    return (List<Map<String, Object>>) response.get("objects");
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> map(Object value) {
    return (Map<String, Object>) value;
  }

  private static RepositoryRuntime hosted(long id, String name) {
    return runtime(id, name, RepositoryType.HOSTED, List.of());
  }

  private static RepositoryRuntime proxy(long id, String name) {
    return runtime(id, name, RepositoryType.PROXY, List.of());
  }

  private static RepositoryRuntime group(long id, List<RepositoryRuntime> members) {
    return runtime(id, "npm-group", RepositoryType.GROUP, members);
  }

  private static RepositoryRuntime runtime(
      long id,
      String name,
      RepositoryType type,
      List<RepositoryRuntime> members) {
    return new RepositoryRuntime(
        id,
        name,
        RepositoryFormat.NPM,
        type,
        "npm-" + type.name().toLowerCase(),
        true,
        7L,
        "ALLOW",
        null,
        null,
        true,
        type == RepositoryType.PROXY ? "https://registry.npmjs.org/" : null,
        60,
        60,
        true,
        null,
        members);
  }
}
