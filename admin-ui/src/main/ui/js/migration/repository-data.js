import { escapeHtml, formatDateTime, shortText } from "../controls/display.js";
import { validateRequiredFields } from "../controls/fields.js";
import { showToast } from "../controls/toast.js";
import {
  renderMigrationList,
  renderMigrationPlan,
  renderMigrationPlanItems,
  renderMigrationProfile,
} from "./nexus.js";
import { responseErrorMessage } from "../shell/http.js";

let repositoryDataMigrationJobId = null;

let repositoryDataMigrationPollTimer = null;
let activeVisit = 0;
let viewActive = false;

const repositoryDataMigrationRequiredFields = [
  { id: "repository-data-migration-source-url", label: "Source URL" },
  { id: "repository-data-migration-source-username", label: "Source username" },
  { id: "repository-data-migration-source-password", label: "Source password" }
];

function repositoryDataMigrationPayload() {
  return {
    sourceBaseUrl: document.getElementById("repository-data-migration-source-url").value.trim(),
    sourceUsername: document.getElementById("repository-data-migration-source-username").value.trim(),
    sourcePassword: document.getElementById("repository-data-migration-source-password").value,
    pageSize: numberValue("repository-data-migration-page-size"),
    concurrency: numberValue("repository-data-migration-concurrency"),
    checksumValidation: document.getElementById("repository-data-migration-checksum-validation").checked,
    metadataSince: dateTimeInstantValue("repository-data-migration-metadata-since"),
    backupProxyRepositories: nameListValue("repository-data-migration-backup-proxies")
  };
}

function numberValue(id) {
  const value = Number.parseInt(document.getElementById(id).value, 10);
  return Number.isFinite(value) ? value : null;
}

function dateTimeInstantValue(id) {
  const value = document.getElementById(id).value;
  if (!value) return null;
  const timestamp = Date.parse(value);
  return Number.isFinite(timestamp) ? new Date(timestamp).toISOString() : null;
}

function nameListValue(id) {
  return document.getElementById(id).value
    .split(/[,\s]+/)
    .map((value) => value.trim())
    .filter(Boolean);
}

