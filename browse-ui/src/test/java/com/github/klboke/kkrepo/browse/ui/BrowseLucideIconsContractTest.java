package com.github.klboke.kkrepo.browse.ui;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import org.junit.jupiter.api.Test;

class BrowseLucideIconsContractTest {
  private static final List<String> REQUIRED_ICONS = List.of(
      "home", "search", "library", "upload", "key-round", "list-filter",
      "boxes", "cloud-download", "package", "folder", "archive", "file-code",
      "files", "file", "file-archive", "file-java-archive", "file-box", "file-json", "file-text",
      "file-key", "file-check", "binary", "arrow-up-down", "copy", "check", "download", "x");
  private static final List<String> LEGACY_GLYPHS = List.of(
      "⌂", "⌕", "▣", "▤", "▥", "⇧", "ⓘ", "📋", "📄", "⚙", "▰", "⊘");

  @Test
  void browseUsesVendoredLucideIconsInsteadOfCharacterGlyphs() throws IOException {
    String index = resource("/META-INF/resources/browse/index.html");
    String javascript = resource("/META-INF/resources/browse/assets/browse.js");
    String stylesheet = resource("/META-INF/resources/browse/assets/lucide-icons.css");

    assertTrue(index.contains("/browse/assets/lucide-icons.css"));
    assertTrue(index.contains("lucide-icon icon-home"));
    assertTrue(javascript.contains("function lucideIcon(name, className = \"\")"));
    assertTrue(javascript.contains("function fileIconName(name, repositoryFormat = \"\")"));
    assertTrue(stylesheet.contains("mask-image: var(--lucide-icon)"));
    assertFalse(javascript.contains("<svg class=\"tree-icon\""));
    for (String glyph : LEGACY_GLYPHS) {
      assertFalse(index.contains(glyph), glyph);
      assertFalse(javascript.contains(glyph), glyph);
    }
    for (String icon : REQUIRED_ICONS) {
      assertTrue(stylesheet.contains(".icon-" + icon + " "), icon);
      try (InputStream stream = getClass().getResourceAsStream(
          "/META-INF/resources/browse/assets/icons/" + icon + ".svg")) {
        assertNotNull(stream, icon);
      }
    }
  }

  @Test
  void iconOnlyButtonsHaveTooltipsAndAccessibleNames() throws IOException {
    String index = resource("/META-INF/resources/browse/index.html");
    String javascript = resource("/META-INF/resources/browse/assets/browse.js");

    assertFalse(index.contains("repo-copy-column"));
    assertTrue(index.contains(
        "id=\"repository-copy-status\" role=\"status\" aria-live=\"polite\""));
    assertTrue(javascript.contains("class=\"repo-url-content\""));
    assertTrue(javascript.contains(
        "title=\"Copy repository URL\" aria-label=\"Copy repository URL\""));
    assertTrue(javascript.contains(
        ">${escapeHtml(clientUrl)}</a>"));
    assertTrue(javascript.contains(
        "iconForFile(entry.name || entry.path, \"crumb-icon\")"));
    assertTrue(index.contains(
        "repo-sort-indicator lucide-icon icon-arrow-up-down\" aria-hidden=\"true\"></span>\n                      <span>Type</span>"));
    assertTrue(javascript.contains(
        "indicator.classList.toggle(\"icon-arrow-up-down\", !active)"));
    assertTrue(javascript.contains(
        "title=\"Copy\" aria-label=\"Copy usage snippet\""));
    assertTrue(javascript.contains("toggle.title = label"));
    assertTrue(javascript.contains("toggle.setAttribute(\"aria-label\", label)"));
  }

  private String resource(String path) throws IOException {
    try (InputStream stream = getClass().getResourceAsStream(path)) {
      return new String(Objects.requireNonNull(stream).readAllBytes(), StandardCharsets.UTF_8);
    }
  }
}
