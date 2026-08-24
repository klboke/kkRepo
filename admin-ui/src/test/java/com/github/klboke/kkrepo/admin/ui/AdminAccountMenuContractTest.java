package com.github.klboke.kkrepo.admin.ui;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import org.junit.jupiter.api.Test;

class AdminAccountMenuContractTest {

  @Test
  void administrationUsesTheSharedBrowseAccountMenu() throws IOException {
    String index = resource("/META-INF/resources/admin/index.html");

    assertTrue(index.contains("/browse/assets/account-menu.css?v=20260824-ui-themes-3"));
    assertTrue(index.contains("user-avatar-icon lucide-icon icon-user-round"));
    assertTrue(index.contains("class=\"user-name\" id=\"current-user\""));
    assertFalse(index.contains("current-user-source"));
    assertTrue(index.contains("id=\"user-menu-source\""));
    assertTrue(index.contains("id=\"my-token-menu-item\""));
    assertTrue(index.contains("user-menu-item-danger"));
  }

  @Test
  void administrationKeepsDetailsInThePopoverAndLinksToMyToken() throws IOException {
    String javascript = resource("/META-INF/resources/admin/assets/admin.js");

    assertTrue(javascript.contains("function accountSourceLabel(source)"));
    assertTrue(javascript.contains("currentUser.textContent = userId"));
    assertTrue(javascript.contains("userMenuSource.textContent = sourceLabel"));
    assertTrue(javascript.contains(
        "userMenuTrigger.setAttribute(\"aria-label\", `Account menu for ${qualifiedUser}`)"));
    assertTrue(javascript.contains("window.location.href = \"/browse/#browse/my-token\""));
  }

  private String resource(String path) throws IOException {
    try (InputStream stream = getClass().getResourceAsStream(path)) {
      return new String(Objects.requireNonNull(stream).readAllBytes(), StandardCharsets.UTF_8);
    }
  }
}
