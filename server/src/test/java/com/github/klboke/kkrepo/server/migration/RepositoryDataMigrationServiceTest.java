package com.github.klboke.kkrepo.server.migration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

import com.github.klboke.kkrepo.migration.nexus.MigrationPlanBuilder;
import com.github.klboke.kkrepo.migration.nexus.MigrationPlanBuilder.MigrationScope;
import com.github.klboke.kkrepo.migration.nexus.NexusMigrationPlan;
import com.github.klboke.kkrepo.migration.nexus.NexusRestClient.NexusInventory;
import com.github.klboke.kkrepo.migration.nexus.NexusRestClient.RepositoryDocument;
import com.github.klboke.kkrepo.migration.nexus.NexusRestClient.SourceProbe;
import com.github.klboke.kkrepo.migration.nexus.security.NexusSecurityExport;
import com.github.klboke.kkrepo.migration.nexus.NexusSourceProfile;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;

class RepositoryDataMigrationServiceTest {

  @Test
  void requestedRepositoriesLimitHostedDataMigrationScope() throws Exception {
    List<?> sources = sourceRepositories(
        List.of("docker-hosted"),
        List.of(),
        inventory(
            repository("maven-releases", "maven2", "hosted"),
            repository("docker-hosted", "docker", "hosted"),
            repository("docker-proxy", "docker", "proxy")));

    assertEquals(1, sources.size());
    assertEquals("docker-hosted", sourceName(sources.get(0)));
  }

  @Test
  void requestedRepositoriesRejectProxyRepositories() {
    IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class, () ->
        sourceRepositories(
            List.of("docker-proxy"),
            List.of(),
            inventory(
                repository("docker-hosted", "docker", "hosted"),
                repository("docker-proxy", "docker", "proxy"))));

