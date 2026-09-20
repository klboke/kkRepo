package com.github.klboke.kkrepo.server.securityscan;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Runs bounded rolling-upgrade and projection-drift reconciliation only when explicitly enabled. */
@Component
public class SecurityScanArtifactReconciliationWorker {
  private static final Logger log =
      LoggerFactory.getLogger(SecurityScanArtifactReconciliationWorker.class);

  private final SecurityScanArtifactReconciliationService reconciliation;
  private final SecurityScanningProperties properties;

  public SecurityScanArtifactReconciliationWorker(
      SecurityScanArtifactReconciliationService reconciliation,
      SecurityScanningProperties properties) {
    this.reconciliation = reconciliation;
    this.properties = properties;
  }

  @Scheduled(
      fixedDelayString = "${kkrepo.security-scanning.artifact-reconcile-delay-ms:1000}",
      initialDelayString = "${kkrepo.security-scanning.initial-delay-ms:5000}")
  public void runOnce() {
    if (!properties.isEnabled()) {
      return;
    }
    try {
      reconciliation.processBatch();
    } catch (RuntimeException error) {
      log.warn(
          "Security scan artifact reconciliation failed; durable cursor remains retryable",
          error);
    }
  }
}
