package com.github.klboke.kkrepo.server.proxy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.github.klboke.kkrepo.server.security.OutboundRequestPolicy;
import com.github.klboke.kkrepo.server.security.OutboundRequestPolicy.ResolvedHttpTarget;
import com.github.klboke.kkrepo.server.support.FakeHttpProxyServer;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.core5.http.HttpHost;
import org.apache.hc.core5.http.message.BasicClassicHttpRequest;
import org.junit.jupiter.api.Test;

class ProxiedHttpClientFactoryTest {

  @Test
  void clientForAcceptsUsernameOnlyHttpProxyWithoutPassword() throws Exception {
    try (ProxiedHttpClientFactory factory = new ProxiedHttpClientFactory(60000, 10000)) {
      // authenticated() is true (username present) but the password is null — previously this threw an
      // NPE inside buildHttp because config.password().toCharArray() was dereferenced unconditionally.
      OutboundProxyConfig usernameOnly =
          new OutboundProxyConfig(OutboundProxyConfig.Type.HTTP, "127.0.0.1", 7890, "clash-user", null);
      try (CloseableHttpClient client = factory.clientFor("repo-a", usernameOnly)) {
        assertNotNull(client);
      }
    }
  }

  @Test
  void clientForCachesPerDistinctCredential() throws Exception {
    try (ProxiedHttpClientFactory factory = new ProxiedHttpClientFactory(60000, 10000)) {
      OutboundProxyConfig a =
          new OutboundProxyConfig(OutboundProxyConfig.Type.HTTP, "10.0.0.5", 7890, "u", "pw-a");
      OutboundProxyConfig b =
          new OutboundProxyConfig(OutboundProxyConfig.Type.HTTP, "10.0.0.5", 7890, "u", "pw-b");
      CloseableHttpClient same = factory.clientFor("repo-a", a);
      assertSame(same, factory.clientFor("repo-a", a));
      // A credential rotation must produce a distinct client, not reuse the stale one.
      assertNotSame(same, factory.clientFor("repo-a", b));
    }
  }

  @Test
  void stalledSocksHandshakeIsBoundedByConnectTimeout() throws Exception {
    // A proxy that accepts the TCP connection but never answers the SOCKS5 greeting must not hold
    // the request thread past kkrepo.outbound-proxy.connect-timeout-ms; without a handshake
    // deadline the blocking greeting read would hang forever (the HTTP response timeout only
    // starts after connection establishment).
    ServerSocket staller = new ServerSocket(0, 50, java.net.InetAddress.getByName("127.0.0.1"));
    List<Socket> accepted = new CopyOnWriteArrayList<>();
    Thread acceptor = new Thread(() -> {
      try {
        while (!staller.isClosed()) {
          accepted.add(staller.accept()); // greet nothing, reply nothing — just hold the socket
        }
      } catch (IOException ignored) {
        // stopped
      }
    }, "stalling-socks-server");
    acceptor.setDaemon(true);
    acceptor.start();
    try (ProxiedHttpClientFactory factory = new ProxiedHttpClientFactory(60000, 500)) {
      OutboundProxyConfig config = new OutboundProxyConfig(
          OutboundProxyConfig.Type.SOCKS, "127.0.0.1", staller.getLocalPort(), null, null);
      long startNanos = System.nanoTime();
      assertThrows(IOException.class, () ->
          factory.execute("repo-a", config, "GET", target("http://localhost/"),
              java.util.Map.of(), 60000));
      long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
      // The connect timeout is 500 ms; the greeting read must time out near it. The upper bound
      // leaves scheduling margin plus one retried attempt (localhost resolves to IPv4 and IPv6)
      // while staying far below any "handshake hung" scenario.
      assertTrue(elapsedMillis >= 400,
          "stalled SOCKS5 handshake failed before the connect timeout fired: "
              + elapsedMillis + "ms");
      assertTrue(elapsedMillis < 4000,
          "stalled SOCKS5 handshake outlived the connect timeout: " + elapsedMillis + "ms");
    } finally {
      staller.close();
      for (Socket socket : accepted) {
        try {
          socket.close();
        } catch (IOException ignored) {
        }
      }
    }
  }

