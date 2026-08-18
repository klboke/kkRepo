package com.github.klboke.kkrepo.server.conan;

import com.github.klboke.kkrepo.cache.LocalCache;
import com.github.klboke.kkrepo.cache.LocalCacheFactory;
import com.github.klboke.kkrepo.persistence.jdbc.api.PersistenceHashes;
import com.github.klboke.kkrepo.server.maven.HttpRemoteFetcher;
import com.github.klboke.kkrepo.server.maven.MavenResponse;
import com.github.klboke.kkrepo.server.maven.RemoteUrlBuilder;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntime;
import com.github.klboke.kkrepo.server.raw.RawProxyService;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HexFormat;
import org.springframework.stereotype.Component;

/** Conan-aware upstream authentication plus shared discovery caching. */
@Component
final class ConanRemoteClient {
  private static final int MAX_DISCOVERY_BYTES = 32 * 1024 * 1024;
  private static final int MAX_TOKEN_BYTES = 4096;
  private final RawProxyService proxy;
  private final HttpRemoteFetcher fetcher;
  private final LocalCache<String, String> exchangedTokens = LocalCacheFactory.standard()
      .<String, String>builder("conan-exchanged-tokens")
      .expireAfterWrite(Duration.ofMinutes(50))
      .maximumSize(10_000)
      .build();

  ConanRemoteClient(RawProxyService proxy, HttpRemoteFetcher fetcher) {
    this.proxy = proxy;
    this.fetcher = fetcher;
  }

  Discovery discovery(RepositoryRuntime runtime, String rawPath, String rawQuery) {
    RepositoryRuntime authenticated = authenticatedRuntime(runtime, false);
    String url = RemoteUrlBuilder.repositoryPathWithQueryString(
        runtime.proxyRemoteUrl(), rawPath, rawQuery);
    String cachePath = ".conan/discovery/" + HexFormat.of().formatHex(
        PersistenceHashes.sha256(rawPath, rawQuery)) + ".json";
    MavenResponse response;
    try {
      response = proxy.getMetadataFromUrlHidden(authenticated, cachePath, url, false);
    } catch (RuntimeException first) {
      if (!hasBasicCredential(runtime)) throw first;
      invalidateToken(runtime);
      response = proxy.getMetadataFromUrlHidden(
          authenticatedRuntime(runtime, true), cachePath, url, false);
    }
    try (InputStream body = response.body()) {
      if (body == null || response.contentLength() > MAX_DISCOVERY_BYTES) {
        throw new ConanExceptions.BadUpstream("Conan upstream discovery response is too large");
      }
      byte[] bytes = body.readNBytes(MAX_DISCOVERY_BYTES + 1);
      if (bytes.length > MAX_DISCOVERY_BYTES) {
        throw new ConanExceptions.BadUpstream("Conan upstream discovery response is too large");
      }
      return new Discovery(bytes, response.contentType(), response.lastModified());
    } catch (IOException e) {
      throw new ConanExceptions.BadUpstream("Unable to read Conan upstream metadata", e);
    }
  }

  HttpRemoteFetcher.Result fetchFile(RepositoryRuntime runtime, String rawPath) throws IOException {
    RepositoryRuntime authenticated = authenticatedRuntime(runtime, false);
    String url = RemoteUrlBuilder.repositoryPathString(runtime.proxyRemoteUrl(), rawPath);
    HttpRemoteFetcher.Result response = fetcher.fetch(
        HttpRemoteFetcher.Request.get(url)
            .withTimeoutProfile(HttpRemoteFetcher.TimeoutProfile.CONTENT)
            .withRepository(authenticated));
    if (response.status() != 401 || !hasBasicCredential(runtime)) return response;
    response.close();
    invalidateToken(runtime);
    return fetcher.fetch(
        HttpRemoteFetcher.Request.get(url)
            .withTimeoutProfile(HttpRemoteFetcher.TimeoutProfile.CONTENT)
            .withRepository(authenticatedRuntime(runtime, true)));
  }

  private RepositoryRuntime authenticatedRuntime(RepositoryRuntime runtime, boolean force) {
    if (!hasBasicCredential(runtime) || hasBearerCredential(runtime)) return runtime;
    String key = tokenKey(runtime);
    if (force) exchangedTokens.invalidate(key);
    String token = exchangedTokens.get(key, ignored -> exchange(runtime));
    return new RepositoryRuntime(
        runtime.id(), runtime.name(), runtime.format(), runtime.type(), runtime.recipeName(),
        runtime.online(), runtime.blobStoreId(), runtime.writePolicy(), runtime.versionPolicy(),
        runtime.layoutPolicy(), runtime.strictContentTypeValidation(), runtime.proxyRemoteUrl(),
        runtime.contentMaxAgeMinutes(), runtime.metadataMaxAgeMinutes(), runtime.autoBlock(),
        null, null, token, runtime.rawContentDisposition(), runtime.dockerConnectorEnabled(),
        runtime.dockerConnectorPort(), runtime.dockerConnectorPublicUrl(),
        runtime.cargoRequireAuthentication(), runtime.members(), runtime.outboundProxy(),
        runtime.minimumReleaseAgeMinutes());
  }

  private String exchange(RepositoryRuntime runtime) {
    String url = RemoteUrlBuilder.repositoryPathString(
        runtime.proxyRemoteUrl(), "v2/users/authenticate");
    try (HttpRemoteFetcher.Result response = fetcher.fetch(
        HttpRemoteFetcher.Request.get(url)
            .withTimeoutProfile(HttpRemoteFetcher.TimeoutProfile.METADATA)
            .withRepository(runtime))) {
      if (response.status() < 200 || response.status() >= 300) {
        throw new ConanExceptions.BadUpstream(
            "Conan upstream authentication returned " + response.status());
      }
      byte[] bytes = response.body().readNBytes(MAX_TOKEN_BYTES + 1);
      if (bytes.length == 0 || bytes.length > MAX_TOKEN_BYTES) {
        throw new ConanExceptions.BadUpstream("Invalid Conan upstream bearer token");
      }
      String token = new String(bytes, StandardCharsets.UTF_8).trim();
      if (token.isBlank() || token.chars().anyMatch(ch -> ch <= 0x1f || ch == 0x7f)) {
        throw new ConanExceptions.BadUpstream("Invalid Conan upstream bearer token");
      }
      return token;
    } catch (IOException e) {
      throw new ConanExceptions.BadUpstream("Unable to authenticate to Conan upstream", e);
    }
  }

  private void invalidateToken(RepositoryRuntime runtime) {
    exchangedTokens.invalidate(tokenKey(runtime));
  }

  private static String tokenKey(RepositoryRuntime runtime) {
    return runtime.id() + ":" + runtime.proxyRemoteUrl() + ":" + runtime.proxyRemoteUsername()
        + ":" + HexFormat.of().formatHex(PersistenceHashes.sha256(runtime.proxyRemotePassword()));
  }

  private static boolean hasBasicCredential(RepositoryRuntime runtime) {
    return runtime.proxyRemoteUsername() != null && !runtime.proxyRemoteUsername().isBlank();
  }

  private static boolean hasBearerCredential(RepositoryRuntime runtime) {
    return runtime.proxyRemoteBearerToken() != null && !runtime.proxyRemoteBearerToken().isBlank();
  }

  record Discovery(byte[] bytes, String contentType, java.time.Instant lastModified) {}
}
