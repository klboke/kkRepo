import { cleanupRunPage, runState } from "./run-state.js";
import { applyCleanupRunPage, fetchCleanupRunPage, renderCleanupRuns } from "./runs.js";
import { escapeHtml, formatDateTime, lowerOrEmpty } from "../controls/display.js";
import { formatBadge, formatIconName } from "../controls/formats.js";
import { activeFieldHelpTrigger, hideFieldHelpPopover, showFieldHelpPopover } from "../controls/help.js";
import { closeFormModal, openFormModal } from "../controls/modal.js";
import { showToast } from "../controls/toast.js";
import { listState } from "../repositories/list.js";
import { repositoryFormatLabel, repositoryTypeLabel } from "../repositories/recipes.js";
import { responseErrorMessage } from "../shell/http.js";
import { CLEANUP_TABS, updateHashForCleanupTab } from "../shell/routes.js";
import { closeBlobStoreActionMenu } from "../storage/blob-stores.js";

let cleanupPolicies = [];

let cleanupCapabilities = [];

const CLEANUP_POLICY_DEFAULT_PAGE_SIZE = 10;

let cleanupPolicyPage = {
  after: 0,
  cursors: [0],
  page: 0,
  size: CLEANUP_POLICY_DEFAULT_PAGE_SIZE,
  nextAfter: null
};

let editingCleanupPolicyId = null;

const cleanupScheduleToggleInFlight = new Set();

let cleanupActionMenuPolicyId = null;

let cleanupActionMenuTrigger = null;

let cleanupRepositorySelection = new Set();

let cleanupRepositoryFilter = "";

let cleanupSchedulePreviewTimer = null;

let cleanupSchedulePreviewController = null;

let cleanupSchedulePreviewSequence = 0;

// ---- Cleanup policies ---------------------------------------------------
function cleanupCapability(format) {
  return cleanupCapabilities.find((item) => lowerOrEmpty(item.format) === lowerOrEmpty(format)) || null;
}

function cleanupPolicyView(policyId) {
  return cleanupPolicies.find((item) => Number(item.policy?.id) === Number(policyId)) || null;
}

function selectCleanupTab(tab, options = {}) {
  const selected = CLEANUP_TABS.has(tab) ? tab : "policies";
  if (options.updateHash !== false) {
    updateHashForCleanupTab(selected, Boolean(options.replaceHash));
  }
  document.querySelectorAll("[data-cleanup-tab]").forEach((button) => {
    const active = button.dataset.cleanupTab === selected;
    button.classList.toggle("is-active", active);
    button.setAttribute("aria-selected", String(active));
    button.tabIndex = active ? 0 : -1;
  });
  document.querySelectorAll("[data-cleanup-panel]").forEach((panel) => {
    const active = panel.dataset.cleanupPanel === selected;
    panel.classList.toggle("is-active", active);
    panel.hidden = !active;
  });
  document.getElementById("create-cleanup-policy-button").hidden = selected !== "policies";
}

function handleCleanupTabKeydown(event) {
  const tabs = Array.from(document.querySelectorAll("[data-cleanup-tab]"));
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
  selectCleanupTab(nextTab.dataset.cleanupTab);
  nextTab.focus();
}

function cleanupRuleBadges(criteria = {}) {
  const snapshot = criteria.mode === "MAVEN_SNAPSHOT_BUILDS";
  criteria = snapshot ? (criteria.snapshot || {}) : criteria;
  const rules = snapshot ? ["snapshot builds"] : [];
  if (criteria.pattern) rules.push(`${criteria.patternType === "REGEX" ? "regex" : "glob"}: ${criteria.pattern}`);
  if (criteria.publishedOlderThanDays != null) rules.push(`published > ${criteria.publishedOlderThanDays}d`);
  if (criteria.lastDownloadedOlderThanDays != null) rules.push(`downloaded > ${criteria.lastDownloadedOlderThanDays}d`);
  if (criteria.retainCount != null) rules.push(`keep ${criteria.retainCount}`);
  return `<div class="cleanup-policy-rule-list">${rules
    .map((rule) => `<span class="state-badge compact">${escapeHtml(rule)}</span>`)
    .join("")}</div>`;
}

function cleanupScheduleLabel(schedule) {
  if (!schedule) return "Disabled";
  return `${schedule.cronExpression} · ${schedule.timeZone}`;
}

function cleanupActionMenuItems() {
  return Array.from(document.querySelectorAll(
    "#cleanup-policy-action-menu [role='menuitem']:not(:disabled)"));
}

function positionCleanupPolicyActionMenu() {
  const menu = document.getElementById("cleanup-policy-action-menu");
  const trigger = cleanupActionMenuTrigger;
  if (!trigger?.isConnected || menu.hidden) return;
  const triggerRect = trigger.getBoundingClientRect();
  if (triggerRect.bottom < 0 || triggerRect.top > window.innerHeight) {
    closeCleanupPolicyActionMenu();
    return;
  }
  const menuRect = menu.getBoundingClientRect();
  const viewportPadding = 10;
  const gap = 6;
  let left = triggerRect.right - menuRect.width;
  left = Math.max(viewportPadding, Math.min(left, window.innerWidth - menuRect.width - viewportPadding));
  let top = triggerRect.bottom + gap;
  if (top + menuRect.height > window.innerHeight - viewportPadding
      && triggerRect.top - menuRect.height - gap >= viewportPadding) {
    top = triggerRect.top - menuRect.height - gap;
  }
  menu.style.left = `${Math.round(left)}px`;
  menu.style.top = `${Math.round(top)}px`;
}

