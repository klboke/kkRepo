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
  void searchConsumesUpstreamPagesUntilTheClientLimitIsMet() throws Exception {
    String pageOne = """
        {"Data":[{"PackageIdentifier":"Contoso.Tool","PackageName":"Contoso Tool",
          "Publisher":"Contoso","Versions":[{"PackageVersion":"1.0.0"}]}],
         "ContinuationToken":"upstream-page-2"}
        """;
    String pageTwo = """
        {"Data":[{"PackageIdentifier":"Fabrikam.Tool","PackageName":"Fabrikam Tool",
          "Publisher":"Fabrikam","Versions":[{"PackageVersion":"2.0.0"}]}]}
        """;
    List<HttpRemoteFetcher.Request> sent = new ArrayList<>();
    when(fetcher.fetch(any())).thenAnswer(invocation -> {
      HttpRemoteFetcher.Request outbound = invocation.getArgument(0, HttpRemoteFetcher.Request.class);
      sent.add(outbound);
      boolean secondPage = outbound.extraHeaders()
          .entrySet().stream().anyMatch(header -> header.getKey().equalsIgnoreCase("ContinuationToken")
              && header.getValue().equals("upstream-page-2"));
      return new HttpRemoteFetcher.Result(200, Map.of(),
          new ByteArrayInputStream((secondPage ? pageTwo : pageOne)
              .getBytes(StandardCharsets.UTF_8)));
    });

    WingetSearchPage page = proxy.search(runtime, searchAll(10), new byte[0], 10);

    // The package on the second upstream page is returned instead of being truncated.
    assertEquals(2, page.packages().size());
    assertEquals("Fabrikam.Tool", page.packages().get(1).packageIdentifier());
    assertNull(page.nextOffset());
    assertEquals(2, sent.size());
    // The opaque upstream token is forwarded as the ContinuationToken request header.
    assertEquals("upstream-page-2", sent.get(1).extraHeaders().get("ContinuationToken"));
  }

  @Test
  void searchStopsWhenTheUpstreamRunsOutOfPages() throws Exception {
    String singlePage = """
        {"Data":[{"PackageIdentifier":"Contoso.Tool","PackageName":"Contoso Tool",
          "Publisher":"Contoso","Versions":[{"PackageVersion":"1.0.0"}]}]}
        """;
    List<HttpRemoteFetcher.Request> sent = new ArrayList<>();
    when(fetcher.fetch(any())).thenAnswer(invocation -> {
      sent.add(invocation.getArgument(0, HttpRemoteFetcher.Request.class));
      return new HttpRemoteFetcher.Result(200, Map.of(),
          new ByteArrayInputStream(singlePage.getBytes(StandardCharsets.UTF_8)));
    });

    WingetSearchPage page = proxy.search(runtime, searchAll(10), new byte[0], 10);

    assertEquals(1, page.packages().size());
    assertNull(page.nextOffset());
    // One upstream page: the response carries no ContinuationToken, so no second fetch.
    assertEquals(1, sent.size());
  }

  @Test
  void searchDedupsRepeatedPackagesAndKeepsFreshPagesOnALateUpstreamFailure() throws Exception {
    String pageOne = """
        {"Data":[{"PackageIdentifier":"Contoso.Tool","PackageName":"Contoso Tool",
          "Publisher":"Contoso","Versions":[{"PackageVersion":"1.0.0"}]}],
         "ContinuationToken":"upstream-page-2"}
        """;
    String pageTwo = """
        {"Data":[{"PackageIdentifier":"Contoso.Tool","PackageName":"Contoso Tool",
          "Publisher":"Contoso","Versions":[{"PackageVersion":"1.0.0"}]},
         {"PackageIdentifier":"Fabrikam.Tool","PackageName":"Fabrikam Tool",
          "Publisher":"Fabrikam","Versions":[{"PackageVersion":"2.0.0"}]}],
         "ContinuationToken":"upstream-page-3"}
        """;
    when(fetcher.fetch(any())).thenAnswer(invocation -> {
      HttpRemoteFetcher.Request outbound = invocation.getArgument(0, HttpRemoteFetcher.Request.class);
      String token = outbound.extraHeaders().get("ContinuationToken");
      if (token == null) {
        return new HttpRemoteFetcher.Result(200, Map.of(),
            new ByteArrayInputStream(pageOne.getBytes(StandardCharsets.UTF_8)));
      }
      if (token.equals("upstream-page-2")) {
        return new HttpRemoteFetcher.Result(200, Map.of(),
            new ByteArrayInputStream(pageTwo.getBytes(StandardCharsets.UTF_8)));
      }
      throw new IOException("upstream down on page three");
    });
    // A cached package must not leak into the result: pages fetched before the failure are fresh.
    hosted.cache(runtime, manifest("Cached.Tool", "9.9.9"));

    WingetSearchPage page = proxy.search(runtime, searchAll(10), new byte[0], 10);

    // The page-two repeat of Contoso.Tool is dropped, and the fresh Fabrikam.Tool survives the
    // page-three failure instead of being replaced by the stale cache.
    assertEquals(List.of("Contoso.Tool", "Fabrikam.Tool"),
        page.packages().stream().map(pkg -> pkg.packageIdentifier()).toList());
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
      String filter = queryParameter(url, "Version");
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