function renderRepositoryDataMigrationStatus(payload, title = "Repository data migration") {
  const result = document.getElementById("repository-data-migration-result");
  const jobs = payload.repositoryJobs || [];
  repositoryDataMigrationJobId = payload.jobId || repositoryDataMigrationJobId;
  const totalAssets = numberOrZero(payload.totalAssets);
  const migratedAssets = numberOrZero(payload.migratedAssets);
  const failedAssets = numberOrZero(payload.failedAssets);
  const completedAssets = migratedAssets + failedAssets;
  const pendingAssets = payload.pendingAssets == null
    ? Math.max(0, totalAssets - completedAssets)
    : numberOrZero(payload.pendingAssets);
  const packagePercent = progressPercent(completedAssets, totalAssets);
  const phase = repositoryDataMigrationPhase(payload, jobs, totalAssets, completedAssets);
  const sourceProfile = payload.sourceProfile || null;
  const migrationPlan = payload.migrationPlan || null;
  result.hidden = false;
  result.innerHTML = `
    <div class="form-title">${escapeHtml(title)}</div>
    <div class="summary-grid">
      <div><span>Job</span><strong>${escapeHtml(payload.jobId || "-")}</strong></div>
      <div><span>Status</span><strong>${escapeHtml(payload.status || "-")}</strong></div>
      <div><span>Started</span><strong>${escapeHtml(formatDateTime(payload.startedAt))}</strong></div>
      <div><span>Phase</span><strong>${escapeHtml(phase)}</strong></div>
      <div><span>Plan items</span><strong>${escapeHtml(migrationPlan?.items?.length ?? 0)}</strong></div>
      <div><span>Repositories</span><strong>${escapeHtml(payload.repositories ?? jobs.length)}</strong></div>
      <div><span>Discovered</span><strong>${escapeHtml(compactNumber(payload.discoveredAssets))}</strong></div>
      <div><span>Total packages</span><strong>${escapeHtml(compactNumber(totalAssets))}</strong></div>
      <div><span>Migrated</span><strong>${escapeHtml(compactNumber(migratedAssets))}</strong></div>
      <div><span>Pending</span><strong>${escapeHtml(compactNumber(pendingAssets))}</strong></div>
      <div><span>Failed</span><strong>${escapeHtml(compactNumber(failedAssets))}</strong></div>
      <div><span>Package progress</span><strong>${totalAssets > 0 ? escapeHtml(formatPercent(packagePercent)) : "—"}</strong></div>
    </div>
    <div class="migration-progress-panel">
      <div class="migration-progress-head">
        <span>${escapeHtml(phase)}</span>
        <strong>${totalAssets > 0 ? escapeHtml(formatPercent(packagePercent)) : "—"}</strong>
      </div>
      ${renderProgressBar(completedAssets, totalAssets)}
      <div class="migration-progress-meta">
        <span>${escapeHtml(compactNumber(completedAssets))} processed</span>
        <span>${escapeHtml(compactNumber(pendingAssets))} pending</span>
        <span>${escapeHtml(compactNumber(failedAssets))} failed</span>
      </div>
    </div>
    ${renderMigrationProfile(sourceProfile, migrationPlan)}
    ${renderMigrationPlan(migrationPlan)}
    ${renderMigrationPlanItems(migrationPlan)}
    ${renderMigrationList("Plan manual actions", migrationPlan?.manualActions || [])}
    ${renderMigrationList("Plan warnings", migrationPlan?.warnings || [])}
    ${jobs.length ? `
      <div class="migration-list-title">Repository jobs</div>
      <table class="nx-table compact"><thead><tr><th>Repository</th><th>Format</th><th>Status</th><th>Total</th><th>Migrated</th><th>Pending</th><th>Failed</th><th>Progress</th><th>Cursor</th><th>Error</th></tr></thead><tbody>
        ${jobs.map((job) => `
          <tr>
            <td>${escapeHtml(job.sourceRepositoryName)}</td>
            <td>${escapeHtml(job.format)}</td>
            <td>${repositoryDataStatusBadge(job.status)}</td>
            <td>${escapeHtml(compactNumber(repositoryJobTotal(job)))}</td>
            <td>${escapeHtml(compactNumber(job.migratedAssets))}</td>
            <td>${escapeHtml(compactNumber(repositoryJobPending(job)))}</td>
            <td>${escapeHtml(compactNumber(job.failedAssets))}</td>
            <td class="repo-progress-cell">${renderCompactProgressBar(repositoryJobProcessed(job), repositoryJobTotal(job))}</td>
            <td><code title="${escapeHtml(job.cursorPath || "")}">${escapeHtml(shortText(job.cursorPath || "-"))}</code></td>
            <td>${job.lastError ? `<code title="${escapeHtml(job.lastError)}">${escapeHtml(shortText(job.lastError))}</code>` : '<span class="health-muted">-</span>'}</td>
          </tr>
        `).join("")}
      </tbody></table>
    ` : '<div class="health-muted">No repository jobs.</div>'}
  `;
}

function repositoryDataMigrationPhase(payload, jobs, totalAssets, completedAssets) {
  if (jobs.some((job) => String(job.status || "") === "discovering")) return "Syncing metadata";
  if (totalAssets > 0 && !payload.packageMigrationEnabled && completedAssets < totalAssets) {
    return "Ready for package sync";
  }
  if (totalAssets > 0 && payload.packageMigrationEnabled && completedAssets < totalAssets) {
    return "Syncing packages";
  }
  if (totalAssets > 0 && completedAssets >= totalAssets && numberOrZero(payload.failedAssets) > 0) {
    return "Completed with failures";
  }
  if (totalAssets > 0 && completedAssets >= totalAssets) return "Completed";
  return payload.active ? "Running" : "Idle";
}

function repositoryJobTotal(job) {
  const total = numberOrZero(job.totalAssets);
  return total > 0 ? total : numberOrZero(job.discoveredAssets);
}

function repositoryJobProcessed(job) {
  return numberOrZero(job.migratedAssets) + numberOrZero(job.failedAssets);
}

function repositoryJobPending(job) {
  if (job.pendingAssets != null) return numberOrZero(job.pendingAssets);
  return Math.max(0, repositoryJobTotal(job) - repositoryJobProcessed(job));
}