function closeCleanupPolicyActionMenu(options = {}) {
  const menu = document.getElementById("cleanup-policy-action-menu");
  const trigger = cleanupActionMenuTrigger;
  if (trigger) trigger.setAttribute("aria-expanded", "false");
  cleanupActionMenuPolicyId = null;
  cleanupActionMenuTrigger = null;
  menu.hidden = true;
  menu.setAttribute("aria-hidden", "true");
  menu.removeAttribute("aria-labelledby");
  menu.innerHTML = "";
  if (options.restoreFocus && trigger?.isConnected) trigger.focus();
}

function openCleanupPolicyActionMenu(policyId, trigger, focusTarget = null) {
  const numericPolicyId = Number(policyId);
  const view = cleanupPolicyView(numericPolicyId);
  if (!view) return;
  const menu = document.getElementById("cleanup-policy-action-menu");
  if (!menu.hidden && cleanupActionMenuPolicyId === numericPolicyId) {
    closeCleanupPolicyActionMenu({ restoreFocus: true });
    return;
  }
  closeCleanupPolicyActionMenu();
  closeBlobStoreActionMenu();
  cleanupActionMenuPolicyId = numericPolicyId;
  cleanupActionMenuTrigger = trigger;
  const executeSupported = Boolean(view.capability?.executeSupported);
  const scheduleEnabled = Boolean(view.schedule?.enabled);
  const scheduleToggleDisabled = !scheduleEnabled && !executeSupported;
  menu.innerHTML = `
    <button class="cleanup-policy-action-menu-item" data-cleanup-action="try-run" type="button" role="menuitem">Try Run</button>
    <button class="cleanup-policy-action-menu-item" data-cleanup-action="execute" type="button" role="menuitem" ${executeSupported ? "" : "disabled"} title="${executeSupported ? "Run cleanup now" : "Protocol deletion adapter is still Try-Run-only"}">Run now</button>
    ${view.schedule ? `<button class="cleanup-policy-action-menu-item" data-cleanup-action="schedule" data-enable="${scheduleEnabled ? "false" : "true"}" type="button" role="menuitem" ${scheduleToggleDisabled ? "disabled" : ""} title="${scheduleToggleDisabled ? "Protocol deletion adapter is still Try-Run-only" : scheduleEnabled ? "Disable automatic execution" : "Enable automatic execution"}">${scheduleEnabled ? "Disable schedule" : "Enable schedule"}</button>` : ""}
    <div class="cleanup-policy-action-menu-separator" role="separator"></div>
    <button class="cleanup-policy-action-menu-item is-danger" data-cleanup-action="delete" type="button" role="menuitem">Delete policy</button>`;
  trigger.setAttribute("aria-expanded", "true");
  menu.setAttribute("aria-hidden", "false");
  menu.setAttribute("aria-labelledby", trigger.id);
  menu.hidden = false;
  positionCleanupPolicyActionMenu();
  const items = cleanupActionMenuItems();
  if (focusTarget === "first") items[0]?.focus();
  if (focusTarget === "last") items.at(-1)?.focus();
}

function handleCleanupPolicyActionMenuKeydown(event) {
  const menu = document.getElementById("cleanup-policy-action-menu");
  if (menu.hidden || !menu.contains(event.target)) return false;
  const items = cleanupActionMenuItems();
  const currentIndex = items.indexOf(document.activeElement);
  let nextIndex = null;
  if (event.key === "ArrowDown") nextIndex = (currentIndex + 1) % items.length;
  if (event.key === "ArrowUp") nextIndex = (currentIndex - 1 + items.length) % items.length;
  if (event.key === "Home") nextIndex = 0;
  if (event.key === "End") nextIndex = items.length - 1;
  if (event.key === "Escape") {
    event.preventDefault();
    closeCleanupPolicyActionMenu({ restoreFocus: true });
    return true;
  }
  if (nextIndex == null) return false;
  event.preventDefault();
  items[nextIndex]?.focus();
  return true;
}

function renderCleanupPolicyPagination() {
  document.getElementById("cleanup-policy-page-summary").textContent =
    cleanupPolicies.length === 1
      ? "1 result on this page"
      : `${cleanupPolicies.length} results on this page`;
  document.getElementById("cleanup-policy-page-label").textContent =
    `Page ${cleanupPolicyPage.page + 1}`;
  document.getElementById("cleanup-policy-page-prev").disabled = cleanupPolicyPage.page === 0;
  document.getElementById("cleanup-policy-page-next").disabled =
    cleanupPolicyPage.nextAfter == null;
  document.getElementById("cleanup-policy-page-size").value = String(cleanupPolicyPage.size);
}

