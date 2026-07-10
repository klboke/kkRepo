// Live data backing the /browse UI.
//   state.mode = "repos" → repo list
//   state.mode = "tree" → Nexus-style expandable tree for state.repo (root = "")
// Children of each path are fetched lazily on first expand and cached in `treeCache`.

installCsrfFetch();

const state = { mode: "repos", repo: null, path: "" };
const treeCache = new Map(); // key = `${repo}::${path}`, value = entries[]
const expanded = new Set();   // keys (same shape as treeCache) currently expanded
const treeMountLoads = new WeakMap();
let userMenuCloseTimer = null;

let repositoriesCache = [];
let uploadRepositoriesCache = [];
let uploadRepositoriesLoaded = false;
let componentsCache = [];
let searchRequestSeq = 0;
let uploadSpecsCache = new Map();
let uploadAssetCount = 1;
let repositorySort = { key: "name", direction: "asc" };
let activeSearchFormat = "maven2";
let currentSession = null;
let currentPermissions = [];
let adminBootstrapStatus = null;
let currentApiKeysCache = [];
let currentApiKeysLoaded = false;
let latestGeneratedApiToken = "";

const APP_HASH_PREFIX = "browse";
const BROWSE_HASH = `${APP_HASH_PREFIX}/browse`;
const DEFAULT_SEARCH_FORMAT = "maven2";
const COMPONENT_SEARCH_LIMIT = 20;
const AUTH_SNAPSHOT_KEY = "nexusPlus.authSnapshot";
const AUTH_SNAPSHOT_MAX_AGE_MS = 10 * 60 * 1000;
let pendingLoginReturnTo = readPendingLoginReturnTo();
const SEARCH_ROUTE_FORMAT = {
  cargo: "cargo",
  go: "go",
  helm: "helm",
  maven2: "maven2",
  nuget: "nuget",
  pub: "pub",
  pypi: "pypi",
  rubygems: "rubygems",
  yum: "yum",
  npm: "npm",
};
const FORMAT_ROUTE_SEGMENT = {
  cargo: "cargo",
  go: "go",
  helm: "helm",
  maven2: "maven2",
  nuget: "nuget",
  pub: "pub",
  pypi: "pypi",
  rubygems: "rubygems",
  yum: "yum",
  npm: "npm",
};

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

function browseListHash() {
  return `#${BROWSE_HASH}`;
}

function viewHash(view) {
  return `#${APP_HASH_PREFIX}/${view}`;
}

function normalizeSearchFormat(format) {
  return SEARCH_ROUTE_FORMAT[format] || DEFAULT_SEARCH_FORMAT;
}

function searchHash(format) {
  const normalized = normalizeSearchFormat(format);
  return `#${APP_HASH_PREFIX}/search/${FORMAT_ROUTE_SEGMENT[normalized]}`;
}

function repositoryBrowseHash(repoName, path = "") {
  const hash = `#${BROWSE_HASH}:${encodeURIComponent(repoName)}`;
  if (!path) return hash;
  const params = new URLSearchParams({ path });
  return `${hash}?${params.toString()}`;
}

function componentBrowsePath(component) {
  const format = (component.format || "").toLowerCase();
  const group = (component.group || "").trim();
  const name = (component.name || "").trim();
  const version = (component.version || "").trim();
  if (!name) return "";
  if (format === "maven2") {
    return [group ? group.replaceAll(".", "/") : "", name, version].filter(Boolean).join("/");
  }
  if (format === "pypi") {
    return [name, version].filter(Boolean).join("/");
  }
  if (format === "npm") {
    return group ? `@${group}/${name}` : name;
  }
  if (format === "cargo") {
    return ["crates", name, version].filter(Boolean).join("/");
  }
  if (format === "go") {
    return name;
  }
  if (format === "pub") {
    return version ? `packages/${name}/versions/${version}.tar.gz` : `packages/${name}`;
  }
  return "";
}

