import * as ohpmLogin from "../ohpm-login.js";
import * as ohpmImport from "../ohpm-import.js";
import * as downloadOffload from "../download-offload.js";
import * as proxySettings from "../proxy-settings.js";
import * as routingRules from "../routing-rules.js";
import * as contentSelectors from "../content-selectors.js";
import { loadAuditLogs } from "../audit/log.js";
import { loadCleanupPolicies, selectCleanupTab } from "../cleanup/policies.js";
import { enterRepositoryDataMigration, leaveRepositoryDataMigration } from "../migration/repository-data.js";
import { loadDockerOperations } from "../repositories/docker.js";
import { loadRepositories } from "../repositories/list.js";
import { loadSecurityScanning, selectSecurityScanTab } from "../scanning/overview.js";
import { securityScanState } from "../scanning/pages.js";
import {
  loadSecurityPrivileges,
  loadSecurityRoles,
  loadSecurityUsers,
  securityRoles,
  showSecurityRoleForm,
} from "../security/access.js";
import {
  hideSecurityApiKeyForm,
  hideSecurityApiKeyResult,
  loadSecurityApiKeys,
} from "../security/api-keys.js";
import {
  loadSecurityLdap,
  loadSecurityOidc,
} from "../security/providers.js";
import { canAccessAdminView, sessionState } from "./session.js";
import { loadSecurityAnonymous } from "../security/anonymous.js";
import { loadSecurityRealms } from "../security/realms.js";
import { loadBlobStores } from "../storage/blob-stores.js";
import { loadUiSettings } from "../system/appearance.js";

const SECURITY_SCAN_ROUTE_BASE = "#admin/security/artifact-scanning";

const SECURITY_SCAN_ROUTE_ALIASES =
  [SECURITY_SCAN_ROUTE_BASE, "#admin/security/scanning"];

const SECURITY_SCAN_TABS =
  new Set(["overview", "tasks", "findings", "repositories", "policies", "waivers"]);

const CLEANUP_ROUTE_BASE = "#admin/repository/cleanup-policies";

const CLEANUP_ROUTE_ALIASES =
  [CLEANUP_ROUTE_BASE, "#admin/repository/cleanup"];

const CLEANUP_TABS = new Set(["policies", "runs"]);

const adminViews = {
  repositories: {
    hash: "#admin/repository/repositories",
    aliases: ["#admin", "#admin/repository"],
    enter: () => {
      loadRepositories();
    },
  },
  "cleanup-policies": {
    hash: CLEANUP_ROUTE_BASE,
    aliases: ["#admin/repository/cleanup"],
    enter: () => {
      selectCleanupTab(
        cleanupTabFromHash() || "policies",
        { updateHash: false });
      loadCleanupPolicies();
    },
  },
  "content-selectors": {
    hash: "#admin/repository/content-selectors",
    enter: () => contentSelectors.load(),
  },
  "routing-rules": {
    hash: "#admin/repository/routingrules",
    enter: () => routingRules.load(),
  },
  blobstores: {
    hash: "#admin/repository/blobstores",
    aliases: ["#admin/repository/blob-stores"],
    enter: () => {
      loadBlobStores({ autoCheck: true });
    },
  },
  "docker-registry": {
    hash: "#admin/repository/docker",
    aliases: ["#admin/repository/docker-registry"],
    enter: () => loadDockerOperations(),
  },
  "security-users": {
    hash: "#admin/security/users",
    aliases: ["#admin/security"],
    enter: () => loadSecurityUsers(),
  },
  "security-roles": {
    hash: "#admin/security/roles",
    enter: (options) => {
      loadSecurityRoles().then(() => {
        if (!options.roleId || !document.getElementById("security-roles-view").classList.contains("is-active")) return;
        const role = securityRoles.find((item) => item.roleId === options.roleId);
        if (role) showSecurityRoleForm(role, { viewOnly: true });
      });
    },
  },
  "security-privileges": {
    hash: "#admin/security/privileges",
    enter: () => loadSecurityPrivileges(),
  },
  "security-realms": {
    hash: "#admin/security/realms",
    enter: () => loadSecurityRealms(),
  },
  "security-ldap": {
    hash: "#admin/security/ldap",
    enter: () => loadSecurityLdap(),
  },
  "security-oidc": {
    hash: "#admin/security/oidc",
    enter: () => loadSecurityOidc(),
  },
  "security-anonymous": {
    hash: "#admin/security/anonymous",
    enter: () => loadSecurityAnonymous(),
  },
  "security-api-keys": {
    hash: "#admin/security/api-keys",
    aliases: ["#admin/security/apikeys"],
    enter: () => loadSecurityApiKeys(),
    leave: () => {
      hideSecurityApiKeyForm();
      hideSecurityApiKeyResult();
    },
  },
  "ohpm-login": {
    hash: "#admin/security/ohpm-login",
    enter: () => ohpmLogin.load(),
  },
  "security-scanning": {
    hash: SECURITY_SCAN_ROUTE_BASE,
    leave: () => { ++securityScanState.repositoryEditRequest; },
    aliases: ["#admin/security/scanning"],
    enter: () => {
      selectSecurityScanTab(
          securityScanTabFromHash() || "overview",
          { updateHash: false });
      loadSecurityScanning();
    },
  },
  "security-audit-log": {
    hash: "#admin/security/audit-log",
    aliases: ["#admin/security/audit"],
    enter: () => loadAuditLogs(0),
  },
  "ui-settings": {
    hash: "#admin/system/ui-settings",
    aliases: ["#admin/system", "#admin/system/ui"],
    enter: () => loadUiSettings(),
  },
  "proxy-settings": {
    hash: "#admin/system/proxy-settings",
    enter: () => proxySettings.load(),
  },
  "download-offload": {
    hash: "#admin/system/download-offload",
    enter: () => downloadOffload.load(),
  },
  "nexus-migration": {
    hash: "#admin/migration/nexus",
    aliases: ["#admin/migration"],
  },
  "repository-data-migration": {
    hash: "#admin/migration/repository-data",
    enter: () => enterRepositoryDataMigration(),
    leave: () => leaveRepositoryDataMigration(),
  },
  "ohpm-import": {
    hash: "#admin/migration/ohpm",
    enter: () => ohpmImport.load(),
  },
};

