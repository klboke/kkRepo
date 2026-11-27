import { escapeHtml } from "../controls/display.js";
import { clearRequiredFieldErrors, validateRequiredFields } from "../controls/fields.js";
import { bindFieldHelpTrigger } from "../controls/help.js";
import { closeFormModal, openFormModal } from "../controls/modal.js";
import { showToast } from "../controls/toast.js";
import { loadSecurityScanning, securityScanCanMutate, selectSecurityScanTab } from "./overview.js";
import { loadSecurityScanList, resetSecurityScanPage, securityScanState } from "./pages.js";
import { responseErrorMessage } from "../shell/http.js";
import { canAdmin } from "../shell/session.js";

let securityScanPolicyFormMode = "create";

let editingSecurityScanPolicyId = null;

let editingSecurityScanPolicyEnabled = true;

let editingSecurityScanPolicyPlatforms = ["linux/amd64"];

const securityScanPolicyRequiredFields = [
  { id: "security-scan-policy-name", label: "Name" }
];

function renderSecurityScanRepositoryStatus(enabled) {
  const presentation = enabled
    ? { label: "Enabled", tone: "ok", icon: "check" }
    : { label: "Disabled", tone: "is-disabled", icon: "circle-slash" };
  return `
    <span class="state-badge compact security-scan-repository-status ${presentation.tone}">
      <span class="lucide-icon icon-${presentation.icon}" aria-hidden="true"></span>
      ${presentation.label}
    </span>`;
}

function renderSecurityScanSetting(label, explanation) {
  return `<span class="security-scan-setting">${escapeHtml(label)}<span class="field-help" tabindex="0" role="note" aria-label="${escapeHtml(explanation)}" data-tooltip="${escapeHtml(explanation)}"><span class="lucide-icon icon-info" aria-hidden="true"></span></span></span>`;
}

function renderSecurityScanResultValidity(repository) {
  const validity = repository.resultValidity;
  if (!validity) return renderSecurityScanSetting("Unavailable", "Effective result validity is unavailable. Refresh after the server update completes.");
  const label = formatSecurityScanValidity(validity.maxResultAgeSeconds) || "No expiry";
  const sources = {
    POLICY: "Inherited from the assigned scan policy.",
    REPOSITORY: "Set by the repository.",
    BOTH: "The shorter of the repository and assigned policy validity periods applies.",
    NO_EXPIRY: "Neither the repository nor an enabled assigned policy imposes an age limit. This does not mean the artifact is safe or its scan is current."
  };
  const context = repository.config?.policyId == null
    ? " No scan policy is assigned."
    : repository.policyEnabled === false ? " The assigned policy is disabled." : "";
  const effective = validity.source === "NO_EXPIRY" ? "No expiry." : `Effective validity: ${label}.`;
  return renderSecurityScanSetting(label, `${effective} ${sources[validity.source] || ""}${context} Age is measured from scan completion. Expired results use pending handling; policy or scanner changes can also require reevaluation.`);
}

function renderSecurityScanExceptionHandling(repository) {
  const config = repository.config || {};
  const inactive = config.enabled !== true || securityScanState.summary?.deploymentEnabled === false;
  const audit = config.enforcementMode !== "ENFORCE";
  const context = inactive
    ? "Scanning is disabled for this repository or deployment; these actions are not applied here."
    : audit
      ? "AUDIT records decisions without blocking downloads."
      : "ENFORCE applies these actions to downloads.";
  const actions = [
    ["Pending", "pendingAction", "Missing, running, stale, or expired results."],
    ["Failure", "failureAction", "Failed or cancelled scans, unavailable profiles, or artifacts rejected by scan limits."],
    ["Partial", "partialAction", "Incomplete scan coverage or missing required platforms. With an enabled policy, Allow continues vulnerability evaluation; it does not guarantee a download is allowed."]
  ];
  return `<div class="security-scan-exception-handling">${actions.map(([label, key, explanation]) => {
    const configured = config[key] === "BLOCK" ? "Block" : "Allow";
    const policyDisabled = key === "partialAction" && repository.policyEnabled === false;
    const action = policyDisabled ? "Allow" : configured;
    const effective = inactive ? `${action} (inactive)` : audit ? `${action} (audit)` : action;
    const detail = `${label}: ${effective}. Configured action: ${configured}. ${explanation} ${context}${policyDisabled ? " The scan policy is disabled, so partial-result checks are bypassed; pending and failure actions still apply." : ""} Other applicable repository policies may still block a download.`;
    return renderSecurityScanSetting(`${label}: ${effective}`, detail);
  }).join("")}</div>`;
}

