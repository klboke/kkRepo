package com.github.klboke.kkrepo.server.securityscan;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class SecurityScanOverviewJsonTest {

  @Test
  void namesTheDeploymentCapabilityWithoutImplyingRepositoriesAreGloballyEnabled() {
    JsonNode json = new ObjectMapper().valueToTree(
        new SecurityScanManagementService.Overview(
            false, null, SecurityScannerStatus.disabled(), null, 0));

    assertTrue(json.has("deploymentEnabled"));
    assertTrue(json.has("scannerStatus"));
    assertFalse(json.get("scannerStatus").get("ready").asBoolean());
    assertFalse(json.has("globallyEnabled"));
  }

  @Test
  void repositoryViewExposesResolvedNamesInsteadOfRequiringIdLookupInTheUi() {
    JsonNode json = new ObjectMapper().valueToTree(
        new SecurityScanManagementService.RepositoryView(
            7,
            "maven-proxy",
            "MAVEN",
            "PROXY",
            "syft-grype-v1",
            "default-audit",
            null));

    assertTrue(json.has("profileName"));
    assertTrue(json.has("policyName"));
    assertFalse(json.get("profileName").asText().matches("\\d+"));
    assertFalse(json.get("policyName").asText().matches("\\d+"));
  }
}
