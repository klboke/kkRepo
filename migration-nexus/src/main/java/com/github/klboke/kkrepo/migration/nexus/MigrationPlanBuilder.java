package com.github.klboke.kkrepo.migration.nexus;

import com.github.klboke.kkrepo.migration.nexus.NexusMigrationPlan.NexusMigrationPlanItem;
import com.github.klboke.kkrepo.migration.nexus.NexusMigrationPlan.SupportStatus;
import com.github.klboke.kkrepo.migration.nexus.NexusSourceProfile.RepositoryCapability;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

public class MigrationPlanBuilder {

  public NexusMigrationPlan build(NexusSourceProfile profile, MigrationScope scope) {
    NexusSourceProfile source = profile == null
        ? new NexusSourceProfile(
            "unknown",
            null,
            null,
            NexusSourceProfile.MetadataEngine.UNKNOWN,
            null,
            null,
            null,
            List.of(),
            java.util.Map.of(),
            List.of())
        : profile;
    MigrationScope selectedScope = scope == null ? MigrationScope.defaultScope() : scope;
    NexusMigrationAdapter adapter = NexusMigrationAdapters.select(source);
    List<NexusMigrationPlanItem> items = new ArrayList<>();
    for (RepositoryCapability repository : source.repositories()) {
      if (!selectedScope.includesRepository(repository.name())) {
        items.add(skippedRepository(repository, adapter.name()));
        continue;
      }
      items.add(repositoryItem(source, repository, adapter, selectedScope));
    }
    if (selectedScope.includeSecurity()) {
      items.add(securityItem(source, adapter.name()));
    }
    items.sort(Comparator
        .comparing(NexusMigrationPlanItem::area)
        .thenComparing(NexusMigrationPlanItem::name, Comparator.nullsLast(String::compareTo))
        .thenComparing(NexusMigrationPlanItem::format, Comparator.nullsLast(String::compareTo)));
    List<String> manualActions = items.stream()
        .filter(item -> item.status() == SupportStatus.NEEDS_MANUAL_ACTION
            || item.status() == SupportStatus.UNSUPPORTED
            || item.status() == SupportStatus.DATA_ONLY)
        .map(item -> item.area() + ":" + item.name())
        .distinct()
        .toList();
    List<String> warnings = new ArrayList<>(source.warnings());
    if (!manualActions.isEmpty()) {
      warnings.add("Some migration areas require manual action or are unsupported; automatic execution must fail closed for those areas.");
    }
    String profileHash = MigrationPlanHashes.profileHash(source);
    String planHash = MigrationPlanHashes.planHash(adapter.name(), items, warnings, manualActions);
    return new NexusMigrationPlan(
        adapter.name(),
        profileHash,
        planHash,
        items,
        warnings.stream().distinct().toList(),
        manualActions);
  }

  private static NexusMigrationPlanItem repositoryItem(
      NexusSourceProfile profile,
      RepositoryCapability repository,
      NexusMigrationAdapter adapter,
      MigrationScope scope) {
    String format = lower(repository.format());
    String type = lower(repository.type());
    boolean supportedRecipe = NexusRepositorySupport.supportedRecipe(repository.format(), repository.type());
    List<String> reasons = new ArrayList<>();
    List<String> warnings = new ArrayList<>();
    SupportStatus status;
    String readMode;
    String checksumMode;
    if (!supportedRecipe) {
      status = SupportStatus.UNSUPPORTED;
      readMode = "none";
      checksumMode = "none";
      reasons.add(NexusRepositorySupport.unsupportedReason(repository.format(), repository.type()));
    } else if ("proxy".equals(type) && !scope.migrateProxyArtifacts()) {
      status = adapter.repositoryStatus(profile, repository.format(), repository.type(), false);
      readMode = adapter.repositoryReadMode(profile, repository.format(), repository.type(), false);
      checksumMode = adapter.checksumMode(profile, repository.format(), repository.type(), false);
      reasons.add("Proxy cache artifacts are not migrated by default; repository configuration is migrated.");
    } else if ("proxy".equals(type) && "terraform".equals(format)) {
      status = SupportStatus.CONFIG_ONLY;
      readMode = "repository-config-rest";
      checksumMode = "not-applicable";
      reasons.add("Terraform proxy cache artifacts are not imported because the source profile does not prove "
          + "remote route, validator, checksum, and signature snapshot semantics; repository configuration is migrated.");
      warnings.add("Terraform proxy cache backup is rejected before a repository-data job is created.");
    } else if ("group".equals(type)) {
      status = SupportStatus.CONFIG_ONLY;
      readMode = "repository-config-rest";
      checksumMode = "not-applicable";
      reasons.add("Group repositories migrate member configuration only; no hosted package/blob content is exported.");
    } else if (adapter.repositoryStatus(profile, repository.format(), repository.type(), scope.migrateProxyArtifacts())
        == SupportStatus.FULL) {
      status = SupportStatus.FULL;
      readMode = adapter.repositoryReadMode(profile, repository.format(), repository.type(), scope.migrateProxyArtifacts());
      checksumMode = adapter.checksumMode(profile, repository.format(), repository.type(), scope.migrateProxyArtifacts());
      reasons.add(contentExporterReason(readMode));
    } else if (profile.metadataEngine() == NexusSourceProfile.MetadataEngine.DATASTORE_H2
        || profile.metadataEngine() == NexusSourceProfile.MetadataEngine.DATASTORE_POSTGRESQL) {
      status = adapter.repositoryStatus(profile, repository.format(), repository.type(), scope.migrateProxyArtifacts());
      readMode = adapter.repositoryReadMode(profile, repository.format(), repository.type(), scope.migrateProxyArtifacts());
      checksumMode = adapter.checksumMode(profile, repository.format(), repository.type(), scope.migrateProxyArtifacts());
      reasons.add("Datastore content schema fingerprint is incomplete for this format; repository content migration stays configuration-only.");
      warnings.add("Datastore content exporter is not enabled until required tables and columns are present for this format.");
    } else {
      status = SupportStatus.NEEDS_MANUAL_ACTION;
      readMode = "repository-config-rest";
      checksumMode = "manual";
      reasons.add("Source metadata engine is unknown; automatic content migration is blocked.");
    }
    return new NexusMigrationPlanItem(
        "repository",
        repository.name(),
        repository.format(),
        repository.type(),
        status,
        adapter.name(),
        formatAdapter(format),
        readMode,
        "kkrepo-dao-upsert",
        checksumMode,
        "repository/" + nullToEmpty(repository.name()),
        reasons,
        warnings);
  }

