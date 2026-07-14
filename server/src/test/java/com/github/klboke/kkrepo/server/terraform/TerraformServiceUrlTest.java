package com.github.klboke.kkrepo.server.terraform;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class TerraformServiceUrlTest {
  private static final String BASE = "https://repo.example/repository/terraform-private";

  @Test
  void keepsUrlTokenOnModuleAndProviderFollowUpUrls() {
    assertEquals(
        BASE + "/v1/modules/dXNlcjpwYXNz/acme/network/aws/1.2.3/module.zip",
        TerraformService.publicUrl(
            BASE, "dXNlcjpwYXNz", "v1/modules/acme/network/aws/1.2.3/module.zip"));
    assertEquals(
        BASE + "/v1/providers/dXNlcjpwYXNz/acme/cloud/1.2.3/package/linux/provider.zip",
        TerraformService.publicUrl(
            BASE, "dXNlcjpwYXNz", "v1/providers/acme/cloud/1.2.3/package/linux/provider.zip"));
  }

  @Test
  void leavesCanonicalUrlsAndInternalPathsUnchanged() {
    assertEquals(
        BASE + "/v1/modules/acme/network/aws/versions",
        TerraformService.publicUrl(BASE, null, "v1/modules/acme/network/aws/versions"));
    assertEquals(
        BASE + "/.terraform/routes/value.json",
        TerraformService.publicUrl(BASE, "dXNlcjpwYXNz", ".terraform/routes/value.json"));
  }
}
