package com.github.klboke.kkrepo.server.maven;

import static org.junit.jupiter.api.Assertions.*;

import com.github.klboke.kkrepo.server.proxy.NtlmCredentials;
import com.github.klboke.kkrepo.server.proxy.ProxiedHttpClientFactory;
import com.github.klboke.kkrepo.server.security.OutboundRequestPolicy;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;

class HttpRemoteFetcherNtlmTest {
  private static final NtlmCredentials VALID = new NtlmCredentials("User", "Password", "Domain", "KKREPO");

  @Test
  void validatesNtlmv2ProofAndIsolatesRepositoriesCredentialRotationAndAnonymousRequests() throws Exception {
    try (Fixture upstream = new Fixture(); ProxiedHttpClientFactory factory = new ProxiedHttpClientFactory(60_000, 2_000)) {
      HttpRemoteFetcher fetcher = fetcher(factory);
      assertEquals(200, get(fetcher, upstream.url(), "first", VALID, false));
      assertEquals(200, get(fetcher, upstream.url(), "first", VALID, true));
      assertEquals(401, get(fetcher, upstream.url(), "first", null, false));
      NtlmCredentials wrong = new NtlmCredentials("User", "wrong", "Domain", "KKREPO");
      assertEquals(401, get(fetcher, upstream.url(), "first", wrong, false));
      assertEquals(401, get(fetcher, upstream.url(), "second", wrong, false));
      assertEquals(200, get(fetcher, upstream.url(), "second", VALID, false));
      assertTrue(upstream.proofs.get() >= 2);
      int before = upstream.proofs.get();
      factory.invalidateNtlm("first");
      assertEquals(200, get(fetcher, upstream.url(), "first", VALID, false));
      assertTrue(upstream.proofs.get() > before, "eviction must force a new handshake");
    }
  }

  @Test
  void sameOriginRedirectPreservesNtlmButAllowedCrossOriginRedirectStripsIt() throws Exception {
    try (Fixture first = new Fixture(); Fixture second = new Fixture();
        ProxiedHttpClientFactory factory = new ProxiedHttpClientFactory(60_000, 2_000)) {
      first.redirect = "/ok";
      HttpRemoteFetcher fetcher = fetcher(factory);
      assertEquals(200, get(fetcher, first.url() + "redirect", "repo", VALID, false));
      first.redirect = second.url();
      assertEquals(401, get(fetcher, first.url() + "redirect", "repo", VALID, false));
      assertEquals(0, second.type1.get(), "redirect must not negotiate with source credentials");
    }
  }

  @Test
  void upstreamNtlmAndHttpProxyBasicCredentialsStaySeparate() throws Exception {
    try (Fixture proxy = new Fixture(); ProxiedHttpClientFactory factory = new ProxiedHttpClientFactory(60_000, 2_000)) {
      proxy.proxyAuthorization = "Basic " + Base64.getEncoder().encodeToString(
          "proxy-user:proxy-secret".getBytes(StandardCharsets.UTF_8));
      var config = new com.github.klboke.kkrepo.server.proxy.OutboundProxyConfig(
          com.github.klboke.kkrepo.server.proxy.OutboundProxyConfig.Type.HTTP,
          "localhost", proxy.server.getAddress().getPort(), "proxy-user", "proxy-secret");
      var request = new HttpRemoteFetcher.Request("http://private.invalid:8081/package", null, null,
          Duration.ofSeconds(5), HttpRemoteFetcher.TimeoutProfile.DEFAULT, false, "repo", "NUGET",
          "private.invalid", null, Set.of(), config, null, null, null, VALID);
      try (var result = fetcher(factory).fetch(request)) {
        assertEquals(200, result.status());
        assertArrayEquals(new byte[] {1, 2, 3}, result.body().readAllBytes());
      }
      assertEquals(1, proxy.proofs.get());
      assertTrue(proxy.proxyChallenges.get() > 0);
    }
  }

  @Test
  void normalizesDomainQualifiedUsernameAndRedactsCredentials() {
    var credentials = new NtlmCredentials("Domain\\User", "Password", null, null);
    assertEquals("Domain", credentials.domain());
    assertEquals("User", credentials.username());
    assertFalse(credentials.toString().contains("Password"));
    assertNotEquals(VALID.cacheKey(), new NtlmCredentials("User", "changed", "Domain", "KKREPO").cacheKey());
  }

  private static HttpRemoteFetcher fetcher(ProxiedHttpClientFactory factory) {
    return new HttpRemoteFetcher(OutboundRequestPolicy.allowPrivateForTests(), null, factory,
        "HTTP_1_1", 5, 5, 5, 0, 1);
  }

  private static int get(HttpRemoteFetcher fetcher, String url, String owner, NtlmCredentials credentials, boolean head)
      throws Exception {
    var request = new HttpRemoteFetcher.Request(url, null, null, Duration.ofSeconds(5),
        HttpRemoteFetcher.TimeoutProfile.DEFAULT, head, owner, "NUGET", "localhost", null,
        Set.of("localhost"), null, null, null, null, credentials);
    try (var response = fetcher.fetch(request)) {
      if (response.status() == 200 && !head) assertArrayEquals(new byte[] {1, 2, 3}, response.body().readAllBytes());
      return response.status();
    }
  }

