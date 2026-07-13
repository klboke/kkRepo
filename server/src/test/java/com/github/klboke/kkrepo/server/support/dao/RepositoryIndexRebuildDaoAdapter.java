package com.github.klboke.kkrepo.server.support.dao;

import com.github.klboke.kkrepo.persistence.jdbc.api.RepositoryIndexRebuildDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.RepositoryIndexRebuildDao.Claim;
import java.util.List;

/** Test-only base class for focused RepositoryIndexRebuildDao fakes. */
public class RepositoryIndexRebuildDaoAdapter implements RepositoryIndexRebuildDao {
  public RepositoryIndexRebuildDaoAdapter() {
  }

  public RepositoryIndexRebuildDaoAdapter(Object ignored) {
  }

  public RepositoryIndexRebuildDaoAdapter(Object firstIgnored, Object secondIgnored) {
  }

  @Override
  public List<Claim> claim(int arg0) {
    throw new UnsupportedOperationException();
  }

  @Override
  public long countBacklog() {
    throw new UnsupportedOperationException();
  }

  @Override
  public long countFailures() {
    throw new UnsupportedOperationException();
  }

  @Override
  public void enqueue(long arg0, String arg1, String arg2) {
    throw new UnsupportedOperationException();
  }

  @Override
  public void enqueue(long arg0, String arg1) {
    throw new UnsupportedOperationException();
  }

  @Override
  public boolean hasPending(long arg0, String arg1, String arg2) {
    throw new UnsupportedOperationException();
  }

  @Override
  public long oldestBacklogAgeSeconds() {
    throw new UnsupportedOperationException();
  }

  @Override
  public void reenqueueFailure(Claim arg0, RuntimeException arg1) {
    throw new UnsupportedOperationException();
  }
}
