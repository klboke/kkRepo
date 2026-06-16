package com.github.klboke.nexusplus.server;

import org.apache.catalina.connector.Connector;
import org.apache.tomcat.util.buf.EncodedSolidusHandling;
import org.springframework.boot.tomcat.servlet.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class TomcatRepositoryPathConfiguration {
  @Bean
  WebServerFactoryCustomizer<TomcatServletWebServerFactory> repositoryPathTomcatCustomizer() {
    return factory -> factory.addConnectorCustomizers(TomcatRepositoryPathConfiguration::allowEncodedSlash);
  }

  private static void allowEncodedSlash(Connector connector) {
    connector.setEncodedSolidusHandling(EncodedSolidusHandling.PASS_THROUGH.getValue());
  }
}
