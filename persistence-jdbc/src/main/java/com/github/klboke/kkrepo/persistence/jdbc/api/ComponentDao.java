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
}
