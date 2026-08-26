package com.github.klboke.kkrepo.browse.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import org.junit.jupiter.api.Test;

class BrowseGlobalComponentSearchContractTest {

  @Test
  void browseExposesGlobalAllAndCustomFormatSearch() throws IOException {
    String index = resource("/META-INF/resources/browse/index.html");
    String javascript = resource("/META-INF/resources/browse/assets/browse.js");
    String browseStylesheet = resource("/META-INF/resources/browse/assets/browse.css");
    String sharedJavascript =
        resource("/META-INF/resources/browse/assets/global-component-search.js");
    String sharedStylesheet =
        resource("/META-INF/resources/browse/assets/global-component-search.css");
    String accountMenuStylesheet =
        resource("/META-INF/resources/browse/assets/account-menu.css");

    assertTrue(index.contains("data-global-component-search action=\"/browse/\""));
    assertTrue(index.indexOf("class=\"top-spacer\"")
        < index.indexOf("<form class=\"global-component-search\""));
    assertTrue(index.contains("placeholder=\"Search components\""));
    assertFalse(index.contains("<button type=\"submit\">Search</button>"));
    assertTrue(index.contains("data-search-format=\"all\""));
    assertTrue(index.contains("<span>All components</span>"));
    assertTrue(index.contains("data-search-format=\"custom\""));
    assertTrue(index.contains("<span>Custom search</span>"));
    assertEquals(10L, index.lines()
        .filter(line -> line.contains("class=\"side-subitem\""))
        .count());
    assertTrue(index.contains("<form class=\"search-form\" id=\"component-search-form\">"));
    assertTrue(index.contains(
        "<select id=\"component-custom-format\" hidden aria-hidden=\"true\" tabindex=\"-1\">"));
    assertTrue(index.contains("aria-haspopup=\"listbox\""));
    assertTrue(index.contains("placeholder=\"Filter formats\""));
    assertTrue(index.contains("id=\"component-custom-format-options\""));
    assertTrue(index.indexOf("id=\"component-custom-format-filter\"")
        < index.indexOf("id=\"component-custom-format-options\""));
    assertEquals(22L, index.lines()
        .filter(line -> line.contains("class=\"search-format-option\""))
        .count());
    assertTrue(index.contains(
        "data-custom-search-format=\"docker\"><span class=\"format-logo format-logo-docker\""));
    assertTrue(index.contains(
        "data-custom-search-format=\"huggingface\"><span class=\"format-logo format-logo-huggingface\""));
    assertTrue(index.contains("<h1 id=\"component-search-title\">Search Maven</h1>"));
    assertTrue(index.contains(
        "/browse/assets/global-component-search.css?v=20260819-topbar-control-height-1"));
    assertTrue(index.contains("/browse/assets/global-component-search.js"));

    assertTrue(javascript.contains("all: \"all\""));
    assertTrue(javascript.contains("custom: \"custom\""));
    assertTrue(javascript.contains("effectiveFormat !== ALL_SEARCH_FORMAT"));
    assertTrue(javascript.contains("normalizeCustomSearchFormat(customFormat)"));
    assertTrue(javascript.contains("keyword: params.get(\"q\") || \"\""));
    assertTrue(javascript.contains(
        "showSearch(route.searchFormat, false, route.keyword, route.customSearchFormat)"));
    assertTrue(javascript.contains("document.getElementById(\"component-search-form\")"));
    assertTrue(javascript.contains("searchPageTitle(activeSearchFormat)"));
    assertTrue(javascript.contains("bindCustomSearchFormatCombobox();"));
    assertTrue(javascript.contains("filterCustomSearchFormatOptions"));
    assertTrue(javascript.contains("moveActiveCustomSearchFormatOption"));

    assertTrue(browseStylesheet.contains(".search-format-popover"));
    assertTrue(browseStylesheet.contains(".search-form .search-format-filter"));
    assertTrue(browseStylesheet.contains("top: calc(100% + 6px);"));
    assertTrue(browseStylesheet.contains(".search-format-option .format-logo"));

    assertTrue(sharedJavascript.contains("/browse/#browse/search/all"));
    assertTrue(sharedJavascript.contains("locationRef.assign(destination)"));
    assertTrue(sharedStylesheet.contains(".global-component-search input:focus-visible"));
    assertFalse(sharedStylesheet.contains("height: 34px;"));
    assertEquals(2L, sharedStylesheet.lines()
        .filter(line -> line.trim().equals("height: 40px;"))
        .count());
    assertEquals(1L, accountMenuStylesheet.lines()
        .filter(line -> line.trim().equals("height: 40px;"))
        .count());
    assertTrue(sharedStylesheet.contains("margin: 0 12px 0 24px"));
    assertTrue(sharedStylesheet.contains("@media (max-width: 760px)"));
  }

  private String resource(String path) throws IOException {
    try (InputStream stream = getClass().getResourceAsStream(path)) {
      return new String(Objects.requireNonNull(stream).readAllBytes(), StandardCharsets.UTF_8);
    }
  }
}
