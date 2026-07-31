package com.github.klboke.kkrepo.server.docker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletResponse;
import java.util.Map;
import org.apache.catalina.connector.Connector;
import org.apache.coyote.AbstractProtocol;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.boot.tomcat.servlet.TomcatServletWebServerFactory;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.session.web.http.SessionRepositoryFilter;

class DockerConnectorConfigurationTest {
  @Test
  void configuresProtocolWithoutReflectiveStringProperties() {
    Connector connector = new Connector(TomcatServletWebServerFactory.DEFAULT_PROTOCOL);
    DockerConnectorManager.ConnectorTuning tuning =
        new DockerConnectorManager.ConnectorTuning(211, 37, 12_345, 2, 3);

    DockerConnectorConfiguration.configureConnector(connector, tuning);

    AbstractProtocol<?> protocol = (AbstractProtocol<?>) connector.getProtocolHandler();
    assertEquals(12_345, protocol.getConnectionTimeout());
    assertEquals(211, protocol.getMaxConnections());
    assertEquals(37, protocol.getAcceptCount());
  }

  @Test
  void connectorRepositoryFilterRunsBeforeDockerAuthFilter() {
    DockerConnectorConfiguration configuration = new DockerConnectorConfiguration();
    @SuppressWarnings("unchecked")
    ObjectProvider<DockerConnectorManager> provider = mock(ObjectProvider.class);

    FilterRegistrationBean<Filter> registration = configuration.dockerConnectorRepositoryFilter(provider);

    assertEquals(
        DockerConnectorConfiguration.CONNECTOR_REPOSITORY_FILTER_ORDER,
        registration.getOrder());
    assertTrue(registration.getOrder() > SessionRepositoryFilter.DEFAULT_ORDER);
    assertTrue(registration.getOrder() < DockerAuthFilter.FILTER_ORDER);
    assertTrue(registration.getUrlPatterns().contains("/v2/*"));
    assertTrue(registration.getUrlPatterns().contains("/service/rest/v1/docker/token"));
  }

  @Test
  void connectorRepositoryFilterResolvesRepositoryByLocalPort() throws Exception {
    DockerConnectorConfiguration configuration = new DockerConnectorConfiguration();
    DockerConnectorManager manager = mock(DockerConnectorManager.class);
    when(manager.repositoryForPort(5001)).thenReturn("docker-hosted");
    @SuppressWarnings("unchecked")
    ObjectProvider<DockerConnectorManager> provider = mock(ObjectProvider.class);
    when(provider.getIfAvailable()).thenReturn(manager);

    Filter filter = configuration.dockerConnectorRepositoryFilter(provider).getFilter();
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/v2/library/alpine/manifests/latest");
    request.setLocalPort(5001);
    MockHttpServletResponse response = new MockHttpServletResponse();

    filter.doFilter(request, response, (servletRequest, servletResponse) ->
        assertEquals(
            "docker-hosted",
            servletRequest.getAttribute(DockerConnectorConfiguration.CONNECTOR_REPOSITORY_ATTRIBUTE)));
  }
}
