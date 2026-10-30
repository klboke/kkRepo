package com.github.klboke.kkrepo.admin.ui;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import org.junit.jupiter.api.Test;

class AdminNpmMinimumReleaseAgeContractTest {

  @Test
  void proxyFormExplainsAndPersistsMinimumReleaseAge() throws IOException {
    String index = resource("/META-INF/resources/admin/index.html");
    String javascript = resource("/META-INF/resources/admin/assets/admin.js");

    assertTrue(index.contains("id=\"repository-minimum-release-age\""));
    assertTrue(index.contains("Use 0 to disable this policy"));
    assertTrue(index.contains("independent of content and metadata cache"));
    assertTrue(index.contains("missing or invalid publish time are blocked"));
    assertTrue(javascript.contains(
        "new Set([\"npm\", \"go\", \"maven2\", \"pypi\"])"));
    assertTrue(javascript.contains("MINIMUM_RELEASE_AGE_FORMATS.has(format) && type === \"PROXY\""));
    assertTrue(javascript.contains(
        "minimumReleaseAgeMinutes: MINIMUM_RELEASE_AGE_FORMATS.has(recipe.format)"));
    assertTrue(javascript.contains("repo.proxy.minimumReleaseAgeMinutes ?? \"0\""));
  }

  private String resource(String path) throws IOException {
    try (InputStream stream = getClass().getResourceAsStream(path)) {
      return new String(Objects.requireNonNull(stream).readAllBytes(), StandardCharsets.UTF_8);
    }
  }
}