function renderSecurityScanRepositories() {
  const table = document.getElementById("security-scan-repository-table");
  table.innerHTML =
    securityScanState.repositories.map((repository) => {
      const config = repository.config;
      const policy = repository.config?.policyId == null
        ? renderSecurityScanSetting("Built-in rules (unassigned)", "No policy is assigned. The built-in Critical severity rules are not a policy record in the Policies tab. Configure this repository to assign a named policy.")
        : escapeHtml(repository.policyName || "Unavailable assigned policy");
      return `
        <tr>
          <td>${renderSecurityScanRepositoryStatus(config?.enabled === true)}</td>
          <td>${renderSecurityScanSetting(repository.name, `Format: ${repository.format}. Type: ${repository.type}.`)}</td>
          <td>${escapeHtml(repository.profileName || "Unavailable profile")}</td>
          <td>${policy}${repository.policyEnabled === false ? " (disabled)" : ""}</td>
          <td>${escapeHtml(config?.enforcementMode || "AUDIT")}</td>
          <td>${renderSecurityScanResultValidity(repository)}</td>
          <td>${renderSecurityScanExceptionHandling(repository)}</td>
          <td class="actions-column">${repository.canConfigure === true ? `<button class="row-action security-scan-repository-edit" data-id="${repository.id}" type="button">configure</button>` : ""}</td>
        </tr>`;
    }).join("")
      || '<tr><td colspan="8" class="placeholder">No repositories are visible.</td></tr>';
  table.querySelectorAll(".field-help").forEach(bindFieldHelpTrigger);
}

function renderSecurityScanPolicies() {
  document.getElementById("security-scan-policy-table").innerHTML =
    securityScanState.policies.map((policy) => `
      <tr><td>${escapeHtml(policy.name)}</td>
      <td>${escapeHtml(policy.revision)}${typeof policy.latestRevision === "boolean" ? ` <span class="state-badge compact">${policy.latestRevision ? "Latest" : "Historical"}</span>` : ""}</td><td>${escapeHtml(policy.blockSeverity)}</td>
      <td>${policy.requireCompleteInventory ? "yes" : "no"}</td>
      <td>${escapeHtml(formatSecurityScanValidity(policy.maxResultAgeSeconds) || "No expiry")}</td>
      <td class="actions-column">${canAdmin("nexus:security-scanning:update") ? `<button class="row-action security-scan-policy-edit" data-id="${escapeHtml(policy.id)}" type="button">edit</button>
      <button class="row-action security-scan-policy-delete" data-id="${escapeHtml(policy.id)}" title="${policy.latestRevision === false ? "Historical revisions cannot be deleted individually. Delete the policy and all revisions from its latest revision." : "Delete this policy and all its revisions. The server checks the latest revision and references."}" ${policy.latestRevision === false ? "disabled" : ""} type="button">delete policy</button>` : ""}</td></tr>`).join("")
      || '<tr><td colspan="6" class="placeholder">No policies are visible.</td></tr>';
}

function formatSecurityScanValidity(seconds) {
  const value = Number(seconds);
  if (!Number.isFinite(value) || value <= 0) return "";
  if (value % 86400 === 0) {
    const days = value / 86400;
    return `${days} ${days === 1 ? "day" : "days"}`;
  }
  if (value % 3600 === 0) {
    const hours = value / 3600;
    return `${hours} ${hours === 1 ? "hour" : "hours"}`;
  }
  return `${value} seconds`;
}

