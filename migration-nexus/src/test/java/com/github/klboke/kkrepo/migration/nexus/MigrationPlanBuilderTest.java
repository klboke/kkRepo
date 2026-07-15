package com.github.klboke.kkrepo.migration.nexus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.klboke.kkrepo.migration.nexus.NexusMigrationPlan.NexusMigrationPlanItem;
import com.github.klboke.kkrepo.migration.nexus.NexusMigrationPlan.SupportStatus;
import com.github.klboke.kkrepo.migration.nexus.NexusSourceProfile.BlobModel;
import com.github.klboke.kkrepo.migration.nexus.NexusSourceProfile.ContentModelFingerprint;
import com.github.klboke.kkrepo.migration.nexus.NexusSourceProfile.FormatCapability;
import com.github.klboke.kkrepo.migration.nexus.NexusSourceProfile.MetadataEngine;
import com.github.klboke.kkrepo.migration.nexus.NexusSourceProfile.RepositoryCapability;
import com.github.klboke.kkrepo.migration.nexus.NexusSourceProfile.RepositoryModel;
import com.github.klboke.kkrepo.migration.nexus.NexusSourceProfile.ScriptApiProfile;
import com.github.klboke.kkrepo.migration.nexus.NexusSourceProfile.SecurityModel;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class MigrationPlanBuilderTest {
  @Test
  void keepsTerraformProxyCacheConfigurationOnlyWhenBackupIsRequested() {
    NexusSourceProfile profile = new NexusSourceProfile(
        "3.92.0-01",
        null,
        new ScriptApiProfile(true, true, true, "text/plain", "ok"),
        MetadataEngine.DATASTORE_POSTGRESQL,
        RepositoryModel.DATASTORE_CONTENT,
        SecurityModel.DATASTORE_SECURITY,
        new BlobModel(List.of("file"), "repository-http", true, "sha256"),
        List.of(new RepositoryCapability(
            "terraform-proxy", "terraform", "proxy", "terraform-proxy", true, "default", Map.of())),
        Map.of("terraform", new FormatCapability(
            "terraform",
            List.of("terraform-proxy"),
            true,
            true,
            "datastore schema matched",
            new ContentModelFingerprint("TERRAFORM", true, true, Map.of(), Map.of()))),
        List.of());

    NexusMigrationPlan plan = new MigrationPlanBuilder().build(
        profile,
        new MigrationPlanBuilder.MigrationScope(List.of("terraform-proxy"), true, true));

    NexusMigrationPlanItem repository = plan.items().stream()
        .filter(item -> "repository".equals(item.area()))
        .findFirst()
        .orElseThrow();
    assertEquals(SupportStatus.CONFIG_ONLY, repository.status());
    assertEquals("repository-config-rest", repository.readMode());
    assertEquals("not-applicable", repository.checksumMode());
    assertTrue(repository.reasons().stream().anyMatch(reason -> reason.contains("snapshot semantics")));
    assertTrue(repository.warnings().stream().anyMatch(warning -> warning.contains("rejected before")));
  }
}
