package com.github.klboke.kkrepo.persistence.jdbc.api;

/** A repository changed before a durable LFS upload action could be created. */
public final class GitLfsRepositoryStateException extends IllegalStateException {
  public enum Reason { MISSING, OFFLINE, READ_ONLY, CONFIGURATION_CHANGED }

  private final Reason reason;

  public GitLfsRepositoryStateException(Reason reason) {
    super("Git LFS repository is not writable: " + reason);
    this.reason = reason;
  }

  public Reason reason() { return reason; }
}