function renderProgressBar(done, total) {
  const percent = progressPercent(done, total);
  if (total <= 0) return '<div class="migration-progress-track is-unknown" role="progressbar" aria-label="Package progress"></div>';
  return `
    <div class="migration-progress-track" aria-label="Package progress" role="progressbar" aria-valuemin="0" aria-valuemax="100" aria-valuenow="${escapeHtml(percent.toFixed(1))}">
      <span style="width: ${escapeHtml(percent.toFixed(2))}%"></span>
    </div>
  `;
}

function renderCompactProgressBar(done, total) {
  const percent = progressPercent(done, total);
  if (total <= 0) return '<span class="health-muted">—</span>';
  return `
    <div class="repo-progress">
      <div class="repo-progress-track">${renderProgressFill(percent)}</div>
      <span>${escapeHtml(formatPercent(percent))}</span>
    </div>
  `;
}

function renderProgressFill(percent) {
  return `<i style="width: ${escapeHtml(percent.toFixed(2))}%"></i>`;
}

function renderRepositoryDataMigrationJobs(payload) {
  const latest = Array.isArray(payload) ? payload[0] : payload;
  if (latest) {
    renderRepositoryDataMigrationStatus(latest, "Latest repository data migration");
  } else {
    const result = document.getElementById("repository-data-migration-result");
    result.hidden = false;
    result.innerHTML = '<div class="health-muted">No repository data migration jobs.</div>';
  }
}

function repositoryDataStatusBadge(status) {
  const value = String(status || "");
  const tone = value === "finished" || value === "migrated" ? "ok"
    : value.includes("fail") ? "bad"
      : value === "ready" ? "warn" : "checking";
  return `<span class="state-badge compact ${tone}">${escapeHtml(value || "-")}</span>`;
}

function compactNumber(value) {
  const number = numberOrZero(value);
  return Number.isFinite(number) ? number.toLocaleString() : "0";
}

function numberOrZero(value) {
  const number = Number(value || 0);
  return Number.isFinite(number) ? number : 0;
}

function progressPercent(done, total) {
  const denominator = numberOrZero(total);
  if (denominator <= 0) return 0;
  return Math.max(0, Math.min(100, numberOrZero(done) * 100 / denominator));
}

function formatPercent(value) {
  const number = numberOrZero(value);
  if (number <= 0) return "0%";
  if (number >= 100) return "100%";
  return `${number.toFixed(number < 10 ? 1 : 0)}%`;
}

function renderRepositoryDataMigrationError(title, message) {
  const result = document.getElementById("repository-data-migration-result");
  result.hidden = false;
  result.innerHTML = `
    <div class="form-title">${escapeHtml(title)}</div>
    <div class="migration-error-panel">
      <div class="migration-list-title">Error</div>
      <pre class="code-panel">${escapeHtml(message || "Repository data migration request failed.")}</pre>
    </div>
  `;
}

async function startRepositoryDataMetadataMigration() {
  if (!validateRequiredFields(repositoryDataMigrationRequiredFields)) return;
  try {
    showToast("Syncing repository metadata...");
    const response = await fetch("/internal/migration/nexus/repository-data/start", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(repositoryDataMigrationPayload())
    });
    if (!response.ok) throw new Error(await responseErrorMessage(response));
    const payload = await response.json();
    renderRepositoryDataMigrationStatus(payload, "Repository metadata sync started");
    startRepositoryDataMigrationPolling(payload.jobId);
    showToast("Repository metadata sync started.", "ok");
  } catch (error) {
    renderRepositoryDataMigrationError("Metadata sync failed", error.message);
    showToast(`Metadata sync failed: ${error.message}`, "error");
  }
}

async function continueRepositoryDataMetadataMigration() {
  if (!repositoryDataMigrationJobId) {
    await loadRepositoryDataMigrationJobs();
  }
  if (!repositoryDataMigrationJobId) {
    showToast("No repository data migration job selected.", "error");
    return;
  }
  await triggerRepositoryDataWorker(
      `/internal/migration/nexus/repository-data/jobs/${repositoryDataMigrationJobId}/metadata/start`,
      "Metadata worker triggered");
}

