package com.github.klboke.kkrepo.server.securityscan;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Runs the bounded rolling-upgrade and projection-drift reconciliation on every deployment. */
@Component
public class SecurityScanArtifactReconciliationWorker {
  private static final Logger log =
      LoggerFactory.getLogger(SecurityScanArtifactReconciliationWorker.class);

  private final SecurityScanArtifactReconciliationService reconciliation;

  public SecurityScanArtifactReconciliationWorker(
      SecurityScanArtifactReconciliationService reconciliation) {
    this.reconciliation = reconciliation;
  }

  @Scheduled(
      fixedDelayString = "${kkrepo.security-scanning.artifact-reconcile-delay-ms:1000}",
      initialDelayString = "${kkrepo.security-scanning.initial-delay-ms:5000}")
  public void runOnce() {
    try {
      reconciliation.processBatch();
    } catch (RuntimeException error) {
      log.warn(
          "Security scan artifact reconciliation failed; durable cursor remains retryable",
          error);
    }
  }
}
