package com.github.klboke.kkrepo.server.docker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.github.klboke.kkrepo.core.RepositoryType;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntime;
import com.github.klboke.kkrepo.server.proxy.ProxiedHttpClientFactory;
import com.github.klboke.kkrepo.server.security.OutboundRequestPolicy;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class DockerProxyPaginationTest {
  static Stream<Arguments> pages() {
    return Stream.of("", "/", "/v2", "/v2/", "/v2/docker-hosted").flatMap(base ->
        Stream.of(false, true).flatMap(group -> Stream.of(false, true).flatMap(catalog ->
            Stream.of(false, true).map(cursor -> Arguments.of(base, group, catalog, cursor)))));
  }

  @ParameterizedTest
  @MethodSource("pages")
  void paginationReachesRegistryAsQueryIncludingBearerRetry(
      String basePath, boolean group, boolean catalog, boolean cursor) throws Exception {
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    String base = "http://127.0.0.1:" + server.getAddress().getPort();
    String prefix = basePath.startsWith("/v2/docker-hosted") ? "/v2/docker-hosted/" : "/v2/";
    String path = prefix + (catalog ? "_catalog" : "team/app/tags/list");
    String query = "n=" + (group ? 4 : 3)
        + (cursor ? "&last=" + (catalog ? "team%2Fa" : "v1.0") : "");
    List<URI> requests = new CopyOnWriteArrayList<>();
    List<String> values = catalog ? List.of("team/b", "team/c", "team/d") : List.of("v1.1", "v1.2", "v1.3");
    server.createContext("/", exchange -> {
      URI uri = exchange.getRequestURI();
      byte[] body;
      if (uri.getPath().equals("/token")) {
        body = "{\"token\":\"pagination-token\"}".getBytes(StandardCharsets.UTF_8);
      } else {
        requests.add(uri);
        if (!path.equals(uri.getRawPath()) || !query.equals(uri.getRawQuery())) {
          exchange.sendResponseHeaders(404, -1);
          exchange.close();
          return;
        }
        if (!"Bearer pagination-token".equals(exchange.getRequestHeaders().getFirst("Authorization"))) {
          exchange.getResponseHeaders().set("WWW-Authenticate", "Bearer realm=\"" + base
              + "/token\",service=\"registry\",scope=\"repository:team/app:pull\"");
          exchange.sendResponseHeaders(401, -1);
          exchange.close();
          return;
        }
        body = ("{\"name\":\"team/app\",\"" + (catalog ? "repositories" : "tags")
            + "\":[\"" + String.join("\",\"", values) + "\"]}").getBytes(StandardCharsets.UTF_8);
      }
      exchange.getResponseHeaders().set("Content-Type", "application/json");
      exchange.sendResponseHeaders(200, body.length);
      exchange.getResponseBody().write(body);
      exchange.close();
    });
    server.start();
    try (ProxiedHttpClientFactory transport = new ProxiedHttpClientFactory(10000, 5000)) {
      DockerRemoteRegistryClient client = new DockerRemoteRegistryClient(
          null, OutboundRequestPolicy.allowPrivateForTests(), null, false, 0, null, transport);
      DockerProxyService proxy = new DockerProxyService(null, mock(DockerManifestStore.class), client);
      RepositoryRuntime member = mock(RepositoryRuntime.class);
      when(member.id()).thenReturn(1L);
      when(member.type()).thenReturn(RepositoryType.PROXY);
      when(member.proxyRemoteUrl()).thenReturn(base + basePath);
      RepositoryRuntime entry = mock(RepositoryRuntime.class);
      when(entry.type()).thenReturn(RepositoryType.GROUP);
      when(entry.members()).thenReturn(List.of(member));
      DockerGroupService groups = new DockerGroupService(null, proxy);
      String last = cursor ? (catalog ? "team/a" : "v1.0") : null;
      if (catalog) {
        DockerCatalogList result = group ? groups.catalog(entry, last, 2) : proxy.catalog(member, last, 2);
        assertEquals(values.subList(0, 2), result.repositories());
        assertTrue(result.hasNext());
      } else {
        DockerTagList result = group ? groups.tags(entry, "team/app", last, 2) : proxy.tags(member, "team/app", last, 2);
        assertEquals(values.subList(0, 2), result.tags());
        assertTrue(result.hasNext());
      }
      assertEquals(List.of(URI.create(path + "?" + query), URI.create(path + "?" + query)), requests);
    } finally {
      server.stop(0);
    }
  }
}