function renderCleanupPolicies() {
  closeCleanupPolicyActionMenu();
  const body = document.getElementById("cleanup-policy-table");
  renderCleanupPolicyPagination();
  if (!cleanupPolicies.length) {
    body.innerHTML = '<tr><td colspan="7" class="empty-cell">No cleanup policies yet.</td></tr>';
    return;
  }
  body.innerHTML = cleanupPolicies.map((view) => {
    const policy = view.policy;
    const repositoryNames = (view.repositories || []).map((repository) => repository.name).join(", ");
    return `
      <tr>
        <td><strong>${escapeHtml(policy.name)}</strong><br><span class="muted">revision ${escapeHtml(policy.revision)}</span></td>
        <td>${formatBadge(policy.format)}</td>
        <td title="${escapeHtml(repositoryNames)}">${escapeHtml((view.repositories || []).length)} · ${escapeHtml(repositoryNames)}</td>
        <td>${cleanupRuleBadges(policy.criteria)}</td>
        <td>${escapeHtml(cleanupScheduleLabel(view.schedule))}${view.schedule?.nextRunAt ? `<br><span class="muted">Next ${escapeHtml(formatDateTime(view.schedule.nextRunAt))}</span>` : ""}</td>
        <td><span class="state-badge compact ${policy.state === "ACTIVE" ? "ok" : "warn"}">${escapeHtml(policy.state)}</span></td>
        <td>
          <div class="cleanup-policy-actions">
            <button class="row-action cleanup-policy-edit" data-id="${escapeHtml(policy.id)}" type="button">Edit</button>
            <button class="row-action cleanup-policy-more-actions" id="cleanup-policy-more-actions-${escapeHtml(policy.id)}" data-id="${escapeHtml(policy.id)}" type="button" aria-label="More actions for ${escapeHtml(policy.name)}" aria-haspopup="menu" aria-controls="cleanup-policy-action-menu" aria-expanded="false" title="More actions"><span aria-hidden="true">⋯</span></button>
          </div>
        </td>
      </tr>`;
  }).join("");
}

function resetCleanupPolicyPage() {
  cleanupPolicyPage.after = 0;
  cleanupPolicyPage.cursors = [0];
  cleanupPolicyPage.page = 0;
  cleanupPolicyPage.nextAfter = null;
}

async function loadCleanupPolicies(options = {}) {
  if (options.resetPage) {
    resetCleanupPolicyPage();
  }
  try {
    const [policyResponse, capabilityResponse, repositoryResponse, runPage] = await Promise.all([
      fetch(`/internal/cleanup/policies?after=${encodeURIComponent(cleanupPolicyPage.after)}&limit=${cleanupPolicyPage.size}`),
      fetch("/internal/cleanup/capabilities"),
      fetch("/internal/repositories?purpose=admin"),
      fetchCleanupRunPage()
    ]);
    if (!policyResponse.ok) throw new Error(await responseErrorMessage(policyResponse));
    if (!capabilityResponse.ok) throw new Error(await responseErrorMessage(capabilityResponse));
    if (!repositoryResponse.ok) throw new Error(await responseErrorMessage(repositoryResponse));
    const policyPage = await policyResponse.json();
    cleanupPolicies = policyPage.items || [];
    cleanupPolicyPage.nextAfter = policyPage.nextAfter ?? null;
    if (cleanupPolicies.length === 0 && cleanupPolicyPage.page > 0) {
      cleanupPolicyPage.cursors.pop();
      cleanupPolicyPage.page -= 1;
      cleanupPolicyPage.after = cleanupPolicyPage.cursors.at(-1) || 0;
      return loadCleanupPolicies();
    }
    cleanupCapabilities = await capabilityResponse.json();
    listState.repositories = await repositoryResponse.json();
    applyCleanupRunPage(runPage);
    if (runState.cleanupRuns.length === 0 && cleanupRunPage.page > 0) {
      cleanupRunPage.cursors.pop();
      cleanupRunPage.page -= 1;
      cleanupRunPage.before = cleanupRunPage.cursors.at(-1) || 0;
      return loadCleanupPolicies();
    }
    renderCleanupPolicies();
    renderCleanupRuns();
  } catch (error) {
    showToast(`Cleanup policies failed to load: ${error.message}`, "error");
  }
}

async function changeCleanupPolicyPage(direction) {
  if (direction === "next") {
    if (cleanupPolicyPage.nextAfter == null) return;
    cleanupPolicyPage.after = cleanupPolicyPage.nextAfter;
    cleanupPolicyPage.cursors.push(cleanupPolicyPage.after);
    cleanupPolicyPage.page += 1;
  } else {
    if (cleanupPolicyPage.page === 0) return;
    cleanupPolicyPage.cursors.pop();
    cleanupPolicyPage.page -= 1;
    cleanupPolicyPage.after = cleanupPolicyPage.cursors.at(-1) || 0;
  }
  await loadCleanupPolicies();
}

async function resizeCleanupPolicyPage(size) {
  cleanupPolicyPage.size = Number(size) || CLEANUP_POLICY_DEFAULT_PAGE_SIZE;
  resetCleanupPolicyPage();
  await loadCleanupPolicies();
}

function cleanupRepositoryCandidates() {
  const format = document.getElementById("cleanup-policy-format").value;
  return listState.repositories
    .filter((repository) => lowerOrEmpty(repository.format) === lowerOrEmpty(format))
    .filter((repository) => document.getElementById("cleanup-policy-mode")?.value === "MAVEN_SNAPSHOT_BUILDS"
      ? lowerOrEmpty(repository.type) === "hosted"
      : ["hosted", "proxy"].includes(lowerOrEmpty(repository.type)))
    .sort((left, right) => left.name.localeCompare(right.name));
}

