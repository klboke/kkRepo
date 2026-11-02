package com.github.klboke.kkrepo.server.winget;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.klboke.kkrepo.persistence.jdbc.api.WingetDao;
import com.github.klboke.kkrepo.protocol.winget.WingetException;
import com.github.klboke.kkrepo.protocol.winget.WingetManifest;
import com.github.klboke.kkrepo.protocol.winget.WingetManifestParser;
import com.github.klboke.kkrepo.protocol.winget.WingetProtocol;
import com.github.klboke.kkrepo.protocol.winget.WingetSearchRequest;
import com.github.klboke.kkrepo.server.maven.HttpRemoteFetcher;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntime;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/** Read-through WinGet proxy. Upstream manifests are cached in the shared metadata store. */
@Service
public class WingetProxyService {
  private static final Logger log = LoggerFactory.getLogger(WingetProxyService.class);

  private final WingetDao dao;
  private final WingetHostedService hosted;
  private final HttpRemoteFetcher fetcher;
  private final ObjectMapper mapper;
  private final WingetManifestParser parser = new WingetManifestParser();

  public WingetProxyService(
      WingetDao dao, WingetHostedService hosted, HttpRemoteFetcher fetcher, ObjectMapper mapper) {
    this.dao = dao;
    this.hosted = hosted;
    this.fetcher = fetcher;
    this.mapper = mapper;
  }

  public WingetManifest manifest(
      RepositoryRuntime runtime, String packageIdentifier, String packageVersion, String channel) {
    List<WingetDao.ManifestRecord> cached = dao.listByPackage(runtime.id(), packageIdentifier);
    if (!cached.isEmpty() && fresh(cached, runtime.contentMaxAgeMinutesOrDefault())) {
      return WingetManifests.filter(
          hosted.manifestFromRows(packageIdentifier, cached, packageVersion, channel),
          packageVersion, channel);
    }
    try {
      WingetManifest fetched = fetchManifest(runtime, packageIdentifier, packageVersion, channel);
      hosted.cache(runtime, fetched);
      return WingetManifests.filter(fetched, packageVersion, channel);
    } catch (IOException | RuntimeException failure) {
      if (!cached.isEmpty()) {
        log.warn("Serving stale WinGet proxy cache for {} after upstream failure: {}",
            packageIdentifier, failure.toString());
        return WingetManifests.filter(
            hosted.manifestFromRows(packageIdentifier, cached, packageVersion, channel),
            packageVersion, channel);
      }
      if (failure instanceof WingetException winget) throw winget;
      throw WingetException.upstream("WinGet upstream is unavailable");
    }
  }

  public WingetSearchPage search(
      RepositoryRuntime runtime, WingetSearchRequest request, byte[] requestBody,
      String continuationToken, int offset, int limit) {
    String url = base(runtime) + "/" + WingetProtocol.MANIFEST_SEARCH_PATH;
    HttpRemoteFetcher.Request remote = new HttpRemoteFetcher.Request(
        url, null, null, null, HttpRemoteFetcher.TimeoutProfile.METADATA, false,
        runtime.name(), "WINGET")
        .withRepositoryForContent(runtime)
        .withAccept(WingetProtocol.MEDIA_TYPE)
        .withBody(WingetProtocol.MEDIA_TYPE, requestBody);
    try (HttpRemoteFetcher.Result result = fetcher.fetch(remote)) {
      if (result.status() == 404) {
        return WingetSearchPage.empty();
      }
      if (result.status() < 200 || result.status() >= 300) {
        throw WingetException.upstream("WinGet upstream search returned HTTP " + result.status());
      }
      byte[] body = WingetJson.readBody(result.body());
      JsonNode root = mapper.readTree(body);
      return new WingetSearchPage(WingetJson.packages(root), null,
          result.header("ContinuationToken"));
    } catch (IOException | RuntimeException failure) {
      log.warn("WinGet proxy search failed; trying cached manifests: {}", failure.toString());
      WingetSearchPage cached = hosted.searchCached(runtime, request, offset, limit);
      if (!cached.packages().isEmpty()) {
        return cached;
      }
      if (failure instanceof WingetException winget) throw winget;
      throw WingetException.upstream("WinGet upstream search is unavailable");
    }
  }

  private WingetManifest fetchManifest(
      RepositoryRuntime runtime, String packageIdentifier, String packageVersion, String channel)
      throws IOException {
    StringBuilder url = new StringBuilder(base(runtime))
        .append('/').append(WingetProtocol.PACKAGE_MANIFESTS_PATH)
        .append('/').append(encode(packageIdentifier));
    List<String> query = new ArrayList<>();
    if (packageVersion != null && !packageVersion.isBlank()) {
      query.add("PackageVersion=" + encode(packageVersion));
    }
    if (channel != null && !channel.isBlank()) {
      query.add("Channel=" + encode(channel));
    }
    if (!query.isEmpty()) {
      url.append('?').append(String.join("&", query));
    }
    HttpRemoteFetcher.Request remote = new HttpRemoteFetcher.Request(
        url.toString(), null, null, null, HttpRemoteFetcher.TimeoutProfile.METADATA, false,
        runtime.name(), "WINGET")
        .withRepositoryForContent(runtime)
        .withAccept(WingetProtocol.MEDIA_TYPE);
    try (HttpRemoteFetcher.Result result = fetcher.fetch(remote)) {
      if (result.status() == 404) {
        throw WingetException.notFound("Package manifest not found: " + packageIdentifier);
      }
      if (result.status() < 200 || result.status() >= 300) {
        throw WingetException.upstream("WinGet upstream returned HTTP " + result.status());
      }
      JsonNode root = mapper.readTree(WingetJson.readBody(result.body()));
      WingetManifest manifest = parser.parse(WingetJson.data(root));
      if (!manifest.packageIdentifier().equalsIgnoreCase(packageIdentifier)) {
        throw WingetException.upstream(
            "WinGet upstream returned a manifest for a different package identifier");
      }
      return manifest;
    }
  }

  private static boolean fresh(List<WingetDao.ManifestRecord> rows, int ttlMinutes) {
    Instant newest = rows.stream()
        .map(WingetDao.ManifestRecord::updatedAt)
        .max(Instant::compareTo)
        .orElse(Instant.EPOCH);
    return newest.plusSeconds(Math.max(0, ttlMinutes) * 60L).isAfter(Instant.now());
  }

  private static String base(RepositoryRuntime runtime) {
    String remote = runtime.proxyRemoteUrl();
    if (remote == null || remote.isBlank()) {
      throw WingetException.unavailable("WinGet proxy remote URL is not configured");
    }
    String base = remote.endsWith("/") ? remote.substring(0, remote.length() - 1) : remote;
    if (base.endsWith("/api")) {
      base = base.substring(0, base.length() - 4);
    }
    return base;
  }

  private static String encode(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
  }
}