function setSecurityScanDurationSelect(selectId, seconds) {
  const select = document.getElementById(selectId);
  select.querySelectorAll("option[data-current-value]").forEach((option) => option.remove());
  const value = seconds == null ? "" : String(seconds);
  if (value && !Array.from(select.options).some((option) => option.value === value)) {
    const option = document.createElement("option");
    option.value = value;
    option.dataset.currentValue = "true";
    option.textContent = `Current setting (${formatSecurityScanValidity(seconds)})`;
    select.appendChild(option);
  }
  select.value = value;
}

function setSecurityScanResultValidity(seconds) {
  setSecurityScanDurationSelect("security-scan-max-age", seconds);
}

function applySecurityScanRepositoryScope(repository, config) {
  const type = String(repository.type || "").toUpperCase();
  const form = document.getElementById("security-scan-repository-form");
  const hostedField = document.getElementById("security-scan-hosted-field");
  const proxyField = document.getElementById("security-scan-proxy-field");
  const hosted = document.getElementById("security-scan-hosted");
  const proxy = document.getElementById("security-scan-proxy");
  const showHosted = type !== "PROXY";
  const showProxy = type !== "HOSTED";

  form.dataset.repositoryType = type;
  hostedField.hidden = !showHosted;
  proxyField.hidden = !showProxy;
  hosted.disabled = !showHosted;
  proxy.disabled = !showProxy;
  hosted.checked = showHosted && config.scanHostedContent !== false;
  proxy.checked = showProxy && config.scanProxyContent !== false;

  const scopeNote = document.getElementById("security-scan-scope-note");
  if (type === "HOSTED") {
    scopeNote.textContent = "Hosted repositories scan packages uploaded to kkRepo.";
  } else if (type === "PROXY") {
    scopeNote.textContent = "Proxy repositories scan packages cached from the remote repository.";
  } else {
    scopeNote.textContent = "Group repositories can scan content resolved from hosted and proxy members.";
  }
}

async function loadSecurityScanPolicyOptions(assignedId) {
  // Page policy heads, not their immutable revision history. The server also
  // includes an older assigned revision so opening the form preserves its binding.
  const policies = [];
  let after = 0;
  do {
    const params = new URLSearchParams({ limit: "100", after: String(after) });
    if (assignedId != null) params.set("assignedPolicyId", String(assignedId));
    const response = await fetch(`/internal/security/scanning/policies/options?${params}`, { cache: "no-store" });
    if (!response.ok) {
      const error = new Error(`HTTP ${response.status}`);
      error.response = response;
      throw error;
    }
    const page = await response.json();
    if (!Array.isArray(page?.items)) throw new Error("Invalid scan policy response");
    policies.push(...page.items);
    const next = page.nextAfter;
    if (next != null && (!Number.isFinite(Number(next)) || Number(next) <= after)) throw new Error("Invalid scan policy cursor");
    after = next == null ? null : Number(next);
  } while (after != null);
  // A policy may be revised between option pages. Discard a superseded head,
  // while keeping the revision this repository already uses.
  const latest = new Map();
  for (const policy of policies) {
    const name = policy.name.toLowerCase();
    if (!latest.has(name) || Number(policy.revision) > Number(latest.get(name).revision)) {
      latest.set(name, policy);
    }
  }
  return policies.filter((policy) => latest.get(policy.name.toLowerCase()) === policy
    || Number(policy.id) === Number(assignedId))
    .sort((left, right) => left.name.localeCompare(right.name) || right.revision - left.revision);
}

function updateSecurityScanRepositoryPolicyHelp() {
  const policyId = optionalNumber("security-scan-policy-id");
  const policy = securityScanState.repositoryPolicyOptions.find((item) => Number(item.id) === policyId);
  const policyHelp = document.getElementById("security-scan-policy-help");
  if (policyId == null) {
    policyHelp.textContent = "No policy is assigned. Built-in rules flag Critical vulnerabilities and have no age limit. They are not listed in Policies. Mode and exception handling still apply.";
  } else if (!policy) {
    policyHelp.textContent = "The assigned policy is unavailable. The current assignment is preserved; select a policy to replace it.";
  } else {
    policyHelp.textContent = policy.enabled
      ? "Rules come from this policy in Security Scanning → Policies. Creating or editing another policy does not assign it here."
      : "This policy is disabled: vulnerability and partial-result checks are bypassed, and its age limit is ignored. Pending and failure handling still apply.";
  }
  const repositoryAge = optionalNumber("security-scan-max-age");
  const policyAge = policy?.enabled ? policy.maxResultAgeSeconds : null;
  const limits = [repositoryAge, policyAge].filter((age) => Number(age) > 0).map(Number);
  const validity = limits.length ? formatSecurityScanValidity(Math.min(...limits)) : "No expiry";
  document.getElementById("security-scan-validity-help").textContent = policyId != null && !policy
    ? "Effective validity is unavailable until the assigned policy can be loaded."
    : `Effective validity: ${validity}. Uses the shorter repository/policy limit, measured from scan completion. Expired results use pending handling. With no limit, results do not expire by age; policy or scanner changes can still require reevaluation.`;
}

