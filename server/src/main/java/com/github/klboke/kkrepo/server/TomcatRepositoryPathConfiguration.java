package com.github.klboke.kkrepo.server;

import org.apache.catalina.connector.Connector;
import org.apache.coyote.ContinueResponseTiming;
import org.apache.coyote.http11.AbstractHttp11Protocol;
import org.apache.tomcat.util.buf.EncodedSolidusHandling;
import org.springframework.boot.tomcat.servlet.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class TomcatRepositoryPathConfiguration {
  @Bean
  WebServerFactoryCustomizer<TomcatServletWebServerFactory> repositoryPathTomcatCustomizer() {
    return factory -> factory.addConnectorCustomizers(
        TomcatRepositoryPathConfiguration::configureRepositoryConnector);
  }

  private static void configureRepositoryConnector(Connector connector) {
    connector.setEncodedSolidusHandling(EncodedSolidusHandling.PASS_THROUGH.getValue());
    if (connector.getProtocolHandler() instanceof AbstractHttp11Protocol<?> http11) {
      http11.setContinueResponseTiming(
          ContinueResponseTiming.ON_REQUEST_BODY_READ.toString());
    }
  }
}
