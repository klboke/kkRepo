package com.github.klboke.kkrepo.server.docker;

import com.github.klboke.kkrepo.server.repositories.RepositoryValidationException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.catalina.LifecycleException;
import org.apache.catalina.connector.Connector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.tomcat.TomcatWebServer;
import org.springframework.boot.tomcat.servlet.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ApplicationListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Reconciles each node's listeners from committed database configuration. The maps own live Tomcat
 * resources, not cached business data. Every replica polls independently, including after missed
 * local notifications and restarts. Certificates are deployment-owned; rotate via rolling restart.
 */
@Service
public class DockerConnectorRuntime implements ApplicationListener<ApplicationReadyEvent> {
  private static final Logger log = LoggerFactory.getLogger(DockerConnectorRuntime.class);
  private final DockerConnectorManager connectorManager;
  private final DockerTlsBundles tlsBundles;
  private final Map<Integer, RunningConnector> managedConnectors = new HashMap<>();
  private final String nodeId = UUID.randomUUID().toString();
  private volatile TomcatWebServer webServer;
  private volatile String lastError;
  private volatile DockerConnectorManager.Snapshot runtimeSnapshot;

  @Autowired
  public DockerConnectorRuntime(DockerConnectorManager connectorManager, DockerTlsBundles tlsBundles) {
    this.connectorManager = connectorManager;
    this.tlsBundles = tlsBundles;
  }

  public DockerConnectorRuntime(DockerConnectorManager connectorManager) {
    this(connectorManager, null);
  }

  @Override
  public void onApplicationEvent(ApplicationReadyEvent event) {
    if (event.getApplicationContext() instanceof WebServerApplicationContext context
        && context.getWebServer() instanceof TomcatWebServer tomcat) {
      webServer = tomcat;
      sync();
    }
  }

  @Scheduled(fixedDelayString = "${kkrepo.docker.connector.refresh-interval-ms:5000}",
      initialDelayString = "${kkrepo.docker.connector.refresh-interval-ms:5000}")
  public void reconcile() {
    if (webServer != null) sync();
  }

  public void validateTlsBundle(String name) {
    if (tlsBundles == null) throw new RepositoryValidationException("Inbound TLS bundles are unavailable");
    tlsBundles.resolve(name);
  }

  public String nodeId() { return nodeId; }
  public String lastError() { return lastError; }

  public DockerConnectorManager.Snapshot snapshot() {
    DockerConnectorManager.Snapshot local = runtimeSnapshot;
    return local == null ? connectorManager.snapshot() : local;
  }

  public synchronized DockerConnectorManager.Snapshot sync() {
    DockerConnectorManager.Snapshot desired;
    try {
      desired = connectorManager.refresh();
    } catch (RuntimeException e) {
      lastError = "Cannot read committed Docker connector configuration";
      log.warn(lastError, e);
      if (runtimeSnapshot == null) {
        runtimeSnapshot = connectorManager.emptySnapshot();
      }
      return runtimeSnapshot;
    }
    TomcatWebServer server = webServer;
    if (server == null) {
      lastError = null;
      runtimeSnapshot = desired;
      return desired;
    }
    org.apache.catalina.Service service = server.getTomcat().getService();
    Map<Integer, DockerConnectorManager.ConnectorStatus> wanted = new LinkedHashMap<>();
    for (var status : desired.connectors()) {
      if ("pending".equals(status.state())) wanted.put(status.port(), status);
    }
    // Fence removed/reassigned ports before starting replacements. Keep unchanged routes live.
    Map<Integer, String> retained = new LinkedHashMap<>();
    managedConnectors.forEach((port, running) -> {
      var next = wanted.get(port);
      if (next != null && running.spec().equals(spec(next)) && running.connector().getState().isAvailable()) {
        retained.put(port, next.repositoryName());
      }
    });
    connectorManager.activeRepositories(retained);
    for (var entry : List.copyOf(managedConnectors.entrySet())) {
      var next = wanted.get(entry.getKey());
      if (next == null || !entry.getValue().spec().equals(spec(next))
          || !entry.getValue().connector().getState().isAvailable()) removeConnector(service, entry.getKey());
    }
    List<DockerConnectorManager.ConnectorStatus> statuses = new ArrayList<>();
    Map<Integer, String> active = new LinkedHashMap<>();
    lastError = null;
    for (var status : desired.connectors()) {
      if (!"pending".equals(status.state())) {
        statuses.add(status);
        continue;
      }
      try {
        RunningConnector running = managedConnectors.get(status.port());
        if (running == null) {
          running = addConnector(service, status, desired.tuning());
          managedConnectors.put(status.port(), running);
        }
        active.put(status.port(), status.repositoryName());
        statuses.add(withRuntime(status, true, "active", running.bundle(), running.fingerprint()));
      } catch (RuntimeException e) {
        // Do not expose provider/key-store exception messages in the admin response.
        String error = e instanceof RepositoryValidationException ? e.getMessage()
            : "Failed starting " + status.scheme().toUpperCase(java.util.Locale.ROOT) + " connector on port " + status.port();
        lastError = error;
        statuses.add(withRuntime(status, false, "error", status.tlsBundle(), null));
        log.warn("Docker connector {}:{} unavailable: {}", status.repositoryName(), status.port(), error);
      }
    }
    connectorManager.activeRepositories(active);
    runtimeSnapshot = new DockerConnectorManager.Snapshot(desired.enabled(), Map.copyOf(active),
        List.copyOf(statuses), desired.tuning(), Instant.now(), desired.sequence());
    return runtimeSnapshot;
  }

