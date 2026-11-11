package com.github.klboke.kkrepo.plugin.api;

/** One candidate proposed by a cleanup rule. The rule never deletes anything itself. */
public record CleanupCandidate(
    String repository,
    String path,
    String reason,
    long sizeBytes) {

  public CleanupCandidate {
    if (repository == null || repository.isBlank()) {
      throw new IllegalArgumentException("Cleanup candidate repository is required");
    }
    if (path == null || path.isBlank()) {
      throw new IllegalArgumentException("Cleanup candidate path is required");
    }
    repository = repository.trim();
    path = path.trim();
    reason = reason == null ? "" : reason.trim();
    if (sizeBytes < 0) {
      throw new IllegalArgumentException("Cleanup candidate size must be non-negative");
    }
  }
}
