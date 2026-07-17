package com.github.klboke.kkrepo.server.proxy;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.RemovalCause;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.hc.client5.http.auth.AuthScope;
import org.apache.hc.client5.http.auth.UsernamePasswordCredentials;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.classic.methods.HttpHead;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.auth.BasicCredentialsProvider;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.core5.http.Header;
import org.apache.hc.core5.http.HttpEntity;
import org.apache.hc.core5.http.ClassicHttpRequest;
import org.apache.hc.core5.http.ClassicHttpResponse;
import org.apache.hc.core5.http.HttpHost;
import org.apache.hc.core5.http.URIScheme;
import org.apache.hc.core5.http.io.SocketConfig;
import org.apache.hc.client5.http.protocol.HttpClientContext;
import org.apache.hc.core5.util.Timeout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Builds and caches outbound HTTP clients that tunnel upstream repository traffic through a
 * per-repository configured proxy (HTTP CONNECT or SOCKS).
 *
 * <p>HTTP proxies use Apache HttpClient 5's native CONNECT support with a per-client
 * {@code CredentialsProvider}, so HTTP proxy credentials are fully isolated per repository. SOCKS
 * uses HttpClient 5's native {@code SocketConfig.socksProxyAddress} support (the JDK's
 * {@code java.net.Proxy(SOCKS)} under the hood). Because the JDK only surfaces SOCKS5
 * username/password auth through the JVM-global {@link java.net.Authenticator}, this class installs
 * a single dispatcher authenticator that routes each challenge to the credentials registered for
 * that proxy's host:port — so repositories using different SOCKS proxies keep their own credentials
 * instead of overwriting one another (see the note on {@link #buildSocks}).
 *
 * <p>Each distinct {@link OutboundProxyConfig#cacheKey()} gets its own pooled
 * {@link CloseableHttpClient} in a Caffeine cache. Entries expire after a period without use
 * ({@code kkrepo.outbound-proxy.idle-ttl-ms}, default 1h) and the removal listener closes the
 * underlying connection pool, so a credential rotation or a repository deletion does not leave a
 * stale pool behind. {@link #close()} closes everything on application stop.
 */
@Component
public class ProxiedHttpClientFactory implements AutoCloseable {
  private static final Logger log = LoggerFactory.getLogger(ProxiedHttpClientFactory.class);

  private final Cache<String, CloseableHttpClient> cache;
  private final Duration idleTtl;
  private final Duration connectTimeout;

  public ProxiedHttpClientFactory(
      @Value("${kkrepo.outbound-proxy.idle-ttl-ms:3600000}") long idleTtlMillis,
      @Value("${kkrepo.outbound-proxy.connect-timeout-ms:10000}") long connectTimeoutMillis) {
    this.idleTtl = Duration.ofMillis(Math.max(1L, idleTtlMillis));
    this.connectTimeout = Duration.ofMillis(Math.max(1L, connectTimeoutMillis));
    this.cache = Caffeine.newBuilder()
        .expireAfterAccess(this.idleTtl)
        .removalListener((String key, CloseableHttpClient client, RemovalCause cause) ->
            closeQuietly(client))
        .build();
  }

  /** Returns the pooled client for the given config, building it on first use. */
  public CloseableHttpClient clientFor(OutboundProxyConfig config) {
    if (config == null || !config.enabled()) {
      throw new IllegalArgumentException("outbound proxy config is not enabled");
    }
    return cache.get(config.cacheKey(), key -> build(config));
  }

  /**
   * Closes every cached client and its connection pool. Invoked on application shutdown so pooled
   * connections do not outlive the process; the Caffeine removal listener also closes any client
   * evicted by the idle TTL.
   */
  @PreDestroy
  @Override
  public void close() {
    cache.invalidateAll();
    cache.cleanUp();
  }

  private static void closeQuietly(CloseableHttpClient client) {
    if (client == null) {
      return;
    }
    try {
      client.close();
    } catch (IOException ignored) {
      // best effort — releasing the pool is what matters
    }
  }

  /**
   * Executes a GET/HEAD through the proxy and returns the live response. The caller must
   * {@link ProxiedResponse#close()} it after draining the body so the connection returns to the pool.
   *
   * <p>The connect timeout is configured once per cached client on its connection manager
   * ({@code kkrepo.outbound-proxy.connect-timeout-ms}), because HttpClient 5.4+ removed per-request
   * connect timeouts ({@code RequestConfig.setConnectTimeout} is deprecated). The response timeout
   * stays per-request in {@link RequestConfig}.
   */
  @SuppressWarnings("resource") // the cached client is shared; it is closed by the cache, not per request
  public ProxiedResponse execute(
      OutboundProxyConfig config,
      String method,
      URI uri,
      Map<String, String> headers,
      long responseTimeoutMillis)
      throws IOException {
    CloseableHttpClient client = clientFor(config);
    ClassicHttpRequest request = "HEAD".equalsIgnoreCase(method) ? new HttpHead(uri) : new HttpGet(uri);
    if (headers != null) {
      headers.forEach((name, value) -> {
        if (name != null && value != null) {
          request.addHeader(name, value);
        }
      });
    }
    RequestConfig requestConfig = RequestConfig.custom()
        .setResponseTimeout(Timeout.ofMilliseconds(Math.max(1L, responseTimeoutMillis)))
        .build();
    HttpClientContext context = HttpClientContext.create();
    context.setRequestConfig(requestConfig);
    // executeOpen (unlike the deprecated execute overloads) returns a live, caller-closed response,
    // which is what the streaming callers (releaseOnClose) require.
    ClassicHttpResponse response = client.executeOpen(null, request, context);
    Map<String, String> responseHeaders = new LinkedHashMap<>();
    for (Header header : response.getHeaders()) {
      responseHeaders.putIfAbsent(header.getName(), header.getValue());
    }
    return new ProxiedResponse(response, responseHeaders);
  }

  private CloseableHttpClient build(OutboundProxyConfig config) {
    if (config.type() == OutboundProxyConfig.Type.SOCKS) {
      return buildSocks(config);
    }
    return buildHttp(config);
  }

  private CloseableHttpClient buildHttp(OutboundProxyConfig config) {
    HttpHost proxy = new HttpHost(URIScheme.HTTP.getId(), config.host(), config.port());
    PoolingHttpClientConnectionManager connectionManager = new PoolingHttpClientConnectionManager();
    connectionManager.setDefaultConnectionConfig(defaultConnectionConfig());
    var builder = HttpClients.custom()
        .setConnectionManager(connectionManager)
        .setProxy(proxy)
        .disableContentCompression()
        .disableRedirectHandling();
    if (config.authenticated()) {
      BasicCredentialsProvider credentials = new BasicCredentialsProvider();
      credentials.setCredentials(
          new AuthScope(config.host(), config.port()),
          new UsernamePasswordCredentials(config.username(), passwordChars(config.password())));
      builder.setDefaultCredentialsProvider(credentials);
    }
    log.info("Configured outbound HTTP proxy {}:{} for upstream repository fetches",
        config.host(), config.port());
    return builder.build();
  }

  private CloseableHttpClient buildSocks(OutboundProxyConfig config) {
    registerSocksCredentials(config);
    // HttpClient 5 natively supports SOCKS via SocketConfig.socksProxyAddress: the connection
    // operator builds a java.net.Proxy(SOCKS) and tunnels every connection (plain and TLS) through
    // it. This is the supported path — registering a custom ConnectionSocketFactory for "http" is
    // ignored by the operator in HttpClient 5.4+.
    PoolingHttpClientConnectionManager connectionManager = new PoolingHttpClientConnectionManager();
    connectionManager.setMaxTotal(50);
    connectionManager.setDefaultMaxPerRoute(20);
    connectionManager.setDefaultConnectionConfig(defaultConnectionConfig());
    connectionManager.setDefaultSocketConfig(SocketConfig.custom()
        .setSocksProxyAddress(new InetSocketAddress(config.host(), config.port()))
        .build());
    log.info("Configured outbound SOCKS proxy {}:{} for upstream repository fetches",
        config.host(), config.port());
    return HttpClients.custom()
        .setConnectionManager(connectionManager)
        .disableContentCompression()
        .disableRedirectHandling()
        .build();
  }

  /**
   * Connect timeout applied at the connection-manager level, the only non-deprecated place
   * HttpClient 5.4+ supports it.
   */
  private ConnectionConfig defaultConnectionConfig() {
    return ConnectionConfig.custom()
        .setConnectTimeout(Timeout.of(this.connectTimeout))
        .build();
  }

  /**
   * A username-only proxy config is valid ({@link OutboundProxyConfig#authenticated()} only requires
   * a username), so a null/blank password must be treated as an empty secret rather than dereferenced.
   */
  private static char[] passwordChars(String password) {
    return password != null ? password.toCharArray() : new char[0];
  }

  // --- SOCKS5 username/password authentication -------------------------------------------------
  //
  // The JDK's java.net.Proxy(SOCKS) implementation only surfaces username/password auth through
  // the JVM-global Authenticator. Installing one authenticator per repository would make
  // repositories overwrite each other's credentials. Instead we install a single dispatcher that
  // routes each SOCKS auth challenge to the credentials registered for that proxy's host:port, so
  // repositories using different SOCKS proxies (or different accounts) stay isolated. The same
  // proxy host:port can only carry one credential set — that is an accepted, documented limit.

  private static final ConcurrentMap<String, UsernamePasswordCredentials> SOCKS_CREDENTIALS =
      new ConcurrentHashMap<>();
  private static final AtomicBoolean DISPATCHER_INSTALLED = new AtomicBoolean();

  private static void registerSocksCredentials(OutboundProxyConfig config) {
    if (!config.authenticated()) {
      return;
    }
    installSocksDispatcher();
    SOCKS_CREDENTIALS.put(
        socksKey(config.host(), config.port()),
        new UsernamePasswordCredentials(config.username(), passwordChars(config.password())));
  }

  private static String socksKey(String host, int port) {
    return host.trim().toLowerCase(Locale.ROOT) + ":" + port;
  }

  private static void installSocksDispatcher() {
    if (!DISPATCHER_INSTALLED.compareAndSet(false, true)) {
      return;
    }
    java.net.Authenticator.setDefault(new java.net.Authenticator() {
      @Override
      protected java.net.PasswordAuthentication getPasswordAuthentication() {
        String host = getRequestingHost();
        if (host == null) {
          return null;
        }
        UsernamePasswordCredentials credentials =
            SOCKS_CREDENTIALS.get(socksKey(host, getRequestingPort()));
        if (credentials == null) {
          return null;
        }
        return new java.net.PasswordAuthentication(
            credentials.getUserName(), credentials.getUserPassword());
      }
    });
  }

  /** Live Apache response; closing it releases the pooled connection. */
  public static final class ProxiedResponse implements AutoCloseable {
    private final ClassicHttpResponse response;
    private final Map<String, String> headers;

    ProxiedResponse(ClassicHttpResponse response, Map<String, String> headers) {
      this.response = response;
      this.headers = headers;
    }

    public int status() {
      return response.getCode();
    }

    public Map<String, String> headers() {
      return headers;
    }

    public String header(String name) {
      for (Map.Entry<String, String> entry : headers.entrySet()) {
        if (entry.getKey().equalsIgnoreCase(name)) {
          return entry.getValue();
        }
      }
      return null;
    }

    public InputStream body() throws IOException {
      HttpEntity entity = response.getEntity();
      return entity != null ? entity.getContent() : java.io.InputStream.nullInputStream();
    }

    @Override
    public void close() {
      try {
        response.close();
      } catch (IOException ignored) {
        // best effort — connection release is what matters
      }
    }
  }
}
