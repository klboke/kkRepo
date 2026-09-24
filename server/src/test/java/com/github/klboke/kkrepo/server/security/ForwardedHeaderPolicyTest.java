package com.github.klboke.kkrepo.server.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetAddress;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;

class ForwardedHeaderPolicyTest {
  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(strings = {" ", " , , "})
  void emptyConfigurationTrustsNoProxy(String configuration) {
    assertFalse(new ForwardedHeaderPolicy(configuration).trusted(request("127.0.0.1")));
  }

  @ParameterizedTest
  @CsvSource({
      "10.0.0.1, 10.0.0.1, true",
      "10.0.0.1, 10.0.0.2, false",
      "::1, 0:0:0:0:0:0:0:1, true",
      "2001:DB8::1, 2001:db8:0:0:0:0:0:1, true",
      "10.0.0.1, ::ffff:10.0.0.1, true",
      "10.0.0.0/16, 10.0.0.0, true",
      "10.0.0.0/16, 10.0.12.34, true",
      "10.0.0.0/16, 10.0.255.255, true",
      "10.0.0.0/16, 10.1.0.0, false",
      "10.0.0.0/16, 9.255.255.255, false",
      "10.0.128.0/17, 10.0.128.0, true",
      "10.0.128.0/17, 10.0.255.255, true",
      "10.0.128.0/17, 10.0.127.255, false",
      "10.0.12.34/16, 10.0.200.1, true",
      "192.0.2.10/31, 192.0.2.11, true",
      "192.0.2.10/31, 192.0.2.12, false",
      "10.0.0.1/32, 10.0.0.1, true",
      "10.0.0.1/32, 10.0.0.2, false",
      "0.0.0.0/0, 203.0.113.1, true",
      "0.0.0.0/0, 2001:db8::1, false",
      "2001:db8::/32, 2001:db8:ffff::1, true",
      "2001:db8::/32, 2001:db9::1, false",
      "2001:db8:8000::/33, 2001:db8:ffff::1, true",
      "2001:db8:8000::/33, 2001:db8:7fff::1, false",
      "2001:db8::/65, 2001:db8::7fff:ffff:ffff:ffff, true",
      "2001:db8::/65, 2001:db8:0:0:8000::, false",
      "2001:db8::2/127, 2001:db8::3, true",
      "2001:db8::2/127, 2001:db8::4, false",
      "2001:db8::1/128, 2001:0db8:0:0:0:0:0:1, true",
      "2001:db8::1/128, 2001:db8::2, false",
      "::/0, 2001:db8::1, true",
      "::/0, 10.0.0.1, false",
      "::/0, ::ffff:10.0.0.1, false",
      "10.0.0.0/16, ::ffff:10.0.12.34, true",
      "10.0.0.0/16, ::ffff:10.1.0.1, false"
  })
  void matchesOnlyConfiguredAddressesAndNetworks(String configuration, String remote, boolean expected) {
    assertEquals(expected, new ForwardedHeaderPolicy(configuration).trusted(request(remote)));
  }

  @Test
  void mixesExactAddressesAndNetworksIgnoringEmptyEntriesAndWhitespace() {
    ForwardedHeaderPolicy policy = new ForwardedHeaderPolicy(" 127.0.0.1, ,10.0.0.0/16, 2001:db8::/32, ");
    assertTrue(policy.trusted(request("127.0.0.1")));
    assertTrue(policy.trusted(request("10.0.12.34")));
    assertTrue(policy.trusted(request("2001:db8::1")));
    assertFalse(policy.trusted(request("203.0.113.1")));
  }

  @Test
  void preservesHostnameResolutionForExactProxyEntries() throws Exception {
    assertTrue(new ForwardedHeaderPolicy("localhost")
        .trusted(request(InetAddress.getByName("localhost").getHostAddress())));
  }

  @ParameterizedTest
  @ValueSource(strings = {
      "10.0.0.0/", "/16", "10.0.0.0/-1", "10.0.0.0/33", "10.0.0.0/+16",
      "10.0.0.0/abc", "10.0.0.0/16/24", "10.0.0.0/255.255.0.0", "10.0.0.0/999999999999",
      "999.0.0.0/16", "localhost/16", "2001:db8::/129", "2001:db8::/-1", "gggg::/64",
      "fe80::1%1/64", "::ffff:10.0.0.0/16", "::ffff:10.0.0.0/112"
  })
  void rejectsInvalidOrAmbiguousCidrConfigurationAtStartup(String entry) {
    IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
        () -> new ForwardedHeaderPolicy("127.0.0.1," + entry));
    assertTrue(error.getMessage().contains("trusted-proxies"));
    assertTrue(error.getMessage().contains(entry));
  }

  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(strings = {" ", "not-an-address", "localhost", "10.0.0.1:8080", "10.0.0.1/32"})
  void invalidRemoteAddressCannotEstablishTrust(String remote) {
    assertFalse(new ForwardedHeaderPolicy("127.0.0.1,10.0.0.0/16,::/0").trusted(request(remote)));
  }

  @Test
  void onlyImmediatePeerCanEstablishTrust() {
    MockHttpServletRequest request = request("203.0.113.1");
    request.addHeader("X-Forwarded-For", "10.0.12.34");
    request.addHeader("Forwarded", "for=10.0.12.34;proto=https;host=registry.example.com");

    ForwardedHeaderPolicy policy = new ForwardedHeaderPolicy("10.0.0.0/16");
    assertFalse(policy.trusted(request));
    assertEquals("http://kkrepo.internal:8080", policy.serverBaseUrl(request));
  }

  @Test
  void rotatingProxyAddressesKeepPublicHttpsUrls() {
    ForwardedHeaderPolicy policy = new ForwardedHeaderPolicy("10.0.0.0/16");
    assertEquals("https://registry.example.com", policy.serverBaseUrl(request("10.0.12.34")));
    assertEquals("https://registry.example.com", policy.serverBaseUrl(request("10.0.200.99")));
    assertEquals("http://kkrepo.internal:8080", policy.serverBaseUrl(request("10.1.0.1")));
  }

  private static MockHttpServletRequest request(String remote) {
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setRemoteAddr(remote);
    request.setScheme("http");
    request.setServerName("kkrepo.internal");
    request.setServerPort(8080);
    request.addHeader("X-Forwarded-Proto", "https");
    request.addHeader("X-Forwarded-Host", "registry.example.com");
    request.addHeader("X-Forwarded-Port", "443");
    return request;
  }
}
