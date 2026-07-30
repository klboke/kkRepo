package com.github.klboke.kkrepo.scanner;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Jackson 2 mapper used only to parse bounded third-party scanner documents. */
@Configuration
public class ScannerJacksonConfiguration {
  @Bean
  @ConditionalOnMissingBean(ObjectMapper.class)
  ObjectMapper scannerDocumentObjectMapper() {
    return new ObjectMapper()
        .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
  }
}
