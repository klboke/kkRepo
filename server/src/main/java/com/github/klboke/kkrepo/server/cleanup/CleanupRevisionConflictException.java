package com.github.klboke.kkrepo.server.cleanup;

public class CleanupRevisionConflictException extends RuntimeException {
  private final long policyId;
  private final long currentRevision;

  public CleanupRevisionConflictException(long policyId, long currentRevision) {
    super("cleanup policy revision changed");
    this.policyId = policyId;
    this.currentRevision = currentRevision;
  }

  public long policyId() {
    return policyId;
  }

  public long currentRevision() {
    return currentRevision;
  }
}
