package com.github.klboke.kkrepo.server.proxy;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.URI;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import org.apache.hc.client5.http.auth.AuthScope;
import org.apache.hc.client5.http.auth.UsernamePasswordCredentials;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.classic.methods.HttpHead;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.auth.BasicCredentialsProvider;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.CloseableHttpResponse;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.client5.http.socket.ConnectionSocketFactory;
import org.apache.hc.core5.http.Header;
import org.apache.hc.core5.http.HttpEntity;
import org.apache.hc.core5.http.ClassicHttpRequest;
import org.apache.hc.core5.http.HttpHost;
import org.apache.hc.core5.http.URIScheme;
import org.apache.hc.core5.http.config.Registry;
import org.apache.hc.core5.http.config.RegistryBuilder;
import org.apache.hc.client5.http.protocol.HttpClientContext;
import org.apache.hc.core5.http.protocol.HttpContext;
import org.apache.hc.core5.util.Timeout;
import org.apache.hc.core5.util.TimeValue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Builds and caches outbound HTTP clients that tunnel upstream repository traffic through a
 * per-repository configured proxy (HTTP CONNECT or SOCKS).
 *
 * <p>The JDK {@code java.net.http.HttpClient} only understands HTTP-CONNECT proxies, so for SOCKS
 * we fall back to Apache HttpClient 5 (already on the classpath via the OSS SDK) with custom socket
 * factories that bind a {@link Proxy}. Each distinct {@link OutboundProxyConfig} gets its own pooled
 * {@link CloseableHttpClient}; clients are immutable so reuse is safe across replicas. There is no
 * eviction — the set of distinct proxies in a deployment is small and bounded by the number of proxy
 * repositories.
 *
 * <p>SOCKS5 username/password authentication relies on a JVM-wide {@link java.net.Authenticator}
 * (a JDK limitation). This is acceptable for the typical "single corporate Clash on the LAN with no
 * auth" deployment; if multiple SOCKS proxies need distinct credentials that must be revisited.
 */
@Component
public class ProxiedHttpClientFactory {
  private static final Logger log = LoggerFactory.getLogger(ProxiedHttpClientFactory.class);

  private final ConcurrentMap<String, CloseableHttpClient> cache = new ConcurrentHashMap<>();

  /** Returns the pooled client for the given config, building it on first use. */
  public CloseableHttpClient clientFor(OutboundProxyConfig config) {
    if (config == null || !config.enabled()) {
      throw new IllegalArgumentException("outbound proxy config is not enabled");
    }
    return cache.computeIfAbsent(config.cacheKey(), key -> build(config));
  }

  /**
   * Executes a GET/HEAD through the proxy and returns the live response. The caller must
   * {@link ProxiedResponse#close()} it after draining the body so the connection returns to the pool.
   */
  public ProxiedResponse execute(
      OutboundProxyConfig config,
      String method,
      URI uri,
      Map<String, String> headers,
      long connectTimeoutMillis,
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
        .setConnectTimeout(Timeout.ofMilliseconds(Math.max(1L, connectTimeoutMillis)))
        .setResponseTimeout(Timeout.ofMilliseconds(Math.max(1L, responseTimeoutMillis)))
        .build();
    HttpClientContext context = HttpClientContext.create();
    context.setRequestConfig(requestConfig);
    CloseableHttpResponse response = client.execute(request, context);
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
    var builder = HttpClients.custom()
        .setProxy(proxy)
        .disableContentCompression();
    if (config.authenticated()) {
      BasicCredentialsProvider credentials = new BasicCredentialsProvider();
      credentials.setCredentials(
          new AuthScope(config.host(), config.port()),
          new UsernamePasswordCredentials(config.username(), config.password().toCharArray()));
      builder.setDefaultCredentialsProvider(credentials);
    }
    log.info("Configured outbound HTTP proxy {}:{} for upstream repository fetches",
        config.host(), config.port());
    return builder.build();
  }