  private static NexusMigrationPlanItem skippedRepository(
      RepositoryCapability repository,
      String adapter) {
    return new NexusMigrationPlanItem(
        "repository",
        repository.name(),
        repository.format(),
        repository.type(),
        SupportStatus.NEEDS_MANUAL_ACTION,
        adapter,
        formatAdapter(repository.format()),
        "none",
        "none",
        "none",
        "repository/" + nullToEmpty(repository.name()),
        List.of("Repository is outside the user-selected migration scope."),
        List.of());
  }

  private static NexusMigrationPlanItem securityItem(
      NexusSourceProfile profile,
      String adapter) {
    List<String> reasons = new ArrayList<>();
    List<String> warnings = new ArrayList<>();
    SupportStatus status;
    String readMode;
    if (profile.securityModel() == NexusSourceProfile.SecurityModel.REST_WITH_MANUAL_SECRETS) {
      status = SupportStatus.DATA_ONLY;
      readMode = profile.scriptApi().runnable() ? "rest-and-script-manual-secrets" : "rest-only";
      reasons.add("Security REST metadata can be migrated, but password hashes or token material require reset or reissue.");
      warnings.add("Source security export did not include all secret material; manual reset or reissue is required.");
    } else if (profile.scriptApi().runnable()) {
      status = SupportStatus.FULL;
      readMode = "rest-and-script";
      reasons.add("REST security metadata plus script compensation are available.");
    } else {
      status = SupportStatus.DATA_ONLY;
      readMode = "rest-only";
      reasons.add("Security REST metadata can be migrated, but password hashes or token material may need reset.");
    }
    return new NexusMigrationPlanItem(
        "security",
        "local-security",
        null,
        null,
        status,
        adapter,
        "security",
        readMode,
        "kkrepo-security-dao",
        "security-object-counts",
        "security/local",
        reasons,
        warnings);
  }

  private static String formatAdapter(String format) {
    String normalized = lower(format);
    if (normalized == null) {
      return "UnknownFormatMigrationAdapter";
    }
    return normalized.substring(0, 1).toUpperCase(Locale.ROOT)
        + normalized.substring(1)
        + "MigrationAdapter";
  }

  private static String contentExporterReason(String readMode) {
    if ("script-datastore".equals(readMode)) {
      return "Datastore content schema fingerprint matched; datastore script exporter is selected.";
    }
    if ("script-orientdb".equals(readMode)) {
      return "OrientDB script exporter path is selected.";
    }
    return "Repository content exporter path is selected.";
  }

  private static String lower(String value) {
    if (value == null || value.isBlank()) {
      return null;
    }
    return value.trim().toLowerCase(Locale.ROOT);
  }

  private static String nullToEmpty(String value) {
    return value == null ? "" : value;
  }

  public record MigrationScope(
      List<String> repositoryNames,
      boolean includeSecurity,
      boolean migrateProxyArtifacts) {

    public MigrationScope {
      repositoryNames = repositoryNames == null
          ? List.of()
          : repositoryNames.stream()
              .filter(value -> value != null && !value.isBlank())
              .map(String::trim)
              .distinct()
              .toList();
    }

    public boolean includesRepository(String name) {
      return repositoryNames.isEmpty() || repositoryNames.contains(name);
    }

    static MigrationScope defaultScope() {
      return new MigrationScope(List.of(), true, false);
    }
  }
}