  @Test
  void slowDripSocksHandshakeStaysWithinConnectTimeout() throws Exception {
    // A proxy that answers one byte at a time with pauses just below a naive per-read timeout.
    // Re-arming SO_TIMEOUT once per handshake phase (or with a fresh full budget per read) would
    // let the ~12 reply bytes accumulate several seconds; the absolute deadline must instead cut
    // the handshake off close to the 500 ms connect timeout.
    ServerSocket dripper = new ServerSocket(0, 50, java.net.InetAddress.getByName("127.0.0.1"));
    List<Socket> accepted = new CopyOnWriteArrayList<>();
    Thread server = new Thread(() -> {
      try {
        while (!dripper.isClosed()) {
          Socket socket = dripper.accept();
          accepted.add(socket);
          dripHandshake(socket, 400);
        }
      } catch (IOException ignored) {
        // stopped
      }
    }, "dripping-socks-server");
    server.setDaemon(true);
    server.start();
    try (ProxiedHttpClientFactory factory = new ProxiedHttpClientFactory(60000, 500)) {
      OutboundProxyConfig config = new OutboundProxyConfig(
          OutboundProxyConfig.Type.SOCKS, "127.0.0.1", dripper.getLocalPort(), null, null);
      long startNanos = System.nanoTime();
      assertThrows(IOException.class, () ->
          factory.execute("repo-a", config, "GET", target("http://localhost/"),
              java.util.Map.of(), 60000));
      long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
      assertTrue(elapsedMillis >= 400,
          "slow-drip SOCKS5 handshake failed before the connect timeout fired: "
              + elapsedMillis + "ms");
      // With per-phase arming the drip would stretch one attempt to ~5 s; the absolute deadline
      // must keep it near 500 ms (margin covers scheduling plus one retried attempt).
      assertTrue(elapsedMillis < 4000,
          "slow-drip SOCKS5 handshake outlived the connect timeout: " + elapsedMillis + "ms");
    } finally {
      dripper.close();
      for (Socket socket : accepted) {
        try {
          socket.close();
        } catch (IOException ignored) {
        }
      }
    }
  }

  /**
   * Speaks just enough SOCKS5 to keep a per-read-timeout client interested: answers the greeting
   * with a "no auth" selection, consumes the CONNECT request, then drips the success reply — one
   * byte every {@code pauseMillis}, each pause below a naive per-read timeout.
   */
  private static void dripHandshake(Socket socket, long pauseMillis) {
    try (socket) {
      InputStream in = socket.getInputStream();
      OutputStream out = socket.getOutputStream();
      byte[] greeting = in.readNBytes(3);
      if (greeting.length < 3 || greeting[0] != 0x05) {
        return;
      }
      out.write(0x05);
      out.flush();
      Thread.sleep(pauseMillis);
      out.write(0x00);
      out.flush();
      // CONNECT request: ver/cmd/rsv/atyp plus address and port.
      Thread.sleep(pauseMillis);
      int atyp = in.readNBytes(4)[3] & 0xFF;
      int addressBytes = switch (atyp) {
        case 0x01 -> 4;
        case 0x04 -> 16;
        case 0x03 -> 1 + (in.readNBytes(1)[0] & 0xFF);
        default -> 0;
      };
      in.readNBytes(addressBytes + 2);
      byte[] reply = {0x05, 0x00, 0x00, 0x01, 0, 0, 0, 0, 0, 0};
      for (byte b : reply) {
        Thread.sleep(pauseMillis);
        out.write(b);
        out.flush();
      }
    } catch (IOException | InterruptedException | ArrayIndexOutOfBoundsException ignored) {
      // client gave up — expected once the deadline fires
    }
  }

  @Test
  void socksClientTunnelsAndAuthenticatesThroughConfiguredProxy() throws Exception {
    // An in-process SOCKS5 server that demands RFC 1929 auth and records the credential it receives.
    // The tunnel is established by the client's own Socks5TunnelSocket, which must supply this
    // config's credentials during the handshake.
    RecordingSocksServer server = new RecordingSocksServer("alice", "s3cret");
    server.start();
    try (ProxiedHttpClientFactory factory = new ProxiedHttpClientFactory(60000, 10000)) {
      OutboundProxyConfig config = new OutboundProxyConfig(
          OutboundProxyConfig.Type.SOCKS, "127.0.0.1", server.port(), "alice", "s3cret");
      try (CloseableHttpClient client = factory.clientFor("repo-a", config)) {
        // "localhost" resolves locally; the tunnel to it succeeds at the SOCKS layer and the server
        // then closes, so the HTTP exchange itself fails — only the handshake matters here.
        assertThrows(IOException.class, () ->
            factory.execute("repo-a", config, "GET", target("http://localhost/"),
                java.util.Map.of(), 5000));
      }
      assertTrue(server.awaitHandshake(), "SOCKS server never saw an authentication attempt");
      assertOnlyCredentials(server, "alice", "s3cret");
      assertTrue(!server.targetAddressTypes().isEmpty(), "SOCKS server never saw CONNECT");
      assertTrue(server.targetAddressTypes().stream().allMatch(type -> type != 0x03),
          "SOCKS CONNECT must use a policy-approved IP, not ask the proxy to resolve a domain: "
              + server.targetAddressTypes());
    } finally {
      server.stop();
    }
  }

