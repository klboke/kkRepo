package com.github.klboke.kkrepo.admin.ui;

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

    assertTrue(index.contains("id=\"repository-allowed-redirect-hosts\""));
    assertTrue(index.contains("Cross-host redirects"));
    assertTrue(index.contains("denied by default"));
    assertTrue(index.contains("never receive the source repository credentials"));
    assertTrue(index.contains("DNS and SSRF validation"));
    assertTrue(index.contains("wildcards are not allowed"));
    assertTrue(javascript.contains(".split(/[\\n,]/)"));
    assertTrue(javascript.contains("allowedRedirectHosts,"));
    assertTrue(javascript.contains(
        "document.getElementById(\"repository-allowed-redirect-hosts\").value = \"\";"));
    assertTrue(javascript.contains("repo.proxy.allowedRedirectHosts.join(\"\\n\")"));
  }

  private String resource(String path) throws IOException {
    try (InputStream stream = getClass().getResourceAsStream(path)) {
      return new String(Objects.requireNonNull(stream).readAllBytes(), StandardCharsets.UTF_8);
    }
  }
}