function cleanupVisibleRepositories(candidates = cleanupRepositoryCandidates()) {
  const query = lowerOrEmpty(cleanupRepositoryFilter).trim();
  if (!query) return candidates;
  return candidates.filter((repository) => [
    repository.name,
    repositoryTypeLabel(repository.type),
    repository.online === false ? "offline" : "online"
  ].some((value) => lowerOrEmpty(value).includes(query)));
}

function cleanupSelectedRepositoryIds() {
  return Array.from(cleanupRepositorySelection)
    .map(Number)
    .filter(Number.isFinite);
}

function setCleanupRepositoryPickerInvalid(invalid) {
  const trigger = document.getElementById("cleanup-policy-repository-trigger");
  trigger.classList.toggle("is-invalid", invalid);
  trigger.setAttribute("aria-invalid", String(invalid));
  document.getElementById("cleanup-policy-repository-error").hidden = !invalid;
}

function cleanupRepositorySummaryHtml(candidates) {
  const selected = candidates.filter((repository) => cleanupRepositorySelection.has(String(repository.id)));
  if (selected.length === 0) {
    const format = repositoryFormatLabel(document.getElementById("cleanup-policy-format").value);
    return `<span class="cleanup-repository-placeholder">Select one or more ${escapeHtml(format)} repositories</span>`;
  }
  const chips = selected.slice(0, 2)
    .map((repository) => `<span class="cleanup-repository-chip"><span>${escapeHtml(repository.name)}</span></span>`)
    .join("");
  const more = selected.length > 2
    ? `<span class="cleanup-repository-more">+${selected.length - 2} more</span>`
    : "";
  return `${chips}${more}`;
}

function refreshCleanupRepositorySelectionChrome(candidates = cleanupRepositoryCandidates(), visible = cleanupVisibleRepositories(candidates)) {
  document.getElementById("cleanup-policy-repository-summary").innerHTML = cleanupRepositorySummaryHtml(candidates);
  document.getElementById("cleanup-policy-repository-count").textContent =
    `${cleanupRepositorySelection.size} selected · ${candidates.length} available`;
  document.getElementById("cleanup-policy-repository-select-all").disabled =
    visible.length === 0 || visible.every((repository) => cleanupRepositorySelection.has(String(repository.id)));
  document.getElementById("cleanup-policy-repository-clear").disabled = cleanupRepositorySelection.size === 0;
  if (cleanupRepositorySelection.size > 0) setCleanupRepositoryPickerInvalid(false);
}

function renderCleanupRepositoryOptions() {
  const candidates = cleanupRepositoryCandidates();
  const visible = cleanupVisibleRepositories(candidates);
  const options = document.getElementById("cleanup-policy-repository-options");
  options.innerHTML = visible.map((repository) => {
    const repositoryId = String(repository.id);
    const selected = cleanupRepositorySelection.has(repositoryId);
    const state = repository.online === false ? "Offline" : "Online";
    return `
      <label class="cleanup-repository-option ${selected ? "is-selected" : ""}" role="option" aria-selected="${selected}">
        <input type="checkbox" value="${escapeHtml(repositoryId)}" data-cleanup-repository-id="${escapeHtml(repositoryId)}" ${selected ? "checked" : ""}>
        <span class="format-logo format-logo-${formatIconName(repository.format)}" aria-hidden="true"></span>
        <span class="cleanup-repository-option-copy">
          <strong>${escapeHtml(repository.name)}</strong>
          <small>${escapeHtml(repositoryFormatLabel(repository.format))} · ${escapeHtml(state)}</small>
        </span>
        <span class="cleanup-repository-type">${escapeHtml(repositoryTypeLabel(repository.type))}</span>
      </label>`;
  }).join("");
  options.hidden = visible.length === 0;

  const empty = document.getElementById("cleanup-policy-repository-empty");
  empty.hidden = visible.length > 0;
  if (visible.length === 0) {
    const format = repositoryFormatLabel(document.getElementById("cleanup-policy-format").value);
    empty.textContent = candidates.length === 0
      ? `No Hosted or Proxy ${format} repositories are available. Create one first.`
      : `No repositories match “${cleanupRepositoryFilter.trim()}”.`;
  }
  refreshCleanupRepositorySelectionChrome(candidates, visible);
}

function setCleanupRepositoryPickerOpen(open, focusSearch = false) {
  const popover = document.getElementById("cleanup-policy-repository-popover");
  const trigger = document.getElementById("cleanup-policy-repository-trigger");
  popover.hidden = !open;
  trigger.setAttribute("aria-expanded", String(open));
  if (open) {
    renderCleanupRepositoryOptions();
    if (focusSearch) setTimeout(() => document.getElementById("cleanup-policy-repository-search").focus(), 0);
  }
}

function closeCleanupRepositoryPicker(focusTrigger = false) {
  setCleanupRepositoryPickerOpen(false);
  if (focusTrigger) document.getElementById("cleanup-policy-repository-trigger").focus();
}

