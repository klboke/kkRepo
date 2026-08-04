package com.github.klboke.kkrepo.server.cleanup;

import com.github.klboke.kkrepo.core.RepositoryType;

/** Defines repository types that own content eligible for cleanup policies. */
final class CleanupTargetRepositories {
  private CleanupTargetRepositories() {
  }

  static void requireSupported(RepositoryType type) {
    if (type != RepositoryType.HOSTED && type != RepositoryType.PROXY) {
      throw new CleanupValidationException(
          "group repositories cannot be cleanup targets; select hosted or proxy repositories instead");
    }
  }
}
