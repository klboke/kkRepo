if (window.location.hash === "#browse" || window.location.hash.startsWith("#browse/")) {
  window.location.replace(`/browse/${window.location.hash}`);
}

installCsrfFetch();

let repositories = [];
let repositoryRecipes = [];
let blobStores = [];
let securityUsers = [];
let securityRoles = [];
let securityPrivileges = [];
let securityRealms = [];
let securityLdap = null;
let securityOidc = null;
let securityAnonymous = null;
let securityApiKeys = [];
const AUDIT_LOG_DEFAULT_PAGE_SIZE = 15;
let auditLogPage = { total: 0, page: 0, size: AUDIT_LOG_DEFAULT_PAGE_SIZE, items: [] };
let currentSession = null;
let blobStoreHealth = {};
let dockerOperations = null;
let blobStoreFormMode = "create";
let editingBlobStoreId = null;
let repositoryFormMode = "create";
let editingRepositoryName = null;
let editingRepositoryBlobStoreName = null;
let repositoryDataMigrationJobId = null;
let repositoryDataMigrationPollTimer = null;
let securityUserMode = "create";
let securityRoleMode = "create";
let securityPrivilegeMode = "create";
let repositorySort = { key: "name", direction: "asc" };
const BUILT_IN_READ_ONLY_ROLE_IDS = new Set(["nx-admin", "nx-anonymous"]);
const formModalDismissHandlers = new Map();

function installCsrfFetch() {
  if (window.__nexusPlusCsrfFetchInstalled) return;
  window.__nexusPlusCsrfFetchInstalled = true;
  const nativeFetch = window.fetch.bind(window);
  window.fetch = (input, init = {}) => {
    const method = String(init.method || "GET").toUpperCase();
    if (["POST", "PUT", "PATCH", "DELETE", "MKCOL"].includes(method) && sameOrigin(input)) {
      const token = csrfToken();
      if (token) {
        const headers = new Headers(init.headers || {});
        headers.set("X-Nexus-Plus-CSRF-Token", token);
        init = { ...init, headers };
      }
    }
    return nativeFetch(input, init);
  };
}

function sameOrigin(input) {
  const url = typeof input === "string" ? input : input.url;
  return new URL(url, window.location.origin).origin === window.location.origin;
}

function csrfToken() {
  return document.cookie
    .split(";")
    .map((part) => part.trim())
    .find((part) => part.startsWith("KKREPO_CSRF="))
    ?.substring("KKREPO_CSRF=".length) || "";
}

const viewHashRoutes = {
  repositories: "#admin/repository/repositories",
  blobstores: "#admin/repository/blobstores",
  "docker-registry": "#admin/repository/docker",
  "security-users": "#admin/security/users",
  "security-roles": "#admin/security/roles",
  "security-privileges": "#admin/security/privileges",
  "security-realms": "#admin/security/realms",
  "security-ldap": "#admin/security/ldap",
  "security-oidc": "#admin/security/oidc",
  "security-anonymous": "#admin/security/anonymous",
  "security-api-keys": "#admin/security/api-keys",
  "security-audit-log": "#admin/security/audit-log",
  "ui-settings": "#admin/system/ui-settings",
  "nexus-migration": "#admin/migration/nexus",
  "repository-data-migration": "#admin/migration/repository-data",
};

const hashViewRoutes = {
  "#admin": "repositories",
  "#admin/repository": "repositories",
  "#admin/repository/repositories": "repositories",
  "#admin/repository/blobstores": "blobstores",
  "#admin/repository/blob-stores": "blobstores",
  "#admin/repository/docker": "docker-registry",
  "#admin/repository/docker-registry": "docker-registry",
  "#admin/security": "security-users",
  "#admin/security/users": "security-users",
  "#admin/security/roles": "security-roles",
  "#admin/security/privileges": "security-privileges",
  "#admin/security/realms": "security-realms",
  "#admin/security/ldap": "security-ldap",
  "#admin/security/oidc": "security-oidc",
  "#admin/security/anonymous": "security-anonymous",
  "#admin/security/api-keys": "security-api-keys",
  "#admin/security/apikeys": "security-api-keys",
  "#admin/security/audit-log": "security-audit-log",
  "#admin/security/audit": "security-audit-log",
  "#admin/system": "ui-settings",
  "#admin/system/ui-settings": "ui-settings",
  "#admin/system/ui": "ui-settings",
  "#admin/migration": "nexus-migration",
  "#admin/migration/nexus": "nexus-migration",
  "#admin/migration/repository-data": "repository-data-migration",
};

const memberTransfer = {
  selected: [],
  highlight: { available: new Set(), selected: new Set() },
  filter: "",
  dragName: null,
};
function createTransferState() {
  return {
    selected: [],
    highlight: { available: new Set(), selected: new Set() },
    filter: ""
  };
}

const securityTransfers = {
  userRoles: createTransferState(),
  rolePrivileges: createTransferState(),
  roleRoles: createTransferState()
};
let toastTimer;
const BROWSE_WELCOME = "/browse/#browse/welcome";
const AUTH_SNAPSHOT_KEY = "nexusPlus.authSnapshot";
const SIDE_GROUP_STATE_KEY = "kkrepo.admin.sideGroups";

function applyOriginAwarePlaceholders() {
  const oidcRedirectUri = document.getElementById("security-oidc-redirect-uri");
  if (oidcRedirectUri) {
    oidcRedirectUri.placeholder = `${window.location.origin}/internal/security/oidc/callback`;
  }
}

function formModalElement(formId) {
  return document.getElementById(`${formId}-modal`);
}

function openFormModals() {
  return Array.from(document.querySelectorAll(".form-modal:not([hidden])"));
}

function activeFormModal() {
  const modals = openFormModals();
  return modals.length > 0 ? modals[modals.length - 1] : null;
}

function updateFormModalBodyState() {
  document.body.classList.toggle("has-form-modal", openFormModals().length > 0);
}

function isFocusableModalElement(element) {
  return Boolean(element)
    && !element.disabled
    && !element.hidden
    && !element.closest("[hidden]")
    && element.type !== "hidden"
    && element.getAttribute("aria-hidden") !== "true";
}

function modalFocusCandidates(modal) {
  if (!modal) return [];
  return Array.from(modal.querySelectorAll("button, input, select, textarea, [tabindex]:not([tabindex='-1'])"))
    .filter(isFocusableModalElement);
}

function focusFormModal(formId, preferredFocusId = null) {
  const modal = formModalElement(formId);
  const preferred = preferredFocusId ? document.getElementById(preferredFocusId) : null;
  const target = isFocusableModalElement(preferred)
    ? preferred
    : modalFocusCandidates(modal)[0];
  if (target) setTimeout(() => target.focus(), 0);
}

function openFormModal(formId, preferredFocusId = null) {
  const form = document.getElementById(formId);
  if (!form) return;
  const modal = formModalElement(formId);
  form.hidden = false;
  if (modal) {
    modal.hidden = false;
    modal.setAttribute("aria-hidden", "false");
  }
  updateFormModalBodyState();
  focusFormModal(formId, preferredFocusId);
}

function closeFormModal(formId) {
  const form = document.getElementById(formId);
  if (form) form.hidden = true;
  const modal = formModalElement(formId);
  if (modal) {
    modal.hidden = true;
    modal.setAttribute("aria-hidden", "true");
  }
  updateFormModalBodyState();
}

function bindFormModalDismiss(formId, handler) {
  formModalDismissHandlers.set(formId, handler);
}

function dismissFormModal(formId) {
  const handler = formModalDismissHandlers.get(formId);
  if (handler) {
    handler();
  } else {
    closeFormModal(formId);
  }
}

function trapFocusInFormModal(event) {
  const modal = activeFormModal();
  if (!modal) return false;
  const candidates = modalFocusCandidates(modal);
  if (candidates.length === 0) return false;
  const first = candidates[0];
  const last = candidates[candidates.length - 1];
  if (event.shiftKey && document.activeElement === first) {
    event.preventDefault();
    last.focus();
    return true;
  }
  if (!event.shiftKey && document.activeElement === last) {
    event.preventDefault();
    first.focus();
    return true;
  }
  return false;
}

function handleFormModalKeydown(event) {
  if (event.key === "Tab") {
    return trapFocusInFormModal(event);
  }
  if (event.key !== "Escape") return false;
  const modal = activeFormModal();
  if (!modal) return false;
  event.preventDefault();
  dismissFormModal(modal.dataset.formId);
  return true;
}

const blobStoreS3RequiredFields = [
  { id: "blobstore-name", label: "Name" },
  { id: "blobstore-endpoint", label: "Endpoint" },
  { id: "blobstore-region", label: "Region" },
  { id: "blobstore-bucket", label: "Bucket" },
  { id: "blobstore-access-key", label: "Access key" },
  { id: "blobstore-secret-key", label: "Secret key" }
];
const blobStoreFileRequiredFields = [
  { id: "blobstore-name", label: "Name" },
  { id: "blobstore-path", label: "Path" }
];
const blobStoreFormFields = [
  ...blobStoreS3RequiredFields,
  { id: "blobstore-path", label: "Path" }
];
const repositoryRequiredFields = [
  {
    id: "repository-recipe",
    label: "Recipe",
    required: () => repositoryFormMode === "create"
  },
  { id: "repository-name", label: "Name" },
  { id: "repository-blobstore", label: "Blob store", required: () => repositoryFormMode === "create" },
  {
    id: "repository-remote-url",
    label: "Remote URL",
    required: () => currentRecipe()?.type === "PROXY"
  },
  {
    id: "repository-docker-connector-port",
    label: "Connector port",
    required: () => currentRecipe()?.format === "docker"
        && document.getElementById("repository-docker-connector-enabled").checked
  }
];
const securityUserRequiredFields = [
  { id: "security-user-id", label: "User ID" }
];
const securityRoleRequiredFields = [
  { id: "security-role-id", label: "Role ID" }
];
const securityPrivilegeRequiredFields = [
  { id: "security-privilege-id", label: "Privilege ID" }
];
const ldapRequiredFields = [
  {
    id: "security-ldap-url",
    label: "URL",
    required: () => document.getElementById("security-ldap-enabled").checked
  },
  {
    id: "security-ldap-host",
    label: "Host",
    required: () => document.getElementById("security-ldap-enabled").checked
  }
];
const oidcRequiredFields = [
  { id: "security-oidc-issuer", label: "Issuer" },
  { id: "security-oidc-jwks-uri", label: "JWKS URI" },
  { id: "security-oidc-client-id", label: "Client ID" },
  { id: "security-oidc-client-secret", label: "Client secret" },
  { id: "security-oidc-redirect-uri", label: "Redirect URI" }
];
const securityApiKeyRequiredFields = [
  { id: "security-api-key-owner-user-id", label: "Owner user ID" }
];
const securityAnonymousRequiredFields = [
  { id: "security-anonymous-user-id", label: "User ID" },
  { id: "security-anonymous-realm-name", label: "Realm name" }
];
const uiSettingsRequiredFields = [
  { id: "ui-default-language", label: "Default language" }
];
const nexusMigrationRequiredFields = [
  { id: "migration-source-url", label: "Source URL" },
  { id: "migration-source-username", label: "Source username" },
  { id: "migration-source-password", label: "Source password" }
];
const repositoryDataMigrationRequiredFields = [
  { id: "repository-data-migration-source-url", label: "Source URL" },
  { id: "repository-data-migration-source-username", label: "Source username" },
  { id: "repository-data-migration-source-password", label: "Source password" }
];

function escapeHtml(value) {
  return String(value ?? "")
    .replaceAll("&", "&amp;")
    .replaceAll("<", "&lt;")
    .replaceAll(">", "&gt;")
    .replaceAll('"', "&quot;")
    .replaceAll("'", "&#39;");
}

function repoIcon(type) {
  const lower = String(type || "").toLowerCase();
  if (lower === "group") return '<span class="repo-icon group">▣</span>';
  if (lower === "proxy") return '<span class="repo-icon proxy">▤</span>';
  return '<span class="repo-icon hosted">▥</span>';
}

function blobStoreIcon(type) {
  return `<span class="repo-icon ${type === "s3" ? "proxy" : "hosted"}">▤</span>`;
}

function pathStyleBadge(enabled) {
  const label = enabled ? "Enabled" : "Disabled";
  const tone = enabled ? "ok" : "warn";
  return `<span class="state-badge compact ${tone}">${label}</span>`;
}

function engineLabel(engine) {
  if (engine === "file") return "File";
  if (engine === "oss-native") return "OSS Native SDK";
  return "AWS S3 SDK";
}

function isFileBlobStore(store) {
  return lowerOrEmpty(store?.engine) === "file" || lowerOrEmpty(store?.type) === "file";
}

function healthBadge(store) {
  const health = blobStoreHealth[String(store.id)] || initialBlobStoreHealth(store);
  const detail = health.message
    ? `<span class="health-detail" title="${escapeHtml(health.message)}">${escapeHtml(health.message)}</span>`
    : "";
  return `<span class="state-badge ${health.tone}">${escapeHtml(health.status)}</span>${detail}`;
}

function initialBlobStoreHealth(store) {
  if (store.id == null) {
    return {
      status: "Not saved",
      tone: "warn",
      message: "Persist configuration before checking."
    };
  }
  return {
    status: "Checking",
    tone: "checking",
    message: "Running health check..."
  };
}

function showToast(message, tone = "info") {
  const region = document.getElementById("toast-region");
  region.innerHTML = `<div class="toast ${tone}">${escapeHtml(message)}</div>`;
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => {
    region.innerHTML = "";
  }, tone === "error" ? 6000 : 3200);
}

function currentReturnTo() {
  return window.location.pathname + window.location.search + window.location.hash;
}

function safeLocalReturnTo(value) {
  if (!value || !value.startsWith("/") || value.startsWith("//")) return "";
  return value;
}

function authRequiredWelcome(returnTo = currentReturnTo()) {
  const params = new URLSearchParams({ login: "1" });
  const target = safeLocalReturnTo(returnTo);
  if (target) params.set("returnTo", target);
  return `/browse/?${params.toString()}#browse/welcome`;
}

function normalizeAdminHash(hash) {
  const [path] = String(hash || "").split("?");
  return path.replace(/\/+$/, "").toLowerCase();
}

function viewFromHash(hash = window.location.hash) {
  return hashViewRoutes[normalizeAdminHash(hash)] || null;
}

function readSideGroupState() {
  try {
    const parsed = JSON.parse(localStorage.getItem(SIDE_GROUP_STATE_KEY) || "{}");
    return parsed && typeof parsed === "object" && !Array.isArray(parsed) ? parsed : {};
  } catch {
    return {};
  }
}

function writeSideGroupState(state) {
  try {
    localStorage.setItem(SIDE_GROUP_STATE_KEY, JSON.stringify(state));
  } catch {
    // localStorage can be unavailable in private or constrained browser contexts.
  }
}

function sideGroupButton(groupName) {
  return Array.from(document.querySelectorAll(".side-group[data-side-group]"))
    .find((button) => button.dataset.sideGroup === groupName) || null;
}

function sideGroupItems(groupName) {
  return Array.from(document.querySelectorAll(".side-group-items[data-side-group-items]"))
    .find((items) => items.dataset.sideGroupItems === groupName) || null;
}

function setSideGroupOpen(button, open, persist = false) {
  const groupName = button?.dataset.sideGroup;
  if (!groupName) return;
  const items = sideGroupItems(groupName);
  button.classList.toggle("is-open", open);
  button.setAttribute("aria-expanded", String(open));
  if (items) {
    items.hidden = !open;
  }
  if (persist) {
    const state = readSideGroupState();
    state[groupName] = open;
    writeSideGroupState(state);
  }
}

function sideGroupForView(view) {
  const item = Array.from(document.querySelectorAll(".side-item[data-view]"))
    .find((candidate) => candidate.dataset.view === view);
  return item?.closest(".side-group-items")?.dataset.sideGroupItems || "";
}

function updateCurrentSideGroup(view) {
  document.querySelectorAll(".side-group[data-side-group]").forEach((button) => {
    button.classList.remove("is-current");
  });
  const groupName = sideGroupForView(view);
  const button = sideGroupButton(groupName);
  if (button) {
    button.classList.add("is-current");
  }
}

function initializeSideGroups() {
  const state = readSideGroupState();
  document.querySelectorAll(".side-group[data-side-group]").forEach((button) => {
    const groupName = button.dataset.sideGroup;
    const open = Object.prototype.hasOwnProperty.call(state, groupName) ? Boolean(state[groupName]) : true;
    setSideGroupOpen(button, open);
    button.addEventListener("click", () => {
      setSideGroupOpen(button, !button.classList.contains("is-open"), true);
    });
  });
  updateCurrentSideGroup(viewFromHash() || "repositories");
}

function updateHashForView(view, replace = false) {
  const hash = viewHashRoutes[view];
  if (!hash || window.location.hash === hash) return;
  if (replace) {
    window.history.replaceState(null, "", hash);
  } else {
    window.history.pushState(null, "", hash);
  }
}

function updateSessionControls(session) {
  currentSession = session || null;
  const signedIn = Boolean(currentSession?.userId);
  const userMenu = document.getElementById("user-menu");
  const currentUserPill = document.getElementById("current-user-pill");
  userMenu.hidden = !signedIn;
  if (!signedIn) closeUserMenu();
  if (signedIn) {
    const source = currentSession.source ? `${displaySource(currentSession.source)}/` : "";
    currentUserPill.textContent = `${source}${currentSession.userId}`;
    sessionStorage.setItem(AUTH_SNAPSHOT_KEY, JSON.stringify({
      session: currentSession,
      permissions: ["nexus:*"],
      savedAt: Date.now(),
    }));
  } else {
    sessionStorage.removeItem(AUTH_SNAPSHOT_KEY);
  }
}

let userMenuCloseTimer = null;

function closeUserMenu() {
  if (userMenuCloseTimer) {
    clearTimeout(userMenuCloseTimer);
    userMenuCloseTimer = null;
  }
  const trigger = document.getElementById("user-menu-trigger");
  const popover = document.getElementById("user-menu-popover");
  if (!trigger || !popover) return;
  trigger.setAttribute("aria-expanded", "false");
  popover.classList.remove("is-open");
  popover.setAttribute("aria-hidden", "true");
}

function openUserMenu() {
  if (userMenuCloseTimer) {
    clearTimeout(userMenuCloseTimer);
    userMenuCloseTimer = null;
  }
  const menu = document.getElementById("user-menu");
  const trigger = document.getElementById("user-menu-trigger");
  const popover = document.getElementById("user-menu-popover");
  if (!menu || !trigger || !popover || menu.hidden) return;
  trigger.setAttribute("aria-expanded", "true");
  popover.classList.add("is-open");
  popover.setAttribute("aria-hidden", "false");
}

function scheduleCloseUserMenu() {
  if (userMenuCloseTimer) clearTimeout(userMenuCloseTimer);
  userMenuCloseTimer = setTimeout(() => {
    userMenuCloseTimer = null;
    closeUserMenu();
  }, 120);
}

