package com.github.klboke.kkrepo.admin.ui;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import org.junit.jupiter.api.Test;

class AdminSwiftRepositoryContractTest {

  @Test
  void swiftProxyIsGithubOnlyAndGroupsCanBeNested() throws IOException {
    String index = resource("/META-INF/resources/admin/index.html");
    String javascript = resource("/META-INF/resources/admin/assets/admin.js");

    assertTrue(index.contains("id=\"repository-swift-proxy-note\""));
    assertTrue(index.contains("https://github.com/"));
    assertTrue(index.contains("SwiftPM clients should always connect to kkrepo over HTTPS"));
    assertTrue(javascript.contains("format === \"swift\" && type === \"PROXY\""));
    assertTrue(javascript.contains("recipe?.type === \"PROXY\" && recipe?.format === \"swift\""));
    assertTrue(javascript.contains("swift: \"https://github.com/\""));
    assertTrue(javascript.contains("format === \"swift\""));
    assertTrue(javascript.contains("format === \"terraform\" || format === \"swift\""));
  }

  private String resource(String path) throws IOException {
    try (InputStream stream = getClass().getResourceAsStream(path)) {
      return new String(Objects.requireNonNull(stream).readAllBytes(), StandardCharsets.UTF_8);
    }
  }
}
