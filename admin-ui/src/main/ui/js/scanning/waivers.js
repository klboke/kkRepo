import { escapeHtml, formatDateTime } from "../controls/display.js";
import { clearRequiredFieldErrors, validateRequiredFields } from "../controls/fields.js";
import { closeFormModal, openFormModal } from "../controls/modal.js";
import { showToast } from "../controls/toast.js";
import { loadSecurityScanning, securityScanCanMutate, selectSecurityScanTab } from "./overview.js";
import { findSecurityScanFindingNode, securityScanPages, securityScanState } from "./pages.js";
import { responseErrorMessage } from "../shell/http.js";

let securityScanWaiverContext = null;

const securityScanWaiverRequiredFields = [
  { id: "security-scan-waiver-target", label: "Repository artifact" },
  { id: "security-scan-waiver-duration", label: "Expiration" },
  { id: "security-scan-waiver-reason", label: "Reason" }
];

const securityScanPackageWaiverRequiredFields = [
  { id: "security-scan-package-waiver-scope", label: "Scope" },
  { id: "security-scan-package-waiver-duration", label: "Expiration" },
  { id: "security-scan-package-waiver-reason", label: "Reason" }
];

function renderSecurityScanWaivers() {
  document.getElementById("security-scan-waiver-table").innerHTML =
    securityScanState.waivers.map((waiver) => {
      return `
        <tr><td><span class="state-badge compact ${waiver.active ? "ok" : "warn"}">${waiver.active ? "Active" : "Expired"}</span></td>
        <td>${escapeHtml(waiver.scopeType)}</td>
        <td>${escapeHtml(waiver.repository || "Global")}</td>
        <td title="${escapeHtml(waiver.assetPath || "")}">${escapeHtml(waiver.assetPath || "All artifacts")}</td>
        <td>${escapeHtml(waiver.exception || (waiver.findingId ? `Finding #${waiver.findingId}` : "-"))}</td>
        <td>${escapeHtml(waiver.expiresAt ? formatDateTime(waiver.expiresAt) : "Never expires")}</td>
        <td>${escapeHtml(waiver.approvedBy || "-")}</td><td>${escapeHtml(waiver.reason)}</td>
        <td class="actions-column">${waiver.canDelete === true ? `<button class="row-action security-scan-waiver-delete" data-id="${waiver.id}" type="button">delete</button>` : ""}</td></tr>`;
    }).join("")
      || '<tr><td colspan="9" class="placeholder">No waivers are visible.</td></tr>';
}

function securityScanWaiverTargetLabel(waiver) {
  if (waiver.repository && waiver.assetPath) {
    return `${waiver.repository} — ${waiver.assetPath}`;
  }
  if (waiver.repository) return `${waiver.repository} — all artifacts`;
  if (waiver.assetPath) return waiver.assetPath;
  return "All repositories and artifacts";
}

function securityScanWaiverPolicyLabel(waiver) {
  if (waiver.policyId == null) return "All policies";
  return waiver.policyRevision == null
    ? `Policy #${waiver.policyId}`
    : `Policy #${waiver.policyId}, revision ${waiver.policyRevision}`;
}

function renderSecurityScanWaiverDetail(detail) {
  const waivers = detail.waivers || [];
  const summary = document.getElementById("security-scan-waiver-detail-summary");
  summary.textContent =
    `${detail.advisoryId || "Finding"} · ${detail.packageName || detail.packageUrl || "Unknown package"} · `
    + `${detail.activeWaiverCount || 0} active, ${detail.expiredWaiverCount || 0} expired. `
    + "Each entry applies only to the repository artifact and policy scope shown below.";
  document.getElementById("security-scan-waiver-detail-list").innerHTML =
    waivers.map((waiver) => `
      <article class="security-scan-waiver-detail-card">
        <div class="security-scan-waiver-detail-head">
          <span class="state-badge compact ${waiver.active ? "ok" : "warn"}">${waiver.active ? "Active" : "Expired"}</span>
          <strong>Waiver #${escapeHtml(waiver.id)}</strong>
          <span class="security-scan-waiver-detail-target">${escapeHtml(securityScanWaiverTargetLabel(waiver))}</span>
        </div>
        <div class="security-scan-waiver-detail-grid">
          <div><span>Scope</span><strong>${escapeHtml(waiver.scopeType || "-")}</strong></div>
          <div><span>Policy</span><strong>${escapeHtml(securityScanWaiverPolicyLabel(waiver))}</strong></div>
          <div><span>Approved by</span><strong>${escapeHtml(waiver.approvedBy || "-")}</strong></div>
          <div><span>Created</span><strong>${escapeHtml(formatDateTime(waiver.createdAt))}</strong></div>
          <div><span>Expires</span><strong>${escapeHtml(waiver.expiresAt ? formatDateTime(waiver.expiresAt) : "Never expires")}</strong></div>
          <div class="security-scan-waiver-detail-selector"><span>Exception</span><strong>${escapeHtml(waiver.advisorySelector || waiver.packageSelector || "-")}</strong></div>
        </div>
        <div class="security-scan-waiver-detail-reason"><span>Reason</span><p>${escapeHtml(waiver.reason || "-")}</p></div>
      </article>`).join("")
      || '<div class="placeholder">No applicable waiver is currently visible.</div>';
}

async function showSecurityScanWaiverDetail(findingId) {
  const finding = securityScanState.findings.find(
    (item) => Number(item.id) === Number(findingId));
  document.getElementById("security-scan-waiver-detail-title").textContent =
    `Finding waiver details${finding?.advisoryId ? `: ${finding.advisoryId}` : ""}`;
  document.getElementById("security-scan-waiver-detail-summary").textContent =
    "Loading applicable waivers…";
  document.getElementById("security-scan-waiver-detail-list").innerHTML = "";
  openFormModal("security-scan-waiver-detail", "security-scan-close-waiver-detail-button");
  try {
    const response = await fetch(
      `/internal/security/scanning/findings/${encodeURIComponent(findingId)}/waivers?currentOnly=${securityScanPages.findings.currentOnly}`,
      { cache: "no-store" });
    if (!response.ok) throw new Error(await responseErrorMessage(response));
    renderSecurityScanWaiverDetail(await response.json());
  } catch (error) {
    document.getElementById("security-scan-waiver-detail-summary").textContent =
      `Unable to load waiver details: ${error.message}`;
    showToast(`Unable to load waiver details: ${error.message}`, "error");
  }
}

function hideSecurityScanWaiverDetail() {
  closeFormModal("security-scan-waiver-detail");
}

function viewAllSecurityScanWaivers() {
  hideSecurityScanWaiverDetail();
  selectSecurityScanTab("waivers");
}

async function showCreateSecurityScanWaiverForm(findingId) {
  const finding = securityScanState.findings.find((item) => String(item.id) === String(findingId));
  if (!securityScanCanMutate("nexus:security-scanning-waivers:create")
      || finding?.canCreateWaiver !== true) return;
  const form = document.getElementById("security-scan-waiver-form");
  form.reset();
  clearRequiredFieldErrors(securityScanWaiverRequiredFields);
  securityScanWaiverContext = null;
  try {
    const response = await fetch(
      `/internal/security/scanning/findings/${encodeURIComponent(findingId)}/waiver-context?currentOnly=${securityScanPages.findings.currentOnly}`,
      { cache: "no-store" });
    if (!response.ok) throw new Error(await responseErrorMessage(response));
    const context = await response.json();
    if (!Array.isArray(context.targets) || context.targets.length === 0) {
      if (Number(context.targetCount || 0) > 0
          && Number(context.waivedTargetCount || 0) >= Number(context.targetCount || 0)) {
        await loadSecurityScanning();
        showToast("This finding is already waived for all manageable repository artifacts.", "ok");
        return;
      }
      throw new Error("No repository artifact is available for this finding.");
    }
    securityScanWaiverContext = context;
    document.getElementById("security-scan-waiver-finding-id").value = context.findingId;
    document.getElementById("security-scan-waiver-finding").value =
      `${context.severity || "UNKNOWN"} · ${context.advisoryId || "Unknown advisory"}`;
    document.getElementById("security-scan-waiver-package").value =
      [context.packageName || context.packageUrl || "Unknown package", context.installedVersion]
        .filter(Boolean)
        .join(" @ ");
    document.getElementById("security-scan-waiver-target").innerHTML =
      context.targets.map((target, index) => `
        <option value="${index}">${escapeHtml(target.repository)} — ${escapeHtml(target.assetPath)}</option>`)
        .join("");
    document.getElementById("security-scan-waiver-duration").value = "604800";
    openFormModal(
      "security-scan-waiver-form",
      context.targets.length > 1 ? "security-scan-waiver-target" : "security-scan-waiver-reason");
  } catch (error) {
    showToast(`Unable to create waiver: ${error.message}`, "error");
  }
}

function hideSecurityScanWaiverForm() {
  clearRequiredFieldErrors(securityScanWaiverRequiredFields);
  securityScanWaiverContext = null;
  closeFormModal("security-scan-waiver-form");
}

async function createSecurityScanWaiver(event) {
  event.preventDefault();
  if (!securityScanCanMutate("nexus:security-scanning-waivers:create")) return;
  if (!validateRequiredFields(
      securityScanWaiverRequiredFields,
      { prefix: "Waiver fields missing" })) return;
  const targetIndex = Number(document.getElementById("security-scan-waiver-target").value);
  const target = securityScanWaiverContext?.targets?.[targetIndex];
  const durationValue = document.getElementById("security-scan-waiver-duration").value;
  const neverExpires = durationValue === "never";
  const durationSeconds = neverExpires ? null : Number(durationValue);
  if (!target
      || (!neverExpires && (!Number.isFinite(durationSeconds) || durationSeconds <= 0))) {
    showToast("Waiver target or expiration is no longer available.", "error");
    return;
  }
  const payload = {
    scopeType: "FINDING",
    repositoryId: target.repositoryId,
    assetId: target.assetId,
    findingId: securityScanWaiverContext.findingId,
    advisorySelector: null,
    packageSelector: null,
    selector: {},
    reason: document.getElementById("security-scan-waiver-reason").value.trim(),
    policyId: null,
    policyRevision: null,
    expiresAt: neverExpires
      ? null
      : new Date(Date.now() + durationSeconds * 1000).toISOString()
  };
  try {
    const response = await fetch("/internal/security/scanning/waivers", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(payload)
    });
    if (response.status === 409) {
      hideSecurityScanWaiverForm();
      await loadSecurityScanning();
      selectSecurityScanTab("findings");
      showToast(
        "Waiver state changed while this form was open. Findings and remaining targets were refreshed.",
        "ok");
      return;
    }
    if (!response.ok) throw new Error(await responseErrorMessage(response));
    hideSecurityScanWaiverForm();
    showToast("Security scan waiver created.", "ok");
    await loadSecurityScanning();
    selectSecurityScanTab("findings");
  } catch (error) {
    showToast(`Waiver creation failed: ${error.message}`, "error");
  }
}

// ---- Package waivers ------------------------------------------------------
// A package waiver selects the exact package URL of one package version within one repository
// or every repository, and replaces the narrower waivers for that package in the same scope.

// The package of the open dialog, and the server preview for the scope selected in it. The
// preview is null while a scope loads or after it failed, which blocks submission: a waiver is
// only ever created for a scope whose preview the operator has seen.
let securityScanPackageWaiverPackage = null;

let securityScanPackageWaiverContext = null;

let securityScanPackageWaiverRequest = 0;

function securityScanPackageWaiverScopeValue(context) {
  return context.repositoryId == null ? "all" : `repository:${context.repositoryId}`;
}

function securityScanPackageLabel(context) {
  return [context.packageName, context.installedVersion].filter(Boolean).join(" @ ");
}

function securityScanPackageWaiverScopeLabel(context) {
  if (context.repositoryId == null) return "all repositories";
  return context.repositories.find((repository) => repository.id === context.repositoryId)?.name
    || `repository #${context.repositoryId}`;
}

async function fetchSecurityScanPackageWaiverContext(packageName, installedVersion, scope = "") {
  const params = new URLSearchParams();
  params.set("packageName", packageName || "");
  params.set("installedVersion", installedVersion ?? "");
  if (scope === "all") params.set("allRepositories", "true");
  else if (scope.startsWith("repository:")) params.set("repositoryId", scope.slice("repository:".length));
  const response = await fetch(
    `/internal/security/scanning/waivers/package-context?${params.toString()}`,
    { cache: "no-store" });
  if (!response.ok) throw new Error(await responseErrorMessage(response));
  return response.json();
}

function renderSecurityScanPackageWaiverReplaced(waivers) {
  if (waivers.length === 0) {
    return '<p class="form-note">No existing waivers for this package in this scope.</p>';
  }
  const items = waivers.map((waiver) => {
    const where = [waiver.repository || "All repositories", waiver.assetPath].filter(Boolean).join(": ");
    return `<li><span class="state-badge compact ${waiver.active ? "ok" : "warn"}">${waiver.active ? "Active" : "Expired"}</span> <code>#${escapeHtml(waiver.id)}</code> ${escapeHtml(waiver.scopeType || "")} · ${escapeHtml(where)} · ${escapeHtml(waiver.exception || "-")}</li>`;
  }).join("");
  return `<p class="form-note"><strong>${waivers.length}</strong> existing ${waivers.length === 1 ? "waiver" : "waivers"} for this package in this scope will be deleted and replaced:</p><ul class="security-scan-package-waiver-list">${items}</ul>`;
}

function renderSecurityScanPackageWaiverContext(context) {
  securityScanPackageWaiverContext = context;
  document.getElementById("security-scan-package-waiver-package").value =
    securityScanPackageLabel(context);
  const scope = document.getElementById("security-scan-package-waiver-scope");
  scope.innerHTML = [
    ...context.repositories.map((repository) =>
      `<option value="repository:${escapeHtml(repository.id)}">${escapeHtml(repository.name)}</option>`),
    ...(context.canWaiveGlobally ? ['<option value="all">All repositories</option>'] : [])
  ].join("");
  scope.value = securityScanPackageWaiverScopeValue(context);
  document.getElementById("security-scan-package-waiver-urls").innerHTML =
    context.packageUrls.map((url) => `<code>${escapeHtml(url)}</code>`).join("")
      || '<span class="form-note">No package URL in this scope</span>';
  document.getElementById("security-scan-package-waiver-url-note").hidden =
    context.missingPackageUrl !== true;
  document.getElementById("security-scan-package-waiver-replaced").innerHTML =
    renderSecurityScanPackageWaiverReplaced(context.replacedWaivers);
  document.getElementById("security-scan-save-package-waiver-button").disabled =
    context.packageUrls.length === 0;
}

async function showSecurityScanPackageWaiverForm(key) {
  const node = findSecurityScanFindingNode(key);
  if (node?.groupBy !== "PACKAGE"
      || !securityScanCanMutate("nexus:security-scanning-waivers:create")) return;
  document.getElementById("security-scan-package-waiver-form").reset();
  clearRequiredFieldErrors(securityScanPackageWaiverRequiredFields);
  const target = {
    packageName: node.group.packageName,
    installedVersion: node.group.installedVersion
  };
  securityScanPackageWaiverPackage = target;
  if (!await changeSecurityScanPackageWaiverScope("")) {
    // A request superseded by a newer one must leave that newer request's target alone.
    if (securityScanPackageWaiverPackage === target) securityScanPackageWaiverPackage = null;
    return;
  }
  document.getElementById("security-scan-package-waiver-duration").value = "7776000";
  openFormModal("security-scan-package-waiver-form", "security-scan-package-waiver-reason");
}

/**
 * Loads the preview for a scope ("" lets the server pick the default). Returns whether this
 * request is the latest one and succeeded; a superseded response is discarded.
 */
async function changeSecurityScanPackageWaiverScope(scope) {
  const target = securityScanPackageWaiverPackage;
  if (!target) return false;
  const request = ++securityScanPackageWaiverRequest;
  securityScanPackageWaiverContext = null;
  document.getElementById("security-scan-save-package-waiver-button").disabled = true;
  try {
    const context = await fetchSecurityScanPackageWaiverContext(
      target.packageName, target.installedVersion, scope);
    if (request !== securityScanPackageWaiverRequest) return false;
    renderSecurityScanPackageWaiverContext(context);
    return true;
  } catch (error) {
    if (request === securityScanPackageWaiverRequest) {
      document.getElementById("security-scan-package-waiver-replaced").innerHTML =
        '<p class="form-note">This scope could not be loaded. Select another scope, or close and reopen this dialog to retry.</p>';
      showToast(`Unable to load this scope: ${error.message}`, "error");
    }
    return false;
  }
}

function hideSecurityScanPackageWaiverForm() {
  clearRequiredFieldErrors(securityScanPackageWaiverRequiredFields);
  securityScanPackageWaiverPackage = null;
  securityScanPackageWaiverContext = null;
  ++securityScanPackageWaiverRequest;
  closeFormModal("security-scan-package-waiver-form");
}

async function createSecurityScanPackageWaiver(event) {
  event.preventDefault();
  if (!securityScanCanMutate("nexus:security-scanning-waivers:create")) return;
  const context = securityScanPackageWaiverContext;
  if (!context
      || document.getElementById("security-scan-package-waiver-scope").value
        !== securityScanPackageWaiverScopeValue(context)) {
    showToast("The selected scope is still loading. Try again when it has loaded.", "error");
    return;
  }
  if (!validateRequiredFields(
      securityScanPackageWaiverRequiredFields,
      { prefix: "Waiver fields missing" })) return;
  const durationValue = document.getElementById("security-scan-package-waiver-duration").value;
  const neverExpires = durationValue === "never";
  const durationSeconds = neverExpires ? null : Number(durationValue);
  if (!neverExpires && (!Number.isFinite(durationSeconds) || durationSeconds <= 0)) {
    showToast("Waiver expiration is no longer available.", "error");
    return;
  }
  const replaced = context.replacedWaivers;
  if (replaced.length > 0 && !securityScanCanMutate("nexus:security-scanning-waivers:delete")) {
    showToast("Replacing existing waivers requires the waiver delete privilege.", "error");
    return;
  }
  const packageLabel = securityScanPackageLabel(context);
  const scopeLabel = securityScanPackageWaiverScopeLabel(context);
  if (replaced.length > 0 && !await window.kkrepoConfirmDelete({
    title: "Replace individual waivers?",
    description: `All ${replaced.length} individual ${replaced.length === 1 ? "waiver" : "waivers"} for ${packageLabel} in ${scopeLabel} will be deleted, and one package waiver will be created instead.`,
    details: [
      { label: "Package", value: packageLabel },
      { label: "Scope", value: scopeLabel },
      { label: "Waivers to delete", value: replaced.map((waiver) => `#${waiver.id}`).join(", ") }
    ],
    confirmLabel: "Yes, replace",
    cancelLabel: "No",
    warning: "Deleted waivers cannot be restored."
  })) {
    hideSecurityScanPackageWaiverForm();
    showToast("Package waiver cancelled. No waivers were changed.", "ok");
    return;
  }
  const payload = {
    packageName: context.packageName,
    installedVersion: context.installedVersion ?? "",
    repositoryId: context.repositoryId ?? null,
    reason: document.getElementById("security-scan-package-waiver-reason").value.trim(),
    expiresAt: neverExpires
      ? null
      : new Date(Date.now() + durationSeconds * 1000).toISOString(),
    replaceWaiverIds: replaced.map((waiver) => waiver.id),
    packageUrls: context.packageUrls
  };
  try {
    const response = await fetch("/internal/security/scanning/waivers/package", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(payload)
    });
    if (response.status === 409) {
      const message = await responseErrorMessage(response);
      await changeSecurityScanPackageWaiverScope(securityScanPackageWaiverScopeValue(context));
      showToast(`${message} The list of waivers to replace was refreshed.`, "error");
      return;
    }
    if (!response.ok) throw new Error(await responseErrorMessage(response));
    const result = await response.json();
    const replacedCount = Array.isArray(result?.replaced) ? result.replaced.length : 0;
    hideSecurityScanPackageWaiverForm();
    showToast(replacedCount > 0
      ? `Package waiver created for ${packageLabel}; ${replacedCount} individual ${replacedCount === 1 ? "waiver" : "waivers"} replaced.`
      : `Package waiver created for ${packageLabel}.`, "ok");
    await loadSecurityScanning();
    selectSecurityScanTab("findings");
  } catch (error) {
    showToast(`Package waiver failed: ${error.message}`, "error");
  }
}

