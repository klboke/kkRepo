package com.github.klboke.kkrepo.persistence.jdbc.api;

import java.time.Instant;
import java.util.List;

public interface RepositoryIndexRebuildDao {
  String HELM_INDEX = "HELM_INDEX";
  String PYPI_ROOT = "PYPI_ROOT";
  String PYPI_PROJECT = "PYPI_PROJECT";
  String YUM_METADATA = "YUM_METADATA";
  String RUBYGEMS_METADATA = "RUBYGEMS_METADATA";
  String TERRAFORM_COMPONENTS = "TERRAFORM_COMPONENTS";
  String SWIFT_COMPONENTS = "SWIFT_COMPONENTS";
  String ROOT_SCOPE = "";

  void enqueue(long repositoryId, String indexKind);

  void enqueue(long repositoryId, String indexKind, String scopeKey);

  void reenqueueFailure(Claim claim, RuntimeException error);

  List<Claim> claim(int maxItems);

  long countBacklog();

  long oldestBacklogAgeSeconds();

  long countFailures();

  boolean hasPending(long repositoryId, String indexKind, String scopeKey);

  record Claim(
      long repositoryId,
      String indexKind,
      String scopeKey,
      Instant requestedAt,
      int attempts,
      String lastError) {}
}
