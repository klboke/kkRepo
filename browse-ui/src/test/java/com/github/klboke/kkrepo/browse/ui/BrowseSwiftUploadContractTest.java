package com.github.klboke.kkrepo.browse.ui;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import org.junit.jupiter.api.Test;

class BrowseSwiftUploadContractTest {

  @Test
  void swiftUploadUsesTheRegistryPublishContract() throws IOException {
    String javascript = resource("/META-INF/resources/browse/assets/browse.js");

    assertTrue(javascript.contains("if (repo.format === \"swift\")"));
    assertTrue(javascript.contains("id=\"upload-swift-scope\""));
    assertTrue(javascript.contains("id=\"upload-swift-name\""));
    assertTrue(javascript.contains("id=\"upload-swift-version\""));
    assertTrue(javascript.contains("id=\"upload-swift-metadata\""));
    assertTrue(javascript.contains("id=\"upload-swift-signature-format\""));
    assertTrue(javascript.contains("id=\"upload-swift-source-signature\""));
    assertTrue(javascript.contains("id=\"upload-swift-metadata-signature\""));
    for (String field : new String[] {
        "scope", "name", "version", "metadata", "signature-format",
        "source-archive-signature", "metadata-signature", "source-archive"
    }) {
      assertTrue(javascript.contains("form.append(\"swift." + field + "\""), field);
    }
    assertTrue(javascript.contains("swift package-registry set"));
    assertTrue(javascript.contains("swift package-registry login"));
    assertTrue(javascript.contains("swift package-registry publish"));
    assertTrue(javascript.contains("supportsAvailability"));
    assertTrue(javascript.contains("Use HTTPS in production"));
  }

  private String resource(String path) throws IOException {
    try (InputStream stream = getClass().getResourceAsStream(path)) {
      return new String(Objects.requireNonNull(stream).readAllBytes(), StandardCharsets.UTF_8);
    }
  }
}
