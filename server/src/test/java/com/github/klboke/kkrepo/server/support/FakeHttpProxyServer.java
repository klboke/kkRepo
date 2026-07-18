package com.github.klboke.kkrepo.server.support;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * In-process fake HTTP proxy for tests. Records every request it receives (request line in
 * absolute or CONNECT form plus headers) and answers plain-HTTP requests through a
 * {@link Responder}. By default, CONNECT requests get a canned "200 Connection established" and
 * the tunnel is then dropped, which is enough to assert that HTTPS targets use CONNECT. Tests that
 * need a real TLS exchange can provide a {@link ConnectTargetResolver} to relay the tunnel to a
 * loopback TLS server.
 */
public final class FakeHttpProxyServer implements AutoCloseable {

  public record RecordedRequest(
      String method, String target, Map<String, List<String>> headers) {
    public String header(String name) {
      for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
        if (entry.getKey().equalsIgnoreCase(name) && !entry.getValue().isEmpty()) {
          return entry.getValue().get(0);
        }
      }
      return null;
    }
  }

  public record FakeResponse(int status, Map<String, String> headers, byte[] body) {
    public static FakeResponse bytes(int status, Map<String, String> headers, byte[] body) {
      return new FakeResponse(status, headers, body);
    }

    public static FakeResponse status(int status, Map<String, String> headers) {
      return new FakeResponse(status, headers, new byte[0]);
    }
  }

  @FunctionalInterface
  public interface Responder {
    /** Returning {@code null} drops the connection without any response. */
    FakeResponse respond(RecordedRequest request) throws IOException;
  }

  @FunctionalInterface
  public interface ConnectTargetResolver {
    InetSocketAddress resolve(RecordedRequest request) throws IOException;
  }

  private final ServerSocket serverSocket;
  private final Responder responder;
  private final ConnectTargetResolver connectTargetResolver;
  private final List<RecordedRequest> requests = new CopyOnWriteArrayList<>();
  private volatile boolean running = true;

  private FakeHttpProxyServer(
      Responder responder,
      ConnectTargetResolver connectTargetResolver) throws IOException {
    this.responder = responder;
    this.connectTargetResolver = connectTargetResolver;
    this.serverSocket = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"));
    Thread acceptor = new Thread(this::acceptLoop, "fake-http-proxy-accept");
    acceptor.setDaemon(true);
    acceptor.start();
  }

  public static FakeHttpProxyServer start(Responder responder) throws IOException {
    return new FakeHttpProxyServer(responder, null);
  }

  public static FakeHttpProxyServer startTunneling(
      Responder responder,
      ConnectTargetResolver connectTargetResolver) throws IOException {
    return new FakeHttpProxyServer(responder, connectTargetResolver);
  }

  public int port() {
    return serverSocket.getLocalPort();
  }

  public List<RecordedRequest> requests() {
    return List.copyOf(requests);
  }

  @Override
  public void close() {
    running = false;
    try {
      serverSocket.close();
    } catch (IOException ignored) {
    }
  }

  private void acceptLoop() {
    while (running) {
      try {
        Socket socket = serverSocket.accept();
        Thread handler = new Thread(() -> handle(socket), "fake-http-proxy-handler");
        handler.setDaemon(true);
        handler.start();
      } catch (IOException e) {
        return;
      }
    }
  }

  private void handle(Socket socket) {
    try (socket) {
      socket.setSoTimeout(10000);
      InputStream in = socket.getInputStream();
      RecordedRequest request = readRequest(in);
      if (request == null) {
        return;
      }
      requests.add(request);
      OutputStream out = socket.getOutputStream();
      if ("CONNECT".equalsIgnoreCase(request.method())) {
        if (connectTargetResolver != null) {
          tunnel(socket, in, out, connectTargetResolver.resolve(request));
          return;
        }
        out.write("HTTP/1.1 200 Connection established\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1));
        out.flush();
        drainBriefly(in);
        return;
      }
      FakeResponse response = responder.respond(request);
      if (response == null) {
        return;
      }
      StringBuilder head = new StringBuilder("HTTP/1.1 ")
          .append(response.status())
          .append(' ')
          .append(reason(response.status()))
          .append("\r\n");
      if (response.headers() != null) {
        response.headers().forEach((name, value) ->
            head.append(name).append(": ").append(value).append("\r\n"));
      }
      byte[] body = response.body() == null ? new byte[0] : response.body();
      head.append("Content-Length: ").append(body.length).append("\r\n");
      head.append("Connection: close\r\n\r\n");
      out.write(head.toString().getBytes(StandardCharsets.ISO_8859_1));
      out.write(body);
      out.flush();
    } catch (IOException ignored) {
    }
  }

  private static void tunnel(
      Socket client,
      InputStream clientIn,
      OutputStream clientOut,
      InetSocketAddress target) throws IOException {
    try (Socket upstream = new Socket()) {
      upstream.connect(target, 10000);
      clientOut.write("HTTP/1.1 200 Connection established\r\n\r\n"
          .getBytes(StandardCharsets.ISO_8859_1));
      clientOut.flush();

      Thread requestPipe = new Thread(
          () -> transfer(clientIn, upstream),
          "fake-http-proxy-client-to-upstream");
      requestPipe.setDaemon(true);
      requestPipe.start();
      transfer(upstream.getInputStream(), clientOut);
      try {
        requestPipe.join(1000);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    } finally {
      client.close();
    }
  }

  private static void transfer(InputStream in, Socket target) {
    try {
      transfer(in, target.getOutputStream());
      target.shutdownOutput();
    } catch (IOException ignored) {
    }
  }

  private static void transfer(InputStream in, OutputStream out) {
    byte[] buffer = new byte[8192];
    try {
      int read;
      while ((read = in.read(buffer)) >= 0) {
        out.write(buffer, 0, read);
        out.flush();
      }
    } catch (IOException ignored) {
    }
  }

  private static void drainBriefly(InputStream in) {
    try {
      byte[] buffer = new byte[4096];
      long deadline = System.currentTimeMillis() + 500;
      while (System.currentTimeMillis() < deadline) {
        int available = in.available();
        if (available <= 0) {
          Thread.sleep(20);
          continue;
        }
        if (in.read(buffer, 0, Math.min(available, buffer.length)) < 0) {
          return;
        }
      }
    } catch (IOException | InterruptedException ignored) {
      Thread.currentThread().interrupt();
    }
  }

  private static RecordedRequest readRequest(InputStream in) throws IOException {
    String raw = readHead(in);
    if (raw == null || raw.isBlank()) {
      return null;
    }
    String[] lines = raw.split("\r\n");
    String[] requestLine = lines[0].split(" ", 3);
    if (requestLine.length < 2) {
      return null;
    }
    Map<String, List<String>> headers = new LinkedHashMap<>();
    for (int i = 1; i < lines.length; i++) {
      int colon = lines[i].indexOf(':');
      if (colon <= 0) {
        continue;
      }
      headers.computeIfAbsent(lines[i].substring(0, colon).trim(), key -> new ArrayList<>())
          .add(lines[i].substring(colon + 1).trim());
    }
    return new RecordedRequest(requestLine[0], requestLine[1], headers);
  }

  private static String readHead(InputStream in) throws IOException {
    StringBuilder head = new StringBuilder();
    int matched = 0;
    int[] terminator = {'\r', '\n', '\r', '\n'};
    while (matched < terminator.length) {
      int b;
      try {
        b = in.read();
      } catch (SocketTimeoutException e) {
        return null;
      }
      if (b < 0) {
        throw new EOFException();
      }
      head.append((char) b);
      matched = b == terminator[matched] ? matched + 1 : (b == '\r' ? 1 : 0);
    }
    return head.substring(0, head.length() - 4);
  }

  private static String reason(int status) {
    return switch (status) {
      case 200 -> "OK";
      case 301 -> "Moved Permanently";
      case 302 -> "Found";
      case 303 -> "See Other";
      case 304 -> "Not Modified";
      case 307 -> "Temporary Redirect";
      case 308 -> "Permanent Redirect";
      case 401 -> "Unauthorized";
      case 404 -> "Not Found";
      case 407 -> "Proxy Authentication Required";
      default -> "Status";
    };
  }
}
