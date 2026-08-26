package com.github.klboke.kkrepo.browse.ui;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import org.junit.jupiter.api.Test;

class BrowseConanRepositoryContractTest {
  @Test
  void exposesSearchUsageMultiFileUploadAndNexusBrowsePath() throws IOException {
    String index = resource("/META-INF/resources/browse/index.html");
    String javascript = resource("/META-INF/resources/browse/assets/browse.js");
    String stylesheet = resource("/META-INF/resources/browse/assets/format-icons.css");

    assertTrue(index.contains("<option value=\"conan\">Conan 2</option>"));
    assertTrue(index.contains("data-format=\"conan\""));
    assertTrue(javascript.contains("function conanUsageDetail"));
    assertTrue(javascript.contains("conan remote add"));
    assertTrue(javascript.contains("conan remote login"));
    assertTrue(javascript.contains("channel !== \"_\" ? `/${channel}` : \"\""));
    assertTrue(javascript.contains("function conanAssetRows"));
    assertTrue(javascript.contains("form.append(\"conan.rrev\", rrev)"));
    assertTrue(javascript.contains("Nexus-compatible Browse path"));
    assertTrue(javascript.contains("`/packages/${packageId}/revisions/${prev}/files`"));
    assertTrue(stylesheet.contains(".format-logo-conan"));
    assertTrue(stylesheet.contains("/browse/assets/formats/conan.svg"));
    try (InputStream icon = getClass().getResourceAsStream(
        "/META-INF/resources/browse/assets/formats/conan.svg")) {
      assertNotNull(icon);
    }
  }

  private String resource(String path) throws IOException {
    try (InputStream stream = getClass().getResourceAsStream(path)) {
      return new String(Objects.requireNonNull(stream).readAllBytes(), StandardCharsets.UTF_8);
    }
  }
}
