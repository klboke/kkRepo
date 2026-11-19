package com.github.klboke.kkrepo.server.winget;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.core.RepositoryType;
import com.github.klboke.kkrepo.protocol.winget.WingetManifest;
import com.github.klboke.kkrepo.protocol.winget.WingetManifestParser;
import com.github.klboke.kkrepo.protocol.winget.WingetSearchRequest;
import com.github.klboke.kkrepo.server.maven.HttpRemoteFetcher;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntime;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class WingetProxyServiceTest {
  private static final String UPSTREAM = "https://upstream.example/api";

  private final ObjectMapper mapper = new ObjectMapper();
  private final WingetManifestParser parser = new WingetManifestParser();
  private final FakeWingetDao dao = new FakeWingetDao();
  private final WingetHostedService hosted = new WingetHostedService(dao, mapper);
  private final HttpRemoteFetcher fetcher = mock(HttpRemoteFetcher.class);
  private final WingetProxyService proxy = new WingetProxyService(dao, hosted, fetcher, mapper);
  private final RepositoryRuntime runtime = proxyRuntime();
  private final List<String> upstreamUrls = new ArrayList<>();

  @Test
  void versionFilteredFetchDoesNotHideOtherUpstreamVersions() throws Exception {
    serveManifestHonouringFilters("Contoso.Tool", "1.0.0", "2.0.0");

    assertEquals(List.of("1.0.0"),
        versions(proxy.manifest(runtime, "Contoso.Tool", "1.0.0", null)));
    // Before the fix the filtered 1.0.0 response replaced the cache, so this fresh-cache lookup
    // threw 404 without contacting the upstream.
    assertEquals(List.of("2.0.0"),
        versions(proxy.manifest(runtime, "Contoso.Tool", "2.0.0", null)));
    assertEquals(List.of("1.0.0", "2.0.0"),
        versions(proxy.manifest(runtime, "Contoso.Tool", null, null)));
    assertEquals(3, upstreamUrls.size());

    // The unfiltered response is a complete snapshot: filtered lookups are now served from it.
    assertEquals(List.of("2.0.0"),
        versions(proxy.manifest(runtime, "Contoso.Tool", "2.0.0", null)));
    assertEquals(3, upstreamUrls.size());
  }

  @Test
  void searchReturnsOneUpstreamPageWithoutContinuationToken() throws Exception {
    String body = """
        {"Data":[{"PackageIdentifier":"Contoso.Tool","PackageName":"Contoso Tool",
          "Publisher":"Contoso","Versions":[{"PackageVersion":"1.0.0"}]}],
         "ContinuationToken":"upstream-page-2"}
        """;
    when(fetcher.fetch(any())).thenReturn(new HttpRemoteFetcher.Result(
        200, Map.of("ContinuationToken", "upstream-page-2"),
        new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8))));

    WingetSearchPage page = proxy.search(runtime, searchAll(10), new byte[0], 10);

    assertEquals(1, page.packages().size());
    // The token cannot be forwarded upstream, so relaying it would make the client request the
    // same first page again with the same token.
    assertNull(page.nextOffset());
  }

  @Test
  void cachedSearchFallbackDoesNotAdvertiseANextPage() throws Exception {
    for (int i = 0; i < 25; i++) {
      hosted.cache(runtime, manifest("Contoso.Tool" + i, "1.0.0"));
    }
    when(fetcher.fetch(any())).thenThrow(new IOException("upstream down"));

    WingetSearchPage page = proxy.search(runtime, searchAll(10), new byte[0], 10);

    assertEquals(10, page.packages().size());
    // A hosted offset token would be resolved to offset 0 for a proxy repository, looping the client.
    assertNull(page.nextOffset());
  }

  private void serveManifestHonouringFilters(String identifier, String... versions)
      throws IOException {
    when(fetcher.fetch(any())).thenAnswer(invocation -> {
      String url = invocation.getArgument(0, HttpRemoteFetcher.Request.class).url();
      upstreamUrls.add(url);
      String filter = queryParameter(url, "PackageVersion");
      String[] served = filter == null ? versions
          : Arrays.stream(versions).filter(filter::equals).toArray(String[]::new);
      String body = "{\"Data\":" + manifestJson(identifier, served) + "}";
      return new HttpRemoteFetcher.Result(200, Map.of(),
          new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)));
    });
  }

  private static String queryParameter(String url, String name) {
    int query = url.indexOf('?');
    if (query < 0) return null;
    for (String pair : url.substring(query + 1).split("&")) {
      if (pair.startsWith(name + "=")) {
        return URLDecoder.decode(pair.substring(name.length() + 1), StandardCharsets.UTF_8);
      }
    }
    return null;
  }

  private WingetSearchRequest searchAll(int maximumResults) throws Exception {
    return WingetSearchRequest.parse(
        mapper.readTree("{\"MaximumResults\":" + maximumResults + "}"));
  }

  private static List<String> versions(WingetManifest manifest) {
    return manifest.versions().stream().map(version -> version.packageVersion()).sorted().toList();
  }

  private WingetManifest manifest(String identifier, String... versions) throws Exception {
    return parser.parse(mapper.readTree(manifestJson(identifier, versions)));
  }

  private static String manifestJson(String identifier, String... versions) {
    String publisher = identifier.substring(0, identifier.indexOf('.'));
    StringBuilder body = new StringBuilder(
        "{\"PackageIdentifier\":\"" + identifier + "\",\"Versions\":[");
    for (int i = 0; i < versions.length; i++) {
      if (i > 0) body.append(',');
      body.append("""
          {"PackageIdentifier":"%s","PackageVersion":"%s",
           "DefaultLocale":{"PackageLocale":"en-US","Publisher":"%s","PackageName":"%s Tool"},
           "Installers":[{"InstallerType":"msi"}]}
          """.formatted(identifier, versions[i], publisher, publisher));
    }
    return body.append("]}").toString();
  }

  private static RepositoryRuntime proxyRuntime() {
    return new RepositoryRuntime(
        2L, "winget-proxy", RepositoryFormat.WINGET, RepositoryType.PROXY, "winget-proxy",
        true, 1L, null,
        null, null, true, UPSTREAM, 60, 30, true, null, List.of());
  }
}