function toggleUserMenu() {
  const popover = document.getElementById("user-menu-popover");
  if (!popover) return;
  if (!popover.classList.contains("is-open")) openUserMenu();
  else closeUserMenu();
}

async function loadCurrentSession(options = {}) {
  try {
    const response = await fetch("/internal/security/session", { cache: "no-store" });
    if (response.status === 401 || response.status === 403) {
      updateSessionControls(null);
      window.location.href = authRequiredWelcome();
      return null;
    }
    if (!response.ok) throw new Error(await responseErrorMessage(response));
    const session = await response.json();
    updateSessionControls(session);
    return session;
  } catch (error) {
    updateSessionControls(null);
    if (!options.quiet) {
      showToast(`Session check failed: ${error.message}`, "error");
    }
    return null;
  }
}

function lowerOrEmpty(value) {
  return String(value ?? "").toLowerCase();
}

function formatInstant(value) {
  if (!value) return "";
  try {
    return new Date(value).toLocaleString();
  } catch {
    return String(value);
  }
}

function isLocalSource(source) {
  return lowerOrEmpty(source) === "local";
}

function displaySource(source) {
  return source == null ? "" : String(source);
}

function commaList(value) {
  return String(value || "")
    .split(",")
    .map((item) => item.trim())
    .filter(Boolean);
}

function setCommaList(id, values) {
  document.getElementById(id).value = Array.isArray(values) ? values.join(", ") : "";
}

function parseJsonObject(id) {
  const input = document.getElementById(id);
  const text = input.value.trim();
  input.classList.remove("is-invalid");
  input.setAttribute("aria-invalid", "false");
  if (!text) return {};
  try {
    const parsed = JSON.parse(text);
    if (!parsed || Array.isArray(parsed) || typeof parsed !== "object") {
      throw new Error("JSON must be an object");
    }
    return parsed;
  } catch (error) {
    input.classList.add("is-invalid");
    input.setAttribute("aria-invalid", "true");
    showToast(`Invalid JSON: ${error.message}`, "error");
    throw error;
  }
}

function markInputValidity(input, invalid) {
  input.classList.toggle("is-invalid", invalid);
  input.setAttribute("aria-invalid", String(invalid));
}

function fieldIsRequired(field) {
  return field.required ? Boolean(field.required(field)) : true;
}

function fieldValue(input) {
  if (!input) return "";
  if (input.tagName === "SELECT") return input.value;
  return String(input.value || "").trim();
}

function updateRequiredMarker(field) {
  const input = document.getElementById(field.id);
  if (!input) return;
  const marker = input.closest("label")?.querySelector(".required-mark");
  if (!marker) return;
  marker.hidden = !fieldIsRequired(field);
}

function updateRequiredMarkers(fields) {
  fields.forEach(updateRequiredMarker);
}

function setFieldRequired(input, required) {
  if (!input) return;
  if (required) {
    input.setAttribute("required", "");
    input.setAttribute("aria-required", "true");
  } else {
    input.removeAttribute("required");
    input.removeAttribute("aria-required");
  }
}

function validateRequiredFields(fields, options = {}) {
  const missing = [];
  let firstMissingInput = null;
  fields.forEach((field) => {
    const input = document.getElementById(field.id);
    if (!input) return;
    const required = fieldIsRequired(field);
    setFieldRequired(input, required);
    updateRequiredMarker(field);
    const invalid = required && !input.disabled && !fieldValue(input);
    markInputValidity(input, invalid);
    if (invalid) {
      missing.push(field.label);
      firstMissingInput = firstMissingInput || input;
    }
  });
  if (missing.length > 0) {
    showToast(`${options.prefix || "Required fields missing"}: ${missing.join(", ")}`, "error");
    firstMissingInput?.focus();
    return false;
  }
  return true;
}

function clearRequiredFieldError(event) {
  if (fieldValue(event.target)) {
    markInputValidity(event.target, false);
  }
}

function bindRequiredFieldErrors(fields) {
  fields.forEach((field) => {
    const input = document.getElementById(field.id);
    input?.addEventListener("input", clearRequiredFieldError);
    input?.addEventListener("change", clearRequiredFieldError);
  });
}

function clearRequiredFieldErrors(fields) {
  fields.forEach((field) => {
    const input = document.getElementById(field.id);
    if (input) markInputValidity(input, false);
  });
}

function filterValue(id) {
  const input = document.getElementById(id);
  return input ? input.value.trim().toLowerCase() : "";
}

function textInputValue(id) {
  const input = document.getElementById(id);
  if (!input) return null;
  const value = input.value.trim();
  return value || null;
}

function numberInputValue(id) {
  const value = textInputValue(id);
  return value == null ? null : Number(value);
}

function setInputValue(id, value, fallback = "") {
  document.getElementById(id).value = value ?? fallback;
}

function setSelectValue(id, value, fallback) {
  const select = document.getElementById(id);
  select.value = value || fallback;
}

function setCheckboxValue(id, value, fallback = false) {
  document.getElementById(id).checked = value == null ? fallback : Boolean(value);
}

function filteredRepositories() {
  const filter = document.getElementById("repository-filter").value.trim().toLowerCase();
  return repositories.filter((repo) => {
    if (!filter) return true;
    return `${repo.name} ${lowerOrEmpty(repo.type)} ${lowerOrEmpty(repo.format)} ${lowerOrEmpty(repo.recipe)} ${lowerOrEmpty(repo.blobStoreName)} ${lowerOrEmpty(repositoryDisplayUrl(repo))}`.includes(filter);
  });
}

function repositorySortValue(repo, key) {
  if (key === "recipe") return repo.recipe || "";
  if (key === "type") return lowerOrEmpty(repo.type);
  if (key === "format") return lowerOrEmpty(repo.format);
  return repo.name || "";
}

function sortRepositories(rows) {
  return [...rows].sort((a, b) => {
    const left = repositorySortValue(a, repositorySort.key);
    const right = repositorySortValue(b, repositorySort.key);
    const primary = left.localeCompare(right, undefined, { numeric: true, sensitivity: "base" });
    const fallback = (a.name || "").localeCompare(b.name || "", undefined, { numeric: true, sensitivity: "base" });
    const result = primary || fallback;
    return repositorySort.direction === "asc" ? result : -result;
  });
}

function toggleRepositorySort(key) {
  if (repositorySort.key === key) {
    repositorySort = {
      key,
      direction: repositorySort.direction === "asc" ? "desc" : "asc",
    };
  } else {
    repositorySort = { key, direction: "asc" };
  }
  renderRepositories();
}

function updateRepositorySortHeaders() {
  document.querySelectorAll("[data-repository-sort]").forEach((button) => {
    const active = button.dataset.repositorySort === repositorySort.key;
    const direction = active ? repositorySort.direction : null;
    const indicator = button.querySelector(".repo-sort-indicator");
    button.classList.toggle("is-active", active);
    button.setAttribute("aria-label", `${button.dataset.repositorySort} sort ${direction || "none"}`);
    button.closest("th").setAttribute(
      "aria-sort",
      !active ? "none" : direction === "asc" ? "ascending" : "descending",
    );
    if (indicator) indicator.textContent = active ? (direction === "asc" ? "↑" : "↓") : "";
  });
}

function filteredBlobStores() {
  const filter = document.getElementById("blobstore-filter").value.trim().toLowerCase();
  return blobStores.filter((store) => {
    if (!filter) return true;
    const health = blobStoreHealth[String(store.id)] || initialBlobStoreHealth(store);
    return `${store.name} ${store.type} ${store.engine} ${health.status} ${health.message} ${store.bucket} ${store.prefix} ${store.endpoint} ${store.path} ${store.resolvedPath} ${store.pathStyleAccess ? "path style" : "virtual host"}`
      .toLowerCase()
      .includes(filter);
  });
}

function renderRepositories() {
  updateRepositorySortHeaders();
  const rows = sortRepositories(filteredRepositories()).map((repo) => {
    const status = repo.online ? "Online" : "Offline";
    const tone = repo.online ? "ok" : "warn";
    const displayUrl = repositoryDisplayUrl(repo);
    const blobStore = repo.blobStoreName
      ? escapeHtml(repo.blobStoreName)
      : '<span class="health-muted">-</span>';
    return `
      <tr>
        <td class="icon-column">${repoIcon(repo.type)}</td>
        <td>${escapeHtml(repo.name)}</td>
        <td>${escapeHtml(repo.recipe)}</td>
        <td>${escapeHtml(lowerOrEmpty(repo.type))}</td>
        <td>${escapeHtml(lowerOrEmpty(repo.format))}</td>
        <td><span class="state-badge compact ${tone}">${status}</span></td>
        <td>${blobStore}</td>
        <td><code>${escapeHtml(displayUrl)}</code></td>
        <td class="actions-column">
          <button class="row-action edit-repository-button" data-name="${escapeHtml(repo.name)}" type="button">edit</button>
          <button class="row-action delete-repository-button" data-name="${escapeHtml(repo.name)}" type="button">delete</button>
        </td>
      </tr>
    `;
  }).join("");
  document.getElementById("repository-table").innerHTML = rows
    || '<tr><td colspan="9" class="placeholder">No repositories yet. Create your first one.</td></tr>';
}

function repositoryDisplayUrl(repo) {
  if (lowerOrEmpty(repo.format) === "docker" && repo.docker?.connectorEnabled && repo.docker?.connectorPort) {
    return repo.docker.connectorPublicUrl || `${window.location.protocol}//${window.location.hostname}:${repo.docker.connectorPort}/v2/`;
  }
  if (lowerOrEmpty(repo.type) === "proxy" && repo.proxy?.remoteUrl) {
    return repo.proxy.remoteUrl;
  }
  return repo.url || "";
}

async function loadDockerOperations() {
  const summary = document.getElementById("docker-connector-summary");
  const table = document.getElementById("docker-connector-table");
  const transfer = document.getElementById("docker-transfer-summary");
  if (!summary || !table || !transfer) return;
  summary.innerHTML = card("Status", "Loading");
  table.innerHTML = '<tr><td colspan="6" class="placeholder">Loading Docker connector runtime...</td></tr>';
  transfer.innerHTML = "";
  try {
    const response = await fetch("/internal/docker/connectors", {
      headers: { Accept: "application/json" },
      cache: "no-store",
    });
    if (!response.ok) throw new Error(await responseErrorMessage(response));
    dockerOperations = await response.json();
    renderDockerOperations(dockerOperations);
  } catch (error) {
    renderDockerOperationsError(error.message);
  }
}

function renderDockerOperations(payload) {
  const connector = payload?.connector || {};
  const transfer = payload?.transfer || {};
  const connectors = Array.isArray(connector.connectors) ? connector.connectors : [];
  const active = connectors.filter((item) => item.active).length;
  const errors = connector.lastError ? 1 : 0;
  document.getElementById("docker-connector-summary").innerHTML = [
    card("Enabled", connector.enabled ? "Yes" : "No"),
    card("Active ports", `${active} / ${connectors.length}`),
    card("Sequence", connector.sequence ?? "-"),
    card("Last refreshed", formatInstant(connector.refreshedAt) || "-"),
    card("Runtime errors", errors ? "Yes" : "No"),
  ].join("");
  document.getElementById("docker-transfer-summary").innerHTML = [
    card("Active uploads", transfer.activeUploads ?? 0),
    card("Max uploads", transfer.maxConcurrentUploads ? transfer.maxConcurrentUploads : "Unlimited"),
    card("Active downloads", transfer.activeDownloads ?? 0),
    card("Max downloads", transfer.maxConcurrentDownloads ? transfer.maxConcurrentDownloads : "Unlimited"),
  ].join("");
  document.getElementById("docker-connector-table").innerHTML = connectors.map((item) => `
    <tr>
      <td>${escapeHtml(item.repositoryName || "")}</td>
      <td>${escapeHtml(lowerOrEmpty(item.repositoryType))}</td>
      <td><code>${escapeHtml(item.port ?? "")}</code></td>
      <td>${item.publicUrl ? `<code>${escapeHtml(item.publicUrl)}</code>` : '<span class="health-muted">-</span>'}</td>
      <td><span class="state-badge compact ${item.active ? "ok" : "warn"}">${escapeHtml(item.state || "")}</span></td>
      <td>${item.active ? "Yes" : "No"}</td>
    </tr>
  `).join("") || '<tr><td colspan="6" class="placeholder">No Docker connector ports configured.</td></tr>';
  const status = document.getElementById("docker-operations-status");
  status.textContent = connector.lastError ? `Last runtime error: ${connector.lastError}` : "";
  status.classList.toggle("error", Boolean(connector.lastError));
}

function renderDockerOperationsError(message) {
  document.getElementById("docker-connector-summary").innerHTML = card("Status", "Failed");
  document.getElementById("docker-connector-table").innerHTML =
      `<tr><td colspan="6" class="placeholder">${escapeHtml(message || "Docker operations request failed.")}</td></tr>`;
  document.getElementById("docker-transfer-summary").innerHTML = "";
  const status = document.getElementById("docker-operations-status");
  status.textContent = message || "";
  status.classList.add("error");
}

function card(label, value) {
  return `<div><span>${escapeHtml(label)}</span><strong>${escapeHtml(value)}</strong></div>`;
}

async function refreshDockerConnectors() {
  const button = document.getElementById("docker-connectors-refresh-button");
  button.disabled = true;
  try {
    const response = await fetch("/internal/docker/connectors/refresh", {
      method: "POST",
      headers: { Accept: "application/json" },
      cache: "no-store",
    });
    if (!response.ok) throw new Error(await responseErrorMessage(response));
    dockerOperations = await response.json();
    renderDockerOperations(dockerOperations);
    showToast("Docker connectors refreshed.", "ok");
  } catch (error) {
    renderDockerOperationsError(error.message);
    showToast(error.message || "Docker connector refresh failed.", "error");
  } finally {
    button.disabled = false;
  }
}

async function clearDockerCache() {
  const button = document.getElementById("docker-cache-clear-button");
  button.disabled = true;
  try {
    const response = await fetch("/internal/docker/cache/clear", {
      method: "POST",
      headers: { Accept: "application/json" },
      cache: "no-store",
    });
    if (!response.ok) throw new Error(await responseErrorMessage(response));
    const payload = await response.json();
    showToast(`Docker cache cleared for ${payload.repositories ?? 0} repositories.`, "ok");
    await loadDockerOperations();
  } catch (error) {
    showToast(error.message || "Docker cache clear failed.", "error");
  } finally {
    button.disabled = false;
  }
}

function renderBlobStores() {
  document.getElementById("blobstore-table").innerHTML = filteredBlobStores().map((store) => {
    const fileStore = isFileBlobStore(store);
    const target = fileStore
      ? `<code>${escapeHtml(store.path || "")}</code>`
      : escapeHtml(store.bucket || "");
    const secondary = fileStore
      ? `<code title="${escapeHtml(store.resolvedPath || "")}">${escapeHtml(store.resolvedPath || "")}</code>`
      : (store.prefix ? `<code>${escapeHtml(store.prefix)}</code>` : '<span class="health-muted">-</span>');
    const endpoint = fileStore
      ? '<span class="health-muted">-</span>'
      : `<code>${escapeHtml(store.endpoint || "")}</code>`;
    const pathStyle = fileStore
      ? '<span class="health-muted">-</span>'
      : pathStyleBadge(Boolean(store.pathStyleAccess));
    return `
      <tr>
        <td class="icon-column">${blobStoreIcon(store.type)}</td>
        <td>${escapeHtml(store.name)}</td>
        <td>${escapeHtml(store.type)}</td>
        <td>${engineLabel(store.engine)}</td>
        <td>${healthBadge(store)}</td>
        <td>${target}</td>
        <td>${secondary}</td>
        <td>${endpoint}</td>
        <td>${pathStyle}</td>
        <td class="actions-column">
          ${store.id == null ? '<span class="health-muted">-</span>' : `
            <button class="row-action edit-blobstore-button" data-id="${store.id}" type="button">edit</button>
            <button class="row-action check-blobstore-button" data-id="${store.id}" data-name="${escapeHtml(store.name)}" type="button">check</button>
          `}
        </td>
      </tr>
    `;
  }).join("");
}

async function loadBlobStores(options = {}) {
  try {
    const response = await fetch("/internal/blob-stores", { cache: "no-store" });
    if (!response.ok) throw new Error(await responseErrorMessage(response));
    const payload = await response.json();
    blobStores = payload.stores || [];
  } catch (error) {
    blobStores = [];
    showToast(`Failed to load blob stores: ${error.message}`, "error");
  }
  renderBlobStores();
  refreshRepositoryBlobStoreOptions();
  if (options.autoCheck) {
    autoCheckBlobStores();
  }
}

async function loadRepositoryRecipes() {
  try {
    const response = await fetch("/internal/repositories/recipes", { cache: "no-store" });
    if (!response.ok) throw new Error(await responseErrorMessage(response));
    repositoryRecipes = await response.json();
  } catch (error) {
    repositoryRecipes = [];
    showToast(`Failed to load recipes: ${error.message}`, "error");
  }
  refreshRepositoryRecipeOptions();
}

async function loadRepositories() {
  try {
    const response = await fetch("/internal/repositories?purpose=admin", { cache: "no-store" });
    if (!response.ok) throw new Error(await responseErrorMessage(response));
    repositories = await response.json();
  } catch (error) {
    repositories = [];
    showToast(`Failed to load repositories: ${error.message}`, "error");
  }
  renderRepositories();
  refreshRepositoryMemberOptions();
}

function blobStoreFormPayload() {
  const engine = document.getElementById("blobstore-engine").value;
  return {
    name: document.getElementById("blobstore-name").value.trim(),
    type: engine === "file" ? "file" : "s3",
    engine,
    endpoint: document.getElementById("blobstore-endpoint").value.trim(),
    region: document.getElementById("blobstore-region").value.trim(),
    bucket: document.getElementById("blobstore-bucket").value.trim(),
    prefix: document.getElementById("blobstore-prefix").value.trim(),
    path: document.getElementById("blobstore-path").value.trim(),
    accessKey: document.getElementById("blobstore-access-key").value.trim(),
    secretKey: document.getElementById("blobstore-secret-key").value,
    pathStyleAccess: document.getElementById("blobstore-path-style").checked
  };
}

function activeBlobStoreRequiredFields() {
  const fileMode = document.getElementById("blobstore-engine").value === "file";
  return (fileMode ? blobStoreFileRequiredFields : blobStoreS3RequiredFields)
    .filter((field) => !(
      (field.id === "blobstore-access-key" || field.id === "blobstore-secret-key")
      && blobStoreFormMode === "edit"
    ));
}

function refreshBlobStoreRequiredMarkers() {
  const active = new Set(activeBlobStoreRequiredFields().map((field) => field.id));
  blobStoreFormFields.forEach((field) => {
    updateRequiredMarker({
      ...field,
      required: () => active.has(field.id)
    });
  });
}