  /** Independent NTLMv2 verifier; a Type 3 token is accepted only after validating its password proof. */
  private static final class Fixture implements AutoCloseable {
    private static final byte[] CHALLENGE = HexFormat.of().parseHex("0123456789abcdef");
    private static final byte[] HASH = HexFormat.of().parseHex("a4f49c406510bdcab6824ee7c30fd852");
    final HttpServer server;
    // Test-only per-connection handshake state, never application state or a production cache.
    final Map<InetSocketAddress, Boolean> connections = new ConcurrentHashMap<>();
    final AtomicInteger proofs = new AtomicInteger();
    final AtomicInteger type1 = new AtomicInteger();
    volatile String redirect;
    volatile String proxyAuthorization;
    final AtomicInteger proxyChallenges = new AtomicInteger();

    Fixture() throws IOException {
      server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      server.createContext("/", this::handle);
      server.start();
    }

    String url() { return "http://localhost:" + server.getAddress().getPort() + "/"; }

    private void handle(HttpExchange exchange) throws IOException {
      try {
        if (proxyAuthorization != null
            && !proxyAuthorization.equals(exchange.getRequestHeaders().getFirst("Proxy-Authorization"))) {
          proxyChallenges.incrementAndGet();
          exchange.getResponseHeaders().set("Proxy-Authenticate", "Basic realm=\"proxy\"");
          exchange.sendResponseHeaders(407, -1);
          return;
        }
        String auth = exchange.getRequestHeaders().getFirst("Authorization");
        if (auth != null && auth.startsWith("NTLM ")) {
          byte[] token = Base64.getDecoder().decode(auth.substring(5));
          int type = ByteBuffer.wrap(token).order(ByteOrder.LITTLE_ENDIAN).getInt(8);
          if (type == 1) {
            type1.incrementAndGet();
            connections.put(exchange.getRemoteAddress(), false);
            exchange.getResponseHeaders().set("WWW-Authenticate", "NTLM " + Base64.getEncoder().encodeToString(type2()));
            exchange.sendResponseHeaders(401, -1);
            return;
          }
          if (type == 3 && connections.containsKey(exchange.getRemoteAddress())) {
            String domain = new String(field(token, 28), StandardCharsets.UTF_16LE);
            String user = new String(field(token, 36), StandardCharsets.UTF_16LE);
            byte[] response = field(token, 20);
            byte[] key = hmac(HASH, (user.toUpperCase(java.util.Locale.ROOT) + domain).getBytes(StandardCharsets.UTF_16LE));
            byte[] input = ByteBuffer.allocate(8 + response.length - 16).put(CHALLENGE).put(response, 16, response.length - 16).array();
            boolean valid = "User".equals(user) && "Domain".equalsIgnoreCase(domain)
                && java.security.MessageDigest.isEqual(hmac(key, input), java.util.Arrays.copyOf(response, 16));
            connections.put(exchange.getRemoteAddress(), valid);
            if (valid) proofs.incrementAndGet();
          }
        }
        if (!Boolean.TRUE.equals(connections.get(exchange.getRemoteAddress()))) {
          exchange.getResponseHeaders().set("WWW-Authenticate", "NTLM");
          exchange.sendResponseHeaders(401, -1);
        } else if (exchange.getRequestURI().getPath().equals("/redirect")) {
          exchange.getResponseHeaders().set("Location", redirect);
          exchange.sendResponseHeaders(302, -1);
        } else if (exchange.getRequestMethod().equals("HEAD")) {
          exchange.getResponseHeaders().set("Content-Length", "3");
          exchange.sendResponseHeaders(200, -1);
        } else {
          exchange.sendResponseHeaders(200, 3);
          exchange.getResponseBody().write(new byte[] {1, 2, 3});
        }
      } catch (Exception e) {
        throw new IOException(e);
      } finally {
        exchange.close();
      }
    }

    static byte[] field(byte[] token, int offset) {
      ByteBuffer bytes = ByteBuffer.wrap(token).order(ByteOrder.LITTLE_ENDIAN);
      int length = Short.toUnsignedInt(bytes.getShort(offset));
      int start = bytes.getInt(offset + 4);
      return java.util.Arrays.copyOfRange(token, start, start + length);
    }

    static byte[] hmac(byte[] key, byte[] bytes) throws Exception {
      Mac mac = Mac.getInstance("HmacMD5");
      mac.init(new SecretKeySpec(key, "HmacMD5"));
      return mac.doFinal(bytes);
    }

    static byte[] type2() {
      byte[] domain = "Domain".getBytes(StandardCharsets.UTF_16LE);
      byte[] info = ByteBuffer.allocate(8 + domain.length).order(ByteOrder.LITTLE_ENDIAN)
          .putShort((short) 2).putShort((short) domain.length).put(domain).putInt(0).array();
      return ByteBuffer.allocate(48 + domain.length + info.length).order(ByteOrder.LITTLE_ENDIAN)
          .put("NTLMSSP\0".getBytes(StandardCharsets.US_ASCII)).putInt(2)
          .putShort((short) domain.length).putShort((short) domain.length).putInt(48)
          .putInt(0x00880205).put(CHALLENGE).putLong(0)
          .putShort((short) info.length).putShort((short) info.length).putInt(48 + domain.length)
          .put(domain).put(info).array();
    }

    @Override public void close() { server.stop(0); }
  }
}
