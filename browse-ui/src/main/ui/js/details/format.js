import { escapeHtml } from "#ui-shared/markup.js";
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

function pathSegments(path) {
  return (path || "").split("/").filter(Boolean);
}

function latestVersion(versions) {
  return versions
    .filter(Boolean)
    .sort((a, b) => a.localeCompare(b, undefined, { numeric: true, sensitivity: "base" }))
    .at(-1) || "";
}

export {
  escapeHtml,
  formatBytes,
  formatBytesDetail,
  formatInstant,
  latestVersion,
  pathSegments,
};
