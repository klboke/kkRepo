import { escapeHtml, formatDateTime } from "../controls/display.js";
import { showToast } from "../controls/toast.js";
import { securityScanFindingArtifactBrowseUrl } from "./findings.js";
import {
  loadSecurityScanning,
  securityScanCanMutate,
  securityScanTone,
  selectSecurityScanTab,
} from "./overview.js";
import { securityScanState } from "./pages.js";
import { responseErrorMessage } from "../shell/http.js";

function renderSecurityScanTasks() {
  document.getElementById("security-scan-task-table").innerHTML =
    securityScanState.tasks.map((task) => {
      const retry = task.canManage === true && ["FAILED", "CANCELLED"].includes(task.status)
        ? `<button class="row-action security-scan-task-retry" data-id="${task.id}" type="button">retry</button>`
        : "";
      const cancel = task.canManage === true && ["PENDING", "RETRY_WAIT", "RUNNING"].includes(task.status)
        ? `<button class="row-action security-scan-task-cancel" data-id="${task.id}" type="button">cancel</button>`
        : "";
      const rescan = task.canRescan === true && task.assetId
        ? `<button class="row-action security-scan-asset-rescan" data-id="${task.assetId}" type="button">rescan</button>`
        : "";
      return `
        <tr>
          <td>${escapeHtml(task.id)}</td>
          <td>${escapeHtml(task.repository || `#${task.repositoryId}`)}</td>
          <td>${renderSecurityScanTaskAsset(task)}</td>
          <td>${escapeHtml(task.stage)}</td>
          <td>${escapeHtml(task.reason)}</td>
          <td><span class="state-badge compact ${securityScanTone(task.status)}">${escapeHtml(task.status)}</span></td>
          <td>${escapeHtml(`${task.attempts}/${task.maxAttempts}`)}</td>
          <td>${escapeHtml(formatDateTime(task.leaseUntil))}</td>
          <td>${escapeHtml(formatDateTime(task.finishedAt))}</td>
          <td title="${escapeHtml(task.lastErrorSummary || "")}">${escapeHtml(task.lastErrorCode || "-")}</td>
          <td class="actions-column">${retry}${cancel}${rescan}</td>
        </tr>`;
    }).join("")
      || '<tr><td colspan="11" class="placeholder">No scan tasks are visible.</td></tr>';
}

function renderSecurityScanTaskAsset(task) {
  if (task.assetId == null) return '<span class="security-scan-task-asset">Asset unavailable</span>';
  const path = task.browsePath || task.assetPath;
  const identity = `<small>Asset ID: ${escapeHtml(task.assetId)}</small>`;
  if (!path) {
    return `<span class="security-scan-task-asset">${identity}<span>Asset unavailable</span></span>`;
  }
  const label = task.browseRepository
    ? `<a href="${escapeHtml(securityScanFindingArtifactBrowseUrl({
        repository: task.browseRepository,
        sourceRepository: task.repository,
        browsePath: path
      }))}" title="${escapeHtml(`Open ${task.repository}: ${path} in Repository Browser`)}">${escapeHtml(path)}</a>`
    : `<span>${escapeHtml(path)}</span>`;
  return `<span class="security-scan-task-asset">${label}${identity}</span>`;
}

async function securityScanTaskAction(kind, id) {
  const task = securityScanState.tasks.find((item) => String(item.id) === String(id));
  if (!securityScanCanMutate() || task?.canManage !== true) return;
  try {
    const response = await fetch(`/internal/security/scanning/tasks/${id}/${kind}`, { method: "POST" });
    if (!response.ok) throw new Error(await responseErrorMessage(response));
    showToast(`Scan task ${kind} accepted.`, "ok");
    await loadSecurityScanning();
    selectSecurityScanTab("tasks");
  } catch (error) {
    showToast(`Task action failed: ${error.message}`, "error");
  }
}

async function securityScanRescan(assetId) {
  if (!securityScanCanMutate() || !securityScanState.tasks.some((task) =>
      String(task.assetId) === String(assetId) && task.canRescan === true)) return;
  try {
    const response = await fetch(`/internal/security/scanning/assets/${assetId}/rescan`, { method: "POST" });
    if (!response.ok) throw new Error(await responseErrorMessage(response));
    showToast("Artifact rescan queued.", "ok");
    await loadSecurityScanning();
    selectSecurityScanTab("tasks");
  } catch (error) {
    showToast(`Rescan failed: ${error.message}`, "error");
  }
}

export {
  renderSecurityScanTasks,
  securityScanRescan,
  securityScanTaskAction,
};
