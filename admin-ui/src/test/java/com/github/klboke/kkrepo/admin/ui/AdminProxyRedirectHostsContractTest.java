package com.github.klboke.kkrepo.admin.ui;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import org.junit.jupiter.api.Test;

class AdminProxyRedirectHostsContractTest {

  @Test
  void proxyFormExplainsPersistsResetsAndReloadsRedirectHosts() throws IOException {
    String index = resource("/META-INF/resources/admin/index.html");
    String javascript = resource("/META-INF/resources/admin/assets/admin.js");

    assertTrue(index.contains("<input id=\"repository-allowed-redirect-hosts\" type=\"text\""));
    assertTrue(index.contains("Allowed redirect host"));
    assertTrue(index.contains("class=\"field-help\""));
    assertTrue(index.contains("class=\"lucide-icon icon-info\""));
    assertTrue(index.contains("data-tooltip=\"Optional."));
    assertTrue(index.contains("placeholder=\"artifacts.example.com\""));
    assertFalse(index.contains("plugins-artifacts.gradle.org"));
    assertTrue(index.contains("Cross-host redirects"));
    assertTrue(index.contains("denied by default"));
    assertTrue(index.contains("Source repository credentials are never forwarded"));
    assertTrue(index.contains("DNS and SSRF validation"));
    assertTrue(index.contains("wildcards are not allowed"));
    assertTrue(javascript.contains(".split(\",\")"));
    assertTrue(javascript.contains("allowedRedirectHosts,"));
    assertTrue(javascript.contains(
        "document.getElementById(\"repository-allowed-redirect-hosts\").value = \"\";"));
    assertTrue(javascript.contains("repo.proxy.allowedRedirectHosts.join(\", \")"));
  }

  private String resource(String path) throws IOException {
    try (InputStream stream = getClass().getResourceAsStream(path)) {
      return new String(Objects.requireNonNull(stream).readAllBytes(), StandardCharsets.UTF_8);
    }
  }
}
