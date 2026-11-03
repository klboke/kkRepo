package com.github.klboke.kkrepo.server.migration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.core.RepositoryType;
import com.github.klboke.kkrepo.migration.nexus.MigrationPlanBuilder.MigrationScope;
import com.github.klboke.kkrepo.migration.nexus.MigrationPlanBuilder;
import com.github.klboke.kkrepo.migration.nexus.NexusMigrationPlan;
import com.github.klboke.kkrepo.migration.nexus.NexusRestClient.NexusInventory;
import com.github.klboke.kkrepo.migration.nexus.NexusRestClient.RepositoryDocument;
import com.github.klboke.kkrepo.migration.nexus.NexusRestClient.SourceProbe;
import com.github.klboke.kkrepo.migration.nexus.NexusRestClient;
import com.github.klboke.kkrepo.migration.nexus.NexusSourceProfile;
import com.github.klboke.kkrepo.migration.nexus.security.NexusSecurityExport;
import com.github.klboke.kkrepo.persistence.jdbc.api.MigrationJobDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.RepositoryDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.RepositoryDataMigrationDao.MigrationJobProgress;
import com.github.klboke.kkrepo.persistence.jdbc.api.RepositoryDataMigrationDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.MigrationJobRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.RepositoryRecord;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;

class RepositoryDataMigrationServiceTest {

  @ParameterizedTest
  @MethodSource("migrationSelections")
  void startPreservesHostedSelectionWhenAddingProxyBackups(
      List<String> hosted, List<String> proxies, List<String> expected) throws Exception {
    NexusInventory inventory = inventory(
        repository("maven-releases", "maven2", "hosted"),
        repository("maven-snapshots", "maven2", "hosted"),
        repository("maven-central", "maven2", "proxy"),
        repository("unselected-proxy", "maven2", "proxy"));
    MigrationJobDao jobs = mock(MigrationJobDao.class);
    RepositoryDataMigrationDao migration = mock(RepositoryDataMigrationDao.class);
    RepositoryDao repositories = mock(RepositoryDao.class);
    List<String> createdRepositories = new ArrayList<>();
    List<String> createdModes = new ArrayList<>();
    when(repositories.findByName(anyString())).thenAnswer(invocation -> {
      String name = invocation.getArgument(0);
      RepositoryType type = name.equals("maven-central") ? RepositoryType.PROXY : RepositoryType.HOSTED;
      return Optional.of(new RepositoryRecord(
          9L, name, RepositoryFormat.MAVEN2, type, "maven2-" + type.name().toLowerCase(java.util.Locale.ROOT),
          true, 1L, null, null, null, null, null, true, Map.of()));
    });
    when(jobs.create(anyString(), anyString(), any())).thenReturn(7L);
    when(jobs.findById(7L)).thenReturn(Optional.of(new MigrationJobRecord(
        7L, "3.29.2-02", "http://nexus.test", "running", Map.of(), Map.of(), Instant.EPOCH, null)));
    when(migration.listRepositories(7L)).thenReturn(List.of());
    when(migration.jobProgress(7L)).thenReturn(new MigrationJobProgress(
        expected.size(), 0, 0, 0, 0, 0, true, false));
    when(migration.createRepositoryJob(eq(7L), anyString(), anyString(), anyLong(), any(), anyInt(), any()))
        .thenAnswer(invocation -> {
          createdRepositories.add(invocation.getArgument(1));
          Map<String, Object> options = invocation.getArgument(6);
          createdModes.add((String) options.get("migrationMode"));
          return (long) createdRepositories.size();
        });
    RepositoryDataMigrationService service = new RepositoryDataMigrationService(
        new ObjectMapper(), jobs, migration, repositories, mock(PlatformTransactionManager.class));
    try (var clients = mockConstruction(NexusRestClient.class,
        (client, context) -> when(client.readInventory()).thenReturn(inventory))) {
      service.start(new RepositoryDataMigrationService.RepositoryDataMigrationRequest(
          "http://nexus.test", "admin", "secret", "3.29.2-02", 100, 1, true, null, hosted, proxies));
    }

    assertEquals(expected, createdRepositories);
    assertEquals(expected.stream().map(name -> name.equals("maven-central") ? "proxy-backup" : "hosted")
        .toList(), createdModes);
    @SuppressWarnings("unchecked")
    ArgumentCaptor<Map<String, Object>> options = ArgumentCaptor.forClass(Map.class);
    verify(jobs).create(eq("3.29.2-02"), eq("http://nexus.test"), options.capture());
    assertEquals(expected, options.getValue().get("repositories"));
    NexusMigrationPlan plan = (NexusMigrationPlan) options.getValue().get("migrationPlan");
    assertEquals(expected.stream().sorted().toList(), plan.items().stream()
        .filter(item -> expected.contains(item.name()))
        .filter(item -> item.status() == NexusMigrationPlan.SupportStatus.FULL)
        .map(item -> item.name()).sorted().toList());
  }

  private static Stream<Arguments> migrationSelections() {
    List<String> allHosted = List.of("maven-releases", "maven-snapshots");
    List<String> withProxy = List.of("maven-releases", "maven-snapshots", "maven-central");
    return Stream.of(
        Arguments.of(null, null, allHosted),
        Arguments.of(List.of(), List.of(), allHosted),
        Arguments.of(null, List.of("maven-central"), withProxy),
        Arguments.of(List.of(), List.of("maven-central"), withProxy),
        Arguments.of(List.of(" "), List.of(" maven-central ", "maven-central"), withProxy),
        Arguments.of(List.of("maven-snapshots"), List.of(), List.of("maven-snapshots")),
        Arguments.of(List.of("maven-snapshots"), List.of("maven-central"),
            List.of("maven-snapshots", "maven-central")));
  }

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
