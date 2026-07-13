package com.github.klboke.kkrepo.server.support.dao;

import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.persistence.jdbc.api.ComponentDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.ComponentDao.ComponentSearchRow;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.ComponentRecord;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Test-only base class for focused ComponentDao fakes. */
public class ComponentDaoAdapter implements ComponentDao {
  public ComponentDaoAdapter() {
  }

  public ComponentDaoAdapter(Object ignored) {
  }

  public ComponentDaoAdapter(Object firstIgnored, Object secondIgnored) {
  }

  @Override
  public long countByRepositoryId(long arg0) {
    throw new UnsupportedOperationException();
  }

  @Override
  public int deleteIfNoAssets(long arg0) {
    throw new UnsupportedOperationException();
  }

  @Override
  public Optional<ComponentRecord> findByCoordinateHash(long arg0, byte[] arg1) {
    throw new UnsupportedOperationException();
  }

  @Override
  public Optional<ComponentRecord> findByGav(long arg0, String arg1, String arg2, String arg3) {
    throw new UnsupportedOperationException();
  }

  @Override
  public Optional<ComponentRecord> findById(long arg0) {
    throw new UnsupportedOperationException();
  }

  @Override
  public Optional<ComponentRecord> findByNameAndVersion(long arg0, String arg1, String arg2) {
    throw new UnsupportedOperationException();
  }

  @Override
  public long insert(ComponentRecord arg0) {
    throw new UnsupportedOperationException();
  }

  @Override
  public List<ComponentRecord> listByGa(long arg0, String arg1, String arg2) {
    throw new UnsupportedOperationException();
  }

  @Override
  public List<ComponentRecord> listByName(long arg0, String arg1) {
    throw new UnsupportedOperationException();
  }

  @Override
  public List<ComponentRecord> listByRepositoryId(long arg0) {
    throw new UnsupportedOperationException();
  }

  @Override
  public List<String> listDistinctNamesByRepositoryId(long arg0) {
    throw new UnsupportedOperationException();
  }

  @Override
  public List<ComponentSearchRow> search(String arg0, RepositoryFormat arg1, int arg2) {
    throw new UnsupportedOperationException();
  }

  @Override
  public List<ComponentSearchRow> searchByRepositoryIds(List<Long> arg0, RepositoryFormat arg1, String arg2, int arg3) {
    throw new UnsupportedOperationException();
  }

  @Override
  public List<ComponentSearchRow> searchByRepositoryIds(List<Long> arg0, String arg1, int arg2) {
    throw new UnsupportedOperationException();
  }

  @Override
  public List<ComponentRecord> searchComponentsByRepositoryIds(List<Long> arg0, RepositoryFormat arg1, String arg2, int arg3) {
    throw new UnsupportedOperationException();
  }

  @Override
  public int touchLastUpdated(long arg0, Instant arg1) {
    throw new UnsupportedOperationException();
  }

  @Override
  public int updateAttributes(long arg0, Map<String, Object> arg1, Instant arg2) {
    throw new UnsupportedOperationException();
  }

  @Override
  public long upsertReturningId(ComponentRecord arg0) {
    throw new UnsupportedOperationException();
  }
}