  @Test
  void socksCredentialsAreIsolatedPerProxyEndpoint() throws Exception {
    // Two SOCKS proxies on different ports with different accounts. Each must receive its own
    // credentials — the dispatcher must not let one repository's config overwrite the other's.
    RecordingSocksServer first = new RecordingSocksServer("user-one", "pw-one");
    RecordingSocksServer second = new RecordingSocksServer("user-two", "pw-two");
    first.start();
    second.start();
    try (ProxiedHttpClientFactory factory = new ProxiedHttpClientFactory(60000, 10000)) {
      OutboundProxyConfig configOne = new OutboundProxyConfig(
          OutboundProxyConfig.Type.SOCKS, "127.0.0.1", first.port(), "user-one", "pw-one");
      OutboundProxyConfig configTwo = new OutboundProxyConfig(
          OutboundProxyConfig.Type.SOCKS, "127.0.0.1", second.port(), "user-two", "pw-two");
      try (CloseableHttpClient ignored = factory.clientFor("repo-one", configOne);
          CloseableHttpClient ignored2 = factory.clientFor("repo-two", configTwo)) {
        assertThrows(IOException.class, () ->
            factory.execute("repo-one", configOne, "GET", target("http://localhost/"),
                java.util.Map.of(), 5000));
        assertThrows(IOException.class, () ->
            factory.execute("repo-two", configTwo, "GET", target("http://localhost/"),
                java.util.Map.of(), 5000));
      }
      assertTrue(first.awaitHandshake());
      assertTrue(second.awaitHandshake());
      // Apache retries across resolved addresses (IPv4/IPv6), so a proxy may see several auth
      // attempts; what matters is that every attempt carried this proxy's own credentials.
      assertOnlyCredentials(first, "user-one", "pw-one");
      assertOnlyCredentials(second, "user-two", "pw-two");
    } finally {
      first.stop();
      second.stop();
    }
  }

  @Test
  void socksCredentialsAreClientScopedOnSharedEndpoint() throws Exception {
    // Two repositories sharing one SOCKS host:port with different accounts. The second client is
    // built before the first one is exercised, so a static host:port-keyed credential map (the old
    // implementation) would have overwritten the first account. Every recorded attempt must pair
    // each username with its own password.
    RecordingSocksServer server = RecordingSocksServer.acceptingAny();
    server.start();
    try (ProxiedHttpClientFactory factory = new ProxiedHttpClientFactory(60000, 10000)) {
      OutboundProxyConfig alice = new OutboundProxyConfig(
          OutboundProxyConfig.Type.SOCKS, "127.0.0.1", server.port(), "alice", "pw-a");
      OutboundProxyConfig bob = new OutboundProxyConfig(
          OutboundProxyConfig.Type.SOCKS, "127.0.0.1", server.port(), "bob", "pw-b");
      try (CloseableHttpClient clientA = factory.clientFor("repo-alice", alice);
          CloseableHttpClient clientB = factory.clientFor("repo-bob", bob)) {
        assertNotSame(clientA, clientB);
        assertThrows(IOException.class, () ->
            factory.execute("repo-alice", alice, "GET", target("http://localhost/"),
                java.util.Map.of(), 5000));
        assertThrows(IOException.class, () ->
            factory.execute("repo-bob", bob, "GET", target("http://localhost/"),
                java.util.Map.of(), 5000));
      }
      assertTrue(server.awaitHandshake());
      assertTrue(server.attempts().stream().anyMatch("alice/pw-a"::equals),
          "alice's client never offered her own credentials: " + server.attempts());
      assertTrue(server.attempts().stream().anyMatch("bob/pw-b"::equals),
          "bob's client never offered his own credentials: " + server.attempts());
      assertTrue(server.attempts().stream()
              .allMatch(pair -> pair.equals("alice/pw-a") || pair.equals("bob/pw-b")),
          "cross-wired credential pair observed: " + server.attempts());
    } finally {
      server.stop();
    }
  }

