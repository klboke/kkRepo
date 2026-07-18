package com.github.klboke.kkrepo.server.proxy;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

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
    // One absolute deadline covers the TCP connect plus the complete SOCKS handshake: the budget
    // starts before super.connect so a slow TCP connect cannot be followed by a fresh full
    // handshake timeout. SO_TIMEOUT is re-armed with the remaining budget before every individual
    // blocking read, so a proxy that drips one byte per socket timeout cannot stretch the
    // cumulative greeting/auth/CONNECT reads past the configured connect timeout.
    long deadlineNanos = timeoutMillis > 0
        ? System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
        : 0L;
    super.connect(proxyAddress, timeoutMillis);
    try {
      handshake(target, deadlineNanos);
    } catch (IOException | RuntimeException failure) {
      try {
        close();
      } catch (IOException ignored) {
        // best effort — the tunnel is unusable either way
      }
      throw failure;
    } finally {
      if (deadlineNanos != 0L && !isClosed()) {
        // Hand the established tunnel back with blocking reads; the HTTP client applies its own
        // per-request response timeout from here on.
        setSoTimeout(0);
      }
    }
  }

  private void handshake(InetSocketAddress target, long deadlineNanos) throws IOException {
    OutputStream out = getOutputStream();
    InputStream in = getInputStream();
    boolean authenticated = username != null;
    int offered = authenticated ? 0x02 : 0x00;
    out.write(0x05);
    out.write(1);
    out.write(offered);
    out.flush();
    int version = readByte(in, deadlineNanos);
    int method = readByte(in, deadlineNanos);
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
      authenticate(in, out, deadlineNanos);
    }
    requestConnect(in, out, target, deadlineNanos);
  }

  /**
   * Re-arms SO_TIMEOUT with the time left until the overall connect deadline, so the greeting,
   * authentication and CONNECT-response reads together cannot outlive the configured connect
   * timeout. A {@code deadlineNanos} of 0 means "no deadline" (infinite connect timeout).
   */
  private void armDeadline(long deadlineNanos) throws IOException {
    if (deadlineNanos == 0L) {
      return;
    }
    long remainingNanos = deadlineNanos - System.nanoTime();
    if (remainingNanos <= 0L) {
      throw new SocketTimeoutException(
          "SOCKS5 handshake with " + describeProxy() + " exceeded the connect timeout");
    }
    setSoTimeout((int) Math.min(Integer.MAX_VALUE,
        Math.max(1L, TimeUnit.NANOSECONDS.toMillis(remainingNanos))));
  }

  private void authenticate(InputStream in, OutputStream out, long deadlineNanos)
      throws IOException {
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
    readByte(in, deadlineNanos); // sub-negotiation version
    int status = readByte(in, deadlineNanos);
    if (status != 0x00) {
      throw new IOException(
          "SOCKS5 proxy " + describeProxy() + " rejected the configured credentials");
    }
  }

  private void requestConnect(
      InputStream in, OutputStream out, InetSocketAddress target, long deadlineNanos)
      throws IOException {
    out.write(0x05);
    out.write(0x01); // CONNECT
    out.write(0x00);
    InetAddress resolved = target.getAddress();
    String hostString = target.getHostString();
    if (resolved != null) {
      // A resolved socket address is a policy-approved connection capability. Always serialize its
      // IP bytes so the SOCKS proxy cannot perform a second, potentially rebound DNS lookup.
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
    int version = readByte(in, deadlineNanos);
    int reply = readByte(in, deadlineNanos);
    readByte(in, deadlineNanos); // reserved
    int addressType = readByte(in, deadlineNanos);
    if (version != 0x05) {
      throw new IOException("SOCKS5 proxy " + describeProxy() + " replied with version " + version);
    }
    if (reply != 0x00) {
      throw new IOException("SOCKS5 CONNECT to " + hostString + ":" + port + " via "
          + describeProxy() + " failed: " + replyMessage(reply));
    }
    switch (addressType) {
      case 0x01 -> skipFully(in, 4, deadlineNanos);
      case 0x04 -> skipFully(in, 16, deadlineNanos);
      case 0x03 -> skipFully(in, readByte(in, deadlineNanos), deadlineNanos);
      default -> throw new IOException(
          "SOCKS5 proxy replied with unknown address type " + addressType);
    }
    readByte(in, deadlineNanos); // bound port, high
    readByte(in, deadlineNanos); // bound port, low
  }

  private String describeProxy() {
    return proxyAddress.getHostString() + ":" + proxyAddress.getPort();
  }

  private int readByte(InputStream in, long deadlineNanos) throws IOException {
    armDeadline(deadlineNanos);
    int value = in.read();
    if (value < 0) {
      throw new EOFException("SOCKS5 proxy closed the connection during the handshake");
    }
    return value;
  }

  private void skipFully(InputStream in, int bytes, long deadlineNanos) throws IOException {
    for (int i = 0; i < bytes; i++) {
      readByte(in, deadlineNanos);
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
