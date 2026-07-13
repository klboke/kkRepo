package com.github.klboke.kkrepo.server.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.github.klboke.kkrepo.persistence.jdbc.api.SecurityAuditDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.SecurityAuditDao.AuditLogRecord;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class ManagementAuditFilterTest {

  @Test
  void successfulMutationRecordsActorPermissionContextPathAndTrustedClientAddress() throws Exception {
    SecurityAuditDao auditDao = mock(SecurityAuditDao.class);
    ManagementAuditFilter filter = filter(auditDao, "127.0.0.1", false);
    MockHttpServletRequest request =
        new MockHttpServletRequest("POST", "/kkrepo/internal/security/users");
    request.setContextPath("/kkrepo");
    request.setRemoteAddr("127.0.0.1");
    request.addHeader("X-Forwarded-For", "203.0.113.7, 10.0.0.1");
    request.setAttribute(
        AuthenticatedSubject.REQUEST_ATTRIBUTE,
        new AuthenticatedSubject("api_key", "alice", "local", 33L, null));
    request.setAttribute(SecurityManagementFilter.REQUESTED_PERMISSION_ATTRIBUTE, "security:user:update");
    MockHttpServletResponse response = new MockHttpServletResponse();

    filter.doFilter(request, response, (req, resp) -> ((HttpServletResponse) resp).setStatus(201));

    ArgumentCaptor<AuditLogRecord> record = ArgumentCaptor.forClass(AuditLogRecord.class);
    verify(auditDao).insert(record.capture());
    assertEquals("api_key", record.getValue().actorSource());
    assertEquals("alice", record.getValue().actorUserId());
    assertEquals("local", record.getValue().actorRealmId());
    assertEquals(33L, record.getValue().actorApiKeyId());
    assertEquals("203.0.113.7", record.getValue().remoteAddr());
    assertEquals("POST", record.getValue().method());
    assertEquals("/internal/security/users", record.getValue().path());
    assertEquals("security:user:update", record.getValue().permission());
    assertEquals(201, record.getValue().status());
    assertEquals("SUCCESS", record.getValue().outcome());
    assertTrue(record.getValue().details().isEmpty());
  }

  @Test
  void readAndNonManagementRequestsAreNotAudited() throws Exception {
    SecurityAuditDao auditDao = mock(SecurityAuditDao.class);
    ManagementAuditFilter filter = filter(auditDao, "", false);

    filter.doFilter(
        new MockHttpServletRequest("GET", "/internal/security/users"),
        new MockHttpServletResponse(),
        (req, resp) -> {
        });
    filter.doFilter(
        new MockHttpServletRequest("POST", "/repository/maven-releases/a.jar"),
        new MockHttpServletResponse(),
        (req, resp) -> {
        });

    verify(auditDao, never()).insert(any());
  }

  @Test
  void sendErrorAndThrownFailureAreRecordedAsFailures() throws Exception {
    SecurityAuditDao auditDao = mock(SecurityAuditDao.class);
    ManagementAuditFilter filter = filter(auditDao, "", false);
    MockHttpServletRequest rejected =
        new MockHttpServletRequest("DELETE", "/service/rest/v1/security/users/alice");
    rejected.setRemoteAddr("198.51.100.5");

    filter.doFilter(
        rejected,
        new MockHttpServletResponse(),
        (req, resp) -> ((HttpServletResponse) resp).sendError(403, "denied"));

    MockHttpServletRequest failed =
        new MockHttpServletRequest("POST", "/internal/repositories");
    failed.setRemoteAddr("198.51.100.6");
    IllegalStateException failure = new IllegalStateException("database unavailable");
    assertThrows(IllegalStateException.class, () ->
        filter.doFilter(failed, new MockHttpServletResponse(), (req, resp) -> {
          throw failure;
        }));

    ArgumentCaptor<AuditLogRecord> records = ArgumentCaptor.forClass(AuditLogRecord.class);
    verify(auditDao, org.mockito.Mockito.times(2)).insert(records.capture());
    assertEquals(403, records.getAllValues().get(0).status());
    assertEquals("FAILURE", records.getAllValues().get(0).outcome());
    assertTrue(records.getAllValues().get(0).details().isEmpty());
    assertEquals(200, records.getAllValues().get(1).status());
    assertEquals("FAILURE", records.getAllValues().get(1).outcome());
    assertEquals("IllegalStateException", records.getAllValues().get(1).details().get("error"));
  }

  @Test
  void auditPersistenceFailureDoesNotChangeManagementResponse() throws Exception {
    SecurityAuditDao auditDao = mock(SecurityAuditDao.class);
    doThrow(new IllegalStateException("audit unavailable")).when(auditDao).insert(any());
    ManagementAuditFilter filter = filter(auditDao, "", true);
    MockHttpServletRequest request =
        new MockHttpServletRequest("POST", "/service/extdirect");
    MockHttpServletResponse response = new MockHttpServletResponse();

    filter.doFilter(request, response, (req, resp) -> ((HttpServletResponse) resp).setStatus(204));

    assertEquals(204, response.getStatus());
    verify(auditDao).insert(any());
  }

  @Test
  void servletFailureIsRethrownAfterAudit() throws Exception {
    SecurityAuditDao auditDao = mock(SecurityAuditDao.class);
    ManagementAuditFilter filter = filter(auditDao, "", false);
    MockHttpServletRequest request =
        new MockHttpServletRequest("PATCH", "/internal/security/roles/admin");
    ServletException failure = new ServletException("role update failed");

    assertThrows(ServletException.class, () ->
        filter.doFilter(request, new MockHttpServletResponse(), (req, resp) -> {
          throw failure;
        }));

    ArgumentCaptor<AuditLogRecord> record = ArgumentCaptor.forClass(AuditLogRecord.class);
    verify(auditDao).insert(record.capture());
    assertEquals("ServletException", record.getValue().details().get("error"));
  }

  private static ManagementAuditFilter filter(
      SecurityAuditDao auditDao,
      String trustedProxies,
      boolean legacyUiEnabled) {
    return new ManagementAuditFilter(
        auditDao,
        new ForwardedHeaderPolicy(trustedProxies),
        new NexusLegacyUiCompatibility(legacyUiEnabled));
  }
}
