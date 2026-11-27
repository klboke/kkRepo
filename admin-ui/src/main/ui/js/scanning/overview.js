import { escapeHtml, formatDateTime, shortText } from "../controls/display.js";
import { renderSecurityScanFindings } from "./findings.js";
import {
  fetchSecurityScanPage,
  loadSecurityScanList,
  renderSecurityScanPagination,
  securityScanListEndpoints,
  securityScanListItems,
  securityScanPages,
  securityScanState,
  setSecurityScanListItems,
} from "./pages.js";
import { renderSecurityScanPolicies, renderSecurityScanRepositories } from "./policies.js";
import { renderSecurityScanTasks } from "./tasks.js";
import { renderSecurityScanWaivers } from "./waivers.js";
import { fetchJson } from "../shell/http.js";
import { SECURITY_SCAN_TABS, updateHashForSecurityScanTab } from "../shell/routes.js";
import { canAdmin } from "../shell/session.js";

function securityScanTone(status) {
  const value = String(status || "").toUpperCase();
  if (["COMPLETE", "SUCCEEDED", "ALLOW", "READY"].includes(value)) return "ok";
  if (["FAILED", "BLOCK_VULNERABILITY", "CANCELLED"].includes(value)) return "error";
  return "warn";
}

const SECURITY_SCAN_SEVERITY_PRESENTATION = Object.freeze({
  CRITICAL: { tone: "is-critical", icon: "octagon-alert" },
  HIGH: { tone: "is-high", icon: "triangle-alert" },
  MEDIUM: { tone: "is-medium", icon: "circle-alert" },
  LOW: { tone: "is-low", icon: "info" },
  UNKNOWN: { tone: "is-unknown", icon: "circle-help" }
});

function renderSecurityScanSeverity(severity) {
  const value = String(severity || "UNKNOWN").toUpperCase();
  const presentation =
    SECURITY_SCAN_SEVERITY_PRESENTATION[value]
      || SECURITY_SCAN_SEVERITY_PRESENTATION.UNKNOWN;
  return `
    <span class="state-badge compact security-scan-severity ${presentation.tone}">
      <span class="lucide-icon icon-${presentation.icon}" aria-hidden="true"></span>
      ${escapeHtml(value)}
    </span>`;
}

function securityScanCanMutate(permission = "nexus:security-scanning:update") {
  return securityScanState.summary?.deploymentEnabled === true && canAdmin(permission);
}

function applySecurityScanDeploymentState(enabled, options = {}) {
  const view = document.getElementById("security-scanning-view");
  const content = document.getElementById("security-scan-capability-content");
  const banner = document.getElementById("security-scan-capability-banner");
  const pending = Boolean(options.pending);
  const unavailable = Boolean(options.unavailable);
  const available = enabled === true && !pending;

  view.classList.toggle("is-deployment-pending", pending);
  view.classList.toggle("is-deployment-disabled", !available && !pending);
  content.inert = !available;
  content.setAttribute("aria-disabled", String(!available));

  content.querySelectorAll("button, input, select, textarea").forEach((control) => {
    if (!available && !control.disabled) {
      control.dataset.securityScanDeploymentDisabled = "true";
      control.disabled = true;
      return;
    }
    if (available && control.dataset.securityScanDeploymentDisabled === "true") {
      control.disabled = false;
      delete control.dataset.securityScanDeploymentDisabled;
    }
  });
  content.querySelectorAll("a[href]").forEach((link) => {
    if (available) {
      link.removeAttribute("aria-disabled");
    } else {
      link.setAttribute("aria-disabled", "true");
    }
  });

  // Keep authorization separate from temporary deployment disabling. A refresh must
  // never reveal a mutation control to a reader when the deployment becomes ready.
  view.querySelectorAll("[data-scan-permission]").forEach((control) => {
    control.hidden = !canAdmin(control.dataset.scanPermission);
  });
  banner.hidden = available;
  banner.classList.toggle("is-pending", pending);
  banner.classList.toggle("is-disabled", !available && !pending);
  if (pending) {
    banner.innerHTML = `
      <strong>Checking deployment capability…</strong>
      <span>Scanning controls remain unavailable until kkRepo confirms the deployment setting.</span>`;
  } else if (unavailable) {
    banner.innerHTML = `
      <strong>Unable to verify the artifact scanning deployment capability.</strong>
      <span>Scanning controls remain disabled. Check the kkRepo management API and reload this page.</span>`;
  } else if (available) {
    banner.replaceChildren();
  } else {
    banner.innerHTML = `
      <strong>Artifact scanning is unavailable in this deployment.</strong>
      <span>A deployment operator must deploy the scanner adapter, set KKREPO_SECURITY_SCANNING_ENABLED=true, and restart kkRepo. Existing repository settings are preserved.</span>`;
  }
}

