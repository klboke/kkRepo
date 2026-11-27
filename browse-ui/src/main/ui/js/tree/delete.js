import { VERSION_DIR_RE } from "../controls/icons.js";
import { escapeHtml, formatBytes, pathSegments } from "../details/format.js";
import { detailPaneMount, showAssetDetail, showComponentDetail } from "../details/pane.js";
import { hasDirectoryUsage } from "../details/usage.js";
import { imageNameFromDockerPath } from "../formats/docker.js";
import { currentRepository } from "../repositories/list.js";
import { repositoryBrowseHash } from "../shell/routes.js";
import { AUTH_SNAPSHOT_KEY, can, sessionState } from "../shell/session.js";
import {
  expanded,
  loadAndRenderTreeLevel,
  pathSelectorValue,
  selectRow,
  state,
  syncTreePath,
  treeCache,
  treeMountLoads,
  treeNodeEntries,
} from "./browser.js";

function canDeleteBrowseContent() {
  return Boolean(sessionState.currentSession && can("nexus:*"));
}

function canDeleteBrowseDirectory(entry) {
  const repo = currentRepository();
  return canDeleteBrowseContent() && entry && !entry.leaf && Boolean(entry.path)
    && String(repo?.type).toLowerCase() === "hosted"
    && ["maven2", "npm", "pypi", "nuget", "yum", "rubygems", "raw"].includes(repo?.format);
}

// Usage snippets can describe arbitrary prefixes; they do not establish a deletable component.
function canDeleteBrowseComponentDirectory(entry) {
  if (!canDeleteBrowseContent() || !entry || entry.leaf || !entry.path) return false;
  const format = currentRepository()?.format;
  const parts = pathSegments(entry.path);
  if (format === "ohpm") {
    const packageParts = parts[0]?.startsWith("@") ? 2 : 1;
    return parts.length === packageParts
      || (parts.length === packageParts + 1 && VERSION_DIR_RE.test(parts.at(-1)));
  }
  if (["pypi", "npm", "cargo", "go", "pub", "composer"].includes(format)) {
    return hasDirectoryUsage(entry);
  }
  if (format === "maven2") return parts.length >= 3 && VERSION_DIR_RE.test(parts.at(-1));
  if (format === "nuget") return parts.length === 2 && VERSION_DIR_RE.test(parts.at(-1));
  if (format === "swift") return parts.length === 3 && VERSION_DIR_RE.test(parts.at(-1));
  if (format === "terraform") {
    return parts[0] === "v1" && VERSION_DIR_RE.test(parts.at(-1))
      && ((parts[1] === "modules" && parts.length === 6)
        || (parts[1] === "providers" && parts.length === 5));
  }
  if (format === "docker") {
    return Boolean(imageNameFromDockerPath(entry.path)) && parts.at(-2) === "manifests";
  }
  return false;
}

function bindDeleteControl(mount, entry) {
  const button = mount.querySelector("#browse-delete-button");
  if (!button || !entry) return;
  button.addEventListener("click", () => deleteBrowseEntry(entry, button));
}

