package com.github.klboke.kkrepo.server.security;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.klboke.kkrepo.server.proxy.ProxiedHttpClientFactory;
import com.sun.net.httpserver.HttpServer;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class OutboundRequestPolicyTest {

  @Test
  void rejectsLoopbackByDefault() {
    OutboundRequestPolicy policy = new OutboundRequestPolicy(false, "");

    assertThrows(SecurityValidationException.class,
        () -> policy.validateHttpUri("http://127.0.0.1:8081/repository/maven-public/", "proxy.remoteUrl"));
  }

  @Test
  void allowedHostsCanOverrideResolvedAddressChecks() {
    OutboundRequestPolicy policy = new OutboundRequestPolicy(false, "localhost");

    assertDoesNotThrow(() ->
        policy.validateHttpUri("http://localhost:8081/repository/maven-public/", "proxy.remoteUrl"));
  }

  @Test
  void allowedHostConfigurationValidationDoesNotRequireDnsButConnectionsDo() {
    OutboundRequestPolicy policy = new OutboundRequestPolicy(
        false,
        "packages.internal",
        host -> {
          throw new UnknownHostException(host);
        });

    assertDoesNotThrow(() ->
        policy.validateHttpUri("https://packages.internal/repository/", "proxy.remoteUrl"));
    assertThrows(SecurityValidationException.class, () ->
        policy.resolveHttpTarget("https://packages.internal/repository/", "remote fetch"));
  }

  @Test
  void rejectsUnsupportedSchemes() {
    OutboundRequestPolicy policy = OutboundRequestPolicy.allowPrivateForTests();

    assertThrows(SecurityValidationException.class,
        () -> policy.validateHttpUri("file:///etc/passwd", "proxy.remoteUrl"));
  }

  @Test
  void rejectsMissingBlankAndMalformedRawUrls() {
    OutboundRequestPolicy policy = OutboundRequestPolicy.allowPrivateForTests();

    assertEquals(
        "remote fetch URL is required",
        assertThrows(
            SecurityValidationException.class,
            () -> policy.resolveHttpTarget((String) null, "remote fetch"))
            .getMessage());
    assertEquals(
        "remote fetch URL is required",
        assertThrows(
            SecurityValidationException.class,
            () -> policy.resolveHttpTarget("  ", "remote fetch"))
            .getMessage());
    SecurityValidationException malformed = assertThrows(
        SecurityValidationException.class,
        () -> policy.resolveHttpTarget("http://[", "remote fetch"));
    assertTrue(malformed.getMessage().startsWith("remote fetch URL is not valid:"));
  }

  @Test
  void rejectsResolverAnswersThatCannotIssueAConnectionCapability() {
    OutboundRequestPolicy nullAnswer = new OutboundRequestPolicy(
        false, "packages.example", host -> null);
    OutboundRequestPolicy emptyAnswer = new OutboundRequestPolicy(
        false, "packages.example", host -> new InetAddress[0]);
    OutboundRequestPolicy nullAddress = new OutboundRequestPolicy(
        false, "packages.example", host -> new InetAddress[] {null});

    assertThrows(SecurityValidationException.class, () ->
        nullAnswer.resolveHttpTarget("https://packages.example/artifact", "remote fetch"));
    assertThrows(SecurityValidationException.class, () ->
        emptyAnswer.resolveHttpTarget("https://packages.example/artifact", "remote fetch"));
    assertThrows(SecurityValidationException.class, () ->
        nullAddress.resolveHttpTarget("https://packages.example/artifact", "remote fetch"));
  }

  @Test
  void rejectsInvalidUriComponentsBeforeDnsResolution() {
    AtomicInteger lookups = new AtomicInteger();
    OutboundRequestPolicy policy = new OutboundRequestPolicy(
        true,
        "",
        host -> {
          lookups.incrementAndGet();
          return new InetAddress[] {InetAddress.getLoopbackAddress()};
        });

    assertThrows(SecurityValidationException.class, () ->
        policy.resolveHttpTarget((URI) null, "remote fetch"));
    assertThrows(SecurityValidationException.class, () ->
        policy.resolveHttpTarget(URI.create("/relative/path"), "remote fetch"));
    assertThrows(SecurityValidationException.class, () ->
        policy.resolveHttpTarget(URI.create("http://user:secret@localhost/artifact"), "remote fetch"));
    assertThrows(SecurityValidationException.class, () ->
        policy.resolveHttpTarget(URI.create("http:///artifact"), "remote fetch"));
    assertThrows(SecurityValidationException.class, () ->
        policy.resolveHttpTarget(URI.create("http://localhost:0/artifact"), "remote fetch"));
    assertThrows(SecurityValidationException.class, () ->
        policy.resolveHttpTarget(URI.create("http://localhost:65536/artifact"), "remote fetch"));

    assertEquals(0, lookups.get(), "invalid URI components must be rejected before DNS");
  }

  @Test
  void eachValidationUsesOneImmutableDnsSnapshot() throws Exception {
    InetAddress publicAddress = InetAddress.getByName("93.184.216.34");
    InetAddress loopback = InetAddress.getByName("127.0.0.1");
    AtomicInteger lookups = new AtomicInteger();
    OutboundRequestPolicy policy = new OutboundRequestPolicy(
        false,
        "",
        host -> lookups.incrementAndGet() == 1
            ? new InetAddress[] {publicAddress}
            : new InetAddress[] {loopback});

    OutboundRequestPolicy.ResolvedHttpTarget approved =
        policy.resolveHttpTarget("https://packages.example/artifact", "remote fetch");

    assertEquals(1, lookups.get());
    assertEquals(java.util.List.of(publicAddress), approved.addresses());
    assertThrows(
        SecurityValidationException.class,
        () -> policy.resolveHttpTarget("https://packages.example/artifact", "remote fetch"));
    assertEquals(java.util.List.of(publicAddress), approved.addresses(),
        "a later DNS answer must not mutate the approved connection capability");
  }

  @Test
  void directClientUsesApprovedAddressWithoutResolvingHostAgain() throws Exception {
    InetAddress loopback = InetAddress.getByName("127.0.0.1");
    HttpServer upstream = HttpServer.create(new InetSocketAddress(loopback, 0), 0);
    AtomicReference<String> receivedHost = new AtomicReference<>();
    upstream.createContext("/artifact", exchange -> {
      receivedHost.set(exchange.getRequestHeaders().getFirst("Host"));
      byte[] body = "pinned".getBytes(StandardCharsets.UTF_8);
      exchange.sendResponseHeaders(200, body.length);
      exchange.getResponseBody().write(body);
      exchange.close();
    });
    upstream.start();
    int port = upstream.getAddress().getPort();
    AtomicInteger lookups = new AtomicInteger();
    OutboundRequestPolicy policy = new OutboundRequestPolicy(
        false,
        "packages.invalid",
        host -> {
          lookups.incrementAndGet();
          return new InetAddress[] {loopback};
        });
    try (ProxiedHttpClientFactory factory = new ProxiedHttpClientFactory(60000, 10000)) {
      OutboundRequestPolicy.ResolvedHttpTarget target = policy.resolveHttpTarget(
          "http://packages.invalid:" + port + "/artifact", "remote fetch");
      try (ProxiedHttpClientFactory.ProxiedResponse response = factory.execute(
          "repo", null, "GET", target, Map.of(), 5000)) {
        assertEquals(200, response.status());
        assertEquals("pinned",
            new String(response.body().readAllBytes(), StandardCharsets.UTF_8));
      }
    } finally {
      upstream.stop(0);
    }

    assertEquals(1, lookups.get());
    assertEquals("packages.invalid:" + port, receivedHost.get());
  }
}