const hashViewRoutes = Object.fromEntries(Object.entries(adminViews).flatMap(([view, route]) =>
  [route.hash, ...(route.aliases || [])].map(hash => [hash, view])));

const SIDE_GROUP_STATE_KEY = "kkrepo.admin.sideGroups";

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
  if (view === "ohpm-login") view = "security-api-keys";
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
  const hash = adminViews[view]?.hash;
  if (!hash || window.location.hash === hash) return;
  if (replace) {
    window.history.replaceState(null, "", hash);
  } else {
    window.history.pushState(null, "", hash);
  }
}

function updateHashForSecurityScanTab(tab, replace = false) {
  const selected = SECURITY_SCAN_TABS.has(tab) ? tab : "overview";
  if (selected !== "repositories") ++securityScanState.repositoryEditRequest;
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

function switchView(view, options = {}) {
  if (!document.getElementById(`${view}-view`)) return false;
  if (!sessionState.initialDataLoaded) {
    // Keep the URL as the pending selection; bootstrap will apply it after authorization.
    // This also lets Back/Forward supersede a click made while discovery was pending.
    if (options.updateHash !== false) updateHashForView(view, Boolean(options.replaceHash));
    return false;
  }
  if (!canAccessAdminView(view)) return false;
  for (const [previous, route] of Object.entries(adminViews)) {
    if (previous !== view && route.leave && document.getElementById(`${previous}-view`)?.classList.contains("is-active")) {
      route.leave();
    }
  }
  if (options.updateHash !== false) {
    updateHashForView(view, Boolean(options.replaceHash));
  }
  document.querySelectorAll(".view").forEach((item) => {
    item.classList.toggle("is-active", item.id === `${view}-view`);
  });
  document.querySelectorAll(".side-item").forEach((item) => {
    item.classList.toggle("is-active", item.dataset.view === (view === "ohpm-login" ? "security-api-keys" : view));
  });
  updateCurrentSideGroup(view);
  adminViews[view]?.enter?.(options);
  return true;
}

function applyHashRoute() {
  if (!sessionState.initialDataLoaded) return false;
  const view = viewFromHash();
  if (!view || !canAccessAdminView(view)) return false;
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

export {
  CLEANUP_TABS,
  SECURITY_SCAN_TABS,
  applyHashRoute,
  initializeSideGroups,
  sideGroupItems,
  switchView,
  updateHashForCleanupTab,
  updateHashForSecurityScanTab,
};
