package com.github.klboke.nexusplus.server;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.util.TimeZone;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class Jackson2Configuration {

  @Bean
  @ConditionalOnMissingBean(ObjectMapper.class)
  ObjectMapper jackson2ObjectMapper(@Value("${spring.jackson.time-zone:Asia/Shanghai}") String timeZone) {
    return new ObjectMapper()
        .registerModule(new JavaTimeModule())
        .setTimeZone(TimeZone.getTimeZone(timeZone))
        .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
        .setSerializationInclusion(JsonInclude.Include.NON_NULL);
  }
}
