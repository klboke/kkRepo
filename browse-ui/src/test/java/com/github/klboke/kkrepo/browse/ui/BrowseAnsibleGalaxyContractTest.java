package com.github.klboke.kkrepo.browse.ui;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import org.junit.jupiter.api.Test;

class BrowseAnsibleGalaxyContractTest {

  @Test
  void exposesSearchDetailsUsageAndValidatedCollectionUpload() throws IOException {
    String index = resource("/META-INF/resources/browse/index.html");
    String javascript = resource("/META-INF/resources/browse/assets/browse.js");

    assertTrue(index.contains("data-search-format=\"ansiblegalaxy\""));
    assertTrue(index.contains("data-format=\"ansiblegalaxy\""));
    assertTrue(javascript.contains("function ansibleGalaxyUsageDetail"));
    assertTrue(javascript.contains("renderAttributeGroup(\"Ansible Galaxy\", detail.ansible)"));
    assertTrue(javascript.contains("[galaxy]\\nserver_list"));
    assertTrue(javascript.contains("requirements.yml"));
    assertTrue(javascript.contains("ansible-galaxy collection install"));
    assertTrue(javascript.contains("ansible-galaxy collection publish"));
    assertTrue(javascript.contains("accept=\".tar.gz,application/gzip,application/x-gzip\""));
    assertTrue(javascript.contains("form.append(\"ansiblegalaxy.asset\""));
    assertTrue(javascript.contains("namespace-name-version.tar.gz"));
  }

  private String resource(String path) throws IOException {
    try (InputStream stream = getClass().getResourceAsStream(path)) {
      return new String(Objects.requireNonNull(stream).readAllBytes(), StandardCharsets.UTF_8);
    }
  }
}
