package com.github.klboke.kkrepo.admin.ui;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import org.junit.jupiter.api.Test;

class AdminSecurityScanningCapabilityContractTest {

  @Test
  void deploymentGateKeepsScanningControlsDisabledUntilCapabilityIsAvailable()
      throws IOException {
    String index = resource("/META-INF/resources/admin/index.html");
    String javascript = resource("/META-INF/resources/admin/assets/admin.js");
    String css = resource("/META-INF/resources/admin/assets/admin.css");

    assertTrue(index.contains("id=\"security-scan-capability-banner\""));
    assertTrue(index.contains("id=\"security-scan-capability-content\""));
    assertTrue(index.contains("aria-disabled=\"true\" inert"));
    assertTrue(javascript.contains("applySecurityScanDeploymentState"));
    assertTrue(javascript.contains("securityScanState.summary?.deploymentEnabled === true"));
    assertTrue(javascript.contains("content.inert = !available"));
    assertTrue(javascript.contains("control.disabled = true"));
    assertTrue(javascript.contains("KKREPO_SECURITY_SCANNING_ENABLED=true"));
    assertTrue(css.contains(".is-deployment-disabled .security-scan-capability-content"));
  }

  private String resource(String path) throws IOException {
    try (InputStream stream = getClass().getResourceAsStream(path)) {
      return new String(Objects.requireNonNull(stream).readAllBytes(), StandardCharsets.UTF_8);
    }
  }
}