async function editSecurityScanRepository(repositoryId) {
  const repository = securityScanState.repositories.find((item) => Number(item.id) === Number(repositoryId));
  if (!securityScanCanMutate() || repository?.canConfigure !== true) return;
  const request = ++securityScanState.repositoryEditRequest;
  let policies;
  try {
    policies = await loadSecurityScanPolicyOptions(repository.config?.policyId);
  } catch (error) {
    if (request !== securityScanState.repositoryEditRequest) return;
    // Error reporting and authentication redirects belong only to the current editor.
    const message = error.response ? await responseErrorMessage(error.response) : error.message;
    if (request === securityScanState.repositoryEditRequest) {
      showToast(`Failed to load scan policies: ${message}`, "error");
    }
    return;
  }
  if (request !== securityScanState.repositoryEditRequest
      || securityScanState.summary?.deploymentEnabled !== true
      || !document.getElementById("security-scanning-view").classList.contains("is-active")) return;
  securityScanState.repositoryPolicyOptions = policies;
  const config = repository.config || {};
  document.getElementById("security-scan-repository-id").value = repository.id;
  document.getElementById("security-scan-repository-name").value = repository.name;
  document.getElementById("security-scan-profile-name").value = repository.profileName || "Unavailable profile";
  document.getElementById("security-scan-profile-id").value = config.profileId || "";
  const policySelect = document.getElementById("security-scan-policy-id");
  policySelect.innerHTML = '<option value="">Built-in rules (no assigned policy)</option>'
    + policies.map((policy) => `<option value="${escapeHtml(policy.id)}">${escapeHtml(policy.name)} (revision ${escapeHtml(policy.revision)})${policy.enabled ? "" : " (disabled)"}</option>`).join("");
  if (config.policyId != null && !policies.some((policy) => Number(policy.id) === Number(config.policyId))) {
    policySelect.innerHTML += `<option value="${escapeHtml(config.policyId)}">${escapeHtml(repository.policyName || "Unavailable assigned policy")} (current assignment)</option>`;
  }
  policySelect.value = config.policyId == null ? "" : String(config.policyId);
  document.getElementById("security-scan-enforcement-mode").value = config.enforcementMode || "AUDIT";
  document.getElementById("security-scan-pending-action").value = config.pendingAction || "ALLOW";
  document.getElementById("security-scan-failure-action").value = config.failureAction || "ALLOW";
  document.getElementById("security-scan-partial-action").value = config.partialAction || "ALLOW";
  setSecurityScanResultValidity(config.maxResultAgeSeconds);
  updateSecurityScanRepositoryPolicyHelp();
  document.getElementById("security-scan-enabled").checked = Boolean(config.enabled);
  applySecurityScanRepositoryScope(repository, config);
  document.getElementById("security-scan-advanced").open =
    [config.pendingAction, config.failureAction, config.partialAction].includes("BLOCK");
  openFormModal("security-scan-repository-form", "security-scan-enabled");
}

function hideSecurityScanRepositoryForm() {
  ++securityScanState.repositoryEditRequest;
  document.getElementById("security-scan-repository-id").value = "";
  closeFormModal("security-scan-repository-form");
}

function optionalNumber(id) {
  const value = document.getElementById(id).value;
  return value === "" ? null : Number(value);
}