function refreshCleanupRepositoryOptions(selectedIds = cleanupSelectedRepositoryIds()) {
  const candidateIds = new Set(cleanupRepositoryCandidates().map((repository) => String(repository.id)));
  cleanupRepositorySelection = new Set(selectedIds
    .map(String)
    .filter((repositoryId) => candidateIds.has(repositoryId)));
  cleanupRepositoryFilter = "";
  document.getElementById("cleanup-policy-repository-search").value = "";
  setCleanupRepositoryPickerInvalid(false);
  renderCleanupRepositoryOptions();
  refreshCleanupCapabilityHelp();
}

function bindCleanupRepositoryPicker() {
  const picker = document.getElementById("cleanup-policy-repository-picker");
  const trigger = document.getElementById("cleanup-policy-repository-trigger");
  const popover = document.getElementById("cleanup-policy-repository-popover");
  const search = document.getElementById("cleanup-policy-repository-search");
  const options = document.getElementById("cleanup-policy-repository-options");

  trigger.addEventListener("click", () => setCleanupRepositoryPickerOpen(popover.hidden, popover.hidden));
  trigger.addEventListener("keydown", (event) => {
    if (event.key === "ArrowDown" || event.key === "ArrowUp") {
      event.preventDefault();
      setCleanupRepositoryPickerOpen(true, true);
      return;
    }
    if (event.key === "Escape" && !popover.hidden) {
      event.preventDefault();
      event.stopPropagation();
      closeCleanupRepositoryPicker(true);
    }
  });

  search.addEventListener("input", () => {
    cleanupRepositoryFilter = search.value;
    renderCleanupRepositoryOptions();
  });
  search.addEventListener("keydown", (event) => {
    if (event.key === "Enter") event.preventDefault();
    if (event.key === "Escape") {
      event.preventDefault();
      event.stopPropagation();
      closeCleanupRepositoryPicker(true);
    }
  });

  options.addEventListener("change", (event) => {
    const checkbox = event.target.closest("[data-cleanup-repository-id]");
    if (!checkbox) return;
    const repositoryId = checkbox.dataset.cleanupRepositoryId;
    if (checkbox.checked) cleanupRepositorySelection.add(repositoryId);
    else cleanupRepositorySelection.delete(repositoryId);
    const option = checkbox.closest(".cleanup-repository-option");
    option.classList.toggle("is-selected", checkbox.checked);
    option.setAttribute("aria-selected", String(checkbox.checked));
    refreshCleanupRepositorySelectionChrome();
  });
  options.addEventListener("keydown", (event) => {
    if (event.key !== "Escape") return;
    event.preventDefault();
    event.stopPropagation();
    closeCleanupRepositoryPicker(true);
  });

  document.getElementById("cleanup-policy-repository-select-all").addEventListener("click", () => {
    cleanupVisibleRepositories().forEach((repository) => cleanupRepositorySelection.add(String(repository.id)));
    renderCleanupRepositoryOptions();
  });
  document.getElementById("cleanup-policy-repository-clear").addEventListener("click", () => {
    cleanupRepositorySelection.clear();
    renderCleanupRepositoryOptions();
  });
  document.addEventListener("click", (event) => {
    if (!picker.contains(event.target)) closeCleanupRepositoryPicker();
  });
}

function refreshCleanupCapabilityHelp() {
  const format = document.getElementById("cleanup-policy-format").value;
  const capability = cleanupCapability(format);
  const mode = document.getElementById("cleanup-policy-mode");
  if (format.toLowerCase() !== "maven2") mode.value = "COMPONENT";
  document.getElementById("cleanup-policy-mode-field").hidden = format.toLowerCase() !== "maven2";
  const snapshot = mode.value === "MAVEN_SNAPSHOT_BUILDS";
  const docker = format.toLowerCase() === "docker";
  document.getElementById("cleanup-policy-snapshot-help").hidden = !snapshot;
  document.getElementById("cleanup-policy-retain-label").textContent = snapshot ? "Keep newest builds per SNAPSHOT" : docker ? "Keep newest images per image name" : "Keep newest versions";
  const retainInput = document.getElementById("cleanup-policy-retain-count");
  retainInput.disabled = !capability?.retainCountSupported;
  retainInput.min = snapshot ? "1" : "0";
  retainInput.required = snapshot;
  if (retainInput.disabled) retainInput.value = "";
  setCleanupFieldHelp("cleanup-policy-retain-help", capability?.retainCountSupported
    ? (snapshot ? "Retains at least this many timestamped builds within each base SNAPSHOT version." : docker
      ? "Counts distinct tagged manifests by publication/update time per image name. Aliases count once. Untagged manifests require an age rule; referenced content remains protected."
      : "Uses this format's protocol version comparator.")
    : "Version retention is not enabled for this format yet; pattern and age rules remain available.");
  const downloadedInput = document.getElementById("cleanup-policy-downloaded-days");
  downloadedInput.disabled = !capability?.lastDownloadedSupported;
  if (downloadedInput.disabled) {
    downloadedInput.value = "";
  }
  setCleanupFieldHelp("cleanup-policy-downloaded-help", capability?.lastDownloadedSupported
    ? (snapshot ? "Successful artifact GET requests count as downloads. Never-downloaded builds use their publication time. A full observation window is required after enabling usage tracking." : "Successful authorized GET requests persist a shared download watermark; HEAD requests do not count. Artifacts without a download timestamp are skipped.")
    : "Last-download retention is disabled until this format records a validated shared download watermark.");
  document.getElementById("cleanup-policy-execute-help").textContent = capability?.executeSupported
    ? editingCleanupPolicyId == null
      ? ""
      : "Changing cleanup rules or target repositories pauses automatic execution until you review and re-enable it."
    : "Try Run is available. Actual deletion and schedule enablement stay blocked until this format's protocol adapter passes deletion validation.";
}