async function deleteSecurityScanWaiver(waiverId) {
  const waiver = securityScanState.waivers.find((item) => String(item.id) === String(waiverId));
  if (!securityScanCanMutate("nexus:security-scanning-waivers:delete")
      || waiver?.canDelete !== true) return;
  if (!await window.kkrepoConfirmDelete({
    title: "Delete security scan waiver?",
    description: "The waived findings will be subject to the scan policy again, which may block downloads.",
    details: [{ label: "Waiver ID", value: String(waiverId) }]
  })) return;
  try {
    const response = await fetch(`/internal/security/scanning/waivers/${waiverId}`, { method: "DELETE" });
    if (!response.ok) throw new Error(await responseErrorMessage(response));
    showToast("Security scan waiver deleted.", "ok");
    await loadSecurityScanning();
    selectSecurityScanTab("waivers");
  } catch (error) {
    showToast(`Waiver deletion failed: ${error.message}`, "error");
  }
}

export {
  changeSecurityScanPackageWaiverScope,
  createSecurityScanPackageWaiver,
  createSecurityScanWaiver,
  deleteSecurityScanWaiver,
  hideSecurityScanPackageWaiverForm,
  hideSecurityScanWaiverDetail,
  hideSecurityScanWaiverForm,
  renderSecurityScanWaivers,
  securityScanPackageWaiverRequiredFields,
  securityScanWaiverRequiredFields,
  showCreateSecurityScanWaiverForm,
  showSecurityScanPackageWaiverForm,
  showSecurityScanWaiverDetail,
  viewAllSecurityScanWaivers,
};
