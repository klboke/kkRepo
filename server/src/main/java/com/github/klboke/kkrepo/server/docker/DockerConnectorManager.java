package com.github.klboke.kkrepo.server.docker;

import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.persistence.jdbc.api.RepositoryDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.RepositoryRecord;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class DockerConnectorManager {
  private final RepositoryDao repositoryDao;
  private final boolean connectorEnabled;
  private final int maxConnections;
  private final int acceptCount;
  private final int connectionTimeoutMillis;
  private final int maxConcurrentUploads;
  private final int maxConcurrentDownloads;
  private final AtomicLong refreshSequence = new AtomicLong();
  private volatile Snapshot snapshot;

  public DockerConnectorManager(
      RepositoryDao repositoryDao,
      @Value("${kkrepo.docker.connector.enabled:true}") boolean connectorEnabled,
      @Value("${kkrepo.docker.connector.max-connections:2000}") int maxConnections,
      @Value("${kkrepo.docker.connector.accept-count:100}") int acceptCount,
      @Value("${kkrepo.docker.connector.connection-timeout:60000}") int connectionTimeoutMillis,
      @Value("${kkrepo.docker.transfer.max-concurrent-uploads:0}") int maxConcurrentUploads,
      @Value("${kkrepo.docker.transfer.max-concurrent-downloads:0}") int maxConcurrentDownloads) {
    this.repositoryDao = repositoryDao;
    this.connectorEnabled = connectorEnabled;
    this.maxConnections = Math.max(1, maxConnections);
    this.acceptCount = Math.max(1, acceptCount);
    this.connectionTimeoutMillis = Math.max(1000, connectionTimeoutMillis);
    this.maxConcurrentUploads = Math.max(0, maxConcurrentUploads);
    this.maxConcurrentDownloads = Math.max(0, maxConcurrentDownloads);
  }

  public Snapshot snapshot() {
    Snapshot local = snapshot;
    if (local != null) {
      return local;
    }
    synchronized (this) {
      local = snapshot;
      if (local == null) {
        local = loadSnapshot();
        snapshot = local;
      }
      return local;
    }
  }

  public Snapshot refresh() {
    synchronized (this) {
      Snapshot refreshed = loadSnapshot();
      snapshot = refreshed;
      return refreshed;
    }
  }

  public String repositoryForPort(int port) {
    return snapshot().repositoriesByPort().get(port);
  }

  public Map<Integer, String> repositoriesByPort() {
    return snapshot().repositoriesByPort();
  }

  public ConnectorTuning tuning() {
    return new ConnectorTuning(
        maxConnections,
        acceptCount,
        connectionTimeoutMillis,
        maxConcurrentUploads,
        maxConcurrentDownloads);
  }

  private Snapshot loadSnapshot() {
    Map<Integer, String> ports = new LinkedHashMap<>();
    List<ConnectorStatus> connectors = new ArrayList<>();
    for (RepositoryRecord record : repositoryDao.list()) {
      DockerSettings settings = dockerSettings(record);
      if (settings == null || settings.connectorPort() == null) {
        continue;
      }
      boolean repositoryEnabled = settings.connectorEnabled();
      boolean configured = connectorEnabled && repositoryEnabled;
      boolean conflict = configured && ports.containsKey(settings.connectorPort());
      if (configured && !conflict) {
        ports.put(settings.connectorPort(), record.name());
      }
      connectors.add(new ConnectorStatus(
          record.id(),
          record.name(),
          record.type() == null ? null : record.type().name(),
          settings.connectorEnabled(),
          settings.connectorPort(),
          settings.connectorPublicUrl(),
          configured && !conflict,
          connectorState(repositoryEnabled, conflict)));
    }
    connectors.sort(Comparator
        .comparing((ConnectorStatus item) -> item.port() == null ? Integer.MAX_VALUE : item.port())
        .thenComparing(ConnectorStatus::repositoryName));
    return new Snapshot(
        connectorEnabled,
        Map.copyOf(ports),
        List.copyOf(connectors),
        tuning(),
        Instant.now(),
        refreshSequence.incrementAndGet());
  }

  static DockerSettings dockerSettings(RepositoryRecord record) {
    if (record == null || record.format() != RepositoryFormat.DOCKER || record.attributes() == null) {
      return null;
    }
    Object raw = record.attributes().get("docker");
    if (!(raw instanceof Map<?, ?> docker)) {
      return null;
    }
    Integer port = parsePort(docker.get("connectorPort"));
    Boolean enabled = parseBoolean(docker.get("connectorEnabled"));
    if (enabled == null) {
      enabled = port != null;
    }
    String publicUrl = string(docker.get("connectorPublicUrl"));
    return new DockerSettings(Boolean.TRUE.equals(enabled), port, publicUrl);
  }

  private static Integer parsePort(Object value) {
    if (value == null) {
      return null;
    }
    if (value instanceof Number number) {
      return number.intValue();
    }
    try {
      return Integer.parseInt(value.toString());
    } catch (RuntimeException ignored) {
      return null;
    }
  }

  private static Boolean parseBoolean(Object value) {
    if (value == null) {
      return null;
    }
    if (value instanceof Boolean bool) {
      return bool;
    }
    return Objects.equals("true", value.toString().trim().toLowerCase(java.util.Locale.ROOT));
  }

  private static String string(Object value) {
    if (value == null) {
      return null;
    }
    String text = value.toString().trim();
    return text.isBlank() ? null : text;
  }

  private String connectorState(boolean repositoryEnabled, boolean conflict) {
    if (!connectorEnabled) {
      return "globally-disabled";
    }
    if (!repositoryEnabled) {
      return "repository-disabled";
    }
    if (conflict) {
      return "duplicate-port";
    }
    return "active";
  }

  record DockerSettings(boolean connectorEnabled, Integer connectorPort, String connectorPublicUrl) {
  }

  public record Snapshot(
      boolean enabled,
      Map<Integer, String> repositoriesByPort,
      List<ConnectorStatus> connectors,
      ConnectorTuning tuning,
      Instant refreshedAt,
      long sequence) {
  }

  public record ConnectorStatus(
      Long repositoryId,
      String repositoryName,
      String repositoryType,
      boolean enabled,
      Integer port,
      String publicUrl,
      boolean active,
      String state) {
  }

  public record ConnectorTuning(
      int maxConnections,
      int acceptCount,
      int connectionTimeoutMillis,
      int maxConcurrentUploads,
      int maxConcurrentDownloads) {
  }
}