async function saveSecurityScanRepository(event) {
  event.preventDefault();
  const selected = securityScanState.repositories.find((item) =>
    String(item.id) === String(document.getElementById("security-scan-repository-id").value));
  if (!securityScanCanMutate() || selected?.canConfigure !== true) return;
  const form = document.getElementById("security-scan-repository-form");
  const repositoryId = Number(document.getElementById("security-scan-repository-id").value);
  const repositoryType = form.dataset.repositoryType;
  const payload = {
    enabled: document.getElementById("security-scan-enabled").checked,
    profileId: Number(document.getElementById("security-scan-profile-id").value),
    scanHostedContent: repositoryType === "PROXY"
      ? false : document.getElementById("security-scan-hosted").checked,
    scanProxyContent: repositoryType === "HOSTED"
      ? false : document.getElementById("security-scan-proxy").checked,
    enforcementMode: document.getElementById("security-scan-enforcement-mode").value,
    pendingAction: document.getElementById("security-scan-pending-action").value,
    failureAction: document.getElementById("security-scan-failure-action").value,
    partialAction: document.getElementById("security-scan-partial-action").value,
    maxResultAgeSeconds: optionalNumber("security-scan-max-age"),
    policyId: optionalNumber("security-scan-policy-id")
  };
  try {
    const response = await fetch(`/internal/security/scanning/repositories/${repositoryId}/config`, {
      method: "PUT",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(payload)
    });
    if (!response.ok) throw new Error(await responseErrorMessage(response));
    hideSecurityScanRepositoryForm();
    showToast("Repository scanning configuration saved.", "ok");
    await loadSecurityScanning();
  } catch (error) {
    showToast(`Save failed: ${error.message}`, "error");
  }
}

function showCreateSecurityScanPolicyForm() {
  if (!securityScanCanMutate()) return;
  securityScanPolicyFormMode = "create";
  editingSecurityScanPolicyId = null;
  editingSecurityScanPolicyEnabled = true;
  editingSecurityScanPolicyPlatforms = ["linux/amd64"];
  const form = document.getElementById("security-scan-policy-form");
  form.reset();
  document.getElementById("security-scan-policy-source-id").value = "";
  document.getElementById("security-scan-policy-name").disabled = false;
  document.getElementById("security-scan-policy-form-title").textContent = "Create policy";
  document.getElementById("security-scan-policy-form-note").textContent =
    "Policies are versioned so past scan decisions remain traceable.";
  document.getElementById("security-scan-save-policy-button").textContent = "Create policy";
  setSecurityScanDurationSelect("security-scan-policy-max-age", null);
  clearRequiredFieldErrors(securityScanPolicyRequiredFields);
  openFormModal("security-scan-policy-form", "security-scan-policy-name");
}

function showEditSecurityScanPolicyForm(policyId) {
  if (!securityScanCanMutate()) return;
  const policy = securityScanState.policies.find(
    (item) => Number(item.id) === Number(policyId));
  if (!policy) {
    showToast("Policy no longer exists. Refresh and try again.", "error");
    return;
  }
  securityScanPolicyFormMode = "edit";
  editingSecurityScanPolicyId = policy.id;
  editingSecurityScanPolicyEnabled = policy.enabled !== false;
  editingSecurityScanPolicyPlatforms =
    Array.isArray(policy.requiredPlatforms) ? [...policy.requiredPlatforms] : [];
  document.getElementById("security-scan-policy-source-id").value = policy.id;
  document.getElementById("security-scan-policy-name").value = policy.name || "";
  document.getElementById("security-scan-policy-name").disabled = true;
  document.getElementById("security-scan-policy-severity").value =
    policy.blockSeverity || "CRITICAL";
  document.getElementById("security-scan-policy-fixable").checked =
    Boolean(policy.onlyFixable);
  document.getElementById("security-scan-policy-block-unknown").checked =
    Boolean(policy.blockUnknownSeverity);
  document.getElementById("security-scan-policy-complete").checked =
    Boolean(policy.requireCompleteInventory);
  setSecurityScanDurationSelect(
    "security-scan-policy-max-age", policy.maxResultAgeSeconds);
  document.getElementById("security-scan-policy-form-title").textContent =
    `Edit policy: ${policy.name}`;
  document.getElementById("security-scan-policy-form-note").textContent =
    `Saving creates a new revision. Repositories using revision ${policy.revision} will move to it; historical decisions keep their original revision.`;
  document.getElementById("security-scan-save-policy-button").textContent = "Save changes";
  clearRequiredFieldErrors(securityScanPolicyRequiredFields);
  openFormModal("security-scan-policy-form", "security-scan-policy-severity");
}

