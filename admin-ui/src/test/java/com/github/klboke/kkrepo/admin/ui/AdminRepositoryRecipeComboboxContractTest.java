package com.github.klboke.kkrepo.admin.ui;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import org.junit.jupiter.api.Test;

class AdminRepositoryRecipeComboboxContractTest {

  @Test
  void repositoryRecipeUsesAccessibleSearchableCombobox() throws IOException {
    String index = resource("/META-INF/resources/admin/index.html");
    String javascript = resource("/META-INF/resources/admin/assets/admin.js");
    String stylesheet = resource("/META-INF/resources/admin/assets/admin.css");

    assertTrue(index.contains("id=\"repository-recipe\" name=\"recipe\" required hidden"));
    assertTrue(index.contains("id=\"repository-recipe-trigger\""));
    assertTrue(index.contains("aria-haspopup=\"listbox\""));
    assertTrue(index.contains("id=\"repository-recipe-search\""));
    assertTrue(index.contains("role=\"combobox\""));
    assertTrue(index.contains("aria-autocomplete=\"list\""));
    assertTrue(index.contains("id=\"repository-recipe-options\""));
    assertTrue(index.contains("role=\"listbox\""));

    assertTrue(javascript.contains("function bindRepositoryRecipeCombobox()"));
    assertTrue(javascript.contains("select.dispatchEvent(new Event(\"change\", { bubbles: true }))"));
    assertTrue(javascript.contains("event.key === \"ArrowDown\" || event.key === \"ArrowUp\""));
    assertTrue(javascript.contains("event.key === \"Enter\""));
    assertTrue(javascript.contains("event.key === \"Escape\""));
    assertTrue(javascript.contains("closest(\"label, .form-field\")"));
    assertFalse(javascript.contains("<small>${escapeHtml(recipe?.name || \"\")}</small>"));

    assertTrue(stylesheet.contains(".recipe-combobox-trigger"));
    assertTrue(stylesheet.contains(".recipe-combobox-popover"));
    assertTrue(stylesheet.contains("z-index: 40;\n  width: 100%;"));
    assertTrue(stylesheet.contains(".recipe-combobox-option"));
    assertTrue(stylesheet.contains(".recipe-combobox-trigger:disabled"));
  }

  private String resource(String path) throws IOException {
    try (InputStream stream = getClass().getResourceAsStream(path)) {
      return new String(Objects.requireNonNull(stream).readAllBytes(), StandardCharsets.UTF_8);
    }
  }
}
