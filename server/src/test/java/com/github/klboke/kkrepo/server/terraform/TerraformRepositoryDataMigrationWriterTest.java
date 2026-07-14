package com.github.klboke.kkrepo.server.terraform;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class TerraformRepositoryDataMigrationWriterTest {
  @Test
  void selectsOnlyNexusModuleAndProviderArchivesForMigration() {
    assertTrue(TerraformRepositoryDataMigrationWriter.isMigratableTerraformPath(
        "/v1/modules/kkrepo/network/aws/1.2.3/kkrepo-network-aws_1.2.3.zip"));
    assertTrue(TerraformRepositoryDataMigrationWriter.isMigratableTerraformPath(
        "/v1/providers/kkrepo/fixture/1.2.3/download/linux/amd64/terraform-provider-fixture_1.2.3_linux_amd64.zip"));

    assertFalse(TerraformRepositoryDataMigrationWriter.isMigratableTerraformPath(
        "/v1/providers/kkrepo/fixture/1.2.3/download/linux/amd64/SHA256SUMS"));
    assertFalse(TerraformRepositoryDataMigrationWriter.isMigratableTerraformPath(
        "/v1/providers/kkrepo/fixture/versions.json"));
    assertFalse(TerraformRepositoryDataMigrationWriter.isMigratableTerraformPath(
        "/v1/providers/kkrepo/fixture/1.2.3/download/linux/amd64/../../escape.zip"));
  }
}