  @Test
  void clearedSocksCredentialsLeaveNoJvmGlobalResidue() throws Exception {
    // A repository that switches from authenticated SOCKS to anonymous (or is deleted) must not
    // leave its account behind in any static state, and the JVM-wide Authenticator must never be
    // replaced — otherwise unrelated code in the same JVM would consult our dispatcher.
    java.net.Authenticator defaultBefore = java.net.Authenticator.getDefault();
    RecordingSocksServer server = RecordingSocksServer.acceptingAny();
    server.start();
    try (ProxiedHttpClientFactory factory = new ProxiedHttpClientFactory(60000, 10000)) {
      OutboundProxyConfig authed = new OutboundProxyConfig(
          OutboundProxyConfig.Type.SOCKS, "127.0.0.1", server.port(), "alice", "pw-a");
      OutboundProxyConfig anonymous = new OutboundProxyConfig(
          OutboundProxyConfig.Type.SOCKS, "127.0.0.1", server.port(), null, null);
      try (CloseableHttpClient ignored = factory.clientFor("repo-a", authed)) {
        assertThrows(IOException.class, () ->
            factory.execute("repo-a", authed, "GET", target("http://localhost/"),
                java.util.Map.of(), 5000));
      }
      int authedAttempts = server.attempts().size();
      assertTrue(authedAttempts >= 1, "expected at least one authenticated attempt");
      // RepositoryService evicts the cached client on update/delete; after that the cleared
      // repository fetches anonymously and the stale account must never be offered again.
      factory.invalidate("repo-a", authed);
      try (CloseableHttpClient ignored = factory.clientFor("repo-a", anonymous)) {
        assertThrows(IOException.class, () ->
            factory.execute("repo-a", anonymous, "GET", target("http://localhost/"),
                java.util.Map.of(), 5000));
      }
      assertEquals(authedAttempts, server.attempts().size(),
          "stale credentials were offered after the switch to anonymous: " + server.attempts());
      assertTrue(server.selectedMethods().contains(0x00),
          "expected an anonymous SOCKS5 handshake, methods: " + server.selectedMethods());
    } finally {
      server.stop();
    }
    assertSame(defaultBefore, java.net.Authenticator.getDefault(),
        "authenticated SOCKS must not install a JVM-global Authenticator");
  }

  private static void assertOnlyCredentials(
      RecordingSocksServer server, String expectedUser, String expectedPass) {
    assertTrue(server.usernames().size() >= 1, "expected at least one auth attempt");
    assertTrue(server.usernames().stream().allMatch(expectedUser::equals),
        "unexpected usernames: " + server.usernames());
    assertTrue(server.passwords().stream().allMatch(expectedPass::equals),
        "unexpected passwords: " + server.passwords());
  }

  @Test
  void httpProxyReceivesAbsoluteFormRequestsAndStreamsResponse() throws Exception {
    try (FakeHttpProxyServer proxy = FakeHttpProxyServer.start(request ->
            FakeHttpProxyServer.FakeResponse.bytes(200,
                Map.of("Content-Type", "text/plain"),
                "proxied-body".getBytes(StandardCharsets.UTF_8)));
        ProxiedHttpClientFactory factory = new ProxiedHttpClientFactory(60000, 10000)) {
      OutboundProxyConfig config =
          new OutboundProxyConfig(OutboundProxyConfig.Type.HTTP, "127.0.0.1", proxy.port(), null, null);
      ResolvedHttpTarget target =
          target("http://localhost/org/foo/1.0/foo-1.0.jar");
      try (ProxiedHttpClientFactory.ProxiedResponse response = factory.execute(
          "repo-a",
          config,
          "GET",
          target,
          Map.of("X-Test", "yes"),
          5000)) {
        assertEquals(200, response.status());
        assertEquals("text/plain", response.header("content-type"));
        assertEquals("proxied-body",
            new String(response.body().readAllBytes(), StandardCharsets.UTF_8));
      }
      FakeHttpProxyServer.RecordedRequest recorded = proxy.requests().get(0);
      assertEquals("GET", recorded.method());
      assertPinnedProxyTarget(target, recorded);
      assertEquals("localhost", recorded.header("Host"));
      assertEquals("yes", recorded.header("X-Test"));
    }
  }

