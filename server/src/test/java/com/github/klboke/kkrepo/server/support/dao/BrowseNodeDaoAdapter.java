package com.github.klboke.kkrepo.server.support.dao;

import com.github.klboke.kkrepo.persistence.jdbc.api.BrowseNodeDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.BrowseNodeDao.BrowseChild;
import java.util.List;

/** Test-only base class for focused BrowseNodeDao fakes. */
public class BrowseNodeDaoAdapter implements BrowseNodeDao {
  public BrowseNodeDaoAdapter() {
  }

  public BrowseNodeDaoAdapter(Object ignored) {
  }

  public BrowseNodeDaoAdapter(Object firstIgnored, Object secondIgnored) {
  }

  @Override
  public int deleteAllForRepository(long arg0) {
    throw new UnsupportedOperationException();
  }

  @Override
  public int deleteByAssetId(long arg0) {
    throw new UnsupportedOperationException();
  }

  @Override
  public List<String> listChildPaths(long arg0, String arg1) {
    throw new UnsupportedOperationException();
  }

  @Override
  public List<BrowseChild> listChildren(long arg0, String arg1) {
    throw new UnsupportedOperationException();
  }

  @Override
  public void upsertPathAncestors(long arg0, String arg1, Long arg2, Long arg3) {
    throw new UnsupportedOperationException();
  }
}