function setCleanupFieldHelp(id, message) {
  const help = document.getElementById(id);
  help.dataset.tooltip = message;
  help.setAttribute("aria-label", message);
  if (activeFieldHelpTrigger === help) showFieldHelpPopover(help);
}

function refreshCleanupScheduleFields() {
  const hasSchedule = Boolean(document.getElementById("cleanup-policy-cron-expression").value.trim());
  document.getElementById("cleanup-policy-time-zone").disabled = !hasSchedule;
}

function populateCleanupTimeZoneOptions() {
  const datalist = document.getElementById("cleanup-policy-time-zone-options");
  if (datalist.dataset.populated === "true") return;
  const values = new Set(["UTC", Intl.DateTimeFormat().resolvedOptions().timeZone].filter(Boolean));
  if (typeof Intl.supportedValuesOf === "function") {
    Intl.supportedValuesOf("timeZone").forEach((timeZone) => values.add(timeZone));
  }
  datalist.innerHTML = Array.from(values)
    .sort((left, right) => left.localeCompare(right))
    .map((timeZone) => `<option value="${escapeHtml(timeZone)}"></option>`)
    .join("");
  datalist.dataset.populated = "true";
}

function formatCleanupScheduleTime(value, timeZone) {
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return String(value || "");
  try {
    return new Intl.DateTimeFormat(undefined, {
      year: "numeric",
      month: "short",
      day: "numeric",
      hour: "2-digit",
      minute: "2-digit",
      second: "2-digit",
      timeZone,
      timeZoneName: "short"
    }).format(date);
  } catch (_error) {
    return formatDateTime(value);
  }
}

function renderCleanupSchedulePreview(state, title, nextRuns = [], timeZone = "") {
  const preview = document.getElementById("cleanup-policy-schedule-preview");
  const list = document.getElementById("cleanup-policy-schedule-preview-times");
  preview.dataset.state = state;
  document.getElementById("cleanup-policy-schedule-preview-title").textContent = title;
  list.innerHTML = nextRuns
    .map((value) => `<li>${escapeHtml(formatCleanupScheduleTime(value, timeZone))}</li>`)
    .join("");
  list.hidden = nextRuns.length === 0;
}

function cancelCleanupSchedulePreview() {
  clearTimeout(cleanupSchedulePreviewTimer);
  cleanupSchedulePreviewTimer = null;
  cleanupSchedulePreviewSequence += 1;
  if (cleanupSchedulePreviewController) cleanupSchedulePreviewController.abort();
  cleanupSchedulePreviewController = null;
}

function scheduleCleanupSchedulePreview() {
  cancelCleanupSchedulePreview();
  const cronExpression = document.getElementById("cleanup-policy-cron-expression").value.trim();
  const timeZone = document.getElementById("cleanup-policy-time-zone").value.trim();
  if (!cronExpression) {
    renderCleanupSchedulePreview("empty", "Manual execution only");
    return;
  }
  if (!timeZone) {
    renderCleanupSchedulePreview("error", "Choose a time zone to preview this schedule.");
    return;
  }
  const sequence = cleanupSchedulePreviewSequence;
  renderCleanupSchedulePreview("loading", "Checking the next two runs…");
  cleanupSchedulePreviewTimer = setTimeout(
    () => loadCleanupSchedulePreview(cronExpression, timeZone, sequence),
    300);
}

async function loadCleanupSchedulePreview(cronExpression, timeZone, sequence) {
  const controller = new AbortController();
  cleanupSchedulePreviewController = controller;
  try {
    const response = await fetch("/internal/cleanup/schedules/preview", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ cronExpression, timeZone, enabled: false }),
      signal: controller.signal
    });
    if (!response.ok) throw new Error(await responseErrorMessage(response));
    const preview = await response.json();
    if (sequence !== cleanupSchedulePreviewSequence) return;
    const nextRuns = Array.isArray(preview.nextRuns) ? preview.nextRuns : [];
    const label = nextRuns.length > 1 ? "Next two runs" : "Next run";
    renderCleanupSchedulePreview(
      "valid",
      `${label} in ${preview.timeZone || timeZone}`,
      nextRuns,
      preview.timeZone || timeZone);
  } catch (error) {
    if (error.name === "AbortError" || sequence !== cleanupSchedulePreviewSequence) return;
    renderCleanupSchedulePreview("error", `Check the run schedule: ${error.message}`);
  } finally {
    if (cleanupSchedulePreviewController === controller) cleanupSchedulePreviewController = null;
  }
}

function populateCleanupFormatOptions(selectedFormat = null) {
  const select = document.getElementById("cleanup-policy-format");
  select.innerHTML = cleanupCapabilities.map((capability) => `
    <option value="${escapeHtml(capability.format)}" ${lowerOrEmpty(capability.format) === lowerOrEmpty(selectedFormat) ? "selected" : ""}>
      ${escapeHtml(repositoryFormatLabel(capability.format))}
    </option>`).join("");
}

