package com.github.klboke.kkrepo.server.security;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.github.klboke.kkrepo.persistence.jdbc.api.SecurityAuditDao.AuditLogEntry;
import com.github.klboke.kkrepo.persistence.jdbc.api.SecurityAuditDao.AuditLogPage;
import com.github.klboke.kkrepo.persistence.jdbc.api.SecurityAuditDao.AuditLogQuery;
import com.github.klboke.kkrepo.persistence.jdbc.api.SecurityAuditDao;
import com.github.klboke.kkrepo.server.support.dao.SecurityAuditDaoAdapter;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SecurityAuditLogControllerTest {
  @Test
  void listPassesSearchAndPaginationParametersToDao() {
    FakeSecurityAuditDao dao = new FakeSecurityAuditDao();
    SecurityAuditLogController controller = new SecurityAuditLogController(dao);
    LocalDateTime from = LocalDateTime.of(2026, 6, 1, 9, 0);
    LocalDateTime to = LocalDateTime.of(2026, 6, 1, 18, 0);

    AuditLogPage page = controller.list(
        "admin",
        "Local",
        "admin",
        "10.0.0.8",
        "POST",
        "/internal/security/users",
        "nexus:users:create",
        201,
        "SUCCESS",
        from,
        to,
        2,
        25);

    assertEquals(1, page.total());
    assertEquals("admin", dao.query.query());
    assertEquals("Local", dao.query.actorSource());
    assertEquals("admin", dao.query.actorUserId());
    assertEquals("10.0.0.8", dao.query.remoteAddr());
    assertEquals("POST", dao.query.method());
    assertEquals("/internal/security/users", dao.query.path());
    assertEquals("nexus:users:create", dao.query.permission());
    assertEquals(201, dao.query.status());
    assertEquals("SUCCESS", dao.query.outcome());
    assertEquals(from, dao.query.from());
    assertEquals(to, dao.query.to());
    assertEquals(2, dao.query.page());
    assertEquals(25, dao.query.size());
  }

  private static class FakeSecurityAuditDao extends SecurityAuditDaoAdapter {
    private AuditLogQuery query;

    private FakeSecurityAuditDao() {
      super(null, null);
    }

    @Override
    public AuditLogPage search(AuditLogQuery query) {
      this.query = query;
      return new AuditLogPage(
          1,
          query.page(),
          query.size(),
          List.of(new AuditLogEntry(
              1,
              LocalDateTime.of(2026, 6, 1, 12, 0),
              "Local",
              "admin",
              "local",
              null,
              "127.0.0.1",
              "POST",
              "/internal/security/users",
              "nexus:users:create",
              201,
              "SUCCESS",
              Map.of())));
    }
  }
}
