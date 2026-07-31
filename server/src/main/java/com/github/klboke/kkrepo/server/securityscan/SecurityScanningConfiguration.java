package com.github.klboke.kkrepo.server.securityscan;

import com.github.klboke.kkrepo.persistence.jdbc.api.ArtifactChangeEventMode;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Connects the optional scanner capability to feature-neutral persistence extension points. */
@Configuration(proxyBeanMethods = false)
public class SecurityScanningConfiguration {
  @Bean
  ArtifactChangeEventMode artifactChangeEventMode(SecurityScanningProperties properties) {
    return new ArtifactChangeEventMode(properties.isEnabled());
  }
}
