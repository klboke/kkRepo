package com.github.klboke.kkrepo.server.maintenance;

import com.github.klboke.kkrepo.persistence.jdbc.api.SecurityAuditDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.SecurityAuditDao.AuditLogRecord;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/** Writes maintenance results to the existing security audit log when available. */
@Component
public class SecurityAuditMaintenanceAudit implements MaintenanceAudit {
  private static final Logger log = LoggerFactory.getLogger(SecurityAuditMaintenanceAudit.class);

  private final ObjectProvider<SecurityAuditDao> audits;
  private final Clock clock;

  public SecurityAuditMaintenanceAudit(ObjectProvider<SecurityAuditDao> audits) {
    this(audits, Clock.systemUTC());
  }

  SecurityAuditMaintenanceAudit(ObjectProvider<SecurityAuditDao> audits, Clock clock) {
    this.audits = audits;
    this.clock = clock;
  }

  @Override
  public void record(MaintenanceAuditEvent event) {
    SecurityAuditDao dao = audits.getIfAvailable();
    if (dao == null) {
      log.info(
          "Maintenance audit (no audit DAO): task={} dryRun={} proposed={} deleted={} outcome={}",
          event.taskId(),
          event.dryRun(),
          event.proposed(),
          event.deleted(),
          event.outcome());
      return;
    }
    Map<String, Object> details = new LinkedHashMap<>();
    details.put("dryRun", event.dryRun());
    details.put("proposed", event.proposed());
    details.put("deleted", event.deleted());
    details.put("durationMs", event.durationMs());
    if (event.error() != null) {
      details.put("error", event.error());
    }
    try {
      dao.insert(new AuditLogRecord(
          LocalDateTime.now(clock),
          "system:maintenance",
          null,
          null,
          null,
          null,
          "SCRIPT",
          "maintenance/" + event.taskId(),
          "maintenance:execute",
          "SUCCESS".equals(event.outcome()) ? 200 : 500,
          event.outcome(),
          details));
    } catch (RuntimeException failure) {
      log.warn("Unable to persist maintenance audit for task {}", event.taskId(), failure);
    }
  }
}
