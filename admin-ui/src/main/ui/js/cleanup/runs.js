import { CLEANUP_RUN_DEFAULT_PAGE_SIZE, cleanupRunPage, runState } from "./run-state.js";
import {
  cleanupPolicyView,
  loadCleanupPolicies,
  selectCleanupTab,
} from "./policies.js";
import { escapeHtml, formatDateTime } from "../controls/display.js";
import { closeFormModal, openFormModal } from "../controls/modal.js";
import { showToast } from "../controls/toast.js";
import { repositoryFormatLabel, repositoryTypeLabel } from "../repositories/recipes.js";
import { responseErrorMessage } from "../shell/http.js";

function cleanupRunTerminal(state) {
  return ["SUCCEEDED", "SUCCEEDED_TRUNCATED", "PARTIAL_LIMIT_REACHED", "PARTIAL", "FAILED", "CANCELLED"].includes(String(state || ""));
}

function cleanupRunProgressLabel(run) {
  const outcome = run.mode === "TRY_RUN"
    ? `${run.wouldDeleteSubjects ?? 0} would delete`
    : `${run.deletedSubjects ?? 0} deleted`;
  return `${run.scannedSubjects ?? 0} scanned · ${outcome}`;
}

function updateCleanupRunInList(run) {
  if (!run?.id) return;
  const index = runState.cleanupRuns.findIndex((item) => String(item.id) === String(run.id));
  if (index >= 0) {
    runState.cleanupRuns[index] = run;
  } else if (cleanupRunPage.page === 0) {
    const hadMore = cleanupRunPage.nextBefore != null;
    runState.cleanupRuns.push(run);
    runState.cleanupRuns = runState.cleanupRuns
      .sort((left, right) => Number(right.id) - Number(left.id));
    const overflowed = runState.cleanupRuns.length > cleanupRunPage.size;
    runState.cleanupRuns = runState.cleanupRuns.slice(0, cleanupRunPage.size);
    cleanupRunPage.nextBefore = overflowed || hadMore
      ? runState.cleanupRuns.at(-1)?.id ?? null
      : null;
  }
  renderCleanupRuns();
}

function renderCleanupRunPagination() {
  document.getElementById("cleanup-run-page-summary").textContent =
    runState.cleanupRuns.length === 1
      ? "1 result on this page"
      : `${runState.cleanupRuns.length} results on this page`;
  document.getElementById("cleanup-run-page-label").textContent =
    `Page ${cleanupRunPage.page + 1}`;
  document.getElementById("cleanup-run-page-prev").disabled = cleanupRunPage.page === 0;
  document.getElementById("cleanup-run-page-next").disabled =
    cleanupRunPage.nextBefore == null;
  document.getElementById("cleanup-run-page-size").value = String(cleanupRunPage.size);
}