function validateBlobStoreForm() {
  const missing = [];
  let firstMissingInput = null;
  const requiredFields = activeBlobStoreRequiredFields();
  blobStoreFormFields.forEach((field) => {
    const input = document.getElementById(field.id);
    if (!requiredFields.some((requiredField) => requiredField.id === field.id)) {
      input.classList.remove("is-invalid");
      input.setAttribute("aria-invalid", "false");
      setFieldRequired(input, false);
      return;
    }
    setFieldRequired(input, true);
    const empty = !input.value.trim();
    input.classList.toggle("is-invalid", empty);
    input.setAttribute("aria-invalid", String(empty));
    if (empty) {
      missing.push(field.label);
      firstMissingInput = firstMissingInput || input;
    }
  });
  refreshBlobStoreRequiredMarkers();
  if (missing.length > 0) {
    showToast(`Required fields missing: ${missing.join(", ")}`, "error");
    firstMissingInput.focus();
    return false;
  }
  return true;
}

function clearBlobStoreFieldError(event) {
  if (event.target.value.trim()) {
    event.target.classList.remove("is-invalid");
    event.target.setAttribute("aria-invalid", "false");
  }
}

function clearBlobStoreFormErrors() {
  blobStoreFormFields.forEach((field) => {
    const input = document.getElementById(field.id);
    input.classList.remove("is-invalid");
    input.setAttribute("aria-invalid", "false");
  });
}

function setBlobStoreFormTitle(title, saveLabel) {
  document.getElementById("blobstore-form-title").textContent = title;
  document.getElementById("save-blobstore-button").textContent = saveLabel;
}

function setSecretFieldMode(required, placeholder = "") {
  const secretInput = document.getElementById("blobstore-secret-key");
  secretInput.required = required;
  secretInput.setAttribute("aria-required", String(required));
  secretInput.placeholder = placeholder;
  refreshBlobStoreRequiredMarkers();
}

function setAccessFieldMode(required, placeholder = "") {
  const accessInput = document.getElementById("blobstore-access-key");
  accessInput.required = required;
  accessInput.setAttribute("aria-required", String(required));
  accessInput.placeholder = placeholder;
  refreshBlobStoreRequiredMarkers();
}

function refreshBlobStoreEngineControls() {
  const engine = document.getElementById("blobstore-engine").value;
  const fileMode = engine === "file";
  const pathStyle = document.getElementById("blobstore-path-style");
  const pathInput = document.getElementById("blobstore-path");
  document.querySelectorAll(".s3-only").forEach((element) => {
    element.hidden = fileMode;
  });
  document.querySelectorAll(".file-only").forEach((element) => {
    element.hidden = !fileMode;
  });
  [
    "blobstore-endpoint",
    "blobstore-region",
    "blobstore-bucket",
    "blobstore-prefix",
    "blobstore-access-key",
    "blobstore-secret-key",
    "blobstore-path-style"
  ].forEach((id) => {
    document.getElementById(id).disabled = fileMode;
  });
  pathInput.disabled = !fileMode;
  pathInput.required = fileMode;
  pathInput.setAttribute("aria-required", String(fileMode));
  pathStyle.disabled = fileMode;
  if (fileMode) {
    pathStyle.title = "";
  } else {
    pathStyle.title = "";
  }
  refreshBlobStoreRequiredMarkers();
}

function showCreateBlobStoreForm() {
  blobStoreFormMode = "create";
  editingBlobStoreId = null;
  setBlobStoreFormTitle("Create blob store", "Create blob store");
  setAccessFieldMode(true);
  setSecretFieldMode(true);
  document.getElementById("blobstore-name").disabled = false;
  document.getElementById("blobstore-name").value = "";
  document.getElementById("blobstore-engine").value = "aws-s3";
  document.getElementById("blobstore-endpoint").value = "http://127.0.0.1:9000";
  document.getElementById("blobstore-region").value = "cn-hangzhou";
  document.getElementById("blobstore-bucket").value = "";
  document.getElementById("blobstore-prefix").value = "";
  document.getElementById("blobstore-path").value = "default";
  document.getElementById("blobstore-access-key").value = "";
  document.getElementById("blobstore-secret-key").value = "";
  document.getElementById("blobstore-path-style").checked = true;
  refreshBlobStoreEngineControls();
  clearBlobStoreFormErrors();
  openFormModal("blobstore-form", "blobstore-name");
}

function showEditBlobStoreForm(id) {
  const store = blobStores.find((item) => String(item.id) === String(id));
  if (!store) {
    showToast("Blob store no longer exists. Refresh and try again.", "error");
    return;
  }
  blobStoreFormMode = "edit";
  editingBlobStoreId = store.id;
  setBlobStoreFormTitle(`Edit blob store: ${store.name}`, "Save changes");
  setAccessFieldMode(false, store.accessKeyConfigured ? "Leave blank to keep existing" : "");
  setSecretFieldMode(false, store.secretConfigured ? "Leave blank to keep existing" : "");
  document.getElementById("blobstore-name").disabled = true;
  document.getElementById("blobstore-name").value = store.name || "";
  document.getElementById("blobstore-engine").value = normalizeBlobStoreEngine(store.engine);
  document.getElementById("blobstore-endpoint").value = store.endpoint || "";
  document.getElementById("blobstore-region").value = store.region || "cn-hangzhou";
  document.getElementById("blobstore-bucket").value = store.bucket || "";
  document.getElementById("blobstore-prefix").value = store.prefix || "";
  document.getElementById("blobstore-path").value = store.path || "";
  document.getElementById("blobstore-access-key").value = "";
  document.getElementById("blobstore-secret-key").value = "";
  document.getElementById("blobstore-path-style").checked = store.pathStyleAccess !== false;
  refreshBlobStoreEngineControls();
  clearBlobStoreFormErrors();
  openFormModal("blobstore-form", isFileBlobStore(store) ? "blobstore-path" : "blobstore-endpoint");
}

function normalizeBlobStoreEngine(engine) {
  const normalized = lowerOrEmpty(engine);
  if (normalized === "file" || normalized === "oss-native") return normalized;
  return "aws-s3";
}

function hideBlobStoreForm() {
  blobStoreFormMode = "create";
  editingBlobStoreId = null;
  clearBlobStoreFormErrors();
  document.getElementById("blobstore-name").disabled = false;
  setAccessFieldMode(true);
  setSecretFieldMode(true);
  document.getElementById("blobstore-path-style").disabled = false;
  closeFormModal("blobstore-form");
}

async function postBlobStoreAction(path, options, pendingMessage, successFallback = "Operation completed.") {
  showToast(pendingMessage);
  let actionMessage = "";
  let tone = "ok";
  try {
    const response = await fetch(path, options);
    if (!response.ok) throw new Error(await responseErrorMessage(response));
    const payload = await response.json();
    if (payload.ok === false) {
      actionMessage = payload.message || "Operation failed.";
      tone = "error";
    } else {
      actionMessage = payload.message || payload.summary?.message || successFallback;
    }
  } catch (error) {
    actionMessage = `Operation failed: ${error.message}`;
    tone = "error";
  }
  await loadBlobStores({ autoCheck: true });
  showToast(actionMessage, tone);
  return tone === "ok";
}

function applyBlobStoreCheckResult(id, result) {
  const key = String(id);
  const summary = result.summary || {};
  blobStoreHealth[key] = result.ok
    ? {
        status: "Healthy",
        tone: "ok",
        message: result.message || summary.message || "Read/write check passed."
      }
    : {
        status: summary.bucketExists === false ? "Missing" : "Failed",
        tone: summary.bucketExists === false ? "warn" : "bad",
        message: result.message || summary.message || "Health check failed."
      };
  blobStores = blobStores.map((store) => {
    if (String(store.id) !== key) return store;
    return {
      ...store,
      bucketExists: Boolean(summary.bucketExists)
    };
  });
}

function applyBlobStoreCheckError(id, message) {
  blobStoreHealth[String(id)] = {
    status: "Failed",
    tone: "bad",
    message
  };
}

async function runBlobStoreCheck(store, options = {}) {
  if (!store || store.id == null) return false;
  const key = String(store.id);
  blobStoreHealth[key] = {
    status: "Checking",
    tone: "checking",
    message: "Running health check..."
  };
  renderBlobStores();
  if (options.toast) {
    showToast(`Checking ${store.name}...`);
  }
  try {
    const response = await fetch(`/internal/blob-stores/${encodeURIComponent(store.id)}/check`, { method: "POST" });
    if (!response.ok) throw new Error(await responseErrorMessage(response));
    const payload = await response.json();
    applyBlobStoreCheckResult(store.id, payload);
    renderBlobStores();
    if (options.toast) {
      showToast(payload.message || "Blob store check completed.", payload.ok ? "ok" : "error");
    }
    return Boolean(payload.ok);
  } catch (error) {
    applyBlobStoreCheckError(store.id, error.message);
    renderBlobStores();
    if (options.toast) {
      showToast(`Check failed: ${error.message}`, "error");
    }
    return false;
  }
}

function autoCheckBlobStores() {
  blobStores
    .filter((store) => store.id != null)
    .forEach((store) => {
      runBlobStoreCheck(store);
    });
}

async function responseErrorMessage(response) {
  if (response.status === 401 || response.status === 403) {
    window.location.href = authRequiredWelcome();
    return "Authentication required.";
  }
  if (response.status === 409) {
    return "Name already exists.";
  }
  try {
    const text = await response.text();
    if (!text) return `HTTP ${response.status}`;
    try {
      const payload = JSON.parse(text);
      return payload.message || payload.error || text.trim() || `HTTP ${response.status}`;
    } catch (parseError) {
      return text.trim() || `HTTP ${response.status}`;
    }
  } catch (error) {
    return `HTTP ${response.status}`;
  }
}

async function saveBlobStore(event) {
  if (event) event.preventDefault();
  if (!validateBlobStoreForm()) return;
  const creating = blobStoreFormMode === "create";
  if (!creating && editingBlobStoreId == null) {
    showToast("Select a blob store before saving changes.", "error");
    return;
  }
  const payload = blobStoreFormPayload();
  if (creating && blobStores.some((store) => store.name === payload.name)) {
    showToast("Blob store name already exists.", "error");
    document.getElementById("blobstore-name").classList.add("is-invalid");
    document.getElementById("blobstore-name").setAttribute("aria-invalid", "true");
    document.getElementById("blobstore-name").focus();
    return;
  }
  const path = creating
    ? "/internal/blob-stores"
    : `/internal/blob-stores/${encodeURIComponent(editingBlobStoreId)}`;
  const saved = await postBlobStoreAction(
    path,
    {
      method: creating ? "POST" : "PUT",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(payload)
    },
    creating ? "Creating blob store..." : "Saving blob store changes...",
    creating ? "Blob store created." : "Blob store updated.");
  if (saved) {
    hideBlobStoreForm();
  }
}

async function checkBlobStore(id, name) {
  const store = blobStores.find((item) => String(item.id) === String(id)) || { id, name };
  await runBlobStoreCheck(store, { toast: true });
}

// ---- Repository form -----------------------------------------------------

function currentRecipe() {
  const name = document.getElementById("repository-recipe").value;
  return repositoryRecipes.find((r) => r.name === name) || null;
}

function refreshRepositoryRecipeOptions() {
  const select = document.getElementById("repository-recipe");
  const previous = select.value;
  select.innerHTML = repositoryRecipes.map((r) =>
    `<option value="${escapeHtml(r.name)}">${escapeHtml(r.name)}</option>`).join("");
  if (previous && repositoryRecipes.some((r) => r.name === previous)) {
    select.value = previous;
  }
  refreshRepositoryRecipeControls();
}

function refreshRepositoryBlobStoreOptions() {
  const select = document.getElementById("repository-blobstore");
  const previous = select.value;
  const names = blobStores
    .filter((store) => store.id != null)
    .map((store) => store.name);
  if (repositoryFormMode === "edit"
      && editingRepositoryBlobStoreName
      && !names.includes(editingRepositoryBlobStoreName)) {
    names.unshift(editingRepositoryBlobStoreName);
  }
  const options = names
    .map((name) =>
      `<option value="${escapeHtml(name)}">${escapeHtml(name)}</option>`)
    .join("");
  select.innerHTML = options || '<option value="">No blob stores</option>';
  if (previous && names.includes(previous)) {
    select.value = previous;
  }
  refreshRepositoryBlobStoreLock();
}

function refreshRepositoryBlobStoreLock() {
  const select = document.getElementById("repository-blobstore");
  const locked = repositoryFormMode === "edit";
  select.disabled = locked;
  select.title = locked ? "Blob store is fixed after repository creation." : "";
  updateRequiredMarkers(repositoryRequiredFields);
}

function memberCandidates() {
  const recipe = currentRecipe();
  const format = recipe ? recipe.format : null;
  if (!format) return [];
  const allowNestedGroups = format === "pub";
  return repositories.filter((repo) => {
    if (repo.format !== format) return false;
    if (repositoryFormMode === "edit" && repo.name === editingRepositoryName) return false;
    if (repo.type === "GROUP" && !allowNestedGroups) return false;
    return true;
  });
}

function refreshRepositoryMemberOptions() {
  const list = document.getElementById("member-transfer");
  if (!list) return;
  const candidates = memberCandidates();
  const validNames = new Set(candidates.map((r) => r.name));
  memberTransfer.selected = memberTransfer.selected.filter((name) => validNames.has(name));
  memberTransfer.highlight.available = new Set(
    [...memberTransfer.highlight.available].filter((n) => validNames.has(n) && !memberTransfer.selected.includes(n)));
  memberTransfer.highlight.selected = new Set(
    [...memberTransfer.highlight.selected].filter((n) => memberTransfer.selected.includes(n)));
  renderMemberTransfer(candidates);
}

function renderMemberTransfer(candidates) {
  const byName = new Map(candidates.map((r) => [r.name, r]));
  const selectedSet = new Set(memberTransfer.selected);
  const filter = memberTransfer.filter.trim().toLowerCase();
  const available = candidates.filter((r) =>
    !selectedSet.has(r.name) && (filter === "" || r.name.toLowerCase().includes(filter)));

  const availableEl = document.getElementById("member-available-list");
  const selectedEl = document.getElementById("member-selected-list");

  availableEl.innerHTML = available.length
    ? available.map((r) => memberRowHtml(r, "available", null)).join("")
    : `<li class="member-empty">${filter ? "No matches" : "No eligible members"}</li>`;

  if (memberTransfer.selected.length === 0) {
    selectedEl.innerHTML = '<li class="member-empty">Drag in repositories to define priority order</li>';
  } else {
    selectedEl.innerHTML = memberTransfer.selected
      .map((name, idx) => {
        const repo = byName.get(name) || { name, type: "?" };
        return memberRowHtml(repo, "selected", idx + 1);
      })
      .join("");
  }

  document.getElementById("member-available-count").textContent = String(available.length);
  document.getElementById("member-selected-count").textContent = String(memberTransfer.selected.length);

  document.getElementById("member-add").disabled = memberTransfer.highlight.available.size === 0;
  document.getElementById("member-add-all").disabled = available.length === 0;
  document.getElementById("member-remove").disabled = memberTransfer.highlight.selected.size === 0;
  document.getElementById("member-remove-all").disabled = memberTransfer.selected.length === 0;
}

function memberRowHtml(repo, side, order) {
  const highlightSet = memberTransfer.highlight[side];
  const isSelected = highlightSet.has(repo.name);
  const draggable = side === "selected";
  const handle = draggable ? '<span class="member-handle" aria-hidden="true">⠿</span>' : "";
  const orderBadge = order != null ? `<span class="member-order">${order}</span>` : "";
  return `<li class="member-row${isSelected ? " is-selected" : ""}" data-name="${escapeHtml(repo.name)}" data-side="${side}"${draggable ? ' draggable="true"' : ""}>${handle}${orderBadge}<span class="member-name">${escapeHtml(repo.name)}</span><span class="member-type">${escapeHtml(lowerOrEmpty(repo.type))}</span></li>`;
}

function toggleMemberHighlight(side, name, additive) {
  const set = memberTransfer.highlight[side];
  if (additive) {
    if (set.has(name)) set.delete(name); else set.add(name);
  } else {
    set.clear();
    set.add(name);
  }
  memberTransfer.highlight[side === "available" ? "selected" : "available"].clear();
}

function addSelectedMembers(names) {
  if (!names.length) return;
  const existing = new Set(memberTransfer.selected);
  for (const name of names) {
    if (!existing.has(name)) {
      memberTransfer.selected.push(name);
      existing.add(name);
    }
  }
  memberTransfer.highlight.available.clear();
  memberTransfer.highlight.selected = new Set(names);
  refreshRepositoryMemberOptions();
}

function removeSelectedMembers(names) {
  if (!names.length) return;
  const removed = new Set(names);
  memberTransfer.selected = memberTransfer.selected.filter((n) => !removed.has(n));
  memberTransfer.highlight.selected.clear();
  memberTransfer.highlight.available = new Set(names);
  refreshRepositoryMemberOptions();
}

function reorderSelected(dragName, dropName, position) {
  if (!dragName || dragName === dropName) return;
  const list = memberTransfer.selected.filter((n) => n !== dragName);
  let idx = dropName ? list.indexOf(dropName) : list.length;
  if (idx < 0) idx = list.length;
  if (position === "below") idx += 1;
  list.splice(idx, 0, dragName);
  memberTransfer.selected = list;
  refreshRepositoryMemberOptions();
}

