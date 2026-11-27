import { renderCopyableLocation } from "../controls/clipboard.js";
import { escapeHtml, lowerOrEmpty, lucideIcon } from "../controls/display.js";
import { formatBadge } from "../controls/formats.js";
import { updateTableSortHeaders } from "../controls/table.js";
import { showToast } from "../controls/toast.js";
import { refreshRepositoryMemberOptions } from "./members.js";
import { listRepositories } from "./api.js";
import {
  fetchInventoryUsage,
  inventoryValue,
  renderRepositoryMetric,
  renderUsageSummary,
} from "../storage/usage.js";

const listState = {
  repositories: [],
  repositorySort: { key: "name", direction: "asc" },
};

let repositoryUsage;

let repositoryLoadVersion = 0;
let repositoryListMessage = "Loading…";

function repoIcon(type) {
  const lower = String(type || "").toLowerCase();
  if (lower === "group") return lucideIcon("boxes", "repo-icon group");
  if (lower === "proxy") return lucideIcon("cloud-download", "repo-icon proxy");
  return lucideIcon("package", "repo-icon hosted");
}

function filteredRepositories() {
  const filter = document.getElementById("repository-filter").value.trim().toLowerCase();
  const store = document.getElementById("repository-store-filter").value;
  return listState.repositories.filter((repo) => {
    if (store && repo.blobStoreName !== store) return false;
    if (!filter) return true;
    return `${repo.name} ${lowerOrEmpty(repo.type)} ${lowerOrEmpty(repo.format)} ${lowerOrEmpty(repo.recipe)} ${lowerOrEmpty(repo.blobStoreName)} ${lowerOrEmpty(repositoryDisplayUrl(repo))}`.includes(filter);
  });
}

function repositorySortValue(repo, key) {
  if (["assetCount", "totalBytes"].includes(key)) {
    return lowerOrEmpty(repo.type) === "group" ? null : inventoryValue(repositoryUsage, repo.id, key);
  }
  if (key === "recipe") return repo.recipe || "";
  if (key === "type") return lowerOrEmpty(repo.type);
  if (key === "format") return lowerOrEmpty(repo.format);
  return repo.name || "";
}

function sortRepositories(rows) {
  return [...rows].sort((a, b) => {
    const left = repositorySortValue(a, listState.repositorySort.key);
    const right = repositorySortValue(b, listState.repositorySort.key);
    if (left == null || right == null) return left == null ? (right == null ? 0 : 1) : -1;
    const primary = typeof left === "bigint" ? (left < right ? -1 : left > right ? 1 : 0) : left.localeCompare(right, undefined, { numeric: true, sensitivity: "base" });
    const fallback = (a.name || "").localeCompare(b.name || "", undefined, { numeric: true, sensitivity: "base" });
    const result = primary || fallback;
    return listState.repositorySort.direction === "asc" ? result : -result;
  });
}

function toggleRepositorySort(key) {
  if (listState.repositorySort.key === key) {
    listState.repositorySort = {
      key,
      direction: listState.repositorySort.direction === "asc" ? "desc" : "asc",
    };
  } else {
    listState.repositorySort = { key, direction: ["assetCount", "totalBytes"].includes(key) ? "desc" : "asc" };
  }
  renderRepositories();
}

function updateRepositorySortHeaders() {
  updateTableSortHeaders("repository", listState.repositorySort);
}

