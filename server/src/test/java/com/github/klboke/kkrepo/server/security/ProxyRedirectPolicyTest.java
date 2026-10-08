package com.github.klboke.kkrepo.server.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ProxyRedirectPolicyTest {
  @Test
  void parsesExplicitPoliciesAndDefaultsMissingValuesToAllowlist() {
    assertEquals(ProxyRedirectPolicy.ALLOWLIST, ProxyRedirectPolicy.parse(null));
    assertEquals(ProxyRedirectPolicy.ALLOWLIST, ProxyRedirectPolicy.parse("ALLOWLIST"));
    assertEquals(ProxyRedirectPolicy.PUBLIC_HTTPS, ProxyRedirectPolicy.parse("PUBLIC_HTTPS"));
    assertEquals(ProxyRedirectPolicy.PUBLIC_HTTPS, ProxyRedirectPolicy.parse(ProxyRedirectPolicy.PUBLIC_HTTPS));
  }

  @ParameterizedTest
  @ValueSource(strings = {"", "public_https", " PUBLIC_HTTPS ", "UNRESTRICTED"})
  void rejectsInvalidPoliciesRatherThanGrantingRedirectTrust(String value) {
    var error = assertThrows(IllegalArgumentException.class, () -> ProxyRedirectPolicy.parse(value));
    assertEquals("proxy.redirectPolicy must be ALLOWLIST or PUBLIC_HTTPS", error.getMessage());
  }
}
