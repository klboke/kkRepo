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
        new SecurityScanManagementService.Overview(false, null, null, 0));

    assertTrue(json.has("deploymentEnabled"));
    assertFalse(json.has("globallyEnabled"));
  }
}