  @Test
  void httpProxyHeadRequestReturnsEntityLessResponse() throws Exception {
    try (FakeHttpProxyServer proxy = FakeHttpProxyServer.start(request ->
            FakeHttpProxyServer.FakeResponse.status(200, Map.of("ETag", "\"abc\"")));
        ProxiedHttpClientFactory factory = new ProxiedHttpClientFactory(60000, 10000)) {
      OutboundProxyConfig config =
          new OutboundProxyConfig(OutboundProxyConfig.Type.HTTP, "127.0.0.1", proxy.port(), null, null);
      try (ProxiedHttpClientFactory.ProxiedResponse response = factory.execute(
          "repo-a", config, "HEAD", target("http://localhost/artifact"), Map.of(), 5000)) {
        assertEquals(200, response.status());
        assertEquals("\"abc\"", response.header("etag"));
        assertEquals(0, response.body().readAllBytes().length);
      }
      assertEquals("HEAD", proxy.requests().get(0).method());
    }
  }

  @Test
  void httpProxyCredentialsAnswerProxyAuthenticationChallenge() throws Exception {
    try (FakeHttpProxyServer proxy = FakeHttpProxyServer.start(request -> {
          if (request.header("Proxy-Authorization") == null) {
            return FakeHttpProxyServer.FakeResponse.status(407,
                Map.of("Proxy-Authenticate", "Basic realm=\"fake-proxy\""));
          }
          return FakeHttpProxyServer.FakeResponse.bytes(200, Map.of(),
              "ok".getBytes(StandardCharsets.UTF_8));
        });
        ProxiedHttpClientFactory factory = new ProxiedHttpClientFactory(60000, 10000)) {
      OutboundProxyConfig config = new OutboundProxyConfig(
          OutboundProxyConfig.Type.HTTP, "127.0.0.1", proxy.port(), "proxy-user", "proxy-pass");
      try (ProxiedHttpClientFactory.ProxiedResponse response = factory.execute(
          "repo-a", config, "GET", target("http://localhost/artifact"), Map.of(), 5000)) {
        assertEquals(200, response.status());
      }
      assertEquals(2, proxy.requests().size());
      assertNull(proxy.requests().get(0).header("Proxy-Authorization"));
      String expected = "Basic " + Base64.getEncoder()
          .encodeToString("proxy-user:proxy-pass".getBytes(StandardCharsets.UTF_8));
      assertEquals(expected, proxy.requests().get(1).header("Proxy-Authorization"));
    }
  }

  @Test
  void httpProxyCredentialsAreIsolatedPerProxyEndpoint() throws Exception {
    try (FakeHttpProxyServer first = FakeHttpProxyServer.start(challengeResponder());
        FakeHttpProxyServer second = FakeHttpProxyServer.start(challengeResponder());
        ProxiedHttpClientFactory factory = new ProxiedHttpClientFactory(60000, 10000)) {
      OutboundProxyConfig configOne = new OutboundProxyConfig(
          OutboundProxyConfig.Type.HTTP, "127.0.0.1", first.port(), "user-one", "pw-one");
      OutboundProxyConfig configTwo = new OutboundProxyConfig(
          OutboundProxyConfig.Type.HTTP, "127.0.0.1", second.port(), "user-two", "pw-two");
      try (ProxiedHttpClientFactory.ProxiedResponse ignored = factory.execute(
              "repo-one", configOne, "GET", target("http://localhost/a"), Map.of(), 5000);
          ProxiedHttpClientFactory.ProxiedResponse ignored2 = factory.execute(
              "repo-two", configTwo, "GET", target("http://localhost/b"), Map.of(), 5000)) {
        assertEquals(200, ignored.status());
        assertEquals(200, ignored2.status());
      }
      assertEquals(basic("user-one", "pw-one"),
          first.requests().get(first.requests().size() - 1).header("Proxy-Authorization"));
      assertEquals(basic("user-two", "pw-two"),
          second.requests().get(second.requests().size() - 1).header("Proxy-Authorization"));
    }
  }

