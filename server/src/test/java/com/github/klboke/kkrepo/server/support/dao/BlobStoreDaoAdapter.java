package com.github.klboke.kkrepo.server.support.dao;

import com.github.klboke.kkrepo.persistence.jdbc.api.BlobStoreDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.BlobStoreRecord;
import java.util.List;
import java.util.Optional;

/** Test-only base class for focused BlobStoreDao fakes. */
public class BlobStoreDaoAdapter implements BlobStoreDao {
  public BlobStoreDaoAdapter() {
  }

  public BlobStoreDaoAdapter(Object ignored) {
  }

  public BlobStoreDaoAdapter(Object firstIgnored, Object secondIgnored) {
  }

  @Override
  public int deleteById(long arg0) {
    throw new UnsupportedOperationException();
  }

  @Override
  public Optional<BlobStoreRecord> findById(long arg0) {
    throw new UnsupportedOperationException();
  }

  @Override
  public Optional<BlobStoreRecord> findByName(String arg0) {
    throw new UnsupportedOperationException();
  }

  @Override
  public long insert(BlobStoreRecord arg0) {
    throw new UnsupportedOperationException();
  }

  @Override
  public List<BlobStoreRecord> list() {
    throw new UnsupportedOperationException();
  }

  @Override
  public void update(BlobStoreRecord arg0) {
    throw new UnsupportedOperationException();
  }

  @Override
  public void updateById(BlobStoreRecord arg0) {
    throw new UnsupportedOperationException();
  }

  @Override
  public BlobStoreRecord upsertByName(BlobStoreRecord arg0) {
    throw new UnsupportedOperationException();
  }
}
