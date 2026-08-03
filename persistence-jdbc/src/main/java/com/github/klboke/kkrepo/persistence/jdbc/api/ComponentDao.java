package com.github.klboke.kkrepo.persistence.jdbc.api;

import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.ComponentRecord;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public interface ComponentDao {
  long insert(ComponentRecord record);

  Optional<ComponentRecord> findByCoordinateHash(long repositoryId, byte[] coordinateHash);

  Optional<ComponentRecord> findById(long componentId);

  long upsertReturningId(ComponentRecord record);

  Optional<ComponentRecord> findByGav(long repositoryId, String groupId, String artifactId, String version);

  Optional<ComponentRecord> findByNameAndVersion(long repositoryId, String name, String version);

  List<ComponentRecord> listByRepositoryId(long repositoryId);

  List<String> listDistinctNamesByRepositoryId(long repositoryId);

  List<ComponentRecord> listByName(long repositoryId, String name);

  List<ComponentRecord> listByGa(long repositoryId, String groupId, String artifactId);

  List<ComponentSearchRow> search(String keyword, RepositoryFormat format, int limit);

  List<ComponentSearchRow> searchByRepositoryIds(List<Long> repositoryIds, String keyword, int limit);

  List<ComponentSearchRow> searchByRepositoryIds(
      List<Long> repositoryIds,
      RepositoryFormat format,
      String keyword,
      int limit);

  /**
   * Returns a stable component-search keyset page. The database remains the shared source of truth;
   * callers may carry {@code afterComponentId} between replicas without node-local search state.
   */
  default List<ComponentSearchRow> searchPage(
      ComponentSearchCriteria criteria, long afterComponentId, int limit) {
    ComponentSearchCriteria effective = criteria == null
        ? new ComponentSearchCriteria(null, null, null, null, null, null, null)
        : criteria;
    if (effective.sha1() != null) {
      return List.of();
    }
    return search(effective.keyword(), effective.format(), Math.max(limit, 300)).stream()
        .filter(row -> row.id() > Math.max(0, afterComponentId))
        .filter(row -> effective.repositoryName() == null
            || effective.repositoryName().equals(row.repositoryName()))
        .filter(row -> effective.namespace() == null
            || effective.namespace().equals(row.namespace()))
        .filter(row -> effective.name() == null || effective.name().equals(row.name()))
        .filter(row -> effective.version() == null || effective.version().equals(row.version()))
        .sorted(java.util.Comparator.comparingLong(ComponentSearchRow::id))
        .limit(Math.max(1, limit))
        .toList();
  }

  List<ComponentRecord> searchComponentsByRepositoryIds(
      List<Long> repositoryIds,
      RepositoryFormat format,
      String keyword,
      int limit);

  int deleteIfNoAssets(long componentId);

  int deleteByRepositoryIdAndFormat(long repositoryId, RepositoryFormat format);

  int touchLastUpdated(long componentId, java.time.Instant when);

  int updateAttributes(long componentId, Map<String, Object> attributes, java.time.Instant when);

  long countByRepositoryId(long repositoryId);

  record ComponentSearchRow(
      long id,
      long repositoryId,
      String repositoryName,
      RepositoryFormat format,
      String namespace,
      String name,
      String version,
      String kind,
      java.time.Instant lastUpdatedAt,
      String storagePath) {
  }

  record ComponentSearchCriteria(
      String keyword,
      RepositoryFormat format,
      String repositoryName,
      String namespace,
      String name,
      String version,
      String sha1) {
    public ComponentSearchCriteria(
        String keyword,
        RepositoryFormat format,
        String repositoryName,
        String namespace,
        String name,
        String version) {
      this(keyword, format, repositoryName, namespace, name, version, null);
    }
  }
}
