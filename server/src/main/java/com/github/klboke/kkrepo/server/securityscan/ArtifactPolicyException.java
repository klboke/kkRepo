package com.github.klboke.kkrepo.server.securityscan;

import com.github.klboke.kkrepo.security.scan.ScanEnums.PolicyDecision;

public class ArtifactPolicyException extends RuntimeException {
  private final PolicyDecision decision;
  private final int retryAfterSeconds;

  ArtifactPolicyException(PolicyDecision decision, int retryAfterSeconds) {
    super("Artifact is unavailable under the repository security policy");
    this.decision = decision;
    this.retryAfterSeconds = retryAfterSeconds;
  }

  public PolicyDecision decision() {
    return decision;
  }

  public int retryAfterSeconds() {
    return retryAfterSeconds;
  }

  public boolean pending() {
    return decision == PolicyDecision.BLOCK_PENDING
        || decision == PolicyDecision.BLOCK_SCAN_FAILED
        || decision == PolicyDecision.BLOCK_PARTIAL;
  }
}
