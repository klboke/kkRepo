package com.github.klboke.kkrepo.server.proxy;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketAddress;
import java.nio.charset.StandardCharsets;

/**
 * A {@link Socket} whose {@link #connect(SocketAddress, int)} first opens a TCP connection to a
 * fixed SOCKS5 proxy and then performs the RFC 1928 CONNECT handshake (optionally with RFC 1929
 * username/password auth) for the requested target.
 *
 * <p>The JDK's own {@code java.net.Proxy(SOCKS)} support only surfaces SOCKS5 credentials through
 * the JVM-global {@link java.net.Authenticator}, which cannot be scoped per repository. This socket
 * carries the proxy address and credentials captured per {@code HttpClient} instance instead, so
 * repositories sharing one SOCKS endpoint with different accounts stay isolated and nothing static
 * is left behind when a client is evicted.
 */
final class Socks5TunnelSocket extends Socket {

  private final InetSocketAddress proxyAddress;
  private final String username;
  private final String password;

  Socks5TunnelSocket(InetSocketAddress proxyAddress, String username, String password) {
    this.proxyAddress = proxyAddress;
    this.username = username;
    this.password = password;
  }

  @Override
  public void connect(SocketAddress endpoint, int timeoutMillis) throws IOException {
    if (!(endpoint instanceof InetSocketAddress target)) {
      throw new IOException("SOCKS5 tunnel requires an InetSocketAddress target, got " + endpoint);
    }
    super.connect(proxyAddress, timeoutMillis);
    try {
      handshake(target);
    } catch (IOException | RuntimeException failure) {
      try {
        close();
      } catch (IOException ignored) {
        // best effort — the tunnel is unusable either way
      }
      throw failure;
    }
  }

  private void handshake(InetSocketAddress target) throws IOException {
    OutputStream out = getOutputStream();
    InputStream in = getInputStream();
    boolean authenticated = username != null;
    int offered = authenticated ? 0x02 : 0x00;
    out.write(0x05);
    out.write(1);
    out.write(offered);
    out.flush();
    int version = readByte(in);
    int method = readByte(in);
    if (version != 0x05) {
      throw new IOException("SOCKS5 proxy " + describeProxy() + " replied with version " + version);
    }
    if (method == 0xFF) {
      throw new IOException(
          "SOCKS5 proxy " + describeProxy() + " accepted no offered authentication method");
    }
    if (method != offered) {
      throw new IOException("SOCKS5 proxy " + describeProxy()
          + " selected unexpected authentication method 0x" + Integer.toHexString(method));
    }
    if (authenticated) {
      authenticate(in, out);
    }
    requestConnect(in, out, target);
  }

  private void authenticate(InputStream in, OutputStream out) throws IOException {
    byte[] user = username.getBytes(StandardCharsets.UTF_8);
    byte[] pass = (password != null ? password : "").getBytes(StandardCharsets.UTF_8);
    if (user.length > 255 || pass.length > 255) {
      throw new IOException("SOCKS5 credentials exceed the 255-byte RFC 1929 limit");
    }
    out.write(0x01);
    out.write(user.length);
    out.write(user);
    out.write(pass.length);
    out.write(pass);
    out.flush();
    readByte(in); // sub-negotiation version
    int status = readByte(in);
    if (status != 0x00) {
      throw new IOException(
          "SOCKS5 proxy " + describeProxy() + " rejected the configured credentials");
    }
  }

  private void requestConnect(InputStream in, OutputStream out, InetSocketAddress target)
      throws IOException {
    out.write(0x05);
    out.write(0x01); // CONNECT
    out.write(0x00);
    InetAddress resolved = target.getAddress();
    String hostString = target.getHostString();
    if (resolved != null && hostString.equals(resolved.getHostAddress())) {
      byte[] address = resolved.getAddress();
      out.write(address.length == 4 ? 0x01 : 0x04);
      out.write(address);
    } else {
      byte[] host = hostString.getBytes(StandardCharsets.UTF_8);
      if (host.length > 255) {
        throw new IOException("SOCKS5 target host name exceeds 255 bytes: " + hostString);
      }
      out.write(0x03);
      out.write(host.length);
      out.write(host);
    }
    int port = target.getPort();
    out.write((port >> 8) & 0xFF);
    out.write(port & 0xFF);
    out.flush();
    int version = readByte(in);
    int reply = readByte(in);
    readByte(in); // reserved
    int addressType = readByte(in);
    if (version != 0x05) {
      throw new IOException("SOCKS5 proxy " + describeProxy() + " replied with version " + version);
    }
    if (reply != 0x00) {
      throw new IOException("SOCKS5 CONNECT to " + hostString + ":" + port + " via "
          + describeProxy() + " failed: " + replyMessage(reply));
    }
    switch (addressType) {
      case 0x01 -> skipFully(in, 4);
      case 0x04 -> skipFully(in, 16);
      case 0x03 -> skipFully(in, readByte(in));
      default -> throw new IOException(
          "SOCKS5 proxy replied with unknown address type " + addressType);
    }
    readByte(in); // bound port, high
    readByte(in); // bound port, low
  }

  private String describeProxy() {
    return proxyAddress.getHostString() + ":" + proxyAddress.getPort();
  }

  private static int readByte(InputStream in) throws IOException {
    int value = in.read();
    if (value < 0) {
      throw new EOFException("SOCKS5 proxy closed the connection during the handshake");
    }
    return value;
  }

  private static void skipFully(InputStream in, int bytes) throws IOException {
    for (int i = 0; i < bytes; i++) {
      readByte(in);
    }
  }

  private static String replyMessage(int reply) {
    return switch (reply) {
      case 0x01 -> "general SOCKS server failure";
      case 0x02 -> "connection not allowed by ruleset";
      case 0x03 -> "network unreachable";
      case 0x04 -> "host unreachable";
      case 0x05 -> "connection refused";
      case 0x06 -> "TTL expired";
      case 0x07 -> "command not supported";
      case 0x08 -> "address type not supported";
      default -> "unknown reply code " + reply;
    };
  }
}
