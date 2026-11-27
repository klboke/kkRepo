import { copyRepositoryUrl } from "../controls/clipboard.js";
import { formatBadge, lucideIcon, repoIcon } from "../controls/icons.js";
import { escapeHtml } from "../details/format.js";
import { browseListHash, pushBrowseRoute, switchView } from "../shell/routes.js";
import { expanded, renderBrowseHeading, showRepositoryTree, state } from "../tree/browser.js";

const listState = {
  repositoriesCache: [],
};

let repositorySort = { key: "name", direction: "asc" };

function repositoryExists(repoName) {
  return listState.repositoriesCache.some((repo) => repo.name === repoName);
}

function currentRepository() {
  return listState.repositoriesCache.find((repo) => repo.name === state.repo) || null;
}

function repositoryBaseUrl(repoName = state.repo) {
  const repository = listState.repositoriesCache.find(repo => repo.name === repoName);
  const path = repository?.url || `/repository/${encodeURIComponent(repoName)}/`;
  return new URL(path, window.location.origin).href;
}

function dockerRepositoryBaseUrl(repo = currentRepository()) {
  if (!repo) return "";
  const docker = repo.docker || {};
  if ((docker.connectorEnabled || docker.httpsConnectorEnabled) && docker.connectorPublicUrl) {
    return docker.connectorPublicUrl.replace(/\/+$/, "");
  }
  if (docker.httpsConnectorEnabled && docker.httpsConnectorPort) {
    return `https://${window.location.hostname}:${docker.httpsConnectorPort}`;
  }
  if (docker.connectorEnabled && docker.connectorPort) {
    return `http://${window.location.hostname}:${docker.connectorPort}`;
  }
  return new URL(`/v2/${encodeURIComponent(repo.name)}`, window.location.origin).href.replace(/\/+$/, "");
}

async function fetchRepositories() {
  const res = await fetch("/internal/repositories", { headers: { Accept: "application/json" }, cache: "no-store" });
  if (!res.ok) throw new Error(`Failed to load repositories: ${res.status}`);
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
    apt: repo.apt || null,
    hostedVersionPolicy: repo.hosted ? repo.hosted.versionPolicy : null,
    status: repo.online ? "Online" : "Offline",
  };
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
    if (indicator) {
      indicator.classList.add("lucide-icon");
      indicator.classList.toggle("icon-arrow-up-down", !active);
      indicator.classList.toggle("icon-arrow-up", active && direction === "asc");
      indicator.classList.toggle("icon-arrow-down", active && direction === "desc");
    }
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
  state.source = "";
  expanded.clear();
  restoreBrowseListLayout();
  renderRepoList();
}

function renderRepoList() {
  const filter = document.getElementById("repository-filter").value;
  const rows = sortRepositoryRows(repositoryRows(listState.repositoriesCache, filter, false));
  restoreBrowseListLayout();
  updateRepositorySortHeaders();
  document.getElementById("repository-table").innerHTML = rows.map((repo) => {
    const clientUrl = repositoryClientUrl(repo);
    return `
    <tr data-repo="${repo.name}" class="repo-row">
      <td class="icon-column">${repoIcon(repo.type)}</td>
      <td>${repo.name}</td>
      <td>${repo.type}</td>
      <td>${formatBadge(repo.format)}</td>
      <td>${repo.status}</td>
      <td class="repo-url-cell">
        <div class="repo-url-content">
          <a class="repo-url-link" href="${escapeHtml(clientUrl)}" target="_blank" rel="noopener">${escapeHtml(clientUrl)}</a>
          <button type="button" class="repo-copy-button" data-repository-url="${escapeHtml(clientUrl)}" title="Copy repository URL" aria-label="Copy repository URL">${lucideIcon("copy")}</button>
        </div>
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

export {
  currentRepository,
  fetchRepositories,
  listState,
  normalizeRepository,
  renderRepoList,
  repositoryBaseUrl,
  repositoryExists,
  repositoryRows,
  showRepositoryList,
  toggleRepositorySort,
};
