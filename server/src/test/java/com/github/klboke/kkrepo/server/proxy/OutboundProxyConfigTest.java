package com.github.klboke.kkrepo.server.proxy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import org.junit.jupiter.api.Test;

class OutboundProxyConfigTest {

  @Test
  void parseTypeRecognisesImplementedProtocols() {
    assertEquals(OutboundProxyConfig.Type.HTTP, OutboundProxyConfig.parseType("http"));
    assertEquals(OutboundProxyConfig.Type.HTTP, OutboundProxyConfig.parseType(" HTTP "));
    assertEquals(OutboundProxyConfig.Type.SOCKS, OutboundProxyConfig.parseType("SOCKS"));
    assertEquals(OutboundProxyConfig.Type.SOCKS, OutboundProxyConfig.parseType("socks5"));
    assertNull(OutboundProxyConfig.parseType(null));
    assertNull(OutboundProxyConfig.parseType(""));
    assertNull(OutboundProxyConfig.parseType("garbage"));
  }

  @Test
  void parseTypeRejectsAliasesForUnimplementedProtocols() {
    // The client factory always speaks plaintext HTTP (CONNECT) or SOCKS5, so aliases that imply
    // a different wire protocol must be rejected instead of silently re-interpreted.
    assertNull(OutboundProxyConfig.parseType("https"));
    assertNull(OutboundProxyConfig.parseType("HTTPS_PROXY"));
    assertNull(OutboundProxyConfig.parseType("http_proxy"));
    assertNull(OutboundProxyConfig.parseType("socks4"));
    assertNull(OutboundProxyConfig.parseType("SOCKS_PROXY"));
  }

  @Test
  void blankCredentialsAreNormalizedToNull() {
    OutboundProxyConfig config =
        new OutboundProxyConfig(OutboundProxyConfig.Type.HTTP, "192.168.1.10", 7890, "  ", "");
    assertNull(config.username());
    assertNull(config.password());
    assertFalse(config.authenticated());
  }

  @Test
  void authenticatedRequiresEnabledConfigAndUsername() {
    assertTrue(new OutboundProxyConfig(OutboundProxyConfig.Type.HTTP, "192.168.1.10", 7890, "u", null)
        .authenticated());
    assertFalse(new OutboundProxyConfig(OutboundProxyConfig.Type.HTTP, "", 7890, "u", "p")
        .authenticated());
    assertFalse(new OutboundProxyConfig(OutboundProxyConfig.Type.HTTP, "192.168.1.10", 0, "u", "p")
        .authenticated());
  }

  @Test
  void nullTypeDefaultsToHttpAndHostIsTrimmed() {
    OutboundProxyConfig config =
        new OutboundProxyConfig(null, " 192.168.1.10 ", 7890, null, null);
    assertEquals(OutboundProxyConfig.Type.HTTP, config.type());
    assertEquals("192.168.1.10", config.host());
    assertTrue(config.enabled());
  }

  @Test
  void enabledRequiresHostAndPort() {
    assertTrue(new OutboundProxyConfig(OutboundProxyConfig.Type.HTTP, "192.168.1.10", 7890, null, null).enabled());
    assertFalse(new OutboundProxyConfig(OutboundProxyConfig.Type.HTTP, "", 7890, null, null).enabled());
    assertFalse(new OutboundProxyConfig(OutboundProxyConfig.Type.HTTP, "192.168.1.10", 0, null, null).enabled());
    assertFalse(new OutboundProxyConfig(OutboundProxyConfig.Type.HTTP, "192.168.1.10", 70000, null, null).enabled());
  }

  @Test
  void cacheKeyDistinguishesPasswordRotation() {
    OutboundProxyConfig a = new OutboundProxyConfig(OutboundProxyConfig.Type.HTTP, "10.0.0.5", 7890, "u", "old");
    OutboundProxyConfig b = new OutboundProxyConfig(OutboundProxyConfig.Type.HTTP, "10.0.0.5", 7890, "u", "new");
    assertNotEquals(a.cacheKey(), b.cacheKey());
    assertEquals(a.cacheKey(), new OutboundProxyConfig(OutboundProxyConfig.Type.HTTP, "10.0.0.5", 7890, "u", "old").cacheKey());
  }

  @Test
  void cacheKeyIsHostCaseInsensitive() {
    OutboundProxyConfig upper = new OutboundProxyConfig(OutboundProxyConfig.Type.HTTP, "Clash.Example.COM", 7890, null, null);
    OutboundProxyConfig lower = new OutboundProxyConfig(OutboundProxyConfig.Type.HTTP, "clash.example.com", 7890, null, null);
    assertEquals(upper.cacheKey(), lower.cacheKey());
  }

  @Test
  void ipv6BypassMatchesUriBracketsAndEquivalentLiteralSpellings() {
    OutboundProxyConfig proxy = new OutboundProxyConfig(OutboundProxyConfig.Type.HTTP,
        "proxy.example.org", 3128, null, null, Set.of("2001:db8::1", "[2001:DB8:0:0::2]"), 1L);

    assertNull(OutboundProxyConfig.forTarget(proxy, "https://[2001:db8::1]/repository"));
    assertNull(OutboundProxyConfig.forTarget(proxy, "https://[2001:db8::2]/repository"));
    assertSame(proxy, OutboundProxyConfig.forTarget(proxy, "https://[2001:db8::3]/repository"));
  }
}
