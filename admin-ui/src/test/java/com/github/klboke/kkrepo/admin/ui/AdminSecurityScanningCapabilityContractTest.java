package com.github.klboke.kkrepo.admin.ui;

import static org.junit.jupiter.api.Assertions.assertFalse;
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

  @Test
  void repositoryFormShowsBusinessSettingsWithoutExposingInternalIds() throws IOException {
    String index = resource("/META-INF/resources/admin/index.html");
    String javascript = resource("/META-INF/resources/admin/assets/admin.js");

    assertTrue(index.contains("id=\"security-scan-profile-name\" type=\"text\" disabled"));
    assertTrue(index.contains("id=\"security-scan-profile-id\" type=\"hidden\""));
    assertTrue(index.contains(
        "id=\"security-scan-repository-policy-name\" type=\"text\" disabled"));
    assertTrue(index.contains("id=\"security-scan-policy-id\" type=\"hidden\""));
    assertFalse(index.contains("<span>Profile ID</span>"));
    assertFalse(index.contains("<span>Policy ID</span>"));

    assertTrue(index.contains("<span>Result validity</span>"));
    assertTrue(index.contains("<option value=\"86400\">1 day</option>"));
    assertTrue(index.contains("<option value=\"604800\">7 days</option>"));
    assertTrue(index.contains("<option value=\"2592000\">30 days</option>"));
    assertTrue(index.contains(
        "<details class=\"security-scan-advanced\" id=\"security-scan-advanced\">"));

    assertTrue(javascript.contains("repository.profileName || \"Unavailable profile\""));
    assertTrue(javascript.contains(
        "repository.policyName || \"Built-in critical baseline\""));
    assertTrue(javascript.contains("hostedField.hidden = !showHosted"));
    assertTrue(javascript.contains("proxyField.hidden = !showProxy"));
    assertTrue(javascript.contains("repositoryType === \"PROXY\""));
    assertTrue(javascript.contains("repositoryType === \"HOSTED\""));
  }

  @Test
  void policyCreateAndEditReuseTheStandardModalForm() throws IOException {
    String index = resource("/META-INF/resources/admin/index.html");
    String javascript = resource("/META-INF/resources/admin/assets/admin.js");

    assertTrue(index.contains(
        "id=\"security-scan-policy-form-modal\" data-form-id=\"security-scan-policy-form\""));
    assertTrue(index.contains(
        "class=\"blobstore-form modal-form security-scan-form\" "
            + "id=\"security-scan-policy-form\" hidden novalidate"));
    assertTrue(index.contains("id=\"security-scan-create-policy-button\""));
    assertTrue(index.contains("id=\"security-scan-cancel-policy-button\""));
    assertTrue(index.contains("id=\"security-scan-save-policy-button\""));
    assertTrue(index.contains("<option value=\"604800\">7 days</option>"));

    assertTrue(javascript.contains("showCreateSecurityScanPolicyForm"));
    assertTrue(javascript.contains("showEditSecurityScanPolicyForm"));
    assertTrue(javascript.contains("openFormModal(\"security-scan-policy-form\""));
    assertTrue(javascript.contains("closeFormModal(\"security-scan-policy-form\")"));
    assertTrue(javascript.contains("security-scan-policy-edit"));
    assertTrue(javascript.contains("method: editing ? \"PUT\" : \"POST\""));
    assertTrue(javascript.contains("Repositories using revision"));
  }

  private String resource(String path) throws IOException {
    try (InputStream stream = getClass().getResourceAsStream(path)) {
      return new String(Objects.requireNonNull(stream).readAllBytes(), StandardCharsets.UTF_8);
    }
  }
}
