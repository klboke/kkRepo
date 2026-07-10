package com.github.klboke.kkrepo.admin.ui;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import org.junit.jupiter.api.Test;

class AdminLucideIconsContractTest {
  private static final List<String> LEGACY_GLYPHS = List.of(
      "▥", "▤", "▧", "◉", "◈", "◍", "◇", "◒", "◌", "⌘", "▦", "▨", "⊕", "↻", "⠿");

  @Test
  void administrationUsesSharedLucideIconSystem() throws IOException {
    String index = resource("/META-INF/resources/admin/index.html");
    String javascript = resource("/META-INF/resources/admin/assets/admin.js");

    assertTrue(index.contains("/browse/assets/lucide-icons.css"));
    assertTrue(index.contains("side-item-icon lucide-icon icon-package"));
    assertTrue(index.contains("heading-icon\"><span class=\"lucide-icon icon-package\""));
    assertTrue(javascript.contains("function lucideIcon(name, className = \"\")"));
    for (String glyph : LEGACY_GLYPHS) {
      assertFalse(index.contains(glyph), glyph);
      assertFalse(javascript.contains(glyph), glyph);
    }
  }

  @Test
  void iconOnlyButtonsHaveTooltipsAndAccessibleNames() throws IOException {
    String index = resource("/META-INF/resources/admin/index.html");
    String login = resource("/META-INF/resources/login/assets/login-modal.js");

    assertTrue(index.contains(
        "id=\"member-add-all\" title=\"Add all\" aria-label=\"Add all\""));
    assertTrue(index.contains(
        "id=\"audit-log-prev-page\" type=\"button\" title=\"Previous page\" aria-label=\"Previous page\""));
    assertTrue(index.contains(
        "aria-label=\"Close dialog\" title=\"Close dialog\"><span class=\"lucide-icon icon-x\""));
    assertTrue(login.contains(
        "aria-label=\"Close sign-in dialog\" title=\"Close dialog\""));
  }

  private String resource(String path) throws IOException {
    try (InputStream stream = getClass().getResourceAsStream(path)) {
      return new String(Objects.requireNonNull(stream).readAllBytes(), StandardCharsets.UTF_8);
    }
  }
}
