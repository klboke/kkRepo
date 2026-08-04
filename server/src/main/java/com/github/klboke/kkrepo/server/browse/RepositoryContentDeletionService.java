package com.github.klboke.kkrepo.server.browse;

import java.util.List;

/** Application-level repository deletion entry point shared by admin and background workflows. */
public interface RepositoryContentDeletionService {
  int deleteForCleanup(
      String repository,
      String subjectKind,
      long subjectId,
      String path,
      String actorId);

  /** Deletes one bounded protocol family while allowing metadata to be repaired once. */
  default List<Integer> deleteBatchForCleanup(
      String repository, List<CleanupDeleteSubject> subjects, String actorId) {
    if (subjects == null || subjects.isEmpty()) return List.of();
    return subjects.stream()
        .map(subject -> deleteForCleanup(
            repository,
            subject.subjectKind(),
            subject.subjectId(),
            subject.path(),
            actorId))
        .toList();
  }

  record CleanupDeleteSubject(String subjectKind, long subjectId, String path) {
  }
}
