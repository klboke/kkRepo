import { AUTH_SNAPSHOT_KEY, clearSessionSnapshot, readSessionSnapshot, saveSessionSnapshot } from "#ui-shared/session-snapshot.js";
import { showToast } from "../controls/toast.js";
import { accountSourceLabel, displaySource } from "../security/access.js";
import { closeUserMenu } from "./account.js";
import { responseErrorMessage } from "./http.js";
import { permissionMatches } from "#ui-shared/permissions.js";

let currentSession = null;

let currentAdminPermissions = [];

const sessionState = {
  initialDataLoaded: false,
};

const BROWSE_WELCOME = "/browse/#browse/welcome";

function currentReturnTo() {
  return window.location.pathname + window.location.search + window.location.hash;
}

function safeLocalReturnTo(value) {
  return window.kkrepoSafeReturnTo(value, "");
}

function authRequiredWelcome(returnTo = currentReturnTo()) {
  const params = new URLSearchParams({ login: "1" });
  const target = safeLocalReturnTo(returnTo);
  if (target) params.set("returnTo", target);
  return `/browse/?${params.toString()}#browse/welcome`;
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
    saveSessionSnapshot(currentSession, currentAdminPermissions);
  } else {
    clearSessionSnapshot();
  }
}

function hydrateSessionControls() {
  const snapshot = readSessionSnapshot();
  if (snapshot) updateSessionControls(snapshot.session);
}

async function loadCurrentSession(options = {}) {
  try {
    const response = await fetch("/internal/security/context", { cache: "no-store" });
    if (response.status === 401 || response.status === 403) {
      updateSessionControls(null);
      window.location.href = authRequiredWelcome();
      return null;
    }
    if (!response.ok) throw new Error(await responseErrorMessage(response));
    const context = await response.json();
    const session = context.session;
    currentAdminPermissions = Array.isArray(context.permissions) ? context.permissions : [];
    updateSessionControls(session);
    if (!session) window.location.href = authRequiredWelcome();
    return session;
  } catch (error) {
    updateSessionControls(null);
    if (!options.quiet) {
      showToast(`Session check failed: ${error.message}`, "error");
    }
    return null;
  }
}

function canAdmin(permission) {
  return currentAdminPermissions.some((granted) => permissionMatches(granted, permission));
}

function canAccessAdminView(view) {
  if (canAdmin("nexus:*")) return view !== "loading";
  if (view === "proxy-settings" || view === "download-offload") return canAdmin("nexus:settings:read");
  if (view === "security-scanning") return canAdmin("nexus:security-scanning:read");
  return view === "content-selectors" && ["read", "create", "update", "delete"]
    .some((action) => canAdmin(`nexus:selectors:${action}`));
}

export {
  AUTH_SNAPSHOT_KEY,
  authRequiredWelcome,
  canAccessAdminView,
  canAdmin,
  hydrateSessionControls,
  loadCurrentSession,
  sessionState,
  updateSessionControls,
};
