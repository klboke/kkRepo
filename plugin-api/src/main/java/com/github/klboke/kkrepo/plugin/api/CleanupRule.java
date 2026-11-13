package com.github.klboke.kkrepo.plugin.api;

import java.util.List;

/**
 * Read-only cleanup policy. Implementations propose candidates; the runtime owns locking, policy
 * checks, audit and deletion.
 */
public interface CleanupRule {

  String id();

  List<CleanupCandidate> candidates(CleanupRequest request);
}
