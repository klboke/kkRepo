package com.github.klboke.kkrepo.admin.ui;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import org.junit.jupiter.api.Test;

class AdminGlobalComponentSearchContractTest {

  @Test
  void administrationExposesTheSharedGlobalSearch() throws IOException {
    String index = resource("/META-INF/resources/admin/index.html");
    String translations = resource("/META-INF/resources/login/assets/ui-i18n.js");

    assertTrue(index.contains("data-global-component-search action=\"/browse/\""));
    assertTrue(index.indexOf("class=\"top-spacer\"")
        < index.indexOf("<form class=\"global-component-search\""));
    assertTrue(index.contains("placeholder=\"Search components\""));
    assertFalse(index.contains("<button type=\"submit\">Search</button>"));
    assertTrue(index.contains("/browse/assets/global-component-search.css"));
    assertTrue(index.contains("/browse/assets/global-component-search.js"));
    assertTrue(translations.contains("\"Search components\": \"搜索组件\""));
    assertTrue(translations.contains("\"All formats\": \"全部格式\""));
  }

  private String resource(String path) throws IOException {
    try (InputStream stream = getClass().getResourceAsStream(path)) {
      return new String(Objects.requireNonNull(stream).readAllBytes(), StandardCharsets.UTF_8);
    }
  }
}