function renderCleanupRuns() {
  const body = document.getElementById("cleanup-run-table");
  renderCleanupRunPagination();
  if (!runState.cleanupRuns.length) {
    body.innerHTML = '<tr><td colspan="7" class="empty-cell">No cleanup runs yet.</td></tr>';
    return;
  }
  body.innerHTML = runState.cleanupRuns.map((run) => {
    const policy = cleanupPolicyView(run.policyId)?.policy;
    const terminal = cleanupRunTerminal(run.state);
    return `
      <tr>
        <td><strong>#${escapeHtml(run.id)}</strong><br><span class="muted">${escapeHtml(formatDateTime(run.createdAt))}</span></td>
        <td>${escapeHtml(policy?.name || `#${run.policyId}`)}</td>
        <td>${escapeHtml(run.mode)}</td>
        <td>${escapeHtml(run.triggerKind)}</td>
        <td><span class="state-badge compact ${run.state === "FAILED" ? "bad" : terminal ? "ok" : "warn"}">${escapeHtml(run.state)}</span></td>
        <td>${escapeHtml(cleanupRunProgressLabel(run))}</td>
        <td><div class="cleanup-policy-actions">
          <button class="row-action cleanup-run-view" data-id="${escapeHtml(run.id)}" type="button">View</button>
          ${terminal ? "" : `<button class="row-action cleanup-run-cancel" data-id="${escapeHtml(run.id)}" type="button">Cancel</button>`}
        </div></td>
      </tr>`;
  }).join("");
}

function resetCleanupRunPage() {
  cleanupRunPage.before = 0;
  cleanupRunPage.cursors = [0];
  cleanupRunPage.page = 0;
  cleanupRunPage.nextBefore = null;
}

async function fetchCleanupRunPage() {
  const response = await fetch(
    `/internal/cleanup/runs?before=${encodeURIComponent(cleanupRunPage.before)}&limit=${cleanupRunPage.size}`);
  if (!response.ok) throw new Error(await responseErrorMessage(response));
  return response.json();
}

function applyCleanupRunPage(page) {
  runState.cleanupRuns = page.items || [];
  cleanupRunPage.nextBefore = page.nextBefore ?? null;
}

async function loadCleanupRuns() {
  try {
    const page = await fetchCleanupRunPage();
    applyCleanupRunPage(page);
    if (runState.cleanupRuns.length === 0 && cleanupRunPage.page > 0) {
      cleanupRunPage.cursors.pop();
      cleanupRunPage.page -= 1;
      cleanupRunPage.before = cleanupRunPage.cursors.at(-1) || 0;
      return loadCleanupRuns();
    }
    renderCleanupRuns();
  } catch (error) {
    showToast(`Cleanup runs failed to load: ${error.message}`, "error");
  }
}

async function changeCleanupRunPage(direction) {
  if (direction === "next") {
    if (cleanupRunPage.nextBefore == null) return;
    cleanupRunPage.before = cleanupRunPage.nextBefore;
    cleanupRunPage.cursors.push(cleanupRunPage.before);
    cleanupRunPage.page += 1;
  } else {
    if (cleanupRunPage.page === 0) return;
    cleanupRunPage.cursors.pop();
    cleanupRunPage.page -= 1;
    cleanupRunPage.before = cleanupRunPage.cursors.at(-1) || 0;
  }
  await loadCleanupRuns();
}

async function resizeCleanupRunPage(size) {
  cleanupRunPage.size = Number(size) || CLEANUP_RUN_DEFAULT_PAGE_SIZE;
  resetCleanupRunPage();
  await loadCleanupRuns();
}

function setCleanupTryRunError(message = "") {
  const input = document.getElementById("cleanup-try-run-scan-limit");
  const error = document.getElementById("cleanup-try-run-scan-limit-error");
  input.classList.toggle("is-invalid", Boolean(message));
  input.setAttribute("aria-invalid", String(Boolean(message)));
  error.textContent = message;
  error.hidden = !message;
}

function showCleanupTryRunDialog(policyId, trigger = null) {
  const view = cleanupPolicyView(policyId);
  if (!view) {
    showToast("Cleanup policy no longer exists. Refresh and try again.", "error");
    return;
  }
  runState.cleanupTryRunPolicyId = Number(policyId);
  runState.cleanupTryRunTrigger = trigger;
  runState.cleanupTryRunSubmitting = false;
  document.getElementById("cleanup-try-run-policy-name").textContent = view.policy.name;
  document.getElementById("cleanup-try-run-repository-count").textContent = String((view.repositories || []).length);
  document.getElementById("cleanup-try-run-scan-limit").value = String(view.policy.scanLimitPerRepository || 1000);
  setCleanupTryRunError();
  openFormModal("cleanup-try-run-form", "cleanup-try-run-scan-limit");
}

function hideCleanupTryRunDialog(options = {}) {
  if (runState.cleanupTryRunSubmitting && options.force !== true) return;
  const restoreFocus = options.restoreFocus !== false;
  const trigger = runState.cleanupTryRunTrigger;
  runState.cleanupTryRunPolicyId = null;
  runState.cleanupTryRunTrigger = null;
  setCleanupTryRunError();
  closeFormModal("cleanup-try-run-form");
  if (restoreFocus && trigger?.isConnected) trigger.focus();
}

async function queueCleanupRun(policyId, mode, scanLimit = null) {
  const view = cleanupPolicyView(policyId);
  if (!view) {
    showToast("Cleanup policy no longer exists. Refresh and try again.", "error");
    return false;
  }
  const isTryRun = mode === "TRY_RUN";
  try {
    const response = await fetch(`/internal/cleanup/policies/${policyId}/runs`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({
        mode,
        expectedPolicyRevision: view.policy.revision,
        scanLimitPerRepository: scanLimit
      })
    });
    if (!response.ok) throw new Error(await responseErrorMessage(response));
    const run = await response.json();
    resetCleanupRunPage();
    runState.cleanupRuns = run.run ? [run.run] : [];
    selectCleanupTab("runs");
    renderCleanupRuns();
    await loadCleanupRuns();
    showToast(`${isTryRun ? "Try Run" : "Cleanup execution"} queued as run #${run.run?.id}.`, "ok");
    monitorCleanupRun(run.run.id, isTryRun ? "Try Run" : "Cleanup execution");
    return true;
  } catch (error) {
    showToast(`${isTryRun ? "Try Run" : "Cleanup execution"} failed: ${error.message}`, "error");
    return false;
  }
}

async function submitCleanupTryRun(event) {
  event.preventDefault();
  const input = document.getElementById("cleanup-try-run-scan-limit");
  const scanLimit = Number(input.value);
  if (!Number.isInteger(scanLimit) || scanLimit < 1 || scanLimit > 10000) {
    setCleanupTryRunError("Enter a whole number between 1 and 10,000.");
    input.focus();
    return;
  }
  const policyId = runState.cleanupTryRunPolicyId;
  if (policyId == null) return;
  const button = document.getElementById("start-cleanup-try-run-button");
  const cancelButton = document.getElementById("cancel-cleanup-try-run-button");
  const closeButton = document.querySelector("#cleanup-try-run-form-modal .form-modal-close");
  runState.cleanupTryRunSubmitting = true;
  button.disabled = true;
  cancelButton.disabled = true;
  closeButton.disabled = true;
  button.textContent = "Starting…";
  try {
    const queued = await queueCleanupRun(policyId, "TRY_RUN", scanLimit);
    if (queued) hideCleanupTryRunDialog({ restoreFocus: false, force: true });
  } finally {
    runState.cleanupTryRunSubmitting = false;
    button.disabled = false;
    cancelButton.disabled = false;
    closeButton.disabled = false;
    button.textContent = "Start Try Run";
  }
}

async function startCleanupRun(policyId, mode, trigger = null) {
  const view = cleanupPolicyView(policyId);
  if (!view) return;
  if (mode === "TRY_RUN") {
    showCleanupTryRunDialog(policyId, trigger);
    return;
  }
  if (!await window.kkrepoConfirmDelete({
    title: "Run cleanup policy?",
    description: "Artifacts matching this policy may be permanently deleted. Review a Try Run before continuing.",
    details: [{ label: "Policy", value: view.policy.name }],
    confirmationText: view.policy.name,
    confirmLabel: "Run cleanup"
  })) return;
  await queueCleanupRun(policyId, mode);
}

async function monitorCleanupRun(runId, label) {
  const token = ++runState.cleanupRunPollToken;
  while (token === runState.cleanupRunPollToken) {
    await new Promise((resolve) => window.setTimeout(resolve, 1000));
    const response = await fetch(`/internal/cleanup/runs/${runId}/summary`);
    if (!response.ok) {
      showToast(`${label} status refresh failed: ${await responseErrorMessage(response)}`, "error");
      return;
    }
    const run = await response.json();
    updateCleanupRunInList(run);
    if (String(runState.activeCleanupRunDetailId) === String(runId)) {
      if (cleanupRunTerminal(run?.state)) {
        const detail = await loadCleanupRunDetails(runId);
        if (detail) await renderCleanupRun(detail, { reloadItems: true });
      } else if (runState.cleanupRunDetailView) {
        await renderCleanupRun({ ...runState.cleanupRunDetailView, run });
      }
    }
    if (cleanupRunTerminal(run?.state)) {
      const succeeded = String(run.state).startsWith("SUCCEEDED");
      showToast(`${label} finished with state ${run.state}.`, succeeded ? "ok" : "error");
      await loadCleanupPolicies();
      return;
    }
  }
}

function showCleanupRunDetail(runId, trigger = null) {
  runState.activeCleanupRunDetailId = String(runId);
  runState.cleanupRunDetailTrigger = trigger;
  runState.cleanupRunDetailItemGroups = [];
  runState.cleanupRunDetailItemsLoaded = false;
  runState.cleanupRunDetailView = null;
  document.getElementById("cleanup-run-detail-title").textContent = `Cleanup run #${runId}`;
  document.getElementById("cleanup-run-detail-content").innerHTML = '<p class="form-note">Loading run details…</p>';
  openFormModal("cleanup-run-detail");
  document.getElementById("cleanup-run-detail").scrollTop = 0;
}

function hideCleanupRunDetail(options = {}) {
  const trigger = runState.cleanupRunDetailTrigger;
  runState.activeCleanupRunDetailId = null;
  runState.cleanupRunDetailTrigger = null;
  runState.cleanupRunDetailItemGroups = [];
  runState.cleanupRunDetailItemsLoaded = false;
  runState.cleanupRunDetailView = null;
  runState.cleanupRunDetailLoadSequence++;
  closeFormModal("cleanup-run-detail");
  if (options.restoreFocus !== false && trigger?.isConnected) trigger.focus();
}

async function viewCleanupRun(runId, trigger = null) {
  showCleanupRunDetail(runId, trigger);
  const sequence = ++runState.cleanupRunDetailLoadSequence;
  try {
    const view = await loadCleanupRunDetails(runId);
    if (!view) throw new Error("Cleanup run details are unavailable");
    if (sequence !== runState.cleanupRunDetailLoadSequence
        || String(runState.activeCleanupRunDetailId) !== String(runId)) return;
    updateCleanupRunInList(view.run);
    await renderCleanupRun(view, { reloadItems: true });
    if (!cleanupRunTerminal(view.run?.state)) monitorCleanupRun(runId, "Cleanup run");
  } catch (error) {
    if (sequence === runState.cleanupRunDetailLoadSequence
        && String(runState.activeCleanupRunDetailId) === String(runId)) {
      document.getElementById("cleanup-run-detail-content").innerHTML = `<p class="form-note"><strong>Run details could not be loaded.</strong> ${escapeHtml(error.message)}</p>`;
    }
    showToast(`Cleanup run failed to load: ${error.message}`, "error");
  }
}

async function cancelCleanupRun(runId) {
  if (!window.confirm(`Cancel cleanup run #${runId}? A deletion already committed by a worker remains committed.`)) return;
  try {
    const response = await fetch(`/internal/cleanup/runs/${runId}/cancel`, { method: "POST" });
    if (!response.ok) throw new Error(await responseErrorMessage(response));
    const view = await response.json();
    updateCleanupRunInList(view.run);
    if (String(runState.activeCleanupRunDetailId) === String(runId)) {
      const detail = await loadCleanupRunDetails(runId);
      if (detail) await renderCleanupRun(detail, { reloadItems: true });
    }
    showToast(`Cancellation requested for cleanup run #${runId}.`, "ok");
    if (!cleanupRunTerminal(view.run?.state)) monitorCleanupRun(runId, "Cleanup run");
    await loadCleanupPolicies();
  } catch (error) {
    showToast(`Cleanup run cancellation failed: ${error.message}`, "error");
  }
}

async function loadCleanupRunDetails(runId) {
  const response = await fetch(
    `/internal/cleanup/runs/${runId}/details?itemsPerRepository=50`);
  if (!response.ok) throw new Error(await responseErrorMessage(response));
  return response.json();
}

function cleanupRunRuleSnapshot(criteria = {}) {
  const snapshot = criteria.mode === "MAVEN_SNAPSHOT_BUILDS";
  criteria = snapshot ? (criteria.snapshot || {}) : criteria;
  const pattern = criteria.pattern
    ? `${criteria.patternType === "REGEX" ? "Regular expression" : "Wildcard"} · ${criteria.pattern}`
    : "All names and paths";
  return `
    <dl class="cleanup-run-rule-grid">
      <div><dt>Name/path pattern</dt><dd>${escapeHtml(pattern)}</dd></div>
      <div><dt>Published age</dt><dd>${criteria.publishedOlderThanDays == null ? "Not configured" : `Older than ${escapeHtml(criteria.publishedOlderThanDays)} days`}</dd></div>
      <div><dt>Download activity</dt><dd>${criteria.lastDownloadedOlderThanDays == null ? "Not configured" : `Last downloaded more than ${escapeHtml(criteria.lastDownloadedOlderThanDays)} days ago`}</dd></div>
      <div><dt>Version retention</dt><dd>${criteria.retainCount == null ? "Not configured" : `Keep newest ${escapeHtml(criteria.retainCount)} ${snapshot ? "builds per SNAPSHOT" : "versions per family"}`}</dd></div>
    </dl>`;
}

function cleanupRunRepositorySnapshot(run, repositories) {
  const snapshot = Array.isArray(run.repositorySnapshot) && run.repositorySnapshot.length > 0
    ? run.repositorySnapshot
    : repositories.map((repository) => ({
        name: repository.repositoryName,
        format: repository.format,
        type: repository.repositoryType
      }));
  if (snapshot.length === 0) return '<span class="muted">No repository snapshot.</span>';
  return `<div class="cleanup-run-repository-list">${snapshot.map((repository) => `
    <span class="cleanup-run-repository-chip">
      <strong>${escapeHtml(repository.name || `#${repository.id}`)}</strong>
      <span>${escapeHtml(repositoryFormatLabel(repository.format))} · ${escapeHtml(repositoryTypeLabel(repository.type))}</span>
    </span>`).join("")}</div>`;
}

async function renderCleanupRun(view, options = {}) {
  const run = view.run;
  if (!run || String(runState.activeCleanupRunDetailId) !== String(run.id)) return;
  const renderSequence = ++runState.cleanupRunDetailLoadSequence;
  const repositories = view.repositories || [];
  if (options.reloadItems || !runState.cleanupRunDetailItemsLoaded) {
    const itemsByRepository = view.itemsByRepository || {};
    runState.cleanupRunDetailItemGroups = repositories.map((repository) => ({
      repository,
      items: itemsByRepository[String(repository.id)] || itemsByRepository[repository.id] || [],
      loadError: null
    }));
    runState.cleanupRunDetailItemsLoaded = true;
  }
  runState.cleanupRunDetailView = view;
  if (renderSequence !== runState.cleanupRunDetailLoadSequence
      || String(runState.activeCleanupRunDetailId) !== String(run.id)) return;

  const itemRows = runState.cleanupRunDetailItemGroups.flatMap(({ repository, items, loadError }) => {
    if (items.length === 0) {
      const operational = [
        loadError ? `Items could not be loaded: ${loadError}` : null,
        repository.errorSummary,
        repository.attemptCount ? `attempt ${repository.attemptCount}/${repository.maxAttempts}` : null,
        repository.leaseUntil ? `lease until ${formatDateTime(repository.leaseUntil)}` : null
      ].filter(Boolean).join(" · ");
      return [`
        <tr>
          <td>${escapeHtml(repository.repositoryName)}</td>
          <td>-</td>
          <td>-</td>
          <td><span class="state-badge compact ${repository.state === "FAILED" ? "bad" : cleanupRunTerminal(repository.state) ? "ok" : "warn"}">${escapeHtml(repository.state)}</span></td>
          <td>${escapeHtml(operational || "No cleanup decisions recorded.")}</td>
        </tr>`];
    }
    return items.map((item) => `
      <tr>
        <td>${escapeHtml(repository.repositoryName)}</td>
        <td>${escapeHtml(item.displayName)}</td>
        <td>${escapeHtml(item.version || "-")}</td>
        <td><span class="state-badge compact ${item.decision === "DELETED" ? "ok" : item.decision === "FAILED" ? "bad" : "warn"}">${escapeHtml(item.decision)}</span></td>
        <td>${escapeHtml(item.errorSummary || JSON.stringify(item.reason || {}))}</td>
      </tr>`);
  });
  const policy = cleanupPolicyView(run.policyId)?.policy;
  const policyName = policy?.name || `Policy #${run.policyId}`;
  const tryRun = run.mode === "TRY_RUN";
  const outcomeLabel = tryRun ? "Would delete" : "Deleted";
  const outcomeValue = tryRun ? run.wouldDeleteSubjects : run.deletedSubjects;
  const stateTone = run.state === "FAILED" ? "bad" : cleanupRunTerminal(run.state) ? "ok" : "warn";
  document.getElementById("cleanup-run-detail-title").textContent = `Cleanup run #${run.id}`;
  document.getElementById("cleanup-run-detail-content").innerHTML = `
    <div class="cleanup-run-detail-heading">
      <div class="cleanup-run-policy-identity">
        <strong>${escapeHtml(policyName)}</strong>
        <span>Policy revision ${escapeHtml(run.policyRevision)}</span>
      </div>
      ${cleanupRunTerminal(run.state) ? "" : `<button class="row-action cleanup-run-cancel" data-id="${escapeHtml(run.id)}" type="button">Cancel run</button>`}
    </div>

    <section class="cleanup-run-detail-section" aria-labelledby="cleanup-run-context-title">
      <h3 id="cleanup-run-context-title">Run context</h3>
      <dl class="cleanup-run-context-grid">
        <div><dt>Mode</dt><dd>${escapeHtml(run.mode)}</dd></div>
        <div><dt>Trigger</dt><dd>${escapeHtml(run.triggerKind)}</dd></div>
        <div><dt>Status</dt><dd><span class="state-badge compact ${stateTone}">${escapeHtml(run.state)}</span></dd></div>
        <div><dt>Requested by</dt><dd>${escapeHtml(run.requestedBy || "-")}</dd></div>
        <div><dt>Created</dt><dd>${escapeHtml(formatDateTime(run.createdAt))}</dd></div>
        <div><dt>Started</dt><dd>${escapeHtml(formatDateTime(run.startedAt))}</dd></div>
        <div><dt>Completed</dt><dd>${escapeHtml(formatDateTime(run.completedAt))}</dd></div>
        <div><dt>Scheduled for</dt><dd>${escapeHtml(formatDateTime(run.scheduledFor))}</dd></div>
      </dl>
    </section>

    <section class="cleanup-run-detail-section" aria-labelledby="cleanup-run-policy-title">
      <div class="cleanup-run-section-heading">
        <h3 id="cleanup-run-policy-title">Deletion policy <span class="cleanup-run-heading-note">Snapshot used by this run</span></h3>
      </div>
      ${cleanupRunRuleSnapshot(run.criteriaSnapshot || {})}
      <dl class="cleanup-run-limit-grid">
        <div><dt>Scan limit / repository</dt><dd>${escapeHtml(run.scanLimitPerRepository)}</dd></div>
        <div><dt>Delete limit / repository</dt><dd>${tryRun ? "Not applied in Try Run" : escapeHtml(run.deleteLimitPerRepository)}</dd></div>
      </dl>
      <div class="cleanup-run-repository-snapshot">
        <strong>Target repositories</strong>
        ${cleanupRunRepositorySnapshot(run, repositories)}
      </div>
    </section>

    <section class="cleanup-run-detail-section" aria-labelledby="cleanup-run-summary-title">
      <h3 id="cleanup-run-summary-title">Run summary</h3>
      ${run.truncatedRepositories > 0 ? '<p class="form-note cleanup-run-notice"><strong>Scan was truncated.</strong> Results cover only the bounded scan range; incomplete version families were excluded.</p>' : ""}
      ${run.errorSummary ? `<p class="form-note cleanup-run-notice"><strong>Run errors:</strong> ${escapeHtml(run.errorSummary)}</p>` : ""}
      <div class="cleanup-run-summary">
        <div><strong>${escapeHtml(run.scannedSubjects ?? 0)}</strong><span>Scanned</span></div>
        <div><strong>${escapeHtml(run.matchedSubjects ?? 0)}</strong><span>Matched</span></div>
        <div><strong>${escapeHtml(outcomeValue ?? 0)}</strong><span>${outcomeLabel}</span></div>
        <div><strong>${escapeHtml(run.failedSubjects ?? 0)}</strong><span>Failed</span></div>
      </div>
    </section>

    <section class="cleanup-run-detail-section cleanup-run-decisions" aria-labelledby="cleanup-run-decisions-title">
      <div class="cleanup-run-section-heading">
        <h3 id="cleanup-run-decisions-title">Decision details <span class="cleanup-run-heading-note">Showing up to 50 decisions per repository</span></h3>
      </div>
      <div class="nx-table-frame cleanup-run-detail-table-frame"><table class="nx-table compact migration-detail-table"><thead><tr><th>Repository</th><th>Subject</th><th>Version</th><th>Decision</th><th>Reason</th></tr></thead><tbody>${itemRows.join("") || '<tr><td colspan="5" class="empty-cell">No cleanup decisions in the bounded scan.</td></tr>'}</tbody></table></div>
    </section>`;
}

export {
  applyCleanupRunPage,
  cancelCleanupRun,
  changeCleanupRunPage,
  fetchCleanupRunPage,
  hideCleanupRunDetail,
  hideCleanupTryRunDialog,
  renderCleanupRuns,
  resizeCleanupRunPage,
  setCleanupTryRunError,
  startCleanupRun,
  submitCleanupTryRun,
  viewCleanupRun,
};