function renderRepositories() {
  updateRepositorySortHeaders();
  const filtered = filteredRepositories();
  renderUsageSummary("repository", filtered, repositoryUsage);
  const rows = sortRepositories(filtered).map((repo) => {
    const status = repo.online ? "Online" : "Offline";
    const tone = repo.online ? "ok" : "warn";
    const displayUrl = repositoryDisplayUrl(repo);
    const blobStore = repo.blobStoreName
      ? escapeHtml(repo.blobStoreName)
      : '<span class="health-muted">-</span>';
    return `
      <tr>
        <td class="icon-column">${repoIcon(repo.type)}</td>
        <td class="repository-name">${escapeHtml(repo.name)}</td>
        <td data-extra-column>${escapeHtml(repo.recipe)}</td>
        <td data-extra-column>${escapeHtml(lowerOrEmpty(repo.type))}</td>
        <td>${formatBadge(repo.format)}</td>
        <td><span class="state-badge compact ${tone}">${status}</span></td>
        <td data-extra-column class="repository-store-column">${blobStore}</td>
        <td class="inventory-count" data-extra-column>${renderRepositoryMetric(repositoryUsage, repo, "assetCount")}</td>
        <td class="inventory-count" data-extra-column>${renderRepositoryMetric(repositoryUsage, repo, "totalBytes", true)}</td>
        <td class="usage-location" data-extra-column>${renderCopyableLocation(displayUrl, "Copy URL")}</td>
        <td class="actions-column">
          <button class="row-action edit-repository-button" data-name="${escapeHtml(repo.name)}" type="button">edit</button>
          <button class="row-action repository-more" data-name="${escapeHtml(repo.name)}" data-url="${escapeHtml(displayUrl)}" type="button" aria-label="More actions for ${escapeHtml(repo.name)}" aria-expanded="false" aria-controls="repository-actions"><span aria-hidden="true">···</span></button>
        </td>
      </tr>
    `;
  }).join("");
  document.getElementById("repository-table").innerHTML = rows
    || `<tr><td colspan="11" class="placeholder">${escapeHtml(repositoryListMessage || (listState.repositories.length ? "No matching repositories." : "No repositories available."))}</td></tr>`;
}

function repositoryDisplayUrl(repo) {
  if (lowerOrEmpty(repo.format) === "docker") {
    const docker = repo.docker || {};
    const https = docker.httpsConnectorEnabled && docker.httpsConnectorPort;
    const http = docker.connectorEnabled && docker.connectorPort;
    if (https || http) {
      return docker.connectorPublicUrl || `${https ? "https:" : "http:"}//${window.location.hostname}:${https || http}/v2/`;
    }
  }
  if (lowerOrEmpty(repo.type) === "proxy" && repo.proxy?.remoteUrl) return repo.proxy.remoteUrl;
  return repo.url || "";
}

function refreshRepositoryStoreFilter() {
  const select = document.getElementById("repository-store-filter");
  const selected = select.value;
  const names = [...new Set(listState.repositories.map((repo) => repo.blobStoreName).filter(Boolean))].sort();
  // Preserve a drill-down to a store with no visible repositories.
  if (selected && !names.includes(selected)) names.push(selected);
  select.innerHTML = '<option value="">All blob stores</option>'
    + names.map((name) => `<option value="${escapeHtml(name)}">${escapeHtml(name)}</option>`).join("");
  select.value = selected;
}

async function loadRepositories() {
  const version = ++repositoryLoadVersion;
  const refresh = document.getElementById("repository-usage-refresh");
  refresh.ariaBusy = "true";
  if (!listState.repositories.length) {
    repositoryListMessage = "Loading…";
    renderRepositories();
  }
  try {
    const payload = await listRepositories();
    if (version !== repositoryLoadVersion) return;
    listState.repositories = payload;
    repositoryListMessage = "";
    repositoryUsage = undefined;
    if (!listState.repositories.length) repositoryUsage = { usage: {}, calculatedAt: new Date().toISOString() };
    if (listState.repositories.length) {
      fetchInventoryUsage("/internal/repositories/statistics/usage").catch(() => null).then((snapshot) => {
        if (version !== repositoryLoadVersion) return;
        repositoryUsage = snapshot;
        renderRepositories();
      });
    }
  } catch (error) {
    if (version !== repositoryLoadVersion) return;
    repositoryListMessage = "Failed to load repositories";
    listState.repositories = [];
    repositoryUsage = null;
    showToast(`Failed to load repositories: ${error.message}`, "error");
  }
  refresh.ariaBusy = "false";
  refreshRepositoryStoreFilter();
  renderRepositories();
  refreshRepositoryMemberOptions();
}

export {
  listState,
  loadRepositories,
  renderRepositories,
  toggleRepositorySort,
};
