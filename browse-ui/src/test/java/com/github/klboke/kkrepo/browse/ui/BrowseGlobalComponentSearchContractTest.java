package com.github.klboke.kkrepo.browse.ui;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import org.junit.jupiter.api.Test;

class BrowseGlobalComponentSearchContractTest {

  @Test
  void browseExposesGlobalAndAllFormatSearch() throws IOException {
    String index = resource("/META-INF/resources/browse/index.html");
    String javascript = resource("/META-INF/resources/browse/assets/browse.js");
    String sharedJavascript =
        resource("/META-INF/resources/browse/assets/global-component-search.js");
    String sharedStylesheet =
        resource("/META-INF/resources/browse/assets/global-component-search.css");

    assertTrue(index.contains("data-global-component-search action=\"/browse/\""));
    assertTrue(index.indexOf("class=\"top-spacer\"")
        < index.indexOf("<form class=\"global-component-search\""));
    assertTrue(index.contains("placeholder=\"Search components\""));
    assertFalse(index.contains("<button type=\"submit\">Search</button>"));
    assertTrue(index.contains("data-search-format=\"custom\""));
    assertTrue(index.contains("<span>All formats</span>"));
    assertTrue(index.contains("<form class=\"search-form\" id=\"component-search-form\">"));
    assertTrue(index.contains("<span id=\"component-search-format\">Maven</span>"));
    assertTrue(index.contains("/browse/assets/global-component-search.css"));
    assertTrue(index.contains("/browse/assets/global-component-search.js"));

    assertTrue(javascript.contains("custom: \"custom\""));
    assertTrue(javascript.contains("if (normalizedFormat !== \"custom\")"));
    assertTrue(javascript.contains("keyword: new URLSearchParams(query).get(\"q\") || \"\""));
    assertTrue(javascript.contains("showSearch(route.searchFormat, false, route.keyword)"));
    assertTrue(javascript.contains("document.getElementById(\"component-search-form\")"));
    assertTrue(javascript.contains("searchFormatLabel(activeSearchFormat)"));

    assertTrue(sharedJavascript.contains("/browse/#browse/search/custom"));
    assertTrue(sharedJavascript.contains("locationRef.assign(destination)"));
    assertTrue(sharedStylesheet.contains(".global-component-search input:focus-visible"));
    assertTrue(sharedStylesheet.contains("margin: 0 12px 0 24px"));
    assertTrue(sharedStylesheet.contains("@media (max-width: 760px)"));
  }

  private String resource(String path) throws IOException {
    try (InputStream stream = getClass().getResourceAsStream(path)) {
      return new String(Objects.requireNonNull(stream).readAllBytes(), StandardCharsets.UTF_8);
    }
  }
}
