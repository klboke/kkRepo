package com.github.klboke.kkrepo.server.support.dao;

import com.github.klboke.kkrepo.persistence.jdbc.api.ProxyStateDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.ProxyStateDao.ProxyRemoteState;
import java.time.Instant;
import java.util.Optional;

/** Test-only base class for focused ProxyStateDao fakes. */
public class ProxyStateDaoAdapter implements ProxyStateDao {
  public ProxyStateDaoAdapter() {
  }

  public ProxyStateDaoAdapter(Object ignored) {
  }

  public ProxyStateDaoAdapter(Object firstIgnored, Object secondIgnored) {
  }

  @Override
  public boolean isBlocked(long arg0, Instant arg1) {
    throw new UnsupportedOperationException();
  }

  @Override
  public Optional<ProxyRemoteState> loadState(long arg0) {
    throw new UnsupportedOperationException();
  }

  @Override
  public ProxyRemoteState recordFailure(long arg0, long arg1, String arg2, Instant arg3) {
    throw new UnsupportedOperationException();
  }

  @Override
  public void recordSuccess(long arg0, Instant arg1) {
    throw new UnsupportedOperationException();
  }
}
