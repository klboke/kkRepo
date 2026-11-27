package com.github.klboke.kkrepo.server.docker;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.github.klboke.kkrepo.core.RepositoryType;
import com.github.klboke.kkrepo.persistence.jdbc.api.RepositoryDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.RepositoryDao.DockerConnectorRepository;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DockerConnectorManagerTest {
  @Test
  void repositoryDisabledConnectorIsNotReportedAsActive() {
    DockerConnectorManager manager = manager(
        true,
        List.of(repository(1L, "docker-hosted", false, 5000)));

    DockerConnectorManager.ConnectorStatus status = manager.snapshot().connectors().get(0);

    assertEquals(false, status.active());
    assertEquals("repository-disabled", status.state());
    assertEquals(Map.of(), manager.snapshot().repositoriesByPort());
  }

  @Test
  void globalDisabledConnectorIsReportedSeparately() {
    DockerConnectorManager manager = manager(
        false,
        List.of(repository(1L, "docker-hosted", true, 5000)));

    DockerConnectorManager.ConnectorStatus status = manager.snapshot().connectors().get(0);

    assertEquals(false, status.active());
    assertEquals("globally-disabled", status.state());
    assertEquals(Map.of(), manager.snapshot().repositoriesByPort());
  }

  @Test
  void duplicatePortOnlyAppliesToEnabledConnectors() {
    DockerConnectorManager manager = manager(
        true,
        List.of(
            repository(1L, "docker-disabled", false, 5000),
            repository(2L, "docker-one", true, 5000),
            repository(3L, "docker-two", true, 5000)));

    List<DockerConnectorManager.ConnectorStatus> connectors = manager.snapshot().connectors();

    assertEquals("repository-disabled", connectors.get(0).state());
    assertEquals("pending", connectors.get(1).state());
    assertEquals("duplicate-port", connectors.get(2).state());
    assertEquals(Map.of(5000, "docker-one"), manager.snapshot().repositoriesByPort());
  }

  @Test
  void httpsIsAnIndependentDesiredListenerAndOnlyAppliedBindingsRouteTraffic() {
    var record = new DockerConnectorRepository(1L, "docker-hosted", RepositoryType.HOSTED,
        Map.of("connectorPort", 5000, "httpsConnectorPort", 5443,
            "tlsBundle", " registry ", "connectorPublicUrl", "https://registry.example"));
    var manager = manager(true, List.of(record));
    assertEquals(Map.of(5000, "docker-hosted", 5443, "docker-hosted"), manager.snapshot().repositoriesByPort());
    assertEquals("https", manager.snapshot().connectors().get(1).scheme());
    assertEquals("registry", manager.snapshot().connectors().get(1).tlsBundle());
    assertNull(manager.repositoryForPort(5443));
    manager.activeRepositories(Map.of(5443, "docker-hosted"));
    assertEquals("docker-hosted", manager.repositoryForPort(5443));
    assertFalse(manager.snapshot().connectors().get(1).active());
  }

  private static DockerConnectorManager manager(boolean globallyEnabled, List<DockerConnectorRepository> repositories) {
    RepositoryDao repositoryDao = mock(RepositoryDao.class);
    when(repositoryDao.listDockerConnectorRepositories()).thenReturn(repositories);
    return new DockerConnectorManager(repositoryDao, globallyEnabled, 2000, 100, 60000, 0, 0);
  }

  private static DockerConnectorRepository repository(long id, String name, boolean connectorEnabled, int connectorPort) {
    return new DockerConnectorRepository(id, name, RepositoryType.HOSTED,
        Map.of("connectorEnabled", connectorEnabled, "connectorPort", connectorPort));
  }
}
