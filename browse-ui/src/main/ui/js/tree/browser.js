import { formatIconName, iconForDir, iconForFile, lucideIcon } from "../controls/icons.js";
import { pathSegments } from "../details/format.js";
import { showAssetDetail, showComponentDetail } from "../details/pane.js";
import { currentRepository, showRepositoryList } from "../repositories/list.js";
import { showSearch } from "../search/results.js";
import { DEFAULT_SEARCH_FORMAT, pushBrowseRoute, repositoryBrowseHash, switchView } from "../shell/routes.js";
import { initSplitResizer } from "./resizer.js";

// Live data backing the /browse UI.
//   state.mode = "repos" → repo list
//   state.mode = "tree" → Nexus-style expandable tree for state.repo (root = "")
// Children of each path are fetched lazily on first expand and cached in `treeCache`.
const state = { mode: "repos", repo: null, path: "", source: "" };

const treeCache = new Map();

// key = `${repo}::${source}::${path}`, value = entries[]
const expanded = new Set();

// keys (same shape as treeCache) currently expanded
const treeMountLoads = new WeakMap();

const treeNodeEntries = new WeakMap();

async function fetchChildren(repo, path) {
  const source = state.repo === repo ? state.source : "";
  const key = `${repo}::${source}::${path}`;
  if (treeCache.has(key)) return treeCache.get(key);
  const params = new URLSearchParams();
  if (path) params.set("path", path);
  if (source) params.set("source", source);
  const query = params.toString();
  const url = `/internal/browse/${encodeURIComponent(repo)}${query ? `?${query}` : ""}`;
  const res = await fetch(url, { headers: { Accept: "application/json" } });
  if (!res.ok) throw new Error(`Browse failed: ${res.status}`);
  const data = await res.json();
  treeCache.set(key, data.entries);
  return data.entries;
}

function renderBrowseHeading() {
  const browseView = document.getElementById("browse-view");
  browseView.querySelector(".page-heading").innerHTML = `
    <span class="heading-icon">${lucideIcon("library")}</span>
    <h1>Browse</h1>
    <span class="heading-copy">Browse assets and components</span>
  `;
}

function showRepositoryTree(repoName, syncHash = true, path = "", sourceRepository = "") {
  if (!repoName) {
    showRepositoryList(syncHash);
    return;
  }
  if (syncHash) pushBrowseRoute(repositoryBrowseHash(repoName, path, sourceRepository));
  switchView("browse");
  state.mode = "tree";
  state.repo = repoName;
  state.path = path || "";
  state.source = sourceRepository || "";
  expanded.clear();
  treeCache.clear();
  renderTree();
}

// --- tree view ---------------------------------------------------------------
function repoTopIcon() {
  return lucideIcon("database", "crumb-icon");
}

function repositoryFormatIcon(repo = currentRepository()) {
  return `<span class="format-logo crumb-format-logo format-logo-${formatIconName(repo?.format)}" aria-hidden="true"></span>`;
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
        ${repositoryFormatIcon()}
        <span class="crumb-current">${state.repo}</span>
      </div>
      <div class="tree-actions">
        <a class="tree-link" href="/service/rest/repository/browse/${encodeURIComponent(state.repo)}/" target="_blank">HTML View</a>
        <a class="tree-link" href="#" id="advanced-search-link">Advanced search…</a>
      </div>
    </div>
    <div class="browse-split">
      <div class="tree-left">
        <div class="tree-root" id="tree-root">Loading…</div>
      </div>
      <div class="split-handle" role="separator" aria-orientation="vertical" aria-label="Resize tree and detail panes" tabindex="0"></div>
      <aside class="detail-right" id="detail-right">
        <div class="detail-placeholder">Select a component or file in the tree to view details.</div>
      </aside>
    </div>
  `;
  initSplitResizer(drill.querySelector(".browse-split"));
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

async function activateTreeBranch(entry, toggleExpand) {
  const expansion = toggleExpand();
  await showComponentDetail(entry);
  await expansion;
}

function buildTreeNode(entry) {
  const li = document.createElement("li");
  li.className = entry.leaf ? "tree-node leaf" : "tree-node branch";
  li.dataset.path = entry.path;
  treeNodeEntries.set(li, entry);

  const row = document.createElement("div");
  row.className = "tree-row";
  const key = `${state.repo}::${entry.path}`;
  const isExpanded = expanded.has(key);

  const toggle = document.createElement("button");
  toggle.type = "button";
  toggle.className = "tree-toggle";
  toggle.disabled = entry.leaf;
  if (entry.leaf) {
    toggle.setAttribute("aria-hidden", "true");
    toggle.tabIndex = -1;
  } else {
    setTreeToggleIcon(toggle, isExpanded);
  }
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
    const ensureLoaded = async () => {
      await ensureTreeLevelLoaded(entry.path, childMount, 1);
    };
    const setExpanded = async (open) => {
      if (open) {
        expanded.add(key);
        setTreeToggleIcon(toggle, true);
        childMount.style.display = "";
        await ensureLoaded();
      } else {
        expanded.delete(key);
        setTreeToggleIcon(toggle, false);
        childMount.style.display = "none";
      }
    };
    const toggleExpand = () => setExpanded(!expanded.has(key));
    toggle.addEventListener("click", toggleExpand);
    label.classList.add("clickable");
    label.addEventListener("click", async () => {
      selectRow(row);
      syncTreePath(entry);
      await activateTreeBranch(entry, toggleExpand);
    });
    if (isExpanded) {
      ensureLoaded();
    }
  }

  return li;
}

function setTreeToggleIcon(toggle, open) {
  const label = open ? "Collapse folder" : "Expand folder";
  toggle.innerHTML = lucideIcon(open ? "minus" : "plus");
  toggle.title = label;
  toggle.setAttribute("aria-label", label);
}

function selectRow(row) {
  document.querySelectorAll(".tree-row.is-selected").forEach((el) => el.classList.remove("is-selected"));
  row.classList.add("is-selected");
}

function syncTreePath(entry) {
  if (!state.repo || !entry || !entry.path) return;
  state.path = entry.path;
  const target = `/browse/${repositoryBrowseHash(state.repo, entry.path, state.source)}`;
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

function centerTreeRow(row) {
  const tree = row?.closest?.(".tree-left");
  if (!tree) return;
  const treeRect = tree.getBoundingClientRect();
  const rowRect = row.getBoundingClientRect();
  const target = tree.scrollTop
    + rowRect.top + (rowRect.height / 2)
    - treeRect.top - (tree.clientHeight / 2);
  const maxScrollTop = Math.max(0, tree.scrollHeight - tree.clientHeight);
  tree.scrollTop = Math.min(maxScrollTop, Math.max(0, target));
}

async function selectInitialTreePath() {
  if (!state.path) return;
  await revealTreePath(state.path);
  const node = document.querySelector(`.tree-node[data-path="${pathSelectorValue(state.path)}"]`);
  const row = node?.querySelector(":scope > .tree-row");
  if (!row) return;
  selectRow(row);
  centerTreeRow(row);
  const entry = treeNodeEntries.get(node) || findCachedTreeEntry(state.path);
  if (!entry) return;
  if (entry.leaf) await showAssetDetail(entry);
  else await showComponentDetail(entry);
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
    if (toggle) setTreeToggleIcon(toggle, true);
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

export {
  expanded,
  fetchChildren,
  loadAndRenderTreeLevel,
  pathSelectorValue,
  renderBrowseHeading,
  repositoryFormatIcon,
  selectRow,
  showRepositoryTree,
  state,
  syncTreePath,
  treeCache,
  treeMountLoads,
  treeNodeEntries,
};
