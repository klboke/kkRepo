import { auditLogPage, auditLogTotalPages, loadAuditLogs, resetAuditLogFilters } from "./log.js";

export function bindAuditEvents() {
  document.getElementById("audit-log-filter-form").addEventListener("submit", (event) => {
    event.preventDefault();
    loadAuditLogs(0);
  });
  document.getElementById("audit-log-reset-button").addEventListener("click", resetAuditLogFilters);
  document.getElementById("audit-log-prev-page").addEventListener("click", () => {
    if (auditLogPage.page > 0) loadAuditLogs(auditLogPage.page - 1);
  });
  document.getElementById("audit-log-next-page").addEventListener("click", () => {
    if (auditLogPage.page < auditLogTotalPages() - 1) loadAuditLogs(auditLogPage.page + 1);
  });
  document.getElementById("audit-log-size").addEventListener("change", () => loadAuditLogs(0));
}
