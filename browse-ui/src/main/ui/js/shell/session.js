import { AUTH_SNAPSHOT_KEY, clearSessionSnapshot, readSessionSnapshot, saveSessionSnapshot } from "#ui-shared/session-snapshot.js";
import { permissionMatches } from "#ui-shared/permissions.js";
import { renderAdminBootstrap } from "../account/setup.js";
import { showRepositoryList } from "../repositories/list.js";
import { closeUserMenu } from "./account.js";
import { APP_HASH_PREFIX, showWelcome } from "./routes.js";
import { canShowUploadNav } from "../upload/catalog.js";

const sessionState = {
  currentSession: null,
  currentPermissions: [],
};

let adminBootstrapStatus = null;

let pendingLoginReturnTo;

export function initializeLoginReturnTo() {
  pendingLoginReturnTo = readPendingLoginReturnTo();
}

function currentReturnTo() {
  return `${window.location.pathname}${window.location.search}${window.location.hash}` || "/browse/";
}

function safeLocalReturnTo(value) {
  return window.kkrepoSafeReturnTo(value, "");
}

function readPendingLoginReturnTo() {
  const params = new URLSearchParams(window.location.search);
  if (params.get("login") !== "1") return "";
  return safeLocalReturnTo(params.get("returnTo")) || "/browse/#browse/welcome";
}

function openPendingLoginIfRequested() {
  if (!pendingLoginReturnTo || sessionState.currentSession || adminBootstrapStatus?.required) return;
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

function accountSourceLabel(source) {
  const value = displaySource(source).trim();
  if (!value) return "Authenticated account";
  return `${value.charAt(0).toUpperCase()}${value.slice(1)} account`;
}

function writeAuthSnapshot() {
  saveSessionSnapshot(sessionState.currentSession, sessionState.currentPermissions);
}

function hydrateAuthSnapshot() {
  const snapshot = readSessionSnapshot();
  if (!snapshot) return null;
  sessionState.currentSession = snapshot.session;
  sessionState.currentPermissions = Array.isArray(snapshot.permissions)
    ? snapshot.permissions.filter(Boolean)
    : [];
  updateTopbarAuth();
  return snapshot.session;
}

async function fetchUiContext() {
  const res = await fetch("/internal/security/context", {
    headers: { Accept: "application/json" },
    cache: "no-store",
  });
  if (!res.ok) throw new Error(`Failed to load UI context: ${res.status}`);
  return res.json();
}

async function fetchPermissions() {
  if (!sessionState.currentSession) return [];
  const res = await fetch("/internal/security/permissions", {
    headers: { Accept: "application/json" },
    cache: "no-store",
  });
  if (res.status === 401 || res.status === 403) return [];
  if (!res.ok) throw new Error(`Failed to load permissions: ${res.status}`);
  const payload = await res.json();
  return payload.map((permission) => permission.id || permission).filter(Boolean);
}

function applyUiContext(context) {
  adminBootstrapStatus = context?.bootstrap || null;
  sessionState.currentSession = context?.session || null;
  sessionState.currentPermissions = Array.isArray(context?.permissions)
    ? context.permissions.filter(Boolean)
    : [];
  renderAdminBootstrap();
  writeAuthSnapshot();
  updateTopbarAuth();
}

function can(permission) {
  return sessionState.currentPermissions.some((granted) => permissionMatches(granted, permission));
}

function adminEntryUrl() {
  if (can("nexus:*")) return "/admin/";
  if (can("nexus:security-scanning:read")) return "/admin/#admin/security/artifact-scanning";
  if (["read", "create", "update", "delete"].some(action => can(`nexus:selectors:${action}`))) {
    return "/admin/#admin/repository/content-selectors";
  }
  return can("nexus:settings:read") ? "/admin/#admin/system/proxy-settings" : null;
}

function updateTopbarAuth() {
  const adminLink = document.getElementById("admin-workspace-link");
  const loginButton = document.getElementById("login-button");
  const userMenu = document.getElementById("user-menu");
  const userMenuTrigger = document.getElementById("user-menu-trigger");
  const currentUser = document.getElementById("current-user");
  const userMenuName = document.getElementById("user-menu-name");
  const userMenuSource = document.getElementById("user-menu-source");
  const uploadNav = document.getElementById("upload-nav");

  const signedIn = Boolean(sessionState.currentSession && sessionState.currentSession.userId);
  if (adminLink) {
    const entryUrl = adminEntryUrl();
    adminLink.hidden = !signedIn || !entryUrl;
    adminLink.href = entryUrl || "/admin/";
  }
  loginButton.hidden = signedIn;
  userMenu.hidden = !signedIn;
  if (!signedIn) closeUserMenu();
  if (signedIn) {
    const userId = String(sessionState.currentSession.userId);
    const source = displaySource(sessionState.currentSession.source).trim();
    const sourceLabel = accountSourceLabel(source);
    const qualifiedUser = source ? `${source}/${userId}` : userId;

    currentUser.textContent = userId;
    userMenuName.textContent = userId;
    userMenuSource.textContent = sourceLabel;
    userMenuTrigger.title = `Account menu for ${qualifiedUser}`;
    userMenuTrigger.setAttribute("aria-label", `Account menu for ${qualifiedUser}`);
  }

  uploadNav.hidden = !canShowUploadNav();
  if (!signedIn && document.getElementById("upload-view").classList.contains("is-active")) {
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
  clearSessionSnapshot();
  window.location.href = `/internal/security/logout?returnTo=${encodeURIComponent("/browse/#browse/welcome")}`;
}

export {
  AUTH_SNAPSHOT_KEY,
  adminBootstrapStatus,
  applyUiContext,
  can,
  currentReturnTo,
  displaySource,
  fetchUiContext,
  hydrateAuthSnapshot,
  login,
  logout,
  openPendingLoginIfRequested,
  safeLocalReturnTo,
  sessionState,
  updateTopbarAuth,
  writeAuthSnapshot,
};
