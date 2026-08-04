package com.github.klboke.kkrepo.server.cleanup;

/** Raised when a worker can no longer prove ownership of the shard and repository lease. */
public class CleanupFenceLostException extends RuntimeException {
  public CleanupFenceLostException(long runRepositoryId) {
    super("cleanup execution fence was lost for run repository " + runRepositoryId);
  }
}