function parseBrowseHash() {
  const hash = window.location.hash.replace(/^#/, "");
  if (!hash) return null;
  if (hash === APP_HASH_PREFIX || hash === BROWSE_HASH) return { view: "browse", repo: null };
  if (hash === `${APP_HASH_PREFIX}/welcome`) return { view: "welcome" };
  if (hash === `${APP_HASH_PREFIX}/upload`) return { view: "upload" };
  if (hash === `${APP_HASH_PREFIX}/my-token`) return { view: "my-token" };
  if (hash === `${APP_HASH_PREFIX}/search`) return { view: "search", searchFormat: DEFAULT_SEARCH_FORMAT };
  if (hash.startsWith(`${APP_HASH_PREFIX}/search/`)) {
    const rawFormat = hash.slice(`${APP_HASH_PREFIX}/search/`.length);
    return { view: "search", searchFormat: normalizeSearchFormat(rawFormat) };
  }
  const prefix = `${BROWSE_HASH}:`;
  if (!hash.startsWith(prefix)) return null;
  const routeValue = hash.slice(prefix.length);
  const separator = routeValue.indexOf("?");
  const encodedRepo = separator === -1 ? routeValue : routeValue.slice(0, separator);
  const query = separator === -1 ? "" : routeValue.slice(separator + 1);
  if (!encodedRepo) return { view: "browse", repo: null };
  const path = new URLSearchParams(query).get("path") || "";
  try {
    return { view: "browse", repo: decodeURIComponent(encodedRepo), path };
  } catch {
    return { view: "browse", repo: encodedRepo, path };
  }
}

function pushBrowseRoute(hash) {
  const target = `/browse/${hash}`;
  const current = `${window.location.pathname}${window.location.search}${window.location.hash}`;
  if (current !== target) {
    window.history.pushState(null, "", target);
  }
}

function canonicalizeBrowseRoute() {
  if (!window.location.hash.startsWith(`#${APP_HASH_PREFIX}`)) return;
  window.history.replaceState(null, "", `/browse/${window.location.hash}`);
}

function repositoryExists(repoName) {
  return repositoriesCache.some((repo) => repo.name === repoName);
}

function currentRepository() {
  return repositoriesCache.find((repo) => repo.name === state.repo) || null;
}

function currentReturnTo() {
  return `${window.location.pathname}${window.location.search}${window.location.hash}` || "/browse/";
}

function safeLocalReturnTo(value) {
  if (!value || !value.startsWith("/") || value.startsWith("//")) return "";
  return value;
}

function readPendingLoginReturnTo() {
  const params = new URLSearchParams(window.location.search);
  if (params.get("login") !== "1") return "";
  return safeLocalReturnTo(params.get("returnTo")) || "/browse/#browse/welcome";
}

function openPendingLoginIfRequested() {
  if (!pendingLoginReturnTo || currentSession || adminBootstrapStatus?.required) return;
  const returnTo = pendingLoginReturnTo;
  pendingLoginReturnTo = "";
  const hash = window.location.hash && window.location.hash.startsWith(`#${APP_HASH_PREFIX}`)
    ? window.location.hash
    : "#browse/welcome";
  window.history.replaceState(null, "", `/browse/${hash}`);
  window.nexusPlusLogin.open(returnTo);
}

function sessionIdentity(session) {
  if (!session || !session.userId) return "";
  return `${session.source || ""}:${session.realmId || ""}:${session.userId}`;
}

function displaySource(source) {
  return source == null ? "" : String(source);
}

function readAuthSnapshot() {
  try {
    const raw = sessionStorage.getItem(AUTH_SNAPSHOT_KEY);
    if (!raw) return null;
    const snapshot = JSON.parse(raw);
    if (!snapshot || !snapshot.session || !snapshot.session.userId) return null;
    if (Date.now() - Number(snapshot.savedAt || 0) > AUTH_SNAPSHOT_MAX_AGE_MS) {
      sessionStorage.removeItem(AUTH_SNAPSHOT_KEY);
      return null;
    }
    return snapshot;
  } catch {
    sessionStorage.removeItem(AUTH_SNAPSHOT_KEY);
    return null;
  }
}

function writeAuthSnapshot() {
  if (!currentSession || !currentSession.userId) {
    sessionStorage.removeItem(AUTH_SNAPSHOT_KEY);
    return;
  }
  sessionStorage.setItem(AUTH_SNAPSHOT_KEY, JSON.stringify({
    session: currentSession,
    permissions: currentPermissions,
    savedAt: Date.now(),
  }));
}

function hydrateAuthSnapshot() {
  const snapshot = readAuthSnapshot();
  if (!snapshot) return null;
  currentSession = snapshot.session;
  currentPermissions = Array.isArray(snapshot.permissions)
    ? snapshot.permissions.filter(Boolean)
    : [];
  updateTopbarAuth();
  return snapshot.session;
}

async function fetchSession() {
  const res = await fetch("/internal/security/session", {
    headers: { Accept: "application/json" },
    cache: "no-store",
  });
  if (res.status === 401 || res.status === 403) return null;
  if (!res.ok) throw new Error(`Failed to load session: ${res.status}`);
  return res.json();
}

async function fetchAdminBootstrapStatus() {
  const res = await fetch("/internal/security/bootstrap", {
    headers: { Accept: "application/json" },
    cache: "no-store",
  });
  if (!res.ok) throw new Error(`Failed to load administrator bootstrap status: ${res.status}`);
  return res.json();
}

function renderAdminBootstrap() {
  const panel = document.getElementById("admin-bootstrap-panel");
  if (!panel) return;
  const required = Boolean(adminBootstrapStatus && adminBootstrapStatus.required);
  panel.hidden = !required;
  const userInput = document.getElementById("admin-bootstrap-user");
  if (userInput && adminBootstrapStatus) {
    userInput.value = `${adminBootstrapStatus.source || "Local"}/${adminBootstrapStatus.userId || "admin"}`;
  }
  const anonymousInput = document.getElementById("admin-bootstrap-anonymous-enabled");
  if (anonymousInput && adminBootstrapStatus) {
    anonymousInput.checked = Boolean(adminBootstrapStatus.anonymousAccessEnabled);
  }
}

function setAdminBootstrapStatus(message, type = "") {
  const status = document.getElementById("admin-bootstrap-status");
  if (!status) return;
  status.textContent = message || "";
  status.classList.toggle("error", type === "error");
  status.classList.toggle("ok", type === "ok");
}

async function responseMessage(response, fallback) {
  try {
    const payload = await response.json();
    return payload.message || payload.error || fallback;
  } catch {
    return fallback;
  }
}

async function submitAdminBootstrap(event) {
  event.preventDefault();
  const passwordInput = document.getElementById("admin-bootstrap-password");
  const confirmInput = document.getElementById("admin-bootstrap-password-confirm");
  const anonymousInput = document.getElementById("admin-bootstrap-anonymous-enabled");
  const submitButton = document.getElementById("admin-bootstrap-submit");
  const password = passwordInput ? passwordInput.value : "";
  const passwordConfirm = confirmInput ? confirmInput.value : "";
  const minLength = adminBootstrapStatus && adminBootstrapStatus.minPasswordLength
    ? adminBootstrapStatus.minPasswordLength
    : 8;

  if (password.length < minLength) {
    setAdminBootstrapStatus(`Password must be at least ${minLength} characters.`, "error");
    passwordInput?.focus();
    return;
  }
  if (password !== passwordConfirm) {
    setAdminBootstrapStatus("Password confirmation does not match.", "error");
    confirmInput?.focus();
    return;
  }

  submitButton.disabled = true;
  setAdminBootstrapStatus("Creating administrator...");
  try {
    const createResponse = await fetch("/internal/security/bootstrap/admin", {
      method: "POST",
      headers: { "Content-Type": "application/json", Accept: "application/json" },
      cache: "no-store",
      body: JSON.stringify({
        password,
        passwordConfirm,
        anonymousAccessEnabled: Boolean(anonymousInput && anonymousInput.checked),
      }),
    });
    if (!createResponse.ok) {
      throw new Error(await responseMessage(createResponse, `Administrator setup failed: ${createResponse.status}`));
    }
    const loginResponse = await fetch("/internal/security/login", {
      method: "POST",
      headers: { "Content-Type": "application/json", Accept: "application/json" },
      cache: "no-store",
      body: JSON.stringify({
        username: `${adminBootstrapStatus?.source || "Local"}/${adminBootstrapStatus?.userId || "admin"}`,
        password,
        returnTo: "/admin/#admin/repository/repositories",
      }),
    });
    if (!loginResponse.ok) {
      throw new Error(await responseMessage(loginResponse, `Administrator created, but login failed: ${loginResponse.status}`));
    }
    const loginResult = await loginResponse.json();
    sessionStorage.removeItem(AUTH_SNAPSHOT_KEY);
    setAdminBootstrapStatus("Administrator created.", "ok");
    window.location.href = loginResult.returnTo || "/admin/#admin/repository/repositories";
  } catch (error) {
    setAdminBootstrapStatus(error.message || "Administrator setup failed.", "error");
    submitButton.disabled = false;
  }
}

async function fetchPermissions() {
  if (!currentSession) return [];
  const res = await fetch("/internal/security/permissions", {
    headers: { Accept: "application/json" },
    cache: "no-store",
  });
  if (res.status === 401 || res.status === 403) return [];
  if (!res.ok) throw new Error(`Failed to load permissions: ${res.status}`);
  const payload = await res.json();
  return payload.map((permission) => permission.id || permission).filter(Boolean);
}

function permissionPartMatches(grantedPart, requestedPart) {
  const requested = (requestedPart || "*").toLowerCase();
  return (grantedPart || "*").split(",").some((part) => {
    const normalized = part.trim().toLowerCase();
    return normalized === "*" || normalized === requested;
  });
}

function permissionMatches(granted, requested) {
  const grantedParts = (granted || "").toLowerCase().split(":");
  const requestedParts = (requested || "").toLowerCase().split(":");
  const length = Math.max(grantedParts.length, requestedParts.length);
  for (let index = 0; index < length; index += 1) {
    if (!permissionPartMatches(grantedParts[index], requestedParts[index])) return false;
  }
  return true;
}

function can(permission) {
  return currentPermissions.some((granted) => permissionMatches(granted, permission));
}

function hasAdminEntryPermission() {
  return can("nexus:*");
}

function canDeleteBrowseContent() {
  return Boolean(currentSession && hasAdminEntryPermission());
}

function hasUploadPermission() {
  return can("nexus:component:create") || hasRepositoryUploadPermission();
}

function hasRepositoryUploadPermission() {
  return currentPermissions.some((permission) => {
    const parts = (permission || "").toLowerCase().split(":");
    if (parts.length < 5) return false;
    if (parts[0] !== "nexus" || parts[1] !== "repository-view") return false;
    return permissionPartMatches(parts[4], "add");
  });
}

function canUseUpload() {
  if (!currentSession || !hasUploadPermission()) return false;
  if (!uploadRepositoriesLoaded) return true;
  return uploadableRepositories(uploadRepositoriesCache).length > 0;
}

function updateTopbarAuth() {
  const adminLink = document.getElementById("admin-workspace-link");
  const loginButton = document.getElementById("login-button");
  const userMenu = document.getElementById("user-menu");
  const currentUser = document.getElementById("current-user");
  const uploadNav = document.getElementById("upload-nav");

  const signedIn = Boolean(currentSession && currentSession.userId);
  if (adminLink) {
    adminLink.hidden = !signedIn || !hasAdminEntryPermission();
  }
  loginButton.hidden = signedIn;
  userMenu.hidden = !signedIn;
  if (!signedIn) closeUserMenu();
  if (signedIn) {
    const source = currentSession.source ? `${displaySource(currentSession.source)}/` : "";
    currentUser.textContent = `${source}${currentSession.userId}`;
  }

  const uploadVisible = canUseUpload();
  uploadNav.hidden = !uploadVisible;
  if (!uploadVisible && document.getElementById("upload-view").classList.contains("is-active")) {
    showRepositoryList();
  }
  if (!signedIn && document.getElementById("my-token-view").classList.contains("is-active")) {
    showWelcome();
  }
}

function login() {
  window.nexusPlusLogin.open(currentReturnTo());
}

function logout() {
  sessionStorage.removeItem(AUTH_SNAPSHOT_KEY);
  window.location.href = `/internal/security/logout?returnTo=${encodeURIComponent("/browse/#browse/welcome")}`;
}

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

function repositoryBaseUrl(repoName = state.repo) {
  return new URL(`/repository/${encodeURIComponent(repoName)}/`, window.location.origin).href;
}

function dockerRepositoryBaseUrl(repo = currentRepository()) {
  if (!repo) return "";
  const docker = repo.docker || {};
  if (docker.connectorEnabled && docker.connectorPublicUrl) {
    return docker.connectorPublicUrl.replace(/\/+$/, "");
  }
  if (docker.connectorEnabled && docker.connectorPort) {
    return `${window.location.protocol}//${window.location.hostname}:${docker.connectorPort}`;
  }
  return new URL(`/v2/${encodeURIComponent(repo.name)}`, window.location.origin).href.replace(/\/+$/, "");
}

async function fetchRepositories() {
  const res = await fetch("/internal/repositories", { headers: { Accept: "application/json" }, cache: "no-store" });
  if (!res.ok) throw new Error(`Failed to load repositories: ${res.status}`);
  const data = await res.json();
  return data.map(normalizeRepository);
}

async function fetchUploadableRepositories() {
  if (!currentSession) return [];
  const res = await fetch("/internal/repositories/uploadable", {
    headers: { Accept: "application/json" },
    cache: "no-store",
  });
  if (res.status === 401 || res.status === 403) return [];
  if (!res.ok) throw new Error(`Failed to load upload repositories: ${res.status}`);
  const data = await res.json();
  return data.map(normalizeRepository);
}

function normalizeRepository(repo) {
  return {
    name: repo.name,
    type: (repo.type || "").toLowerCase(),
    format: (repo.format || "").toLowerCase(),
    online: repo.online,
    url: repo.url,
    docker: repo.docker || null,
    hostedVersionPolicy: repo.hosted ? repo.hosted.versionPolicy : null,
    status: repo.online ? "Online" : "Offline",
  };
}

async function fetchUploadSpecs() {
  const res = await fetch("/service/rest/v1/formats/upload-specs", { headers: { Accept: "application/json" } });
  if (!res.ok) throw new Error(`Failed to load upload specs: ${res.status}`);
  const specs = await res.json();
  return new Map(specs.map((spec) => [spec.format, spec]));
}

async function fetchChildren(repo, path) {
  const key = `${repo}::${path}`;
  if (treeCache.has(key)) return treeCache.get(key);
  const url = `/internal/browse/${encodeURIComponent(repo)}${path ? `?path=${encodeURIComponent(path)}` : ""}`;
  const res = await fetch(url, { headers: { Accept: "application/json" } });
  if (!res.ok) throw new Error(`Browse failed: ${res.status}`);
  const data = await res.json();
  treeCache.set(key, data.entries);
  return data.entries;
}

async function fetchSearchComponents(format, keyword) {
  const params = new URLSearchParams();
  const q = (keyword || "").trim();
  if (q) params.set("q", q);
  params.set("format", normalizeSearchFormat(format));
  params.set("limit", String(COMPONENT_SEARCH_LIMIT));
  const res = await fetch(`/internal/search/components?${params.toString()}`, {
    headers: { Accept: "application/json" },
    cache: "no-store",
  });
  if (!res.ok) throw new Error(`Search failed: ${res.status}`);
  return res.json();
}

function repoIcon(type) {
  if (type === "group") return '<span class="repo-icon group">▣</span>';
  if (type === "proxy") return '<span class="repo-icon proxy">▤</span>';
  return '<span class="repo-icon hosted">▥</span>';
}

// --- inline SVG icons (Nexus-style) -----------------------------------------

const ICON_FOLDER = `<svg class="tree-icon" viewBox="0 0 16 14" xmlns="http://www.w3.org/2000/svg" aria-hidden="true">
  <path d="M1 3 L1 13 L15 13 L15 4 L8 4 L7 3 Z" fill="#f4c34a" stroke="#c79320" stroke-width="0.5"/>
  <path d="M1 3 L7 3 L8 4 L15 4 L15 5 L1 5 Z" fill="#d99820"/>
</svg>`;

const ICON_ARCHIVE = `<svg class="tree-icon" viewBox="0 0 16 14" xmlns="http://www.w3.org/2000/svg" aria-hidden="true">
  <rect x="1" y="3" width="14" height="3" fill="#e07a1e" stroke="#a85a14" stroke-width="0.5"/>
  <rect x="1" y="6" width="14" height="7" fill="#f0a050" stroke="#a85a14" stroke-width="0.5"/>
  <rect x="6" y="3" width="4" height="10" fill="#d9892f" stroke="#a85a14" stroke-width="0.3"/>
</svg>`;

const ICON_POM = `<svg class="tree-icon" viewBox="0 0 16 16" xmlns="http://www.w3.org/2000/svg" aria-hidden="true">
  <path d="M3 1 L10 1 L13 4 L13 15 L3 15 Z" fill="#ffffff" stroke="#7a98c1" stroke-width="0.8"/>
  <path d="M10 1 L10 4 L13 4 Z" fill="#cfd8e3"/>
  <circle cx="8" cy="10" r="2.6" fill="#3478c4"/>
  <circle cx="8" cy="10" r="1.1" fill="#ffffff"/>
</svg>`;

const ICON_HASH = `<svg class="tree-icon" viewBox="0 0 16 16" xmlns="http://www.w3.org/2000/svg" aria-hidden="true">
  <path d="M2 4 L9 4 L11 6 L11 14 L2 14 Z" fill="#ffffff" stroke="#9aa3ad" stroke-width="0.8"/>
  <path d="M5 2 L12 2 L14 4 L14 12 L11 12 L11 6 L9 4 L5 4 Z" fill="#ffffff" stroke="#9aa3ad" stroke-width="0.8"/>
</svg>`;

const ICON_FILE = `<svg class="tree-icon" viewBox="0 0 16 16" xmlns="http://www.w3.org/2000/svg" aria-hidden="true">
  <path d="M3 1 L10 1 L13 4 L13 15 L3 15 Z" fill="#ffffff" stroke="#7a98c1" stroke-width="0.8"/>
  <path d="M10 1 L10 4 L13 4 Z" fill="#cfd8e3"/>
</svg>`;

const VERSION_DIR_RE = /^\d/;

function iconForDir(name) {
  // Nexus uses an orange archive box for version directories (1.6, 1.7.0, 1.0-SNAPSHOT…),
  // yellow folder for everything else.
  return VERSION_DIR_RE.test(name) ? ICON_ARCHIVE : ICON_FOLDER;
}

function iconForFile(name) {
  if (name.endsWith(".tgz")) return ICON_ARCHIVE;
  if (name.endsWith(".pom") || name.endsWith(".xml")) return ICON_POM;
  if (/\.(sha1|sha256|sha512|md5|asc)$/.test(name)) return ICON_HASH;
  return ICON_FILE;
}

// --- repo list ---------------------------------------------------------------

function repositoryRows(repos, filter, hostedOnly) {
  const needle = (filter || "").trim().toLowerCase();
  return repos.filter((repo) => {
    if (hostedOnly && repo.type !== "hosted") return false;
    if (!needle) return true;
    return `${repo.name} ${repo.type} ${repo.format} ${repo.status}`.toLowerCase().includes(needle);
  });
}

function sortRepositoryRows(rows) {
  return [...rows].sort((a, b) => {
    const left = (a[repositorySort.key] || "").toString();
    const right = (b[repositorySort.key] || "").toString();
    const primary = left.localeCompare(right, undefined, { numeric: true, sensitivity: "base" });
    const fallback = a.name.localeCompare(b.name, undefined, { numeric: true, sensitivity: "base" });
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
  renderRepoList();
}

function updateRepositorySortHeaders() {
  document.querySelectorAll("[data-repo-sort]").forEach((button) => {
    const active = button.dataset.repoSort === repositorySort.key;
    const direction = active ? repositorySort.direction : null;
    const indicator = button.querySelector(".repo-sort-indicator");
    button.classList.toggle("is-active", active);
    button.setAttribute("aria-label", `${button.dataset.repoSort} sort ${direction || "none"}`);
    button.closest("th").setAttribute(
      "aria-sort",
      !active ? "none" : direction === "asc" ? "ascending" : "descending",
    );
    if (indicator) indicator.textContent = active ? (direction === "asc" ? "↑" : "↓") : "";
  });
}

function repositoryClientUrl(repo) {
  if (repo.format === "docker") {
    return dockerRepositoryBaseUrl(repo);
  }
  const path = repo.url || `/repository/${encodeURIComponent(repo.name)}/`;
  try {
    return new URL(path, window.location.origin).href;
  } catch {
    return path;
  }
}

function renderBrowseHeading() {
  const browseView = document.getElementById("browse-view");
  browseView.querySelector(".page-heading").innerHTML = `
    <span class="heading-icon">▣</span>
    <h1>Browse</h1>
    <span class="heading-copy">Browse assets and components</span>
  `;
}

function restoreBrowseListLayout() {
  const browseView = document.getElementById("browse-view");
  renderBrowseHeading();
  const tools = browseView.querySelector(".table-tools");
  if (tools) tools.style.display = "";
  const frame = document.getElementById("repository-table").parentElement.parentElement;
  frame.style.display = "";
  const drill = document.getElementById("browse-drill");
  if (drill) drill.remove();
}

function showRepositoryList(syncHash = true) {
  if (syncHash) pushBrowseRoute(browseListHash());
  switchView("browse");
  state.mode = "repos";
  state.repo = null;
  state.path = "";
  expanded.clear();
  restoreBrowseListLayout();
  renderRepoList();
}

function showRepositoryTree(repoName, syncHash = true, path = "") {
  if (!repoName) {
    showRepositoryList(syncHash);
    return;
  }
  if (syncHash) pushBrowseRoute(repositoryBrowseHash(repoName, path));
  switchView("browse");
  state.mode = "tree";
  state.repo = repoName;
  state.path = path || "";
  expanded.clear();
  treeCache.clear();
  renderTree();
}

function showWelcome(syncHash = true) {
  if (syncHash) pushBrowseRoute(viewHash("welcome"));
  switchView("welcome");
}

function showUpload(syncHash = true) {
  if (!canUseUpload()) {
    showRepositoryList(true);
    return;
  }
  if (syncHash) pushBrowseRoute(viewHash("upload"));
  switchView("upload");
  renderUpload();
}

function showMyToken(syncHash = true) {
  if (!currentSession || !currentSession.userId) {
    showWelcome(true);
    return;
  }
  if (syncHash) pushBrowseRoute(viewHash("my-token"));
  switchView("my-token");
  renderMyToken();
  if (!currentApiKeysLoaded) {
    loadCurrentApiKeys();
  }
}

function currentOwnerLabel() {
  if (!currentSession || !currentSession.userId) return "";
  const source = currentSession.source ? `${displaySource(currentSession.source)}/` : "";
  return `${source}${currentSession.userId}`;
}

function setMyTokenStatus(message, type = "") {
  const status = document.getElementById("my-token-status");
  if (!status) return;
  status.textContent = message || "";
  status.classList.toggle("ok", type === "ok");
  status.classList.toggle("error", type === "error");
}

function markMyTokenDisplayNameValidity(invalid) {
  const input = document.getElementById("my-token-display-name");
  if (!input) return;
  input.classList.toggle("is-invalid", invalid);
  input.setAttribute("aria-invalid", String(invalid));
}

function setMyTokenBusy(busy) {
  document.getElementById("my-token-create").disabled = busy;
  document.querySelectorAll("[data-token-reset], [data-token-delete]").forEach((button) => {
    button.disabled = busy;
  });
}

function clearMyTokenSecret() {
  latestGeneratedApiToken = "";
  document.getElementById("my-token-secret").hidden = true;
  document.getElementById("my-token-value").value = "";
}

function revealGeneratedToken(token) {
  latestGeneratedApiToken = token || "";
  const panel = document.getElementById("my-token-secret");
  document.getElementById("my-token-value").value = latestGeneratedApiToken;
  panel.hidden = !latestGeneratedApiToken;
}

function expireCurrentSession() {
  currentSession = null;
  currentPermissions = [];
  currentApiKeysCache = [];
  currentApiKeysLoaded = false;
  clearMyTokenSecret();
  writeAuthSnapshot();
  updateTopbarAuth();
  showWelcome();
}

async function fetchCurrentApiKeys() {
  const res = await fetch("/internal/security/api-keys/current", {
    headers: { Accept: "application/json" },
    cache: "no-store",
  });
  if (res.status === 401 || res.status === 403) {
    expireCurrentSession();
    throw new Error("Session expired.");
  }
  if (!res.ok) throw new Error(await responseMessage(res, `Failed to load API keys: ${res.status}`));
  return res.json();
}

async function loadCurrentApiKeys() {
  if (!currentSession || !currentSession.userId) return;
  setMyTokenStatus("Loading...");
  try {
    currentApiKeysCache = await fetchCurrentApiKeys();
    currentApiKeysLoaded = true;
    renderMyToken();
    setMyTokenStatus("");
  } catch (error) {
    if (currentSession) {
      setMyTokenStatus(error.message || "Failed to load API keys.", "error");
    }
  }
}

function renderMyToken() {
  const table = document.getElementById("my-token-table");
  if (!table) return;
  if (!currentSession || !currentSession.userId) {
    table.innerHTML = '<tr><td colspan="8" class="muted-row">Sign in required.</td></tr>';
    return;
  }
  if (!currentApiKeysLoaded) {
    table.innerHTML = '<tr><td colspan="8" class="muted-row">Loading...</td></tr>';
    return;
  }
  if (!currentApiKeysCache.length) {
    table.innerHTML = '<tr><td colspan="8" class="muted-row">No API keys.</td></tr>';
    return;
  }
  table.innerHTML = currentApiKeysCache.map((apiKey) => {
    const owner = `${apiKey.ownerSource || ""}/${apiKey.ownerUserId || ""}`.replace(/^\//, "");
    const scopes = Array.isArray(apiKey.scopes) && apiKey.scopes.length ? apiKey.scopes.join(", ") : "-";
    const id = Number(apiKey.id);
    return `
      <tr>
        <td>${escapeHtml(apiKey.domain || "")}</td>
        <td>${escapeHtml(apiKey.displayName || "-")}</td>
        <td>${escapeHtml(owner || currentOwnerLabel())}</td>
        <td>${escapeHtml(apiKey.status || "")}</td>
        <td><code>${escapeHtml(apiKey.tokenPrefix || "-")}</code></td>
        <td>${escapeHtml(scopes)}</td>
        <td>${escapeHtml(formatInstant(apiKey.updatedAt || apiKey.createdAt) || "-")}</td>
        <td>
          <div class="token-actions">
            <button class="secondary-button" type="button" data-token-reset="${id}">Reset</button>
            <button class="secondary-button token-danger-button" type="button" data-token-delete="${id}">Delete</button>
          </div>
        </td>
      </tr>
    `;
  }).join("");
  table.querySelectorAll("[data-token-reset]").forEach((button) => {
    button.addEventListener("click", () => resetCurrentApiKey(button.dataset.tokenReset));
  });
  table.querySelectorAll("[data-token-delete]").forEach((button) => {
    button.addEventListener("click", () => deleteCurrentApiKey(button.dataset.tokenDelete));
  });
}

async function createCurrentApiKey(event) {
  event.preventDefault();
  if (!currentSession || !currentSession.userId) {
    showWelcome();
    return;
  }
  const domain = document.getElementById("my-token-domain").value || "NpmToken";
  const displayNameInput = document.getElementById("my-token-display-name");
  const displayName = displayNameInput.value.trim();
  if (!displayName) {
    markMyTokenDisplayNameValidity(true);
    setMyTokenStatus("Display name is required.", "error");
    displayNameInput.focus();
    return;
  }
  markMyTokenDisplayNameValidity(false);
  setMyTokenBusy(true);
  setMyTokenStatus("Generating...");
  try {
    const res = await fetch("/internal/security/api-keys/current", {
      method: "POST",
      headers: { "Content-Type": "application/json", Accept: "application/json" },
      cache: "no-store",
      body: JSON.stringify({ domain, displayName, scopes: [] }),
    });
    if (res.status === 401 || res.status === 403) {
      expireCurrentSession();
      return;
    }
    if (!res.ok) throw new Error(await responseMessage(res, `Failed to generate API key: ${res.status}`));
    const payload = await res.json();
    revealGeneratedToken(payload.token || "");
    currentApiKeysLoaded = false;
    await loadCurrentApiKeys();
    setMyTokenStatus("Generated.", "ok");
  } catch (error) {
    setMyTokenStatus(error.message || "Failed to generate API key.", "error");
  } finally {
    setMyTokenBusy(false);
  }
}

async function resetCurrentApiKey(id) {
  if (!id) return;
  setMyTokenBusy(true);
  setMyTokenStatus("Resetting...");
  try {
    const res = await fetch(`/internal/security/api-keys/current/${encodeURIComponent(id)}/reset`, {
      method: "POST",
      headers: { Accept: "application/json" },
      cache: "no-store",
    });
    if (res.status === 401 || res.status === 403) {
      expireCurrentSession();
      return;
    }
    if (!res.ok) throw new Error(await responseMessage(res, `Failed to reset API key: ${res.status}`));
    const payload = await res.json();
    revealGeneratedToken(payload.token || "");
    currentApiKeysLoaded = false;
    await loadCurrentApiKeys();
    setMyTokenStatus("Reset.", "ok");
  } catch (error) {
    setMyTokenStatus(error.message || "Failed to reset API key.", "error");
  } finally {
    setMyTokenBusy(false);
  }
}

async function deleteCurrentApiKey(id) {
  if (!id || !window.confirm("Delete this API key?")) return;
  setMyTokenBusy(true);
  setMyTokenStatus("Deleting...");
  try {
    const res = await fetch(`/internal/security/api-keys/current/${encodeURIComponent(id)}`, {
      method: "DELETE",
      headers: { Accept: "application/json" },
      cache: "no-store",
    });
    if (res.status === 401 || res.status === 403) {
      expireCurrentSession();
      return;
    }
    if (!res.ok) throw new Error(await responseMessage(res, `Failed to delete API key: ${res.status}`));
    clearMyTokenSecret();
    currentApiKeysLoaded = false;
    await loadCurrentApiKeys();
    setMyTokenStatus("Deleted.", "ok");
  } catch (error) {
    setMyTokenStatus(error.message || "Failed to delete API key.", "error");
  } finally {
    setMyTokenBusy(false);
  }
}

async function copyGeneratedToken() {
  if (!latestGeneratedApiToken) return;
  const button = document.getElementById("my-token-copy");
  await copyTextToClipboard(latestGeneratedApiToken);
  button.textContent = "Copied";
  setTimeout(() => { button.textContent = "Copy"; }, 1200);
}

function activateSearch(format = DEFAULT_SEARCH_FORMAT, syncHash = true) {
  const normalized = normalizeSearchFormat(format);
  if (syncHash) pushBrowseRoute(searchHash(normalized));
  setSearchSubnavOpen(true);
  switchView("search");
  selectSearchFormat(normalized);
  return normalized;
}

function showSearch(format = DEFAULT_SEARCH_FORMAT, syncHash = true) {
  renderSearch(activateSearch(format, syncHash));
}

function openSearchResult(component) {
  if (!component || !component.repository) return;
  showRepositoryTree(component.repository, true, componentBrowsePath(component));
}

function renderRepoList() {
  const filter = document.getElementById("repository-filter").value;
  const rows = sortRepositoryRows(repositoryRows(repositoriesCache, filter, false));
  restoreBrowseListLayout();
  updateRepositorySortHeaders();
  document.getElementById("repository-table").innerHTML = rows.map((repo) => {
    const clientUrl = repositoryClientUrl(repo);
    return `
    <tr data-repo="${repo.name}" class="repo-row">
      <td class="icon-column">${repoIcon(repo.type)}</td>
      <td>${repo.name}</td>
      <td>${repo.type}</td>
      <td>${repo.format}</td>
      <td>${repo.status}</td>
      <td class="repo-url-cell">
        <a class="repo-url-link" href="${escapeHtml(clientUrl)}" target="_blank" rel="noopener">${escapeHtml(clientUrl)}</a>
      </td>
      <td class="repo-copy-column">
        <button type="button" class="repo-copy-button" data-repository-url="${escapeHtml(clientUrl)}" title="Copy ${escapeHtml(clientUrl)}">Copy</button>
      </td>
    </tr>
  `;
  }).join("");
  document.querySelectorAll("#repository-table .repo-copy-button").forEach((button) => {
    button.addEventListener("click", async (event) => {
      event.stopPropagation();
      await copyRepositoryUrl(button);
    });
  });
  document.querySelectorAll("#repository-table tr.repo-row").forEach((tr) => {
    tr.addEventListener("click", (event) => {
      if (event.target.closest("button, a")) return;
      showRepositoryTree(tr.dataset.repo);
    });
  });
}

async function copyTextToClipboard(text) {
  if (navigator.clipboard && navigator.clipboard.writeText) {
    try {
      await navigator.clipboard.writeText(text);
      return;
    } catch {
      // Fall through to the legacy path when browser permissions block Clipboard API.
    }
  }
  const textarea = document.createElement("textarea");
  textarea.value = text;
  textarea.setAttribute("readonly", "");
  textarea.style.position = "fixed";
  textarea.style.opacity = "0";
  document.body.appendChild(textarea);
  textarea.select();
  document.execCommand("copy");
  textarea.remove();
}

async function copyRepositoryUrl(button) {
  const url = button.dataset.repositoryUrl;
  if (!url) return;
  try {
    await copyTextToClipboard(url);
    button.classList.add("copied");
    button.textContent = "Copied";
    setTimeout(() => {
      button.classList.remove("copied");
      button.textContent = "Copy";
    }, 1200);
  } catch {
    button.textContent = "Failed";
    setTimeout(() => { button.textContent = "Copy"; }, 1400);
  }
}

// --- tree view ---------------------------------------------------------------

function repoTopIcon() {
  // small dark cylinder/database icon used in Nexus's breadcrumb
  return `<svg class="crumb-icon" viewBox="0 0 16 16" xmlns="http://www.w3.org/2000/svg" aria-hidden="true">
    <ellipse cx="8" cy="3.5" rx="6" ry="2" fill="#3a3a3a"/>
    <path d="M2 3.5 L2 12.5 Q2 14.5 8 14.5 Q14 14.5 14 12.5 L14 3.5 Q14 5.5 8 5.5 Q2 5.5 2 3.5 Z" fill="#3a3a3a"/>
    <path d="M2 7 Q2 9 8 9 Q14 9 14 7" fill="none" stroke="#6e6e6e" stroke-width="0.6"/>
    <path d="M2 10 Q2 12 8 12 Q14 12 14 10" fill="none" stroke="#6e6e6e" stroke-width="0.6"/>
  </svg>`;
}

function repoBreadcrumbIcon() {
  return `<svg class="crumb-icon" viewBox="0 0 16 14" xmlns="http://www.w3.org/2000/svg" aria-hidden="true">
    <path d="M1 3 L7 3 L8 4 L15 4 L15 13 L1 13 Z" fill="#f4c34a" stroke="#c79320" stroke-width="0.5"/>
  </svg>`;
}

function renderTree() {
  const browseView = document.getElementById("browse-view");
  renderBrowseHeading();
  // Hide the repository list controls; the tree renders its own breadcrumb/action row.
  const origFrame = document.getElementById("repository-table").parentElement.parentElement;
  origFrame.style.display = "none";
  const tools = browseView.querySelector(".table-tools");
  if (tools) tools.style.display = "none";

  let drill = document.getElementById("browse-drill");
  if (!drill) {
    drill = document.createElement("div");
    drill.id = "browse-drill";
    browseView.appendChild(drill);
  }
  drill.innerHTML = `
    <div class="tree-header">
      <div class="tree-crumbs">
        ${repoTopIcon()}
        <button type="button" class="crumb crumb-browse" data-crumb-root="1">Browse</button>
        <span class="crumb-sep">/</span>
        ${repoBreadcrumbIcon()}
        <span class="crumb-current">${state.repo}</span>
      </div>
      <div class="tree-actions">
        <a class="tree-link" href="/repository/${encodeURIComponent(state.repo)}/" target="_blank">HTML View</a>
        <a class="tree-link" href="#" id="advanced-search-link">Advanced search…</a>
      </div>
    </div>
    <div class="browse-split">
      <div class="tree-left">
        <div class="tree-root" id="tree-root">Loading…</div>
      </div>
      <aside class="detail-right" id="detail-right">
        <div class="detail-placeholder">Select a component or file in the tree to view details.</div>
      </aside>
    </div>
  `;
  drill.querySelector(".crumb[data-crumb-root]").addEventListener("click", () => showRepositoryList());
  drill.querySelector("#advanced-search-link").addEventListener("click", (e) => {
    e.preventDefault();
    showSearch(currentRepository()?.format || DEFAULT_SEARCH_FORMAT);
  });
  ensureTreeLevelLoaded("", document.getElementById("tree-root"), 0).then(() => selectInitialTreePath());
}

async function loadAndRenderTreeLevel(path, mountEl, depth) {
  let entries;
  try {
    entries = await fetchChildren(state.repo, path);
  } catch (e) {
    mountEl.innerHTML = `<div class="tree-error">Failed to load: ${e.message}</div>`;
    return;
  }
  if (!entries.length) {
    mountEl.innerHTML = depth === 0
      ? '<div class="tree-empty">(empty)</div>'
      : '<div class="tree-empty-child">(empty)</div>';
    return;
  }
  const ul = document.createElement("ul");
  ul.className = depth === 0 ? "tree tree-root-ul" : "tree";
  for (const entry of entries) {
    ul.appendChild(buildTreeNode(entry));
  }
  mountEl.replaceChildren(ul);
}

async function ensureTreeLevelLoaded(path, mountEl, depth) {
  if (!mountEl) return false;
  if (mountEl.dataset.loaded) {
    const pending = treeMountLoads.get(mountEl);
    if (pending) await pending;
    return true;
  }
  mountEl.dataset.loaded = "1";
  mountEl.innerHTML = '<div class="tree-loading">Loading…</div>';
  const pending = loadAndRenderTreeLevel(path, mountEl, depth).finally(() => {
    treeMountLoads.delete(mountEl);
  });
  treeMountLoads.set(mountEl, pending);
  await pending;
  return true;
}

function buildTreeNode(entry) {
  const li = document.createElement("li");
  li.className = entry.leaf ? "tree-node leaf" : "tree-node branch";
  li.dataset.path = entry.path;

  const row = document.createElement("div");
  row.className = "tree-row";
  const key = `${state.repo}::${entry.path}`;
  const isExpanded = expanded.has(key);

  const toggle = document.createElement("button");
  toggle.type = "button";
  toggle.className = "tree-toggle";
  toggle.textContent = entry.leaf ? "" : (isExpanded ? "−" : "+");
  toggle.disabled = entry.leaf;
  row.appendChild(toggle);

  const icon = document.createElement("span");
  icon.className = "tree-icon-wrap";
  icon.innerHTML = entry.leaf ? iconForFile(entry.name) : iconForDir(entry.name);
  row.appendChild(icon);

  const label = document.createElement("span");
  label.className = "tree-label";
  label.textContent = entry.name;
  row.appendChild(label);

  if (entry.leaf) {
    label.classList.add("clickable");
    label.addEventListener("click", async () => {
      selectRow(row);
      syncTreePath(entry);
      await showAssetDetail(entry);
    });
  }

  li.appendChild(row);

  if (!entry.leaf) {
    const childMount = document.createElement("div");
    childMount.className = "tree-children";
    childMount.style.display = isExpanded ? "" : "none";
    li.appendChild(childMount);
    const isVersionDir = VERSION_DIR_RE.test(entry.name);
    const ensureLoaded = async () => {
      await ensureTreeLevelLoaded(entry.path, childMount, 1);
    };
    const setExpanded = async (open) => {
      if (open) {
        expanded.add(key);
        toggle.textContent = "−";
        childMount.style.display = "";
        await ensureLoaded();
      } else {
        expanded.delete(key);
        toggle.textContent = "+";
        childMount.style.display = "none";
      }
    };
    const toggleExpand = () => setExpanded(!expanded.has(key));
    toggle.addEventListener("click", toggleExpand);
    label.classList.add("clickable");
    label.addEventListener("click", async () => {
      selectRow(row);
      syncTreePath(entry);
      if (isVersionDir || hasDirectoryUsage(entry)) {
        await showComponentDetail(entry);
        await setExpanded(true);
      } else {
        await toggleExpand();
      }
    });
    if (isExpanded) {
      ensureLoaded();
    }
  }

  return li;
}

function selectRow(row) {
  document.querySelectorAll(".tree-row.is-selected").forEach((el) => el.classList.remove("is-selected"));
  row.classList.add("is-selected");
}

function syncTreePath(entry) {
  if (!state.repo || !entry || !entry.path) return;
  state.path = entry.path;
  const target = `/browse/${repositoryBrowseHash(state.repo, entry.path)}`;
  const current = `${window.location.pathname}${window.location.search}${window.location.hash}`;
  if (current !== target) {
    window.history.replaceState(null, "", target);
  }
}

function parentTreePaths(path) {
  const parts = pathSegments(path);
  return parts.slice(0, -1).map((_, index) => parts.slice(0, index + 1).join("/"));
}

function pathSelectorValue(path) {
  if (window.CSS && typeof window.CSS.escape === "function") return window.CSS.escape(path);
  return String(path).replaceAll("\\", "\\\\").replaceAll('"', '\\"');
}

async function selectInitialTreePath() {
  if (!state.path) return;
  await revealTreePath(state.path);
  const row = document.querySelector(`.tree-node[data-path="${pathSelectorValue(state.path)}"] > .tree-row`);
  if (!row) return;
  selectRow(row);
  row.scrollIntoView({ block: "center" });
  const entry = findCachedTreeEntry(state.path);
  if (!entry) return;
  if (entry.leaf) await showAssetDetail(entry);
  else if (VERSION_DIR_RE.test(entry.name) || hasDirectoryUsage(entry)) await showComponentDetail(entry);
}

async function revealTreePath(path) {
  for (const parentPath of parentTreePaths(path)) {
    const key = `${state.repo}::${parentPath}`;
    expanded.add(key);
    const node = document.querySelector(`.tree-node[data-path="${pathSelectorValue(parentPath)}"]`);
    if (!node) return;
    const toggle = node.querySelector(":scope > .tree-row > .tree-toggle");
    const childMount = node.querySelector(":scope > .tree-children");
    if (!childMount) return;
    if (toggle) toggle.textContent = "−";
    childMount.style.display = "";
    await ensureTreeLevelLoaded(parentPath, childMount, 1);
  }
}

function findCachedTreeEntry(path) {
  for (const entries of treeCache.values()) {
    const match = entries.find((entry) => entry.path === path);
    if (match) return match;
  }
  return null;
}

function escapeHtml(value) {
  return (value ?? "").toString()
    .replaceAll("&", "&amp;")
    .replaceAll("<", "&lt;")
    .replaceAll(">", "&gt;")
    .replaceAll('"', "&quot;");
}

function formatBytes(size) {
  if (size == null || isNaN(size)) return "";
  if (size < 1024) return `${size} B`;
  if (size < 1024 * 1024) return `${(size / 1024).toFixed(1)} KiB`;
  if (size < 1024 * 1024 * 1024) return `${(size / (1024 * 1024)).toFixed(1)} MiB`;
  return `${(size / (1024 * 1024 * 1024)).toFixed(2)} GiB`;
}

function formatBytesDetail(size) {
  if (size == null || isNaN(size)) return "-";
  return `${formatBytes(Number(size))} (${Number(size)} bytes)`;
}

function formatInstant(text) {
  if (!text) return "";
  try { return new Date(text).toLocaleString(); } catch { return text; }
}

function detailPaneMount() {
  return document.getElementById("detail-right");
}

function detailCrumb(iconSvg, text) {
  return `<div class="detail-crumb">${iconSvg}<span>${escapeHtml(text)}</span></div>`;
}

function detailHead(iconSvg, text, deleteEntry) {
  const deleteButton = canDeleteBrowseContent() && deleteEntry
    ? '<button type="button" class="detail-delete-button" id="browse-delete-button">Delete</button>'
    : "";
  return `<div class="detail-head-row">${detailCrumb(iconSvg, text)}${deleteButton}</div>`;
}

function kv(label, value, opts = {}) {
  const cls = opts.locked ? "kv-val kv-locked" : "kv-val";
  const inner = opts.raw ? value : escapeHtml(value);
  return `<div class="kv"><span class="kv-key">${escapeHtml(label)}</span><span class="${cls}">${inner}</span></div>`;
}

function maybeKv(label, value, opts = {}) {
  if (value === null || value === undefined || value === "") return "";
  return kv(label, value, opts);
}

function codeValue(value) {
  return value ? `<code>${escapeHtml(value)}</code>` : "-";
}

function shortDigest(value) {
  const text = value || "";
  if (text.startsWith("sha256:") && text.length > 26) {
    return `${text.slice(0, 19)}...${text.slice(-8)}`;
  }
  return text;
}

function parseGav(path) {
  const segments = (path || "").split("/").filter(Boolean);
  if (segments.length < 2) return null;
  const version = segments[segments.length - 1];
  const artifactId = segments[segments.length - 2];
  const groupId = segments.slice(0, segments.length - 2).join(".");
  return { groupId, artifactId, version };
}

function colorizeSnippet(text) {
  return escapeHtml(text)
    .replace(/(&lt;\/?)([a-zA-Z][\w.-]*)/g, '$1<span class="x-tag">$2</span>');
}

function usageSnippet(displayName, snippetText, description = "") {
  return { displayName, snippetText, description };
}

function renderSummaryRows(rows) {
  return rows.map((row) => kv(row[0], row[1], row[2] || {})).join("");
}

function formatAttributeValue(value) {
  if (value == null) return "";
  if (Array.isArray(value)) return value.join(", ");
  if (typeof value === "object") return JSON.stringify(value);
  return String(value);
}

function renderAttributeGroup(title, values) {
  const entries = Object.entries(values || {}).filter(([, value]) => value !== null && value !== undefined);
  if (!entries.length) return "";
  return `
    <div class="attr-group">
      <div class="attr-group-title">${escapeHtml(title)}</div>
      ${entries.map(([key, value]) => kv(key, formatAttributeValue(value))).join("")}
    </div>`;
}

function renderAttributesSection(detail, opts = {}) {
  if (!detail) return "";
  const body = [
    renderAttributeGroup("Checksum", detail.checksum),
    renderAttributeGroup("Content", detail.content),
    opts.hideDocker ? "" : renderAttributeGroup("Docker", detail.docker),
    renderAttributeGroup("Npm", detail.npm),
    renderAttributeGroup("Pub", detail.pub),
    renderAttributeGroup("Provenance", detail.provenance),
  ].filter(Boolean).join("");
  if (!body) return "";
  return `
      <section class="panel">
        <header class="panel-head">Attributes</header>
        <div class="panel-body attr-panel">
          ${body}
        </div>
      </section>`;
}

function renderUsageSection(snippets) {
  if (!snippets || !snippets.length) return "";
  const first = snippets[0];
  return `
      <section class="panel">
        <header class="panel-head">Usage</header>
        <div class="panel-body">
          <div class="usage-row">
            <select class="usage-pick" id="usage-pick">
              ${snippets.map((snippet, index) =>
                `<option value="${index}">${escapeHtml(snippet.displayName)}</option>`).join("")}
            </select>
            <button type="button" class="usage-copy" id="usage-copy" title="Copy">📋</button>
          </div>
          <p class="usage-hint" id="usage-hint">${escapeHtml(first.description || "")}</p>
          <pre class="snippet"><code id="usage-snippet">${colorizeSnippet(first.snippetText)}</code></pre>
        </div>
      </section>`;
}

function bindUsageControls(mount, snippets) {
  if (!snippets || !snippets.length) return;
  const pick = mount.querySelector("#usage-pick");
  const code = mount.querySelector("#usage-snippet");
  const hint = mount.querySelector("#usage-hint");
  const copy = mount.querySelector("#usage-copy");
  if (!pick || !code || !hint || !copy) return;
  const selected = () => snippets[Number(pick.value)] || snippets[0];
  pick.addEventListener("change", () => {
    const snippet = selected();
    code.innerHTML = colorizeSnippet(snippet.snippetText);
    hint.textContent = snippet.description || "";
  });
  copy.addEventListener("click", async () => {
    try {
      await navigator.clipboard.writeText(selected().snippetText);
      copy.classList.add("copied");
      copy.textContent = "✓";
      setTimeout(() => { copy.classList.remove("copied"); copy.textContent = "📋"; }, 1200);
    } catch {
      /* clipboard blocked */
    }
  });
}

function bindDeleteControl(mount, entry) {
  const button = mount.querySelector("#browse-delete-button");
  if (!button || !entry) return;
  button.addEventListener("click", () => deleteBrowseEntry(entry, button));
}

async function deleteBrowseEntry(entry, button) {
  if (!entry || !entry.path || !state.repo) return;
  const label = entry.leaf ? entry.name : entry.path;
  if (!window.confirm(`Delete ${label}?`)) return;
  button.disabled = true;
  const originalText = button.textContent;
  button.textContent = "Deleting";
  const params = new URLSearchParams({ path: entry.path });
  if (entry.sourceRepository) params.set("source", entry.sourceRepository);
  try {
    const res = await fetch(`/internal/browse/${encodeURIComponent(state.repo)}?${params.toString()}`, {
      method: "DELETE",
      headers: { Accept: "application/json" },
      cache: "no-store",
    });
    if (res.status === 401) {
      sessionStorage.removeItem(AUTH_SNAPSHOT_KEY);
      window.location.href = "/browse/#browse/welcome";
      return;
    }
    if (!res.ok) {
      throw new Error(await deleteError(res));
    }
    treeCache.clear();
    const deletedKey = `${state.repo}::${entry.path}`;
    for (const key of Array.from(expanded)) {
      if (key === deletedKey || key.startsWith(`${deletedKey}/`)) {
        expanded.delete(key);
      }
    }
    renderTree();
  } catch (error) {
    button.disabled = false;
    button.textContent = originalText;
    window.alert(`Delete failed: ${error.message}`);
  }
}

async function deleteError(response) {
  const text = await response.text();
  if (!text) return `HTTP ${response.status}`;
  try {
    const json = JSON.parse(text);
    return json.message || json.error || text;
  } catch (_) {
    return text;
  }
}

function renderDetailPane({ crumbIcon, crumbText, summaryRows, snippets, deleteEntry }) {
  const mount = detailPaneMount();
  if (!mount) return;
  mount.innerHTML = `
    <div class="detail-pane">
      ${detailHead(crumbIcon, crumbText, deleteEntry)}
      <button type="button" class="analyze-btn">⚙ Analyze application</button>
      <section class="panel">
        <header class="panel-head">Summary</header>
        <div class="panel-body">
          ${renderSummaryRows(summaryRows)}
        </div>
      </section>
      ${renderUsageSection(snippets)}
    </div>`;
  bindUsageControls(mount, snippets);
  bindDeleteControl(mount, deleteEntry);
}

function pathSegments(path) {
  return (path || "").split("/").filter(Boolean);
}

function latestVersion(versions) {
  return versions
    .filter(Boolean)
    .sort((a, b) => a.localeCompare(b, undefined, { numeric: true, sensitivity: "base" }))
    .at(-1) || "";
}

function hasDirectoryUsage(entry) {
  const repo = currentRepository();
  if (!repo || entry.leaf) return false;
  const parts = pathSegments(entry.path);
  if (repo.format === "pypi") {
    return parts.length >= 1 && parts[0] !== "simple";
  }
  if (repo.format === "npm") {
    return Boolean(npmPackageName(entry.path));
  }
  if (repo.format === "cargo") {
    return Boolean(cargoCoordinates(entry).name);
  }
  if (repo.format === "go") {
    return goModulePath(entry.path) === entry.path;
  }
  if (repo.format === "pub") {
    return Boolean(pubCoordinates(entry).name);
  }
  return false;
}

function mavenUsageDetail(entry) {
  const gav = parseGav(entry.path);
  if (!gav) return null;
  const { groupId, artifactId, version } = gav;
  const mavenSnippet =
`<dependency>
    <groupId>${groupId}</groupId>
    <artifactId>${artifactId}</artifactId>
    <version>${version}</version>
</dependency>`;
  const gradleSnippet = `implementation '${groupId}:${artifactId}:${version}'`;
  const gradleKtsSnippet = `implementation("${groupId}:${artifactId}:${version}")`;
  const sbtSnippet = `libraryDependencies += "${groupId}" % "${artifactId}" % "${version}"`;
  const ivySnippet = `<dependency org="${groupId}" name="${artifactId}" rev="${version}" />`;
  return {
    crumbText: entry.path,
    summaryRows: [
      ["Repository", state.repo],
      ["Format", "maven2"],
      ["Group", groupId],
      ["Name", artifactId],
      ["Version", version],
      ["Most popular version", "", { locked: true }],
      ["Age", "", { locked: true }],
      ["Popularity", "", { locked: true }],
    ],
    snippets: [
      usageSnippet("Apache Maven", mavenSnippet, "Insert this snippet into your pom.xml"),
      usageSnippet("Gradle Groovy DSL", gradleSnippet, "Add to dependencies { } in build.gradle"),
      usageSnippet("Gradle Kotlin DSL", gradleKtsSnippet, "Add to dependencies { } in build.gradle.kts"),
      usageSnippet("Apache Ivy", ivySnippet, "Insert into ivy.xml <dependencies>"),
      usageSnippet("sbt", sbtSnippet, "Add to build.sbt"),
    ],
  };
}

async function pypiUsageDetail(entry) {
  const parts = pathSegments(entry.path);
  if (!parts.length || parts[0] === "simple") return null;
  const name = parts[0];
  const children = !entry.leaf && parts.length === 1 ? await fetchChildren(state.repo, entry.path) : [];
  const version = parts[1] || latestVersion(children.filter((child) => !child.leaf).map((child) => child.name));
  const pin = version ? `${name}==${version}` : name;
  return {
    crumbText: entry.path,
    summaryRows: [
      ["Repository", state.repo],
      ["Format", "pypi"],
      ["Name", name],
      ["Version", version || "latest"],
      ["Most popular version", "", { locked: true }],
      ["Age", "", { locked: true }],
      ["Popularity", "", { locked: true }],
    ],
    snippets: [
      usageSnippet("pip", `pip install ${pin}`),
      usageSnippet("easy_install", `easy_install ${pin}`),
      usageSnippet("pipenv", `pipenv install ${pin}`),
      usageSnippet("requirements.txt", version ? `${name} == ${version}` : name),
    ],
  };
}

function npmPackageName(path) {
  const clean = (path || "").split("/-/")[0];
  const parts = pathSegments(clean);
  if (!parts.length) return "";
  if (parts[0].startsWith("@")) {
    return parts.length >= 2 ? `${parts[0]}/${parts[1]}` : "";
  }
  return parts[0];
}

function npmVersionFromTarball(path, packageName) {
  const filename = pathSegments(path).at(-1) || "";
  if (!filename.endsWith(".tgz")) return "";
  const base = filename.slice(0, -4);
  const packageBase = pathSegments(packageName).at(-1) || packageName;
  const prefix = `${packageBase}-`;
  return base.startsWith(prefix) ? base.slice(prefix.length) : "";
}

async function npmUsageDetail(entry) {
  const name = npmPackageName(entry.path);
  if (!name) return null;
  let version = npmVersionFromTarball(entry.path, name);
  if (!version && !entry.leaf) {
    const children = await fetchChildren(state.repo, name);
    version = latestVersion(children
      .filter((child) => child.leaf)
      .map((child) => npmVersionFromTarball(child.path, name)));
  }
  const installTarget = version ? `${name}@${version}` : name;
  return {
    crumbText: entry.path,
    summaryRows: [
      ["Repository", state.repo],
      ["Format", "npm"],
      ["Name", name],
      ["Version", version || "latest"],
      ["Most popular version", "", { locked: true }],
      ["Age", "", { locked: true }],
      ["Popularity", "", { locked: true }],
    ],
    snippets: [
      usageSnippet("npm", `npm install ${installTarget}`, "Install runtime dependency"),
      usageSnippet("Yarn", `yarn add ${installTarget}`, "Install runtime dependency"),
      usageSnippet("package.json", `"${name}": "${version || "latest"}"`,
        "Install runtime dependency to the package.json dependencies section"),
    ],
  };
}

function cargoCoordinates(entry) {
  const parts = pathSegments(entry.path);
  if (!parts.length || parts[0] === "config.json") {
    return { name: "", version: "" };
  }
  if (parts[0] === "crates" && parts.length >= 2) {
    const name = parts[1] || "";
    let version = parts[2] || "";
    const file = parts.at(-1) || "";
    if (!version && file.endsWith(".crate")) {
      version = cargoVersionFromCrateFile(file, name);
    }
    return { name, version };
  }
  if (parts[0] === "api" && parts[1] === "v1" && parts[2] === "crates") {
    return { name: parts[3] || "", version: parts[4] || "" };
  }
  const leaf = parts.at(-1) || "";
  return parts.join("/") === cargoIndexPathForCrate(leaf)
    ? { name: leaf, version: "" }
    : { name: "", version: "" };
}

function cargoVersionFromCrateFile(filename, crateName) {
  if (!filename || !filename.endsWith(".crate")) return "";
  const base = filename.slice(0, -".crate".length);
  const prefix = `${crateName}-`;
  return base.startsWith(prefix) ? base.slice(prefix.length) : "";
}

function cargoIndexPathForCrate(crateName) {
  const name = String(crateName || "").toLowerCase();
  if (!/^[a-z0-9_-]+$/.test(name)) return "";
  if (name.length === 1) return `1/${name}`;
  if (name.length === 2) return `2/${name}`;
  if (name.length === 3) return `3/${name[0]}/${name}`;
  return `${name.slice(0, 2)}/${name.slice(2, 4)}/${name}`;
}

function cargoSourceName(repoName) {
  return (repoName || "kkrepo")
    .replace(/[^A-Za-z0-9_-]+/g, "-")
    .replace(/^-+|-+$/g, "") || "kkrepo";
}

function tomlString(value) {
  return `"${String(value || "").replaceAll("\\", "\\\\").replaceAll('"', '\\"')}"`;
}

function cargoSourceTable(sourceName) {
  return /^[A-Za-z0-9_-]+$/.test(sourceName) ? sourceName : tomlString(sourceName);
}

async function latestCargoVersion(cratePath) {
  try {
    const children = await fetchChildren(state.repo, cratePath);
    return latestVersion(children
      .filter((child) => !child.leaf)
      .map((child) => child.name));
  } catch {
    return "";
  }
}

async function cargoUsageDetail(entry) {
  const coordinates = cargoCoordinates(entry);
  if (!coordinates.name) return null;
  const name = coordinates.name;
  const cratePath = `crates/${name}`;
  const version = coordinates.version || (!entry.leaf && entry.path === cratePath
    ? await latestCargoVersion(cratePath)
    : "");
  const sourceName = cargoSourceName(state.repo);
  const sourceTable = cargoSourceTable(sourceName);
  const repoUrl = repositoryBaseUrl();
  const registryUrl = `sparse+${repoUrl}`;
  const addTarget = version ? `${name}@${version}` : name;
  const dependency = version ? `${name} = "${version}"` : `${name} = "*"`;
  const alternateDependency = version
    ? `${name} = { version = "${version}", registry = "${sourceName}" }`
    : `${name} = { version = "*", registry = "${sourceName}" }`;
  return {
    crumbText: entry.path,
    summaryRows: [
      ["Repository", state.repo],
      ["Format", "cargo"],
      ["Name", name],
      ["Version", version || "latest"],
      ["Registry URL", repoUrl],
    ],
    snippets: [
      usageSnippet(".cargo/config.toml", `[source.crates-io]
replace-with = "${sourceName}"

[source.${sourceTable}]
registry = "${registryUrl}"`, "Route crates.io dependencies through this repository"),
      usageSnippet("cargo add", `cargo add ${addTarget}`),
      usageSnippet("Cargo.toml", dependency),
      usageSnippet("Named registry config", `[registries.${sourceTable}]
index = "${registryUrl}"`, "Add to .cargo/config.toml when using this repository as an explicit registry"),
      usageSnippet("Cargo.toml registry", alternateDependency),
    ],
  };
}

function helmChartFromAsset(name) {
  if (!/\.t(?:ar\.)?gz$/i.test(name || "")) return null;
  const base = name.replace(/\.t(?:ar\.)?gz$/i, "");
  const match = base.match(/^(.+)-((?:v)?\d.*)$/);
  if (!match) return null;
  return { name: match[1], version: match[2] };
}

function releaseName(chartName) {
  return (chartName || "release").toLowerCase().replace(/[^a-z0-9-]+/g, "-").replace(/^-|-$/g, "") || "release";
}

async function helmUsageDetail(entry) {
  const chart = helmChartFromAsset(entry.name);
  if (!chart) return null;
  const repoUrl = repositoryBaseUrl();
  return {
    crumbText: entry.path,
    summaryRows: [
      ["Repository", state.repo],
      ["Format", "helm"],
      ["Name", chart.name],
      ["Version", chart.version],
      ["Repository URL", repoUrl],
    ],
    snippets: [
      usageSnippet("helm repo add", `helm repo add ${state.repo} ${repoUrl}`),
      usageSnippet("helm install", `helm install ${releaseName(chart.name)} ${chart.name} --repo ${repoUrl} --version ${chart.version}`),
      usageSnippet("Chart.yaml", `dependencies:
  - name: ${chart.name}
    version: ${chart.version}
    repository: ${repoUrl}`),
    ],
  };
}

function goModulePath(path) {
  const marker = "/@v/";
  if ((path || "").includes(marker)) return path.slice(0, path.indexOf(marker));
  if ((path || "").endsWith("/@latest")) return path.slice(0, -"/@latest".length);
  const parts = pathSegments(path);
  if (parts.length < 2 || parts.includes("@v")) return "";
  return parts.join("/");
}

function goVersionFromPath(path) {
  const filename = pathSegments(path).at(-1) || "";
  const match = filename.match(/^(v[^/]+)\.(?:info|mod|zip)$/);
  return match ? match[1] : "";
}

async function latestGoVersion(modulePath) {
  try {
    const children = await fetchChildren(state.repo, `${modulePath}/@v`);
    return latestVersion(children.map((child) => goVersionFromPath(child.path)));
  } catch {
    return "";
  }
}

async function goUsageDetail(entry) {
  const modulePath = goModulePath(entry.path);
  if (!modulePath) return null;
  const version = goVersionFromPath(entry.path) || await latestGoVersion(modulePath);
  const repoUrl = repositoryBaseUrl();
  const target = `${modulePath}@${version || "latest"}`;
  const snippets = [
    usageSnippet("go get", `GOPROXY=${repoUrl} go get ${target}`),
    usageSnippet("go env", `go env -w GOPROXY=${repoUrl},direct`),
  ];
  if (version) {
    snippets.push(usageSnippet("go.mod", `require ${modulePath} ${version}`));
  }
  return {
    crumbText: entry.path,
    summaryRows: [
      ["Repository", state.repo],
      ["Format", "go"],
      ["Name", modulePath],
      ["Version", version || "latest"],
      ["Repository URL", repoUrl],
    ],
    snippets,
  };
}

function pubVersionFromArchiveFile(filename, packageName) {
  if (!filename || !filename.endsWith(".tar.gz")) return "";
  const base = filename.slice(0, -".tar.gz".length);
  const prefix = `${packageName}-`;
  if (base.startsWith(prefix)) return base.slice(prefix.length);
  return base === packageName ? "" : base;
}

function pubCoordinates(entry) {
  const parts = pathSegments(entry.path);
  if (!parts.length) return { name: "", version: "" };
  if (parts[0] === "packages") {
    const name = parts[1] || "";
    if (parts[2] !== "versions") return { name, version: "" };
    const file = parts[3] || "";
    return { name, version: file.endsWith(".tar.gz") ? file.slice(0, -".tar.gz".length) : "" };
  }
  if (parts[0] === "api" && parts[1] === "archives") {
    const file = parts[2] || "";
    const base = file.endsWith(".tar.gz") ? file.slice(0, -".tar.gz".length) : "";
    const separator = base.indexOf("-");
    return separator > 0 ? { name: base.slice(0, separator), version: base.slice(separator + 1) } : { name: "", version: "" };
  }
  const name = parts[0] || "";
  if (!name || name === "api") return { name: "", version: "" };
  const version = parts[1] || "";
  const file = parts[2] || "";
  if (file === "version.json") return { name, version };
  if (file.endsWith(".tar.gz")) return { name, version: pubVersionFromArchiveFile(file, name) || version };
  return { name, version: parts.length >= 2 ? version : "" };
}

async function latestPubVersion(entry, packageName) {
  const parts = pathSegments(entry.path);
  const candidates = [];
  if (parts[0] === "packages" && packageName) {
    candidates.push(`packages/${packageName}/versions`);
  }
  if (parts.length === 1 && packageName) {
    candidates.push(packageName);
  }
  if (parts.length === 2 && parts[0] === packageName) {
    return parts[1] || "";
  }
  for (const path of candidates) {
    try {
      const children = await fetchChildren(state.repo, path);
      const versions = children.map((child) => {
        if (child.leaf) return pubCoordinates(child).version;
        return child.name;
      });
      const latest = latestVersion(versions);
      if (latest) return latest;
    } catch {
      // Leave version blank when browse children are unavailable.
    }
  }
  return "";
}

async function pubUsageDetail(entry) {
  const coordinates = pubCoordinates(entry);
  if (!coordinates.name) return null;
  const name = coordinates.name;
  const version = coordinates.version || (!entry.leaf ? await latestPubVersion(entry, name) : "");
  const repoUrl = repositoryBaseUrl();
  const dependencyVersion = version || "any";
  const addTarget = version ? `${name}:${version}` : name;
  return {
    crumbText: entry.path,
    summaryRows: [
      ["Repository", state.repo],
      ["Format", "pub"],
      ["Name", name],
      ["Version", version || "latest"],
      ["Repository URL", repoUrl],
    ],
    snippets: [
      usageSnippet("pubspec.yaml", `dependencies:
  ${name}:
    hosted:
      url: ${repoUrl}
      name: ${name}
    version: ${dependencyVersion}`, "Declare a dependency from this hosted Pub repository"),
      usageSnippet("dart pub add", `dart pub add ${addTarget} --hosted-url ${repoUrl}`),
      usageSnippet("PUB_HOSTED_URL", `PUB_HOSTED_URL=${repoUrl} dart pub get`),
      usageSnippet("publish_to", `publish_to: ${repoUrl}`),
    ],
  };
}

function dockerCoordinates(entry, detail = null) {
  const docker = detail?.docker || {};
  const image = docker.image_name || imageNameFromDockerPath(entry.path);
  const reference = docker.reference || dockerTagFromDockerPath(entry.path) || "latest";
  return { image, reference, digest: docker.digest || docker.raw_bytes_digest || "" };
}

function isDockerDetail(detail) {
  return currentRepository()?.format === "docker" || !!detail?.docker?.asset_kind;
}

function imageNameFromDockerPath(path) {
  const parts = pathSegments(path);
  const manifests = parts.lastIndexOf("manifests");
  if (manifests > 0) {
    const start = parts[0] === "v2" ? 1 : parts[0] === "docker" && parts[1] === "manifests" ? 2 : 0;
    return parts.slice(start, manifests).join("/");
  }
  const blobs = parts.lastIndexOf("blobs");
  if (blobs > 0) {
    const start = parts[0] === "v2" ? 1 : 0;
    return parts.slice(start, blobs).join("/");
  }
  return "";
}

function dockerTagFromDockerPath(path) {
  const parts = pathSegments(path);
  const manifests = parts.lastIndexOf("manifests");
  if (manifests >= 0 && parts[manifests + 1]) {
    const reference = parts[manifests + 1];
    return reference.includes(":") ? "" : reference;
  }
  return "";
}

function dockerUsageDetail(entry, detail = null) {
  const { image, reference, digest } = dockerCoordinates(entry, detail);
  if (!image) return null;
  const base = dockerRepositoryBaseUrl();
  const byTag = `${base}/${image}:${reference || "latest"}`;
  const snippets = [
    usageSnippet("docker pull", `docker pull ${byTag}`),
    usageSnippet("docker tag", `docker tag ${image}:${reference || "latest"} ${byTag}`),
    usageSnippet("docker push", `docker push ${byTag}`),
  ];
  if (digest) {
    snippets.push(usageSnippet("docker pull digest", `docker pull ${base}/${image}@${digest}`));
  }
  return {
    crumbText: entry.path,
    summaryRows: [
      ["Repository", state.repo],
      ["Format", "docker"],
      ["Image", image],
      ["Reference", reference || digest || "-"],
      ["Digest", digest || "-"],
      ["Registry URL", base],
    ],
    snippets,
  };
}

function dockerKindLabel(kind) {
  const normalized = (kind || "").toString().toUpperCase();
  if (normalized === "MANIFEST") return "Manifest";
  if (normalized === "BLOB") return "Blob";
  return normalized || "-";
}

function dockerActorRows(detail) {
  const uploader = detail?.uploader || "-";
  const uploaderIp = detail?.uploaderIp || "";
  const proxy = uploader === "proxy";
  return `
    ${kv(proxy ? "Cached by" : "Uploaded by", uploader)}
    ${uploaderIp ? kv(proxy ? "Remote" : "Uploader IP", uploaderIp) : ""}
  `;
}

function dockerSizeRows(docker, detail, entry) {
  const manifestSize = docker?.manifest_size_bytes ?? detail?.size ?? entry.size;
  const blobSize = docker?.blob_size_bytes ?? detail?.size ?? entry.size;
  if ((docker?.asset_kind || "").toString().toUpperCase() === "BLOB") {
    return kv("Blob size", formatBytesDetail(blobSize));
  }
  return `
    ${kv("Manifest size", formatBytesDetail(manifestSize))}
    ${docker?.layer_size_bytes != null ? kv("Image size", formatBytesDetail(docker.layer_size_bytes)) : ""}
    ${docker?.cached_image_size_bytes != null ? kv("Cached image size", formatBytesDetail(docker.cached_image_size_bytes)) : ""}
  `;
}

function renderDockerSummaryPanel(entry, detail, sourceRepository) {
  const docker = detail?.docker || {};
  const { image, reference, digest } = dockerCoordinates(entry, detail);
  return `
    <section class="panel">
      <header class="panel-head">Summary</header>
      <div class="panel-body">
        ${kv("Repository", state.repo)}
        ${sourceRepository && sourceRepository !== state.repo ? kv("Source repository", sourceRepository) : ""}
        ${kv("Path", entry.path)}
        ${maybeKv("Image", image)}
        ${maybeKv("Reference", reference || digest || "-")}
        ${kv("Digest", codeValue(digest || docker.raw_bytes_digest || "-"), { raw: true })}
        ${kv("Asset type", dockerKindLabel(docker.asset_kind))}
        ${dockerSizeRows(docker, detail, entry)}
        ${maybeKv("Cached platform", docker.cached_image_platform)}
        ${dockerActorRows(detail)}
        ${kv("Last updated", formatInstant(detail?.lastUpdatedAt || entry.lastUpdatedAt))}
      </div>
    </section>`;
}

function renderGenericAssetSummaryPanel(entry, detail, sourceRepository, uploader, uploaderIp) {
  return `
    <section class="panel">
      <header class="panel-head">Summary</header>
      <div class="panel-body">
        ${kv("Repository", state.repo)}
        ${sourceRepository && sourceRepository !== state.repo ? kv("Source repository", sourceRepository) : ""}
        ${kv("Path", entry.path)}
        ${kv("Uploader", uploader)}
        ${uploaderIp ? kv("Uploader IP", uploaderIp) : ""}
        ${kv("Size", formatBytesDetail(detail?.size ?? entry.size))}
        ${kv("Content-Type", detail?.contentType || entry.contentType || "-")}
        ${kv("Last updated", formatInstant(detail?.lastUpdatedAt || entry.lastUpdatedAt))}
        ${kv("SHA-1", codeValue(detail?.checksum?.sha1 || entry.sha1 || "-"), { raw: true })}
      </div>
    </section>`;
}

function renderDockerMetadataPanel(detail) {
  const docker = detail?.docker || {};
  if (!Object.keys(docker).length) return "";
  const platforms = Array.isArray(docker.platforms) ? docker.platforms : [];
  const configs = Array.isArray(docker.config_descriptors) ? docker.config_descriptors : [];
  const layers = Array.isArray(docker.layer_descriptors) ? docker.layer_descriptors : [];
  const manifests = Array.isArray(docker.manifest_descriptors) ? docker.manifest_descriptors : [];
  const referrers = Array.isArray(docker.referrers) ? docker.referrers : [];
  const cachedPlatforms = docker.cached_platform_count != null
    ? `${docker.cached_platform_count}${docker.platform_count ? ` / ${docker.platform_count}` : ""}`
    : "";
  return `
    <section class="panel">
      <header class="panel-head">Docker metadata</header>
      <div class="panel-body">
        ${maybeKv("Media type", docker.media_type || detail?.contentType)}
        ${maybeKv("Layers", docker.layer_count)}
        ${maybeKv("Cached layers", docker.cached_layer_count != null && docker.layer_count ? `${docker.cached_layer_count} / ${docker.layer_count}` : docker.cached_layer_count)}
        ${maybeKv("Layer size", docker.layer_size_bytes != null ? formatBytesDetail(docker.layer_size_bytes) : "")}
        ${maybeKv("Cached layer size", docker.cached_layer_size_bytes != null ? formatBytesDetail(docker.cached_layer_size_bytes) : "")}
        ${maybeKv("Referenced size", docker.referenced_size_bytes != null ? formatBytesDetail(docker.referenced_size_bytes) : "")}
        ${maybeKv("Platforms", docker.platform_count)}
        ${maybeKv("Cached platforms", cachedPlatforms)}
        ${maybeKv("Platform summary", docker.platform_summary)}
        ${maybeKv("Descriptors", docker.descriptor_count)}
        ${maybeKv("Tags", Array.isArray(docker.tags) ? docker.tags.join(", ") : "")}
        ${maybeKv("Referrers", docker.referrer_count)}
        ${renderDockerPlatforms(platforms)}
        ${renderDockerDescriptorList("Config", configs)}
        ${renderDockerDescriptorList("Layers", layers)}
        ${renderDockerDescriptorList("Child manifests", manifests)}
        ${renderDockerReferrers(referrers)}
      </div>
    </section>`;
}

function renderDockerPlatforms(platforms) {
  if (!platforms.length) return "";
  const rows = platforms.map((platform) => `
    <div class="platform-row">
      <span class="platform-name">${escapeHtml(platform.platform || "-")}</span>
      <code class="platform-digest">${escapeHtml(shortDigest(platform.digest || ""))}</code>
      <span class="platform-size">${escapeHtml(platform.cached_image_size_bytes != null
        ? formatBytes(platform.cached_image_size_bytes)
        : platform.manifest_size_bytes != null ? formatBytes(platform.manifest_size_bytes) : "-")}</span>
    </div>`).join("");
  return `<div class="platform-list">${rows}</div>`;
}

function renderDockerDescriptorList(title, descriptors) {
  if (!descriptors.length) return "";
  const rows = descriptors.map((descriptor) => `
    <div class="docker-descriptor-row">
      <code class="descriptor-digest" title="${escapeHtml(descriptor.digest || "")}">${escapeHtml(shortDigest(descriptor.digest || ""))}</code>
      <span class="descriptor-media" title="${escapeHtml(descriptor.media_type || "")}">${escapeHtml(descriptor.media_type || "-")}</span>
      <span class="descriptor-size">${escapeHtml(descriptor.size_bytes != null ? formatBytes(descriptor.size_bytes) : "-")}</span>
      ${descriptor.platform ? `<span class="descriptor-platform">${escapeHtml(descriptor.platform)}</span>` : ""}
    </div>`).join("");
  return `
    <div class="docker-descriptor-list">
      <div class="docker-descriptor-title">${escapeHtml(title)}</div>
      ${rows}
    </div>`;
}

function renderDockerReferrers(referrers) {
  if (!referrers.length) return "";
  const rows = referrers.map((referrer) => `
    <div class="docker-descriptor-row docker-referrer-row">
      <code class="descriptor-digest" title="${escapeHtml(referrer.digest || "")}">${escapeHtml(shortDigest(referrer.digest || ""))}</code>
      <span class="descriptor-media" title="${escapeHtml(referrer.artifact_type || referrer.media_type || "")}">${escapeHtml(referrer.artifact_type || referrer.media_type || "-")}</span>
      <span class="descriptor-size">${escapeHtml(referrer.size_bytes != null ? formatBytes(referrer.size_bytes) : "-")}</span>
      <span class="descriptor-platform">${escapeHtml(referrer.image_name || "")}</span>
    </div>`).join("");
  return `
    <div class="docker-descriptor-list">
      <div class="docker-descriptor-title">Referrers</div>
      ${rows}
    </div>`;
}

async function usageDetailForEntry(entry) {
  const repo = currentRepository();
  if (!repo) return null;
  if (repo.format === "maven2") return mavenUsageDetail(entry);
  if (repo.format === "pypi") return pypiUsageDetail(entry);
  if (repo.format === "npm") return npmUsageDetail(entry);
  if (repo.format === "cargo") return cargoUsageDetail(entry);
  if (repo.format === "helm") return helmUsageDetail(entry);
  if (repo.format === "go") return goUsageDetail(entry);
  if (repo.format === "pub") return pubUsageDetail(entry);
  if (repo.format === "docker") return dockerUsageDetail(entry);
  return null;
}

async function showComponentDetail(entry) {
  const mount = detailPaneMount();
  if (!mount) return;
  const detail = await usageDetailForEntry(entry);
  if (!detail) {
    mount.innerHTML = `<div class="detail-placeholder">No component coordinates for this path.</div>`;
    return;
  }
  renderDetailPane({
    crumbIcon: repoBreadcrumbIcon(),
    crumbText: detail.crumbText,
    summaryRows: detail.summaryRows,
    snippets: detail.snippets,
    deleteEntry: entry,
  });
}

async function showAssetDetail(entry) {
  const mount = detailPaneMount();
  if (!mount) return;
  const usage = await usageDetailForEntry(entry);
  const detail = await fetchAssetAttributes(entry);
  const dockerUsage = currentRepository()?.format === "docker" ? dockerUsageDetail(entry, detail) : null;
  const uploader = detail?.uploader || "-";
  const uploaderIp = detail?.uploaderIp || "";
  const sourceRepository = detail?.sourceRepository || entry.sourceRepository || "";
  const dockerDetail = isDockerDetail(detail);
  const downloadUrl = dockerDetail ? (entry.downloadUrl || detail?.downloadUrl) : (detail?.downloadUrl || entry.downloadUrl);
  mount.innerHTML = `
    <div class="detail-pane">
      ${detailHead('<span class="crumb-icon">📄</span>', entry.path, entry)}
      ${dockerDetail
        ? renderDockerSummaryPanel(entry, detail, sourceRepository)
        : renderGenericAssetSummaryPanel(entry, detail, sourceRepository, uploader, uploaderIp)}
      ${dockerDetail ? renderDockerMetadataPanel(detail) : ""}
      ${renderUsageSection((dockerUsage || usage) ? (dockerUsage || usage).snippets : [])}
      ${renderAttributesSection(detail, { hideDocker: dockerDetail })}
      <div class="download-row">
        <a class="copy-button" href="${downloadUrl}" target="_blank">Download ↓</a>
      </div>
    </div>`;
  if (dockerUsage || usage) bindUsageControls(mount, (dockerUsage || usage).snippets);
  bindDeleteControl(mount, entry);
}

async function fetchAssetAttributes(entry) {
  if (!entry || !entry.leaf || !state.repo || !entry.path) return null;
  const params = new URLSearchParams({ path: entry.path });
  if (entry.sourceRepository) params.set("source", entry.sourceRepository);
  try {
    const res = await fetch(`/internal/browse/${encodeURIComponent(state.repo)}/attributes?${params.toString()}`, {
      headers: { Accept: "application/json" },
      cache: "no-store",
    });
    if (res.status === 401) {
      sessionStorage.removeItem(AUTH_SNAPSHOT_KEY);
      window.location.href = "/browse/#browse/welcome";
      return null;
    }
    if (!res.ok) return null;
    return await res.json();
  } catch {
    return null;
  }
}

// --- upload ---------------------------------------------------------------

function renderUpload() {
  const select = document.getElementById("upload-repository");
  const submit = document.getElementById("upload-submit");
  if (!uploadRepositoriesLoaded) {
    select.disabled = true;
    submit.disabled = true;
    select.innerHTML = '<option value="">Loading repositories...</option>';
    document.getElementById("upload-fields").innerHTML =
      '<div class="muted-row upload-empty">Loading upload repositories...</div>';
    setUploadStatus("");
    return;
  }

  const uploadRepos = uploadableRepositories(repositoryRows(uploadRepositoriesCache, "", true));
  const previous = select.value;
  select.innerHTML = uploadRepos.map((repo) =>
    `<option value="${escapeHtml(repo.name)}">${escapeHtml(repo.name)} (${escapeHtml(repo.format)})</option>`).join("");
  select.disabled = false;
  submit.disabled = uploadRepos.length === 0;
  if (previous && uploadRepos.some((repo) => repo.name === previous)) {
    select.value = previous;
  } else if (uploadRepos.length) {
    select.value = uploadRepos[0].name;
  }
  renderUploadFields();
  updateUploadPath();
}

function uploadableRepositories(repos) {
  return repos.filter((repo) => {
    if (!repo.online) return false;
    if (!uploadSpecsCache.has(repo.format)) return false;
    return !(repo.format === "maven2" && repo.hostedVersionPolicy === "SNAPSHOT");
  });
}

function selectedUploadRepository() {
  const name = document.getElementById("upload-repository").value;
  return uploadRepositoriesCache.find((repo) => repo.name === name) || null;
}

function renderUploadFields() {
  const repo = selectedUploadRepository();
  const fields = document.getElementById("upload-fields");
  if (!repo) {
    fields.innerHTML = '<div class="muted-row upload-empty">No supported hosted repository selected.</div>';
    return;
  }
  if (repo.format === "maven2") {
    fields.innerHTML = `
      <label>
        <span>Group ID</span>
        <input id="upload-group-id" type="text" placeholder="com.acme" required>
      </label>
      <label>
        <span>Artifact ID</span>
        <input id="upload-artifact-id" type="text" placeholder="demo" required>
      </label>
      <label>
        <span>Version</span>
        <input id="upload-version" type="text" placeholder="1.0.0" required>
      </label>
      <label>
        <span>Packaging</span>
        <input id="upload-packaging" type="text" placeholder="jar">
      </label>
      <label class="upload-check">
        <input id="upload-generate-pom" type="checkbox">
        <span>Generate POM</span>
      </label>
      <div class="upload-assets" id="upload-assets">
        ${mavenAssetRows()}
      </div>
      <button class="secondary-button upload-add-asset" id="upload-add-asset" type="button">Add asset</button>
      <label class="upload-path">
        <span>Path</span>
        <textarea id="upload-path" readonly></textarea>
      </label>
      <datalist id="upload-extension-options">
        <option value="jar"></option>
        <option value="pom"></option>
        <option value="xml"></option>
        <option value="zip"></option>
        <option value="asc"></option>
      </datalist>
    `;
    document.getElementById("upload-add-asset").addEventListener("click", () => {
      uploadAssetCount += 1;
      document.getElementById("upload-assets").innerHTML = mavenAssetRows();
      updateUploadPath();
    });
    bindRemoveAssetButtons();
    return;
  }
  fields.innerHTML = `
    <label class="upload-file">
      <span>File</span>
      <input id="upload-file" type="file" required>
    </label>
    <label class="upload-path">
      <span>Asset</span>
      <input id="upload-path" type="text" readonly>
    </label>
  `;
}

function mavenAssetRows() {
  return Array.from({ length: uploadAssetCount }, (_, index) => {
    const number = index + 1;
    return `
      <div class="upload-asset-row" data-asset-index="${number}">
        <label>
          <span>Classifier</span>
          <input class="upload-classifier" type="text">
        </label>
        <label>
          <span>Extension</span>
          <input class="upload-extension" list="upload-extension-options" type="text" value="${number === 1 ? "jar" : ""}" required>
        </label>
        <label class="upload-file">
          <span>File</span>
          <input class="upload-file-input" type="file" required>
        </label>
        <button class="secondary-button upload-remove-asset" type="button" ${uploadAssetCount === 1 ? "disabled" : ""}>Remove</button>
      </div>
    `;
  }).join("");
}

function bindRemoveAssetButtons() {
  document.querySelectorAll(".upload-remove-asset").forEach((button) => {
    button.addEventListener("click", () => {
      if (uploadAssetCount <= 1) return;
      uploadAssetCount -= 1;
      document.getElementById("upload-assets").innerHTML = mavenAssetRows();
      bindRemoveAssetButtons();
      updateUploadPath();
    });
  });
}

function uploadFieldValue(id) {
  const node = document.getElementById(id);
  return node ? node.value.trim() : "";
}

function computedUploadPaths() {
  const repo = selectedUploadRepository();
  if (!repo) return [];
  if (repo.format !== "maven2") {
    const file = document.getElementById("upload-file")?.files?.[0];
    return file ? [file.name] : [];
  }
  const groupId = uploadFieldValue("upload-group-id");
  const artifactId = uploadFieldValue("upload-artifact-id");
  const version = uploadFieldValue("upload-version");
  if (!groupId || !artifactId || !version) return [];
  const paths = [];
  document.querySelectorAll(".upload-asset-row").forEach((row) => {
    const classifier = row.querySelector(".upload-classifier").value.trim();
    const extension = row.querySelector(".upload-extension").value.trim() || "jar";
    const filename = `${artifactId}-${version}${classifier ? `-${classifier}` : ""}.${extension.replace(/^\.+/, "")}`;
    paths.push(`${groupId.replaceAll(".", "/")}/${artifactId}/${version}/${filename}`);
  });
  const generatePom = document.getElementById("upload-generate-pom")?.checked;
  const hasPom = paths.some((path) => path.endsWith(".pom"));
  if (generatePom && !hasPom) {
    paths.push(`${groupId.replaceAll(".", "/")}/${artifactId}/${version}/${artifactId}-${version}.pom`);
  }
  return paths;
}

function updateUploadPath() {
  const target = document.getElementById("upload-path");
  if (!target) return;
  const value = computedUploadPaths().join("\n");
  target.value = value;
}

function setUploadStatus(message, tone = "") {
  const status = document.getElementById("upload-status");
  status.textContent = message;
  status.className = `upload-status ${tone}`.trim();
}

async function uploadSelectedAsset(event) {
  event.preventDefault();
  const repo = selectedUploadRepository();
  if (!repo) {
    setUploadStatus("Repository is required.", "error");
    return;
  }
  const form = new FormData();
  try {
    buildUploadForm(repo, form);
  } catch (error) {
    setUploadStatus(error.message, "error");
    return;
  }
  const submit = document.getElementById("upload-submit");
  submit.disabled = true;
  setUploadStatus("Uploading...");
  try {
    const response = await fetch(`/service/rest/v1/components?repository=${encodeURIComponent(repo.name)}`, {
      method: "POST",
      body: form,
    });
    if (!response.ok) {
      throw new Error(await uploadError(response));
    }
    treeCache.clear();
    clearUploadForm();
    setUploadStatus(`Uploaded to ${repo.name}`, "ok");
  } catch (error) {
    setUploadStatus(`Upload failed: ${error.message}`, "error");
  } finally {
    submit.disabled = false;
  }
}

function clearUploadForm() {
  uploadAssetCount = 1;
  renderUploadFields();
  updateUploadPath();
}

function buildUploadForm(repo, form) {
  if (repo.format === "maven2") {
    const groupId = uploadFieldValue("upload-group-id");
    const artifactId = uploadFieldValue("upload-artifact-id");
    const version = uploadFieldValue("upload-version");
    if (!groupId || !artifactId || !version) {
      throw new Error("Group ID, Artifact ID, and Version are required.");
    }
    form.append("maven2.groupId", groupId);
    form.append("maven2.artifactId", artifactId);
    form.append("maven2.version", version);
    const packaging = uploadFieldValue("upload-packaging");
    if (packaging) form.append("maven2.packaging", packaging);
    if (document.getElementById("upload-generate-pom")?.checked) {
      form.append("maven2.generate-pom", "true");
    }
    const rows = Array.from(document.querySelectorAll(".upload-asset-row"));
    if (!rows.length) throw new Error("At least one asset is required.");
    rows.forEach((row, index) => {
      const file = row.querySelector(".upload-file-input").files[0];
      const extension = row.querySelector(".upload-extension").value.trim();
      const classifier = row.querySelector(".upload-classifier").value.trim();
      if (!file || !extension) throw new Error("Each Maven asset requires File and Extension.");
      const assetKey = `maven2.asset${index + 1}`;
      form.append(assetKey, file, file.name);
      form.append(`${assetKey}.extension`, extension.replace(/^\.+/, ""));
      if (classifier) form.append(`${assetKey}.classifier`, classifier);
    });
    return;
  }
  const file = document.getElementById("upload-file")?.files?.[0];
  if (!file) throw new Error("File is required.");
  form.append(`${repo.format}.asset`, file, file.name);
}

async function uploadError(response) {
  const text = await response.text();
  if (!text) return `HTTP ${response.status}`;
  try {
    const json = JSON.parse(text);
    return json.error || json.message || text;
  } catch (_) {
    return text;
  }
}

async function renderSearch(format = DEFAULT_SEARCH_FORMAT) {
  const seq = ++searchRequestSeq;
  const keyword = document.getElementById("component-keyword").value.trim().toLowerCase();
  document.getElementById("component-table").innerHTML =
    '<tr><td colspan="7" class="muted-row">Searching...</td></tr>';
  let payload;
  try {
    payload = await fetchSearchComponents(format, keyword);
  } catch (error) {
    if (seq !== searchRequestSeq) return;
    document.getElementById("component-total").textContent = "0";
    document.getElementById("component-table").innerHTML =
      `<tr><td colspan="7" class="muted-row">Search failed: ${escapeHtml(error.message)}</td></tr>`;
    return;
  }
  if (seq !== searchRequestSeq) return;
  const rows = payload.items || [];
  componentsCache = rows;
  document.getElementById("component-total").textContent = rows.length.toLocaleString("en-US");
  if (!rows.length) {
    document.getElementById("component-table").innerHTML =
      '<tr><td colspan="7" class="muted-row">No components found.</td></tr>';
    return;
  }
  document.getElementById("component-table").innerHTML = rows.map((component, index) => {
    const browsePath = componentBrowsePath(component);
    const title = browsePath
      ? `Open ${component.repository}/${browsePath} in Browse`
      : `Open ${component.repository} in Browse`;
    return `
    <tr class="component-result-row" data-component-result-index="${index}" tabindex="0" title="${escapeHtml(title)}">
      <td class="icon-column"><span class="component-icon">▰</span></td>
      <td>${escapeHtml(component.name)}</td>
      <td>${component.group ? escapeHtml(component.group) : '<span class="health-muted">⊘</span>'}</td>
      <td>${escapeHtml(component.version)}</td>
      <td>${escapeHtml(component.format)}</td>
      <td>${escapeHtml(component.repository)}</td>
      <td class="expand-column">›</td>
    </tr>
  `;
  }).join("");
  document.querySelectorAll("#component-table .component-result-row").forEach((tr) => {
    const result = rows[Number(tr.dataset.componentResultIndex)];
    tr.addEventListener("click", () => openSearchResult(result));
    tr.addEventListener("keydown", (event) => {
      if (event.key !== "Enter" && event.key !== " ") return;
      event.preventDefault();
      openSearchResult(result);
    });
  });
}

function switchView(view) {
  document.querySelectorAll(".view").forEach((item) => {
    item.classList.toggle("is-active", item.id === `${view}-view`);
  });
  document.querySelectorAll(".side-item").forEach((item) => {
    item.classList.toggle("is-active", view !== "search" && item.dataset.view === view);
  });
  if (view !== "search") clearSearchFormatSelection();
  // when leaving Browse, restore the original tools/frame
  if (view !== "browse") {
    const drill = document.getElementById("browse-drill");
    if (drill) drill.remove();
    const tools = document.querySelector("#browse-view .table-tools");
    if (tools) tools.style.display = "";
    const frame = document.getElementById("repository-table").parentElement.parentElement;
    frame.style.display = "";
  }
}

function setSearchSubnavOpen(open) {
  const toggle = document.querySelector('.side-item[data-view="search"]');
  const subnav = document.getElementById("search-subnav");
  if (!toggle || !subnav) return;
  toggle.classList.toggle("is-expanded", open);
  toggle.setAttribute("aria-expanded", open ? "true" : "false");
  subnav.hidden = !open;
}

function clearSearchFormatSelection() {
  document.querySelectorAll(".side-subitem").forEach((entry) => {
    entry.classList.remove("is-active");
  });
}

function selectSearchFormat(format) {
  activeSearchFormat = normalizeSearchFormat(format);
  document.querySelectorAll(".side-subitem").forEach((entry) => {
    entry.classList.toggle("is-active", entry.dataset.searchFormat === activeSearchFormat);
  });
}

function applyHashRoute() {
  const route = parseBrowseHash();
  if (!route) return false;
  if (route.view === "welcome") {
    showWelcome(false);
    canonicalizeBrowseRoute();
    return true;
  }
  if (route.view === "upload") {
    showUpload(false);
    canonicalizeBrowseRoute();
    return true;
  }
  if (route.view === "my-token") {
    showMyToken(false);
    canonicalizeBrowseRoute();
    return true;
  }
  if (route.view === "search") {
    showSearch(route.searchFormat, false);
    window.history.replaceState(null, "", `/browse/${searchHash(route.searchFormat)}`);
    return true;
  }
  switchView("browse");
  if (route.repo && repositoryExists(route.repo)) showRepositoryTree(route.repo, false, route.path || "");
  else showRepositoryList(false);
  canonicalizeBrowseRoute();
  return true;
}

async function bootstrap() {
  try {
    adminBootstrapStatus = await fetchAdminBootstrapStatus();
  } catch (e) {
    adminBootstrapStatus = null;
  }
  renderAdminBootstrap();

  const hydratedSession = hydrateAuthSnapshot();
  try {
    currentSession = await fetchSession();
  } catch (e) {
    currentSession = null;
  }
  if (!currentSession) {
    currentPermissions = [];
    writeAuthSnapshot();
  } else if (sessionIdentity(hydratedSession) !== sessionIdentity(currentSession)) {
    currentPermissions = [];
  }
  updateTopbarAuth();
  try {
    currentPermissions = currentSession ? await fetchPermissions() : [];
  } catch (e) {
    currentPermissions = [];
  }
  writeAuthSnapshot();
  updateTopbarAuth();

  try {
    [repositoriesCache, uploadSpecsCache] = await Promise.all([
      fetchRepositories(),
      fetchUploadSpecs(),
    ]);
  } catch (e) {
    repositoriesCache = [];
    uploadSpecsCache = new Map();
    uploadRepositoriesLoaded = true;
    uploadRepositoriesCache = [];
    updateTopbarAuth();
    renderUpload();
    document.getElementById("repository-table").innerHTML =
      `<tr><td colspan="6" class="muted-row">Failed to load repositories: ${e.message}</td></tr>`;
    openPendingLoginIfRequested();
    return;
  }
  try {
    uploadRepositoriesCache = await fetchUploadableRepositories();
  } catch (e) {
    uploadRepositoriesCache = [];
  } finally {
    uploadRepositoriesLoaded = true;
  }
  updateTopbarAuth();
  renderUpload();
  if (!applyHashRoute()) {
    renderRepoList();
  }
  openPendingLoginIfRequested();
}

document.querySelectorAll(".side-item").forEach((item) => {
  item.addEventListener("click", () => {
    const view = item.dataset.view;
    if (view === "search") {
      const subnav = document.getElementById("search-subnav");
      setSearchSubnavOpen(subnav ? subnav.hidden : true);
      return;
    }
    if (view === "browse") showRepositoryList();
    else if (view === "upload") showUpload();
    else if (view === "welcome") showWelcome();
  });
});

document.querySelectorAll(".side-subitem").forEach((item) => {
  item.addEventListener("click", () => {
    showSearch(item.dataset.searchFormat);
  });
});

document.getElementById("repository-filter").addEventListener("input", () => {
  if (state.mode === "repos") renderRepoList();
});
document.querySelectorAll("[data-repo-sort]").forEach((button) => {
  button.addEventListener("click", () => toggleRepositorySort(button.dataset.repoSort));
});
document.getElementById("upload-repository").addEventListener("change", () => {
  uploadAssetCount = 1;
  renderUploadFields();
  updateUploadPath();
});
document.getElementById("upload-fields").addEventListener("input", updateUploadPath);
document.getElementById("upload-fields").addEventListener("change", updateUploadPath);
document.getElementById("component-search-button").addEventListener("click", () => renderSearch(activeSearchFormat));
document.getElementById("upload-form").addEventListener("submit", uploadSelectedAsset);
document.getElementById("my-token-form").addEventListener("submit", createCurrentApiKey);
document.getElementById("my-token-display-name").addEventListener("input", (event) => {
  if (event.target.value.trim()) {
    markMyTokenDisplayNameValidity(false);
    setMyTokenStatus("");
  }
});
document.getElementById("my-token-copy").addEventListener("click", copyGeneratedToken);
document.getElementById("admin-bootstrap-panel").addEventListener("submit", submitAdminBootstrap);
document.getElementById("login-button").addEventListener("click", login);
document.getElementById("user-menu").addEventListener("mouseenter", openUserMenu);
document.getElementById("user-menu").addEventListener("mouseleave", scheduleCloseUserMenu);
document.getElementById("user-menu-trigger").addEventListener("click", (event) => {
  event.stopPropagation();
  toggleUserMenu();
});
document.getElementById("my-token-menu-item").addEventListener("click", () => {
  closeUserMenu();
  showMyToken();
});
document.getElementById("signout-button").addEventListener("click", logout);
document.addEventListener("click", (event) => {
  if (!document.getElementById("user-menu").contains(event.target)) closeUserMenu();
});
document.addEventListener("keydown", (event) => {
  if (event.key === "Escape") closeUserMenu();
});
window.addEventListener("hashchange", applyHashRoute);
window.addEventListener("popstate", applyHashRoute);

bootstrap();
