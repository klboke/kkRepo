package com.github.klboke.kkrepo.browse.ui;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import org.junit.jupiter.api.Test;

class BrowseAuthenticationPerformanceContractTest {

  @Test
  void startupUsesAggregatedContextAndHydratesBeforeNetworkCompletion() throws IOException {
    String javascript = resource("/META-INF/resources/browse/assets/browse.js");

    assertTrue(javascript.contains("hydrateAuthSnapshot();"));
    assertTrue(javascript.contains("fetch(\"/internal/security/context\""));
    assertTrue(javascript.contains("Promise.all([contextPromise, repositoriesPromise])"));
    assertFalse(javascript.contains("currentSession = await fetchSession()"));
    assertFalse(javascript.contains("currentPermissions = currentSession ? await fetchPermissions() : []"));
  }

  @Test
  void uploadRepositoryDiscoveryIsLazyAndLoginDoesNotReloadThePage() throws IOException {
    String browse = resource("/META-INF/resources/browse/assets/browse.js");
    String login = resource("/META-INF/resources/login/assets/login-modal.js");

    assertTrue(browse.contains("function ensureUploadableRepositories(force = false)"));
    assertTrue(browse.contains("window.addEventListener(\"kkrepo:login-success\", handleLoginSuccess)"));
    assertTrue(login.contains("new CustomEvent(\"kkrepo:login-success\""));
    assertFalse(login.contains("window.location.reload()"));
  }

  private String resource(String path) throws IOException {
    try (InputStream stream = getClass().getResourceAsStream(path)) {
      return new String(Objects.requireNonNull(stream).readAllBytes(), StandardCharsets.UTF_8);
    }
  }
}