  private static FakeHttpProxyServer.Responder challengeResponder() {
    return request -> request.header("Proxy-Authorization") == null
        ? FakeHttpProxyServer.FakeResponse.status(407,
            Map.of("Proxy-Authenticate", "Basic realm=\"fake-proxy\""))
        : FakeHttpProxyServer.FakeResponse.bytes(200, Map.of(),
            "ok".getBytes(StandardCharsets.UTF_8));
  }

  private static String basic(String username, String password) {
    return "Basic " + Base64.getEncoder()
        .encodeToString((username + ":" + password).getBytes(StandardCharsets.UTF_8));
  }

  @Test
  void redirectsAreReturnedToCallerInsteadOfFollowedAutomatically() throws Exception {
    try (FakeHttpProxyServer proxy = FakeHttpProxyServer.start(request ->
            FakeHttpProxyServer.FakeResponse.status(302,
                Map.of("Location", "http://elsewhere.example.com/redirected")));
        ProxiedHttpClientFactory factory = new ProxiedHttpClientFactory(60000, 10000)) {
      OutboundProxyConfig config =
          new OutboundProxyConfig(OutboundProxyConfig.Type.HTTP, "127.0.0.1", proxy.port(), null, null);
      try (ProxiedHttpClientFactory.ProxiedResponse response = factory.execute(
          "repo-a", config, "GET", target("http://localhost/original"), Map.of(), 5000)) {
        assertEquals(302, response.status());
        assertEquals("http://elsewhere.example.com/redirected", response.header("Location"));
      }
      assertEquals(1, proxy.requests().size(), "redirect must not be followed automatically");
    }
  }

  @Test
  void httpsTargetsAreTunnelledViaConnect() throws Exception {
    try (FakeHttpProxyServer proxy = FakeHttpProxyServer.start(request ->
            FakeHttpProxyServer.FakeResponse.status(500, Map.of()));
        ProxiedHttpClientFactory factory = new ProxiedHttpClientFactory(60000, 10000)) {
      OutboundProxyConfig config =
          new OutboundProxyConfig(OutboundProxyConfig.Type.HTTP, "127.0.0.1", proxy.port(), null, null);
      // The fake proxy answers CONNECT and then drops the tunnel, so the TLS handshake fails —
      // only the CONNECT request line matters here.
      ResolvedHttpTarget target = target("https://localhost:8443/path");
      assertThrows(IOException.class, () -> factory.execute(
          "repo-a", config, "GET", target, Map.of(), 5000));
      FakeHttpProxyServer.RecordedRequest connect = proxy.requests().get(0);
      assertEquals("CONNECT", connect.method());
      assertEquals(target.addresses().get(0),
          java.net.InetAddress.getByName(URI.create("http://" + connect.target()).getHost()));
      assertTrue(connect.target().endsWith(":8443"));
    }
  }

  @Test
  void idleEvictionClosesStaleClients() throws Exception {
    ProxiedHttpClientFactory factory = new ProxiedHttpClientFactory(100, 10000);
    try {
      OutboundProxyConfig stale =
          new OutboundProxyConfig(OutboundProxyConfig.Type.HTTP, "127.0.0.1", 1, null, null);
      OutboundProxyConfig active =
          new OutboundProxyConfig(OutboundProxyConfig.Type.HTTP, "127.0.0.1", 2, null, null);
      CloseableHttpClient staleClient = factory.clientFor("repo-stale", stale);
      assertNotNull(staleClient);
      Thread.sleep(1200);
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
      while (System.nanoTime() < deadline) {
        factory.clientFor("repo-active", active); // touching the cache runs eviction maintenance
        try {
          probeClient(staleClient);
        } catch (IllegalStateException closed) {
          return;
        } catch (IOException stillOpen) {
          // pool not shut down yet
        }
        Thread.sleep(50);
      }
      fail("stale client was not closed after the idle TTL expired");
    } finally {
      factory.close();
    }
  }

  @Test
  void invalidateEvictsAndClosesCachedClient() throws Exception {
    try (ProxiedHttpClientFactory factory = new ProxiedHttpClientFactory(60000, 10000)) {
      OutboundProxyConfig config =
          new OutboundProxyConfig(OutboundProxyConfig.Type.HTTP, "10.0.0.8", 7890, "u", "pw");
      CloseableHttpClient cached = factory.clientFor("repo-a", config);
      assertSame(cached, factory.clientFor("repo-a", config));
      factory.invalidate("repo-a", config);
      assertNotSame(cached, factory.clientFor("repo-a", config),
          "invalidate must drop the cached client so the next lookup rebuilds it");
      assertThrows(IllegalStateException.class, () ->
          probeClient(cached));
    }
  }

