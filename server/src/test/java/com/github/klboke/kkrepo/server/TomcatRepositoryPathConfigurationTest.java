package com.github.klboke.kkrepo.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import org.apache.catalina.connector.Connector;
import org.apache.coyote.ContinueResponseTiming;
import org.apache.coyote.http11.AbstractHttp11Protocol;
import org.apache.tomcat.util.buf.EncodedSolidusHandling;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.tomcat.servlet.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServer;

class TomcatRepositoryPathConfigurationTest {

  @Test
  void configuresHttp11ContinueResponseForOnDemandBodyReads() {
    TomcatServletWebServerFactory factory = customizedFactory();
    Connector connector = new Connector(TomcatServletWebServerFactory.DEFAULT_PROTOCOL);

    factory.getConnectorCustomizers().forEach(customizer -> customizer.customize(connector));

    assertSame(
        EncodedSolidusHandling.PASS_THROUGH,
        connector.getEncodedSolidusHandlingInternal());
    AbstractHttp11Protocol<?> http11 = assertInstanceOf(
        AbstractHttp11Protocol.class, connector.getProtocolHandler());
    assertEquals("onRead", http11.getContinueResponseTiming());
    assertSame(
        ContinueResponseTiming.ON_REQUEST_BODY_READ,
        http11.getContinueResponseTimingInternal());
  }

  @Test
  void invalidSwiftPutReceivesFinalErrorBeforeAnyContinueResponse(@TempDir Path tempDir)
      throws Exception {
    TomcatServletWebServerFactory factory = customizedFactory();
    factory.setPort(0);
    factory.setBaseDirectory(tempDir.toFile());
    WebServer server = factory.getWebServer(servletContext -> {
      var registration = servletContext.addServlet("rejectInvalidSwiftPut", new HttpServlet() {
        @Override
        protected void doPut(HttpServletRequest request, HttpServletResponse response) {
          response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
          response.setContentLength(0);
        }
      });
      registration.setLoadOnStartup(1);
      registration.addMapping("/repository/*");
    });

    server.start();
    try (Socket socket = new Socket("127.0.0.1", server.getPort())) {
      socket.setSoTimeout(5_000);
      String request = "PUT /repository/swift-hosted/Acme/Demo/not-semver HTTP/1.1\r\n"
          + "Host: 127.0.0.1:" + server.getPort() + "\r\n"
          + "Expect: 100-continue\r\n"
          + "Content-Type: multipart/form-data; boundary=swift-test\r\n"
          + "Content-Length: 4\r\n"
          + "Connection: close\r\n"
          + "\r\n";
      socket.getOutputStream().write(request.getBytes(StandardCharsets.ISO_8859_1));
      socket.getOutputStream().flush();

      String firstResponse = readHttpHeader(socket);

      assertTrue(firstResponse.startsWith("HTTP/1.1 400"), firstResponse);
      assertFalse(firstResponse.contains("100 Continue"), firstResponse);
    } finally {
      server.stop();
    }
  }

  private static TomcatServletWebServerFactory customizedFactory() {
    TomcatServletWebServerFactory factory = new TomcatServletWebServerFactory();
    new TomcatRepositoryPathConfiguration()
        .repositoryPathTomcatCustomizer()
        .customize(factory);
    return factory;
  }

  private static String readHttpHeader(Socket socket) throws IOException {
    ByteArrayOutputStream header = new ByteArrayOutputStream();
    int matched = 0;
    while (header.size() < 16 * 1024) {
      int next = socket.getInputStream().read();
      if (next < 0) {
        break;
      }
      header.write(next);
      matched = switch (matched) {
        case 0 -> next == '\r' ? 1 : 0;
        case 1 -> next == '\n' ? 2 : next == '\r' ? 1 : 0;
        case 2 -> next == '\r' ? 3 : 0;
        case 3 -> next == '\n' ? 4 : next == '\r' ? 1 : 0;
        default -> 4;
      };
      if (matched == 4) {
        break;
      }
    }
    return header.toString(StandardCharsets.ISO_8859_1);
  }
}
