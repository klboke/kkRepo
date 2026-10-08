package com.github.klboke.kkrepo.persistence.jdbc.api;

import com.github.klboke.kkrepo.persistence.jdbc.api.model.BlobStoreRecord;
import java.util.List;
import java.util.Optional;

public interface BlobStoreDao {
  enum DeleteResult {
    DELETED, NOT_FOUND, REPOSITORY_IN_USE, BLOBS_REMAIN, UPLOADS_IN_PROGRESS
  }

  long insert(BlobStoreRecord record);

  void update(BlobStoreRecord record);

  void updateById(BlobStoreRecord record);

  BlobStoreRecord upsertByName(BlobStoreRecord record);

  Optional<BlobStoreRecord> findById(long id);

  Optional<BlobStoreRecord> findByName(String name);

  List<BlobStoreRecord> list();

  int deleteById(long id);

  /** Call inside a database transaction; locks the catalog row before checking references. */
  DeleteResult deleteEmptyById(long id);
}