function renderSecurityScanSummary() {
  const payload = securityScanState.summary;
  const target = document.getElementById("security-scan-summary");
  if (!payload) {
    target.innerHTML = '<div><span>Status</span><strong>Unavailable</strong></div>';
    document.getElementById("security-scan-status").textContent = "Capability status unavailable";
    return;
  }
  const summary = payload.summary || {};
  const scanner = payload.scanner;
  const matchingScanner = payload.matchingScanner || scanner;
  const scannerHealth = payload.scannerStatus;
  const scannerStatus = !payload.deploymentEnabled
    ? "Disabled"
    : scannerHealth?.ready ? "Ready" : "Degraded";
  const scannerPresentation = scannerStatus === "Ready"
    ? { tone: "is-ready", icon: "check" }
    : scannerStatus === "Degraded"
      ? { tone: "is-degraded", icon: "info" }
      : { tone: "is-disabled", icon: "circle-slash" };
  const scannerReason = securityScannerReasonLabel(scannerHealth?.reasonCode);
  const scannerDescription = scannerStatus === "Disabled"
    ? "Scanner deployment capability is disabled"
    : scannerStatus === "Ready"
      ? "Scanner is ready"
      : scannerReason;
  const scannerCard = `
    <div>
      <span>Scanner</span>
      <strong class="security-scan-scanner-state ${scannerPresentation.tone}"
        aria-label="${escapeHtml(scannerDescription)}"
        title="${escapeHtml(scannerDescription)}">
        <span class="lucide-icon icon-${scannerPresentation.icon}" aria-hidden="true"></span>
        ${escapeHtml(scannerStatus)}
      </strong>
    </div>`;
  const databaseRevision =
    String(matchingScanner?.vulnerabilityDatabaseRevision || "-");
  const databaseRevisionMatch =
    databaseRevision.match(/^(\d{4}-\d{2}-\d{2})T(.+)$/);
  const databaseRevisionMarkup = databaseRevisionMatch
    ? `<span class="security-scan-database-revision-date">${escapeHtml(databaseRevisionMatch[1])}</span>
       <span class="security-scan-database-revision-time">${escapeHtml(databaseRevisionMatch[2])}</span>`
    : escapeHtml(shortText(databaseRevision, 18));
  const databaseRevisionCard = `
    <div>
      <span class="security-scan-database-label"
        aria-label="Vulnerability DB version"
        title="Version of the vulnerability database used to match findings">Vulnerability DB</span>
      <strong class="security-scan-database-revision"
        title="${escapeHtml(databaseRevision)}">${databaseRevisionMarkup}</strong>
    </div>`;
  target.innerHTML = scannerCard + databaseRevisionCard + [
    ["Candidate backlog", summary.candidateBacklog ?? "—"],
    ["Pending tasks", summary.pendingTasks ?? "—"],
    ["Running", summary.runningTasks ?? "—"],
    ["Failed", summary.failedTasks ?? "—"],
    ["Complete assets", summary.completeAssets ?? "—"],
    ["Partial / stale", summary.partialAssets == null || summary.staleAssets == null ? "—" : summary.partialAssets + summary.staleAssets],
    ["Policy blocks", summary.blockedAssets ?? "—"],
    ["Critical / high", `${summary.criticalFindings ?? "—"} / ${summary.highFindings ?? "—"}`]
  ].map(([label, value]) =>
    `<div><span>${escapeHtml(label)}</span><strong>${escapeHtml(value)}</strong></div>`).join("");
  document.getElementById("security-scan-status").textContent =
    scanner?.observedAt ? `Scanner observed ${formatDateTime(scanner.observedAt)}` : "";
}

function securityScannerReasonLabel(reasonCode) {
  const labels = {
    SNAPSHOT_UNAVAILABLE: "Scanner status has not been observed",
    SCANNER_NOT_READY: "Scanner reported that it is not ready",
    SCANNER_OBSERVATION_STALE: "Scanner status observation is stale",
    DATABASE_AGE_UNKNOWN: "Vulnerability database age is unavailable",
    DATABASE_STALE: "Vulnerability database is stale"
  };
  return labels[reasonCode] || "Scanner readiness is degraded";
}

