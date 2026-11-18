package com.github.klboke.kkrepo.persistence.jdbc.api;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public interface OhpmImportDao {
  enum Source { DIRECTORY, REGISTRY }
  enum JobState { PLANNING, READY, APPLYING, COMPLETED, CANCELLED }
  enum ItemState { PLANNED, IMPORTED, SKIPPED, MANUAL, FAILED }

  record Job(String id, long repositoryId, Source source, Long sourceRepositoryId, String requester,
      JobState state, Instant createdAt, Instant updatedAt) {}

  record Item(String jobId, String key, String packageName, String version, Map<String, Object> plan,
      ItemState state, String detail, Instant updatedAt) {
    public boolean succeeded() {
      return state == ItemState.IMPORTED || state == ItemState.SKIPPED;
    }
  }

  Job create(long repositoryId, Source source, Long sourceRepositoryId, String requester);

  Optional<Job> findJob(String id);

  List<Job> listJobs(long repositoryId, int limit);

  Optional<Job> lockJob(String id);

  Item addPlan(String jobId, String key, String packageName, String version,
      Map<String, Object> plan, ItemState state, String detail);

  Optional<Item> findItem(String jobId, String key);

  Optional<Item> lockItem(String jobId, String key);

  List<Item> items(String jobId);

  boolean transition(String jobId, JobState expected, JobState next);

  boolean result(String jobId, String key, ItemState state, String detail);
}
