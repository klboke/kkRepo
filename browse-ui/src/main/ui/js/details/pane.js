import { copyWithFeedback } from "../controls/clipboard.js";
import { iconForFile, lucideIcon } from "../controls/icons.js";
import { escapeHtml, formatBytesDetail, formatInstant } from "./format.js";
import { usageDetailForEntry } from "./usage.js";
import {
  dockerUsageDetail,
  isDockerDetail,
  renderDockerMetadataPanel,
  renderDockerSummaryPanel,
} from "../formats/docker.js";
import { currentRepository } from "../repositories/list.js";
import { AUTH_SNAPSHOT_KEY } from "../shell/session.js";
import { repositoryFormatIcon, state } from "../tree/browser.js";
import {
  bindDeleteControl,
  canDeleteBrowseComponentDirectory,
  canDeleteBrowseContent,
  canDeleteBrowseDirectory,
} from "../tree/delete.js";

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
    renderAttributeGroup("OHPM", detail.ohpm),
    renderAttributeGroup("Pub", detail.pub),
    renderAttributeGroup("Composer", detail.composer),
    renderAttributeGroup("Conda", detail.conda),
    renderAttributeGroup("Alpine", detail.alpine),
    renderAttributeGroup("R / CRAN", detail.r),
    renderAttributeGroup("Hugging Face", detail.huggingface),
    renderAttributeGroup("Swift", detail.swift),
    renderAttributeGroup("Ansible Galaxy", detail.ansible),
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
            <select class="usage-pick" id="usage-pick" aria-label="Usage">
              ${snippets.map((snippet, index) =>
                `<option value="${index}">${escapeHtml(snippet.displayName)}</option>`).join("")}
            </select>
            <button type="button" class="usage-copy" id="usage-copy" title="Copy" aria-label="Copy usage snippet">${lucideIcon("copy")}</button>
          </div>
          <p class="usage-hint" id="usage-hint">${escapeHtml(first.description || "")}</p>
          <pre class="snippet"><code id="usage-snippet">${colorizeSnippet(first.snippetText)}</code></pre>
          <p class="copy-feedback" role="status"></p>
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
  copy.addEventListener("click", () => copyWithFeedback(copy, selected().snippetText, "Copy usage snippet",
    "Usage snippet copied", "Copy failed. Select the command and copy it manually.", mount.querySelector(".copy-feedback")));
}

function renderDetailPane({ crumbIcon, crumbText, summaryRows, snippets, deleteEntry }) {
  const mount = detailPaneMount();
  if (!mount) return;
  mount.innerHTML = `
    <div class="detail-pane">
      ${detailHead(crumbIcon, crumbText, deleteEntry)}
      <button type="button" class="analyze-btn">${lucideIcon("settings")} Analyze application</button>
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

async function showComponentDetail(entry) {
  const mount = detailPaneMount();
  if (!mount) return;
  const repository = state.repo;
  const detail = await usageDetailForEntry(entry);
  if (state.repo !== repository || state.path !== entry.path || !mount.isConnected) return;
  renderDetailPane({
    crumbIcon: repositoryFormatIcon(),
    crumbText: detail?.crumbText || entry.path,
    summaryRows: detail?.summaryRows || [
      ["Repository", repository],
      ["Directory", entry.path],
    ],
    snippets: detail?.snippets || [],
    // Preserve existing protocol component actions; only supported hosted formats gain a
    // delete action for arbitrary directories without component coordinates.
    deleteEntry: canDeleteBrowseDirectory(entry) || canDeleteBrowseComponentDirectory(entry) ? entry : null,
  });
}

async function showAssetDetail(entry) {
  const mount = detailPaneMount();
  if (!mount) return;
  const detail = await fetchAssetAttributes(entry);
  const browseOnly = !entry.downloadUrl;
  const deleteEntry = browseOnly || entry.readOnly ? null : entry;
  const usage = browseOnly || entry.readOnly ? null : await usageDetailForEntry(entry, detail);
  const dockerUsage = currentRepository()?.format === "docker" ? dockerUsageDetail(entry, detail) : null;
  const uploader = detail?.uploader || "-";
  const uploaderIp = detail?.uploaderIp || "";
  const sourceRepository = detail?.sourceRepository || entry.sourceRepository || "";
  const dockerDetail = isDockerDetail(detail);
  const downloadUrl = dockerDetail ? (entry.downloadUrl || detail?.downloadUrl) : (detail?.downloadUrl || entry.downloadUrl);
  const download = renderAssetDownloadLink(downloadUrl, entry.readOnly);
  mount.innerHTML = `
    <div class="detail-pane">
      ${detailHead(iconForFile(entry.name || entry.path, "crumb-icon"), entry.path, deleteEntry)}
      ${dockerDetail
        ? renderDockerSummaryPanel(entry, detail, sourceRepository)
        : renderGenericAssetSummaryPanel(entry, detail, sourceRepository, uploader, uploaderIp)}
      ${dockerDetail ? renderDockerMetadataPanel(detail) : ""}
      ${renderUsageSection((dockerUsage || usage) ? (dockerUsage || usage).snippets : [])}
      ${renderAttributesSection(detail, { hideDocker: dockerDetail })}
      ${download}
    </div>`;
  if (dockerUsage || usage) bindUsageControls(mount, (dockerUsage || usage).snippets);
  bindDeleteControl(mount, deleteEntry);
  const copy = mount.querySelector(".download-copy");
  if (copy) copy.addEventListener("click", () => copyWithFeedback(copy,
    mount.querySelector(".download-row a").href, "Copy URL", "Copied",
    "Copy failed", mount.querySelector(".download-feedback")));
}

function renderAssetDownloadLink(downloadUrl, copyable = false) {
  const target = typeof downloadUrl === "string" ? downloadUrl : "";
  if (!target) return "";
  return `<div class="download-row">
      <a class="copy-button" href="${escapeHtml(target)}" target="_blank" rel="noopener noreferrer">${lucideIcon("download")} Download</a>
      ${copyable ? `<button type="button" class="usage-copy download-copy" aria-label="Copy URL" title="Copy URL">${lucideIcon("copy")}</button>
      <span class="download-feedback" role="status"></span>` : ""}
    </div>`;
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

export {
  codeValue,
  detailPaneMount,
  kv,
  maybeKv,
  parseGav,
  shortDigest,
  showAssetDetail,
  showComponentDetail,
  usageSnippet,
};