function bindMemberTransferEvents() {
  const root = document.getElementById("member-transfer");
  if (!root || root.dataset.bound === "1") return;
  root.dataset.bound = "1";

  const onListClick = (side) => (event) => {
    const row = event.target.closest(".member-row");
    if (!row || row.dataset.side !== side) return;
    toggleMemberHighlight(side, row.dataset.name, event.ctrlKey || event.metaKey || event.shiftKey);
    refreshRepositoryMemberOptions();
  };
  const onListDouble = (side) => (event) => {
    const row = event.target.closest(".member-row");
    if (!row || row.dataset.side !== side) return;
    if (side === "available") addSelectedMembers([row.dataset.name]);
    else removeSelectedMembers([row.dataset.name]);
  };

  const availableList = document.getElementById("member-available-list");
  const selectedList = document.getElementById("member-selected-list");
  availableList.addEventListener("click", onListClick("available"));
  availableList.addEventListener("dblclick", onListDouble("available"));
  selectedList.addEventListener("click", onListClick("selected"));
  selectedList.addEventListener("dblclick", onListDouble("selected"));

  document.getElementById("member-add").addEventListener("click", () => {
    addSelectedMembers([...memberTransfer.highlight.available]);
  });
  document.getElementById("member-add-all").addEventListener("click", () => {
    const names = Array.from(availableList.querySelectorAll(".member-row")).map((el) => el.dataset.name);
    addSelectedMembers(names);
  });
  document.getElementById("member-remove").addEventListener("click", () => {
    removeSelectedMembers([...memberTransfer.highlight.selected]);
  });
  document.getElementById("member-remove-all").addEventListener("click", () => {
    removeSelectedMembers([...memberTransfer.selected]);
  });

  document.getElementById("member-filter").addEventListener("input", (event) => {
    memberTransfer.filter = event.target.value || "";
    refreshRepositoryMemberOptions();
  });

  selectedList.addEventListener("dragstart", (event) => {
    const row = event.target.closest(".member-row");
    if (!row || row.dataset.side !== "selected") return;
    memberTransfer.dragName = row.dataset.name;
    row.classList.add("dragging");
    event.dataTransfer.effectAllowed = "move";
    try { event.dataTransfer.setData("text/plain", row.dataset.name); } catch (_) {}
  });
  selectedList.addEventListener("dragend", (event) => {
    const row = event.target.closest(".member-row");
    if (row) row.classList.remove("dragging");
    selectedList.querySelectorAll(".drop-target-above, .drop-target-below")
      .forEach((el) => el.classList.remove("drop-target-above", "drop-target-below"));
    memberTransfer.dragName = null;
  });
  selectedList.addEventListener("dragover", (event) => {
    if (!memberTransfer.dragName) return;
    event.preventDefault();
    event.dataTransfer.dropEffect = "move";
    const row = event.target.closest(".member-row");
    selectedList.querySelectorAll(".drop-target-above, .drop-target-below")
      .forEach((el) => el.classList.remove("drop-target-above", "drop-target-below"));
    if (!row) return;
    const rect = row.getBoundingClientRect();
    const below = event.clientY > rect.top + rect.height / 2;
    row.classList.add(below ? "drop-target-below" : "drop-target-above");
  });
  selectedList.addEventListener("drop", (event) => {
    if (!memberTransfer.dragName) return;
    event.preventDefault();
    const row = event.target.closest(".member-row");
    if (row) {
      const rect = row.getBoundingClientRect();
      const below = event.clientY > rect.top + rect.height / 2;
      reorderSelected(memberTransfer.dragName, row.dataset.name, below ? "below" : "above");
    } else {
      reorderSelected(memberTransfer.dragName, null, "below");
    }
  });
}

function refreshRepositoryRecipeControls() {
  const recipe = currentRecipe();
  const type = recipe ? recipe.type : null;
  const format = recipe ? recipe.format : null;
  document.getElementById("repository-hosted-fields").hidden = type !== "HOSTED";
  document.getElementById("repository-proxy-fields").hidden = type !== "PROXY";
  document.getElementById("repository-group-fields").hidden = type !== "GROUP";
  document.getElementById("repository-docker-fields").hidden = format !== "docker";
  document.getElementById("repository-cargo-fields").hidden =
    format !== "cargo";
  refreshDockerConnectorControls();
  document.getElementById("repository-blobstore").closest("label").hidden = false;
  refreshRepositoryBlobStoreLock();
  document.querySelectorAll("#repository-hosted-fields .maven-only").forEach((el) => {
    el.hidden = format !== "maven2";
  });
  refreshRepositoryRemoteDefaults(recipe);
  if (type === "GROUP") refreshRepositoryMemberOptions();
  updateRequiredMarkers(repositoryRequiredFields);
}

function refreshRepositoryRemoteDefaults(recipe) {
  const remote = document.getElementById("repository-remote-url");
  if (!recipe || recipe.type !== "PROXY") return;
  const defaults = {
    maven2: "https://repo.maven.apache.org/maven2/",
    npm: "https://registry.npmjs.org/",
    pypi: "https://pypi.org/",
    helm: "https://charts.bitnami.com/bitnami",
    nuget: "https://api.nuget.org/v3/index.json",
    rubygems: "https://rubygems.org/",
    yum: "https://download.fedoraproject.org/pub/fedora/linux/",
    raw: "https://example.com/",
    docker: "https://registry-1.docker.io/",
    cargo: "https://index.crates.io/",
    pub: "https://pub.dev/"
  };
  remote.placeholder = defaults[recipe.format] || "https://example.com/";
  if (repositoryFormMode === "create" && !remote.value.trim() && defaults[recipe.format]) {
    remote.value = defaults[recipe.format];
  }
}

function repositoryFormPayload() {
  const recipe = currentRecipe();
  const type = recipe ? recipe.type : null;
  const payload = {
    name: document.getElementById("repository-name").value.trim(),
    recipe: document.getElementById("repository-recipe").value,
    online: document.getElementById("repository-online").checked,
    strictContentTypeValidation: document.getElementById("repository-strict").checked
  };
  if (repositoryFormMode === "create") {
    payload.blobStoreName = document.getElementById("repository-blobstore").value || null;
  }
  if (type === "HOSTED") {
    payload.hosted = {
      writePolicy: document.getElementById("repository-write-policy").value,
      versionPolicy: recipe.format === "maven2" ? document.getElementById("repository-version-policy").value : null,
      layoutPolicy: recipe.format === "maven2" ? document.getElementById("repository-layout-policy").value : null
    };
  } else if (type === "PROXY") {
    const content = document.getElementById("repository-content-max-age").value;
    const metadata = document.getElementById("repository-metadata-max-age").value;
    payload.proxy = {
      remoteUrl: document.getElementById("repository-remote-url").value.trim(),
      contentMaxAgeMinutes: content === "" ? null : Number(content),
      metadataMaxAgeMinutes: metadata === "" ? null : Number(metadata),
      autoBlock: document.getElementById("repository-auto-block").checked,
      remoteUsername: textInputValue("repository-remote-username"),
      remotePassword: textInputValue("repository-remote-password"),
      remotePasswordConfigured: document.getElementById("repository-remote-password-clear").checked ? false : null,
      remoteBearerToken: textInputValue("repository-remote-bearer-token"),
      remoteBearerTokenConfigured: document.getElementById("repository-remote-bearer-token-clear").checked ? false : null
    };
  } else if (type === "GROUP") {
    payload.group = {
      memberNames: [...memberTransfer.selected]
    };
  }
  if (recipe?.format === "docker") {
    const connectorPort = document.getElementById("repository-docker-connector-port").value;
    payload.docker = {
      connectorEnabled: document.getElementById("repository-docker-connector-enabled").checked,
      connectorPort: connectorPort === "" ? null : Number(connectorPort),
      connectorPublicUrl: textInputValue("repository-docker-connector-public-url")
    };
  }
  if (recipe?.format === "cargo") {
    payload.cargo = {
      requireAuthentication: document.getElementById("repository-cargo-require-authentication").checked
    };
  }
  return payload;
}

function setRepositoryFormDefaults() {
  document.getElementById("repository-name").value = "";
  document.getElementById("repository-online").checked = true;
  document.getElementById("repository-strict").checked = true;
  document.getElementById("repository-write-policy").value = "ALLOW_ONCE";
  document.getElementById("repository-version-policy").value = "RELEASE";
  document.getElementById("repository-layout-policy").value = "STRICT";
  document.getElementById("repository-remote-url").value = "";
  document.getElementById("repository-remote-username").value = "";
  document.getElementById("repository-remote-password").value = "";
  document.getElementById("repository-remote-password").placeholder = "";
  document.getElementById("repository-remote-password-clear").checked = false;
  document.getElementById("repository-remote-bearer-token").value = "";
  document.getElementById("repository-remote-bearer-token").placeholder = "";
  document.getElementById("repository-remote-bearer-token-clear").checked = false;
  document.getElementById("repository-content-max-age").value = "1440";
  document.getElementById("repository-metadata-max-age").value = "1440";
  document.getElementById("repository-auto-block").checked = true;
  document.getElementById("repository-docker-connector-enabled").checked = false;
  document.getElementById("repository-docker-connector-port").value = "";
  document.getElementById("repository-docker-connector-public-url").value = "";
  document.getElementById("repository-cargo-require-authentication").checked = false;
  memberTransfer.selected = [];
  memberTransfer.highlight.available.clear();
  memberTransfer.highlight.selected.clear();
  memberTransfer.filter = "";
  const filterInput = document.getElementById("member-filter");
  if (filterInput) filterInput.value = "";
  refreshDockerConnectorControls();
}

function refreshDockerConnectorControls() {
  const enabled = document.getElementById("repository-docker-connector-enabled").checked;
  const portInput = document.getElementById("repository-docker-connector-port");
  portInput.disabled = !enabled;
  setFieldRequired(portInput, currentRecipe()?.format === "docker" && enabled);
  document.getElementById("repository-docker-connector-public-url").disabled = !enabled;
  updateRequiredMarkers(repositoryRequiredFields);
}

function showCreateRepositoryForm() {
  repositoryFormMode = "create";
  editingRepositoryName = null;
  editingRepositoryBlobStoreName = null;
  document.getElementById("repository-form-title").textContent = "Create repository";
  document.getElementById("save-repository-button").textContent = "Create repository";
  document.getElementById("repository-name").disabled = false;
  document.getElementById("repository-recipe").disabled = false;
  document.getElementById("repository-blobstore").disabled = false;
  document.getElementById("repository-blobstore").title = "";
  setRepositoryFormDefaults();
  if (repositoryRecipes.length > 0) {
    document.getElementById("repository-recipe").value = repositoryRecipes[0].name;
  }
  refreshRepositoryBlobStoreOptions();
  refreshRepositoryRecipeControls();
  clearRequiredFieldErrors(repositoryRequiredFields);
  openFormModal("repository-form", "repository-name");
}

function showEditRepositoryForm(name) {
  const repo = repositories.find((r) => r.name === name);
  if (!repo) {
    showToast("Repository no longer exists. Refresh.", "error");
    return;
  }
  repositoryFormMode = "edit";
  editingRepositoryName = repo.name;
  editingRepositoryBlobStoreName = repo.blobStoreName || null;
  document.getElementById("repository-form-title").textContent = `Edit repository: ${repo.name}`;
  document.getElementById("save-repository-button").textContent = "Save changes";
  document.getElementById("repository-name").disabled = true;
  document.getElementById("repository-recipe").disabled = true;
  setRepositoryFormDefaults();
  document.getElementById("repository-name").value = repo.name;
  document.getElementById("repository-recipe").value = repo.recipe;
  document.getElementById("repository-online").checked = Boolean(repo.online);
  document.getElementById("repository-strict").checked = Boolean(repo.strictContentTypeValidation);
  refreshRepositoryBlobStoreOptions();
  if (repo.blobStoreName) {
    document.getElementById("repository-blobstore").value = repo.blobStoreName;
  }
  if (repo.hosted) {
    if (repo.hosted.writePolicy) document.getElementById("repository-write-policy").value = repo.hosted.writePolicy;
    if (repo.hosted.versionPolicy) document.getElementById("repository-version-policy").value = repo.hosted.versionPolicy;
    if (repo.hosted.layoutPolicy) document.getElementById("repository-layout-policy").value = repo.hosted.layoutPolicy;
  }
  if (repo.proxy) {
    document.getElementById("repository-remote-url").value = repo.proxy.remoteUrl || "";
    document.getElementById("repository-remote-username").value = repo.proxy.remoteUsername || "";
    document.getElementById("repository-remote-password").value = "";
    document.getElementById("repository-remote-password").placeholder =
      repo.proxy.remotePasswordConfigured ? "Saved password unchanged" : "";
    document.getElementById("repository-remote-password-clear").checked = false;
    document.getElementById("repository-remote-bearer-token").value = "";
    document.getElementById("repository-remote-bearer-token").placeholder =
      repo.proxy.remoteBearerTokenConfigured ? "Saved bearer token unchanged" : "";
    document.getElementById("repository-remote-bearer-token-clear").checked = false;
    document.getElementById("repository-content-max-age").value = repo.proxy.contentMaxAgeMinutes ?? "1440";
    document.getElementById("repository-metadata-max-age").value = repo.proxy.metadataMaxAgeMinutes ?? "1440";
    document.getElementById("repository-auto-block").checked = repo.proxy.autoBlock !== false;
  }
  if (repo.type === "GROUP" && repo.group && Array.isArray(repo.group.memberNames)) {
    memberTransfer.selected = [...repo.group.memberNames];
  }
  if (repo.docker) {
    document.getElementById("repository-docker-connector-enabled").checked = Boolean(repo.docker.connectorEnabled);
    document.getElementById("repository-docker-connector-port").value = repo.docker.connectorPort ?? "";
    document.getElementById("repository-docker-connector-public-url").value = repo.docker.connectorPublicUrl || "";
  }
  if (repo.cargo) {
    document.getElementById("repository-cargo-require-authentication").checked =
      Boolean(repo.cargo.requireAuthentication);
  }
  refreshRepositoryRecipeControls();
  clearRequiredFieldErrors(repositoryRequiredFields);
  openFormModal("repository-form", "repository-online");
}

function hideRepositoryForm() {
  repositoryFormMode = "create";
  editingRepositoryName = null;
  editingRepositoryBlobStoreName = null;
  document.getElementById("repository-blobstore").disabled = false;
  document.getElementById("repository-blobstore").title = "";
  clearRequiredFieldErrors(repositoryRequiredFields);
  closeFormModal("repository-form");
}

async function saveRepository() {
  const recipe = currentRecipe();
  if (!recipe) {
    showToast("Pick a recipe before saving.", "error");
    return;
  }
  if (!validateRequiredFields(repositoryRequiredFields)) {
    return;
  }
  const payload = repositoryFormPayload();
  const creating = repositoryFormMode === "create";
  const path = creating
    ? "/internal/repositories"
    : `/internal/repositories/${encodeURIComponent(editingRepositoryName)}`;
  showToast(creating ? "Creating repository..." : "Saving repository...");
  try {
    const response = await fetch(path, {
      method: creating ? "POST" : "PUT",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(payload)
    });
    if (!response.ok) throw new Error(await responseErrorMessage(response));
    await loadRepositories();
    hideRepositoryForm();
    showToast(creating ? "Repository created." : "Repository updated.", "ok");
  } catch (error) {
    showToast(`Save failed: ${error.message}`, "error");
  }
}

async function deleteRepository(name) {
  if (!confirm(`Delete repository "${name}"? This cannot be undone.`)) return;
  showToast(`Deleting ${name}...`);
  try {
    const response = await fetch(`/internal/repositories/${encodeURIComponent(name)}`, {
      method: "DELETE"
    });
    if (!response.ok) throw new Error(await responseErrorMessage(response));
    await loadRepositories();
    showToast(`Repository ${name} deleted.`, "ok");
  } catch (error) {
    showToast(`Delete failed: ${error.message}`, "error");
  }
}

// ---- Security ------------------------------------------------------------

function listContainsFilter(values, filter) {
  if (window.kkrepoAdminFilters?.matchesFilter) {
    return window.kkrepoAdminFilters.matchesFilter(values, filter);
  }
  if (!filter) return true;
  return values.map((value) => lowerOrEmpty(value)).join(" ").includes(filter);
}

function renderSecurityUserSourceFilter() {
  const select = document.getElementById("security-user-source-filter");
  if (!select) return;
  const selected = select.value;
  const sources = Array.from(new Set(securityUsers.map((user) => user.source).filter(Boolean)))
    .sort((left, right) => {
      if (isLocalSource(left)) return -1;
      if (isLocalSource(right)) return 1;
      return displaySource(left).localeCompare(displaySource(right));
    });
  select.innerHTML = [
    '<option value="">All sources</option>',
    ...sources.map((source) => `<option value="${escapeHtml(source)}">${escapeHtml(displaySource(source))}</option>`)
  ].join("");
  select.value = sources.includes(selected) ? selected : "";
}

function ensureUserSourceOption(source) {
  const select = document.getElementById("security-user-source");
  if (!select || !source) return;
  if (Array.from(select.options).some((option) => option.value === source)) return;
  select.insertAdjacentHTML("beforeend", `<option value="${escapeHtml(source)}">${escapeHtml(displaySource(source))}</option>`);
}

function uniqueStringList(values) {
  const raw = Array.isArray(values) ? values : commaList(values);
  const result = [];
  const seen = new Set();
  for (const value of raw) {
    const text = String(value ?? "").trim();
    if (!text || seen.has(text)) continue;
    result.push(text);
    seen.add(text);
  }
  return result;
}

function compareTransferItems(left, right) {
  return left.label.localeCompare(right.label, undefined, { sensitivity: "base" });
}

function securityRoleCandidates(excludedRoleId = "") {
  const excluded = String(excludedRoleId || "").trim();
  const seen = new Set();
  return securityRoles
    .filter((role) => role?.roleId && role.roleId !== excluded)
    .filter((role) => {
      if (seen.has(role.roleId)) return false;
      seen.add(role.roleId);
      return true;
    })
    .map((role) => ({
      id: role.roleId,
      label: role.roleId,
      meta: role.readOnly ? "read-only" : "role",
      filterValues: [role.roleId, role.name, role.description, role.readOnly ? "read-only" : "role"]
    }))
    .sort(compareTransferItems);
}

function securityPrivilegeCandidates() {
  const seen = new Set();
  return securityPrivileges
    .filter((privilege) => privilege?.privilegeId)
    .filter((privilege) => {
      if (seen.has(privilege.privilegeId)) return false;
      seen.add(privilege.privilegeId);
      return true;
    })
    .map((privilege) => ({
      id: privilege.privilegeId,
      label: privilege.privilegeId,
      meta: privilege.type || "privilege",
      filterValues: [
        privilege.privilegeId,
        privilege.type,
        privilege.name,
        privilege.description,
        privilege.permission
      ]
    }))
    .sort(compareTransferItems);
}

function isBuiltInReadOnlyRoleId(roleId) {
  return BUILT_IN_READ_ONLY_ROLE_IDS.has(String(roleId || "").trim());
}

function securityTransferConfig(key) {
  if (key === "userRoles") {
    return {
      prefix: "user-role",
      state: securityTransfers.userRoles,
      inputId: "security-user-roles",
      candidates: () => securityRoleCandidates(),
      emptyAvailable: "No available roles",
      emptySelected: "No roles granted"
    };
  }
  if (key === "rolePrivileges") {
    return {
      prefix: "role-privilege",
      state: securityTransfers.rolePrivileges,
      inputId: "security-role-privileges",
      candidates: () => securityPrivilegeCandidates(),
      emptyAvailable: "No available privileges",
      emptySelected: "No privileges given"
    };
  }
  if (key === "roleRoles") {
    return {
      prefix: "role-contained",
      state: securityTransfers.roleRoles,
      inputId: "security-role-roles",
      candidates: () => securityRoleCandidates(document.getElementById("security-role-id")?.value),
      excludedId: () => document.getElementById("security-role-id")?.value?.trim() || "",
      emptyAvailable: "No available roles",
      emptySelected: "No contained roles"
    };
  }
  return null;
}

function syncSecurityTransferInput(config) {
  const input = document.getElementById(config.inputId);
  if (input) input.value = config.state.selected.join(", ");
}

