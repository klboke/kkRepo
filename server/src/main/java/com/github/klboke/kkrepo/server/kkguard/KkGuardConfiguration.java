package com.github.klboke.kkrepo.server.kkguard;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.klboke.kkrepo.server.kkguard.dependencytrack.DependencyTrackClient;
import com.github.klboke.kkrepo.server.kkguard.dependencytrack.HttpDependencyTrackClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the kkGuard Dependency-Track client from operator configuration.
 *
 * <p>A misconfigured Dependency-Track URL or key must not prevent the JVM from starting: kkGuard is
 * an optional product and a typo in {@code kkrepo.kkg.dependency-track.url} previously aborted the
 * whole application at bean creation. The client degrades to "not configured" instead, the scan
 * API answers an explicit error, and the worker stays idle until the configuration is fixed.
 */
@Configuration(proxyBeanMethods = false)
public class KkGuardConfiguration {
  private static final Logger log = LoggerFactory.getLogger(KkGuardConfiguration.class);

  @Bean
  DependencyTrackClient dependencyTrackClient(
      KkGuardProperties properties, ObjectMapper objectMapper) {
    try {
      return HttpDependencyTrackClient.create(properties.getDependencyTrack(), objectMapper);
    } catch (RuntimeException e) {
      log.error(
          "kkGuard Dependency-Track client is misconfigured and stays disabled: {}", e.getMessage());
      return HttpDependencyTrackClient.create(
          new KkGuardProperties.DependencyTrack(), objectMapper);
    }
  }
}
