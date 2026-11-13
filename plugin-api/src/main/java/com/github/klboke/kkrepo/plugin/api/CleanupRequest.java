package com.github.klboke.kkrepo.plugin.api;

/** Input passed to a cleanup rule. */
public record CleanupRequest(
    String repository,
    String pathPrefix,
    int olderThanDays,
    int limit) {

  public CleanupRequest {
    if (repository == null || repository.isBlank()) {
      throw new IllegalArgumentException("Cleanup repository is required");
    }
    repository = repository.trim();
    pathPrefix = pathPrefix == null ? "" : pathPrefix.trim();
    if (olderThanDays < 0) {
      throw new IllegalArgumentException("olderThanDays must be non-negative");
    }
    if (limit <= 0) {
      throw new IllegalArgumentException("limit must be positive");
    }
  }
}
