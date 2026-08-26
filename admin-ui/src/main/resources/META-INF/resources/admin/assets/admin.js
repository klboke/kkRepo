if (window.location.hash === "#browse" || window.location.hash.startsWith("#browse/")) {
  window.location.replace(`/browse/${window.location.hash}`);
}

installCsrfFetch();

let repositories = [];
let repositoryRecipes = [];
let blobStores = [];
let cleanupPolicies = [];
let cleanupCapabilities = [];
let cleanupRuns = [];
const CLEANUP_POLICY_DEFAULT_PAGE_SIZE = 10;
const CLEANUP_RUN_DEFAULT_PAGE_SIZE = 10;
let cleanupPolicyPage = {
  after: 0,
  cursors: [0],
  page: 0,
  size: CLEANUP_POLICY_DEFAULT_PAGE_SIZE,
  nextAfter: null
};
let cleanupRunPage = {
  before: 0,
  cursors: [0],
  page: 0,
  size: CLEANUP_RUN_DEFAULT_PAGE_SIZE,
  nextBefore: null
};
let editingCleanupPolicyId = null;
let cleanupTryRunPolicyId = null;
let cleanupTryRunTrigger = null;
let cleanupTryRunSubmitting = false;
const cleanupScheduleToggleInFlight = new Set();
let cleanupActionMenuPolicyId = null;
let cleanupActionMenuTrigger = null;
let cleanupRunPollToken = 0;
let activeCleanupRunDetailId = null;
let cleanupRunDetailTrigger = null;
let cleanupRunDetailLoadSequence = 0;
let cleanupRunDetailItemGroups = [];
let cleanupRunDetailItemsLoaded = false;
let cleanupRunDetailView = null;
let cleanupRepositorySelection = new Set();
let cleanupRepositoryFilter = "";
let cleanupSchedulePreviewTimer = null;
let cleanupSchedulePreviewController = null;
let cleanupSchedulePreviewSequence = 0;
let activeFieldHelpTrigger = null;
let fieldHelpHideTimer = null;
let securityUsers = [];
let securityRoles = [];
let securityPrivileges = [];
let securityRealms = [];
let securityLdap = null;
let securityOidc = null;
const SECURITY_PROVIDER_SECRET_MASK = "********";
let securityProviderJsonSyncing = false;
const securityProviderAttributes = { ldap: {}, oidc: {} };
let securityAnonymous = null;
let securityApiKeys = [];
let securityScanState = {
  summary: null,
  tasks: [],
  runs: [],
  findings: [],
  repositories: [],
  policies: [],
  waivers: []
};
const SECURITY_SCAN_DEFAULT_PAGE_SIZE = 10;
const securityScanListEndpoints = {
  runs: "runs",
  tasks: "tasks",
  findings: "findings",
  repositories: "repositories",
  policies: "policies",
  waivers: "waivers"
};
let securityScanPages = Object.fromEntries(
  Object.keys(securityScanListEndpoints).map((key) => [
    key,
    {
      after: 0,
      cursors: [0],
      page: 0,
      size: SECURITY_SCAN_DEFAULT_PAGE_SIZE,
      query: "",
      nextAfter: null
    }
  ]));
let securityScanPolicyFormMode = "create";
let editingSecurityScanPolicyId = null;
let editingSecurityScanPolicyEnabled = true;
let editingSecurityScanPolicyPlatforms = ["linux/amd64"];
let securityScanWaiverContext = null;
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
let activeRepositoryRecipeName = null;
let repositoryDataMigrationJobId = null;
let repositoryDataMigrationPollTimer = null;
let securityUserMode = "create";
let securityRoleMode = "create";
let securityPrivilegeMode = "create";
let repositorySort = { key: "name", direction: "asc" };
const BUILT_IN_READ_ONLY_ROLE_IDS = new Set(["nx-admin", "nx-anonymous"]);
const formModalDismissHandlers = new Map();
const securityScanPolicyRequiredFields = [
  { id: "security-scan-policy-name", label: "Name" }
];
const securityScanWaiverRequiredFields = [
  { id: "security-scan-waiver-target", label: "Repository artifact" },
  { id: "security-scan-waiver-duration", label: "Expiration" },
  { id: "security-scan-waiver-reason", label: "Reason" }
];

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

const SECURITY_SCAN_ROUTE_BASE = "#admin/security/artifact-scanning";
const SECURITY_SCAN_ROUTE_ALIASES =
  [SECURITY_SCAN_ROUTE_BASE, "#admin/security/scanning"];
const SECURITY_SCAN_TABS =
  new Set(["overview", "tasks", "findings", "repositories", "policies", "waivers"]);
const CLEANUP_ROUTE_BASE = "#admin/repository/cleanup-policies";
const CLEANUP_ROUTE_ALIASES =
  [CLEANUP_ROUTE_BASE, "#admin/repository/cleanup"];
const CLEANUP_TABS = new Set(["policies", "runs"]);

const viewHashRoutes = {
  repositories: "#admin/repository/repositories",
  "cleanup-policies": CLEANUP_ROUTE_BASE,
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
  "security-scanning": SECURITY_SCAN_ROUTE_BASE,
  "security-audit-log": "#admin/security/audit-log",
  "ui-settings": "#admin/system/ui-settings",
  "nexus-migration": "#admin/migration/nexus",
  "repository-data-migration": "#admin/migration/repository-data",
};

