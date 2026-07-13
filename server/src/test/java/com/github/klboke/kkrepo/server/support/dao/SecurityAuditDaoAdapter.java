package com.github.klboke.kkrepo.server.support.dao;

import com.github.klboke.kkrepo.persistence.jdbc.api.SecurityAuditDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.SecurityAuditDao.AuditLogPage;
import com.github.klboke.kkrepo.persistence.jdbc.api.SecurityAuditDao.AuditLogQuery;
import com.github.klboke.kkrepo.persistence.jdbc.api.SecurityAuditDao.AuditLogRecord;

/** Test-only base class for focused SecurityAuditDao fakes. */
public class SecurityAuditDaoAdapter implements SecurityAuditDao {
  public SecurityAuditDaoAdapter() {
  }

  public SecurityAuditDaoAdapter(Object ignored) {
  }

  public SecurityAuditDaoAdapter(Object firstIgnored, Object secondIgnored) {
  }

  @Override
  public void insert(AuditLogRecord arg0) {
    throw new UnsupportedOperationException();
  }

  @Override
  public AuditLogPage search(AuditLogQuery arg0) {
    throw new UnsupportedOperationException();
  }
}
