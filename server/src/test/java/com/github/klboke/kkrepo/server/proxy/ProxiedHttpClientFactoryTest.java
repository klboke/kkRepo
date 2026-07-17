package com.github.klboke.kkrepo.server.proxy;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.junit.jupiter.api.Test;

class ProxiedHttpClientFactoryTest {

  @Test
  void clientForAcceptsUsernameOnlyHttpProxyWithoutPassword() throws Exception {
    try (ProxiedHttpClientFactory factory = new ProxiedHttpClientFactory(60000, 10000)) {
      // authenticated() is true (username present) but the password is null — previously this threw an
      // NPE inside buildHttp because config.password().toCharArray() was dereferenced unconditionally.
      OutboundProxyConfig usernameOnly =
          new OutboundProxyConfig(OutboundProxyConfig.Type.HTTP, "127.0.0.1", 7890, "clash-user", null);
      try (CloseableHttpClient client = factory.clientFor(usernameOnly)) {
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
      CloseableHttpClient same = factory.clientFor(a);
      assertSame(same, factory.clientFor(a));
      // A credential rotation must produce a distinct client, not reuse the stale one.
      assertNotSame(same, factory.clientFor(b));
    }
  }

  @Test
  void socksClientTunnelsAndAuthenticatesThroughConfiguredProxy() throws Exception {
    // An in-process SOCKS5 server that demands RFC 1929 auth and records the credential it receives.
    // The request is tunnelled through the JDK's native SOCKS support; the factory's dispatcher
    // authenticator must supply this config's credentials to the JDK.
    RecordingSocksServer server = new RecordingSocksServer("alice", "s3cret");
    server.start();
    try (ProxiedHttpClientFactory factory = new ProxiedHttpClientFactory(60000, 10000)) {
      OutboundProxyConfig config = new OutboundProxyConfig(
          OutboundProxyConfig.Type.SOCKS, "127.0.0.1", server.port(), "alice", "s3cret");
      try (CloseableHttpClient client = factory.clientFor(config)) {
        // "localhost" resolves locally; the tunnel to it succeeds at the SOCKS layer and the server
        // then closes, so the HTTP exchange itself fails — only the handshake matters here.
        assertThrows(IOException.class, () ->
            factory.execute(config, "GET", java.net.URI.create("http://localhost/"),
                java.util.Map.of(), 5000));
      }
      assertTrue(server.awaitHandshake(), "SOCKS server never saw an authentication attempt");
      assertOnlyCredentials(server, "alice", "s3cret");
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
      try (CloseableHttpClient ignored = factory.clientFor(configOne);
          CloseableHttpClient ignored2 = factory.clientFor(configTwo)) {
        assertThrows(IOException.class, () ->
            factory.execute(configOne, "GET", java.net.URI.create("http://localhost/"),
                java.util.Map.of(), 5000));
        assertThrows(IOException.class, () ->
            factory.execute(configTwo, "GET", java.net.URI.create("http://localhost/"),
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

  private static void assertOnlyCredentials(
      RecordingSocksServer server, String expectedUser, String expectedPass) {
    assertTrue(server.usernames().size() >= 1, "expected at least one auth attempt");
    assertTrue(server.usernames().stream().allMatch(expectedUser::equals),
        "unexpected usernames: " + server.usernames());
    assertTrue(server.passwords().stream().allMatch(expectedPass::equals),
        "unexpected passwords: " + server.passwords());
  }

  @Test
  void shutdownClosesCachedClients() throws Exception {
    ProxiedHttpClientFactory factory = new ProxiedHttpClientFactory(60000, 10000);
    OutboundProxyConfig config =
        new OutboundProxyConfig(OutboundProxyConfig.Type.HTTP, "127.0.0.1", 7890, null, null);
    CloseableHttpClient client = factory.clientFor(config);
    assertNotNull(client);
    factory.close();
    // After close the client must be shut down: executing against it throws IllegalStateException.
    assertThrows(IllegalStateException.class, () ->
        client.executeOpen(null, new HttpGet("http://localhost/"), null));
  }

  /**
   * Minimal single-shot SOCKS5 server that demands RFC 1929 username/password auth, records the
   * credentials, answers CONNECT with success, then closes (the client fails afterwards — fine).
   */
  private static final class RecordingSocksServer {
    private final String expectedUser;
    private final String expectedPass;
    private final List<String> usernames = new CopyOnWriteArrayList<>();
    private final List<String> passwords = new CopyOnWriteArrayList<>();
    private final CountDownLatch handshakeSeen = new CountDownLatch(1);
    private ServerSocket serverSocket;
    private Thread thread;
    private volatile boolean running;

    RecordingSocksServer(String expectedUser, String expectedPass) {
      this.expectedUser = expectedUser;
      this.expectedPass = expectedPass;
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
        for (byte m : methods) {
          if (m == 0x02) {
            offersUserPass = true;
          }
        }
        if (!offersUserPass) {
          out.write(new byte[] {0x05, (byte) 0xFF});
          out.flush();
          return;
        }
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
        handshakeSeen.countDown();
        boolean ok = authVer == 0x01 && user.equals(expectedUser) && pass.equals(expectedPass);
        out.write(new byte[] {0x01, (byte) (ok ? 0x00 : 0x01)});
        out.flush();
        if (!ok) {
          return;
        }
        // CONNECT request.
        in.read(); // ver
        in.read(); // cmd
        in.read(); // rsv
        int atyp = in.read();
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