const hashViewRoutes = {
  "#admin": "repositories",
  "#admin/repository": "repositories",
  "#admin/repository/repositories": "repositories",
  "#admin/repository/cleanup-policies": "cleanup-policies",
  "#admin/repository/cleanup": "cleanup-policies",
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
  "#admin/security/artifact-scanning": "security-scanning",
  "#admin/security/scanning": "security-scanning",
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
const AUTH_SNAPSHOT_MAX_AGE_MS = 10 * 60 * 1000;
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
  },
  {
    id: "repository-apt-distribution",
    label: "APT distribution",
    required: () => currentRecipe()?.format === "apt"
        && (currentRecipe()?.type === "HOSTED"
          || document.getElementById("repository-apt-enforce-distribution").checked)
  },
  {
    id: "repository-apt-component",
    label: "APT component",
    required: () => currentRecipe()?.format === "apt"
  },
  {
    id: "repository-apt-architectures",
    label: "APT architectures",
    required: () => currentRecipe()?.format === "apt"
  },
  {
    id: "repository-alpine-distributions",
    label: "Alpine distributions",
    required: () => currentRecipe()?.format === "alpine"
  },
  {
    id: "repository-alpine-channels",
    label: "Alpine channels",
    required: () => currentRecipe()?.format === "alpine"
  },
  {
    id: "repository-alpine-architectures",
    label: "Alpine architectures",
    required: () => currentRecipe()?.format === "alpine"
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
  { id: "ui-default-language", label: "Default language" },
  { id: "ui-default-theme", label: "Default theme" }
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

function lucideIcon(name, className = "") {
  return `<span class="lucide-icon icon-${name}${className ? ` ${className}` : ""}" aria-hidden="true"></span>`;
}

function repoIcon(type) {
  const lower = String(type || "").toLowerCase();
  if (lower === "group") return lucideIcon("boxes", "repo-icon group");
  if (lower === "proxy") return lucideIcon("cloud-download", "repo-icon proxy");
  return lucideIcon("package", "repo-icon hosted");
}

const FORMAT_ICON_NAMES = Object.freeze({
  maven2: "maven",
  npm: "npm",
  pypi: "pypi",
  cargo: "cargo",
  pub: "pub",
  composer: "composer",
  go: "go",
  helm: "helm",
  docker: "docker",
  nuget: "nuget",
  rubygems: "rubygems",
  yum: "yum",
  terraform: "terraform",
  swift: "swift",
  ansiblegalaxy: "ansiblegalaxy",
  conda: "conda",
  conan: "conan",
  apt: "apt",
  alpine: "alpine",
  r: "r",
  huggingface: "huggingface",
  raw: "raw",
});

const FORMAT_DISPLAY_NAMES = Object.freeze({
  maven2: "Maven",
  npm: "npm",
  pypi: "PyPI",
  cargo: "Cargo / Rust",
  pub: "Dart / Pub",
  composer: "Composer / PHP",
  go: "Go",
  helm: "Helm",
  docker: "Docker / OCI",
  nuget: "NuGet",
  rubygems: "RubyGems",
  yum: "Yum / RPM",
  terraform: "Terraform",
  swift: "Swift",
  ansiblegalaxy: "Ansible Galaxy",
  conda: "Conda",
  conan: "Conan 2",
  apt: "APT / Debian",
  alpine: "Alpine / APK",
  r: "R / CRAN",
  huggingface: "Hugging Face Models",
  raw: "Raw",
});

function formatIconName(format) {
  return FORMAT_ICON_NAMES[lowerOrEmpty(format)] || "raw";
}

function formatBadge(format) {
  const normalized = lowerOrEmpty(format);
  const icon = formatIconName(normalized);
  return `<span class="format-cell"><span class="format-logo format-logo-${icon}" aria-hidden="true"></span><span class="format-cell-label">${escapeHtml(normalized)}</span></span>`;
}

function blobStoreIcon(type) {
  return lucideIcon("database", `repo-icon ${type === "s3" ? "proxy" : "hosted"}`);
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

function securityScanTabFromHash(hash = window.location.hash) {
  const path = normalizeAdminHash(hash);
  for (const base of SECURITY_SCAN_ROUTE_ALIASES) {
    if (path === base) return "overview";
    if (!path.startsWith(`${base}/`)) continue;
    const tab = path.substring(base.length + 1);
    return SECURITY_SCAN_TABS.has(tab) ? tab : "overview";
  }
  return null;
}

function cleanupTabFromHash(hash = window.location.hash) {
  const path = normalizeAdminHash(hash);
  for (const base of CLEANUP_ROUTE_ALIASES) {
    if (path === base) return "policies";
    if (!path.startsWith(`${base}/`)) continue;
    const tab = path.substring(base.length + 1);
    return CLEANUP_TABS.has(tab) ? tab : "policies";
  }
  return null;
}

function viewFromHash(hash = window.location.hash) {
  const path = normalizeAdminHash(hash);
  if (securityScanTabFromHash(path) != null) return "security-scanning";
  if (cleanupTabFromHash(path) != null) return "cleanup-policies";
  return hashViewRoutes[path] || null;
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

function updateHashForSecurityScanTab(tab, replace = false) {
  const selected = SECURITY_SCAN_TABS.has(tab) ? tab : "overview";
  const hash = selected === "overview"
    ? SECURITY_SCAN_ROUTE_BASE
    : `${SECURITY_SCAN_ROUTE_BASE}/${selected}`;
  if (window.location.hash === hash) return;
  if (replace) {
    window.history.replaceState(null, "", hash);
  } else {
    window.history.pushState(null, "", hash);
  }
}

function updateHashForCleanupTab(tab, replace = false) {
  const selected = CLEANUP_TABS.has(tab) ? tab : "policies";
  const hash = selected === "policies"
    ? CLEANUP_ROUTE_BASE
    : `${CLEANUP_ROUTE_BASE}/${selected}`;
  if (window.location.hash === hash) return;
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
  const userMenuTrigger = document.getElementById("user-menu-trigger");
  const currentUser = document.getElementById("current-user");
  const userMenuName = document.getElementById("user-menu-name");
  const userMenuSource = document.getElementById("user-menu-source");
  userMenu.hidden = !signedIn;
  if (!signedIn) closeUserMenu();
  if (signedIn) {
    const userId = String(currentSession.userId);
    const source = displaySource(currentSession.source).trim();
    const sourceLabel = accountSourceLabel(source);
    const qualifiedUser = source ? `${source}/${userId}` : userId;

    currentUser.textContent = userId;
    userMenuName.textContent = userId;
    userMenuSource.textContent = sourceLabel;
    userMenuTrigger.title = `Account menu for ${qualifiedUser}`;
    userMenuTrigger.setAttribute("aria-label", `Account menu for ${qualifiedUser}`);
    sessionStorage.setItem(AUTH_SNAPSHOT_KEY, JSON.stringify({
      session: currentSession,
      permissions: ["nexus:*"],
      savedAt: Date.now(),
    }));
  } else {
    sessionStorage.removeItem(AUTH_SNAPSHOT_KEY);
  }
}

function hydrateSessionControls() {
  try {
    const raw = sessionStorage.getItem(AUTH_SNAPSHOT_KEY);
    if (!raw) return;
    const snapshot = JSON.parse(raw);
    if (!snapshot?.session?.userId
        || Date.now() - Number(snapshot.savedAt || 0) > AUTH_SNAPSHOT_MAX_AGE_MS) {
      sessionStorage.removeItem(AUTH_SNAPSHOT_KEY);
      return;
    }
    updateSessionControls(snapshot.session);
  } catch {
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

function accountSourceLabel(source) {
  const value = displaySource(source).trim();
  if (!value) return "Authenticated account";
  return `${value.charAt(0).toUpperCase()}${value.slice(1)} account`;
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
    input.closest("details")?.setAttribute("open", "");
    showToast(`Invalid JSON: ${error.message}`, "error");
    throw error;
  }
}

function editableSecurityProviderAttributes(attributes, excludedKeys = []) {
  const editable = { ...(attributes || {}) };
  delete editable.source;
  delete editable.nexusRealm;
  excludedKeys.forEach((key) => delete editable[key]);
  return editable;
}

function securityProviderJsonScalar(value, fallback = "") {
  return typeof value === "string" || typeof value === "number" ? value : fallback;
}

function securityProviderJsonNumber(value, fallback = "") {
  if (value == null || value === "") return fallback;
  const number = Number(value);
  return Number.isFinite(number) ? number : fallback;
}

function securityProviderJsonBoolean(value, fallback = false) {
  if (value == null) return fallback;
  if (typeof value === "boolean") return value;
  if (value === "true") return true;
  if (value === "false") return false;
  return fallback;
}

function setSecurityProviderSecretFromJson(id, value) {
  if (value === SECURITY_PROVIDER_SECRET_MASK) return;
  setInputValue(id, securityProviderJsonScalar(value));
}

function securityLdapFormValue({ attributes = securityProviderAttributes.ldap, maskSecrets = true } = {}) {
  const authPassword = textInputValue("security-ldap-auth-password");
  return {
    enabled: document.getElementById("security-ldap-enabled").checked,
    priority: numberInputValue("security-ldap-priority") ?? 10,
    source: "LDAP",
    url: textInputValue("security-ldap-url"),
    protocol: textInputValue("security-ldap-protocol") || "ldap",
    host: textInputValue("security-ldap-host"),
    port: numberInputValue("security-ldap-port"),
    useTrustStore: document.getElementById("security-ldap-trust-store").checked,
    searchBase: textInputValue("security-ldap-search-base"),
    authScheme: textInputValue("security-ldap-auth-scheme"),
    authRealm: textInputValue("security-ldap-auth-realm"),
    authUsername: textInputValue("security-ldap-auth-username"),
    authPassword: maskSecrets && authPassword ? SECURITY_PROVIDER_SECRET_MASK : authPassword,
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
}

function securityOidcFormValue({ attributes = securityProviderAttributes.oidc, maskSecrets = true } = {}) {
  const clientSecret = textInputValue("security-oidc-client-secret");
  return {
    enabled: document.getElementById("security-oidc-enabled").checked,
    priority: numberInputValue("security-oidc-priority") ?? 20,
    source: "OIDC",
    issuer: textInputValue("security-oidc-issuer"),
    jwksUri: textInputValue("security-oidc-jwks-uri"),
    audience: textInputValue("security-oidc-audience"),
    clientId: textInputValue("security-oidc-client-id"),
    clientSecret: maskSecrets && clientSecret ? SECURITY_PROVIDER_SECRET_MASK : clientSecret,
    authorizationEndpoint: textInputValue("security-oidc-authorization-endpoint"),
    tokenEndpoint: textInputValue("security-oidc-token-endpoint"),
    redirectUri: textInputValue("security-oidc-redirect-uri"),
    scopes: textInputValue("security-oidc-scopes"),
    userIdClaim: textInputValue("security-oidc-user-id-claim"),
    firstNameClaim: textInputValue("security-oidc-first-name-claim"),
    lastNameClaim: textInputValue("security-oidc-last-name-claim"),
    emailClaim: textInputValue("security-oidc-email-claim"),
    groupsClaim: textInputValue("security-oidc-groups-claim"),
    rolesClaim: textInputValue("security-oidc-roles-claim"),
    clockSkewSeconds: numberInputValue("security-oidc-clock-skew") ?? 60,
    jwksCacheSeconds: numberInputValue("security-oidc-jwks-cache") ?? 300,
    attributes
  };
}

function applySecurityLdapJsonValue(value) {
  setCheckboxValue("security-ldap-enabled", securityProviderJsonBoolean(value.enabled));
  setInputValue("security-ldap-priority", securityProviderJsonNumber(value.priority, 10));
  setInputValue("security-ldap-url", securityProviderJsonScalar(value.url));
  setSelectValue("security-ldap-protocol", securityProviderJsonScalar(value.protocol), "ldap");
  setInputValue("security-ldap-host", securityProviderJsonScalar(value.host));
  setInputValue("security-ldap-port", securityProviderJsonNumber(value.port));
  setCheckboxValue("security-ldap-trust-store", securityProviderJsonBoolean(value.useTrustStore));
  setInputValue("security-ldap-search-base", securityProviderJsonScalar(value.searchBase));
  setInputValue("security-ldap-auth-scheme", securityProviderJsonScalar(value.authScheme, "simple"));
  setInputValue("security-ldap-auth-realm", securityProviderJsonScalar(value.authRealm));
  setInputValue("security-ldap-auth-username", securityProviderJsonScalar(value.authUsername));
  setSecurityProviderSecretFromJson("security-ldap-auth-password", value.authPassword);
  setInputValue("security-ldap-connection-timeout", securityProviderJsonNumber(value.connectionTimeout, 30));
  setInputValue("security-ldap-retry-delay", securityProviderJsonNumber(value.connectionRetryDelay));
  setInputValue("security-ldap-max-incidents", securityProviderJsonNumber(value.maxIncidentsCount));
  setInputValue("security-ldap-user-base-dn", securityProviderJsonScalar(value.userBaseDn));
  setCheckboxValue("security-ldap-user-subtree", securityProviderJsonBoolean(value.userSubtree, true));
  setInputValue("security-ldap-user-object-class", securityProviderJsonScalar(value.userObjectClass, "inetOrgPerson"));
  setInputValue("security-ldap-user-filter", securityProviderJsonScalar(value.userLdapFilter));
  setInputValue("security-ldap-user-id-attribute", securityProviderJsonScalar(value.userIdAttribute, "uid"));
  setInputValue("security-ldap-user-real-name-attribute", securityProviderJsonScalar(value.userRealNameAttribute, "cn"));
  setInputValue("security-ldap-user-member-of-attribute", securityProviderJsonScalar(value.userMemberOfAttribute, "memberOf"));
  setInputValue("security-ldap-user-email-attribute", securityProviderJsonScalar(value.userEmailAddressAttribute, "mail"));
  setInputValue("security-ldap-user-password-attribute", securityProviderJsonScalar(value.userPasswordAttribute, "userPassword"));
  setCheckboxValue("security-ldap-groups-as-roles", securityProviderJsonBoolean(value.ldapGroupsAsRoles, true));
  setSelectValue("security-ldap-group-type", securityProviderJsonScalar(value.groupType), "static");
  setInputValue("security-ldap-group-base-dn", securityProviderJsonScalar(value.groupBaseDn));
  setCheckboxValue("security-ldap-group-subtree", securityProviderJsonBoolean(value.groupSubtree, true));
  setInputValue("security-ldap-group-id-attribute", securityProviderJsonScalar(value.groupIdAttribute, "cn"));
  setInputValue("security-ldap-group-member-attribute", securityProviderJsonScalar(value.groupMemberAttribute, "member"));
  setInputValue("security-ldap-group-member-format", securityProviderJsonScalar(value.groupMemberFormat, "${dn}"));
  setInputValue("security-ldap-group-object-class", securityProviderJsonScalar(value.groupObjectClass, "groupOfNames"));
  const attributes = value.attributes && !Array.isArray(value.attributes) && typeof value.attributes === "object"
    ? editableSecurityProviderAttributes(value.attributes, ["name"])
    : {};
  securityProviderAttributes.ldap = attributes;
  clearRequiredFieldErrors(ldapRequiredFields);
  refreshSecurityLdapRequiredMarkers();
}

function applySecurityOidcJsonValue(value) {
  setCheckboxValue("security-oidc-enabled", securityProviderJsonBoolean(value.enabled));
  setInputValue("security-oidc-priority", securityProviderJsonNumber(value.priority, 20));
  setInputValue("security-oidc-issuer", securityProviderJsonScalar(value.issuer ?? value.issuerUri));
  setInputValue("security-oidc-jwks-uri", securityProviderJsonScalar(value.jwksUri));
  setInputValue("security-oidc-audience", securityProviderJsonScalar(value.audience));
  setInputValue("security-oidc-client-id", securityProviderJsonScalar(value.clientId));
  setSecurityProviderSecretFromJson("security-oidc-client-secret", value.clientSecret);
  setInputValue("security-oidc-authorization-endpoint", securityProviderJsonScalar(value.authorizationEndpoint));
  setInputValue("security-oidc-token-endpoint", securityProviderJsonScalar(value.tokenEndpoint));
  setInputValue("security-oidc-redirect-uri", securityProviderJsonScalar(value.redirectUri));
  setInputValue("security-oidc-scopes", securityProviderJsonScalar(value.scopes, "openid profile email"));
  setInputValue("security-oidc-user-id-claim", securityProviderJsonScalar(value.userIdClaim, "preferred_username"));
  setInputValue("security-oidc-first-name-claim", securityProviderJsonScalar(value.firstNameClaim, "given_name"));
  setInputValue("security-oidc-last-name-claim", securityProviderJsonScalar(value.lastNameClaim, "family_name"));
  setInputValue("security-oidc-email-claim", securityProviderJsonScalar(value.emailClaim, "email"));
  setInputValue("security-oidc-groups-claim", securityProviderJsonScalar(value.groupsClaim, "groups"));
  setInputValue("security-oidc-roles-claim", securityProviderJsonScalar(value.rolesClaim, "roles"));
  setInputValue("security-oidc-clock-skew", securityProviderJsonNumber(value.clockSkewSeconds, 60));
  setInputValue("security-oidc-jwks-cache", securityProviderJsonNumber(value.jwksCacheSeconds, 300));
  const attributes = value.attributes && !Array.isArray(value.attributes) && typeof value.attributes === "object"
    ? editableSecurityProviderAttributes(value.attributes)
    : {};
  securityProviderAttributes.oidc = attributes;
  clearRequiredFieldErrors(oidcRequiredFields);
  refreshSecurityOidcRequiredMarkers();
}

function setSecurityProviderJsonStatus(provider, state, text) {
  const status = document.getElementById(`security-${provider}-json-status`);
  status.dataset.state = state;
  status.textContent = text;
}

function syncSecurityProviderJsonFromForm(provider, { force = false } = {}) {
  if (securityProviderJsonSyncing) return;
  const editor = document.getElementById(`security-${provider}-json-editor`);
  if (!force && document.activeElement === editor) return;
  const value = provider === "ldap" ? securityLdapFormValue() : securityOidcFormValue();
  editor.value = JSON.stringify(value, null, 2);
  editor.classList.remove("is-invalid");
  editor.setAttribute("aria-invalid", "false");
  editor.removeAttribute("title");
  setSecurityProviderJsonStatus(provider, "synced", "Synced");
}

function applySecurityProviderJsonEditor(provider) {
  const editor = document.getElementById(`security-${provider}-json-editor`);
  try {
    const value = JSON.parse(editor.value);
    if (!value || Array.isArray(value) || typeof value !== "object") {
      throw new Error("JSON must be an object");
    }
    securityProviderJsonSyncing = true;
    if (provider === "ldap") {
      applySecurityLdapJsonValue(value);
    } else {
      applySecurityOidcJsonValue(value);
    }
    editor.classList.remove("is-invalid");
    editor.setAttribute("aria-invalid", "false");
    editor.removeAttribute("title");
    setSecurityProviderJsonStatus(provider, "synced", "Synced");
    return true;
  } catch (error) {
    editor.classList.add("is-invalid");
    editor.setAttribute("aria-invalid", "true");
    editor.title = error.message;
    setSecurityProviderJsonStatus(provider, "error", "Waiting for valid JSON");
    return false;
  } finally {
    securityProviderJsonSyncing = false;
  }
}

function bindSecurityProviderJson(provider) {
  const form = document.getElementById(`security-${provider}-form`);
  const editor = document.getElementById(`security-${provider}-json-editor`);
  form.addEventListener("input", () => syncSecurityProviderJsonFromForm(provider));
  form.addEventListener("change", () => syncSecurityProviderJsonFromForm(provider));
  editor.addEventListener("input", () => applySecurityProviderJsonEditor(provider));
  editor.addEventListener("blur", () => {
    if (applySecurityProviderJsonEditor(provider)) {
      syncSecurityProviderJsonFromForm(provider, { force: true });
    }
  });
}

function markInputValidity(input, invalid) {
  input.classList.toggle("is-invalid", invalid);
  input.setAttribute("aria-invalid", String(invalid));
  if (input.id === "repository-recipe") {
    const trigger = document.getElementById("repository-recipe-trigger");
    trigger?.classList.toggle("is-invalid", invalid);
    trigger?.setAttribute("aria-invalid", String(invalid));
  }
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
  const marker = input.closest("label, .form-field")?.querySelector(".required-mark");
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
      firstMissingInput = firstMissingInput
        || (input.id === "repository-recipe"
          ? document.getElementById("repository-recipe-trigger")
          : input);
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
    if (indicator) {
      indicator.classList.add("lucide-icon");
      indicator.classList.toggle("icon-arrow-up-down", !active);
      indicator.classList.toggle("icon-arrow-up", active && direction === "asc");
      indicator.classList.toggle("icon-arrow-down", active && direction === "desc");
    }
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
        <td>${formatBadge(repo.format)}</td>
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

function repositoryFormatLabel(format) {
  const normalized = lowerOrEmpty(format);
  return FORMAT_DISPLAY_NAMES[normalized] || normalized || "Unknown";
}

function repositoryTypeLabel(type) {
  const normalized = lowerOrEmpty(type);
  return normalized ? normalized.charAt(0).toUpperCase() + normalized.slice(1) : "Unknown";
}

function repositoryRecipeContent(recipe) {
  const format = lowerOrEmpty(recipe?.format);
  const type = repositoryTypeLabel(recipe?.type);
  return `
    <span class="format-logo format-logo-${formatIconName(format)}" aria-hidden="true"></span>
    <span class="recipe-option-copy">
      <strong>${escapeHtml(repositoryFormatLabel(format))}</strong>
    </span>
    <span class="recipe-type">${escapeHtml(type)}</span>
  `;
}

function repositoryRecipeMatches(recipe, filter) {
  if (!filter) return true;
  return `${recipe?.name || ""} ${repositoryFormatLabel(recipe?.format)} ${repositoryTypeLabel(recipe?.type)}`
    .toLowerCase()
    .includes(filter);
}

function visibleRepositoryRecipes() {
  const filter = document.getElementById("repository-recipe-search").value.trim().toLowerCase();
  return repositoryRecipes.filter((recipe) => repositoryRecipeMatches(recipe, filter));
}

function setActiveRepositoryRecipe(name, scroll = false) {
  activeRepositoryRecipeName = name || null;
  const search = document.getElementById("repository-recipe-search");
  let activeOption = null;
  document.querySelectorAll("#repository-recipe-options .recipe-combobox-option").forEach((option) => {
    const active = option.dataset.recipe === activeRepositoryRecipeName;
    option.classList.toggle("is-active", active);
    if (active) activeOption = option;
  });
  if (activeOption) {
    search.setAttribute("aria-activedescendant", activeOption.id);
    if (scroll) activeOption.scrollIntoView({ block: "nearest" });
  } else {
    search.removeAttribute("aria-activedescendant");
  }
}

function renderRepositoryRecipeOptions() {
  const select = document.getElementById("repository-recipe");
  const options = document.getElementById("repository-recipe-options");
  const recipes = visibleRepositoryRecipes();
  if (!recipes.some((recipe) => recipe.name === activeRepositoryRecipeName)) {
    activeRepositoryRecipeName = recipes.find((recipe) => recipe.name === select.value)?.name
      || recipes[0]?.name
      || null;
  }
  options.innerHTML = recipes.length
    ? recipes.map((recipe, index) => `
        <div
            class="recipe-combobox-option${recipe.name === activeRepositoryRecipeName ? " is-active" : ""}"
            id="repository-recipe-option-${index}"
            role="option"
            aria-selected="${String(recipe.name === select.value)}"
            data-recipe="${escapeHtml(recipe.name)}">
          ${repositoryRecipeContent(recipe)}
        </div>
      `).join("")
    : '<div class="recipe-combobox-empty">No matching recipes</div>';
  setActiveRepositoryRecipe(activeRepositoryRecipeName);
}

function syncRepositoryRecipeCombobox() {
  const select = document.getElementById("repository-recipe");
  const trigger = document.getElementById("repository-recipe-trigger");
  const value = document.getElementById("repository-recipe-value");
  const recipe = currentRecipe();
  trigger.disabled = select.disabled || repositoryRecipes.length === 0;
  trigger.setAttribute("aria-disabled", String(trigger.disabled));
  value.innerHTML = recipe
    ? `<span class="recipe-selection">${repositoryRecipeContent(recipe)}</span>`
    : escapeHtml(repositoryRecipes.length === 0 ? "No recipes available" : "Select a recipe");
  if (!document.getElementById("repository-recipe-popover").hidden) {
    renderRepositoryRecipeOptions();
  }
}

function openRepositoryRecipeCombobox() {
  const trigger = document.getElementById("repository-recipe-trigger");
  const popover = document.getElementById("repository-recipe-popover");
  if (trigger.disabled || !popover.hidden) return;
  document.getElementById("repository-recipe-search").value = "";
  activeRepositoryRecipeName = document.getElementById("repository-recipe").value
    || repositoryRecipes[0]?.name
    || null;
  popover.hidden = false;
  trigger.setAttribute("aria-expanded", "true");
  renderRepositoryRecipeOptions();
  setTimeout(() => document.getElementById("repository-recipe-search").focus(), 0);
}

function closeRepositoryRecipeCombobox(focusTrigger = false) {
  const popover = document.getElementById("repository-recipe-popover");
  const trigger = document.getElementById("repository-recipe-trigger");
  popover.hidden = true;
  trigger.setAttribute("aria-expanded", "false");
  activeRepositoryRecipeName = null;
  if (focusTrigger) trigger.focus();
}

function toggleRepositoryRecipeCombobox() {
  const popover = document.getElementById("repository-recipe-popover");
  if (popover.hidden) openRepositoryRecipeCombobox();
  else closeRepositoryRecipeCombobox(true);
}

function moveActiveRepositoryRecipe(direction) {
  const options = Array.from(
    document.querySelectorAll("#repository-recipe-options .recipe-combobox-option"));
  if (options.length === 0) return;
  let index = options.findIndex((option) => option.dataset.recipe === activeRepositoryRecipeName);
  if (index < 0) index = direction > 0 ? -1 : 0;
  index = (index + direction + options.length) % options.length;
  setActiveRepositoryRecipe(options[index].dataset.recipe, true);
}

function selectRepositoryRecipe(name) {
  if (!repositoryRecipes.some((recipe) => recipe.name === name)) return;
  const select = document.getElementById("repository-recipe");
  select.value = name;
  markInputValidity(select, false);
  select.dispatchEvent(new Event("change", { bubbles: true }));
  closeRepositoryRecipeCombobox(true);
}

function bindRepositoryRecipeCombobox() {
  const root = document.getElementById("repository-recipe-combobox");
  const trigger = document.getElementById("repository-recipe-trigger");
  const search = document.getElementById("repository-recipe-search");
  const options = document.getElementById("repository-recipe-options");

  trigger.addEventListener("click", toggleRepositoryRecipeCombobox);
  trigger.addEventListener("keydown", (event) => {
    if (event.key === "ArrowDown" || event.key === "ArrowUp") {
      event.preventDefault();
      openRepositoryRecipeCombobox();
      return;
    }
    if (event.key === "Escape" && !document.getElementById("repository-recipe-popover").hidden) {
      event.preventDefault();
      event.stopPropagation();
      closeRepositoryRecipeCombobox(true);
    }
  });

  search.addEventListener("input", () => {
    activeRepositoryRecipeName = null;
    renderRepositoryRecipeOptions();
  });
  search.addEventListener("keydown", (event) => {
    if (event.key === "ArrowDown" || event.key === "ArrowUp") {
      event.preventDefault();
      moveActiveRepositoryRecipe(event.key === "ArrowDown" ? 1 : -1);
      return;
    }
    if (event.key === "Enter") {
      event.preventDefault();
      if (activeRepositoryRecipeName) selectRepositoryRecipe(activeRepositoryRecipeName);
      return;
    }
    if (event.key === "Escape") {
      event.preventDefault();
      event.stopPropagation();
      closeRepositoryRecipeCombobox(true);
    }
  });

  options.addEventListener("mousemove", (event) => {
    const option = event.target.closest(".recipe-combobox-option");
    if (option) setActiveRepositoryRecipe(option.dataset.recipe);
  });
  options.addEventListener("click", (event) => {
    const option = event.target.closest(".recipe-combobox-option");
    if (option) selectRepositoryRecipe(option.dataset.recipe);
  });

  document.addEventListener("click", (event) => {
    if (!root.contains(event.target)) closeRepositoryRecipeCombobox();
  });
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
  const allowNestedGroups = format === "pub" || format === "composer"
    || format === "terraform" || format === "swift" || format === "ansiblegalaxy"
    || format === "conda";
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
  const handle = draggable ? lucideIcon("grip-vertical", "member-handle") : "";
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
  document.getElementById("repository-outbound-proxy-fields").hidden = type !== "PROXY";
  document.getElementById("repository-group-fields").hidden = type !== "GROUP";
  document.getElementById("repository-docker-fields").hidden = format !== "docker";
  document.getElementById("repository-cargo-fields").hidden =
    format !== "cargo";
  document.getElementById("repository-conan-fields").hidden = format !== "conan";
  document.getElementById("repository-conan-operations").hidden =
    format !== "conan" || repositoryFormMode !== "edit";
  document.getElementById("repository-apt-fields").hidden = format !== "apt";
  document.getElementById("repository-alpine-fields").hidden = format !== "alpine";
  document.getElementById("repository-swift-proxy-note").hidden =
    !(format === "swift" && type === "PROXY");
  const pypiIndexPathVisible = format === "pypi" && type === "PROXY";
  document.getElementById("repository-pypi-index-path-field").hidden =
    !pypiIndexPathVisible;
  const minimumReleaseAgeVisible = format === "npm" && type === "PROXY";
  document.getElementById("repository-minimum-release-age-field").hidden =
    !minimumReleaseAgeVisible;
  document.getElementById("repository-minimum-release-age-note").hidden =
    !minimumReleaseAgeVisible;
  refreshDockerConnectorControls();
  refreshAptControls();
  refreshAlpineControls();
  document.getElementById("repository-blobstore").closest("label").hidden = false;
  refreshRepositoryBlobStoreLock();
  document.querySelectorAll("#repository-hosted-fields .maven-only").forEach((el) => {
    el.hidden = format !== "maven2";
  });
  refreshRepositoryRemoteDefaults(recipe);
  if (type === "GROUP") refreshRepositoryMemberOptions();
  updateRequiredMarkers(repositoryRequiredFields);
  syncRepositoryRecipeCombobox();
}

function refreshRepositoryRemoteDefaults(recipe) {
  const remote = document.getElementById("repository-remote-url");
  remote.readOnly = Boolean(recipe?.type === "PROXY" && recipe?.format === "swift");
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
    pub: "https://pub.dev/",
    composer: "https://repo.packagist.org/",
    terraform: "https://registry.terraform.io/",
    swift: "https://github.com/",
    ansiblegalaxy: "https://galaxy.ansible.com/",
    conda: "https://repo.anaconda.com/pkgs/main/",
    conan: "https://center2.conan.io/",
    apt: "https://deb.debian.org/debian/",
    alpine: "https://dl-cdn.alpinelinux.org/alpine/",
    r: "https://cloud.r-project.org/",
    huggingface: "https://huggingface.co/"
  };
  if (recipe.format === "swift") {
    remote.value = defaults.swift;
  }
  remote.placeholder = defaults[recipe.format] || "https://example.com/";
  if (repositoryFormMode === "create" && !remote.value.trim() && defaults[recipe.format]) {
    remote.value = defaults[recipe.format];
  }
}

function refreshAptControls() {
  const recipe = currentRecipe();
  const apt = recipe?.format === "apt";
  const hosted = apt && recipe?.type === "HOSTED";
  const proxy = apt && recipe?.type === "PROXY";
  const mode = document.getElementById("repository-apt-metadata-mode");
  if (hosted) mode.value = "RESIGN";
  mode.disabled = hosted;
  document.getElementById("repository-apt-flat-field").hidden = !proxy;
  document.getElementById("repository-apt-enforce-field").hidden = !proxy;
  if (hosted) document.getElementById("repository-apt-enforce-distribution").checked = true;
  const flat = document.getElementById("repository-apt-flat");
  if (!proxy) flat.checked = false;
  if (flat.checked && mode.value === "RESIGN") mode.value = "PASSTHROUGH";
  mode.querySelector('option[value="RESIGN"]').disabled = proxy && flat.checked;
  document.getElementById("repository-apt-valid-until-days").disabled = !hosted && mode.value !== "RESIGN";
  document.getElementById("repository-apt-origin").disabled = !hosted && mode.value !== "RESIGN";
  document.getElementById("repository-apt-label").disabled = !hosted && mode.value !== "RESIGN";
  document.getElementById("repository-apt-operations").hidden =
    !apt || repositoryFormMode !== "edit";
  updateRequiredMarkers(repositoryRequiredFields);
}

function refreshAlpineControls() {
  const recipe = currentRecipe();
  const alpine = recipe?.format === "alpine";
  const proxy = alpine && recipe?.type === "PROXY";
  const locallySigned = alpine && !proxy;
  const mode = document.getElementById("repository-alpine-metadata-mode");
  if (locallySigned) mode.value = "RESIGN";
  mode.disabled = locallySigned;
  const resignProxy = proxy && mode.value === "RESIGN";
  document.getElementById("repository-alpine-verify-field").hidden = !proxy;
  document.getElementById("repository-alpine-stale-field").hidden = !proxy;
  document.getElementById("repository-alpine-upstream-key-field").hidden = !resignProxy;
  if (locallySigned || resignProxy) {
    document.getElementById("repository-alpine-verify-upstream").checked = true;
  }
  document.getElementById("repository-alpine-operations").hidden =
    !alpine || repositoryFormMode !== "edit";
  updateRequiredMarkers(repositoryRequiredFields);
}

async function loadConanStatus(name = editingRepositoryName) {
  if (!name) return;
  const output = document.getElementById("repository-conan-status");
  output.textContent = "Loading Conan status…";
  try {
    const response = await fetch(
      `/internal/repositories/${encodeURIComponent(name)}/conan/status`);
    if (!response.ok) throw new Error(await responseErrorMessage(response));
    const status = await response.json();
    output.textContent = [
      `${status.recipes ?? 0} recipes`,
      `${status.recipeRevisions ?? 0} recipe revisions`,
      `${status.packages ?? 0} packages`,
      `${status.packageRevisions ?? 0} package revisions`,
      `${status.committedFiles ?? 0} committed files`,
      `${status.openUploadSessions ?? 0} open upload sessions`,
      `${status.cachedProxyFiles ?? 0} cached proxy files`,
    ].join(" · ");
  } catch (error) {
    output.textContent = error.message || "Conan status unavailable.";
  }
}

async function loadAptStatus(name = editingRepositoryName) {
  if (!name) return;
  const output = document.getElementById("repository-apt-status");
  output.textContent = "Loading APT status…";
  try {
    const response = await fetch(
      `/internal/repositories/${encodeURIComponent(name)}/apt/status`);
    if (!response.ok) throw new Error(await responseErrorMessage(response));
    const status = await response.json();
    const key = status.activeKey
      ? `key ${status.activeKey.fingerprint} (revision ${status.activeKey.revision})`
      : "signing key will be generated on first publication";
    const suites = Array.isArray(status.suites) && status.suites.length
      ? status.suites.map((suite) => {
        const failure = suite.lastError ? `, last error: ${suite.lastError}` : "";
        return `${suite.distribution}: ${suite.publishedRevision}/${suite.desiredRevision}${failure}`;
      }).join("; ")
      : "no metadata snapshot published";
    const proxies = Array.isArray(status.proxyDistributions)
      ? status.proxyDistributions.map((item) =>
        `${item.distribution}: ${item.indexCount} Release entries`).join("; ")
      : "";
    output.textContent = `${key}. Suites: ${suites}.${proxies ? ` Upstream: ${proxies}.` : ""}`;
  } catch (error) {
    output.textContent = `Unable to load APT status: ${error.message}`;
  }
}

async function rebuildAptMetadata() {
  if (!editingRepositoryName) return;
  showToast("Rebuilding APT metadata…");
  try {
    const distribution = document.getElementById("repository-apt-distribution").value.trim();
    const response = await fetch(
      `/internal/repositories/${encodeURIComponent(editingRepositoryName)}/apt/rebuild`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ distribution: distribution || null })
      });
    if (!response.ok) throw new Error(await responseErrorMessage(response));
    showToast("APT metadata rebuilt.", "success");
    await loadAptStatus();
  } catch (error) {
    showToast(error.message || "APT metadata rebuild failed.", "error");
  }
}

async function rotateAptSigningKey() {
  if (!editingRepositoryName) return;
  const privateKey = document.getElementById("repository-apt-private-key").value.trim();
  if (!privateKey) {
    showToast("Paste an ASCII-armored OpenPGP private key first.", "error");
    return;
  }
  showToast("Importing APT signing key…");
  try {
    const response = await fetch(
      `/internal/repositories/${encodeURIComponent(editingRepositoryName)}/apt/signing-key`, {
        method: "PUT",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({
          privateKey,
          passphrase: document.getElementById("repository-apt-key-passphrase").value
        })
      });
    if (!response.ok) throw new Error(await responseErrorMessage(response));
    document.getElementById("repository-apt-private-key").value = "";
    document.getElementById("repository-apt-key-passphrase").value = "";
    showToast("APT signing key rotated and metadata republished.", "success");
    await loadAptStatus();
  } catch (error) {
    showToast(error.message || "APT signing key import failed.", "error");
  }
}

async function generateAptSigningKey() {
  if (!editingRepositoryName) return;
  if (!window.confirm("Generate a new APT signing key and republish repository metadata?")) return;
  showToast("Generating APT signing key…");
  try {
    const response = await fetch(
      `/internal/repositories/${encodeURIComponent(editingRepositoryName)}/apt/signing-key`, {
        method: "PUT",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ generate: true })
      });
    if (!response.ok) throw new Error(await responseErrorMessage(response));
    showToast("APT signing key rotated and metadata republished.", "success");
    await loadAptStatus();
  } catch (error) {
    showToast(error.message || "APT signing key generation failed.", "error");
  }
}

