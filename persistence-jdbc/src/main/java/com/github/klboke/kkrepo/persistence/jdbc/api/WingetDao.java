package com.github.klboke.kkrepo.persistence.jdbc.api;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Durable WinGet manifest metadata. Manifests are small JSON documents, not blob payloads. */
public interface WingetDao {
  Optional<ManifestRecord> find(
      long repositoryId, String packageIdentifier, String packageVersion, String channel);

  List<ManifestRecord> listByPackage(long repositoryId, String packageIdentifier);

  /** Loads search rows without the potentially large manifest JSON. */
  List<ManifestRecord> listSummaries(long repositoryId, List<String> packageIdentifiers);

  List<String> searchPackageIdentifiers(
      long repositoryId, List<String> terms, int limit, int offset);

  void insert(ManifestRecord record);

  int deleteVersion(
      long repositoryId, String packageIdentifier, String packageVersion, String channel);

  int deletePackage(long repositoryId, String packageIdentifier);

  int deleteByRepository(long repositoryId);

  long countVersions(long repositoryId);

  record ManifestRecord(
      long id,
      long repositoryId,
      String packageIdentifier,
      String packageVersion,
      String channel,
      String defaultLocale,
      String packageName,
      String publisher,
      String moniker,
      String searchText,
      String summaryJson,
      String manifestJson,
      String manifestSha256,
      Instant updatedAt) {}
}
