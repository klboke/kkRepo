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

  /** Locks the native component identity for cleanup revalidation. */
  default Optional<ComponentRecord> findByIdForUpdate(long componentId) {
    return findById(componentId);
  }

  long upsertReturningId(ComponentRecord record);

  Optional<ComponentRecord> findByGav(long repositoryId, String groupId, String artifactId, String version);

  Optional<ComponentRecord> findByNameAndVersion(long repositoryId, String name, String version);

  List<ComponentRecord> listByRepositoryId(long repositoryId);

  default List<ComponentRecord> listByRepositoryId(long repositoryId, int maxItems) {
    return listByRepositoryId(repositoryId).stream().limit(Math.max(1, maxItems)).toList();
  }

  /**
   * Returns a bounded, stable page starting strictly after a complete cleanup family.
   *
   * <p>The family tuple deliberately excludes version and component id. Cleanup policies that
   * retain versions must never resume in the middle of a family. Production implementations use
   * case-sensitive database ordering; this default keeps lightweight test adapters compatible.
   */
  default List<ComponentRecord> listCleanupPage(
      long repositoryId, CleanupFamilyCursor afterFamily, int maxItems) {
    return listByRepositoryId(repositoryId).stream()
        .filter(component -> afterFamily == null || compareFamily(component, afterFamily) > 0)
        .sorted(java.util.Comparator
            .comparing((ComponentRecord component) -> cleanupValue(component.namespace()))
            .thenComparing(component -> cleanupValue(component.name()))
            .thenComparing(component -> cleanupValue(component.kind()))
            .thenComparingLong(ComponentRecord::id))
        .limit(Math.max(1, maxItems))
        .toList();
  }

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

  record CleanupFamilyCursor(String namespace, String name, String kind) {
  }

  private static int compareFamily(
      ComponentRecord component, CleanupFamilyCursor cursor) {
    int namespace = cleanupValue(component.namespace()).compareTo(cleanupValue(cursor.namespace()));
    if (namespace != 0) return namespace;
    int name = cleanupValue(component.name()).compareTo(cleanupValue(cursor.name()));
    if (name != 0) return name;
    return cleanupValue(component.kind()).compareTo(cleanupValue(cursor.kind()));
  }

  private static String cleanupValue(String value) {
    return value == null ? "" : value;
  }
}
