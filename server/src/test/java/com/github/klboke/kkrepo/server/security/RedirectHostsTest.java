package com.github.klboke.kkrepo.server.security;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class RedirectHostsTest {
  @Test
  void standaloneStarIsAContentOptInRatherThanAnUnrestrictedHostMatch() {
    assertEquals("*", RedirectHosts.normalizeRule(" * "));
    assertFalse(RedirectHosts.matches(List.of("*"), "cdn.example"));
  }

  @Test
  void normalizesExactAndSubdomainRules() {
    assertEquals("*.quay.io", RedirectHosts.normalizeRule(" *.QUAY.IO. "));
    assertEquals("xn--bcher-kva.example", RedirectHosts.normalizeRule("Bücher.example"));
    assertEquals("*.xn--bcher-kva.example", RedirectHosts.normalizeRule("*.Bücher.example"));
    assertEquals("localhost", RedirectHosts.normalizeRule("localhost"));
    assertEquals("127.0.0.1", RedirectHosts.normalizeRule("127.0.0.1"));
  }

  @Test
  void rejectsInvalidRules() {
    for (String rule : Arrays.asList(null, "", " ", "*.io", "*.127.0.0.1", "*.*.quay.io",
        "cdn*.quay.io", "https://quay.io", "quay.io:443", "quay.io/path", ".quay.io",
        "-cdn.quay.io", "cdn-.quay.io", "a..quay.io", "a".repeat(64) + ".quay.io",
        "a.".repeat(127) + "com")) {
      assertThrows(IllegalArgumentException.class, () -> RedirectHosts.normalizeRule(rule), rule);
    }
  }

  @Test
  void matchesOnlyWholeSubdomainsAndKeepsApexExplicit() {
    List<String> rules = Arrays.asList(null, "artifacts.example.org", "*.quay.io");
    for (String host : List.of("cdn01.quay.io", "CDN02.QUAY.IO.", "nested.cdn.quay.io")) {
      assertTrue(RedirectHosts.matches(rules, host));
    }
    for (String host : Arrays.asList(null, "", "quay.io", "evilquay.io", "quay.io.evil.test", "other.test")) {
      assertFalse(RedirectHosts.matches(rules, host));
    }
    assertTrue(RedirectHosts.matches(rules, "artifacts.example.org"));
    assertTrue(RedirectHosts.matches(List.of("quay.io"), "quay.io."));
    assertFalse(RedirectHosts.matches(null, "quay.io"));
    assertFalse(RedirectHosts.matches(List.of("*"), "quay.io"));
  }
}
