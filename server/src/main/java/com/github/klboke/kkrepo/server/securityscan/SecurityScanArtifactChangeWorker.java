package com.github.klboke.kkrepo.server.securityscan;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Maintains the bounded scan-candidate projection after deployment capability is enabled. */
@Component
public class SecurityScanArtifactChangeWorker {
  private static final Logger log =
      LoggerFactory.getLogger(SecurityScanArtifactChangeWorker.class);

  private final SecurityScanArtifactChangeService artifactChanges;
  private final SecurityScanningProperties properties;

  public SecurityScanArtifactChangeWorker(
      SecurityScanArtifactChangeService artifactChanges,
      SecurityScanningProperties properties) {
    this.artifactChanges = artifactChanges;
    this.properties = properties;
  }

  @Scheduled(
      fixedDelayString = "${kkrepo.security-scanning.artifact-change-delay-ms:1000}",
      initialDelayString = "${kkrepo.security-scanning.initial-delay-ms:5000}")
  public void runOnce() {
    if (!properties.isEnabled()) {
      return;
    }
    try {
      artifactChanges.processBatch();
    } catch (CannotAcquireLockException contention) {
      log.debug(
          "Security scan artifact-change batch deferred while foreground content is changing");
    } catch (RuntimeException e) {
      log.warn("Security scan artifact-change batch failed; cursor remains durable for retry", e);
    }
  }
}