function setSecurityTransferSelection(key, values) {
  const config = securityTransferConfig(key);
  if (!config) return;
  config.state.selected = uniqueStringList(values);
  config.state.highlight.available.clear();
  config.state.highlight.selected.clear();
  config.state.filter = "";
  const filter = document.getElementById(`${config.prefix}-filter`);
  if (filter) filter.value = "";
  refreshSecurityTransfer(key);
}

function transferItemMatchesFilter(item, filter) {
  return listContainsFilter(item.filterValues || [item.label, item.meta], filter);
}

function securityTransferRowHtml(config, item, side) {
  const highlighted = config.state.highlight[side].has(item.id);
  return `<li class="member-row${highlighted ? " is-selected" : ""}" data-id="${escapeHtml(item.id)}" data-side="${side}"><span class="member-name">${escapeHtml(item.label)}</span><span class="member-type">${escapeHtml(item.meta || "")}</span></li>`;
}

function refreshSecurityTransfer(key) {
  const config = securityTransferConfig(key);
  if (!config) return;
  const availableEl = document.getElementById(`${config.prefix}-available-list`);
  const selectedEl = document.getElementById(`${config.prefix}-selected-list`);
  if (!availableEl || !selectedEl) return;
  const readOnly = isSecurityTransferReadOnly(key);

  const excludedId = typeof config.excludedId === "function" ? config.excludedId() : "";
  config.state.selected = uniqueStringList(config.state.selected).filter((id) => id !== excludedId);
  const candidates = config.candidates();
  const byId = new Map(candidates.map((item) => [item.id, item]));
  const selectedSet = new Set(config.state.selected);
  const filter = config.state.filter.trim().toLowerCase();
  const available = candidates.filter((item) =>
    !selectedSet.has(item.id) && transferItemMatchesFilter(item, filter));
  const availableIds = new Set(available.map((item) => item.id));
  config.state.highlight.available = new Set(
    [...config.state.highlight.available].filter((id) => availableIds.has(id)));
  config.state.highlight.selected = new Set(
    [...config.state.highlight.selected].filter((id) => selectedSet.has(id)));

  availableEl.innerHTML = available.length
    ? available.map((item) => securityTransferRowHtml(config, item, "available")).join("")
    : `<li class="member-empty">${filter ? "No matches" : config.emptyAvailable}</li>`;

  selectedEl.innerHTML = config.state.selected.length
    ? config.state.selected
      .map((id) => securityTransferRowHtml(config, byId.get(id) || { id, label: id, meta: "missing" }, "selected"))
      .join("")
    : `<li class="member-empty">${config.emptySelected}</li>`;

  document.getElementById(`${config.prefix}-available-count`).textContent = String(available.length);
  document.getElementById(`${config.prefix}-selected-count`).textContent = String(config.state.selected.length);
  document.getElementById(`${config.prefix}-add`).disabled = readOnly || config.state.highlight.available.size === 0;
  document.getElementById(`${config.prefix}-add-all`).disabled = readOnly || available.length === 0;
  document.getElementById(`${config.prefix}-remove`).disabled = readOnly || config.state.highlight.selected.size === 0;
  document.getElementById(`${config.prefix}-remove-all`).disabled = readOnly || config.state.selected.length === 0;
  syncSecurityTransferInput(config);
}

function refreshSecurityTransfers(keys = ["userRoles", "rolePrivileges", "roleRoles"]) {
  keys.forEach(refreshSecurityTransfer);
}

function isSecurityTransferReadOnly(key) {
  return securityRoleMode === "view" && (key === "rolePrivileges" || key === "roleRoles");
}

function toggleSecurityTransferHighlight(key, side, id, additive) {
  const config = securityTransferConfig(key);
  if (isSecurityTransferReadOnly(key)) return;
  if (!config) return;
  const set = config.state.highlight[side];
  if (additive) {
    if (set.has(id)) set.delete(id); else set.add(id);
  } else {
    set.clear();
    set.add(id);
  }
  config.state.highlight[side === "available" ? "selected" : "available"].clear();
}

function addSecurityTransferItems(key, ids) {
  const config = securityTransferConfig(key);
  if (isSecurityTransferReadOnly(key)) return;
  if (!config || !ids.length) return;
  const validIds = new Set(config.candidates().map((item) => item.id));
  const existing = new Set(config.state.selected);
  const added = [];
  for (const id of ids) {
    if (validIds.has(id) && !existing.has(id)) {
      config.state.selected.push(id);
      existing.add(id);
      added.push(id);
    }
  }
  config.state.highlight.available.clear();
  config.state.highlight.selected = new Set(added);
  refreshSecurityTransfer(key);
}

function removeSecurityTransferItems(key, ids) {
  const config = securityTransferConfig(key);
  if (isSecurityTransferReadOnly(key)) return;
  if (!config || !ids.length) return;
  const removed = new Set(ids);
  config.state.selected = config.state.selected.filter((id) => !removed.has(id));
  config.state.highlight.selected.clear();
  config.state.highlight.available = new Set(ids);
  refreshSecurityTransfer(key);
}

function bindSecurityTransfer(key) {
  const config = securityTransferConfig(key);
  if (!config) return;
  const root = document.getElementById(`${config.prefix}-transfer`);
  if (!root || root.dataset.bound === "1") return;
  root.dataset.bound = "1";

  const availableList = document.getElementById(`${config.prefix}-available-list`);
  const selectedList = document.getElementById(`${config.prefix}-selected-list`);
  const listClickHandler = (side) => (event) => {
    const row = event.target.closest(".member-row");
    if (!row || row.dataset.side !== side) return;
    toggleSecurityTransferHighlight(key, side, row.dataset.id, event.ctrlKey || event.metaKey || event.shiftKey);
    refreshSecurityTransfer(key);
  };
  const listDoubleClickHandler = (side) => (event) => {
    const row = event.target.closest(".member-row");
    if (!row || row.dataset.side !== side) return;
    if (isSecurityTransferReadOnly(key)) return;
    if (side === "available") addSecurityTransferItems(key, [row.dataset.id]);
    else removeSecurityTransferItems(key, [row.dataset.id]);
  };

  availableList.addEventListener("click", listClickHandler("available"));
  availableList.addEventListener("dblclick", listDoubleClickHandler("available"));
  selectedList.addEventListener("click", listClickHandler("selected"));
  selectedList.addEventListener("dblclick", listDoubleClickHandler("selected"));
  document.getElementById(`${config.prefix}-add`).addEventListener("click", () => {
    addSecurityTransferItems(key, [...config.state.highlight.available]);
  });
  document.getElementById(`${config.prefix}-add-all`).addEventListener("click", () => {
    addSecurityTransferItems(key, Array.from(availableList.querySelectorAll(".member-row")).map((row) => row.dataset.id));
  });
  document.getElementById(`${config.prefix}-remove`).addEventListener("click", () => {
    removeSecurityTransferItems(key, [...config.state.highlight.selected]);
  });
  document.getElementById(`${config.prefix}-remove-all`).addEventListener("click", () => {
    removeSecurityTransferItems(key, [...config.state.selected]);
  });
  document.getElementById(`${config.prefix}-filter`).addEventListener("input", (event) => {
    config.state.filter = event.target.value || "";
    refreshSecurityTransfer(key);
  });
}

function bindSecurityTransfers() {
  ["userRoles", "rolePrivileges", "roleRoles"].forEach(bindSecurityTransfer);
}

async function fetchJson(path, fallback, errorLabel) {
  try {
    const response = await fetch(path, { cache: "no-store" });
    if (response.status === 401 || response.status === 403) {
      updateSessionControls(null);
      window.location.href = authRequiredWelcome();
      return fallback;
    }
    if (!response.ok) throw new Error(await responseErrorMessage(response));
    return await response.json();
  } catch (error) {
    showToast(`${errorLabel}: ${error.message}`, "error");
    return fallback;
  }
}

function uiLanguageLabel(language) {
  if (language === "zh-CN") return "Chinese";
  if (language === "en") return "English";
  return "Follow browser";
}

function syncUiSettingsForm() {
  const settings = window.kkrepoI18n?.settings?.();
  const select = document.getElementById("ui-default-language");
  if (select && settings) {
    select.value = settings.defaultLanguage || "en";
  }
  clearRequiredFieldErrors(uiSettingsRequiredFields);
  updateUiSettingsStatus();
}

function updateUiSettingsStatus() {
  const status = document.getElementById("ui-settings-status");
  if (!status || !window.kkrepoI18n) return;
  const defaultLanguage = window.kkrepoI18n.defaultLanguage();
  const currentLanguage = window.kkrepoI18n.currentLanguage();
  status.textContent = `Default language: ${uiLanguageLabel(defaultLanguage)}. Active language: ${uiLanguageLabel(currentLanguage)}.`;
}

async function loadUiSettings() {
  if (!window.kkrepoI18n) return;
  await window.kkrepoI18n.ready();
  syncUiSettingsForm();
}

async function saveUiSettings() {
  const button = document.getElementById("save-ui-settings-button");
  const select = document.getElementById("ui-default-language");
  if (!button || !select || !window.kkrepoI18n) return;
  if (!validateRequiredFields(uiSettingsRequiredFields)) return;
  button.disabled = true;
  try {
    await window.kkrepoI18n.saveDefaultLanguage(select.value);
    syncUiSettingsForm();
    showToast("UI language settings saved.", "ok");
  } catch (error) {
    showToast(`Save failed: ${error.message}`, "error");
  } finally {
    button.disabled = false;
  }
}

async function loadSecurityUsers() {
  [securityUsers, securityRoles] = await Promise.all([
    fetchJson("/internal/security/users", [], "Failed to load users"),
    fetchJson("/internal/security/roles", [], "Failed to load roles")
  ]);
  renderSecurityUsers();
  refreshSecurityTransfers(["userRoles"]);
}

function renderSecurityUsers() {
  renderSecurityUserSourceFilter();
  const filter = filterValue("security-user-filter");
  const sourceFilter = document.getElementById("security-user-source-filter")?.value || "";
  const rows = securityUsers.filter((user) =>
    (!sourceFilter || user.source === sourceFilter)
    && listContainsFilter([displaySource(user.source), user.source, user.userId, user.status, user.email, ...(user.roles || [])], filter))
    .map((user) => `
      <tr>
        <td>${escapeHtml(displaySource(user.source))}</td>
        <td>${escapeHtml(user.userId)}</td>
        <td><span class="state-badge compact ${user.status === "ACTIVE" ? "ok" : "warn"}">${escapeHtml(user.status || "")}</span></td>
        <td>${escapeHtml(user.email || "")}</td>
        <td>${escapeHtml((user.roles || []).join(", "))}</td>
        <td class="actions-column">
          <button class="row-action edit-security-user-button" data-source="${escapeHtml(user.source)}" data-id="${escapeHtml(user.userId)}" type="button">edit</button>
          <button class="row-action delete-security-user-button" data-source="${escapeHtml(user.source)}" data-id="${escapeHtml(user.userId)}" type="button">delete</button>
        </td>
      </tr>
    `).join("");
  document.getElementById("security-user-table").innerHTML = rows
    || '<tr><td colspan="6" class="placeholder">No users.</td></tr>';
}

function showSecurityUserForm(user = null) {
  securityUserMode = user ? "edit" : "create";
  document.getElementById("security-user-form-title").textContent = user ? `Edit user: ${user.userId}` : "Create user";
  ensureUserSourceOption(user?.source || "Local");
  document.getElementById("security-user-source").value = user?.source || "Local";
  document.getElementById("security-user-id").value = user?.userId || "";
  document.getElementById("security-user-source").disabled = Boolean(user);
  document.getElementById("security-user-id").disabled = Boolean(user);
  document.getElementById("security-user-first-name").value = user?.firstName || "";
  document.getElementById("security-user-last-name").value = user?.lastName || "";
  document.getElementById("security-user-email").value = user?.email || "";
  document.getElementById("security-user-status").value = user?.status || "ACTIVE";
  document.getElementById("security-user-password").value = "";
  setSecurityTransferSelection("userRoles", user?.roles || []);
  clearRequiredFieldErrors(securityUserRequiredFields);
  openFormModal("security-user-form", user ? "security-user-first-name" : "security-user-id");
}

function hideSecurityUserForm() {
  closeFormModal("security-user-form");
  document.getElementById("security-user-source").disabled = false;
  document.getElementById("security-user-id").disabled = false;
  clearRequiredFieldErrors(securityUserRequiredFields);
}

async function saveSecurityUser() {
  if (!validateRequiredFields(securityUserRequiredFields)) return;
  const payload = {
    source: document.getElementById("security-user-source").value.trim() || "Local",
    userId: document.getElementById("security-user-id").value.trim(),
    firstName: document.getElementById("security-user-first-name").value.trim() || null,
    lastName: document.getElementById("security-user-last-name").value.trim() || null,
    email: document.getElementById("security-user-email").value.trim() || null,
    status: document.getElementById("security-user-status").value,
    password: document.getElementById("security-user-password").value || null,
    roles: commaList(document.getElementById("security-user-roles").value)
  };
  const path = securityUserMode === "edit"
    ? `/internal/security/users/${encodeURIComponent(payload.source)}/${encodeURIComponent(payload.userId)}`
    : "/internal/security/users";
  try {
    const response = await fetch(path, {
      method: securityUserMode === "edit" ? "PUT" : "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(payload)
    });
    if (!response.ok) throw new Error(await responseErrorMessage(response));
    hideSecurityUserForm();
    await loadSecurityUsers();
    showToast("User saved.", "ok");
  } catch (error) {
    showToast(`Save failed: ${error.message}`, "error");
  }
}

async function deleteSecurityUser(source, userId) {
  if (!confirm(`Delete user "${displaySource(source)}/${userId}"?`)) return;
  try {
    const response = await fetch(`/internal/security/users/${encodeURIComponent(source)}/${encodeURIComponent(userId)}`, {
      method: "DELETE"
    });
    if (!response.ok) throw new Error(await responseErrorMessage(response));
    await loadSecurityUsers();
    showToast("User deleted.", "ok");
  } catch (error) {
    showToast(`Delete failed: ${error.message}`, "error");
  }
}

async function loadSecurityRoles() {
  [securityRoles, securityPrivileges] = await Promise.all([
    fetchJson("/internal/security/roles", [], "Failed to load roles"),
    fetchJson("/internal/security/privileges", [], "Failed to load privileges")
  ]);
  renderSecurityRoles();
  refreshSecurityTransfers(["rolePrivileges", "roleRoles"]);
}

function renderSecurityRoles() {
  const filter = filterValue("security-role-filter");
  const rows = securityRoles.filter((role) =>
    listContainsFilter([role.roleId, role.name, role.description], filter))
    .map((role) => `
      <tr>
        <td>${escapeHtml(role.roleId)}${role.readOnly ? ' <span class="state-badge compact">read-only</span>' : ""}</td>
        <td>${escapeHtml(role.name || "")}</td>
        <td>${escapeHtml(role.description || "")}</td>
        <td class="actions-column">
          ${role.readOnly ? `
            <button class="row-action view-security-role-button" data-id="${escapeHtml(role.roleId)}" type="button">view</button>
          ` : `
            <button class="row-action edit-security-role-button" data-id="${escapeHtml(role.roleId)}" type="button">edit</button>
            <button class="row-action delete-security-role-button" data-id="${escapeHtml(role.roleId)}" type="button">delete</button>
          `}
        </td>
      </tr>
    `).join("");
  document.getElementById("security-role-table").innerHTML = rows
    || '<tr><td colspan="4" class="placeholder">No roles.</td></tr>';
}

function showSecurityRoleForm(role = null, options = {}) {
  const viewOnly = Boolean(options.viewOnly);
  securityRoleMode = viewOnly ? "view" : role ? "edit" : "create";
  const roleId = role?.roleId || "";
  const readOnlyField = document.getElementById("security-role-readonly-field");
  const showReadOnlyField = isBuiltInReadOnlyRoleId(roleId);
  document.getElementById("security-role-form-title").textContent = viewOnly
    ? `View role: ${role.roleId}`
    : role ? `Edit role: ${role.roleId}` : "Create role";
  document.getElementById("security-role-id").value = roleId;
  document.getElementById("security-role-id").disabled = Boolean(role);
  document.getElementById("security-role-name").value = role?.name || "";
  document.getElementById("security-role-description").value = role?.description || "";
  readOnlyField.hidden = !showReadOnlyField;
  document.getElementById("security-role-readonly").checked = showReadOnlyField;
  setSecurityTransferSelection("rolePrivileges", role?.privileges || []);
  setSecurityTransferSelection("roleRoles", role?.roles || []);
  setSecurityRoleFormReadOnly(viewOnly);
  clearRequiredFieldErrors(securityRoleRequiredFields);
  openFormModal("security-role-form", viewOnly ? "cancel-security-role-button" : role ? "security-role-name" : "security-role-id");
}

function hideSecurityRoleForm() {
  closeFormModal("security-role-form");
  securityRoleMode = "create";
  setSecurityRoleFormReadOnly(false);
  document.getElementById("security-role-id").disabled = false;
  clearRequiredFieldErrors(securityRoleRequiredFields);
}

function setSecurityRoleFormReadOnly(readOnly) {
  const form = document.getElementById("security-role-form");
  const saveButton = document.getElementById("save-security-role-button");
  const cancelButton = document.getElementById("cancel-security-role-button");
  form.classList.toggle("is-readonly", readOnly);
  document.getElementById("security-role-name").disabled = readOnly;
  document.getElementById("security-role-description").disabled = readOnly;
  document.getElementById("security-role-id").disabled = readOnly || securityRoleMode !== "create";
  saveButton.hidden = readOnly;
  cancelButton.textContent = readOnly ? "Close" : "Cancel";
  ["role-privilege", "role-contained"].forEach((prefix) => {
    document.getElementById(`${prefix}-transfer`)?.classList.toggle("is-readonly", readOnly);
    const filter = document.getElementById(`${prefix}-filter`);
    if (filter) filter.disabled = readOnly;
  });
  refreshSecurityTransfers(["rolePrivileges", "roleRoles"]);
}

async function saveSecurityRole() {
  if (securityRoleMode === "view") return;
  if (!validateRequiredFields(securityRoleRequiredFields)) return;
  const payload = {
    roleId: document.getElementById("security-role-id").value.trim(),
    name: document.getElementById("security-role-name").value.trim() || null,
    description: document.getElementById("security-role-description").value.trim() || null,
    readOnly: isBuiltInReadOnlyRoleId(document.getElementById("security-role-id").value),
    privileges: commaList(document.getElementById("security-role-privileges").value),
    roles: commaList(document.getElementById("security-role-roles").value)
  };
  const path = securityRoleMode === "edit"
    ? `/internal/security/roles/${encodeURIComponent(payload.roleId)}`
    : "/internal/security/roles";
  try {
    const response = await fetch(path, {
      method: securityRoleMode === "edit" ? "PUT" : "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(payload)
    });
    if (!response.ok) throw new Error(await responseErrorMessage(response));
    hideSecurityRoleForm();
    await loadSecurityRoles();
    showToast("Role saved.", "ok");
  } catch (error) {
    showToast(`Save failed: ${error.message}`, "error");
  }
}