async function startRepositoryDataPackageMigration() {
  if (!repositoryDataMigrationJobId) {
    await loadRepositoryDataMigrationJobs();
  }
  if (!repositoryDataMigrationJobId) {
    showToast("No repository data migration job selected.", "error");
    return;
  }
  await triggerRepositoryDataWorker(
      `/internal/migration/nexus/repository-data/jobs/${repositoryDataMigrationJobId}/packages/start`,
      "Package sync triggered");
}

async function retryRepositoryDataFailedPackages() {
  if (!repositoryDataMigrationJobId) {
    await loadRepositoryDataMigrationJobs();
  }
  if (!repositoryDataMigrationJobId) {
    showToast("No repository data migration job selected.", "error");
    return;
  }
  await triggerRepositoryDataWorker(
      `/internal/migration/nexus/repository-data/jobs/${repositoryDataMigrationJobId}/packages/retry-failed`,
      "Failed package retry triggered");
}

async function triggerRepositoryDataWorker(url, toastMessage) {
  try {
    const response = await fetch(url, { method: "POST" });
    if (!response.ok) throw new Error(await responseErrorMessage(response));
    const payload = await response.json();
    renderRepositoryDataMigrationStatus(payload, toastMessage);
    startRepositoryDataMigrationPolling(payload.jobId);
    showToast(toastMessage, "ok");
  } catch (error) {
    renderRepositoryDataMigrationError("Worker trigger failed", error.message);
    showToast(`Worker trigger failed: ${error.message}`, "error");
  }
}

async function loadRepositoryDataMigrationJobs() {
  const visit = activeVisit;
  try {
    const response = await fetch("/internal/migration/nexus/repository-data/jobs");
    if (!response.ok) throw new Error(await responseErrorMessage(response));
    const jobs = await response.json();
    if (viewActive && visit === activeVisit) renderRepositoryDataMigrationJobs(jobs);
  } catch (error) {
    if (!viewActive || visit !== activeVisit) return;
    renderRepositoryDataMigrationError("Load jobs failed", error.message);
    showToast(`Load jobs failed: ${error.message}`, "error");
  }
}

async function loadRepositoryDataMigrationStatus(jobId = repositoryDataMigrationJobId) {
  if (!jobId || !viewActive) return;
  const visit = activeVisit;
  try {
    const response = await fetch(`/internal/migration/nexus/repository-data/jobs/${jobId}`);
    if (!response.ok) throw new Error(await responseErrorMessage(response));
    const payload = await response.json();
    if (viewActive && visit === activeVisit && jobId === repositoryDataMigrationJobId) renderRepositoryDataMigrationStatus(payload);
  } catch (error) {
    if (!viewActive || visit !== activeVisit || jobId !== repositoryDataMigrationJobId) return;
    renderRepositoryDataMigrationError("Load status failed", error.message);
  }
}

function startRepositoryDataMigrationPolling(jobId) {
  repositoryDataMigrationJobId = jobId || repositoryDataMigrationJobId;
  clearInterval(repositoryDataMigrationPollTimer);
  if (!repositoryDataMigrationJobId || !viewActive) return;
  repositoryDataMigrationPollTimer = setInterval(
      () => loadRepositoryDataMigrationStatus(repositoryDataMigrationJobId),
      3000);
}

async function enterRepositoryDataMigration() {
  viewActive = true;
  const visit = ++activeVisit;
  await loadRepositoryDataMigrationJobs();
  if (viewActive && visit === activeVisit) startRepositoryDataMigrationPolling(repositoryDataMigrationJobId);
}

function leaveRepositoryDataMigration() {
  viewActive = false;
  ++activeVisit;
  clearInterval(repositoryDataMigrationPollTimer);
  repositoryDataMigrationPollTimer = null;
}

export {
  enterRepositoryDataMigration,
  leaveRepositoryDataMigration,
  continueRepositoryDataMetadataMigration,
  loadRepositoryDataMigrationJobs,
  repositoryDataMigrationRequiredFields,
  retryRepositoryDataFailedPackages,
  startRepositoryDataMetadataMigration,
  startRepositoryDataPackageMigration,
};
