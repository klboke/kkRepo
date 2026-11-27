import { escapeHtml } from "../controls/display.js";
import { showToast } from "../controls/toast.js";
import { displaySource } from "../security/access.js";
import { responseErrorMessage } from "../shell/http.js";

const AUDIT_LOG_DEFAULT_PAGE_SIZE = 15;

let auditLogPage = { total: 0, page: 0, size: AUDIT_LOG_DEFAULT_PAGE_SIZE, items: [] };

function auditLogParams(page = auditLogPage.page) {
  const params = new URLSearchParams();
  params.set("page", Math.max(0, Number(page) || 0));
  params.set("size", Number(document.getElementById("audit-log-size").value || auditLogPage.size || AUDIT_LOG_DEFAULT_PAGE_SIZE));
  const fields = [
    ["q", "audit-log-query"],
    ["actorUserId", "audit-log-actor"],
    ["outcome", "audit-log-outcome"],
    ["from", "audit-log-from"],
    ["to", "audit-log-to"]
  ];
  fields.forEach(([name, id]) => {
    const value = document.getElementById(id).value.trim();
    if (value) params.set(name, value);
  });
  return params;
}

async function loadAuditLogs(page = 0) {
  try {
    const response = await fetch(`/internal/security/audit-log?${auditLogParams(page).toString()}`, {
      cache: "no-store"
    });
    if (!response.ok) throw new Error(await responseErrorMessage(response));
    const payload = await response.json();
    auditLogPage = {
      total: Number(payload.total) || 0,
      page: Number(payload.page) || 0,
      size: Number(payload.size) || AUDIT_LOG_DEFAULT_PAGE_SIZE,
      items: payload.items || []
    };
    const totalPages = auditLogTotalPages();
    if (auditLogPage.total > 0 && auditLogPage.page >= totalPages) {
      await loadAuditLogs(totalPages - 1);
      return;
    }
  } catch (error) {
    auditLogPage = {
      total: 0,
      page: 0,
      size: Number(document.getElementById("audit-log-size").value || AUDIT_LOG_DEFAULT_PAGE_SIZE),
      items: []
    };
    showToast(`Failed to load audit log: ${error.message}`, "error");
  }
  renderAuditLogs();
}

function auditLogTotalPages() {
  return Math.max(1, Math.ceil((Number(auditLogPage.total) || 0) / (Number(auditLogPage.size) || AUDIT_LOG_DEFAULT_PAGE_SIZE)));
}

function renderAuditLogs() {
  const rows = auditLogPage.items.map((entry) => `
    <tr>
      <td><code>${escapeHtml(formatAuditTimestamp(entry.occurredAt))}</code></td>
      <td>${auditActor(entry)}</td>
      <td>${escapeHtml(entry.remoteAddr || "")}</td>
      <td><code>${escapeHtml(entry.method || "")}</code></td>
      <td><code class="audit-path-cell" title="${escapeHtml(entry.path || "")}">${escapeHtml(entry.path || "")}</code></td>
      <td><code class="audit-permission-cell" title="${escapeHtml(entry.permission || "")}">${escapeHtml(entry.permission || "")}</code></td>
      <td>${entry.status == null ? '<span class="health-muted">-</span>' : escapeHtml(entry.status)}</td>
      <td>${auditOutcomeBadge(entry.outcome)}</td>
      <td>${auditDetails(entry.details)}</td>
    </tr>
  `).join("");
  document.getElementById("audit-log-table").innerHTML = rows
    || '<tr><td colspan="9" class="placeholder">No audit records.</td></tr>';
  const totalPages = auditLogTotalPages();
  const first = auditLogPage.total === 0 ? 0 : auditLogPage.page * auditLogPage.size + 1;
  const last = Math.min(auditLogPage.total, (auditLogPage.page + 1) * auditLogPage.size);
  document.getElementById("audit-log-summary").textContent = `${first}-${last} of ${auditLogPage.total}`;
  document.getElementById("audit-log-page-label").textContent = `Page ${auditLogPage.page + 1} / ${totalPages}`;
  document.getElementById("audit-log-prev-page").disabled = auditLogPage.page <= 0;
  document.getElementById("audit-log-next-page").disabled = auditLogPage.page >= totalPages - 1;
  document.getElementById("audit-log-size").value = String(auditLogPage.size);
}

function formatAuditTimestamp(value) {
  if (!value) return "";
  return String(value)
    .replace("T", " ")
    .replace(/(\.\d{3})\d*/, "$1");
}

function auditActor(entry) {
  const source = entry.actorSource ? `${displaySource(entry.actorSource)}/` : "";
  const user = entry.actorUserId ? `${source}${entry.actorUserId}` : "system";
  const apiKey = entry.actorApiKeyId == null ? "" : ` <span class="state-badge compact">key #${escapeHtml(entry.actorApiKeyId)}</span>`;
  return `${escapeHtml(user)}${apiKey}`;
}

function auditOutcomeBadge(outcome) {
  const value = String(outcome || "");
  const tone = value === "SUCCESS" ? "ok" : value === "FAILURE" ? "bad" : "checking";
  return `<span class="state-badge compact ${tone}">${escapeHtml(value || "-")}</span>`;
}

function auditDetails(details) {
  if (!details || Object.keys(details).length === 0) {
    return '<span class="health-muted">-</span>';
  }
  const text = JSON.stringify(details);
  return `<code class="audit-details-cell" title="${escapeHtml(text)}">${escapeHtml(text)}</code>`;
}

function resetAuditLogFilters() {
  [
    "audit-log-query",
    "audit-log-actor",
    "audit-log-outcome",
    "audit-log-from",
    "audit-log-to"
  ].forEach((id) => {
    document.getElementById(id).value = "";
  });
  loadAuditLogs(0);
}

export {
  auditLogPage,
  auditLogTotalPages,
  loadAuditLogs,
  resetAuditLogFilters,
};
