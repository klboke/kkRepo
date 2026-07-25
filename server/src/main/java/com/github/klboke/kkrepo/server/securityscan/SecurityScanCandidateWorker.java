package com.github.klboke.kkrepo.server.securityscan;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Small scheduler shell; all ownership and state changes are delegated to the transactional service. */
@Component
@ConditionalOnProperty(
    prefix = "kkrepo.security-scanning", name = "enabled", havingValue = "true")
public class SecurityScanCandidateWorker {
  private static final Logger log = LoggerFactory.getLogger(SecurityScanCandidateWorker.class);

  private final SecurityScanArtifactChangeService artifactChanges;
  private final SecurityScanCandidateService service;

  public SecurityScanCandidateWorker(
      SecurityScanArtifactChangeService artifactChanges,
      SecurityScanCandidateService service) {
    this.artifactChanges = artifactChanges;
    this.service = service;
  }

  @Scheduled(
      fixedDelayString = "${kkrepo.security-scanning.candidate-delay-ms:1000}",
      initialDelayString = "${kkrepo.security-scanning.initial-delay-ms:5000}")
  public void runOnce() {
    try {
      artifactChanges.processBatch();
    } catch (RuntimeException e) {
      log.warn("Security scan artifact-change batch failed; cursor remains durable for retry", e);
    }
    try {
      service.processBatch();
    } catch (RuntimeException e) {
      log.warn("Security scan candidate batch failed; markers remain durable for retry", e);
    }
  }
}