async function loadAlpineStatus(name = editingRepositoryName) {
  if (!name) return;
  const output = document.getElementById("repository-alpine-status");
  output.textContent = "Loading Alpine status…";
  try {
    const response = await fetch(
      `/internal/repositories/${encodeURIComponent(name)}/alpine/status`);
    if (!response.ok) throw new Error(await responseErrorMessage(response));
    const status = await response.json();
    const key = status.activeKey
      ? `${status.activeKey.filename} · ${status.activeKey.fingerprint} · revision ${status.activeKey.revision}`
      : "no active signing key";
    const namespaces = Array.isArray(status.namespaces) && status.namespaces.length
      ? status.namespaces.map((item) => {
        const failure = item.lastError ? `, last error: ${item.lastError}` : "";
        return `${item.namespace}: ${item.publishedRevision}/${item.desiredRevision}${failure}`;
      }).join("; ")
      : "no signed index snapshot published";
    const proxies = Array.isArray(status.proxyNamespaces) && status.proxyNamespaces.length
      ? status.proxyNamespaces.map((item) =>
        `${item.namespace}: ${item.signatureVerified ? "verified" : "unverified"}`).join("; ")
      : "";
    output.textContent = `Key: ${key}. Namespaces: ${namespaces}.${proxies ? ` Upstream: ${proxies}.` : ""}`;
  } catch (error) {
    output.textContent = `Unable to load Alpine status: ${error.message}`;
  }
}

