import { permissionPartMatches } from "#ui-shared/permissions.js";
import { normalizeRepository, showRepositoryList } from "../repositories/list.js";
import { pushBrowseRoute, switchView, viewHash } from "../shell/routes.js";
import { can, sessionState, updateTopbarAuth } from "../shell/session.js";
import { renderUpload } from "./fields.js";

const catalogState = {
  uploadRepositoriesCache: [],
  uploadRepositoriesLoaded: false,
  uploadSpecsCache: new Map(),
};

let uploadRepositoriesPromise = null;

function hasUploadPermission() {
  return can("nexus:component:create") || hasRepositoryUploadPermission();
}

function hasRepositoryUploadPermission() {
  return sessionState.currentPermissions.some((permission) => {
    const parts = (permission || "").toLowerCase().split(":");
    if (parts.length < 5) return false;
    if (parts[0] !== "nexus" || parts[1] !== "repository-view") return false;
    return permissionPartMatches(parts[4], "add");
  });
}

// The Upload entry is offered to signed-in users that hold an add/create permission. Whether
// any repository can actually take a web upload is explained on the page itself instead of
// silently redirecting away from it.
function canShowUploadNav() {
  return Boolean(sessionState.currentSession && hasUploadPermission());
}

// Deliberately neutral: the Upload entry is gated on add/create permissions, while the backend
// lists a repository as uploadable only when the account may edit it, so the page cannot tell
// a missing repository from a missing permission and must not recommend a specific grant.
function uploadEmptyStateMessage() {
  return "No repository is available for web upload with your current account. Web upload needs an "
    + "online hosted repository in a format that supports it (for example Raw, Maven, npm or PyPI) "
    + "and permission to edit it; ask an administrator if you expect one. Docker images are pushed "
    + "with \"docker push\" instead.";
}

async function fetchUploadableRepositories() {
  if (!sessionState.currentSession) return [];
  const res = await fetch("/internal/repositories/uploadable", {
    headers: { Accept: "application/json" },
    cache: "no-store",
  });
  if (res.status === 401 || res.status === 403) return [];
  if (!res.ok) throw new Error(`Failed to load upload repositories: ${res.status}`);
  const data = await res.json();
  return data.map(normalizeRepository);
}

async function fetchUploadSpecs() {
  const res = await fetch("/service/rest/v1/formats/upload-specs", { headers: { Accept: "application/json" } });
  if (!res.ok) throw new Error(`Failed to load upload specs: ${res.status}`);
  const specs = await res.json();
  return new Map(specs.map((spec) => [spec.format, spec]));
}

function showUpload(syncHash = true) {
  if (!sessionState.currentSession) {
    showRepositoryList(true);
    return;
  }
  if (syncHash) pushBrowseRoute(viewHash("upload"));
  switchView("upload");
  renderUpload();
  ensureUploadableRepositories();
}

function uploadableRepositories(repos) {
  return repos.filter((repo) => {
    if (!repo.online) return false;
    if (!catalogState.uploadSpecsCache.has(repo.format)) return false;
    return !(repo.format === "maven2" && repo.hostedVersionPolicy === "SNAPSHOT");
  });
}

function selectedUploadRepository() {
  const name = document.getElementById("upload-repository").value;
  return catalogState.uploadRepositoriesCache.find((repo) => repo.name === name) || null;
}

function selectedUploadSpec(repo = selectedUploadRepository()) {
  return repo ? catalogState.uploadSpecsCache.get(repo.format) || null : null;
}

function ensureUploadableRepositories(force = false) {
  if (!sessionState.currentSession) {
    catalogState.uploadRepositoriesCache = [];
    catalogState.uploadRepositoriesLoaded = true;
    updateTopbarAuth();
    renderUpload();
    return Promise.resolve();
  }
  if (!force && catalogState.uploadRepositoriesLoaded) return Promise.resolve();
  if (uploadRepositoriesPromise) return uploadRepositoriesPromise;
  catalogState.uploadRepositoriesLoaded = false;
  renderUpload();
  uploadRepositoriesPromise = fetchUploadableRepositories()
    .then((repositories) => {
      catalogState.uploadRepositoriesCache = repositories;
    })
    .catch(() => {
      catalogState.uploadRepositoriesCache = [];
    })
    .finally(() => {
      catalogState.uploadRepositoriesLoaded = true;
      uploadRepositoriesPromise = null;
      updateTopbarAuth();
      renderUpload();
    });
  return uploadRepositoriesPromise;
}

export {
  canShowUploadNav,
  catalogState,
  fetchUploadSpecs,
  selectedUploadRepository,
  selectedUploadSpec,
  showUpload,
  uploadEmptyStateMessage,
  uploadableRepositories,
};