    assertEquals(
        "Repositories are invalid for hosted data migration: [docker-proxy (proxy)]",
        thrown.getMessage());
  }

  @Test
  void composerProxyIsNotSelectedUnlessExplicitlyRequestedForBackup() throws Exception {
    List<?> sources = sourceRepositories(
        List.of(),
        List.of(),
        inventory(
            datastoreProbe("composer", true),
            repository("composer-proxy", "composer", "proxy")));

    assertEquals(List.of(), sources);
  }

  @Test
  void composerProxyUsesExplicitProxyBackupMigrationMode() throws Exception {
    List<?> sources = sourceRepositories(
        List.of(),
        List.of("composer-proxy"),
        inventory(
            datastoreProbe("composer", true),
            repository("composer-proxy", "composer", "proxy")));

    assertEquals(1, sources.size());
    assertEquals("composer-proxy", sourceName(sources.getFirst()));
    assertEquals("proxy-backup", sourceMigrationMode(sources.getFirst()));
  }

  @Test
  void terraformProxyUsesExplicitProxyBackupMigrationMode() throws Exception {
    List<?> sources = sourceRepositories(
        List.of(),
        List.of("terraform-proxy"),
        inventory(
            datastoreProbe("terraform", true),
            repository("terraform-proxy", "terraform", "proxy")));

    assertEquals(1, sources.size());
    assertEquals("terraform-proxy", sourceName(sources.getFirst()));
    assertEquals("proxy-backup", sourceMigrationMode(sources.getFirst()));
  }

  @Test
  void requestedRepositoriesAllowDatastoreCargoHostedMigrationWhenContentSchemaIsPresent() throws Exception {
    List<?> sources = sourceRepositories(
        List.of("cargo-hosted"),
        List.of(),
        inventory(
            datastoreProbe("cargo", true),
            repository("cargo-hosted", "cargo", "hosted")));

    assertEquals(1, sources.size());
    assertEquals("cargo-hosted", sourceName(sources.get(0)));
  }

  @Test
  void requestedRepositoriesRejectConfigOnlyPlanItems() {
    NexusInventory inventory = inventory(
        datastoreProbe("maven2", false),
        repository("maven-releases", "maven2", "hosted"));

    IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class, () ->
        sourceRepositories(
            List.of("maven-releases"),
            List.of(),
            inventory));

    assertEquals(
        "Repositories are invalid for hosted data migration: [maven-releases (plan status CONFIG_ONLY)]",
        thrown.getMessage());
  }

  @Test
  void repositoryDataJobOptionsPersistProfileAndPlanHashes() throws Exception {
    NexusInventory inventory = inventory(
        repository("docker-hosted", "docker", "hosted"));
    NexusSourceProfile profile = NexusSourceProfile.fromInventory(inventory, "3.29.2-02");
    NexusMigrationPlan plan = new MigrationPlanBuilder().build(
        profile,
        new MigrationScope(List.of("docker-hosted"), true, false));
    Object sourceRepository = sourceRepositories(
        List.of("docker-hosted"),
        List.of(),
        inventory).get(0);
    RepositoryDataMigrationService.RepositoryDataMigrationRequest request =
        new RepositoryDataMigrationService.RepositoryDataMigrationRequest(
            "http://localhost:28090",
            "admin",
            "secret",
            "3.29.2-02",
            100,
            1,
            true,
            Instant.parse("2026-06-30T00:00:00Z"),
            List.of("docker-hosted"),
            List.of());

    Map<?, ?> options = jobOptions(request, 100, 1, true, List.of(sourceRepository), profile, plan);

    assertEquals("OrientDbNexusAdapter", options.get("sourceAdapter"));
    assertEquals(plan.profileHash(), options.get("profileHash"));
    assertEquals(plan.planHash(), options.get("planHash"));
    assertEquals("repository-data", options.get("scope"));
    assertEquals(List.of("docker-hosted"), options.get("repositories"));
  }

  @Test
  void repositoryDataJobVersionPrefersProbedSourceProfileVersion() {
    NexusInventory inventory = inventory(
        datastoreProbe("docker", true),
        repository("docker-hosted", "docker", "hosted"));
    NexusSourceProfile profile = NexusSourceProfile.fromInventory(inventory, null);
    RepositoryDataMigrationService.RepositoryDataMigrationRequest request =
        new RepositoryDataMigrationService.RepositoryDataMigrationRequest(
            "http://localhost:28092",
            "admin",
            "secret",
            null,
            100,
            1,
            true,
            null,
            List.of("docker-hosted"),
            List.of());

    assertEquals(
        "3.77.2-02",
        RepositoryDataMigrationService.resolvedSourceNexusVersion(profile, request));
  }

  @Test
  void repositoryDataStatusSummaryExposesPlanWithoutSourcePassword() {
    NexusInventory inventory = inventory(
        repository("docker-hosted", "docker", "hosted"));
    NexusSourceProfile profile = NexusSourceProfile.fromInventory(inventory, "3.29.2-02");
    NexusMigrationPlan plan = new MigrationPlanBuilder().build(
        profile,
        new MigrationScope(List.of("docker-hosted"), true, false));
    RepositoryDataMigrationService.RepositoryDataMigrationStatus status =
        new RepositoryDataMigrationService.RepositoryDataMigrationStatus(
            7L,
            "running",
            Instant.parse("2026-06-30T00:00:00Z"),
            "3.29.2-02",
            "http://localhost:28090",
            1,
            3,
            4,
            2,
            1,
            1,
            false,
            true,
            false,
            plan.adapter(),
            plan.profileHash(),
            plan.planHash(),
            profile,
            plan,
            List.of());

    Map<String, Object> summary = status.toSummary("running");

    assertEquals("3.29.2-02", summary.get("sourceNexusVersion"));
    assertEquals("http://localhost:28090", summary.get("sourceBaseUrl"));
    assertEquals(plan.adapter(), summary.get("sourceAdapter"));
    assertEquals(plan.profileHash(), summary.get("profileHash"));
    assertEquals(plan.planHash(), summary.get("planHash"));
    assertEquals(profile, summary.get("sourceProfile"));
    assertEquals(plan, summary.get("migrationPlan"));
    assertEquals(false, summary.containsKey("sourcePassword"));
  }

  private static List<?> sourceRepositories(
      List<String> repositories,
      List<String> backupProxyRepositories,
      NexusInventory inventory) throws Exception {
    RepositoryDataMigrationService service = new RepositoryDataMigrationService(
        null, null, null, null, mock(PlatformTransactionManager.class));
    Method method = RepositoryDataMigrationService.class.getDeclaredMethod(
        "sourceRepositories",
        NexusInventory.class,
        List.class,
        List.class,
        NexusMigrationPlan.class);
    method.setAccessible(true);
    NexusSourceProfile profile = NexusSourceProfile.fromInventory(inventory, "3.29.2-02");
    NexusMigrationPlan plan = new MigrationPlanBuilder().build(
        profile,
        new MigrationScope(scope(repositories, backupProxyRepositories), true, !backupProxyRepositories.isEmpty()));
    try {
      return (List<?>) method.invoke(service, inventory, repositories, backupProxyRepositories, plan);
    } catch (InvocationTargetException e) {
      if (e.getCause() instanceof RuntimeException runtime) {
        throw runtime;
      }
      throw e;
    }
  }

  private static String sourceName(Object sourceRepository) throws Exception {
    Method method = sourceRepository.getClass().getDeclaredMethod("name");
    method.setAccessible(true);
    return (String) method.invoke(sourceRepository);
  }

  private static String sourceMigrationMode(Object sourceRepository) throws Exception {
    Method method = sourceRepository.getClass().getDeclaredMethod("migrationMode");
    method.setAccessible(true);
    return (String) method.invoke(sourceRepository);
  }

  private static Map<?, ?> jobOptions(
      RepositoryDataMigrationService.RepositoryDataMigrationRequest request,
      int pageSize,
      int concurrency,
      boolean checksumValidation,
      List<?> repositories,
      NexusSourceProfile profile,
      NexusMigrationPlan plan) throws Exception {
    RepositoryDataMigrationService service = new RepositoryDataMigrationService(
        null, null, null, null, mock(PlatformTransactionManager.class));
    Method method = RepositoryDataMigrationService.class.getDeclaredMethod(
        "jobOptions",
        RepositoryDataMigrationService.RepositoryDataMigrationRequest.class,
        int.class,
        int.class,
        boolean.class,
        List.class,
        NexusSourceProfile.class,
        NexusMigrationPlan.class);
    method.setAccessible(true);
    return (Map<?, ?>) method.invoke(
        service,
        request,
        pageSize,
        concurrency,
        checksumValidation,
        repositories,
        profile,
        plan);
  }

  private static NexusInventory inventory(RepositoryDocument... repositories) {
    return new NexusInventory(
        List.of(Map.of("name", "default")),
        List.of(repositories),
        NexusSecurityExport.empty(),
        List.of());
  }

  private static NexusInventory inventory(SourceProbe probe, RepositoryDocument... repositories) {
    return new NexusInventory(
        List.of(Map.of("name", "default")),
        List.of(repositories),
        NexusSecurityExport.empty(),
        List.of(),
        probe);
  }

  private static RepositoryDocument repository(String name, String format, String type) {
    return new RepositoryDocument(
        Map.of("name", name, "format", format, "type", type),
        Map.of("name", name, "format", format, "type", type));
  }

  private static SourceProbe datastoreProbe(String format, boolean contentMigration) {
    String prefix = "maven2".equals(format) ? "MAVEN2" : format.toUpperCase();
    return new SourceProbe(
        "3.77.2-02",
        true,
        true,
        true,
        "text/plain",
        "ok",
        "DATASTORE_H2",
        "H2 2.3.232",
        "jdbc:h2:file:/nexus-data/db/nexus",
        Map.of(
            "columns", List.of("ID", "NAME", "RECIPE_NAME", "ATTRIBUTES"),
            "datastoreContentModels", Map.of(format, Map.of(
                "prefix", prefix,
                "tablesPresent", true,
                "requiredColumnsPresent", contentMigration,
                "tables", Map.of(),
                "columns", Map.of()))),
        List.of());
  }

  private static List<String> scope(List<String> repositories, List<String> backupProxyRepositories) {
    java.util.LinkedHashSet<String> values = new java.util.LinkedHashSet<>();
    if (repositories != null) {
      values.addAll(repositories);
    }
    if (backupProxyRepositories != null) {
      values.addAll(backupProxyRepositories);
    }
    return List.copyOf(values);
  }
}