function firstAlpineNamespace() {
  const first = (id, fallback) => document.getElementById(id).value
    .split(",").map((value) => value.trim()).filter(Boolean)[0] || fallback;
  return [
    first("repository-alpine-distributions", "v3.23"),
    first("repository-alpine-channels", "main"),
    first("repository-alpine-architectures", "x86_64")
  ].join("/");
}

async function rebuildAlpineMetadata() {
  if (!editingRepositoryName) return;
  const namespace = firstAlpineNamespace();
  showToast(`Rebuilding Alpine index ${namespace}…`);
  try {
    const response = await fetch(
      `/internal/repositories/${encodeURIComponent(editingRepositoryName)}/alpine/rebuild`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ namespace })
      });
    if (!response.ok) throw new Error(await responseErrorMessage(response));
    showToast("Alpine index rebuilt.", "success");
    await loadAlpineStatus();
  } catch (error) {
    showToast(error.message || "Alpine index rebuild failed.", "error");
  }
}

async function rotateAlpineSigningKey(generate = false) {
  if (!editingRepositoryName) return;
  const privateKey = document.getElementById("repository-alpine-private-key").value.trim();
  if (!generate && !privateKey) {
    showToast("Paste a PKCS#8 RSA private key first.", "error");
    return;
  }
  if (generate && !window.confirm(
      "Generate a new Alpine signing key and republish every known index?")) return;
  showToast(generate ? "Generating Alpine signing key…" : "Importing Alpine signing key…");
  try {
    const response = await fetch(
      `/internal/repositories/${encodeURIComponent(editingRepositoryName)}/alpine/signing-key`, {
        method: "PUT",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({
          generate,
          privateKey: generate ? null : privateKey,
          keyFilename: document.getElementById("repository-alpine-key-filename").value.trim(),
          signatureType: document.getElementById("repository-alpine-signature-type").value
        })
      });
    if (!response.ok) throw new Error(await responseErrorMessage(response));
    document.getElementById("repository-alpine-private-key").value = "";
    showToast("Alpine signing key rotated and indexes republished.", "success");
    await loadAlpineStatus();
  } catch (error) {
    showToast(error.message || "Alpine signing key rotation failed.", "error");
  }
}

