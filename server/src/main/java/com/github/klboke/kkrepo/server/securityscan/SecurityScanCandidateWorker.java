package com.github.klboke.kkrepo.server.securityscan;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Small scheduler shell; all ownership and state changes are delegated to the transactional service. */
@Component
public class SecurityScanCandidateWorker {
  private static final Logger log = LoggerFactory.getLogger(SecurityScanCandidateWorker.class);

  private final SecurityScanCandidateService service;
  private final SecurityScanningProperties properties;

  public SecurityScanCandidateWorker(
      SecurityScanCandidateService service, SecurityScanningProperties properties) {
    this.service = service;
    this.properties = properties;
  }

  @Scheduled(
      fixedDelayString = "${kkrepo.security-scanning.candidate-delay-ms:1000}",
      initialDelayString = "${kkrepo.security-scanning.initial-delay-ms:5000}")
  public void runOnce() {
    if (!properties.isEnabled()) {
      return;
    }
    try {
      service.processBatch();
    } catch (RuntimeException e) {
      log.warn("Security scan candidate batch failed; markers remain durable for retry", e);
    }
  }
}