  @Test
  void invalidateIsScopedToTheOwningRepository() throws Exception {
    // Two repositories with identical proxy settings get independent clients; evicting one
    // repository's client (update/delete) must not close the other repository's pool, which may
    // still be streaming an in-flight response.
    try (ProxiedHttpClientFactory factory = new ProxiedHttpClientFactory(60000, 10000)) {
      OutboundProxyConfig config =
          new OutboundProxyConfig(OutboundProxyConfig.Type.HTTP, "10.0.0.9", 7890, "u", "pw");
      OutboundProxyConfig identical =
          new OutboundProxyConfig(OutboundProxyConfig.Type.HTTP, "10.0.0.9", 7890, "u", "pw");
      CloseableHttpClient clientA = factory.clientFor("repo-a", config);
      CloseableHttpClient clientB = factory.clientFor("repo-b", identical);
      assertNotSame(clientA, clientB,
          "repositories sharing a proxy config must not share one pooled client");

      factory.invalidate("repo-a", config);

      assertSame(clientB, factory.clientFor("repo-b", identical),
          "invalidating repo-a must keep repo-b's pooled client alive");
      assertNotSame(clientA, factory.clientFor("repo-a", config),
          "repo-a's own client must be rebuilt after invalidation");
    }
  }

  @Test
  void invalidateIgnoresNullAndDisabledConfigs() throws Exception {
    try (ProxiedHttpClientFactory factory = new ProxiedHttpClientFactory(60000, 10000)) {
      factory.invalidate("repo-a", null);
      factory.invalidate("repo-a",
          new OutboundProxyConfig(OutboundProxyConfig.Type.HTTP, "", 0, null, null));
    }
  }

  @Test
  void shutdownClosesCachedClients() throws Exception {
    ProxiedHttpClientFactory factory = new ProxiedHttpClientFactory(60000, 10000);
    OutboundProxyConfig config =
        new OutboundProxyConfig(OutboundProxyConfig.Type.HTTP, "127.0.0.1", 7890, null, null);
    CloseableHttpClient client = factory.clientFor("repo-a", config);
    assertNotNull(client);
    factory.close();
    // After close the client must be shut down: executing against it throws IllegalStateException.
    assertThrows(IllegalStateException.class, () ->
        probeClient(client));
  }

  private static void probeClient(CloseableHttpClient client) throws IOException {
    java.net.InetAddress address = java.net.InetAddress.getLoopbackAddress();
    HttpHost target = new HttpHost("http", address, address.getHostAddress(), 1);
    try (var response = client.executeOpen(
        target, new BasicClassicHttpRequest("GET", "/"), null)) {
      // A live client may reach a test listener; closing is enough for this lifecycle probe.
    }
  }

  private static ResolvedHttpTarget target(String url) {
    return OutboundRequestPolicy.allowPrivateForTests()
        .resolveHttpTarget(url, "outbound client test");
  }

  private static void assertPinnedProxyTarget(
      ResolvedHttpTarget target, FakeHttpProxyServer.RecordedRequest recorded)
      throws Exception {
    URI routed = URI.create(recorded.target());
    assertEquals(target.addresses().get(0), java.net.InetAddress.getByName(routed.getHost()));
    assertEquals(target.uri().getRawPath(), routed.getRawPath());
    assertEquals(target.uri().getRawQuery(), routed.getRawQuery());
  }

  /**
   * Minimal single-shot SOCKS5 server that records each handshake, answers CONNECT with success,
   * then closes (the client fails afterwards — fine). In the default mode it demands RFC 1929
   * username/password auth for one expected account; {@link #acceptingAny()} also accepts any
   * credentials and anonymous (0x00) handshakes, so tests can observe exactly what each client
   * offered.
   */
  private static final class RecordingSocksServer {
    private final String expectedUser;
    private final String expectedPass;
    private final List<String> usernames = new CopyOnWriteArrayList<>();
    private final List<String> passwords = new CopyOnWriteArrayList<>();
    private final List<String> attempts = new CopyOnWriteArrayList<>();
    private final List<Integer> selectedMethods = new CopyOnWriteArrayList<>();
    private final List<Integer> targetAddressTypes = new CopyOnWriteArrayList<>();
    private final CountDownLatch handshakeSeen = new CountDownLatch(1);
    private ServerSocket serverSocket;
    private Thread thread;
    private volatile boolean running;