async function downloadAlpinePublicKey() {
  if (!editingRepositoryName) return;
  try {
    const response = await fetch(
      `/internal/repositories/${encodeURIComponent(editingRepositoryName)}/alpine/public-key`);
    if (!response.ok) throw new Error(await responseErrorMessage(response));
    const blob = await response.blob();
    const url = URL.createObjectURL(blob);
    const link = document.createElement("a");
    link.href = url;
    link.download = document.getElementById("repository-alpine-key-filename").value.trim()
      || `${editingRepositoryName}.rsa.pub`;
    link.click();
    URL.revokeObjectURL(url);
  } catch (error) {
    showToast(error.message || "Alpine public key download failed.", "error");
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
    const minimumReleaseAge = document.getElementById("repository-minimum-release-age").value;
    const allowedRedirectHosts = document.getElementById("repository-allowed-redirect-hosts").value
      .split(",").map((value) => value.trim()).filter(Boolean);
    payload.proxy = {
      remoteUrl: document.getElementById("repository-remote-url").value.trim(),
      allowedRedirectHosts,
      contentMaxAgeMinutes: content === "" ? null : Number(content),
      metadataMaxAgeMinutes: metadata === "" ? null : Number(metadata),
      minimumReleaseAgeMinutes: recipe.format === "npm"
        ? (minimumReleaseAge === "" ? 0 : Number(minimumReleaseAge))
        : null,
      autoBlock: document.getElementById("repository-auto-block").checked,
      remoteUsername: textInputValue("repository-remote-username"),
      remotePassword: textInputValue("repository-remote-password"),
      remotePasswordConfigured: document.getElementById("repository-remote-password-clear").checked ? false : null,
      remoteBearerToken: textInputValue("repository-remote-bearer-token"),
      remoteBearerTokenConfigured: document.getElementById("repository-remote-bearer-token-clear").checked ? false : null,
      outboundProxyType: document.getElementById("repository-outbound-proxy-type").value,
      outboundProxyHost: textInputValue("repository-outbound-proxy-host"),
      outboundProxyPort: (function () {
        const port = document.getElementById("repository-outbound-proxy-port").value;
        return port === "" ? null : Number(port);
      })(),
      outboundProxyUsername: textInputValue("repository-outbound-proxy-username"),
      outboundProxyPassword: textInputValue("repository-outbound-proxy-password"),
      outboundProxyPasswordConfigured:
        document.getElementById("repository-outbound-proxy-password-clear").checked ? false : null
    };
    if (recipe.format === "pypi") {
      payload.pypi = {
        indexPath: document.getElementById("repository-pypi-index-path").value.trim()
      };
    }
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
  if (recipe?.format === "apt") {
    const architectures = document.getElementById("repository-apt-architectures").value
      .split(",")
      .map((value) => value.trim())
      .filter(Boolean);
    const validUntil = document.getElementById("repository-apt-valid-until-days").value;
    payload.apt = {
      distribution: document.getElementById("repository-apt-distribution").value.trim(),
      component: document.getElementById("repository-apt-component").value.trim(),
      architectures,
      flat: document.getElementById("repository-apt-flat").checked,
      enforceDistribution: document.getElementById("repository-apt-enforce-distribution").checked,
      metadataMode: document.getElementById("repository-apt-metadata-mode").value,
      validUntilDays: validUntil === "" ? 0 : Number(validUntil),
      origin: document.getElementById("repository-apt-origin").value.trim(),
      label: document.getElementById("repository-apt-label").value.trim()
    };
  }
  if (recipe?.format === "alpine") {
    const list = (id) => document.getElementById(id).value
      .split(",").map((value) => value.trim()).filter(Boolean);
    const upstreamKey = document.getElementById(
      "repository-alpine-upstream-public-key").value.trim();
    payload.alpine = {
      distributions: list("repository-alpine-distributions"),
      channels: list("repository-alpine-channels"),
      architectures: list("repository-alpine-architectures"),
      metadataMode: document.getElementById("repository-alpine-metadata-mode").value,
      verifyUpstreamSignatures:
        document.getElementById("repository-alpine-verify-upstream").checked,
      staleIfError: document.getElementById("repository-alpine-stale-if-error").checked,
      keyFilename: document.getElementById("repository-alpine-key-filename").value.trim(),
      signatureType: document.getElementById("repository-alpine-signature-type").value,
      description: document.getElementById("repository-alpine-description").value.trim(),
      upstreamPublicKeys: upstreamKey ? [upstreamKey] : []
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
  document.getElementById("repository-pypi-index-path").value = "/simple";
  document.getElementById("repository-allowed-redirect-hosts").value = "";
  document.getElementById("repository-remote-username").value = "";
  document.getElementById("repository-remote-password").value = "";
  document.getElementById("repository-remote-password").placeholder = "";
  document.getElementById("repository-remote-password-clear").checked = false;
  document.getElementById("repository-remote-bearer-token").value = "";
  document.getElementById("repository-remote-bearer-token").placeholder = "";
  document.getElementById("repository-remote-bearer-token-clear").checked = false;
  document.getElementById("repository-outbound-proxy-type").value = "";
  document.getElementById("repository-outbound-proxy-host").value = "";
  document.getElementById("repository-outbound-proxy-port").value = "";
  document.getElementById("repository-outbound-proxy-username").value = "";
  document.getElementById("repository-outbound-proxy-password").value = "";
  document.getElementById("repository-outbound-proxy-password").placeholder = "";
  document.getElementById("repository-outbound-proxy-password-clear").checked = false;
  document.getElementById("repository-content-max-age").value = "1440";
  document.getElementById("repository-metadata-max-age").value = "1440";
  document.getElementById("repository-minimum-release-age").value = "0";
  document.getElementById("repository-auto-block").checked = true;
  document.getElementById("repository-docker-connector-enabled").checked = false;
  document.getElementById("repository-docker-connector-port").value = "";
  document.getElementById("repository-docker-connector-public-url").value = "";
  document.getElementById("repository-cargo-require-authentication").checked = false;
  document.getElementById("repository-apt-distribution").value = "stable";
  document.getElementById("repository-apt-component").value = "main";
  document.getElementById("repository-apt-architectures").value = "amd64";
  document.getElementById("repository-apt-flat").checked = false;
  document.getElementById("repository-apt-enforce-distribution").checked = false;
  document.getElementById("repository-apt-metadata-mode").value = "PASSTHROUGH";
  document.getElementById("repository-apt-valid-until-days").value = "30";
  document.getElementById("repository-apt-origin").value = "kkRepo";
  document.getElementById("repository-apt-label").value = "kkRepo";
  document.getElementById("repository-apt-private-key").value = "";
  document.getElementById("repository-apt-key-passphrase").value = "";
  document.getElementById("repository-apt-status").textContent = "APT status not loaded.";
  document.getElementById("repository-alpine-distributions").value = "v3.23";
  document.getElementById("repository-alpine-channels").value = "main";
  document.getElementById("repository-alpine-architectures").value = "x86_64, aarch64";
  document.getElementById("repository-alpine-metadata-mode").value = "PASSTHROUGH";
  document.getElementById("repository-alpine-verify-upstream").checked = false;
  document.getElementById("repository-alpine-stale-if-error").checked = true;
  document.getElementById("repository-alpine-key-filename").value = "kkrepo-alpine.rsa.pub";
  document.getElementById("repository-alpine-signature-type").value = "RSA";
  document.getElementById("repository-alpine-description").value = "kkRepo Alpine repository";
  document.getElementById("repository-alpine-upstream-public-key").value = "";
  document.getElementById("repository-alpine-private-key").value = "";
  document.getElementById("repository-alpine-status").textContent = "Alpine status not loaded.";
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
    document.getElementById("repository-allowed-redirect-hosts").value =
      Array.isArray(repo.proxy.allowedRedirectHosts) ? repo.proxy.allowedRedirectHosts.join(", ") : "";
    document.getElementById("repository-remote-username").value = repo.proxy.remoteUsername || "";
    document.getElementById("repository-remote-password").value = "";
    document.getElementById("repository-remote-password").placeholder =
      repo.proxy.remotePasswordConfigured ? "Saved password unchanged" : "";
    document.getElementById("repository-remote-password-clear").checked = false;
    document.getElementById("repository-remote-bearer-token").value = "";
    document.getElementById("repository-remote-bearer-token").placeholder =
      repo.proxy.remoteBearerTokenConfigured ? "Saved bearer token unchanged" : "";
    document.getElementById("repository-remote-bearer-token-clear").checked = false;
    // Normalize legacy/alias values stored before the backend canonicalized the type,
    // so the select (HTTP/SOCKS only) still shows the effective choice.
    const outboundProxyType = (repo.proxy.outboundProxyType || "").toUpperCase();
    document.getElementById("repository-outbound-proxy-type").value =
      outboundProxyType === "SOCKS5" ? "SOCKS" : outboundProxyType;
    document.getElementById("repository-outbound-proxy-host").value = repo.proxy.outboundProxyHost || "";
    document.getElementById("repository-outbound-proxy-port").value = repo.proxy.outboundProxyPort ?? "";
    document.getElementById("repository-outbound-proxy-username").value = repo.proxy.outboundProxyUsername || "";
    document.getElementById("repository-outbound-proxy-password").value = "";
    document.getElementById("repository-outbound-proxy-password").placeholder =
      repo.proxy.outboundProxyPasswordConfigured ? "Saved proxy password unchanged" : "";
    document.getElementById("repository-outbound-proxy-password-clear").checked = false;
    document.getElementById("repository-content-max-age").value = repo.proxy.contentMaxAgeMinutes ?? "1440";
    document.getElementById("repository-metadata-max-age").value = repo.proxy.metadataMaxAgeMinutes ?? "1440";
    document.getElementById("repository-minimum-release-age").value =
      repo.proxy.minimumReleaseAgeMinutes ?? "0";
    document.getElementById("repository-auto-block").checked = repo.proxy.autoBlock !== false;
  }
  document.getElementById("repository-pypi-index-path").value =
    repo.pypi?.indexPath ?? "/simple";
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
  if (repo.apt) {
    document.getElementById("repository-apt-distribution").value = repo.apt.distribution || "";
    document.getElementById("repository-apt-component").value = repo.apt.component || "main";
    document.getElementById("repository-apt-architectures").value =
      Array.isArray(repo.apt.architectures) ? repo.apt.architectures.join(", ") : "amd64";
    document.getElementById("repository-apt-flat").checked = Boolean(repo.apt.flat);
    document.getElementById("repository-apt-enforce-distribution").checked =
      Boolean(repo.apt.enforceDistribution);
    document.getElementById("repository-apt-metadata-mode").value =
      repo.apt.metadataMode || (repo.type === "HOSTED" ? "RESIGN" : "PASSTHROUGH");
    document.getElementById("repository-apt-valid-until-days").value =
      repo.apt.validUntilDays ?? "0";
    document.getElementById("repository-apt-origin").value = repo.apt.origin || "kkRepo";
    document.getElementById("repository-apt-label").value = repo.apt.label || "kkRepo";
  }
  if (repo.alpine) {
    document.getElementById("repository-alpine-distributions").value =
      Array.isArray(repo.alpine.distributions) ? repo.alpine.distributions.join(", ") : "v3.23";
    document.getElementById("repository-alpine-channels").value =
      Array.isArray(repo.alpine.channels) ? repo.alpine.channels.join(", ") : "main";
    document.getElementById("repository-alpine-architectures").value =
      Array.isArray(repo.alpine.architectures)
        ? repo.alpine.architectures.join(", ") : "x86_64, aarch64";
    document.getElementById("repository-alpine-metadata-mode").value =
      repo.alpine.metadataMode || (repo.type === "PROXY" ? "PASSTHROUGH" : "RESIGN");
    document.getElementById("repository-alpine-verify-upstream").checked =
      Boolean(repo.alpine.verifyUpstreamSignatures);
    document.getElementById("repository-alpine-stale-if-error").checked =
      repo.alpine.staleIfError !== false;
    document.getElementById("repository-alpine-key-filename").value =
      repo.alpine.keyFilename || `${repo.name}.rsa.pub`;
    document.getElementById("repository-alpine-signature-type").value =
      repo.alpine.signatureType || "RSA";
    document.getElementById("repository-alpine-description").value =
      repo.alpine.description || "kkRepo Alpine repository";
    document.getElementById("repository-alpine-upstream-public-key").value =
      Array.isArray(repo.alpine.upstreamPublicKeys)
        ? (repo.alpine.upstreamPublicKeys[0] || "") : "";
  }
  refreshRepositoryRecipeControls();
  if (repo.format === "conan") loadConanStatus(repo.name);
  if (repo.format === "apt") loadAptStatus(repo.name);
  if (repo.format === "alpine") loadAlpineStatus(repo.name);
  clearRequiredFieldErrors(repositoryRequiredFields);
  openFormModal("repository-form", "repository-online");
}

function hideRepositoryForm() {
  closeRepositoryRecipeCombobox();
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
    markInputValidity(document.getElementById("repository-recipe"), true);
    showToast("Pick a recipe before saving.", "error");
    document.getElementById("repository-recipe-trigger").focus();
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

function uiThemeLabel(theme) {
  if (theme === "default") return "Default";
  if (theme === "jfrog") return "JFrog";
  return String(theme || "")
    .split("-")
    .filter(Boolean)
    .map((part) => part.charAt(0).toUpperCase() + part.slice(1))
    .join(" ");
}

function syncUiThemePreviewStatus() {
  const settings = window.kkrepoI18n?.settings?.();
  const themeSelect = document.getElementById("ui-default-theme");
  const previewStatus = document.getElementById("ui-theme-preview-status");
  if (!settings || !themeSelect || !previewStatus) return;
  previewStatus.hidden = themeSelect.value === (settings.defaultTheme || "default");
}

function previewUiTheme() {
  const settings = window.kkrepoI18n?.settings?.();
  const themeSelect = document.getElementById("ui-default-theme");
  if (!settings || !themeSelect || !window.kkrepoTheme) return;
  themeSelect.value = window.kkrepoTheme.applyTheme(
    themeSelect.value,
    settings.supportedDefaultThemes);
  syncUiThemePreviewStatus();
}

function syncUiSettingsForm() {
  const settings = window.kkrepoI18n?.settings?.();
  const languageSelect = document.getElementById("ui-default-language");
  const themeSelect = document.getElementById("ui-default-theme");
  const previewStatus = document.getElementById("ui-theme-preview-status");
  const previewTheme = themeSelect && previewStatus && !previewStatus.hidden
    ? themeSelect.value
    : null;
  if (languageSelect && settings) {
    languageSelect.value = settings.defaultLanguage || "en";
  }
  if (themeSelect && settings) {
    const themes = settings.supportedDefaultThemes?.length
      ? settings.supportedDefaultThemes
      : ["default"];
    themeSelect.replaceChildren(...themes.map((theme) => {
      const option = document.createElement("option");
      option.value = theme;
      option.textContent = uiThemeLabel(theme);
      return option;
    }));
    themeSelect.value = previewTheme && themes.includes(previewTheme)
      ? previewTheme
      : settings.defaultTheme || "default";
  }
  syncUiThemePreviewStatus();
  clearRequiredFieldErrors(uiSettingsRequiredFields);
}

async function loadUiSettings() {
  if (!window.kkrepoI18n) return;
  await window.kkrepoI18n.ready();
  syncUiSettingsForm();
}

async function saveUiSettings() {
  const button = document.getElementById("save-ui-settings-button");
  const languageSelect = document.getElementById("ui-default-language");
  const themeSelect = document.getElementById("ui-default-theme");
  if (!button || !languageSelect || !themeSelect || !window.kkrepoI18n) return;
  if (!validateRequiredFields(uiSettingsRequiredFields)) return;
  button.disabled = true;
  try {
    await window.kkrepoI18n.saveSettings(languageSelect.value, themeSelect.value);
    syncUiSettingsForm();
    showToast("UI settings saved.", "ok");
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
  securityProviderAttributes.ldap = editableSecurityProviderAttributes(settings.attributes, ["name"]);
  refreshSecurityLdapRequiredMarkers();
  syncSecurityProviderJsonFromForm("ldap", { force: true });
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
  const payload = securityLdapFormValue({ maskSecrets: false });
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
  securityProviderAttributes.oidc = editableSecurityProviderAttributes(settings.attributes);
  refreshSecurityOidcRequiredMarkers();
  syncSecurityProviderJsonFromForm("oidc", { force: true });
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
  const payload = securityOidcFormValue({ maskSecrets: false });
  payload.issuerUri = payload.issuer;
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
    ["Candidate backlog", summary.candidateBacklog ?? 0],
    ["Pending tasks", summary.pendingTasks ?? 0],
    ["Running", summary.runningTasks ?? 0],
    ["Failed", summary.failedTasks ?? 0],
    ["Complete assets", summary.completeAssets ?? 0],
    ["Partial / stale", (summary.partialAssets ?? 0) + (summary.staleAssets ?? 0)],
    ["Policy blocks", summary.blockedAssets ?? 0],
    ["Critical / high", `${summary.criticalFindings ?? 0} / ${summary.highFindings ?? 0}`]
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

function renderSecurityScanTasks() {
  document.getElementById("security-scan-task-table").innerHTML =
    securityScanState.tasks.map((task) => {
      const retry = ["FAILED", "CANCELLED"].includes(task.status)
        ? `<button class="row-action security-scan-task-retry" data-id="${task.id}" type="button">retry</button>`
        : "";
      const cancel = ["PENDING", "RETRY_WAIT", "RUNNING"].includes(task.status)
        ? `<button class="row-action security-scan-task-cancel" data-id="${task.id}" type="button">cancel</button>`
        : "";
      const rescan = task.assetId
        ? `<button class="row-action security-scan-asset-rescan" data-id="${task.assetId}" type="button">rescan</button>`
        : "";
      return `
        <tr>
          <td>${escapeHtml(task.id)}</td>
          <td>${escapeHtml(task.repository || `#${task.repositoryId}`)}</td>
          <td>${escapeHtml(task.assetId ?? "-")}</td>
          <td>${escapeHtml(task.stage)}</td>
          <td>${escapeHtml(task.reason)}</td>
          <td><span class="state-badge compact ${securityScanTone(task.status)}">${escapeHtml(task.status)}</span></td>
          <td>${escapeHtml(`${task.attempts}/${task.maxAttempts}`)}</td>
          <td>${escapeHtml(formatDateTime(task.leaseUntil))}</td>
          <td title="${escapeHtml(task.lastErrorSummary || "")}">${escapeHtml(task.lastErrorCode || "-")}</td>
          <td class="actions-column">${retry}${cancel}${rescan}</td>
        </tr>`;
    }).join("")
      || '<tr><td colspan="10" class="placeholder">No scan tasks are visible.</td></tr>';
}

function renderSecurityScanFindingRepositories(finding) {
  const repositories = Array.isArray(finding.repositories)
    ? finding.repositories.filter(Boolean)
    : [];
  if (repositories.length === 0) return "-";
  const fullLabel = repositories.join(", ");
  const visibleLabel = repositories.length > 1
    ? `${repositories[0]} +${repositories.length - 1}`
    : repositories[0];
  return `<span class="security-scan-finding-repositories" title="${escapeHtml(fullLabel)}">${escapeHtml(visibleLabel)}</span>`;
}

function securityScanExternalHttpUrl(value) {
  if (!value) return "";
  try {
    const url = new URL(String(value));
    if (!["http:", "https:"].includes(url.protocol) || url.username || url.password) return "";
    return url.href;
  } catch {
    return "";
  }
}

function securityScanFindingAdvisoryUrl(finding) {
  return [finding.primaryUrl, finding.dataSource]
    .map(securityScanExternalHttpUrl)
    .find(Boolean) || "";
}

function renderSecurityScanFindingAdvisory(finding) {
  const label = finding.advisoryId || `#${finding.id}`;
  const href = securityScanFindingAdvisoryUrl(finding);
  const content = `<code>${escapeHtml(label)}</code>`;
  if (!href) return content;
  return `
    <a class="security-scan-advisory-link"
       href="${escapeHtml(href)}"
       target="_blank"
       rel="noopener noreferrer"
       title="Open vulnerability advisory"
       aria-label="${escapeHtml(`Open advisory ${label} in a new tab`)}">
      ${content}
      ${lucideIcon("external-link")}
    </a>`;
}

function renderSecurityScanFindings() {
  document.getElementById("security-scan-finding-table").innerHTML =
    securityScanState.findings.map((finding) => `
      <tr>
        <td>${renderSecurityScanSeverity(finding.severity)}</td>
        <td>${renderSecurityScanFindingAdvisory(finding)}</td>
        <td>${renderSecurityScanFindingRepositories(finding)}</td>
        <td title="${escapeHtml(finding.packageUrl || "")}">${escapeHtml(finding.packageName)}</td>
        <td>${escapeHtml(finding.installedVersion || "-")}</td>
        <td>${escapeHtml((finding.fixedVersions || []).join(", ") || "-")}</td>
        <td>${renderSecurityScanFindingWaiverStatus(finding)}</td>
        <td class="actions-column security-scan-finding-actions">${renderSecurityScanFindingActions(finding)}</td>
      </tr>`).join("")
      || '<tr><td colspan="8" class="placeholder">No known vulnerability findings are visible.</td></tr>';
}

function renderSecurityScanFindingWaiverStatus(finding) {
  const expired = Number(finding.expiredWaiverCount || 0);
  const targetCount = Number(finding.waiverTargetCount || 0);
  const waivedTargetCount = Number(finding.waivedTargetCount || 0);
  if (waivedTargetCount > 0) {
    const label = waivedTargetCount < targetCount
      ? `Partially waived · ${waivedTargetCount}/${targetCount}`
      : "Waived";
    return `<button class="state-badge compact ok security-scan-waiver-status-button security-scan-finding-waiver-detail" data-id="${escapeHtml(finding.id)}" type="button" title="View applicable waiver details">${escapeHtml(label)}</button>`;
  }
  if (expired > 0) {
    const label = expired === 1 ? "Expired" : `Expired · ${expired}`;
    return `<button class="state-badge compact warn security-scan-waiver-status-button security-scan-finding-waiver-detail" data-id="${escapeHtml(finding.id)}" type="button" title="View expired waiver details">${escapeHtml(label)}</button>`;
  }
  return '<span class="state-badge compact">Not waived</span>';
}

function renderSecurityScanFindingWaiverAction(finding) {
  const targetCount = Number(finding.waiverTargetCount || 0);
  const waivedTargetCount = Number(finding.waivedTargetCount || 0);
  const fullyWaived = targetCount > 0 && waivedTargetCount >= targetCount;
  if (fullyWaived) {
    return `<button class="row-action security-scan-finding-waive" data-id="${escapeHtml(finding.id)}" type="button" title="All associated repository artifacts are already waived" disabled>waived</button>`;
  }
  const label = waivedTargetCount > 0 ? "waive remaining" : "waive";
  return `<button class="row-action security-scan-finding-waive" data-id="${escapeHtml(finding.id)}" type="button">${label}</button>`;
}

function renderSecurityScanFindingActions(finding) {
  const view = `<button class="row-action security-scan-finding-view" data-id="${escapeHtml(finding.id)}" type="button" title="View finding details">view</button>`;
  return `${view}${renderSecurityScanFindingWaiverAction(finding)}`;
}

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

function renderSecurityScanRepositories() {
  document.getElementById("security-scan-repository-table").innerHTML =
    securityScanState.repositories.map((repository) => {
      const config = repository.config;
      return `
        <tr>
          <td>${renderSecurityScanRepositoryStatus(config?.enabled === true)}</td>
          <td>${escapeHtml(repository.name)}</td>
          <td>${escapeHtml(repository.format)}</td>
          <td>${escapeHtml(repository.type)}</td>
          <td>${escapeHtml(repository.profileName || "Unavailable profile")}</td>
          <td>${escapeHtml(repository.policyName || "Built-in critical baseline")}</td>
          <td>${escapeHtml(config?.enforcementMode || "AUDIT")}</td>
          <td class="actions-column"><button class="row-action security-scan-repository-edit" data-id="${repository.id}" type="button">configure</button></td>
        </tr>`;
    }).join("")
      || '<tr><td colspan="8" class="placeholder">No repositories are visible.</td></tr>';
}

function renderSecurityScanPolicies() {
  document.getElementById("security-scan-policy-table").innerHTML =
    securityScanState.policies.map((policy) => `
      <tr><td>${escapeHtml(policy.name)}</td>
      <td>${escapeHtml(policy.revision)}</td><td>${escapeHtml(policy.blockSeverity)}</td>
      <td>${policy.requireCompleteInventory ? "yes" : "no"}</td>
      <td>${escapeHtml(formatSecurityScanValidity(policy.maxResultAgeSeconds) || "No expiry")}</td>
      <td class="actions-column"><button class="row-action security-scan-policy-edit" data-id="${escapeHtml(policy.id)}" type="button">edit</button></td></tr>`).join("")
      || '<tr><td colspan="6" class="placeholder">No policies are visible.</td></tr>';
}

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
        <td class="actions-column"><button class="row-action security-scan-waiver-delete" data-id="${waiver.id}" type="button">delete</button></td></tr>`;
    }).join("")
      || '<tr><td colspan="9" class="placeholder">No waivers are visible.</td></tr>';
}

function securityScanPageParams(key) {
  const page = securityScanPages[key];
  const params = new URLSearchParams();
  params.set("after", String(page.after || 0));
  params.set("limit", String(page.size || SECURITY_SCAN_DEFAULT_PAGE_SIZE));
  if (page.query) params.set("q", page.query);
  return params;
}

async function fetchSecurityScanPage(key) {
  const endpoint = securityScanListEndpoints[key];
  return fetchJson(
    `/internal/security/scanning/${endpoint}?${securityScanPageParams(key).toString()}`,
    { items: [], nextAfter: null },
    `Failed to load scan ${key}`);
}

function resetSecurityScanPage(key) {
  const page = securityScanPages[key];
  page.after = 0;
  page.cursors = [0];
  page.page = 0;
  page.nextAfter = null;
}

function renderSecurityScanPagination(key) {
  const page = securityScanPages[key];
  const items = securityScanState[key] || [];
  const summary = document.querySelector(`[data-security-scan-page-summary="${key}"]`);
  const label = document.querySelector(`[data-security-scan-page-label="${key}"]`);
  const previous = document.querySelector(
    `[data-security-scan-page-action="prev"][data-security-scan-page-list="${key}"]`);
  const next = document.querySelector(
    `[data-security-scan-page-action="next"][data-security-scan-page-list="${key}"]`);
  const size = document.querySelector(`[data-security-scan-page-size="${key}"]`);
  const query = document.querySelector(`[data-security-scan-query="${key}"]`);
  if (summary) {
    summary.textContent = items.length === 1
      ? "1 result on this page"
      : `${items.length} results on this page`;
  }
  if (label) label.textContent = `Page ${page.page + 1}`;
  if (previous) previous.disabled = page.page <= 0;
  if (next) next.disabled = page.nextAfter == null;
  if (size) size.value = String(page.size);
  if (query && document.activeElement !== query) query.value = page.query;
}

function renderSecurityScanList(key) {
  const renderers = {
    runs: renderSecurityScanRuns,
    tasks: renderSecurityScanTasks,
    findings: renderSecurityScanFindings,
    repositories: renderSecurityScanRepositories,
    policies: renderSecurityScanPolicies,
    waivers: renderSecurityScanWaivers
  };
  renderers[key]?.();
  renderSecurityScanPagination(key);
}

async function loadSecurityScanList(key) {
  const payload = await fetchSecurityScanPage(key);
  const items = Array.isArray(payload?.items) ? payload.items : [];
  const page = securityScanPages[key];
  if (items.length === 0 && page.page > 0) {
    page.cursors.pop();
    page.page -= 1;
    page.after = page.cursors.at(-1) || 0;
    return loadSecurityScanList(key);
  }
  securityScanState[key] = items;
  page.nextAfter = payload?.nextAfter ?? null;
  renderSecurityScanList(key);
}

async function searchSecurityScanList(key) {
  const input = document.querySelector(`[data-security-scan-query="${key}"]`);
  securityScanPages[key].query = input?.value.trim() || "";
  resetSecurityScanPage(key);
  await loadSecurityScanList(key);
}

async function clearSecurityScanListSearch(key) {
  securityScanPages[key].query = "";
  const input = document.querySelector(`[data-security-scan-query="${key}"]`);
  if (input) input.value = "";
  resetSecurityScanPage(key);
  await loadSecurityScanList(key);
}

async function moveSecurityScanPage(key, direction) {
  const page = securityScanPages[key];
  if (direction === "next") {
    if (page.nextAfter == null) return;
    page.after = page.nextAfter;
    page.cursors.push(page.after);
    page.page += 1;
  } else {
    if (page.page <= 0) return;
    page.cursors.pop();
    page.page -= 1;
    page.after = page.cursors.at(-1) || 0;
  }
  await loadSecurityScanList(key);
}

async function resizeSecurityScanPage(key, size) {
  securityScanPages[key].size = Number(size) || SECURITY_SCAN_DEFAULT_PAGE_SIZE;
  resetSecurityScanPage(key);
  await loadSecurityScanList(key);
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
    securityScanState[key] = Array.isArray(payload?.items) ? payload.items : [];
    securityScanPages[key].nextAfter = payload?.nextAfter ?? null;
  });
  renderSecurityScanning();
  const emptyLaterPages = keys.filter(
    (key) => securityScanState[key].length === 0 && securityScanPages[key].page > 0);
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

function editSecurityScanRepository(repositoryId) {
  const repository = securityScanState.repositories.find((item) => Number(item.id) === Number(repositoryId));
  if (!repository) return;
  const config = repository.config || {};
  document.getElementById("security-scan-repository-id").value = repository.id;
  document.getElementById("security-scan-repository-name").value = repository.name;
  document.getElementById("security-scan-profile-name").value = repository.profileName || "Unavailable profile";
  document.getElementById("security-scan-profile-id").value = config.profileId || "";
  document.getElementById("security-scan-repository-policy-name").value =
    repository.policyName || "Built-in critical baseline";
  document.getElementById("security-scan-policy-id").value = config.policyId || "";
  document.getElementById("security-scan-enforcement-mode").value = config.enforcementMode || "AUDIT";
  document.getElementById("security-scan-pending-action").value = config.pendingAction || "ALLOW";
  document.getElementById("security-scan-failure-action").value = config.failureAction || "ALLOW";
  document.getElementById("security-scan-partial-action").value = config.partialAction || "ALLOW";
  setSecurityScanResultValidity(config.maxResultAgeSeconds);
  document.getElementById("security-scan-enabled").checked = Boolean(config.enabled);
  applySecurityScanRepositoryScope(repository, config);
  document.getElementById("security-scan-advanced").open =
    [config.pendingAction, config.failureAction, config.partialAction].includes("BLOCK");
  openFormModal("security-scan-repository-form", "security-scan-enabled");
}

function hideSecurityScanRepositoryForm() {
  document.getElementById("security-scan-repository-id").value = "";
  closeFormModal("security-scan-repository-form");
}

function optionalNumber(id) {
  const value = document.getElementById(id).value;
  return value === "" ? null : Number(value);
}

async function saveSecurityScanRepository(event) {
  event.preventDefault();
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
      `/internal/security/scanning/findings/${encodeURIComponent(findingId)}/waivers`,
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

function securityScanFindingDetailValue(value) {
  if (Array.isArray(value)) return value.filter(Boolean).join(", ") || "-";
  return value == null || value === "" ? "-" : String(value);
}

function renderSecurityScanFindingDetailSection(title, fields) {
  return `
    <section class="security-scan-finding-detail-section">
      <h3>${escapeHtml(title)}</h3>
      <dl>
        ${fields.map(([label, value]) => `
          <div>
            <dt>${escapeHtml(label)}</dt>
            <dd>${escapeHtml(securityScanFindingDetailValue(value))}</dd>
          </div>`).join("")}
      </dl>
    </section>`;
}

function showSecurityScanFindingDetail(findingId) {
  const finding = securityScanState.findings.find(
    (item) => Number(item.id) === Number(findingId));
  if (!finding) {
    showToast("Finding details are no longer available. Refresh and try again.", "error");
    return;
  }
  const waiverCoverage = Number(finding.waiverTargetCount || 0) > 0
    ? `${Number(finding.waivedTargetCount || 0)} of ${Number(finding.waiverTargetCount)} artifacts waived`
    : `${Number(finding.activeWaiverCount || 0)} active, ${Number(finding.expiredWaiverCount || 0)} expired`;
  const packageLabel =
    [finding.packageName || finding.packageUrl || "Unknown package", finding.installedVersion]
      .filter(Boolean)
      .join(" @ ");
  document.getElementById("security-scan-finding-detail-title").textContent = "Finding details";
  document.getElementById("security-scan-finding-detail-content").innerHTML = `
    <div class="security-scan-finding-detail-hero">
      <div class="security-scan-finding-detail-kicker">
        ${renderSecurityScanSeverity(finding.severity)}
        ${renderSecurityScanFindingAdvisory(finding)}
      </div>
      <h3>${escapeHtml(finding.title || "Known vulnerability finding")}</h3>
      <p>${escapeHtml(packageLabel)}</p>
    </div>
    <div class="security-scan-finding-detail-highlights">
      <div><span>Repositories</span><strong>${escapeHtml(securityScanFindingDetailValue(finding.repositories))}</strong></div>
      <div><span>Fixed versions</span><strong>${escapeHtml(securityScanFindingDetailValue(finding.fixedVersions))}</strong></div>
      <div><span>Waiver coverage</span><strong>${escapeHtml(waiverCoverage)}</strong></div>
    </div>
    <div class="security-scan-finding-detail-sections">
      ${renderSecurityScanFindingDetailSection("Vulnerability", [
        ["Aliases", finding.aliases],
        ["CVSS score", finding.cvssScore],
        ["CVSS vector", finding.cvssVector],
        ["Severity source", finding.severitySource],
        ["Source", finding.dataSource],
        ["Source status", finding.sourceStatus]
      ])}
      ${renderSecurityScanFindingDetailSection("Traceability", [
        ["Scan run", finding.scanRunId],
        ["Finding ID", finding.id],
        ["Package URL", finding.packageUrl],
        ["Locations", finding.locations],
        ["Primary URL", finding.primaryUrl]
      ])}
    </div>`;
  openFormModal("security-scan-finding-detail", "security-scan-close-finding-detail-button");
}

function hideSecurityScanFindingDetail() {
  closeFormModal("security-scan-finding-detail");
}

async function showCreateSecurityScanWaiverForm(findingId) {
  const form = document.getElementById("security-scan-waiver-form");
  form.reset();
  clearRequiredFieldErrors(securityScanWaiverRequiredFields);
  securityScanWaiverContext = null;
  try {
    const response = await fetch(
      `/internal/security/scanning/findings/${encodeURIComponent(findingId)}/waiver-context`,
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

async function securityScanTaskAction(kind, id) {
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

async function deleteSecurityScanWaiver(waiverId) {
  if (!window.confirm(`Delete security scan waiver ${waiverId}?`)) return;
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
  const rules = [];
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
  const index = cleanupRuns.findIndex((item) => String(item.id) === String(run.id));
  if (index >= 0) {
    cleanupRuns[index] = run;
  } else if (cleanupRunPage.page === 0) {
    const hadMore = cleanupRunPage.nextBefore != null;
    cleanupRuns.push(run);
    cleanupRuns = cleanupRuns
      .sort((left, right) => Number(right.id) - Number(left.id));
    const overflowed = cleanupRuns.length > cleanupRunPage.size;
    cleanupRuns = cleanupRuns.slice(0, cleanupRunPage.size);
    cleanupRunPage.nextBefore = overflowed || hadMore
      ? cleanupRuns.at(-1)?.id ?? null
      : null;
  }
  renderCleanupRuns();
}

function renderCleanupRunPagination() {
  document.getElementById("cleanup-run-page-summary").textContent =
    cleanupRuns.length === 1
      ? "1 result on this page"
      : `${cleanupRuns.length} results on this page`;
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
  if (!cleanupRuns.length) {
    body.innerHTML = '<tr><td colspan="7" class="empty-cell">No cleanup runs yet.</td></tr>';
    return;
  }
  body.innerHTML = cleanupRuns.map((run) => {
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

function resetCleanupPolicyPage() {
  cleanupPolicyPage.after = 0;
  cleanupPolicyPage.cursors = [0];
  cleanupPolicyPage.page = 0;
  cleanupPolicyPage.nextAfter = null;
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
  cleanupRuns = page.items || [];
  cleanupRunPage.nextBefore = page.nextBefore ?? null;
}

async function loadCleanupRuns() {
  try {
    const page = await fetchCleanupRunPage();
    applyCleanupRunPage(page);
    if (cleanupRuns.length === 0 && cleanupRunPage.page > 0) {
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
    repositories = await repositoryResponse.json();
    applyCleanupRunPage(runPage);
    if (cleanupRuns.length === 0 && cleanupRunPage.page > 0) {
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

function cleanupRepositoryCandidates() {
  const format = document.getElementById("cleanup-policy-format").value;
  return repositories
    .filter((repository) => lowerOrEmpty(repository.format) === lowerOrEmpty(format))
    .filter((repository) => ["hosted", "proxy"].includes(lowerOrEmpty(repository.type)))
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
  const retainInput = document.getElementById("cleanup-policy-retain-count");
  retainInput.disabled = !capability?.retainCountSupported;
  if (retainInput.disabled) retainInput.value = "";
  setCleanupFieldHelp("cleanup-policy-retain-help", capability?.retainCountSupported
    ? "Uses this format's protocol version comparator."
    : "Version retention is not enabled for this format yet; pattern and age rules remain available.");
  const downloadedInput = document.getElementById("cleanup-policy-downloaded-days");
  downloadedInput.disabled = !capability?.lastDownloadedSupported;
  if (downloadedInput.disabled) {
    downloadedInput.value = "";
  }
  setCleanupFieldHelp("cleanup-policy-downloaded-help", capability?.lastDownloadedSupported
    ? "Successful authorized GET requests persist a shared download watermark; HEAD requests do not count. Artifacts without a download timestamp are skipped."
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

function clearFieldHelpHideTimer() {
  clearTimeout(fieldHelpHideTimer);
  fieldHelpHideTimer = null;
}

function positionFieldHelpPopover(trigger) {
  const popover = document.getElementById("field-help-popover");
  const triggerRect = trigger.getBoundingClientRect();
  if (triggerRect.bottom < 0 || triggerRect.top > window.innerHeight) {
    hideFieldHelpPopover();
    return;
  }
  const popoverRect = popover.getBoundingClientRect();
  const viewportPadding = 10;
  const gap = 7;
  let left = triggerRect.left + (triggerRect.width / 2) - (popoverRect.width / 2);
  left = Math.max(viewportPadding, Math.min(left, window.innerWidth - popoverRect.width - viewportPadding));
  let top = triggerRect.bottom + gap;
  if (top + popoverRect.height > window.innerHeight - viewportPadding
      && triggerRect.top - popoverRect.height - gap >= viewportPadding) {
    top = triggerRect.top - popoverRect.height - gap;
  }
  popover.style.left = `${Math.round(left)}px`;
  popover.style.top = `${Math.round(top)}px`;
}

function showFieldHelpPopover(trigger) {
  const message = trigger?.dataset.tooltip?.trim();
  if (!message) return;
  clearFieldHelpHideTimer();
  if (activeFieldHelpTrigger && activeFieldHelpTrigger !== trigger) {
    activeFieldHelpTrigger.removeAttribute("aria-describedby");
  }
  activeFieldHelpTrigger = trigger;
  const popover = document.getElementById("field-help-popover");
  popover.textContent = message;
  popover.hidden = false;
  trigger.setAttribute("aria-describedby", popover.id);
  positionFieldHelpPopover(trigger);
}

function hideFieldHelpPopover() {
  clearFieldHelpHideTimer();
  if (activeFieldHelpTrigger) activeFieldHelpTrigger.removeAttribute("aria-describedby");
  activeFieldHelpTrigger = null;
  document.getElementById("field-help-popover").hidden = true;
}

function scheduleFieldHelpPopoverHide() {
  clearFieldHelpHideTimer();
  fieldHelpHideTimer = setTimeout(() => {
    const popover = document.getElementById("field-help-popover");
    const triggerActive = activeFieldHelpTrigger
      && (activeFieldHelpTrigger.matches(":hover") || document.activeElement === activeFieldHelpTrigger);
    if (!triggerActive && !popover.matches(":hover")) hideFieldHelpPopover();
  }, 220);
}

function bindFieldHelpTooltips() {
  const popover = document.getElementById("field-help-popover");
  document.querySelectorAll(".field-help").forEach((trigger) => {
    trigger.addEventListener("mouseenter", () => showFieldHelpPopover(trigger));
    trigger.addEventListener("mouseleave", scheduleFieldHelpPopoverHide);
    trigger.addEventListener("focus", () => showFieldHelpPopover(trigger));
    trigger.addEventListener("blur", scheduleFieldHelpPopoverHide);
    trigger.addEventListener("keydown", (event) => {
      if (event.key === "Escape") hideFieldHelpPopover();
    });
  });
  popover.addEventListener("mouseenter", clearFieldHelpHideTimer);
  popover.addEventListener("mouseleave", scheduleFieldHelpPopoverHide);
  document.addEventListener("scroll", () => {
    if (activeFieldHelpTrigger && !popover.hidden) positionFieldHelpPopover(activeFieldHelpTrigger);
  }, true);
  window.addEventListener("resize", () => {
    if (activeFieldHelpTrigger && !popover.hidden) positionFieldHelpPopover(activeFieldHelpTrigger);
  });
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
  const criteria = policy.criteria || {};
  populateCleanupFormatOptions(policy.format);
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
    criteria,
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
  cleanupTryRunPolicyId = Number(policyId);
  cleanupTryRunTrigger = trigger;
  cleanupTryRunSubmitting = false;
  document.getElementById("cleanup-try-run-policy-name").textContent = view.policy.name;
  document.getElementById("cleanup-try-run-repository-count").textContent = String((view.repositories || []).length);
  document.getElementById("cleanup-try-run-scan-limit").value = String(view.policy.scanLimitPerRepository || 1000);
  setCleanupTryRunError();
  openFormModal("cleanup-try-run-form", "cleanup-try-run-scan-limit");
}

function hideCleanupTryRunDialog(options = {}) {
  if (cleanupTryRunSubmitting && options.force !== true) return;
  const restoreFocus = options.restoreFocus !== false;
  const trigger = cleanupTryRunTrigger;
  cleanupTryRunPolicyId = null;
  cleanupTryRunTrigger = null;
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
    cleanupRuns = run.run ? [run.run] : [];
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
  const policyId = cleanupTryRunPolicyId;
  if (policyId == null) return;
  const button = document.getElementById("start-cleanup-try-run-button");
  const cancelButton = document.getElementById("cancel-cleanup-try-run-button");
  const closeButton = document.querySelector("#cleanup-try-run-form-modal .form-modal-close");
  cleanupTryRunSubmitting = true;
  button.disabled = true;
  cancelButton.disabled = true;
  closeButton.disabled = true;
  button.textContent = "Starting…";
  try {
    const queued = await queueCleanupRun(policyId, "TRY_RUN", scanLimit);
    if (queued) hideCleanupTryRunDialog({ restoreFocus: false, force: true });
  } finally {
    cleanupTryRunSubmitting = false;
    button.disabled = false;
    cancelButton.disabled = false;
    closeButton.disabled = false;
    button.textContent = "Start Try Run";
  }
}

function startCleanupRun(policyId, mode, trigger = null) {
  const view = cleanupPolicyView(policyId);
  if (!view) return;
  if (mode === "TRY_RUN") {
    showCleanupTryRunDialog(policyId, trigger);
    return;
  }
  if (!window.confirm(`Run cleanup policy "${view.policy.name}" now? Matching artifacts can be deleted.`)) return;
  queueCleanupRun(policyId, mode);
}

async function monitorCleanupRun(runId, label) {
  const token = ++cleanupRunPollToken;
  while (token === cleanupRunPollToken) {
    await new Promise((resolve) => window.setTimeout(resolve, 1000));
    const response = await fetch(`/internal/cleanup/runs/${runId}/summary`);
    if (!response.ok) {
      showToast(`${label} status refresh failed: ${await responseErrorMessage(response)}`, "error");
      return;
    }
    const run = await response.json();
    updateCleanupRunInList(run);
    if (String(activeCleanupRunDetailId) === String(runId)) {
      if (cleanupRunTerminal(run?.state)) {
        const detail = await loadCleanupRunDetails(runId);
        if (detail) await renderCleanupRun(detail, { reloadItems: true });
      } else if (cleanupRunDetailView) {
        await renderCleanupRun({ ...cleanupRunDetailView, run });
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
  activeCleanupRunDetailId = String(runId);
  cleanupRunDetailTrigger = trigger;
  cleanupRunDetailItemGroups = [];
  cleanupRunDetailItemsLoaded = false;
  cleanupRunDetailView = null;
  document.getElementById("cleanup-run-detail-title").textContent = `Cleanup run #${runId}`;
  document.getElementById("cleanup-run-detail-content").innerHTML = '<p class="form-note">Loading run details…</p>';
  openFormModal("cleanup-run-detail");
  document.getElementById("cleanup-run-detail").scrollTop = 0;
}

function hideCleanupRunDetail(options = {}) {
  const trigger = cleanupRunDetailTrigger;
  activeCleanupRunDetailId = null;
  cleanupRunDetailTrigger = null;
  cleanupRunDetailItemGroups = [];
  cleanupRunDetailItemsLoaded = false;
  cleanupRunDetailView = null;
  cleanupRunDetailLoadSequence++;
  closeFormModal("cleanup-run-detail");
  if (options.restoreFocus !== false && trigger?.isConnected) trigger.focus();
}

async function viewCleanupRun(runId, trigger = null) {
  showCleanupRunDetail(runId, trigger);
  const sequence = ++cleanupRunDetailLoadSequence;
  try {
    const view = await loadCleanupRunDetails(runId);
    if (!view) throw new Error("Cleanup run details are unavailable");
    if (sequence !== cleanupRunDetailLoadSequence
        || String(activeCleanupRunDetailId) !== String(runId)) return;
    updateCleanupRunInList(view.run);
    await renderCleanupRun(view, { reloadItems: true });
    if (!cleanupRunTerminal(view.run?.state)) monitorCleanupRun(runId, "Cleanup run");
  } catch (error) {
    if (sequence === cleanupRunDetailLoadSequence
        && String(activeCleanupRunDetailId) === String(runId)) {
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
    if (String(activeCleanupRunDetailId) === String(runId)) {
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
  const pattern = criteria.pattern
    ? `${criteria.patternType === "REGEX" ? "Regular expression" : "Wildcard"} · ${criteria.pattern}`
    : "All names and paths";
  return `
    <dl class="cleanup-run-rule-grid">
      <div><dt>Name/path pattern</dt><dd>${escapeHtml(pattern)}</dd></div>
      <div><dt>Published age</dt><dd>${criteria.publishedOlderThanDays == null ? "Not configured" : `Older than ${escapeHtml(criteria.publishedOlderThanDays)} days`}</dd></div>
      <div><dt>Download activity</dt><dd>${criteria.lastDownloadedOlderThanDays == null ? "Not configured" : `Last downloaded more than ${escapeHtml(criteria.lastDownloadedOlderThanDays)} days ago`}</dd></div>
      <div><dt>Version retention</dt><dd>${criteria.retainCount == null ? "Not configured" : `Keep newest ${escapeHtml(criteria.retainCount)} versions per family`}</dd></div>
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
  if (!run || String(activeCleanupRunDetailId) !== String(run.id)) return;
  const renderSequence = ++cleanupRunDetailLoadSequence;
  const repositories = view.repositories || [];
  if (options.reloadItems || !cleanupRunDetailItemsLoaded) {
    const itemsByRepository = view.itemsByRepository || {};
    cleanupRunDetailItemGroups = repositories.map((repository) => ({
      repository,
      items: itemsByRepository[String(repository.id)] || itemsByRepository[repository.id] || [],
      loadError: null
    }));
    cleanupRunDetailItemsLoaded = true;
  }
  cleanupRunDetailView = view;
  if (renderSequence !== cleanupRunDetailLoadSequence
      || String(activeCleanupRunDetailId) !== String(run.id)) return;

  const itemRows = cleanupRunDetailItemGroups.flatMap(({ repository, items, loadError }) => {
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

async function deleteCleanupPolicy(policyId) {
  const view = cleanupPolicyView(policyId);
  if (!view || !window.confirm(`Delete cleanup policy "${view.policy.name}"? Historical runs remain available.`)) return;
  try {
    const response = await fetch(`/internal/cleanup/policies/${policyId}?revision=${view.policy.revision}`, { method: "DELETE" });
    if (!response.ok) throw new Error(await responseErrorMessage(response));
    showToast("Cleanup policy deleted.", "ok");
    await loadCleanupPolicies();
  } catch (error) {
    showToast(`Cleanup policy deletion failed: ${error.message}`, "error");
  }
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
  if (view === "cleanup-policies") {
    selectCleanupTab(
      cleanupTabFromHash() || "policies",
      { updateHash: false });
    loadCleanupPolicies();
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
  if (view === "security-scanning") {
    selectSecurityScanTab(
        securityScanTabFromHash() || "overview",
        { updateHash: false });
    loadSecurityScanning();
  }
  if (view === "security-audit-log") loadAuditLogs(0);
  if (view === "ui-settings") loadUiSettings();
  if (view === "repository-data-migration") loadRepositoryDataMigrationJobs();
  return true;
}

function applyHashRoute() {
  const view = viewFromHash();
  if (!view) return false;
  if (view === "cleanup-policies"
      && document.getElementById("cleanup-policies-view").classList.contains("is-active")) {
    selectCleanupTab(
      cleanupTabFromHash() || "policies",
      { updateHash: false });
    return true;
  }
  if (view === "security-scanning"
      && document.getElementById("security-scanning-view").classList.contains("is-active")) {
    selectSecurityScanTab(
        securityScanTabFromHash() || "overview",
        { updateHash: false });
    return true;
  }
  return switchView(view, { updateHash: false });
}

applyOriginAwarePlaceholders();
initializeSideGroups();
[
  ["repository-form", hideRepositoryForm],
  ["blobstore-form", hideBlobStoreForm],
  ["cleanup-policy-form", hideCleanupPolicyForm],
  ["cleanup-try-run-form", hideCleanupTryRunDialog],
  ["cleanup-run-detail", hideCleanupRunDetail],
  ["security-user-form", hideSecurityUserForm],
  ["security-role-form", hideSecurityRoleForm],
  ["security-privilege-form", hideSecurityPrivilegeForm],
  ["security-api-key-form", hideSecurityApiKeyForm],
  ["security-scan-repository-form", hideSecurityScanRepositoryForm],
  ["security-scan-policy-form", hideSecurityScanPolicyForm],
  ["security-scan-waiver-form", hideSecurityScanWaiverForm],
  ["security-scan-waiver-detail", hideSecurityScanWaiverDetail],
  ["security-scan-finding-detail", hideSecurityScanFindingDetail]
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
document.getElementById("my-token-menu-item").addEventListener("click", () => {
  closeUserMenu();
  window.location.href = "/browse/#browse/my-token";
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
  const actionMenu = document.getElementById("cleanup-policy-action-menu");
  if (!actionMenu.hidden
      && !actionMenu.contains(event.target)
      && !event.target.closest(".cleanup-policy-more-actions")) {
    closeCleanupPolicyActionMenu();
  }
});
document.addEventListener("keydown", (event) => {
  if (handleFormModalKeydown(event)) return;
  if (handleCleanupPolicyActionMenuKeydown(event)) return;
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
bindRepositoryRecipeCombobox();
document.getElementById("repository-recipe").addEventListener("change", refreshRepositoryRecipeControls);
document.getElementById("repository-docker-connector-enabled").addEventListener("change", refreshDockerConnectorControls);
document.getElementById("repository-apt-flat").addEventListener("change", refreshAptControls);
document.getElementById("repository-conan-refresh-status").addEventListener("click", () => loadConanStatus());
document.getElementById("repository-apt-enforce-distribution").addEventListener("change", refreshAptControls);
document.getElementById("repository-apt-metadata-mode").addEventListener("change", refreshAptControls);
document.getElementById("repository-apt-refresh-status").addEventListener("click", () => loadAptStatus());
document.getElementById("repository-apt-rebuild").addEventListener("click", rebuildAptMetadata);
document.getElementById("repository-apt-rotate-key").addEventListener("click", rotateAptSigningKey);
document.getElementById("repository-apt-generate-key").addEventListener("click", generateAptSigningKey);
document.getElementById("repository-alpine-metadata-mode").addEventListener("change", refreshAlpineControls);
document.getElementById("repository-alpine-refresh-status").addEventListener("click", () => loadAlpineStatus());
document.getElementById("repository-alpine-rebuild").addEventListener("click", rebuildAlpineMetadata);
document.getElementById("repository-alpine-rotate-key").addEventListener("click", () => rotateAlpineSigningKey(false));
document.getElementById("repository-alpine-generate-key").addEventListener("click", () => rotateAlpineSigningKey(true));
document.getElementById("repository-alpine-download-key").addEventListener("click", downloadAlpinePublicKey);
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
document.getElementById("create-cleanup-policy-button").addEventListener("click", showCreateCleanupPolicyForm);
document.getElementById("refresh-cleanup-policy-button").addEventListener("click", loadCleanupPolicies);
document.getElementById("cleanup-policy-page-prev").addEventListener(
  "click", () => changeCleanupPolicyPage("prev"));
document.getElementById("cleanup-policy-page-next").addEventListener(
  "click", () => changeCleanupPolicyPage("next"));
document.getElementById("cleanup-policy-page-size").addEventListener(
  "change", (event) => resizeCleanupPolicyPage(event.currentTarget.value));
document.getElementById("cleanup-run-page-prev").addEventListener(
  "click", () => changeCleanupRunPage("prev"));
document.getElementById("cleanup-run-page-next").addEventListener(
  "click", () => changeCleanupRunPage("next"));
document.getElementById("cleanup-run-page-size").addEventListener(
  "change", (event) => resizeCleanupRunPage(event.currentTarget.value));
document.getElementById("cancel-cleanup-policy-button").addEventListener("click", hideCleanupPolicyForm);
document.getElementById("cleanup-policy-form").addEventListener("submit", saveCleanupPolicy);
document.getElementById("cancel-cleanup-try-run-button").addEventListener("click", hideCleanupTryRunDialog);
document.getElementById("cleanup-try-run-form").addEventListener("submit", submitCleanupTryRun);
document.getElementById("cleanup-try-run-scan-limit").addEventListener("input", () => setCleanupTryRunError());
bindFieldHelpTooltips();
bindCleanupRepositoryPicker();
document.querySelectorAll("[data-cleanup-tab]").forEach((button) => {
  button.addEventListener("click", () => selectCleanupTab(button.dataset.cleanupTab));
  button.addEventListener("keydown", handleCleanupTabKeydown);
});
document.getElementById("cleanup-policy-format").addEventListener("change", () => {
  refreshCleanupRepositoryOptions([]);
  refreshCleanupScheduleFields();
});
document.getElementById("cleanup-policy-cron-expression").addEventListener("input", () => {
  refreshCleanupScheduleFields();
  scheduleCleanupSchedulePreview();
});
document.getElementById("cleanup-policy-time-zone").addEventListener("input", scheduleCleanupSchedulePreview);
document.getElementById("cleanup-policy-time-zone").addEventListener("change", scheduleCleanupSchedulePreview);
document.getElementById("cleanup-policy-table").addEventListener("click", (event) => {
  const editButton = event.target.closest(".cleanup-policy-edit");
  if (editButton) {
    showEditCleanupPolicyForm(editButton.dataset.id);
    return;
  }
  const moreButton = event.target.closest(".cleanup-policy-more-actions");
  if (moreButton) openCleanupPolicyActionMenu(moreButton.dataset.id, moreButton);
});
document.getElementById("cleanup-policy-table").addEventListener("keydown", (event) => {
  const moreButton = event.target.closest(".cleanup-policy-more-actions");
  if (!moreButton || !["ArrowDown", "ArrowUp"].includes(event.key)) return;
  event.preventDefault();
  openCleanupPolicyActionMenu(
    moreButton.dataset.id,
    moreButton,
    event.key === "ArrowDown" ? "first" : "last");
});
document.getElementById("cleanup-policy-action-menu").addEventListener("click", (event) => {
  const item = event.target.closest("[data-cleanup-action]");
  if (!item || item.disabled || cleanupActionMenuPolicyId == null) return;
  const policyId = cleanupActionMenuPolicyId;
  const actionTrigger = cleanupActionMenuTrigger;
  const action = item.dataset.cleanupAction;
  const enableSchedule = item.dataset.enable === "true";
  closeCleanupPolicyActionMenu();
  if (action === "try-run") startCleanupRun(policyId, "TRY_RUN", actionTrigger);
  if (action === "execute") startCleanupRun(policyId, "EXECUTE");
  if (action === "schedule") setCleanupPolicyScheduleEnabled(policyId, enableSchedule, null);
  if (action === "delete") deleteCleanupPolicy(policyId);
});
document.getElementById("cleanup-policy-action-menu").addEventListener("focusout", () => {
  setTimeout(() => {
    const menu = document.getElementById("cleanup-policy-action-menu");
    if (!menu.contains(document.activeElement)
        && document.activeElement !== cleanupActionMenuTrigger) {
      closeCleanupPolicyActionMenu();
    }
  }, 0);
});
document.addEventListener("scroll", () => positionCleanupPolicyActionMenu(), true);
window.addEventListener("resize", positionCleanupPolicyActionMenu);
document.getElementById("cleanup-run-table").addEventListener("click", (event) => {
  const viewButton = event.target.closest(".cleanup-run-view");
  if (viewButton) {
    viewCleanupRun(viewButton.dataset.id, viewButton);
    return;
  }
  const cancelButton = event.target.closest(".cleanup-run-cancel");
  if (cancelButton) cancelCleanupRun(cancelButton.dataset.id);
});
document.getElementById("cleanup-run-detail-content").addEventListener("click", (event) => {
  const cancelButton = event.target.closest(".cleanup-run-cancel");
  if (cancelButton) cancelCleanupRun(cancelButton.dataset.id);
});
document.getElementById("docker-connectors-refresh-button").addEventListener("click", refreshDockerConnectors);
document.getElementById("docker-cache-clear-button").addEventListener("click", clearDockerCache);

document.getElementById("security-scan-refresh-button").addEventListener("click", loadSecurityScanning);
document.querySelectorAll("[data-scan-tab]").forEach((button) => {
  button.addEventListener("click", () => selectSecurityScanTab(button.dataset.scanTab));
  button.addEventListener("keydown", handleSecurityScanTabKeydown);
});
document.querySelectorAll("[data-security-scan-list-form]").forEach((form) => {
  form.addEventListener("submit", (event) => {
    event.preventDefault();
    searchSecurityScanList(form.dataset.securityScanListForm);
  });
});
document.querySelectorAll("[data-security-scan-clear]").forEach((button) => {
  button.addEventListener(
    "click",
    () => clearSecurityScanListSearch(button.dataset.securityScanClear));
});
document.querySelectorAll("[data-security-scan-page-action]").forEach((button) => {
  button.addEventListener(
    "click",
    () => moveSecurityScanPage(
      button.dataset.securityScanPageList,
      button.dataset.securityScanPageAction));
});
document.querySelectorAll("[data-security-scan-page-size]").forEach((select) => {
  select.addEventListener(
    "change",
    () => resizeSecurityScanPage(select.dataset.securityScanPageSize, select.value));
});
document.getElementById("security-scan-repository-table").addEventListener("click", (event) => {
  const editButton = event.target.closest(".security-scan-repository-edit");
  if (editButton) editSecurityScanRepository(editButton.dataset.id);
});
document.getElementById("security-scan-repository-form").addEventListener("submit", saveSecurityScanRepository);
document.getElementById("security-scan-cancel-repository-button").addEventListener(
  "click", hideSecurityScanRepositoryForm);
document.getElementById("security-scan-create-policy-button").addEventListener(
  "click", showCreateSecurityScanPolicyForm);
document.getElementById("security-scan-cancel-policy-button").addEventListener(
  "click", hideSecurityScanPolicyForm);
document.getElementById("security-scan-policy-form").addEventListener(
  "submit", saveSecurityScanPolicy);
bindRequiredFieldErrors(securityScanPolicyRequiredFields);
document.getElementById("security-scan-policy-table").addEventListener("click", (event) => {
  const editButton = event.target.closest(".security-scan-policy-edit");
  if (editButton) showEditSecurityScanPolicyForm(editButton.dataset.id);
});
document.getElementById("security-scan-cancel-waiver-button").addEventListener(
  "click", hideSecurityScanWaiverForm);
document.getElementById("security-scan-waiver-form").addEventListener("submit", createSecurityScanWaiver);
document.getElementById("security-scan-close-waiver-detail-button").addEventListener(
  "click", hideSecurityScanWaiverDetail);
document.getElementById("security-scan-view-all-waivers-button").addEventListener(
  "click", viewAllSecurityScanWaivers);
document.getElementById("security-scan-close-finding-detail-button").addEventListener(
  "click", hideSecurityScanFindingDetail);
bindRequiredFieldErrors(securityScanWaiverRequiredFields);
document.getElementById("security-scan-finding-table").addEventListener("click", (event) => {
  const viewButton = event.target.closest(".security-scan-finding-view");
  if (viewButton) {
    showSecurityScanFindingDetail(viewButton.dataset.id);
    return;
  }
  const detailButton = event.target.closest(".security-scan-finding-waiver-detail");
  if (detailButton) {
    showSecurityScanWaiverDetail(detailButton.dataset.id);
    return;
  }
  const waiveButton = event.target.closest(".security-scan-finding-waive");
  if (waiveButton) showCreateSecurityScanWaiverForm(waiveButton.dataset.id);
});
document.getElementById("security-scan-task-table").addEventListener("click", (event) => {
  const retryButton = event.target.closest(".security-scan-task-retry");
  if (retryButton) {
    securityScanTaskAction("retry", retryButton.dataset.id);
    return;
  }
  const cancelButton = event.target.closest(".security-scan-task-cancel");
  if (cancelButton) {
    securityScanTaskAction("cancel", cancelButton.dataset.id);
    return;
  }
  const rescanButton = event.target.closest(".security-scan-asset-rescan");
  if (rescanButton) securityScanRescan(rescanButton.dataset.id);
});
document.getElementById("security-scan-waiver-table").addEventListener("click", (event) => {
  const deleteButton = event.target.closest(".security-scan-waiver-delete");
  if (deleteButton) deleteSecurityScanWaiver(deleteButton.dataset.id);
});

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
bindSecurityProviderJson("ldap");
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
bindSecurityProviderJson("oidc");
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
document.getElementById("ui-default-theme").addEventListener("change", previewUiTheme);
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

hydrateSessionControls();
loadCurrentSession({ quiet: true }).then((session) => {
  if (!session) return;
  loadRepositoryRecipes().then(() => {
    if (!applyHashRoute()) {
      loadRepositories();
    }
  });
  loadBlobStores();
});
