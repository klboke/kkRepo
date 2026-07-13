package com.github.klboke.kkrepo.server.support.dao;

import com.github.klboke.kkrepo.persistence.jdbc.api.RepositoryDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.RepositoryRecord;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Test-only base class for focused RepositoryDao fakes. */
public class RepositoryDaoAdapter implements RepositoryDao {
  public RepositoryDaoAdapter() {
  }

  public RepositoryDaoAdapter(Object ignored) {
  }

  public RepositoryDaoAdapter(Object firstIgnored, Object secondIgnored) {
  }

  @Override
  public void addMember(long arg0, long arg1, int arg2) {
    throw new UnsupportedOperationException();
  }

  @Override
  public void clearMembers(long arg0) {
    throw new UnsupportedOperationException();
  }

  @Override
  public int deleteById(long arg0) {
    throw new UnsupportedOperationException();
  }

  @Override
  public boolean existsByName(String arg0) {
    throw new UnsupportedOperationException();
  }

  @Override
  public Optional<RepositoryRecord> findById(long arg0) {
    throw new UnsupportedOperationException();
  }

  @Override
  public Optional<RepositoryRecord> findByName(String arg0) {
    throw new UnsupportedOperationException();
  }

  @Override
  public List<String> findNamesUsingBlobStore(long arg0) {
    throw new UnsupportedOperationException();
  }

  @Override
  public boolean hasComponents(long arg0) {
    throw new UnsupportedOperationException();
  }

  @Override
  public long insert(RepositoryRecord arg0) {
    throw new UnsupportedOperationException();
  }

  @Override
  public List<RepositoryRecord> list() {
    throw new UnsupportedOperationException();
  }

  @Override
  public Map<Long, List<String>> listAllGroupMembers() {
    throw new UnsupportedOperationException();
  }

  @Override
  public List<RepositoryRecord> listGroupsContaining(long arg0) {
    throw new UnsupportedOperationException();
  }

  @Override
  public List<RepositoryRecord> listMembers(long arg0) {
    throw new UnsupportedOperationException();
  }

  @Override
  public void replaceMembers(long arg0, List<Long> arg1) {
    throw new UnsupportedOperationException();
  }

  @Override
  public void update(RepositoryRecord arg0) {
    throw new UnsupportedOperationException();
  }

  @Override
  public RepositoryRecord upsertByName(RepositoryRecord arg0) {
    throw new UnsupportedOperationException();
  }
}