  private RunningConnector addConnector(org.apache.catalina.Service service,
      DockerConnectorManager.ConnectorStatus status, DockerConnectorManager.ConnectorTuning tuning) {
    for (Connector existing : service.findConnectors()) {
      if (existing.getPort() == status.port() || existing.getLocalPort() == status.port()) {
        throw new IllegalStateException("Port belongs to another listener");
      }
    }
    Connector connector = new Connector(TomcatServletWebServerFactory.DEFAULT_PROTOCOL);
    connector.setPort(status.port());
    DockerConnectorConfiguration.configureConnector(connector, tuning);
    DockerTlsBundles.Resolved identity = null;
    if ("https".equals(status.scheme())) {
      if (tlsBundles == null) throw new RepositoryValidationException("Inbound TLS bundles are unavailable");
      identity = tlsBundles.resolve(status.tlsBundle());
      tlsBundles.configure(connector, identity);
    }
    try {
      service.addConnector(connector);
      if (!connector.getState().isAvailable()) connector.start();
      if (!connector.getState().isAvailable()) throw new LifecycleException("Listener did not start");
      return new RunningConnector(spec(status), connector,
          identity == null ? null : identity.status().name(),
          identity == null ? null : identity.status().fingerprint());
    } catch (LifecycleException | RuntimeException e) {
      destroy(service, connector);
      throw new IllegalStateException("Failed starting Docker connector", e);
    }
  }

  private void removeConnector(org.apache.catalina.Service service, int port) {
    RunningConnector running = managedConnectors.remove(port);
    if (running != null) destroy(service, running.connector());
  }

  private void destroy(org.apache.catalina.Service service, Connector connector) {
    service.removeConnector(connector);
    try {
      connector.stop();
      connector.destroy();
    } catch (LifecycleException e) {
      log.warn("Failed destroying Docker connector on port {}", connector.getPort(), e);
    }
  }

  private static ConnectorSpec spec(DockerConnectorManager.ConnectorStatus status) {
    return new ConnectorSpec(status.repositoryId(), status.repositoryName(), status.scheme(), status.tlsBundle());
  }

  private static DockerConnectorManager.ConnectorStatus withRuntime(
      DockerConnectorManager.ConnectorStatus status, boolean active, String state, String bundle, String fingerprint) {
    return new DockerConnectorManager.ConnectorStatus(status.repositoryId(), status.repositoryName(),
        status.repositoryType(), status.enabled(), status.port(), status.publicUrl(), active, state,
        status.scheme(), bundle, fingerprint);
  }

  private record ConnectorSpec(Long repositoryId, String repository, String scheme, String bundle) { }
  private record RunningConnector(ConnectorSpec spec, Connector connector, String bundle, String fingerprint) { }
}
