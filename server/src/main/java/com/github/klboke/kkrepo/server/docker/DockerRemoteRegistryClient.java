package com.github.klboke.kkrepo.server.docker;

import com.github.klboke.kkrepo.cache.SharedCache;
import com.github.klboke.kkrepo.protocol.docker.DockerErrorCode;
import com.github.klboke.kkrepo.protocol.docker.DockerProtocolException;
import com.github.klboke.kkrepo.server.maven.HttpRemoteFetcher;
import com.github.klboke.kkrepo.server.maven.RemoteUrlBuilder;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntime;
import com.github.klboke.kkrepo.server.metrics.KkRepoMetrics;
import com.github.klboke.kkrepo.server.proxy.ProxiedHttpClientFactory;
import com.github.klboke.kkrepo.server.security.OutboundRequestPolicy;
import io.micrometer.core.instrument.Timer;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class DockerRemoteRegistryClient {
  private static final String TOKEN_CACHE_NAMESPACE = "docker-remote-token";
  private static final int MAX_REDIRECTS = 5;

  private final HttpRemoteFetcher fetcher;
  private final OutboundRequestPolicy outboundPolicy;
  private final HttpClient tokenClient = HttpClient.newHttpClient();
  private final ProxiedHttpClientFactory proxyFactory;
  private final SharedCache tokenCache;
  private final boolean tokenCacheEnabled;
  private final Duration tokenCacheTtl;
  private final KkRepoMetrics metrics;

  public DockerRemoteRegistryClient(HttpRemoteFetcher fetcher, OutboundRequestPolicy outboundPolicy) {
    this(fetcher, outboundPolicy, null, true, 300, null, null);
  }

  @Autowired
  public DockerRemoteRegistryClient(
      HttpRemoteFetcher fetcher,
      OutboundRequestPolicy outboundPolicy,
      SharedCache tokenCache,
      @Value("${kkrepo.docker.proxy.remote-token-cache.enabled:true}") boolean tokenCacheEnabled,
      @Value("${kkrepo.docker.proxy.remote-token-cache.ttl-seconds:300}") long tokenCacheTtlSeconds,
      KkRepoMetrics metrics,
      ProxiedHttpClientFactory proxyFactory) {
    this.fetcher = fetcher;
    this.outboundPolicy = outboundPolicy;
    this.tokenCache = tokenCache;
    this.tokenCacheEnabled = tokenCacheEnabled;
    this.tokenCacheTtl = tokenCacheTtlSeconds <= 0 ? Duration.ZERO : Duration.ofSeconds(tokenCacheTtlSeconds);
    this.metrics = metrics;
    this.proxyFactory = proxyFactory;
  }

  public DockerRemoteRegistryClient(
      HttpRemoteFetcher fetcher,
      OutboundRequestPolicy outboundPolicy,
      SharedCache tokenCache,
      boolean tokenCacheEnabled,
      long tokenCacheTtlSeconds) {
    this(fetcher, outboundPolicy, tokenCache, tokenCacheEnabled, tokenCacheTtlSeconds, null, null);
  }

  public HttpRemoteFetcher.Result get(RepositoryRuntime runtime, String remotePath, String accept) throws IOException {
    String url = remoteUrl(runtime, remotePath);
    HttpRemoteFetcher.Result result = fetch(url, runtime, null, accept);
    if (result.status() == 401) {
      String challenge = result.header("WWW-Authenticate");
      RemoteToken token = token(runtime, challenge).orElse(null);
      result.close();
      if (token != null) {
        result = fetch(url, runtime, token.value(), accept);
        if (result.status() == 401 && token.cached()) {
          evictToken(token.cacheKey());
          challenge = result.header("WWW-Authenticate");
          token = fetchToken(runtime, BearerChallenge.parse(challenge).orElse(null), true).orElse(null);
          if (token != null) {
            result.close();
            result = fetch(url, runtime, token.value(), accept);
          }
        }
      }
    }
    return result;
  }

  private HttpRemoteFetcher.Result fetch(
      String url, RepositoryRuntime runtime, String bearerToken, String accept) throws IOException {
    return fetchWithHeaders(url, runtime, bearerToken, accept, 0);
  }

  private HttpRemoteFetcher.Result fetchWithHeaders(
      String url,
      RepositoryRuntime runtime,
      String bearerToken,
      String accept,
      int redirects) throws IOException {
    if (proxyEnabled(runtime)) {
      return proxiedFetchWithHeaders(url, runtime, bearerToken, accept, redirects);
    }
    Timer.Sample sample = metrics == null ? null : metrics.startTimer();
    int status = 0;
    Throwable failure = null;
    boolean recorded = false;
    try {
      URI uri = outboundPolicy.validateHttpUri(url, "docker remote fetch");
      HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
          .timeout(Duration.ofSeconds(180))
          .header("User-Agent", "kkrepo/0.1")
          .header("Accept", accept == null || accept.isBlank() ? "*/*" : accept);
      if (bearerToken != null && !bearerToken.isBlank()) {
        builder.header("Authorization", "Bearer " + bearerToken);
      } else {
        basicAuthorization(runtime).ifPresent(value -> builder.header("Authorization", value));
      }
      HttpResponse<java.io.InputStream> response = tokenClient.send(
          builder.GET().build(), HttpResponse.BodyHandlers.ofInputStream());
      status = response.statusCode();
      Optional<String> redirect = redirectLocation(response);
      if (redirect.isPresent()) {
        response.body().close();
        if (redirects >= MAX_REDIRECTS) {
          throw new IOException("Too many redirects fetching Docker remote " + url);
        }
        URI redirected = outboundPolicy.validateHttpUri(uri.resolve(redirect.get()), "docker remote redirect");
        recordRemote(runtime, "GET", url, status, null, sample);
        recorded = true;
        return fetchWithHeaders(redirected.toString(), runtime, bearerToken, accept, redirects + 1);
      }
      Map<String, String> headers = new LinkedHashMap<>();
      response.headers().map().forEach((k, v) -> {
        if (!v.isEmpty()) headers.put(k, v.get(0));
      });
      return new HttpRemoteFetcher.Result(response.statusCode(), headers, response.body());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      IOException wrapped = new IOException("Interrupted fetching Docker remote " + url, e);
      failure = wrapped;
      throw wrapped;
    } catch (IOException | RuntimeException e) {
      failure = e;
      throw e;
    } finally {
      if (!recorded) {
        recordRemote(runtime, "GET", url, status, failure, sample);
      }
    }
  }

  private Optional<RemoteToken> token(RepositoryRuntime runtime, String challenge) throws IOException {
    BearerChallenge bearer = BearerChallenge.parse(challenge).orElse(null);
    if (bearer == null) {
      return Optional.empty();
    }
    String cacheKey = tokenCacheKey(runtime, bearer);
    if (tokenCacheEnabled && !tokenCacheTtl.isZero() && tokenCache != null) {
      Optional<String> cached = tokenCache.getString(TOKEN_CACHE_NAMESPACE, cacheKey)
          .filter(value -> !value.isBlank());
      if (cached.isPresent()) {
        return Optional.of(new RemoteToken(cached.get(), cacheKey, true));
      }
    }
    return fetchToken(runtime, bearer, true);
  }

  private Optional<RemoteToken> fetchToken(
      RepositoryRuntime runtime,
      BearerChallenge bearer,
      boolean cacheable) throws IOException {
    if (bearer == null) {
      return Optional.empty();
    }
    String url = bearer.realm()
        + "?service=" + encode(bearer.service())
        + (bearer.scope() == null ? "" : "&scope=" + encode(bearer.scope()));
    if (proxyEnabled(runtime)) {
      return proxiedFetchToken(runtime, bearer, cacheable, url);
    }
    Timer.Sample sample = metrics == null ? null : metrics.startTimer();
    int status = 0;
    Throwable failure = null;
    try {
      URI uri = outboundPolicy.validateHttpUri(url, "docker token fetch");
      HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
          .timeout(Duration.ofSeconds(30))
          .header("User-Agent", "kkrepo/0.1");
      basicAuthorization(runtime).ifPresent(value -> builder.header("Authorization", value));
      HttpRequest request = builder.GET().build();
      HttpResponse<String> response = tokenClient.send(request, HttpResponse.BodyHandlers.ofString());
      status = response.statusCode();
      if (response.statusCode() < 200 || response.statusCode() >= 300) {
        return Optional.empty();
      }
      String body = response.body();
      String token = extractJsonString(body, "token");
      if (token == null) {
        token = extractJsonString(body, "access_token");
      }
      if (token == null || token.isBlank()) {
        return Optional.empty();
      }
      String cacheKey = tokenCacheKey(runtime, bearer);
      if (cacheable && tokenCacheEnabled && !tokenCacheTtl.isZero() && tokenCache != null) {
        tokenCache.putString(TOKEN_CACHE_NAMESPACE, cacheKey, token, tokenTtl(body));
      }
      return Optional.of(new RemoteToken(token, cacheKey, false));
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      IOException wrapped = new IOException("Interrupted fetching Docker remote token", e);
      failure = wrapped;
      throw wrapped;
    } catch (IOException | RuntimeException e) {
      failure = e;
      throw e;
    } finally {
      recordRemote(runtime, "TOKEN", url, status, failure, sample);
    }
  }

  private void evictToken(String cacheKey) {
    if (tokenCache != null && cacheKey != null) {
      tokenCache.evict(TOKEN_CACHE_NAMESPACE, cacheKey);
    }
  }

  private boolean proxyEnabled(RepositoryRuntime runtime) {
    return proxyFactory != null
        && runtime != null
        && runtime.outboundProxy() != null
        && runtime.outboundProxy().enabled();
  }

  /** Proxied counterpart of {@link #fetchWithHeaders} — same headers/redirect/metrics, via SOCKS/HTTP proxy. */
  private HttpRemoteFetcher.Result proxiedFetchWithHeaders(
      String url,
      RepositoryRuntime runtime,
      String bearerToken,
      String accept,
      int redirects) throws IOException {
    Timer.Sample sample = metrics == null ? null : metrics.startTimer();
    int status = 0;
    Throwable failure = null;
    boolean recorded = false;
    ProxiedHttpClientFactory.ProxiedResponse response = null;
    try {
      URI uri = outboundPolicy.validateHttpUri(url, "docker remote fetch");
      Map<String, String> headers = new LinkedHashMap<>();
      headers.put("User-Agent", "kkrepo/0.1");
      headers.put("Accept", accept == null || accept.isBlank() ? "*/*" : accept);
      if (bearerToken != null && !bearerToken.isBlank()) {
        headers.put("Authorization", "Bearer " + bearerToken);
      } else {
        basicAuthorization(runtime).ifPresent(value -> headers.put("Authorization", value));
      }
      response = proxyFactory.execute(
          runtime.outboundProxy(),
          "GET",
          uri,
          headers,
          Duration.ofSeconds(10).toMillis(),
          Duration.ofSeconds(180).toMillis());
      status = response.status();
      boolean redirectStatus =
          status == 301 || status == 302 || status == 303 || status == 307 || status == 308;
      String location = response.header("Location");
      if (redirectStatus && location != null && !location.isBlank()) {
        response.close();
        response = null;
        if (redirects >= MAX_REDIRECTS) {
          throw new IOException("Too many redirects fetching Docker remote " + url);
        }
        URI redirected = outboundPolicy.validateHttpUri(uri.resolve(location), "docker remote redirect");
        recordRemote(runtime, "GET", url, status, null, sample);
        recorded = true;
        return proxiedFetchWithHeaders(redirected.toString(), runtime, bearerToken, accept, redirects + 1);
      }
      return new HttpRemoteFetcher.Result(response.status(), response.headers(), releaseOnClose(response));
    } catch (IOException | RuntimeException e) {
      failure = e;
      if (response != null) {
        response.close();
      }
      throw e;
    } finally {
      if (!recorded) {
        recordRemote(runtime, "GET", url, status, failure, sample);
      }
    }
  }

  /** Proxied counterpart of {@link #fetchToken} for the Docker OAuth token exchange. */
  private Optional<RemoteToken> proxiedFetchToken(
      RepositoryRuntime runtime,
      BearerChallenge bearer,
      boolean cacheable,
      String url) throws IOException {
    Timer.Sample sample = metrics == null ? null : metrics.startTimer();
    int status = 0;
    Throwable failure = null;
    ProxiedHttpClientFactory.ProxiedResponse response = null;
    try {
      URI uri = outboundPolicy.validateHttpUri(url, "docker token fetch");
      Map<String, String> headers = new LinkedHashMap<>();
      headers.put("User-Agent", "kkrepo/0.1");
      basicAuthorization(runtime).ifPresent(value -> headers.put("Authorization", value));
      response = proxyFactory.execute(
          runtime.outboundProxy(),
          "GET",
          uri,
          headers,
          Duration.ofSeconds(10).toMillis(),
          Duration.ofSeconds(30).toMillis());
      status = response.status();
      if (status < 200 || status >= 300) {
        return Optional.empty();
      }
      String body = new String(response.body().readAllBytes(), StandardCharsets.UTF_8);
      String token = extractJsonString(body, "token");
      if (token == null) {
        token = extractJsonString(body, "access_token");
      }
      if (token == null || token.isBlank()) {
        return Optional.empty();
      }
      String cacheKey = tokenCacheKey(runtime, bearer);
      if (cacheable && tokenCacheEnabled && !tokenCacheTtl.isZero() && tokenCache != null) {
        tokenCache.putString(TOKEN_CACHE_NAMESPACE, cacheKey, token, tokenTtl(body));
      }
      return Optional.of(new RemoteToken(token, cacheKey, false));
    } catch (IOException | RuntimeException e) {
      failure = e;
      throw e;
    } finally {
      if (response != null) {
        response.close();
      }
      recordRemote(runtime, "TOKEN", url, status, failure, sample);
    }
  }

  private static java.io.InputStream releaseOnClose(ProxiedHttpClientFactory.ProxiedResponse response) throws IOException {
    java.io.InputStream body = response.body();
    return new java.io.FilterInputStream(body) {
      @Override
      public void close() throws java.io.IOException {
        try {
          super.close();
        } finally {
          response.close();
        }
      }
    };
  }

  private static String remoteUrl(RepositoryRuntime runtime, String remotePath) {
    String base = runtime.proxyRemoteUrl();
    if (base == null || base.isBlank()) {
      throw new DockerProtocolException(DockerErrorCode.NAME_UNKNOWN, "Docker proxy remote URL is not configured");
    }
    while (base.endsWith("/")) base = base.substring(0, base.length() - 1);
    String path = remotePath == null ? "" : remotePath;
    while (path.startsWith("/")) path = path.substring(1);
    if (base.endsWith("/v2")) {
      return RemoteUrlBuilder.repositoryPathString(base, path);
    }
    if (base.contains("/v2/")) {
      return RemoteUrlBuilder.repositoryPathString(base, path);
    }
    return RemoteUrlBuilder.repositoryPathString(base + "/v2", path);
  }

  private static String encode(String value) {
    return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
  }

  private Duration tokenTtl(String body) {
    Long expiresIn = extractJsonLong(body, "expires_in");
    if (expiresIn == null || expiresIn <= 0) {
      return tokenCacheTtl;
    }
    return Duration.ofSeconds(Math.max(1, Math.min(tokenCacheTtl.toSeconds(), expiresIn - 15)));
  }

  private static String tokenCacheKey(RepositoryRuntime runtime, BearerChallenge bearer) {
    String source = (runtime == null ? "" : runtime.proxyRemoteUrl()) + "\n"
        + bearer.realm() + "\n"
        + nullToEmpty(bearer.service()) + "\n"
        + nullToEmpty(bearer.scope()) + "\n"
        + credentialFingerprint(runtime);
    return runtimeId(runtime) + ":" + sha256(source);
  }

  private static Optional<String> redirectLocation(HttpResponse<?> response) {
    int status = response.statusCode();
    if (status == 301 || status == 302 || status == 303 || status == 307 || status == 308) {
      return response.headers().firstValue("Location").filter(value -> !value.isBlank());
    }
    return Optional.empty();
  }

  private void recordRemote(
      RepositoryRuntime runtime, String method, String url, int status, Throwable failure, Timer.Sample sample) {
    if (metrics == null || sample == null) {
      return;
    }
    metrics.recordProxyRemote(
        runtime == null ? null : runtime.name(),
        "docker",
        method,
        remoteHost(url),
        status,
        failure,
        sample);
  }

  private static String remoteHost(String url) {
    try {
      return URI.create(url).getHost();
    } catch (RuntimeException e) {
      return "unknown";
    }
  }

  private static long runtimeId(RepositoryRuntime runtime) {
    return runtime == null ? 0 : runtime.id();
  }

  private static String credentialFingerprint(RepositoryRuntime runtime) {
    return sha256(basicAuthorization(runtime).orElse("anonymous"));
  }

  private static String sha256(String value) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
          .digest(nullToEmpty(value).getBytes(StandardCharsets.UTF_8)));
    } catch (Exception e) {
      throw new IllegalStateException("Failed hashing Docker remote token cache key", e);
    }
  }

  private static String nullToEmpty(String value) {
    return value == null ? "" : value;
  }

  private static Optional<String> basicAuthorization(RepositoryRuntime runtime) {
    if (runtime == null
        || runtime.proxyRemoteUsername() == null
        || runtime.proxyRemoteUsername().isBlank()
        || runtime.proxyRemotePassword() == null
        || runtime.proxyRemotePassword().isBlank()) {
      return Optional.empty();
    }
    String raw = runtime.proxyRemoteUsername() + ":" + runtime.proxyRemotePassword();
    return Optional.of("Basic " + Base64.getEncoder().encodeToString(raw.getBytes(StandardCharsets.UTF_8)));
  }

  private static String extractJsonString(String json, String key) {
    if (json == null) {
      return null;
    }
    String needle = "\"" + key + "\"";
    int keyIndex = json.indexOf(needle);
    if (keyIndex < 0) {
      return null;
    }
    int colon = json.indexOf(':', keyIndex + needle.length());
    if (colon < 0) {
      return null;
    }
    int first = json.indexOf('"', colon + 1);
    if (first < 0) {
      return null;
    }
    int second = json.indexOf('"', first + 1);
    return second < 0 ? null : json.substring(first + 1, second);
  }

  private static Long extractJsonLong(String json, String key) {
    if (json == null) {
      return null;
    }
    String needle = "\"" + key + "\"";
    int keyIndex = json.indexOf(needle);
    if (keyIndex < 0) {
      return null;
    }
    int colon = json.indexOf(':', keyIndex + needle.length());
    if (colon < 0) {
      return null;
    }
    int start = colon + 1;
    while (start < json.length() && Character.isWhitespace(json.charAt(start))) {
      start++;
    }
    int end = start;
    while (end < json.length() && Character.isDigit(json.charAt(end))) {
      end++;
    }
    if (end == start) {
      return null;
    }
    try {
      return Long.parseLong(json.substring(start, end));
    } catch (NumberFormatException e) {
      return null;
    }
  }

  private record RemoteToken(String value, String cacheKey, boolean cached) {
  }

  private record BearerChallenge(String realm, String service, String scope) {
    static Optional<BearerChallenge> parse(String header) {
      if (header == null || !header.regionMatches(true, 0, "Bearer ", 0, 7)) {
        return Optional.empty();
      }
      Map<String, String> values = new LinkedHashMap<>();
      String rest = header.substring(7);
      for (String part : splitChallengeParameters(rest)) {
        int eq = part.indexOf('=');
        if (eq <= 0) continue;
        String key = part.substring(0, eq).trim();
        String value = part.substring(eq + 1).trim();
        if (value.startsWith("\"") && value.endsWith("\"") && value.length() >= 2) {
          value = value.substring(1, value.length() - 1);
        }
        values.put(key, value);
      }
      String realm = values.get("realm");
      if (realm == null || realm.isBlank()) {
        return Optional.empty();
      }
      return Optional.of(new BearerChallenge(realm, values.get("service"), values.get("scope")));
    }

    private static List<String> splitChallengeParameters(String value) {
      List<String> parts = new java.util.ArrayList<>();
      StringBuilder current = new StringBuilder();
      boolean quoted = false;
      boolean escaped = false;
      for (int i = 0; i < value.length(); i++) {
        char ch = value.charAt(i);
        if (escaped) {
          current.append(ch);
          escaped = false;
          continue;
        }
        if (quoted && ch == '\\') {
          current.append(ch);
          escaped = true;
          continue;
        }
        if (ch == '"') {
          quoted = !quoted;
          current.append(ch);
          continue;
        }
        if (ch == ',' && !quoted) {
          parts.add(current.toString());
          current.setLength(0);
          continue;
        }
        current.append(ch);
      }
      parts.add(current.toString());
      return parts;
    }
  }
}