function renderSecurityScanRuns() {
  document.getElementById("security-scan-run-table").innerHTML =
    securityScanState.runs.map((run) => `
      <tr>
        <td>${escapeHtml(run.id)}</td>
        <td>${escapeHtml(run.taskId ?? "-")}</td>
        <td><span class="state-badge compact ${securityScanTone(run.status)}">${escapeHtml(run.status)}</span></td>
        <td>${escapeHtml(run.completeness)}</td>
        <td>${escapeHtml(run.findingCount)}</td>
        <td>${escapeHtml(run.criticalCount)}</td>
        <td>${escapeHtml(run.highCount)}</td>
        <td>${escapeHtml(formatDateTime(run.completedAt))}</td>
        <td><a class="row-action" href="/internal/security/scanning/sboms/${encodeURIComponent(run.sbomId)}">download</a></td>
      </tr>`).join("")
      || '<tr><td colspan="9" class="placeholder">No scan runs are visible.</td></tr>';
}

function renderSecurityScanning() {
  renderSecurityScanSummary();
  renderSecurityScanRuns();
  renderSecurityScanTasks();
  renderSecurityScanFindings();
  renderSecurityScanRepositories();
  renderSecurityScanPolicies();
  renderSecurityScanWaivers();
  const deploymentEnabled = securityScanState.summary?.deploymentEnabled === true;
  applySecurityScanDeploymentState(
    deploymentEnabled,
    { unavailable: securityScanState.summary == null });
  if (deploymentEnabled) {
    Object.keys(securityScanListEndpoints).forEach(renderSecurityScanPagination);
  }
}

async function loadSecurityScanning() {
  applySecurityScanDeploymentState(false, { pending: true });
  document.getElementById("security-scan-status").textContent = "Loading…";
  const keys = Object.keys(securityScanListEndpoints);
  const [summary, ...pages] = await Promise.all([
    fetchJson("/internal/security/scanning/summary", null, "Failed to load scan summary"),
    ...keys.map(fetchSecurityScanPage)
  ]);
  securityScanState.summary = summary;
  keys.forEach((key, index) => {
    const payload = pages[index];
    if (payload.requestVersion !== securityScanPages[key].requestVersion) return;
    securityScanPages[key].nextAfter = payload?.nextCursor ?? payload?.nextAfter ?? null;
    setSecurityScanListItems(key, Array.isArray(payload?.items) ? payload.items : []);
  });
  renderSecurityScanning();
  const emptyLaterPages = keys.filter(
    (key) => securityScanListItems(key).length === 0 && securityScanPages[key].page > 0);
  await Promise.all(emptyLaterPages.map((key) => {
    const page = securityScanPages[key];
    page.cursors.pop();
    page.page -= 1;
    page.after = page.cursors.at(-1) || 0;
    return loadSecurityScanList(key);
  }));
}

function selectSecurityScanTab(tab, options = {}) {
  const selected = SECURITY_SCAN_TABS.has(tab) ? tab : "overview";
  if (selected !== "repositories") ++securityScanState.repositoryEditRequest;
  if (options.updateHash !== false) {
    updateHashForSecurityScanTab(selected, Boolean(options.replaceHash));
  }
  document.querySelectorAll("[data-scan-tab]").forEach((button) => {
    const active = button.dataset.scanTab === selected;
    button.classList.toggle("is-active", active);
    button.setAttribute("aria-selected", String(active));
    button.tabIndex = active ? 0 : -1;
  });
  document.querySelectorAll("[data-scan-panel]").forEach((panel) => {
    const active = panel.dataset.scanPanel === selected;
    panel.classList.toggle("is-active", active);
    panel.hidden = !active;
  });
}

function handleSecurityScanTabKeydown(event) {
  const tabs = Array.from(document.querySelectorAll("[data-scan-tab]"));
  const currentIndex = tabs.indexOf(event.currentTarget);
  if (currentIndex < 0) return;
  let nextIndex;
  if (event.key === "ArrowRight") {
    nextIndex = (currentIndex + 1) % tabs.length;
  } else if (event.key === "ArrowLeft") {
    nextIndex = (currentIndex - 1 + tabs.length) % tabs.length;
  } else if (event.key === "Home") {
    nextIndex = 0;
  } else if (event.key === "End") {
    nextIndex = tabs.length - 1;
  } else {
    return;
  }
  event.preventDefault();
  const nextTab = tabs[nextIndex];
  selectSecurityScanTab(nextTab.dataset.scanTab);
  nextTab.focus();
}

export {
  SECURITY_SCAN_SEVERITY_PRESENTATION,
  handleSecurityScanTabKeydown,
  loadSecurityScanning,
  renderSecurityScanRuns,
  renderSecurityScanSeverity,
  securityScanCanMutate,
  securityScanTone,
  selectSecurityScanTab,
};
