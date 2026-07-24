package com.github.klboke.kkrepo.server.securityscan;

import com.github.klboke.kkrepo.persistence.jdbc.api.SecurityAuditDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.SecurityAuditDao.AuditLogRecord;
import com.github.klboke.kkrepo.server.security.AuthenticatedSubject;
import jakarta.servlet.http.HttpServletRequest;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class SecurityScanAuditService {
  private final SecurityAuditDao audit;

  public SecurityScanAuditService(SecurityAuditDao audit) {
    this.audit = audit;
  }

  public void record(
      HttpServletRequest request,
      AuthenticatedSubject actor,
      String action,
      Long repositoryId,
      Map<String, Object> details) {
    Map<String, Object> safe = new LinkedHashMap<>();
    safe.put("action", action);
    if (repositoryId != null) safe.put("repositoryId", repositoryId);
    if (details != null) safe.putAll(details);
    audit.insert(new AuditLogRecord(
        LocalDateTime.now(),
        actor.source(),
        actor.userId(),
        actor.realmId(),
        actor.apiKeyId(),
        request == null ? null : request.getRemoteAddr(),
        request == null ? null : request.getMethod(),
        request == null ? "/internal/security/scanning" : request.getRequestURI(),
        "nexus:security-scanning:" + action.toLowerCase(java.util.Locale.ROOT),
        200,
        "SUCCESS",
        Map.copyOf(safe)));
  }

  public void recordSystem(
      String action, Long repositoryId, Map<String, Object> details) {
    Map<String, Object> safe = new LinkedHashMap<>();
    safe.put("action", action);
    if (repositoryId != null) safe.put("repositoryId", repositoryId);
    if (details != null) safe.putAll(details);
    audit.insert(new AuditLogRecord(
        LocalDateTime.now(),
        "system",
        "security-scan-worker",
        "system",
        null,
        null,
        "SCHEDULED",
        "/internal/security/scanning",
        "nexus:security-scanning:" + action.toLowerCase(java.util.Locale.ROOT),
        200,
        "SUCCESS",
        Map.copyOf(safe)));
  }
}
