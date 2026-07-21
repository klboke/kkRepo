package com.github.klboke.kkrepo.server.docker;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.Map;
import org.apache.catalina.connector.Connector;
import org.apache.coyote.AbstractProtocol;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.boot.tomcat.servlet.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class DockerConnectorConfiguration {
  static final int CONNECTOR_REPOSITORY_FILTER_ORDER = DockerAuthFilter.FILTER_ORDER - 1;

  public static final String CONNECTOR_REPOSITORY_ATTRIBUTE =
      DockerConnectorConfiguration.class.getName() + ".REPOSITORY";

  @Bean
  WebServerFactoryCustomizer<TomcatServletWebServerFactory> dockerConnectorCustomizer(
      DockerConnectorManager connectorManager) {
    return factory -> {
      DockerConnectorManager.Snapshot snapshot = connectorManager.refresh();
      if (!snapshot.enabled()) {
        return;
      }
      DockerConnectorManager.ConnectorTuning tuning = snapshot.tuning();
      for (Map.Entry<Integer, String> entry : snapshot.repositoriesByPort().entrySet()) {
        Connector connector = new Connector(TomcatServletWebServerFactory.DEFAULT_PROTOCOL);
        connector.setPort(entry.getKey());
        configureConnector(connector, tuning);
        factory.addAdditionalConnectors(connector);
      }
    };
  }

  static void configureConnector(
      Connector connector, DockerConnectorManager.ConnectorTuning tuning) {
    if (!(connector.getProtocolHandler() instanceof AbstractProtocol<?> protocol)) {
      throw new IllegalStateException(
          "Unsupported Docker connector protocol: "
              + connector.getProtocolHandler().getClass().getName());
    }
    protocol.setConnectionTimeout(tuning.connectionTimeoutMillis());
    protocol.setMaxConnections(tuning.maxConnections());
    protocol.setAcceptCount(tuning.acceptCount());
  }

  @Bean
  FilterRegistrationBean<Filter> dockerConnectorRepositoryFilter(
      ObjectProvider<DockerConnectorManager> connectorManagerProvider) {
    FilterRegistrationBean<Filter> registration = new FilterRegistrationBean<>();
    registration.setName("dockerConnectorRepositoryFilter");
    registration.setOrder(CONNECTOR_REPOSITORY_FILTER_ORDER);
    registration.setFilter(new Filter() {
      @Override
      public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
          throws IOException, ServletException {
        if (request instanceof HttpServletRequest http) {
          DockerConnectorManager manager = connectorManagerProvider.getIfAvailable();
          String repository = manager == null ? null : manager.repositoryForPort(http.getLocalPort());
          if (repository != null && isDockerConnectorRequest(http.getRequestURI())) {
            http.setAttribute(CONNECTOR_REPOSITORY_ATTRIBUTE, repository);
          }
        }
        chain.doFilter(request, response);
      }
    });
    registration.addUrlPatterns("/v2");
    registration.addUrlPatterns("/v2/*");
    registration.addUrlPatterns("/service/rest/v1/docker/token");
    registration.setDispatcherTypes(jakarta.servlet.DispatcherType.REQUEST, jakarta.servlet.DispatcherType.ASYNC);
    return registration;
  }

  private static boolean isDockerConnectorRequest(String uri) {
    return uri.equals("/v2")
        || uri.equals("/v2/")
        || uri.startsWith("/v2/")
        || uri.equals("/service/rest/v1/docker/token");
  }
}