async function deleteSecurityRole(roleId) {
  if (!confirm(`Delete role "${roleId}"?`)) return;
  try {
    const response = await fetch(`/internal/security/roles/${encodeURIComponent(roleId)}`, { method: "DELETE" });
    if (!response.ok) throw new Error(await responseErrorMessage(response));
    await loadSecurityRoles();
    showToast("Role deleted.", "ok");
  } catch (error) {
    showToast(`Delete failed: ${error.message}`, "error");
  }
}

async function loadSecurityPrivileges() {
  securityPrivileges = await fetchJson("/internal/security/privileges", [], "Failed to load privileges");
  renderSecurityPrivileges();
}

function renderSecurityPrivileges() {
  const filter = filterValue("security-privilege-filter");
  const rows = securityPrivileges.filter((privilege) =>
    listContainsFilter([privilege.privilegeId, privilege.type, privilege.name, privilege.description, privilege.permission], filter))
    .map((privilege) => `
      <tr>
        <td>${escapeHtml(privilege.privilegeId)}${privilege.readOnly ? ' <span class="state-badge compact">read-only</span>' : ""}</td>
        <td>${escapeHtml(privilege.type)}</td>
        <td>${escapeHtml(privilege.name || "")}</td>
        <td><code>${escapeHtml(privilege.permission || "")}</code></td>
        <td class="actions-column">
          ${privilege.readOnly ? "" : `
            <button class="row-action edit-security-privilege-button" data-id="${escapeHtml(privilege.privilegeId)}" type="button">edit</button>
            <button class="row-action delete-security-privilege-button" data-id="${escapeHtml(privilege.privilegeId)}" type="button">delete</button>
          `}
        </td>
      </tr>
    `).join("");
  document.getElementById("security-privilege-table").innerHTML = rows
    || '<tr><td colspan="5" class="placeholder">No privileges.</td></tr>';
}

function showSecurityPrivilegeForm(privilege = null) {
  securityPrivilegeMode = privilege ? "edit" : "create";
  document.getElementById("security-privilege-form-title").textContent = privilege ? `Edit privilege: ${privilege.privilegeId}` : "Create privilege";
  document.getElementById("security-privilege-id").value = privilege?.privilegeId || "";
  document.getElementById("security-privilege-id").disabled = Boolean(privilege);
  document.getElementById("security-privilege-name").value = privilege?.name || "";
  document.getElementById("security-privilege-type").value = privilege?.type || "wildcard";
  document.getElementById("security-privilege-description").value = privilege?.description || "";
  document.getElementById("security-privilege-readonly").checked = Boolean(privilege?.readOnly);
  document.getElementById("security-privilege-properties").value = JSON.stringify(privilege?.properties || { pattern: "nexus:*" }, null, 2);
  clearRequiredFieldErrors(securityPrivilegeRequiredFields);
  openFormModal("security-privilege-form", privilege ? "security-privilege-name" : "security-privilege-id");
}

function hideSecurityPrivilegeForm() {
  closeFormModal("security-privilege-form");
  document.getElementById("security-privilege-id").disabled = false;
  clearRequiredFieldErrors(securityPrivilegeRequiredFields);
}

async function saveSecurityPrivilege() {
  if (!validateRequiredFields(securityPrivilegeRequiredFields)) return;
  let properties;
  try {
    properties = parseJsonObject("security-privilege-properties");
  } catch (_) {
    return;
  }
  const payload = {
    privilegeId: document.getElementById("security-privilege-id").value.trim(),
    name: document.getElementById("security-privilege-name").value.trim() || null,
    type: document.getElementById("security-privilege-type").value,
    description: document.getElementById("security-privilege-description").value.trim() || null,
    readOnly: document.getElementById("security-privilege-readonly").checked,
    properties
  };
  const path = securityPrivilegeMode === "edit"
    ? `/internal/security/privileges/${encodeURIComponent(payload.privilegeId)}`
    : "/internal/security/privileges";
  try {
    const response = await fetch(path, {
      method: securityPrivilegeMode === "edit" ? "PUT" : "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(payload)
    });
    if (!response.ok) throw new Error(await responseErrorMessage(response));
    hideSecurityPrivilegeForm();
    await loadSecurityPrivileges();
    showToast("Privilege saved.", "ok");
  } catch (error) {
    showToast(`Save failed: ${error.message}`, "error");
  }
}

async function deleteSecurityPrivilege(privilegeId) {
  if (!confirm(`Delete privilege "${privilegeId}"?`)) return;
  try {
    const response = await fetch(`/internal/security/privileges/${encodeURIComponent(privilegeId)}`, { method: "DELETE" });
    if (!response.ok) throw new Error(await responseErrorMessage(response));
    await loadSecurityPrivileges();
    showToast("Privilege deleted.", "ok");
  } catch (error) {
    showToast(`Delete failed: ${error.message}`, "error");
  }
}

async function loadSecurityRealms() {
  securityRealms = await fetchJson("/internal/security/realms", [], "Failed to load realms");
  renderSecurityRealms();
}

function renderSecurityRealms() {
  document.getElementById("security-realm-table").innerHTML = securityRealms.map((realm) => `
    <tr data-realm-id="${escapeHtml(realm.realmId)}">
      <td><input class="security-realm-enabled" type="checkbox"${realm.enabled || realm.realmId === "local" ? " checked" : ""}${realm.realmId === "local" ? ' disabled title="Local realm is required."' : ""}></td>
      <td><input class="security-realm-priority" type="number" value="${Number(realm.priority) || 0}"></td>
      <td>${escapeHtml(realm.name)} <code>${escapeHtml(realm.realmId)}</code></td>
      <td>${escapeHtml(realm.type)}</td>
      <td>${escapeHtml(realm.attributes?.source || "")}</td>
    </tr>
  `).join("");
}

async function saveSecurityRealms() {
  const commands = Array.from(document.querySelectorAll("#security-realm-table tr")).map((row) => {
    const realm = securityRealms.find((item) => item.realmId === row.dataset.realmId);
    return {
      realmId: row.dataset.realmId,
      type: realm?.type,
      name: realm?.name,
      enabled: row.dataset.realmId === "local" || row.querySelector(".security-realm-enabled").checked,
      priority: Number(row.querySelector(".security-realm-priority").value || 0),
      attributes: realm?.attributes || {}
    };
  });
  try {
    const response = await fetch("/internal/security/realms", {
      method: "PUT",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(commands)
    });
    if (!response.ok) throw new Error(await responseErrorMessage(response));
    securityRealms = await response.json();
    renderSecurityRealms();
    showToast("Realms saved.", "ok");
  } catch (error) {
    showToast(`Save failed: ${error.message}`, "error");
  }
}

async function loadSecurityLdap() {
  securityLdap = await fetchJson(
    "/internal/security/ldap",
    {
      enabled: false,
      priority: 10,
      source: "LDAP",
      name: "LDAP",
      protocol: "ldap",
      authScheme: "simple",
      connectionTimeout: 30,
      userSubtree: true,
      userObjectClass: "inetOrgPerson",
      userIdAttribute: "uid",
      userRealNameAttribute: "cn",
      userMemberOfAttribute: "memberOf",
      userEmailAddressAttribute: "mail",
      userPasswordAttribute: "userPassword",
      ldapGroupsAsRoles: true,
      groupType: "static",
      groupSubtree: true,
      groupIdAttribute: "cn",
      groupMemberAttribute: "member",
      groupMemberFormat: "${dn}",
      groupObjectClass: "groupOfNames",
      attributes: {}
    },
    "Failed to load LDAP settings");
  renderSecurityLdap();
}

function renderSecurityLdap() {
  const settings = securityLdap || {};
  clearRequiredFieldErrors(ldapRequiredFields);
  setCheckboxValue("security-ldap-enabled", settings.enabled);
  setInputValue("security-ldap-priority", Number(settings.priority ?? 10));
  setInputValue("security-ldap-source", settings.source, "LDAP");
  setInputValue("security-ldap-name", settings.name, "LDAP");
  setSelectValue("security-ldap-protocol", settings.protocol, "ldap");
  setInputValue("security-ldap-host", settings.host);
  setInputValue("security-ldap-port", settings.port);
  setCheckboxValue("security-ldap-trust-store", settings.useTrustStore);
  setInputValue("security-ldap-url", settings.url);
  setInputValue("security-ldap-search-base", settings.searchBase);
  setInputValue("security-ldap-auth-scheme", settings.authScheme, "simple");
  setInputValue("security-ldap-auth-realm", settings.authRealm);
  setInputValue("security-ldap-auth-username", settings.authUsername);
  setInputValue("security-ldap-auth-password", settings.authPassword);
  setInputValue("security-ldap-connection-timeout", Number(settings.connectionTimeout ?? 30));
  setInputValue("security-ldap-retry-delay", settings.connectionRetryDelay);
  setInputValue("security-ldap-max-incidents", settings.maxIncidentsCount);
  setInputValue("security-ldap-user-base-dn", settings.userBaseDn);
  setCheckboxValue("security-ldap-user-subtree", settings.userSubtree, true);
  setInputValue("security-ldap-user-object-class", settings.userObjectClass, "inetOrgPerson");
  setInputValue("security-ldap-user-filter", settings.userLdapFilter);
  setInputValue("security-ldap-user-id-attribute", settings.userIdAttribute, "uid");
  setInputValue("security-ldap-user-real-name-attribute", settings.userRealNameAttribute, "cn");
  setInputValue("security-ldap-user-member-of-attribute", settings.userMemberOfAttribute, "memberOf");
  setInputValue("security-ldap-user-email-attribute", settings.userEmailAddressAttribute, "mail");
  setInputValue("security-ldap-user-password-attribute", settings.userPasswordAttribute, "userPassword");
  setCheckboxValue("security-ldap-groups-as-roles", settings.ldapGroupsAsRoles, true);
  setSelectValue("security-ldap-group-type", settings.groupType, "static");
  setInputValue("security-ldap-group-base-dn", settings.groupBaseDn);
  setCheckboxValue("security-ldap-group-subtree", settings.groupSubtree, true);
  setInputValue("security-ldap-group-id-attribute", settings.groupIdAttribute, "cn");
  setInputValue("security-ldap-group-member-attribute", settings.groupMemberAttribute, "member");
  setInputValue("security-ldap-group-member-format", settings.groupMemberFormat, "${dn}");
  setInputValue("security-ldap-group-object-class", settings.groupObjectClass, "groupOfNames");
  document.getElementById("security-ldap-attributes").value = JSON.stringify(settings.attributes || {}, null, 2);
  refreshSecurityLdapRequiredMarkers();
}

function refreshSecurityLdapRequiredMarkers() {
  updateRequiredMarkers(ldapRequiredFields);
  const required = document.getElementById("security-ldap-enabled").checked;
  setFieldRequired(document.getElementById("security-ldap-url"), false);
  setFieldRequired(document.getElementById("security-ldap-host"), false);
  document.getElementById("security-ldap-url").setAttribute("aria-required", String(required));
  document.getElementById("security-ldap-host").setAttribute("aria-required", String(required));
  if (!required) {
    clearRequiredFieldErrors(ldapRequiredFields);
  }
}

function clearSecurityLdapRequiredErrors() {
  if (textInputValue("security-ldap-url") || textInputValue("security-ldap-host")) {
    clearRequiredFieldErrors(ldapRequiredFields);
  }
}

function validateSecurityLdapRequiredFields() {
  refreshSecurityLdapRequiredMarkers();
  const enabled = document.getElementById("security-ldap-enabled").checked;
  const url = textInputValue("security-ldap-url");
  const host = textInputValue("security-ldap-host");
  const invalid = enabled && !url && !host;
  markInputValidity(document.getElementById("security-ldap-url"), invalid);
  markInputValidity(document.getElementById("security-ldap-host"), invalid);
  if (invalid) {
    showToast("LDAP is enabled but URL or Host is required.", "error");
    document.getElementById("security-ldap-url").focus();
    return false;
  }
  return true;
}

async function saveSecurityLdap() {
  if (!validateSecurityLdapRequiredFields()) {
    return;
  }
  let attributes;
  try {
    attributes = parseJsonObject("security-ldap-attributes");
  } catch (_) {
    return;
  }
  const url = textInputValue("security-ldap-url");
  const host = textInputValue("security-ldap-host");
  const payload = {
    enabled: document.getElementById("security-ldap-enabled").checked,
    priority: numberInputValue("security-ldap-priority") ?? 10,
    source: textInputValue("security-ldap-source") || "LDAP",
    name: textInputValue("security-ldap-name") || "LDAP",
    url,
    protocol: textInputValue("security-ldap-protocol") || "ldap",
    host,
    port: numberInputValue("security-ldap-port"),
    useTrustStore: document.getElementById("security-ldap-trust-store").checked,
    searchBase: textInputValue("security-ldap-search-base"),
    authScheme: textInputValue("security-ldap-auth-scheme"),
    authRealm: textInputValue("security-ldap-auth-realm"),
    authUsername: textInputValue("security-ldap-auth-username"),
    authPassword: textInputValue("security-ldap-auth-password"),
    connectionTimeout: numberInputValue("security-ldap-connection-timeout"),
    connectionRetryDelay: numberInputValue("security-ldap-retry-delay"),
    maxIncidentsCount: numberInputValue("security-ldap-max-incidents"),
    userBaseDn: textInputValue("security-ldap-user-base-dn"),
    userSubtree: document.getElementById("security-ldap-user-subtree").checked,
    userObjectClass: textInputValue("security-ldap-user-object-class"),
    userLdapFilter: textInputValue("security-ldap-user-filter"),
    userIdAttribute: textInputValue("security-ldap-user-id-attribute"),
    userRealNameAttribute: textInputValue("security-ldap-user-real-name-attribute"),
    userMemberOfAttribute: textInputValue("security-ldap-user-member-of-attribute"),
    userEmailAddressAttribute: textInputValue("security-ldap-user-email-attribute"),
    userPasswordAttribute: textInputValue("security-ldap-user-password-attribute"),
    ldapGroupsAsRoles: document.getElementById("security-ldap-groups-as-roles").checked,
    groupType: textInputValue("security-ldap-group-type"),
    groupBaseDn: textInputValue("security-ldap-group-base-dn"),
    groupSubtree: document.getElementById("security-ldap-group-subtree").checked,
    groupIdAttribute: textInputValue("security-ldap-group-id-attribute"),
    groupMemberAttribute: textInputValue("security-ldap-group-member-attribute"),
    groupMemberFormat: textInputValue("security-ldap-group-member-format"),
    groupObjectClass: textInputValue("security-ldap-group-object-class"),
    attributes
  };
  try {
    const response = await fetch("/internal/security/ldap", {
      method: "PUT",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(payload)
    });
    if (!response.ok) throw new Error(await responseErrorMessage(response));
    securityLdap = await response.json();
    renderSecurityLdap();
    securityRealms = await fetchJson("/internal/security/realms", [], "Failed to refresh realms");
    showToast("LDAP settings saved.", "ok");
  } catch (error) {
    showToast(`Save failed: ${error.message}`, "error");
  }
}

async function loadSecurityOidc() {
  securityOidc = await fetchJson(
    "/internal/security/oidc",
    {
      enabled: false,
      priority: 20,
      source: "OIDC",
      userIdClaim: "preferred_username",
      firstNameClaim: "given_name",
      lastNameClaim: "family_name",
      emailClaim: "email",
      groupsClaim: "groups",
      rolesClaim: "roles",
      clockSkewSeconds: 60,
      jwksCacheSeconds: 300,
      attributes: {}
    },
    "Failed to load OIDC settings");
  renderSecurityOidc();
}

function renderSecurityOidc() {
  const settings = securityOidc || {};
  oidcRequiredFields.forEach((field) => {
    markInputValidity(document.getElementById(field.id), false);
  });
  document.getElementById("security-oidc-enabled").checked = Boolean(settings.enabled);
  document.getElementById("security-oidc-priority").value = Number(settings.priority ?? 20);
  document.getElementById("security-oidc-source").value = settings.source || "OIDC";
  document.getElementById("security-oidc-issuer").value = settings.issuerUri || settings.issuer || "";
  document.getElementById("security-oidc-jwks-uri").value = settings.jwksUri || "";
  document.getElementById("security-oidc-audience").value = settings.audience || "";
  document.getElementById("security-oidc-client-id").value = settings.clientId || "";
  document.getElementById("security-oidc-client-secret").value = settings.clientSecret || "";
  document.getElementById("security-oidc-authorization-endpoint").value = settings.authorizationEndpoint || "";
  document.getElementById("security-oidc-token-endpoint").value = settings.tokenEndpoint || "";
  document.getElementById("security-oidc-redirect-uri").value = settings.redirectUri || "";
  document.getElementById("security-oidc-scopes").value = settings.scopes || "openid profile email";
  document.getElementById("security-oidc-user-id-claim").value = settings.userIdClaim || "preferred_username";
  document.getElementById("security-oidc-first-name-claim").value = settings.firstNameClaim || "given_name";
  document.getElementById("security-oidc-last-name-claim").value = settings.lastNameClaim || "family_name";
  document.getElementById("security-oidc-email-claim").value = settings.emailClaim || "email";
  document.getElementById("security-oidc-groups-claim").value = settings.groupsClaim || "groups";
  document.getElementById("security-oidc-roles-claim").value = settings.rolesClaim || "roles";
  document.getElementById("security-oidc-clock-skew").value = Number(settings.clockSkewSeconds ?? 60);
  document.getElementById("security-oidc-jwks-cache").value = Number(settings.jwksCacheSeconds ?? 300);
  document.getElementById("security-oidc-attributes").value = JSON.stringify(settings.attributes || {}, null, 2);
  refreshSecurityOidcRequiredMarkers();
}

function refreshSecurityOidcRequiredMarkers() {
  const enabled = document.getElementById("security-oidc-enabled").checked;
  oidcRequiredFields.forEach((field) => {
    const input = document.getElementById(field.id);
    setFieldRequired(input, enabled);
    updateRequiredMarker({
      ...field,
      required: () => enabled
    });
  });
  if (!enabled) {
    clearRequiredFieldErrors(oidcRequiredFields);
  }
}

function validateSecurityOidcRequiredFields() {
  const enabled = document.getElementById("security-oidc-enabled").checked;
  const missing = [];
  oidcRequiredFields.forEach((field) => {
    const input = document.getElementById(field.id);
    const invalid = enabled && !input.value.trim();
    markInputValidity(input, invalid);
    if (invalid) {
      missing.push(field.label);
    }
  });
  refreshSecurityOidcRequiredMarkers();
  if (missing.length) {
    showToast(`OIDC is enabled but required fields are missing: ${missing.join(", ")}`, "error");
    document.getElementById(oidcRequiredFields.find((field) => {
      const input = document.getElementById(field.id);
      return input.classList.contains("is-invalid");
    }).id).focus();
    return false;
  }
  return true;
}

