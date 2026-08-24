package com.github.klboke.kkrepo.server;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.util.Map;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.tomcat.autoconfigure.TomcatServerProperties;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.PropertiesPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.PropertiesLoaderUtils;

class TomcatUploadConfigurationTest {

  @Test
  void defaultsMultipartPartLimitAboveTomcatDefault() throws IOException {
    assertEquals(200, bindTomcatProperties(Map.of()).getMaxPartCount());
  }

  @Test
  void exposesMultipartPartLimitThroughKkRepoEnvironmentVariable() throws IOException {
    assertEquals(321, bindTomcatProperties(Map.of("KKREPO_TOMCAT_MAX_PART_COUNT", 321)).getMaxPartCount());
  }

  private static TomcatServerProperties bindTomcatProperties(Map<String, Object> overrides)
      throws IOException {
    StandardEnvironment environment = new StandardEnvironment();
    environment.getPropertySources().addFirst(new MapPropertySource("testOverrides", overrides));
    Properties applicationProperties = PropertiesLoaderUtils.loadProperties(
        new ClassPathResource("application.properties"));
    environment.getPropertySources().addLast(
        new PropertiesPropertySource("kkrepoApplicationProperties", applicationProperties));
    return Binder.get(environment)
        .bind("server.tomcat", Bindable.of(TomcatServerProperties.class))
        .get();
  }
}
