package com.github.klboke.kkrepo.browse.ui;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import org.junit.jupiter.api.Test;

class BrowseAccountMenuContractTest {

  @Test
  void topbarUsesIconLedAccountEntriesForGuestAndSignedInStates() throws IOException {
    String index = resource("/META-INF/resources/browse/index.html");

    assertTrue(index.contains("class=\"account-login\" id=\"login-button\""));
    assertTrue(index.contains("account-login-icon lucide-icon icon-log-in"));
    assertTrue(index.contains("<span class=\"account-entry-title\">Sign in</span>"));
    assertTrue(index.contains("user-avatar-icon lucide-icon icon-user-round"));
    assertTrue(index.contains("class=\"user-name\" id=\"current-user\""));
    assertFalse(index.contains("account-entry-caption"));
    assertFalse(index.contains("current-user-source"));
    assertTrue(index.contains("user-menu-item-icon lucide-icon icon-key-round"));
    assertTrue(index.contains("user-menu-signout-icon lucide-icon icon-log-in"));
    assertTrue(index.contains("/login/assets/login-modal.css?v=20260818-login-icons-1"));
    assertTrue(index.contains("/login/assets/login-modal.js?v=20260818-login-icons-3"));
  }

  @Test
  void signedInAccountEntryHydratesIdentityAndAccessibleLabel() throws IOException {
    String javascript = resource("/META-INF/resources/browse/assets/browse.js");

    assertTrue(javascript.contains("function accountSourceLabel(source)"));
    assertFalse(javascript.contains("function accountInitial(userId)"));
    assertTrue(javascript.contains("currentUser.textContent = userId"));
    assertTrue(javascript.contains("userMenuSource.textContent = sourceLabel"));
    assertTrue(javascript.contains(
        "userMenuTrigger.setAttribute(\"aria-label\", `Account menu for ${qualifiedUser}`)"));
  }

  @Test
  void accountEntrySupportsFocusReducedMotionAndNarrowScreens() throws IOException {
    String index = resource("/META-INF/resources/browse/index.html");
    String stylesheet = resource("/META-INF/resources/browse/assets/account-menu.css");

    assertTrue(index.contains("/browse/assets/account-menu.css?v=20260824-ui-themes-3"));
    assertTrue(stylesheet.contains("width: 126px;\n  height: 40px;\n  min-width: 126px;\n  max-width: 126px;"));
    assertTrue(stylesheet.contains(".user-menu-trigger:focus-visible"));
    assertTrue(stylesheet.contains("@media (prefers-reduced-motion: reduce)"));
    assertTrue(stylesheet.contains("@media (max-width: 430px)"));
    assertTrue(stylesheet.contains("width: min(220px, calc(100vw - 16px))"));
  }

  @Test
  void signedInAvatarConsumesSharedThemeTokens() throws IOException {
    String stylesheet = resource("/META-INF/resources/browse/assets/account-menu.css");

    assertTrue(stylesheet.contains("box-shadow: 0 0 0 3px var(--account-focus-ring-color)"));
    assertTrue(stylesheet.contains("border: 1px solid var(--account-avatar-border)"));
    assertTrue(stylesheet.contains("background: var(--account-avatar-background)"));
    assertTrue(stylesheet.contains("box-shadow: var(--account-avatar-highlight)"));
    assertTrue(stylesheet.contains("color: var(--account-avatar-ink)"));
    assertTrue(stylesheet.contains("background: var(--account-avatar-presence)"));
    assertFalse(stylesheet.contains("linear-gradient(145deg, #22aa95, #0a665b)"));
  }

  private String resource(String path) throws IOException {
    try (InputStream stream = getClass().getResourceAsStream(path)) {
      return new String(Objects.requireNonNull(stream).readAllBytes(), StandardCharsets.UTF_8);
    }
  }
}
