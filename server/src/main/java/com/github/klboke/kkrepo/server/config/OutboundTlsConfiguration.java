package com.github.klboke.kkrepo.server.config;

import com.github.klboke.kkrepo.core.http.OutboundTlsTrust;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class OutboundTlsConfiguration {
  @Bean
  OutboundTlsTrust outboundTlsTrust(@Value("${kkrepo.tls.ca-certificates:}") String caCertificates) {
    return OutboundTlsTrust.fromPemBundle(caCertificates);
  }
}
