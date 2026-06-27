package com.github.klboke.kkrepo.server.migration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

import com.github.klboke.kkrepo.migration.nexus.NexusRestClient.NexusInventory;
import com.github.klboke.kkrepo.migration.nexus.NexusRestClient.RepositoryDocument;
import com.github.klboke.kkrepo.migration.nexus.security.NexusSecurityExport;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
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
  void requestedRepositoriesRejectCargoRepositoriesUntilNexus377MigrationIsDesigned() {
    IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class, () ->
        sourceRepositories(
            List.of("cargo-hosted"),
            List.of(),
            inventory(repository("cargo-hosted", "cargo", "hosted"))));

    assertEquals(
        "Repositories are invalid for hosted data migration: [cargo-hosted (unsupported format cargo)]",
        thrown.getMessage());
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
        List.class);
    method.setAccessible(true);
    try {
      return (List<?>) method.invoke(service, inventory, repositories, backupProxyRepositories);
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

  private static NexusInventory inventory(RepositoryDocument... repositories) {
    return new NexusInventory(
        List.of(Map.of("name", "default")),
        List.of(repositories),
        NexusSecurityExport.empty(),
        List.of());
  }

  private static RepositoryDocument repository(String name, String format, String type) {
    return new RepositoryDocument(
        Map.of("name", name, "format", format, "type", type),
        Map.of("name", name, "format", format, "type", type));
  }
}
