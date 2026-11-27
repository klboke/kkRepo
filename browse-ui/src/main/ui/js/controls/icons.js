import { lucideIcon } from "#ui-shared/markup.js";
import { formatIconName } from "#ui-shared/format-icons.js";
import { escapeHtml } from "../details/format.js";
import { currentRepository } from "../repositories/list.js";

function repoIcon(type) {
  if (type === "group") return lucideIcon("boxes", "repo-icon group");
  if (type === "proxy") return lucideIcon("cloud-download", "repo-icon proxy");
  return lucideIcon("package", "repo-icon hosted");
}

function formatBadge(format) {
  const normalized = String(format || "").toLowerCase();
  const icon = formatIconName(normalized);
  return `<span class="format-cell"><span class="format-logo format-logo-${icon}" aria-hidden="true"></span><span class="format-cell-label">${escapeHtml(normalized)}</span></span>`;
}

const FILE_ICON_RULES = Object.freeze([
  Object.freeze({
    icon: "file-key",
    suffixes: Object.freeze([".tgz.prov", ".prov", ".asc", ".sig", ".key"]),
  }),
  Object.freeze({
    icon: "file-check",
    suffixes: Object.freeze([".sha1", ".sha256", ".sha512", ".md5"]),
  }),
  Object.freeze({
    icon: "file-json",
    suffixes: Object.freeze([".json", ".module", ".info"]),
  }),
  Object.freeze({
    icon: "file-code",
    suffixes: Object.freeze([".pom", ".xml", ".nuspec"]),
  }),
  Object.freeze({
    icon: "file-text",
    suffixes: Object.freeze([".mod", ".sum", ".list", ".yaml", ".yml"]),
  }),
  Object.freeze({
    icon: "layers",
    suffixes: Object.freeze([".safetensors"]),
  }),
  Object.freeze({
    icon: "binary",
    suffixes: Object.freeze([".bin"]),
  }),
  Object.freeze({
    icon: "file-box",
    suffixes: Object.freeze([".whl", ".egg", ".nupkg", ".snupkg", ".crate", ".gem", ".rpm"]),
  }),
  Object.freeze({
    icon: "file-java-archive",
    suffixes: Object.freeze([".jar", ".war", ".ear", ".aar"]),
  }),
  Object.freeze({
    icon: "file-archive",
    suffixes: Object.freeze([
      ".zip", ".tar.bz2", ".tbz", ".tar.gz", ".tgz",
      ".tlz", ".tar.lz", ".tar.lzma", ".tar.xz", ".txz", ".tar.z", ".tz", ".taz",
      ".tar", ".gemspec.rz", ".rz", ".xml.gz", ".sqlite.bz2", ".gz",
    ]),
  }),
]);

function fileIconName(name, repositoryFormat = "") {
  const normalized = String(name || "").toLowerCase();
  const format = String(repositoryFormat || "").toLowerCase();
  if (format === "alpine" && normalized.endsWith(".apk")) return "file-apk";
  if (format === "r" && normalized.endsWith(".tar.gz")) return "file-archive";
  if (format === "cargo" && normalized && !normalized.includes(".")) return "file-json";
  if (format === "go" && normalized === "list") return "file-text";
  if (format === "go" && normalized === "@latest") return "file-json";
  if (format === "rubygems" && (normalized === "versions" || normalized === "names")) {
    return "file-text";
  }
  if (format === "docker"
      && (/^[a-f0-9]{64}$/.test(normalized) || /^sha256:[a-f0-9]{64}$/.test(normalized))) {
    return "binary";
  }
  const rule = FILE_ICON_RULES.find(({ suffixes }) =>
    suffixes.some((suffix) => normalized.endsWith(suffix)));
  return rule?.icon || "file";
}

const ICON_FOLDER = lucideIcon("folder", "tree-icon");

const ICON_ARCHIVE = lucideIcon("archive", "tree-icon");

const VERSION_DIR_RE = /^\d/;

function iconForDir(name) {
  // Nexus uses an orange archive box for version directories (1.6, 1.7.0, 1.0-SNAPSHOT…),
  // yellow folder for everything else.
  return VERSION_DIR_RE.test(name) ? ICON_ARCHIVE : ICON_FOLDER;
}

function iconForFile(name, className = "tree-icon") {
  return lucideIcon(fileIconName(name, currentRepository()?.format), className);
}

export {
  VERSION_DIR_RE,
  formatBadge,
  formatIconName,
  iconForDir,
  iconForFile,
  lucideIcon,
  repoIcon,
};