    RecordingSocksServer(String expectedUser, String expectedPass) {
      this.expectedUser = expectedUser;
      this.expectedPass = expectedPass;
    }

    static RecordingSocksServer acceptingAny() {
      return new RecordingSocksServer(null, null);
    }

    void start() throws IOException {
      serverSocket = new ServerSocket(0, 50, java.net.InetAddress.getByName("127.0.0.1"));
      running = true;
      thread = new Thread(this::serve, "recording-socks-server");
      thread.setDaemon(true);
      thread.start();
    }

    int port() {
      return serverSocket.getLocalPort();
    }

    List<String> usernames() {
      return usernames;
    }

    List<String> passwords() {
      return passwords;
    }

    /** Recorded {@code username/password} pairs, one per RFC 1929 attempt. */
    List<String> attempts() {
      return attempts;
    }

    /** Auth method the server selected per handshake (0x00 anonymous, 0x02 username/password). */
    List<Integer> selectedMethods() {
      return selectedMethods;
    }

    /** SOCKS5 address types used by CONNECT (0x01 IPv4, 0x03 domain, 0x04 IPv6). */
    List<Integer> targetAddressTypes() {
      return targetAddressTypes;
    }

    boolean awaitHandshake() throws InterruptedException {
      return handshakeSeen.await(10, TimeUnit.SECONDS);
    }

    void stop() {
      running = false;
      try {
        serverSocket.close();
      } catch (IOException ignored) {
      }
    }

    private void serve() {
      while (running) {
        try {
          Socket socket = serverSocket.accept();
          handle(socket);
        } catch (IOException e) {
          return; // stopped
        }
      }
    }

    private void handle(Socket socket) {
      try (socket) {
        InputStream in = socket.getInputStream();
        OutputStream out = socket.getOutputStream();
        int ver = in.read();
        int nMethods = in.read();
        if (ver != 0x05 || nMethods < 0) {
          return;
        }
        byte[] methods = in.readNBytes(nMethods);
        boolean offersUserPass = false;
        boolean offersNone = false;
        for (byte m : methods) {
          if (m == 0x02) {
            offersUserPass = true;
          }
          if (m == 0x00) {
            offersNone = true;
          }
        }
        boolean requireAuth = expectedUser != null;
        if (offersUserPass) {
          selectedMethods.add(0x02);
          out.write(new byte[] {0x05, 0x02});
          out.flush();
          // RFC 1929 sub-negotiation.
          int authVer = in.read();
          int ulen = in.read();
          String user = new String(in.readNBytes(Math.max(0, ulen)), StandardCharsets.UTF_8);
          int plen = in.read();
          String pass = new String(in.readNBytes(Math.max(0, plen)), StandardCharsets.UTF_8);
          usernames.add(user);
          passwords.add(pass);
          attempts.add(user + "/" + pass);
          handshakeSeen.countDown();
          boolean ok = !requireAuth
              || (authVer == 0x01 && user.equals(expectedUser) && pass.equals(expectedPass));
          out.write(new byte[] {0x01, (byte) (ok ? 0x00 : 0x01)});
          out.flush();
          if (!ok) {
            return;
          }
        } else if (offersNone && !requireAuth) {
          selectedMethods.add(0x00);
          out.write(new byte[] {0x05, 0x00});
          out.flush();
          handshakeSeen.countDown();
        } else {
          out.write(new byte[] {0x05, (byte) 0xFF});
          out.flush();
          return;
        }
        // CONNECT request.
        in.read(); // ver
        in.read(); // cmd
        in.read(); // rsv
        int atyp = in.read();
        targetAddressTypes.add(atyp);
        if (atyp == 0x03) {
          int len = in.read();
          in.readNBytes(Math.max(0, len));
        } else if (atyp == 0x01) {
          in.readNBytes(4);
        } else if (atyp == 0x04) {
          in.readNBytes(16);
        }
        in.read();
        in.read();
        // Reply success (IPv4 0.0.0.0:0), then close so the client's HTTP read fails.
        out.write(new byte[] {0x05, 0x00, 0x00, 0x01, 0, 0, 0, 0, 0, 0});
        out.flush();
      } catch (IOException ignored) {
      }
    }
  }
}
