package com.github.klboke.kkrepo.browse.ui;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import org.junit.jupiter.api.Test;

class BrowseAptRepositoryContractTest {

  @Test
  void rendersAptDetailsUsageAndDebianPackageUpload() throws IOException {
    String index = resource("/META-INF/resources/browse/index.html");
    String javascript = resource("/META-INF/resources/browse/assets/browse.js");
    String stylesheet = resource("/META-INF/resources/browse/assets/format-icons.css");

    assertTrue(index.contains("data-search-format=\"apt\""));
    assertTrue(index.contains("data-format=\"apt\""));
    assertTrue(index.contains("APT / Debian"));
    assertTrue(javascript.contains("function aptUsageDetail"));
    assertTrue(javascript.contains("const metadata = detail?.apt || {}"));
    assertTrue(javascript.contains("const keyring = `/etc/apt/keyrings/${state.repo}.gpg`"));
    assertTrue(javascript.contains("deb [signed-by=${keyring}]"));
    assertTrue(javascript.contains("apt-get update"));
    assertTrue(javascript.contains("apt-get install"));
    assertTrue(javascript.contains("accept=\".deb,application/vnd.debian.binary-package\""));
    assertTrue(javascript.contains("form.append(\"apt.asset\""));
    assertTrue(stylesheet.contains(".format-logo-apt"));
    assertTrue(stylesheet.contains("/browse/assets/formats/apt.svg"));
    try (InputStream icon = getClass().getResourceAsStream(
        "/META-INF/resources/browse/assets/formats/apt.svg")) {
      assertNotNull(icon);
    }
  }

  private String resource(String path) throws IOException {
    try (InputStream stream = getClass().getResourceAsStream(path)) {
      return new String(Objects.requireNonNull(stream).readAllBytes(), StandardCharsets.UTF_8);
    }
  }
}