function hideSecurityScanPolicyForm() {
  securityScanPolicyFormMode = "create";
  editingSecurityScanPolicyId = null;
  editingSecurityScanPolicyEnabled = true;
  editingSecurityScanPolicyPlatforms = ["linux/amd64"];
  document.getElementById("security-scan-policy-name").disabled = false;
  clearRequiredFieldErrors(securityScanPolicyRequiredFields);
  closeFormModal("security-scan-policy-form");
}

async function saveSecurityScanPolicy(event) {
  event.preventDefault();
  if (!securityScanCanMutate()) return;
  if (!validateRequiredFields(
      securityScanPolicyRequiredFields,
      { prefix: "Policy fields missing" })) return;
  const editing = securityScanPolicyFormMode === "edit";
  const payload = {
    name: document.getElementById("security-scan-policy-name").value.trim(),
    enabled: editingSecurityScanPolicyEnabled,
    blockSeverity: document.getElementById("security-scan-policy-severity").value,
    onlyFixable: document.getElementById("security-scan-policy-fixable").checked,
    blockUnknownSeverity: document.getElementById("security-scan-policy-block-unknown").checked,
    requireCompleteInventory: document.getElementById("security-scan-policy-complete").checked,
    maxResultAgeSeconds: optionalNumber("security-scan-policy-max-age"),
    requiredPlatforms: editingSecurityScanPolicyPlatforms
  };
  try {
    const path = editing
      ? `/internal/security/scanning/policies/${editingSecurityScanPolicyId}`
      : "/internal/security/scanning/policies";
    const response = await fetch(path, {
      method: editing ? "PUT" : "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(payload)
    });
    if (!response.ok) throw new Error(await responseErrorMessage(response));
    hideSecurityScanPolicyForm();
    showToast(editing
      ? "Security scan policy revision saved."
      : "Security scan policy created.", "ok");
    await loadSecurityScanning();
    selectSecurityScanTab("policies");
  } catch (error) {
    showToast(`Policy save failed: ${error.message}`, "error");
  }
}

async function deleteSecurityScanPolicy(policyId, button) {
  if (!securityScanCanMutate()) return;
  if (button?.disabled) return;
  const policy = securityScanState.policies.find((item) => String(item.id) === String(policyId));
  // Older replicas omit revision metadata during rolling upgrades; the server still fences deletion.
  if (!policy || policy.latestRevision === false) return;
  if (button) button.disabled = true;
  try {
    if (!await window.kkrepoConfirmDelete({
      title: "Delete scan policy?",
      description: "This deletes the policy and all its revisions. Referenced policies cannot be deleted.",
      details: [{ label: "Policy", value: policy.name }],
      confirmationText: policy.name
    })) return;
    const response = await fetch(
      `/internal/security/scanning/policies/${encodeURIComponent(policy.id)}`, { method: "DELETE" });
    if (!response.ok) throw new Error(await responseErrorMessage(response));
    showToast("Security scan policy and all revisions deleted.", "ok");
    resetSecurityScanPage("policies");
    await loadSecurityScanList("policies");
  } catch (error) {
    showToast(`Policy deletion failed: ${error.message}`, "error");
  } finally {
    if (button) button.disabled = false;
  }
}

export {
  deleteSecurityScanPolicy,
  editSecurityScanRepository,
  hideSecurityScanPolicyForm,
  hideSecurityScanRepositoryForm,
  renderSecurityScanPolicies,
  renderSecurityScanRepositories,
  saveSecurityScanPolicy,
  saveSecurityScanRepository,
  securityScanPolicyRequiredFields,
  showCreateSecurityScanPolicyForm,
  showEditSecurityScanPolicyForm,
  updateSecurityScanRepositoryPolicyHelp,
};