function showCreateCleanupPolicyForm() {
  editingCleanupPolicyId = null;
  const form = document.getElementById("cleanup-policy-form");
  form.reset();
  populateCleanupTimeZoneOptions();
  populateCleanupFormatOptions(cleanupCapabilities[0]?.format || "maven2");
  document.getElementById("cleanup-policy-time-zone").value = Intl.DateTimeFormat().resolvedOptions().timeZone || "UTC";
  document.getElementById("cleanup-policy-form-title").textContent = "Create cleanup policy";
  document.getElementById("save-cleanup-policy-button").textContent = "Create policy";
  refreshCleanupRepositoryOptions([]);
  refreshCleanupScheduleFields();
  scheduleCleanupSchedulePreview();
  openFormModal("cleanup-policy-form", "cleanup-policy-name");
}

function showEditCleanupPolicyForm(policyId) {
  const view = cleanupPolicyView(policyId);
  if (!view) {
    showToast("Cleanup policy no longer exists. Refresh and try again.", "error");
    return;
  }
  editingCleanupPolicyId = Number(policyId);
  populateCleanupTimeZoneOptions();
  const policy = view.policy;
  const snapshot = policy.criteria?.mode === "MAVEN_SNAPSHOT_BUILDS";
  const criteria = snapshot ? (policy.criteria.snapshot || {}) : (policy.criteria || {});
  populateCleanupFormatOptions(policy.format);
  document.getElementById("cleanup-policy-mode").value = snapshot ? "MAVEN_SNAPSHOT_BUILDS" : "COMPONENT";
  document.getElementById("cleanup-policy-name").value = policy.name || "";
  document.getElementById("cleanup-policy-pattern-type").value = criteria.patternType || "GLOB";
  document.getElementById("cleanup-policy-pattern").value = criteria.pattern || "";
  document.getElementById("cleanup-policy-published-days").value = criteria.publishedOlderThanDays ?? "";
  document.getElementById("cleanup-policy-downloaded-days").value = criteria.lastDownloadedOlderThanDays ?? "";
  document.getElementById("cleanup-policy-retain-count").value = criteria.retainCount ?? "";
  document.getElementById("cleanup-policy-notes").value = policy.notes || "";
  document.getElementById("cleanup-policy-cron-expression").value = view.schedule?.cronExpression || "";
  document.getElementById("cleanup-policy-time-zone").value = view.schedule?.timeZone || "UTC";
  document.getElementById("cleanup-policy-form-title").textContent = `Edit cleanup policy: ${policy.name}`;
  document.getElementById("save-cleanup-policy-button").textContent = "Save changes";
  refreshCleanupRepositoryOptions((view.repositories || []).map((repository) => repository.id));
  refreshCleanupCapabilityHelp();
  refreshCleanupScheduleFields();
  scheduleCleanupSchedulePreview();
  openFormModal("cleanup-policy-form", "cleanup-policy-name");
}

function hideCleanupPolicyForm() {
  cancelCleanupSchedulePreview();
  hideFieldHelpPopover();
  editingCleanupPolicyId = null;
  closeCleanupRepositoryPicker();
  closeFormModal("cleanup-policy-form");
}

function cleanupOptionalNumber(id) {
  const raw = document.getElementById(id).value;
  return raw === "" ? null : Number(raw);
}

function cleanupPolicyPayload() {
  const currentView = editingCleanupPolicyId == null ? null : cleanupPolicyView(editingCleanupPolicyId);
  const current = currentView?.policy || null;
  const criteria = {};
  const pattern = document.getElementById("cleanup-policy-pattern").value.trim();
  const publishedDays = cleanupOptionalNumber("cleanup-policy-published-days");
  const downloadedDays = cleanupOptionalNumber("cleanup-policy-downloaded-days");
  const retainCount = cleanupOptionalNumber("cleanup-policy-retain-count");
  if (pattern) {
    criteria.pattern = pattern;
    criteria.patternType = document.getElementById("cleanup-policy-pattern-type").value;
  }
  if (publishedDays != null) criteria.publishedOlderThanDays = publishedDays;
  if (downloadedDays != null) {
    criteria.lastDownloadedOlderThanDays = downloadedDays;
  }
  if (retainCount != null) criteria.retainCount = retainCount;
  const cronExpression = document.getElementById("cleanup-policy-cron-expression").value.trim();
  const schedule = cronExpression ? {
    cronExpression,
    timeZone: document.getElementById("cleanup-policy-time-zone").value.trim(),
    enabled: Boolean(currentView?.schedule?.enabled)
  } : null;
  return {
    name: document.getElementById("cleanup-policy-name").value.trim(),
    format: document.getElementById("cleanup-policy-format").value,
    notes: document.getElementById("cleanup-policy-notes").value.trim() || null,
    criteria: document.getElementById("cleanup-policy-mode")?.value === "MAVEN_SNAPSHOT_BUILDS"
      ? { mode: "MAVEN_SNAPSHOT_BUILDS", snapshot: criteria } : criteria,
    repositoryIds: cleanupSelectedRepositoryIds(),
    scanLimitPerRepository: current?.scanLimitPerRepository ?? null,
    deleteLimitPerRepository: current?.deleteLimitPerRepository ?? null,
    schedule,
    revision: current?.revision ?? null
  };
}

