package com.github.klboke.kkrepo.server.security;

import com.github.klboke.kkrepo.persistence.jdbc.api.SecurityAuditDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.SecurityAuditDao.AuditLogPage;
import com.github.klboke.kkrepo.persistence.jdbc.api.SecurityAuditDao.AuditLogQuery;
import java.time.LocalDateTime;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/security/audit-log")
public class SecurityAuditLogController {
  private final SecurityAuditDao auditDao;

  public SecurityAuditLogController(SecurityAuditDao auditDao) {
    this.auditDao = auditDao;
  }

  @GetMapping
  public AuditLogPage list(
      @RequestParam(name = "q", required = false) String query,
      @RequestParam(name = "actorSource", required = false) String actorSource,
      @RequestParam(name = "actorUserId", required = false) String actorUserId,
      @RequestParam(name = "remoteAddr", required = false) String remoteAddr,
      @RequestParam(name = "method", required = false) String method,
      @RequestParam(name = "path", required = false) String path,
      @RequestParam(name = "permission", required = false) String permission,
      @RequestParam(name = "status", required = false) Integer status,
      @RequestParam(name = "outcome", required = false) String outcome,
      @RequestParam(name = "from", required = false)
      @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
      @RequestParam(name = "to", required = false)
      @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to,
      @RequestParam(name = "page", defaultValue = "0") int page,
      @RequestParam(name = "size", defaultValue = "50") int size) {
    return auditDao.search(new AuditLogQuery(
        query,
        actorSource,
        actorUserId,
        remoteAddr,
        method,
        path,
        permission,
        status,
        outcome,
        from,
        to,
        page,
        size));
  }
}
