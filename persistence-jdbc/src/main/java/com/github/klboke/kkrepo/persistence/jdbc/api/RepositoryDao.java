package com.github.klboke.kkrepo.persistence.jdbc.api;

import com.github.klboke.kkrepo.persistence.jdbc.api.model.RepositoryRecord;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public interface RepositoryDao {
  long insert(RepositoryRecord record);

  Optional<RepositoryRecord> findById(long id);

  Optional<RepositoryRecord> findByName(String name);

  List<RepositoryRecord> list();

  void update(RepositoryRecord record);

  RepositoryRecord upsertByName(RepositoryRecord record);

  int deleteById(long id);

  boolean existsByName(String name);

  void addMember(long groupRepositoryId, long memberRepositoryId, int sortOrder);

  List<RepositoryRecord> listMembers(long groupRepositoryId);

  Map<Long, List<String>> listAllGroupMembers();

  void clearMembers(long groupRepositoryId);

  void replaceMembers(long groupRepositoryId, List<Long> memberRepositoryIds);

  List<RepositoryRecord> listGroupsContaining(long memberRepositoryId);

  List<String> findNamesUsingBlobStore(long blobStoreId);

  boolean hasComponents(long repositoryId);
}