async function saveCleanupPolicy(event) {
  event.preventDefault();
  const payload = cleanupPolicyPayload();
  setCleanupRepositoryPickerInvalid(payload.repositoryIds.length === 0);
  if (!payload.name || payload.repositoryIds.length === 0) {
    showToast("Policy name and at least one target repository are required.", "error");
    if (payload.repositoryIds.length === 0) document.getElementById("cleanup-policy-repository-trigger").focus();
    return;
  }
  if (Object.keys(payload.criteria).length === 0) {
    showToast("Add at least one pattern, age, or retain rule.", "error");
    return;
  }
  const editing = editingCleanupPolicyId != null;
  try {
    const response = await fetch(editing
      ? `/internal/cleanup/policies/${editingCleanupPolicyId}`
      : "/internal/cleanup/policies", {
      method: editing ? "PUT" : "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(payload)
    });
    if (!response.ok) throw new Error(await responseErrorMessage(response));
    hideCleanupPolicyForm();
    showToast(editing
      ? "Cleanup policy saved. Rule or repository changes pause its schedule."
      : "Cleanup policy created. Its schedule is paused.", "ok");
    await loadCleanupPolicies();
  } catch (error) {
    showToast(`Cleanup policy save failed: ${error.message}`, "error");
  }
}

function cleanupPolicyCommandForView(view, scheduleEnabled) {
  const policy = view.policy;
  return {
    name: policy.name,
    format: policy.format,
    notes: policy.notes,
    criteria: policy.criteria || {},
    repositoryIds: (view.repositories || []).map((repository) => repository.id),
    scanLimitPerRepository: policy.scanLimitPerRepository,
    deleteLimitPerRepository: policy.deleteLimitPerRepository,
    schedule: view.schedule ? {
      cronExpression: view.schedule.cronExpression,
      timeZone: view.schedule.timeZone,
      enabled: scheduleEnabled
    } : null,
    revision: policy.revision
  };
}

async function setCleanupPolicyScheduleEnabled(policyId, enabled, button) {
  const numericPolicyId = Number(policyId);
  if (cleanupScheduleToggleInFlight.has(numericPolicyId)) return;
  const view = cleanupPolicyView(numericPolicyId);
  if (!view?.schedule) {
    showToast("Add a run schedule before enabling automatic execution.", "error");
    return;
  }
  if (enabled && !view.capability?.executeSupported) {
    showToast("Automatic execution is not available for this repository format.", "error");
    return;
  }
  cleanupScheduleToggleInFlight.add(numericPolicyId);
  const originalLabel = button?.textContent;
  if (button) {
    button.disabled = true;
    button.textContent = enabled ? "Enabling…" : "Disabling…";
  }
  try {
    const response = await fetch(`/internal/cleanup/policies/${numericPolicyId}`, {
      method: "PUT",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(cleanupPolicyCommandForView(view, enabled))
    });
    if (!response.ok) throw new Error(await responseErrorMessage(response));
    showToast(`Automatic execution ${enabled ? "enabled" : "disabled"} for "${view.policy.name}".`, "ok");
    await loadCleanupPolicies();
  } catch (error) {
    showToast(`Schedule update failed: ${error.message}`, "error");
    await loadCleanupPolicies();
  } finally {
    cleanupScheduleToggleInFlight.delete(numericPolicyId);
    if (button?.isConnected) {
      button.disabled = false;
      button.textContent = originalLabel;
    }
  }
}

async function deleteCleanupPolicy(policyId) {
  const view = cleanupPolicyView(policyId);
  if (!view || !await window.kkrepoConfirmDelete({
    title: "Delete cleanup policy?",
    description: "This removes the cleanup policy. Historical runs remain available.",
    details: [{ label: "Policy", value: view.policy.name }]
  })) return;
  try {
    const response = await fetch(`/internal/cleanup/policies/${policyId}?revision=${view.policy.revision}`, { method: "DELETE" });
    if (!response.ok) throw new Error(await responseErrorMessage(response));
    showToast("Cleanup policy deleted.", "ok");
    await loadCleanupPolicies();
  } catch (error) {
    showToast(`Cleanup policy deletion failed: ${error.message}`, "error");
  }
}

export {
  bindCleanupRepositoryPicker,
  changeCleanupPolicyPage,
  cleanupActionMenuPolicyId,
  cleanupActionMenuTrigger,
  cleanupPolicyView,
  closeCleanupPolicyActionMenu,
  deleteCleanupPolicy,
  handleCleanupPolicyActionMenuKeydown,
  handleCleanupTabKeydown,
  hideCleanupPolicyForm,
  loadCleanupPolicies,
  openCleanupPolicyActionMenu,
  positionCleanupPolicyActionMenu,
  refreshCleanupCapabilityHelp,
  refreshCleanupRepositoryOptions,
  refreshCleanupScheduleFields,
  resizeCleanupPolicyPage,
  saveCleanupPolicy,
  scheduleCleanupSchedulePreview,
  selectCleanupTab,
  setCleanupPolicyScheduleEnabled,
  showCreateCleanupPolicyForm,
  showEditCleanupPolicyForm,
};
