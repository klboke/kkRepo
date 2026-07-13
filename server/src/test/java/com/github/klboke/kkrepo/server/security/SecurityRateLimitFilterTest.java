package com.github.klboke.kkrepo.server.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.github.klboke.kkrepo.server.metrics.KkRepoMetrics;
import com.github.klboke.kkrepo.server.support.InMemorySharedCache;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class SecurityRateLimitFilterTest {

  @Test
  void repeatedLoginIsBlockedWithRetryHeaderAndMetric() throws Exception {
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    SecurityRateLimitFilter filter = filter(
        1, 1, new InMemorySharedCache(), new ForwardedHeaderPolicy(""), registry, false);
    CountingChain chain = new CountingChain();

    MockHttpServletResponse first = invoke(
        filter, request("POST", "/internal/security/login", "198.51.100.10"), chain);
    MockHttpServletResponse second = invoke(
        filter, request("POST", "/internal/security/login", "198.51.100.10"), chain);

    assertEquals(200, first.getStatus());
    assertEquals(429, second.getStatus());
    assertEquals("60", second.getHeader("Retry-After"));
    assertEquals(1, chain.calls);
    var counter = registry.find("kkrepo_rate_limit_blocked_total")
        .tag("type", "login")
        .counter();
    assertNotNull(counter);
    assertEquals(1.0, counter.count());
  }

  @Test
  void bootstrapAndLoginUseIndependentCounters() throws Exception {
    SecurityRateLimitFilter filter = filter(
        1, 1, new InMemorySharedCache(), new ForwardedHeaderPolicy(""),
        new SimpleMeterRegistry(), false);
    CountingChain chain = new CountingChain();

    MockHttpServletResponse login = invoke(
        filter, request("POST", "/internal/security/login", "198.51.100.20"), chain);
    MockHttpServletResponse bootstrap = invoke(
        filter, request("POST", "/internal/security/bootstrap/admin", "198.51.100.20"), chain);

    assertEquals(200, login.getStatus());
    assertEquals(200, bootstrap.getStatus());
    assertEquals(2, chain.calls);
  }

  @Test
  void managementAuthRequiresPresentedCredentialsAndUsesTrustedForwardedAddress() throws Exception {
    InMemorySharedCache cache = new InMemorySharedCache();
    SecurityRateLimitFilter filter = filter(
        1, 1, cache, new ForwardedHeaderPolicy("127.0.0.1"),
        new SimpleMeterRegistry(), false);
    CountingChain chain = new CountingChain();
    MockHttpServletRequest first = request(
        "POST", "/service/rest/v1/security/users", "127.0.0.1");
    first.addHeader("Authorization", "Basic abc");
    first.addHeader("X-Forwarded-For", "203.0.113.9, 10.0.0.1");
    MockHttpServletRequest second = request(
        "POST", "/service/rest/v1/security/users", "127.0.0.1");
    second.addHeader("X-Nexus-Plus-Token", "token");
    second.addHeader("X-Forwarded-For", "203.0.113.9");
    MockHttpServletRequest noCredentials = request(
        "POST", "/service/rest/v1/security/users", "127.0.0.1");
    noCredentials.addHeader("X-Forwarded-For", "203.0.113.9");

    assertEquals(200, invoke(filter, first, chain).getStatus());
    assertEquals(429, invoke(filter, second, chain).getStatus());
    assertEquals(200, invoke(filter, noCredentials, chain).getStatus());
    assertEquals(2, chain.calls);
  }

  @Test
  void untrustedForwardedHeaderDoesNotMergeDifferentRemoteClients() throws Exception {
    SecurityRateLimitFilter filter = filter(
        1, 1, new InMemorySharedCache(), new ForwardedHeaderPolicy("127.0.0.1"),
        new SimpleMeterRegistry(), false);
    CountingChain chain = new CountingChain();
    MockHttpServletRequest first = request("POST", "/internal/security/login", "198.51.100.30");
    first.addHeader("X-Forwarded-For", "203.0.113.50");
    MockHttpServletRequest second = request("POST", "/internal/security/login", "198.51.100.31");
    second.addHeader("X-Forwarded-For", "203.0.113.50");

    assertEquals(200, invoke(filter, first, chain).getStatus());
    assertEquals(200, invoke(filter, second, chain).getStatus());
    assertEquals(2, chain.calls);
  }

  @Test
  void nonPositiveLimitsDisableBlockingAndLegacyLoginCanBeLimited() throws Exception {
    SecurityRateLimitFilter unlimited = filter(
        0, 0, new InMemorySharedCache(), new ForwardedHeaderPolicy(""),
        new SimpleMeterRegistry(), false);
    CountingChain unlimitedChain = new CountingChain();
    for (int i = 0; i < 3; i++) {
      assertEquals(200, invoke(
          unlimited,
          request("POST", "/internal/security/login", "198.51.100.40"),
          unlimitedChain).getStatus());
    }
    assertEquals(3, unlimitedChain.calls);

    SecurityRateLimitFilter legacy = filter(
        1, 1, new InMemorySharedCache(), new ForwardedHeaderPolicy(""),
        new SimpleMeterRegistry(), true);
    CountingChain legacyChain = new CountingChain();
    assertEquals(200, invoke(
        legacy, request("POST", "/service/rapture/session", "198.51.100.41"), legacyChain).getStatus());
    assertEquals(429, invoke(
        legacy, request("POST", "/service/rapture/session", "198.51.100.41"), legacyChain).getStatus());
  }

  private static SecurityRateLimitFilter filter(
      int loginLimit,
      int bootstrapLimit,
      InMemorySharedCache cache,
      ForwardedHeaderPolicy forwardedHeaderPolicy,
      SimpleMeterRegistry registry,
      boolean legacyUiEnabled) {
    return new SecurityRateLimitFilter(
        loginLimit,
        bootstrapLimit,
        "X-Nexus-Plus-Token",
        cache,
        forwardedHeaderPolicy,
        new KkRepoMetrics(registry),
        new NexusLegacyUiCompatibility(legacyUiEnabled));
  }

  private static MockHttpServletRequest request(String method, String uri, String remoteAddress) {
    MockHttpServletRequest request = new MockHttpServletRequest(method, uri);
    request.setRemoteAddr(remoteAddress);
    return request;
  }

  private static MockHttpServletResponse invoke(
      SecurityRateLimitFilter filter,
      MockHttpServletRequest request,
      CountingChain chain) throws Exception {
    MockHttpServletResponse response = new MockHttpServletResponse();
    filter.doFilter(request, response, chain);
    return response;
  }

  private static final class CountingChain implements FilterChain {
    private int calls;

    @Override
    public void doFilter(jakarta.servlet.ServletRequest request, jakarta.servlet.ServletResponse response) {
      calls++;
    }
  }
}