async function deleteBrowseEntry(entry, button) {
  if (!entry || !entry.path || !state.repo) return;
  if (canDeleteBrowseDirectory(entry)) return previewDirectoryDeletion(entry, button);
  if (!entry.leaf && !canDeleteBrowseComponentDirectory(entry)) return;
  const repository = state.repo;
  const sourceRepository = entry.sourceRepository || repository;
  const path = entry.path;
  const description = currentRepository()?.format === "gitlfs"
    ? "Deleting this LFS object may break checkouts of Git commits that reference it."
    : currentRepository()?.format === "ohpm"
      ? "This unpublishes the selected package or version, including its HAR, paired HSP and distribution tags. Existing installations may no longer be reproducible."
    : entry.leaf
      ? "The selected file will be removed. Depending on the package format, associated files may also be deleted. Builds and clients that depend on them may fail."
      : "The selected component and its associated files will be removed. Builds and clients that depend on them may fail.";
  if (!await window.kkrepoConfirmDelete({
    title: entry.leaf ? "Delete file?" : "Delete component?",
    description,
    details: [{ label: "Repository", value: sourceRepository }, { label: "Path", value: path }]
  })) return;
  if (state.repo !== repository || state.path !== path || !button.isConnected) return;
  button.disabled = true;
  const originalText = button.textContent;
  button.textContent = "Deleting";
  const params = new URLSearchParams({ path });
  if (entry.sourceRepository) params.set("source", entry.sourceRepository);
  try {
    const res = await fetch(`/internal/browse/${encodeURIComponent(repository)}?${params.toString()}`, {
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
    await refreshTreeAfterDeletion(repository, entry);
  } catch (error) {
    button.disabled = false;
    button.textContent = originalText;
    window.alert(`Delete failed: ${error.message}`);
  }
}

async function previewDirectoryDeletion(entry, button) {
  if (!canDeleteBrowseDirectory(entry)) return;
  const repository = state.repo;
  const originalText = button.textContent;
  button.disabled = true;
  button.textContent = "Reviewing…";
  try {
    const params = new URLSearchParams({ path: entry.path });
    const response = await fetch(`/internal/browse/${encodeURIComponent(repository)}/directory-deletion?${params}`, {
      headers: { Accept: "application/json" }, cache: "no-store",
    });
    if (response.status === 401) {
      sessionStorage.removeItem(AUTH_SNAPSHOT_KEY);
      window.location.href = "/browse/#browse/welcome";
      return;
    }
    if (!response.ok) throw new Error(await deleteError(response));
    const preview = await response.json();
    if (state.repo !== repository || state.path !== entry.path) return;
    showDirectoryDeletionDialog(repository, entry, preview, button);
  } catch (error) {
    window.alert(`Cannot delete directory: ${error.message}`);
  } finally {
    button.disabled = false;
    button.textContent = originalText;
  }
}

function showDirectoryDeletionDialog(repository, entry, preview, trigger) {
  const dialog = document.createElement("dialog");
  dialog.className = "kk-confirm-dialog directory-delete-dialog";
  dialog.setAttribute("aria-labelledby", "directory-delete-title");
  dialog.setAttribute("aria-describedby", "directory-delete-warning");
  dialog.innerHTML = `
    <form>
      <div class="kk-confirm-heading">
        <span class="kk-confirm-icon lucide-icon icon-triangle-alert" aria-hidden="true"></span>
        <h2 id="directory-delete-title">Delete directory contents?</h2>
      </div>
      <dl class="kk-confirm-details">
        <dt>Repository</dt><dd><code data-i18n-skip>${escapeHtml(repository)}</code></dd>
        <dt>Directory</dt><dd><code data-i18n-skip>${escapeHtml(preview.path)}</code></dd>
        <dt>Contents</dt><dd><strong>${preview.assetCount}</strong> <span>${preview.assetCount === 1 ? "file" : "files"}</span> ·
          <strong>${preview.componentCount}</strong> <span>${preview.componentCount === 1 ? "component" : "components"}</span> · ${formatBytes(preview.estimatedBytes)}</dd>
      </dl>
      <p>The selected files will be deleted, including files in subdirectories.</p>
      <p id="directory-delete-warning" class="kk-confirm-warning">Deletion is permanent. This action cannot be undone.</p>
      ${preview.metadataAssetCount ? `<p><strong>${preview.metadataAssetCount}</strong> <span>generated metadata files may remain or be regenerated by the package protocol.</span></p>` : ""}
      <details><summary>Review all affected paths</summary>
        <ul class="directory-delete-paths">${preview.items.map((item) => `<li><code data-i18n-skip>${escapeHtml(item.path)}</code>${item.action === "PROTOCOL_METADATA" ? " <span>(protocol metadata)</span>" : ""}</li>`).join("")}</ul>
      </details>
      <label for="directory-delete-confirmation">Type the exact directory path to confirm:</label>
      <input id="directory-delete-confirmation" type="text" autocomplete="off" spellcheck="false" required>
      <p class="directory-delete-error" role="alert"></p>
      <div class="kk-confirm-actions directory-delete-actions">
        <button type="button" class="kk-confirm-cancel" data-delete-cancel autofocus>Cancel</button>
        <button type="button" class="kk-confirm-cancel" data-delete-refresh hidden>Review again</button>
        <button type="submit" class="kk-confirm-delete" data-delete-confirm disabled>Delete files</button>
      </div>
    </form>`;
  document.body.appendChild(dialog);
  const input = dialog.querySelector("input");
  const confirm = dialog.querySelector("[data-delete-confirm]");
  const cancel = dialog.querySelector("[data-delete-cancel]");
  const refresh = dialog.querySelector("[data-delete-refresh]");
  const error = dialog.querySelector(".directory-delete-error");
  let deleting = false;
  let invalidated = false;
  const navigate = () => { if (!deleting) dialog.close(); };
  window.addEventListener("hashchange", navigate);
  window.addEventListener("pagehide", navigate);
  input.addEventListener("input", () => {
    confirm.disabled = deleting || invalidated || input.value !== preview.path;
  });
  cancel.addEventListener("click", () => dialog.close());
  dialog.addEventListener("cancel", (event) => { if (deleting) event.preventDefault(); });
  dialog.addEventListener("close", () => {
    window.removeEventListener("hashchange", navigate);
    window.removeEventListener("pagehide", navigate);
    dialog.remove();
    if (trigger.isConnected) trigger.focus({ preventScroll: true });
  });
  refresh.addEventListener("click", () => {
    dialog.close();
    if (state.repo === repository && state.path === entry.path) previewDirectoryDeletion(entry, trigger);
  });
  dialog.querySelector("form").addEventListener("submit", async (event) => {
    event.preventDefault();
    if (deleting || invalidated || input.value !== preview.path) return;
    // Capture the repository and path from the reviewed preview, never from changing navigation.
    deleting = true;
    confirm.disabled = true;
    cancel.disabled = true;
    input.disabled = true;
    confirm.textContent = "Deleting…";
    error.textContent = "";
    try {
      const response = await fetch(`/internal/browse/${encodeURIComponent(repository)}/directory-deletion`, {
        method: "POST",
        headers: { Accept: "application/json", "Content-Type": "application/json" },
        body: JSON.stringify({ path: preview.path, confirmationPath: input.value, selectionToken: preview.selectionToken }),
      });
      if (response.status === 401) {
        sessionStorage.removeItem(AUTH_SNAPSHOT_KEY);
        dialog.close();
        window.location.href = "/browse/#browse/welcome";
        return;
      }
      if (!response.ok) throw new Error(await deleteError(response));
      dialog.close();
      try {
        await refreshTreeAfterDeletion(repository, entry);
      } catch (refreshFailure) {
        window.alert(`Directory deleted, but Browse could not refresh: ${refreshFailure.message}. Reload the page.`);
      }
    } catch (failure) {
      // An ambiguous network failure may follow a committed deletion. Always require a fresh
      // preview before retrying, including when the server rejects a changed selection (409).
      invalidated = true;
      error.textContent = failure.message;
      refresh.hidden = false;
    } finally {
      deleting = false;
      cancel.disabled = false;
      confirm.textContent = "Delete files";
    }
  });
  window.kkrepoI18n?.apply(dialog);
  dialog.showModal();
  cancel.focus();
}

async function refreshTreeAfterDeletion(repository, entry) {
  treeCache.clear();
  const deletedKey = `${repository}::${entry.path}`;
  for (const key of Array.from(expanded)) {
    if (key === deletedKey || key.startsWith(`${deletedKey}/`)) expanded.delete(key);
  }
  if (state.repo !== repository) return;
  const tree = document.querySelector(".tree-left");
  const root = document.getElementById("tree-root");
  if (!tree || !root) return;
  const position = { top: tree.scrollTop, left: tree.scrollLeft };
  const selectedPath = state.path;
  const oldMinHeight = root.style.minHeight;
  // Preserve the scroll extent while expanded branches are being reloaded asynchronously.
  root.style.minHeight = `${root.scrollHeight}px`;
  try {
    await loadAndRenderTreeLevel("", root, 0);
    await settleExpandedTreeLoads(root);
    if (state.repo !== repository || !root.isConnected) return;
    if (state.path === selectedPath) {
      let path = selectedPath;
      let node;
      while (path && !(node = root.querySelector(`.tree-node[data-path="${pathSelectorValue(path)}"]`))) {
        path = pathSegments(path).slice(0, -1).join("/");
      }
      if (node) {
        const selectedEntry = treeNodeEntries.get(node);
        selectRow(node.querySelector(":scope > .tree-row"));
        syncTreePath(selectedEntry);
        if (selectedEntry.leaf) await showAssetDetail(selectedEntry);
        else await showComponentDetail(selectedEntry);
      } else {
        state.path = "";
        window.history.replaceState(null, "", `/browse/${repositoryBrowseHash(repository, "", state.source)}`);
        detailPaneMount().innerHTML = '<div class="detail-placeholder">Select a component or file in the tree to view details.</div>';
      }
    }
  } finally {
    root.style.minHeight = oldMinHeight;
    if (state.repo === repository && root.isConnected) {
      tree.scrollTop = position.top;
      tree.scrollLeft = position.left;
    }
  }
}

async function settleExpandedTreeLoads(root) {
  const pending = Array.from(root.querySelectorAll(".tree-children"))
    .map((mount) => treeMountLoads.get(mount)).filter(Boolean);
  if (!pending.length) return;
  await Promise.all(pending);
  await settleExpandedTreeLoads(root);
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

export {
  bindDeleteControl,
  canDeleteBrowseComponentDirectory,
  canDeleteBrowseContent,
  canDeleteBrowseDirectory,
};
