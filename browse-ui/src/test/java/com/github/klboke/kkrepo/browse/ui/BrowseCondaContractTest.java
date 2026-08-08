package com.github.klboke.kkrepo.browse.ui;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import org.junit.jupiter.api.Test;

class BrowseCondaContractTest {

  @Test
  void rendersCondaDetailsUsageAndUploadCoordinates() throws IOException {
    String index = resource("/META-INF/resources/browse/index.html");
    String javascript = resource("/META-INF/resources/browse/assets/browse.js");
    String stylesheet = resource("/META-INF/resources/browse/assets/format-icons.css");

    assertTrue(index.contains("data-search-format=\"conda\""));
    assertTrue(index.contains("data-format=\"conda\""));
    assertTrue(javascript.contains("renderAttributeGroup(\"Conda\", detail.conda)"));
    assertTrue(javascript.contains("function condaUsageDetail(entry, detail = null)"));
    assertTrue(javascript.contains("conda search --override-channels"));
    assertTrue(javascript.contains("conda install --override-channels"));
    assertTrue(javascript.contains("id=\"upload-conda-channel\""));
    assertTrue(javascript.contains("id=\"upload-conda-subdir\""));
    assertTrue(javascript.contains("accept=\".conda,.tar.bz2"));
    assertTrue(javascript.contains("form.append(\"conda.channel\""));
    assertTrue(javascript.contains("form.append(\"conda.subdir\""));
    assertTrue(javascript.contains("form.append(\"conda.asset\""));
    assertTrue(stylesheet.contains(".format-logo-conda"));
    assertTrue(stylesheet.contains("/browse/assets/formats/conda.svg"));
    try (InputStream icon = getClass().getResourceAsStream(
        "/META-INF/resources/browse/assets/formats/conda.svg")) {
      assertNotNull(icon);
    }
  }

  private String resource(String path) throws IOException {
    try (InputStream stream = getClass().getResourceAsStream(path)) {
      return new String(Objects.requireNonNull(stream).readAllBytes(), StandardCharsets.UTF_8);
    }
  }
}
