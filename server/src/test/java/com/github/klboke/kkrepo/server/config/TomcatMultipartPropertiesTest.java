package com.github.klboke.kkrepo.server.config;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.tomcat.autoconfigure.TomcatServerProperties;
import org.springframework.core.env.PropertiesPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.PropertiesLoaderUtils;

class TomcatMultipartPropertiesTest {

  @Test
  void applicationDefaultsAllowMetadataRichTwineUploads() throws Exception {
    Properties applicationProperties = PropertiesLoaderUtils.loadProperties(
        new ClassPathResource("application.properties"));
    StandardEnvironment environment = new StandardEnvironment();
    environment.getPropertySources().addLast(
        new PropertiesPropertySource("applicationProperties", applicationProperties));

    TomcatServerProperties tomcat = Binder.get(environment)
        .bind("server.tomcat", TomcatServerProperties.class)
        .orElseThrow(() -> new AssertionError("server.tomcat properties did not bind"));

    assertEquals(256, tomcat.getMaxPartCount());
  }
}
