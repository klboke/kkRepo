package com.github.klboke.kkrepo.server.docker;

import java.util.HashMap;
import java.util.Map;
import org.apache.catalina.LifecycleException;
import org.apache.catalina.connector.Connector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.tomcat.TomcatWebServer;
import org.springframework.boot.web.server.WebServer;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ApplicationListener;
import org.springframework.stereotype.Service;

@Service
public class DockerConnectorRuntime implements ApplicationListener<ApplicationReadyEvent> {
  private static final Logger log = LoggerFactory.getLogger(DockerConnectorRuntime.class);

  private final DockerConnectorManager connectorManager;
  private final Map<Integer, Connector> managedConnectors = new HashMap<>();
  private volatile TomcatWebServer webServer;
  private volatile String lastError;

  public DockerConnectorRuntime(DockerConnectorManager connectorManager) {
    this.connectorManager = connectorManager;
  }

  @Override
  public void onApplicationEvent(ApplicationReadyEvent event) {
    if (event.getApplicationContext() instanceof WebServerApplicationContext context) {
      WebServer server = context.getWebServer();
      if (server instanceof TomcatWebServer tomcat) {
        this.webServer = tomcat;
        sync();
      }
    }
  }

  public synchronized DockerConnectorManager.Snapshot sync() {
    DockerConnectorManager.Snapshot snapshot = connectorManager.refresh();
    TomcatWebServer localServer = webServer;
    if (localServer == null || !snapshot.enabled()) {
      return snapshot;
    }
    try {
      org.apache.catalina.Service service = localServer.getTomcat().getService();
      Map<Integer, String> desired = snapshot.repositoriesByPort();
      for (Integer port : java.util.List.copyOf(managedConnectors.keySet())) {
        if (!desired.containsKey(port)) {
          removeConnector(service, port);
        }
      }
      DockerConnectorManager.ConnectorTuning tuning = snapshot.tuning();
      for (Integer port : desired.keySet()) {
        Connector existing = connector(service, port);
        if (existing == null) {
          addConnector(service, port, tuning);
        } else {
          managedConnectors.putIfAbsent(port, existing);
        }
      }
      lastError = null;
    } catch (RuntimeException e) {
      lastError = e.getMessage();
      log.warn("Failed syncing Docker connector runtime", e);
    }
    return snapshot;
  }

  public String lastError() {
    return lastError;
  }

  private void addConnector(
      org.apache.catalina.Service service,
      int port,
      DockerConnectorManager.ConnectorTuning tuning) {
    Connector connector = new Connector(org.springframework.boot.tomcat.servlet.TomcatServletWebServerFactory.DEFAULT_PROTOCOL);
    connector.setPort(port);
    DockerConnectorConfiguration.configureConnector(connector, tuning);
    service.addConnector(connector);
    try {
      connector.start();
      managedConnectors.put(port, connector);
    } catch (LifecycleException e) {
      service.removeConnector(connector);
      throw new IllegalStateException("Failed starting Docker connector on port " + port, e);
    }
  }

  private void removeConnector(org.apache.catalina.Service service, int port) {
    Connector connector = managedConnectors.remove(port);
    if (connector == null) {
      connector = connector(service, port);
    }
    if (connector == null) {
      return;
    }
    try {
      connector.stop();
    } catch (LifecycleException e) {
      log.warn("Failed stopping Docker connector on port {}", port, e);
    }
    service.removeConnector(connector);
    try {
      connector.destroy();
    } catch (LifecycleException e) {
      log.warn("Failed destroying Docker connector on port {}", port, e);
    }
  }

  private Connector connector(org.apache.catalina.Service service, int port) {
    for (Connector connector : service.findConnectors()) {
      if (connector.getPort() == port) {
        return connector;
      }
    }
    return null;
  }
}
