package com.github.klboke.kkrepo.admin.ui;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import org.junit.jupiter.api.Test;

class LoginModalIconsContractTest {

  @Test
  void signInDialogUsesSharedLucideIconsWithoutObscuringPasswordControls() throws IOException {
    String javascript = resource("/META-INF/resources/login/assets/login-modal.js");
    String stylesheet = resource("/META-INF/resources/login/assets/login-modal.css");

    assertFalse(javascript.contains("login-dialog-title-icon"));
    assertTrue(javascript.contains("login-dialog-field-icon lucide-icon icon-user-round"));
    assertTrue(javascript.contains("login-dialog-field-icon lucide-icon icon-lock"));
    assertTrue(javascript.contains("login-dialog-button-icon lucide-icon icon-shield-check"));
    assertTrue(javascript.contains("login-dialog-submit\" type=\"submit\"><span class=\"login-dialog-button-icon lucide-icon icon-log-in\""));
    assertTrue(stylesheet.contains(".login-dialog-input-wrap"));
    assertTrue(stylesheet.contains("padding: 0 42px 0 40px"));
    assertTrue(stylesheet.contains(".login-dialog-input-wrap:focus-within .login-dialog-field-icon"));
  }

  private String resource(String path) throws IOException {
    try (InputStream stream = getClass().getResourceAsStream(path)) {
      return new String(Objects.requireNonNull(stream).readAllBytes(), StandardCharsets.UTF_8);
    }
  }
}
