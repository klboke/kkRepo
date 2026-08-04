package com.github.klboke.kkrepo.server.cleanup;

import java.time.Instant;

public class CleanupProtectionConflictException extends RuntimeException {
  private final long protectionId;
  private final Instant currentUpdatedAt;

  public CleanupProtectionConflictException(long protectionId, Instant currentUpdatedAt) {
    super("cleanup protection changed since it was loaded");
    this.protectionId = protectionId;
    this.currentUpdatedAt = currentUpdatedAt;
  }

  public long protectionId() {
    return protectionId;
  }

  public Instant currentUpdatedAt() {
    return currentUpdatedAt;
  }
}