  private CloseableHttpClient buildSocks(OutboundProxyConfig config) {
    if (config.authenticated()) {
      installSocksAuthenticator(config.username(), config.password());
    }
    SSLContext sslContext;
    try {
      sslContext = SSLContext.getInstance("TLS");
      sslContext.init(null, null, null);
    } catch (NoSuchAlgorithmException | KeyManagementException e) {
      throw new IllegalStateException("Failed initializing SSL context for SOCKS proxy", e);
    }
    Proxy socks = new Proxy(Proxy.Type.SOCKS, new InetSocketAddress(config.host(), config.port()));
    Registry<ConnectionSocketFactory> registry = RegistryBuilder.<ConnectionSocketFactory>create()
        .register("http", new SocksPlainSocketFactory(socks))
        .register("https", new SocksSslSocketFactory(sslContext, socks))
        .build();
    PoolingHttpClientConnectionManager connectionManager =
        new PoolingHttpClientConnectionManager(registry);
    connectionManager.setMaxTotal(50);
    connectionManager.setDefaultMaxPerRoute(20);
    log.info("Configured outbound SOCKS proxy {}:{} for upstream repository fetches",
        config.host(), config.port());
    return HttpClients.custom()
        .setConnectionManager(connectionManager)
        .disableContentCompression()
        .build();
  }

  /**
   * SOCKS5 username/password auth is surfaced by the JDK only through a global Authenticator. Because
   * kkRepo otherwise never uses SOCKS, a single global authenticator is acceptable for round 1.
   */
  private static void installSocksAuthenticator(String username, String password) {
    java.net.Authenticator.setDefault(new java.net.Authenticator() {
      @Override
      protected java.net.PasswordAuthentication getPasswordAuthentication() {
        if (getRequestorType() == RequestorType.SERVER || getRequestingHost() != null) {
          return new java.net.PasswordAuthentication(username, password.toCharArray());
        }
        return null;
      }
    });
  }

  /** Live Apache response; closing it releases the pooled connection. */
  public static final class ProxiedResponse implements AutoCloseable {
    private final CloseableHttpResponse response;
    private final Map<String, String> headers;

    ProxiedResponse(CloseableHttpResponse response, Map<String, String> headers) {
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

  /** Plain (http://) socket factory that tunnels every connection through a fixed SOCKS proxy. */
  static final class SocksPlainSocketFactory implements ConnectionSocketFactory {
    private final Proxy proxy;

    SocksPlainSocketFactory(Proxy proxy) {
      this.proxy = proxy;
    }

    @Override
    public java.net.Socket createSocket(HttpContext context) {
      return new java.net.Socket(proxy);
    }

    @Override
    public java.net.Socket connectSocket(
        TimeValue connectTimeout,
        java.net.Socket socket,
        HttpHost host,
        InetSocketAddress remoteAddress,
        InetSocketAddress localAddress,
        HttpContext context)
        throws IOException {
      java.net.Socket sock = socket != null ? socket : new java.net.Socket(proxy);
      if (localAddress != null) {
        sock.bind(localAddress);
      }
      sock.connect(remoteAddress, connectTimeout == null ? 0 : connectTimeout.toMillisecondsIntBound());
      return sock;
    }
  }

  /** TLS (https://) socket factory that tunnels through SOCKS, with SNI + hostname verification. */
  static final class SocksSslSocketFactory implements ConnectionSocketFactory {
    private final SSLContext sslContext;
    private final Proxy proxy;

    SocksSslSocketFactory(SSLContext sslContext, Proxy proxy) {
      this.sslContext = sslContext;
      this.proxy = proxy;
    }

    @Override
    public java.net.Socket createSocket(HttpContext context) {
      return new java.net.Socket(proxy);
    }

    @Override
    public java.net.Socket connectSocket(
        TimeValue connectTimeout,
        java.net.Socket socket,
        HttpHost host,
        InetSocketAddress remoteAddress,
        InetSocketAddress localAddress,
        HttpContext context)
        throws IOException {
      java.net.Socket sock = socket != null ? socket : new java.net.Socket(proxy);
      if (localAddress != null) {
        sock.bind(localAddress);
      }
      sock.connect(remoteAddress, connectTimeout == null ? 0 : connectTimeout.toMillisecondsIntBound());
      int port = remoteAddress.getPort() > 0 ? remoteAddress.getPort() : 443;
      SSLSocket sslSocket = (SSLSocket) sslContext.getSocketFactory()
          .createSocket(sock, host.getHostName(), port, true);
      SSLParameters parameters = sslSocket.getSSLParameters();
      parameters.setEndpointIdentificationAlgorithm("HTTPS");
      sslSocket.setSSLParameters(parameters);
      sslSocket.startHandshake();
      return sslSocket;
    }
  }
}
