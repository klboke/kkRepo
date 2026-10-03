package com.github.klboke.kkrepo.protocol.gitlfs;

/** A client-visible LFS JSON error, without servlet or persistence dependencies. */
public final class GitLfsException extends RuntimeException {
  private final int status;
  public GitLfsException(int status, String message) {
    super(message);
    this.status = status;
  }
  public int status() { return status; }
}
