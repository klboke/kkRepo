package com.github.klboke.kkrepo.admin.ui;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import org.junit.jupiter.api.Test;

class AdminAlpineRepositoryContractTest {
  @Test
  void exposesAlpineConfigurationAndKeyOperations() throws IOException {
    String index = resource("/META-INF/resources/admin/index.html");
    String javascript = resource("/META-INF/resources/admin/assets/admin.js");

    assertTrue(index.contains("id=\"repository-alpine-fields\""));
    assertTrue(index.contains("APK v2"));
    assertTrue(index.contains("id=\"repository-alpine-upstream-public-key\""));
    assertTrue(index.contains("id=\"repository-alpine-private-key\""));
    assertTrue(javascript.contains("alpine: \"Alpine / APK\""));
    assertTrue(javascript.contains("/alpine/status"));
    assertTrue(javascript.contains("/alpine/rebuild"));
    assertTrue(javascript.contains("/alpine/signing-key"));
    assertTrue(javascript.contains("/alpine/public-key"));
    assertTrue(javascript.contains("upstreamPublicKeys: upstreamKey ? [upstreamKey] : []"));
  }

  private String resource(String path) throws IOException {
    try (InputStream stream = getClass().getResourceAsStream(path)) {
      return new String(Objects.requireNonNull(stream).readAllBytes(), StandardCharsets.UTF_8);
    }
  }
}
