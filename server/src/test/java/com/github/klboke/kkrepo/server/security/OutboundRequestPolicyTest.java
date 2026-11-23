package com.github.klboke.kkrepo.server.security;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.github.klboke.kkrepo.server.maven.HttpRemoteFetcher;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntime;
import com.github.klboke.kkrepo.server.proxy.ProxiedHttpClientFactory;
import com.github.klboke.kkrepo.server.proxy.ProxiedHttpClientFactory.ProxiedResponse;
import com.sun.net.httpserver.HttpServer;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;

class OutboundRequestPolicyTest {

  @Test
  void publicHttpsIgnoresGlobalPrivateAddressExceptionsAndPinsAllDnsAnswers() throws Exception {
    var privatePolicy = new OutboundRequestPolicy(true, "cdn.example",
        host -> new InetAddress[] {InetAddress.getByName("8.8.8.8"), InetAddress.getByName("127.0.0.1")});
    assertThrows(SecurityValidationException.class,
        () -> privatePolicy.resolvePublicHttpsTarget("https://cdn.example/blob", "content"));
    var publicPolicy = new OutboundRequestPolicy(true, "cdn.example",
        host -> new InetAddress[] {InetAddress.getByName("8.8.8.8")});
    var target = publicPolicy.resolvePublicHttpsTarget("https://cdn.example/blob", "content");
    assertEquals("8.8.8.8", target.addresses().getFirst().getHostAddress());
    assertEquals(false, target.proxyResolvesDns());
    assertEquals(true, target.publicHttpsOnly());
    assertThrows(SecurityValidationException.class,
        () -> publicPolicy.resolvePublicHttpsTarget("http://cdn.example/blob", "content"));
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(strings = {
      "127.0.0.1", "10.0.0.1", "172.16.0.1", "192.168.0.1", "169.254.169.254",
      "0.0.0.0", "224.0.0.1", "::1", "fc00::1", "fe80::1", "::ffff:127.0.0.1",
      "100.64.0.1", "192.0.2.1", "198.18.0.1", "198.51.100.1", "203.0.113.1", "240.0.0.1",
      "2001:db8::1", "3fff::1", "2001:2::1", "2001:10::1", "2002:7f00:1::1", "64:ff9b::7f00:1"
  })
  void publicHttpsRejectsForbiddenDestinationsEvenWithGlobalExceptions(String address) {
    var policy = new OutboundRequestPolicy(true, "cdn.example",
        host -> new InetAddress[] {InetAddress.getByName(address)});
    assertThrows(SecurityValidationException.class,
        () -> policy.resolvePublicHttpsTarget("https://cdn.example/blob", "content"));
  }

  @ParameterizedTest
  @ValueSource(ints = {0, 2})
  void logsOnlyTheRejectedHopAndAddressWithoutSignedUrl(int rejectedHop) throws Exception {
    String upstream = "https://services.gradle.org";
    String blockedHost = rejectedHop == 0 ? "services.gradle.org" : "release-assets.githubusercontent.com";
    InetAddress publicAddress = InetAddress.ofLiteral("104.16.73.101");
    InetAddress blockedAddress = InetAddress.ofLiteral("::ffff");
    var policy = new OutboundRequestPolicy(false, "", host -> host.equals(blockedHost)
        ? new InetAddress[] {publicAddress, blockedAddress} : new InetAddress[] {publicAddress});
    var clients = mock(ProxiedHttpClientFactory.class);
    var firstRedirect = redirect("https://github.com/gradle/gradle-distributions/releases/download/gradle.zip");
    var secondRedirect = redirect("https://release-assets.githubusercontent.com/file?sig=secret");
    when(clients.execute(eq("services-gradle-org"), isNull(), eq("GET"), any(), anyMap(), isNull(), anyLong()))
        .thenReturn(firstRedirect, secondRedirect);
    var runtime = mock(RepositoryRuntime.class);
    when(runtime.name()).thenReturn("services-gradle-org");
    when(runtime.proxyRemoteUrl()).thenReturn(upstream);
    when(runtime.allowedRedirectHosts()).thenReturn(Set.of("*"));
    when(runtime.contentRedirectPolicy()).thenReturn(ProxyRedirectPolicy.PUBLIC_HTTPS);
    var request = HttpRemoteFetcher.Request.get(upstream + "/distributions/gradle.zip")
        .withRepositoryForContent(runtime, false);
    var fetcher = new HttpRemoteFetcher(policy, null, clients, "HTTP_1_1", 30, 60, 120, 5, 1);
    var logger = (Logger) LoggerFactory.getLogger(HttpRemoteFetcher.class);
    var appender = new ListAppender<ILoggingEvent>();
    appender.start();
    logger.addAppender(appender);
    try {
      var exception = assertThrows(HttpRemoteFetcher.RemoteRequestRejectedException.class, () -> fetcher.fetch(request));
      var response = new SecurityErrorAdvice().validation(exception);
      String purpose = rejectedHop == 0 ? "remote fetch" : "remote redirect";
      assertEquals(400, response.getStatusCode().value());
      assertEquals(Map.of("message", purpose + " URL resolves to a non-public address"), response.getBody());
      assertEquals(1, appender.list.size());
      var event = appender.list.getFirst();
      assertEquals("Proxy upstream address rejected: repository=services-gradle-org method=GET redirectHop="
          + rejectedHop + " host=" + blockedHost + " address=" + blockedAddress.getHostAddress()
          + " reason=NON_PUBLIC_ADDRESS", event.getFormattedMessage());
      assertNull(event.getThrowableProxy());
      verify(clients, times(rejectedHop)).execute(
          eq("services-gradle-org"), isNull(), eq("GET"), any(), anyMap(), isNull(), anyLong());
    } finally {
      logger.detachAppender(appender);
      appender.stop();
    }
  }

  private static ProxiedResponse redirect(String location) {
    var response = mock(ProxiedResponse.class);
    when(response.status()).thenReturn(302);
    when(response.header("Location")).thenReturn(location);
    when(response.headers()).thenReturn(Map.of("Location", location));
    return response;
  }

  @Test
  void publicHttpsRejectsMissingAndMalformedUrlsBeforeResolvingDns() {
    AtomicInteger lookups = new AtomicInteger();
    var policy = new OutboundRequestPolicy(true, "cdn.example", host -> {
      lookups.incrementAndGet();
      return new InetAddress[] {InetAddress.getByName("8.8.8.8")};
    });
    for (String url : new String[] {null, "", "  "}) {
      var error = assertThrows(SecurityValidationException.class,
          () -> policy.resolvePublicHttpsTarget(url, "content"));
      assertEquals("content URL is required", error.getMessage());
    }
    var error = assertThrows(SecurityValidationException.class,
        () -> policy.resolvePublicHttpsTarget("https://cdn.example/bad path?signature=secret", "content"));
    assertEquals("content URL is not valid", error.getMessage());
    assertEquals(0, lookups.get());
  }

  @Test
  void rejectsLoopbackByDefault() {
    OutboundRequestPolicy policy = new OutboundRequestPolicy(false, "");

    var error = assertThrows(OutboundRequestPolicy.AddressRejectedException.class,
        () -> policy.validateHttpUri("http://127.0.0.1:8081/repository/maven-public/", "proxy.remoteUrl"));
    assertEquals("127.0.0.1", error.address());
    assertEquals("PRIVATE_OR_LOCAL_ADDRESS", error.reason());
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
  void proxyResolvedTargetSkipsLocalDnsAndCarriesTheOriginalHostname() {
    AtomicInteger lookups = new AtomicInteger();
    OutboundRequestPolicy policy = new OutboundRequestPolicy(
        false,
        "",
        host -> {
          lookups.incrementAndGet();
          throw new UnknownHostException(host);
        });

    OutboundRequestPolicy.ResolvedHttpTarget target = policy.resolveHttpTarget(
        "https://packages.invalid/repository/", "remote fetch", true);

    assertEquals(0, lookups.get());
    assertEquals("packages.invalid", target.uri().getHost());
    assertTrue(target.proxyResolvesDns());
    assertTrue(target.addresses().isEmpty());
  }

  @Test
  void proxyResolvedTargetsStillRejectExplicitPrivateAndLocalOnlyHosts() {
    OutboundRequestPolicy policy = new OutboundRequestPolicy(false, "");

    assertThrows(SecurityValidationException.class, () ->
        policy.resolveHttpTarget("http://127.0.0.1/artifact", "remote fetch", true));
    assertThrows(SecurityValidationException.class, () ->
        policy.resolveHttpTarget("http://2130706433/artifact", "remote fetch", true));
    assertThrows(SecurityValidationException.class, () ->
        policy.resolveHttpTarget("http://localhost/artifact", "remote fetch", true));
    assertThrows(SecurityValidationException.class, () ->
        policy.resolveHttpTarget("http://packages.local/artifact", "remote fetch", true));
    assertThrows(SecurityValidationException.class, () ->
        policy.resolveHttpTarget("http://registry.home.arpa/artifact", "remote fetch", true));
  }

  @Test
  void privateAddressOverrideAlsoAppliesToProxyResolvedTargets() {
    OutboundRequestPolicy policy = OutboundRequestPolicy.allowPrivateForTests();

    OutboundRequestPolicy.ResolvedHttpTarget target =
        policy.resolveHttpTarget("http://localhost/artifact", "remote fetch", true);

    assertTrue(target.proxyResolvesDns());
    assertTrue(target.addresses().isEmpty());
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
