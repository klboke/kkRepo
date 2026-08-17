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
   * Returns a stable search page scoped to repositories the caller has already authorized.
   *
   * <p>The cursor follows {@code last_updated_at DESC, id DESC}. The default keeps lightweight
   * adapters source-compatible for the first page; production implementations must override this
   * method when callers need to continue after a cursor.
   */
  default List<ComponentSearchRow> searchPageByRepositoryIds(
      List<Long> repositoryIds,
      RepositoryFormat format,
      String keyword,
      ComponentSearchCursor after,
      int limit) {
    if (after != null) {
      throw new UnsupportedOperationException("component search cursor is not supported");
    }
    return searchByRepositoryIds(repositoryIds, format, keyword, limit);
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

  record ComponentSearchCursor(java.time.Instant lastUpdatedAt, long id) {
    public static ComponentSearchCursor after(ComponentSearchRow row) {
      return new ComponentSearchCursor(row.lastUpdatedAt(), row.id());
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