async function saveSecurityOidc() {
  if (!validateSecurityOidcRequiredFields()) {
    return;
  }
  let attributes;
  try {
    attributes = parseJsonObject("security-oidc-attributes");
  } catch (_) {
    return;
  }
  const jwksUri = document.getElementById("security-oidc-jwks-uri").value.trim();
  const payload = {
    enabled: document.getElementById("security-oidc-enabled").checked,
    priority: Number(document.getElementById("security-oidc-priority").value || 20),
    source: document.getElementById("security-oidc-source").value.trim() || "OIDC",
    issuer: document.getElementById("security-oidc-issuer").value.trim() || null,
    issuerUri: document.getElementById("security-oidc-issuer").value.trim() || null,
    jwksUri: jwksUri || null,
    audience: document.getElementById("security-oidc-audience").value.trim() || null,
    clientId: document.getElementById("security-oidc-client-id").value.trim() || null,
    clientSecret: document.getElementById("security-oidc-client-secret").value.trim() || null,
    authorizationEndpoint: document.getElementById("security-oidc-authorization-endpoint").value.trim() || null,
    tokenEndpoint: document.getElementById("security-oidc-token-endpoint").value.trim() || null,
    redirectUri: document.getElementById("security-oidc-redirect-uri").value.trim() || null,
    scopes: document.getElementById("security-oidc-scopes").value.trim() || null,
    userIdClaim: document.getElementById("security-oidc-user-id-claim").value.trim() || null,
    firstNameClaim: document.getElementById("security-oidc-first-name-claim").value.trim() || null,
    lastNameClaim: document.getElementById("security-oidc-last-name-claim").value.trim() || null,
    emailClaim: document.getElementById("security-oidc-email-claim").value.trim() || null,
    groupsClaim: document.getElementById("security-oidc-groups-claim").value.trim() || null,
    rolesClaim: document.getElementById("security-oidc-roles-claim").value.trim() || null,
    clockSkewSeconds: Number(document.getElementById("security-oidc-clock-skew").value || 60),
    jwksCacheSeconds: Number(document.getElementById("security-oidc-jwks-cache").value || 300),
    attributes
  };
  try {
    const response = await fetch("/internal/security/oidc", {
      method: "PUT",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(payload)
    });
    if (!response.ok) throw new Error(await responseErrorMessage(response));
    securityOidc = await response.json();
    renderSecurityOidc();
    securityRealms = await fetchJson("/internal/security/realms", [], "Failed to refresh realms");
    showToast("OIDC settings saved.", "ok");
  } catch (error) {
    showToast(`Save failed: ${error.message}`, "error");
  }
}

async function loadSecurityAnonymous() {
  securityAnonymous = await fetchJson(
    "/internal/security/anonymous",
    { enabled: false, userSource: "Local", userId: "anonymous", realmName: "NexusAuthorizingRealm" },
    "Failed to load anonymous settings");
  renderSecurityAnonymous();
}

function renderSecurityAnonymous() {
  const settings = securityAnonymous || {};
  document.getElementById("security-anonymous-enabled").checked = Boolean(settings.enabled);
  document.getElementById("security-anonymous-source").value = "Local";
  document.getElementById("security-anonymous-user-id").value = settings.userId || "anonymous";
  document.getElementById("security-anonymous-realm-name").value = settings.realmName || "NexusAuthorizingRealm";
}

async function saveSecurityAnonymous() {
  if (!validateRequiredFields(securityAnonymousRequiredFields)) return;
  const payload = {
    enabled: document.getElementById("security-anonymous-enabled").checked,
    userSource: "Local",
    userId: document.getElementById("security-anonymous-user-id").value.trim() || "anonymous",
    realmName: document.getElementById("security-anonymous-realm-name").value.trim() || "NexusAuthorizingRealm"
  };
  try {
    const response = await fetch("/internal/security/anonymous", {
      method: "PUT",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(payload)
    });
    if (!response.ok) throw new Error(await responseErrorMessage(response));
    securityAnonymous = await response.json();
    renderSecurityAnonymous();
    showToast("Anonymous access saved.", "ok");
  } catch (error) {
    showToast(`Save failed: ${error.message}`, "error");
  }
}

async function loadSecurityApiKeys() {
  securityApiKeys = await fetchJson("/internal/security/api-keys", [], "Failed to load API keys");
  renderSecurityApiKeys();
}

function renderSecurityApiKeys() {
  const rows = securityApiKeys.map((key) => `
    <tr>
      <td>${escapeHtml(key.domain)}</td>
      <td>${escapeHtml(key.ownerSource)}/${escapeHtml(key.ownerUserId)}</td>
      <td><span class="state-badge compact ${key.status === "ACTIVE" ? "ok" : "warn"}">${escapeHtml(key.status || "")}</span></td>
      <td><code>${escapeHtml(key.tokenPrefix || "")}</code></td>
      <td>${escapeHtml((key.scopes || []).join(", "))}</td>
      <td class="actions-column"><button class="row-action delete-security-api-key-button" data-id="${key.id}" type="button">delete</button></td>
    </tr>
  `).join("");
  document.getElementById("security-api-key-table").innerHTML = rows
    || '<tr><td colspan="6" class="placeholder">No API keys.</td></tr>';
}

function showSecurityApiKeyForm() {
  document.getElementById("security-api-key-domain").value = "NpmToken";
  document.getElementById("security-api-key-owner-source").value = "Local";
  document.getElementById("security-api-key-owner-user-id").value = "";
  document.getElementById("security-api-key-display-name").value = "";
  document.getElementById("security-api-key-scopes").value = "";
  clearRequiredFieldErrors(securityApiKeyRequiredFields);
  openFormModal("security-api-key-form", "security-api-key-owner-user-id");
}

function hideSecurityApiKeyForm() {
  closeFormModal("security-api-key-form");
  clearRequiredFieldErrors(securityApiKeyRequiredFields);
}

async function saveSecurityApiKey() {
  if (!validateRequiredFields(securityApiKeyRequiredFields)) return;
  const payload = {
    domain: document.getElementById("security-api-key-domain").value.trim() || "NpmToken",
    ownerSource: document.getElementById("security-api-key-owner-source").value.trim() || "Local",
    ownerUserId: document.getElementById("security-api-key-owner-user-id").value.trim(),
    displayName: document.getElementById("security-api-key-display-name").value.trim() || null,
    scopes: commaList(document.getElementById("security-api-key-scopes").value)
  };
  try {
    const response = await fetch("/internal/security/api-keys", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(payload)
    });
    if (!response.ok) throw new Error(await responseErrorMessage(response));
    const created = await response.json();
    hideSecurityApiKeyForm();
    await loadSecurityApiKeys();
    showToast(created.token ? `API key created: ${created.token}` : "API key imported.", "ok");
  } catch (error) {
    showToast(`Create failed: ${error.message}`, "error");
  }
}

async function deleteSecurityApiKey(id) {
  if (!confirm("Delete this API key?")) return;
  try {
    const response = await fetch(`/internal/security/api-keys/${encodeURIComponent(id)}`, { method: "DELETE" });
    if (!response.ok) throw new Error(await responseErrorMessage(response));
    await loadSecurityApiKeys();
    showToast("API key deleted.", "ok");
  } catch (error) {
    showToast(`Delete failed: ${error.message}`, "error");
  }
}

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

function migrationPayload() {
  return {
    sourceBaseUrl: document.getElementById("migration-source-url").value.trim(),
    sourceUsername: document.getElementById("migration-source-username").value.trim(),
    sourcePassword: document.getElementById("migration-source-password").value
  };
}

function renderCompactTable(headers, rows, emptyText = "") {
  if (!rows.length) {
    return emptyText ? `<pre class="code-panel">${escapeHtml(emptyText)}</pre>` : "";
  }
  return `
    <table class="nx-table compact migration-detail-table">
      <thead><tr>${headers.map((header) => `<th>${escapeHtml(header.label)}</th>`).join("")}</tr></thead>
      <tbody>
        ${rows.map((row) => `
          <tr>
            ${headers.map((header) => `<td>${migrationTableCell(header, row)}</td>`).join("")}
          </tr>
        `).join("")}
      </tbody>
    </table>
  `;
}

function migrationTableCell(header, row) {
  if (header.html) {
    return header.html(row);
  }
  return escapeHtml(header.value(row));
}

function renderMigrationSection(title, html) {
  if (!html) return "";
  return `
    <div class="migration-list-title">${escapeHtml(title)}</div>
    ${html}
  `;
}

function renderMigrationList(title, values) {
  const items = values || [];
  if (!items.length) return "";
  return renderMigrationSection(title, `<pre class="code-panel">${escapeHtml(items.join("\\n"))}</pre>`);
}

function migrationValues(...sources) {
  return sources
    .flatMap((source) => Array.isArray(source) ? source : [])
    .filter((value) => value != null && String(value).trim())
    .map((value) => String(value))
    .filter((value, index, values) => values.indexOf(value) === index);
}

function renderMigrationProfile(profile, plan) {
  if (!profile) return "";
  const scriptApi = profile.scriptApi || {};
  const blobModel = profile.blobModel || {};
  return renderMigrationSection("Source profile", renderCompactTable([
    { label: "Version", value: (source) => source.nexusVersion || "" },
    { label: "Metadata", value: (source) => source.metadataEngine || "" },
    { label: "Repository model", value: (source) => source.repositoryModel || "" },
    { label: "Security model", value: (source) => source.securityModel || "" },
    { label: "Script API", value: () => migrationScriptApiSummary(scriptApi) },
    { label: "Script run type", value: () => scriptApi.runContentType || "" },
    { label: "Blob read", value: () => blobModel.readMode || "" },
    { label: "Blob types", value: () => (blobModel.sourceTypes || []).join(", ") || "-" },
    { label: "Profile hash", html: () => renderMigrationHash(plan?.profileHash) }
  ], [profile]));
}

function renderMigrationPlan(plan) {
  if (!plan) return "";
  return renderMigrationSection("Migration plan", renderCompactTable([
    { label: "Adapter", value: (value) => value.adapter || "" },
    { label: "Profile hash", html: (value) => renderMigrationHash(value.profileHash) },
    { label: "Plan hash", html: (value) => renderMigrationHash(value.planHash) },
    { label: "Plan items", value: (value) => (value.items || []).length },
    { label: "Warnings", value: (value) => (value.warnings || []).length },
    { label: "Manual actions", value: (value) => (value.manualActions || []).length }
  ], [plan]));
}

function renderMigrationPlanItems(plan) {
  const items = plan?.items || [];
  return renderMigrationSection("Plan items", renderCompactTable([
    { label: "Area", value: (item) => item.area || "" },
    { label: "Name", value: (item) => item.name || "" },
    { label: "Format", value: (item) => item.format || "" },
    { label: "Type", value: (item) => item.type || "" },
    { label: "Status", html: (item) => migrationPlanStatusBadge(item.status) },
    { label: "Source adapter", value: (item) => item.sourceAdapter || "" },
    { label: "Format adapter", value: (item) => item.formatAdapter || "" },
    { label: "Read mode", value: (item) => item.readMode || "" },
    { label: "Write mode", value: (item) => item.writeMode || "" },
    { label: "Checksum", value: (item) => item.checksumMode || "" },
    { label: "Resume key", value: (item) => item.resumeKey || "" },
    { label: "Reasons", value: (item) => (item.reasons || []).join("; ") || "-" },
    { label: "Warnings", value: (item) => (item.warnings || []).join("; ") || "-" }
  ], items));
}

function migrationScriptApiSummary(scriptApi) {
  const status = scriptApi.status || "unknown";
  const runnable = scriptApi.runnable ? "runnable" : "not runnable";
  const cleanup = scriptApi.deletedAfterProbe ? "deleted" : "not deleted";
  return `${status}; ${runnable}; ${cleanup}`;
}

function migrationPlanStatusBadge(status) {
  const value = String(status || "-");
  const tone = value === "FULL" ? "ok"
    : value === "CONFIG_ONLY" || value === "DATA_ONLY" ? "warn"
      : value === "UNSUPPORTED" || value === "NEEDS_MANUAL_ACTION" ? "bad" : "checking";
  return `<span class="state-badge compact ${tone}">${escapeHtml(value)}</span>`;
}

function renderMigrationHash(value) {
  const hash = String(value || "");
  if (!hash) return '<span class="health-muted">-</span>';
  return `<code class="migration-hash-cell" title="${escapeHtml(hash)}">${escapeHtml(shortText(hash, 18))}</code>`;
}

function renderMigrationResult(payload, title) {
  const result = document.getElementById("migration-result");
  const preflight = payload.preflight || payload;
  const config = payload.config || {};
  const apiSecurity = payload.apiSecurity || null;
  const security = preflight.security || {};
  const blobStorePlans = preflight.blobStorePlans || [];
  const repositoriesToMigrate = preflight.repositoriesToMigrate || [];
  const groupRepositories = preflight.groupRepositories || [];
  const unsupported = preflight.unsupported || [];
  const proxyRisks = preflight.proxyRemoteRisks || [];
  const sourceProfile = preflight.sourceProfile || payload.sourceProfile || null;
  const migrationPlan = preflight.migrationPlan || payload.migrationPlan || null;
  const passwordUsers = payload.passwordResetRequiredUsers || preflight.passwordResetRequiredUsers || [];
  const warnings = migrationValues(preflight.warnings, payload.warnings, migrationPlan?.warnings);
  const validation = payload.validation || {};
  const validationChecks = validation.checks || [];
  const manualActions = migrationValues(validation.manualActions, migrationPlan?.manualActions);
  result.hidden = false;
  result.innerHTML = `
    <div class="form-title">${escapeHtml(title)}</div>
    <div class="summary-grid">
      <div><span>Status</span><strong>${escapeHtml(payload.status || "preflight")}</strong></div>
      <div><span>Plan items</span><strong>${escapeHtml(migrationPlan?.items?.length ?? 0)}</strong></div>
      <div><span>Blob stores</span><strong>${escapeHtml(config.blobStores ?? preflight.blobStores ?? 0)}</strong></div>
      <div><span>Repositories</span><strong>${escapeHtml(config.repositories ?? preflight.supportedRepositories ?? 0)}</strong></div>
      <div><span>Unsupported</span><strong>${escapeHtml(config.unsupportedRepositories ?? preflight.unsupportedRepositories ?? 0)}</strong></div>
      <div><span>Groups</span><strong>${escapeHtml(config.groupRepositories ?? groupRepositories.length ?? 0)}</strong></div>
      <div><span>Users</span><strong>${escapeHtml(apiSecurity?.users ?? security.users ?? preflight.users ?? 0)}</strong></div>
      <div><span>Roles</span><strong>${escapeHtml(apiSecurity?.roles ?? security.roles ?? 0)}</strong></div>
      <div><span>Privileges</span><strong>${escapeHtml(apiSecurity?.privileges ?? security.privileges ?? 0)}</strong></div>
      <div><span>API keys</span><strong>${escapeHtml(apiSecurity?.apiKeys ?? security.apiKeys ?? 0)}</strong></div>
      <div><span>Password resets</span><strong>${escapeHtml(passwordUsers.length)}</strong></div>
    </div>
    ${renderMigrationProfile(sourceProfile, migrationPlan)}
    ${renderMigrationPlan(migrationPlan)}
    ${renderMigrationPlanItems(migrationPlan)}
    ${renderMigrationList("Warnings", warnings)}
    ${renderMigrationSection("Blob stores", renderCompactTable([
      { label: "Source", value: (store) => store.sourceName || "" },
      { label: "Source type", value: (store) => store.sourceType || "" },
      { label: "Target", value: (store) => store.targetName || "" },
      { label: "Target type", value: (store) => store.targetType || "" },
      { label: "Bucket", value: (store) => store.targetBucket || "" },
      { label: "Prefix", value: (store) => store.targetPrefix || "" }
    ], blobStorePlans, "No source blob stores were reported; the default target blob store will be used."))}
    ${renderMigrationSection("Repositories to migrate", renderCompactTable([
      { label: "Name", value: (repo) => repo.name || "" },
      { label: "Format", value: (repo) => repo.format || "" },
      { label: "Type", value: (repo) => repo.type || "" },
      { label: "Recipe", value: (repo) => repo.recipe || "" },
      { label: "Blob store", value: (repo) => repo.blobStoreName || "" },
      { label: "Online", value: (repo) => repo.online === false ? "false" : "true" },
      { label: "Remote URL", value: (repo) => repo.remoteUrl || "" }
    ], repositoriesToMigrate, "No supported repositories were found in the source inventory."))}
    ${renderMigrationSection("Group members", renderCompactTable([
      { label: "Repository", value: (repo) => repo.repository || "" },
      { label: "Format", value: (repo) => repo.format || "" },
      { label: "Members", value: (repo) => (repo.members || []).join(", ") || "-" }
    ], groupRepositories))}
    ${renderMigrationSection("Unsupported repositories", renderCompactTable([
      { label: "Name", value: (repo) => repo.name || "" },
      { label: "Format", value: (repo) => repo.format || "" },
      { label: "Type", value: (repo) => repo.type || "" },
      { label: "Reason", value: (repo) => repo.reason || "" }
    ], unsupported))}
    ${renderMigrationSection("Proxy remotes", renderCompactTable([
      { label: "Repository", value: (risk) => risk.repository || "" },
      { label: "Format", value: (risk) => risk.format || "" },
      { label: "Remote URL", value: (risk) => risk.remoteUrl || "" },
      { label: "Status", value: (risk) => risk.status || "" }
    ], proxyRisks))}
    ${renderMigrationSection("Security users", renderCompactTable([
      { label: "Source", value: (user) => user.source || "" },
      { label: "User ID", value: (user) => user.userId || "" },
      { label: "Status", value: (user) => user.status || "" },
      { label: "Email", value: (user) => user.email || "" },
      { label: "Password hash", value: (user) => user.passwordHashPresent ? "present" : "missing" }
    ], security.userDetails || []))}
    ${renderMigrationSection("Security roles", renderCompactTable([
      { label: "Role ID", value: (role) => role.id || "" },
      { label: "Source", value: (role) => role.source || "" },
      { label: "Name", value: (role) => role.name || "" },
      { label: "Read only", value: (role) => role.readOnly ? "true" : "false" },
      { label: "Privileges", value: (role) => (role.privileges || []).join(", ") || "-" },
      { label: "Child roles", value: (role) => (role.childRoles || []).join(", ") || "-" }
    ], security.roleDetails || []))}
    ${renderMigrationSection("User role mappings", renderCompactTable([
      { label: "Source", value: (mapping) => mapping.source || "" },
      { label: "User ID", value: (mapping) => mapping.userId || "" },
      { label: "Roles", value: (mapping) => (mapping.roles || []).join(", ") || "-" }
    ], security.userRoleMappingDetails || []))}
    ${renderMigrationSection("API keys", renderCompactTable([
      { label: "Domain", value: (apiKey) => apiKey.domain || "" },
      { label: "Owner source", value: (apiKey) => apiKey.ownerSource || "" },
      { label: "Owner user", value: (apiKey) => apiKey.ownerUserId || "" },
      { label: "Display name", value: (apiKey) => apiKey.displayName || "" },
      { label: "Status", value: (apiKey) => apiKey.status || "" },
      { label: "Raw key", value: (apiKey) => apiKey.rawKeyPresent ? "present" : "missing" }
    ], security.apiKeyDetails || []))}
    ${renderMigrationSection("Content selectors", renderCompactTable([
      { label: "Name", value: (selector) => selector.name || "" },
      { label: "Type", value: (selector) => selector.type || "" },
      { label: "Format", value: (selector) => selector.format || "" },
      { label: "Expression", value: (selector) => selector.expression || "" }
    ], security.contentSelectorDetails || []))}
    ${renderMigrationList("Realm order", security.realmOrder || [])}
    ${security.anonymous ? renderMigrationSection("Anonymous access", renderCompactTable([
      { label: "Enabled", value: (anonymous) => anonymous.enabled ? "true" : "false" },
      { label: "User source", value: (anonymous) => anonymous.userSource || "" },
      { label: "User ID", value: (anonymous) => anonymous.userId || "" },
      { label: "Realm", value: (anonymous) => anonymous.realmName || "" }
    ], [security.anonymous])) : ""}
    ${renderMigrationList("Password reset required", passwordUsers)}
    ${renderMigrationList("Manual actions", manualActions)}
    ${renderMigrationSection("Validation result", renderCompactTable([
      { label: "Scope", value: (check) => check.scope || "" },
      { label: "Check", value: (check) => check.name || "" },
      { label: "Status", value: (check) => check.status || "" },
      { label: "Message", value: (check) => check.message || "" },
      { label: "Details", value: (check) => (check.details || []).join(", ") || "-" }
    ], validationChecks))}
  `;
}

function renderMigrationError(title, message) {
  const result = document.getElementById("migration-result");
  result.hidden = false;
  result.innerHTML = `
    <div class="form-title">${escapeHtml(title)}</div>
    <div class="migration-error-panel">
      <div class="migration-list-title">Error</div>
      <pre class="code-panel">${escapeHtml(message || "Migration request failed.")}</pre>
    </div>
  `;
}

async function runNexusMigrationPreflight() {
  if (!validateRequiredFields(nexusMigrationRequiredFields)) return;
  try {
    showToast("Running preflight...");
    const response = await fetch("/internal/migration/nexus/preflight", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(migrationPayload())
    });
    if (!response.ok) throw new Error(await responseErrorMessage(response));
    renderMigrationResult(await response.json(), "Preflight result");
    showToast("Preflight finished.", "ok");
  } catch (error) {
    renderMigrationError("Preflight failed", error.message);
    showToast(`Preflight failed: ${error.message}`, "error");
  }
}

async function runNexusMigration() {
  if (!validateRequiredFields(nexusMigrationRequiredFields)) return;
  try {
    showToast("Running migration...");
    const response = await fetch("/internal/migration/nexus/run", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(migrationPayload())
    });
    if (!response.ok) throw new Error(await responseErrorMessage(response));
    renderMigrationResult(await response.json(), "Migration result");
    showToast("Migration finished.", "ok");
    await Promise.all([loadBlobStores(), loadRepositories(), loadSecurityUsers()]);
  } catch (error) {
    renderMigrationError("Migration failed", error.message);
    showToast(`Migration failed: ${error.message}`, "error");
  }
}

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
      <div><span>Package progress</span><strong>${escapeHtml(formatPercent(packagePercent))}</strong></div>
    </div>
    <div class="migration-progress-panel">
      <div class="migration-progress-head">
        <span>${escapeHtml(phase)}</span>
        <strong>${escapeHtml(formatPercent(packagePercent))}</strong>
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
  return `
    <div class="migration-progress-track" role="progressbar" aria-valuemin="0" aria-valuemax="100" aria-valuenow="${escapeHtml(percent.toFixed(1))}">
      <span style="width: ${escapeHtml(percent.toFixed(2))}%"></span>
    </div>
  `;
}

function renderCompactProgressBar(done, total) {
  const percent = progressPercent(done, total);
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

function formatDateTime(value) {
  if (!value) return "-";
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return String(value);
  return date.toLocaleString();
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

function shortText(value, max = 64) {
  const text = String(value || "");
  return text.length <= max ? text : `${text.slice(0, max - 1)}…`;
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
  try {
    const response = await fetch("/internal/migration/nexus/repository-data/jobs");
    if (!response.ok) throw new Error(await responseErrorMessage(response));
    renderRepositoryDataMigrationJobs(await response.json());
  } catch (error) {
    renderRepositoryDataMigrationError("Load jobs failed", error.message);
    showToast(`Load jobs failed: ${error.message}`, "error");
  }
}

async function loadRepositoryDataMigrationStatus(jobId = repositoryDataMigrationJobId) {
  if (!jobId) return;
  try {
    const response = await fetch(`/internal/migration/nexus/repository-data/jobs/${jobId}`);
    if (!response.ok) throw new Error(await responseErrorMessage(response));
    const payload = await response.json();
    renderRepositoryDataMigrationStatus(payload);
  } catch (error) {
    renderRepositoryDataMigrationError("Load status failed", error.message);
  }
}

function startRepositoryDataMigrationPolling(jobId) {
  repositoryDataMigrationJobId = jobId || repositoryDataMigrationJobId;
  clearInterval(repositoryDataMigrationPollTimer);
  if (!repositoryDataMigrationJobId) return;
  repositoryDataMigrationPollTimer = setInterval(
      () => loadRepositoryDataMigrationStatus(repositoryDataMigrationJobId),
      3000);
}

function switchView(view, options = {}) {
  if (!document.getElementById(`${view}-view`)) return false;
  if (options.updateHash !== false) {
    updateHashForView(view, Boolean(options.replaceHash));
  }
  document.querySelectorAll(".view").forEach((item) => {
    item.classList.toggle("is-active", item.id === `${view}-view`);
  });
  document.querySelectorAll(".side-item").forEach((item) => {
    item.classList.toggle("is-active", item.dataset.view === view);
  });
  updateCurrentSideGroup(view);
  if (view === "blobstores") {
    loadBlobStores({ autoCheck: true });
  }
  if (view === "repositories") {
    loadRepositories();
  }
  if (view === "docker-registry") loadDockerOperations();
  if (view === "security-users") loadSecurityUsers();
  if (view === "security-roles") loadSecurityRoles();
  if (view === "security-privileges") loadSecurityPrivileges();
  if (view === "security-realms") loadSecurityRealms();
  if (view === "security-ldap") loadSecurityLdap();
  if (view === "security-oidc") loadSecurityOidc();
  if (view === "security-anonymous") loadSecurityAnonymous();
  if (view === "security-api-keys") loadSecurityApiKeys();
  if (view === "security-audit-log") loadAuditLogs(0);
  if (view === "ui-settings") loadUiSettings();
  if (view === "repository-data-migration") loadRepositoryDataMigrationJobs();
  return true;
}

function applyHashRoute() {
  const view = viewFromHash();
  if (!view) return false;
  return switchView(view, { updateHash: false });
}

applyOriginAwarePlaceholders();
initializeSideGroups();
[
  ["repository-form", hideRepositoryForm],
  ["blobstore-form", hideBlobStoreForm],
  ["security-user-form", hideSecurityUserForm],
  ["security-role-form", hideSecurityRoleForm],
  ["security-privilege-form", hideSecurityPrivilegeForm],
  ["security-api-key-form", hideSecurityApiKeyForm]
].forEach(([formId, handler]) => bindFormModalDismiss(formId, handler));

document.querySelectorAll(".side-item[data-view]").forEach((item) => {
  item.addEventListener("click", () => switchView(item.dataset.view));
});

window.addEventListener("hashchange", applyHashRoute);
window.addEventListener("popstate", applyHashRoute);

document.getElementById("repository-filter").addEventListener("input", renderRepositories);
document.addEventListener("click", (event) => {
  const sortButton = event.target.closest("[data-repository-sort]");
  if (!sortButton) return;
  toggleRepositorySort(sortButton.dataset.repositorySort);
});
document.getElementById("blobstore-filter").addEventListener("input", renderBlobStores);
document.getElementById("user-menu").addEventListener("mouseenter", openUserMenu);
document.getElementById("user-menu").addEventListener("mouseleave", scheduleCloseUserMenu);
document.getElementById("user-menu-trigger").addEventListener("click", (event) => {
  event.stopPropagation();
  toggleUserMenu();
});
document.getElementById("signout-button").addEventListener("click", () => {
  closeUserMenu();
  sessionStorage.removeItem(AUTH_SNAPSHOT_KEY);
  window.location.href = `/internal/security/logout?returnTo=${encodeURIComponent("/browse/#browse/welcome")}`;
});
document.addEventListener("click", (event) => {
  const dismissButton = event.target.closest("[data-modal-dismiss]");
  if (!dismissButton) return;
  event.preventDefault();
  dismissFormModal(dismissButton.dataset.modalDismiss);
});
document.addEventListener("click", (event) => {
  if (!document.getElementById("user-menu").contains(event.target)) closeUserMenu();
});
document.addEventListener("keydown", (event) => {
  if (handleFormModalKeydown(event)) return;
  if (event.key === "Escape") closeUserMenu();
});
document.getElementById("create-blobstore-button").addEventListener("click", showCreateBlobStoreForm);
document.getElementById("cancel-blobstore-button").addEventListener("click", hideBlobStoreForm);
document.getElementById("blobstore-form").addEventListener("submit", saveBlobStore);
document.getElementById("save-blobstore-button").addEventListener("click", saveBlobStore);
document.getElementById("blobstore-engine").addEventListener("change", refreshBlobStoreEngineControls);
blobStoreFormFields.forEach((field) => {
  document.getElementById(field.id).addEventListener("input", clearBlobStoreFieldError);
});
document.getElementById("blobstore-table").addEventListener("click", (event) => {
  const editButton = event.target.closest(".edit-blobstore-button");
  if (editButton) {
    showEditBlobStoreForm(editButton.dataset.id);
    return;
  }
  const checkButton = event.target.closest(".check-blobstore-button");
  if (!checkButton) return;
  checkBlobStore(checkButton.dataset.id, checkButton.dataset.name);
});

document.getElementById("create-repository-button").addEventListener("click", showCreateRepositoryForm);
document.getElementById("cancel-repository-button").addEventListener("click", hideRepositoryForm);
document.getElementById("save-repository-button").addEventListener("click", saveRepository);
document.getElementById("repository-form").addEventListener("submit", (event) => {
  event.preventDefault();
  saveRepository();
});
document.getElementById("repository-recipe").addEventListener("change", refreshRepositoryRecipeControls);
document.getElementById("repository-docker-connector-enabled").addEventListener("change", refreshDockerConnectorControls);
bindRequiredFieldErrors(repositoryRequiredFields);
bindMemberTransferEvents();
bindSecurityTransfers();
document.getElementById("repository-table").addEventListener("click", (event) => {
  const editButton = event.target.closest(".edit-repository-button");
  if (editButton) {
    showEditRepositoryForm(editButton.dataset.name);
    return;
  }
  const deleteButton = event.target.closest(".delete-repository-button");
  if (deleteButton) {
    deleteRepository(deleteButton.dataset.name);
  }
});
document.getElementById("docker-connectors-refresh-button").addEventListener("click", refreshDockerConnectors);
document.getElementById("docker-cache-clear-button").addEventListener("click", clearDockerCache);

document.getElementById("security-user-filter").addEventListener("input", renderSecurityUsers);
document.getElementById("security-user-source-filter").addEventListener("change", renderSecurityUsers);
document.getElementById("create-security-user-button").addEventListener("click", () => showSecurityUserForm());
document.getElementById("cancel-security-user-button").addEventListener("click", hideSecurityUserForm);
document.getElementById("save-security-user-button").addEventListener("click", saveSecurityUser);
document.getElementById("security-user-form").addEventListener("submit", (event) => {
  event.preventDefault();
  saveSecurityUser();
});
bindRequiredFieldErrors(securityUserRequiredFields);
document.getElementById("security-user-table").addEventListener("click", (event) => {
  const editButton = event.target.closest(".edit-security-user-button");
  if (editButton) {
    const user = securityUsers.find((item) => item.source === editButton.dataset.source && item.userId === editButton.dataset.id);
    if (user) showSecurityUserForm(user);
    return;
  }
  const deleteButton = event.target.closest(".delete-security-user-button");
  if (deleteButton) deleteSecurityUser(deleteButton.dataset.source, deleteButton.dataset.id);
});

document.getElementById("security-role-filter").addEventListener("input", renderSecurityRoles);
document.getElementById("create-security-role-button").addEventListener("click", () => showSecurityRoleForm());
document.getElementById("cancel-security-role-button").addEventListener("click", hideSecurityRoleForm);
document.getElementById("save-security-role-button").addEventListener("click", saveSecurityRole);
document.getElementById("security-role-id").addEventListener("input", () => refreshSecurityTransfer("roleRoles"));
document.getElementById("security-role-form").addEventListener("submit", (event) => {
  event.preventDefault();
  saveSecurityRole();
});
bindRequiredFieldErrors(securityRoleRequiredFields);
document.getElementById("security-role-table").addEventListener("click", (event) => {
  const viewButton = event.target.closest(".view-security-role-button");
  if (viewButton) {
    const role = securityRoles.find((item) => item.roleId === viewButton.dataset.id);
    if (role) showSecurityRoleForm(role, { viewOnly: true });
    return;
  }
  const editButton = event.target.closest(".edit-security-role-button");
  if (editButton) {
    const role = securityRoles.find((item) => item.roleId === editButton.dataset.id);
    if (role) showSecurityRoleForm(role);
    return;
  }
  const deleteButton = event.target.closest(".delete-security-role-button");
  if (deleteButton) deleteSecurityRole(deleteButton.dataset.id);
});

document.getElementById("security-privilege-filter").addEventListener("input", renderSecurityPrivileges);
document.getElementById("create-security-privilege-button").addEventListener("click", () => showSecurityPrivilegeForm());
document.getElementById("cancel-security-privilege-button").addEventListener("click", hideSecurityPrivilegeForm);
document.getElementById("save-security-privilege-button").addEventListener("click", saveSecurityPrivilege);
document.getElementById("security-privilege-form").addEventListener("submit", (event) => {
  event.preventDefault();
  saveSecurityPrivilege();
});
bindRequiredFieldErrors(securityPrivilegeRequiredFields);
document.getElementById("security-privilege-table").addEventListener("click", (event) => {
  const editButton = event.target.closest(".edit-security-privilege-button");
  if (editButton) {
    const privilege = securityPrivileges.find((item) => item.privilegeId === editButton.dataset.id);
    if (privilege) showSecurityPrivilegeForm(privilege);
    return;
  }
  const deleteButton = event.target.closest(".delete-security-privilege-button");
  if (deleteButton) deleteSecurityPrivilege(deleteButton.dataset.id);
});

document.getElementById("save-security-realms-button").addEventListener("click", saveSecurityRealms);
document.getElementById("save-security-ldap-button").addEventListener("click", saveSecurityLdap);
document.getElementById("security-ldap-form").addEventListener("submit", (event) => {
  event.preventDefault();
  saveSecurityLdap();
});
document.getElementById("security-ldap-enabled").addEventListener("change", refreshSecurityLdapRequiredMarkers);
ldapRequiredFields.forEach((field) => {
  const input = document.getElementById(field.id);
  input.addEventListener("input", clearSecurityLdapRequiredErrors);
  input.addEventListener("change", clearSecurityLdapRequiredErrors);
});
document.getElementById("save-security-oidc-button").addEventListener("click", saveSecurityOidc);
document.getElementById("security-oidc-form").addEventListener("submit", (event) => {
  event.preventDefault();
  saveSecurityOidc();
});
document.getElementById("security-oidc-enabled").addEventListener("change", refreshSecurityOidcRequiredMarkers);
oidcRequiredFields.forEach((field) => {
  document.getElementById(field.id).addEventListener("input", (event) => {
    markInputValidity(event.target, false);
  });
});
document.getElementById("save-security-anonymous-button").addEventListener("click", saveSecurityAnonymous);
document.getElementById("security-anonymous-form").addEventListener("submit", (event) => {
  event.preventDefault();
  saveSecurityAnonymous();
});
bindRequiredFieldErrors(securityAnonymousRequiredFields);
document.getElementById("save-ui-settings-button").addEventListener("click", saveUiSettings);
document.getElementById("ui-settings-form").addEventListener("submit", (event) => {
  event.preventDefault();
  saveUiSettings();
});
bindRequiredFieldErrors(uiSettingsRequiredFields);
window.addEventListener("kkrepo:i18n-change", syncUiSettingsForm);

document.getElementById("create-security-api-key-button").addEventListener("click", showSecurityApiKeyForm);
document.getElementById("cancel-security-api-key-button").addEventListener("click", hideSecurityApiKeyForm);
document.getElementById("save-security-api-key-button").addEventListener("click", saveSecurityApiKey);
document.getElementById("security-api-key-form").addEventListener("submit", (event) => {
  event.preventDefault();
  saveSecurityApiKey();
});
bindRequiredFieldErrors(securityApiKeyRequiredFields);
document.getElementById("security-api-key-table").addEventListener("click", (event) => {
  const deleteButton = event.target.closest(".delete-security-api-key-button");
  if (deleteButton) deleteSecurityApiKey(deleteButton.dataset.id);
});
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
document.getElementById("nexus-migration-form").addEventListener("submit", (event) => {
  event.preventDefault();
  runNexusMigrationPreflight();
});
bindRequiredFieldErrors(nexusMigrationRequiredFields);
document.getElementById("migration-preflight-button").addEventListener("click", runNexusMigrationPreflight);
document.getElementById("migration-run-button").addEventListener("click", runNexusMigration);
document.getElementById("repository-data-migration-form").addEventListener("submit", (event) => {
  event.preventDefault();
  startRepositoryDataMetadataMigration();
});
bindRequiredFieldErrors(repositoryDataMigrationRequiredFields);
document.getElementById("repository-data-migration-start-button").addEventListener("click", startRepositoryDataMetadataMigration);
document.getElementById("repository-data-migration-metadata-button").addEventListener("click", continueRepositoryDataMetadataMigration);
document.getElementById("repository-data-migration-packages-button").addEventListener("click", startRepositoryDataPackageMigration);
document.getElementById("repository-data-migration-retry-failed-button").addEventListener("click", retryRepositoryDataFailedPackages);
document.getElementById("repository-data-migration-refresh-button").addEventListener("click", loadRepositoryDataMigrationJobs);

loadCurrentSession({ quiet: true }).then((session) => {
  if (!session) return;
  loadRepositoryRecipes().then(() => {
    if (!applyHashRoute()) {
      loadRepositories();
    }
  });
  loadBlobStores();
});
