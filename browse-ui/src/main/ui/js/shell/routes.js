import { showMyToken } from "../account/tokens.js";
import { repositoryExists, showRepositoryList } from "../repositories/list.js";
import { clearSearchFormatSelection } from "../search/formats.js";
import { showSearch } from "../search/results.js";
import { initialDataLoaded } from "./start.js";
import { showRepositoryTree } from "../tree/browser.js";
import { showUpload } from "../upload/catalog.js";

const APP_HASH_PREFIX = "browse";

const BROWSE_HASH = `${APP_HASH_PREFIX}/browse`;

const DEFAULT_SEARCH_FORMAT = "maven2";

const ALL_SEARCH_FORMAT = "all";

const CUSTOM_SEARCH_FORMAT = "custom";

const COMPONENT_SEARCH_LIMIT = 20;

const SEARCH_ROUTE_FORMAT = {
  all: "all",
  custom: "custom",
  cargo: "cargo",
  go: "go",
  helm: "helm",
  maven2: "maven2",
  nuget: "nuget",
  pub: "pub",
  composer: "composer",
  terraform: "terraform",
  swift: "swift",
  ansiblegalaxy: "ansiblegalaxy",
  conda: "conda",
  conan: "conan",
  apt: "apt",
  alpine: "alpine",
  r: "r",
  huggingface: "huggingface",
  gitlfs: "gitlfs",
  pypi: "pypi",
  rubygems: "rubygems",
  yum: "yum",
  npm: "npm",
  ohpm: "ohpm",
  docker: "docker",
  raw: "raw",
};

const FORMAT_ROUTE_SEGMENT = {
  all: "all",
  custom: "custom",
  cargo: "cargo",
  go: "go",
  helm: "helm",
  maven2: "maven2",
  nuget: "nuget",
  pub: "pub",
  composer: "composer",
  terraform: "terraform",
  swift: "swift",
  ansiblegalaxy: "ansiblegalaxy",
  conda: "conda",
  conan: "conan",
  apt: "apt",
  alpine: "alpine",
  r: "r",
  huggingface: "huggingface",
  gitlfs: "gitlfs",
  pypi: "pypi",
  rubygems: "rubygems",
  yum: "yum",
  npm: "npm",
  ohpm: "ohpm",
  docker: "docker",
  raw: "raw",
};

const SEARCH_FORMAT_LABEL = {
  all: "All components",
  custom: "Custom search",
  cargo: "Cargo",
  go: "Go",
  helm: "Helm",
  maven2: "Maven",
  nuget: "NuGet",
  pub: "Pub",
  composer: "Composer",
  terraform: "Terraform",
  swift: "Swift",
  ansiblegalaxy: "Ansible Galaxy",
  conda: "Conda",
  conan: "Conan 2",
  apt: "APT / Debian",
  alpine: "APK / Alpine",
  r: "R / CRAN",
  huggingface: "Hugging Face Models",
  gitlfs: "Git LFS",
  pypi: "PyPI",
  rubygems: "RubyGems",
  yum: "Yum",
  npm: "npm",
  ohpm: "OHPM / HarmonyOS",
  docker: "Docker / OCI",
  raw: "Raw",
};

function browseListHash() {
  return `#${BROWSE_HASH}`;
}

function viewHash(view) {
  return `#${APP_HASH_PREFIX}/${view}`;
}

function normalizeSearchFormat(format) {
  return SEARCH_ROUTE_FORMAT[format] || DEFAULT_SEARCH_FORMAT;
}

function normalizeCustomSearchFormat(format) {
  const normalized = SEARCH_ROUTE_FORMAT[format];
  if (!normalized || normalized === ALL_SEARCH_FORMAT || normalized === CUSTOM_SEARCH_FORMAT) {
    return DEFAULT_SEARCH_FORMAT;
  }
  return normalized;
}

function searchFormatLabel(format) {
  return SEARCH_FORMAT_LABEL[normalizeSearchFormat(format)];
}

function searchPageTitle(format) {
  const normalized = normalizeSearchFormat(format);
  if (normalized === ALL_SEARCH_FORMAT) return "Search all components";
  if (normalized === CUSTOM_SEARCH_FORMAT) return "Custom search";
  return `Search ${searchFormatLabel(normalized)}`;
}

function searchHash(format, keyword = "", customFormat = DEFAULT_SEARCH_FORMAT) {
  const normalized = normalizeSearchFormat(format);
  const base = `#${APP_HASH_PREFIX}/search/${FORMAT_ROUTE_SEGMENT[normalized]}`;
  const params = new URLSearchParams();
  if (normalized === CUSTOM_SEARCH_FORMAT) {
    params.set("format", normalizeCustomSearchFormat(customFormat));
  }
  const q = String(keyword || "").trim();
  if (q) params.set("q", q);
  const query = params.toString();
  return query ? `${base}?${query}` : base;
}

function repositoryBrowseHash(repoName, path = "", sourceRepository = "") {
  const hash = `#${BROWSE_HASH}:${encodeURIComponent(repoName)}`;
  const params = new URLSearchParams();
  if (path) params.set("path", path);
  if (sourceRepository && sourceRepository !== repoName) {
    params.set("source", sourceRepository);
  }
  const query = params.toString();
  return query ? `${hash}?${query}` : hash;
}

function componentBrowsePath(component) {
  const storedPath = (component.browsePath || "").trim();
  if (storedPath) return storedPath;
  const format = (component.format || "").toLowerCase();
  const group = (component.group || "").trim();
  const name = (component.name || "").trim();
  const version = (component.version || "").trim();
  if (!name) return "";
  if (format === "maven2") {
    return [group ? group.replaceAll(".", "/") : "", name, version].filter(Boolean).join("/");
  }
  if (format === "pypi") {
    return [name, version].filter(Boolean).join("/");
  }
  if (format === "npm") {
    return group ? `@${group}/${name}` : name;
  }
  if (format === "cargo") {
    return ["crates", name, version].filter(Boolean).join("/");
  }
  if (format === "go") {
    return name;
  }
  if (format === "pub") {
    return version ? `packages/${name}/versions/${version}.tar.gz` : `packages/${name}`;
  }
  if (format === "composer" && component.kind !== "composer-package") {
    return name;
  }
  if (format === "terraform") return name;
  if (format === "swift") {
    return [group, name, version].filter(Boolean).join("/");
  }
  if (format === "ansiblegalaxy") {
    return [group, name, version].filter(Boolean).join("/");
  }
  if (format === "conda") {
    return [group, name, version].filter(Boolean).join("/");
  }
  if (format === "conan") {
    const user = group.split("/", 1)[0] || "_";
    return [user, name, version].filter(Boolean).join("/");
  }
  if (format === "apt") {
    return [group, name, version].filter(Boolean).join("/");
  }
  if (format === "alpine") {
    return [group, name, version].filter(Boolean).join("/");
  }
  if (format === "huggingface") {
    return [group, name, version].filter(Boolean).join("/");
  }
  return "";
}

function parseBrowseHash() {
  const hash = window.location.hash.replace(/^#/, "");
  if (!hash) return null;
  if (hash === APP_HASH_PREFIX || hash === BROWSE_HASH) return { view: "browse", repo: null };
  if (hash === `${APP_HASH_PREFIX}/welcome`) return { view: "welcome" };
  if (hash === `${APP_HASH_PREFIX}/upload`) return { view: "upload" };
  if (hash === `${APP_HASH_PREFIX}/my-token`) return { view: "my-token" };
  if (hash === `${APP_HASH_PREFIX}/search`) {
    return { view: "search", searchFormat: DEFAULT_SEARCH_FORMAT, keyword: "" };
  }
  if (hash.startsWith(`${APP_HASH_PREFIX}/search/`)) {
    const routeValue = hash.slice(`${APP_HASH_PREFIX}/search/`.length);
    const separator = routeValue.indexOf("?");
    const rawFormat = separator === -1 ? routeValue : routeValue.slice(0, separator);
    const query = separator === -1 ? "" : routeValue.slice(separator + 1);
    const params = new URLSearchParams(query);
    let searchFormat = normalizeSearchFormat(rawFormat);
    // Before Custom search gained a format selector, this route represented all formats.
    if (searchFormat === CUSTOM_SEARCH_FORMAT && !params.has("format")) {
      searchFormat = ALL_SEARCH_FORMAT;
    }
    return {
      view: "search",
      searchFormat,
      customSearchFormat: searchFormat === CUSTOM_SEARCH_FORMAT
        ? normalizeCustomSearchFormat(params.get("format"))
        : undefined,
      keyword: params.get("q") || "",
    };
  }
  const prefix = `${BROWSE_HASH}:`;
  if (!hash.startsWith(prefix)) return null;
  const routeValue = hash.slice(prefix.length);
  const separator = routeValue.indexOf("?");
  const encodedRepo = separator === -1 ? routeValue : routeValue.slice(0, separator);
  const query = separator === -1 ? "" : routeValue.slice(separator + 1);
  if (!encodedRepo) return { view: "browse", repo: null };
  const params = new URLSearchParams(query);
  const path = params.get("path") || "";
  const source = params.get("source") || "";
  try {
    return { view: "browse", repo: decodeURIComponent(encodedRepo), path, source };
  } catch {
    return { view: "browse", repo: encodedRepo, path, source };
  }
}

function pushBrowseRoute(hash) {
  const target = `/browse/${hash}`;
  const current = `${window.location.pathname}${window.location.search}${window.location.hash}`;
  if (current !== target) {
    window.history.pushState(null, "", target);
  }
}

function canonicalizeBrowseRoute() {
  if (!window.location.hash.startsWith(`#${APP_HASH_PREFIX}`)) return;
  window.history.replaceState(null, "", `/browse/${window.location.hash}`);
}

function showWelcome(syncHash = true) {
  if (syncHash) pushBrowseRoute(viewHash("welcome"));
  switchView("welcome");
}

function switchView(view) {
  document.querySelectorAll(".view").forEach((item) => {
    item.classList.toggle("is-active", item.id === `${view}-view`);
  });
  document.querySelectorAll(".side-item").forEach((item) => {
    item.classList.toggle("is-active", view !== "search" && item.dataset.view === view);
  });
  if (view !== "search") clearSearchFormatSelection();
  // when leaving Browse, restore the original tools/frame
  if (view !== "browse") {
    const drill = document.getElementById("browse-drill");
    if (drill) drill.remove();
    const tools = document.querySelector("#browse-view .table-tools");
    if (tools) tools.style.display = "";
    const frame = document.getElementById("repository-table").parentElement.parentElement;
    frame.style.display = "";
  }
}

function applyHashRoute() {
  const route = parseBrowseHash();
  // Resolve data-dependent routes only after session and repository discovery. In particular,
  // an empty startup cache must not redirect signed-in users or discard repository deep links.
  if (!initialDataLoaded) {
    switchView(!route || route.view === "welcome" ? "welcome" : "loading");
    return Boolean(route);
  }
  if (!route) return false;
  if (route.view === "welcome") {
    showWelcome(false);
    canonicalizeBrowseRoute();
    return true;
  }
  if (route.view === "upload") {
    showUpload(false);
    canonicalizeBrowseRoute();
    return true;
  }
  if (route.view === "my-token") {
    showMyToken(false);
    canonicalizeBrowseRoute();
    return true;
  }
  if (route.view === "search") {
    showSearch(route.searchFormat, false, route.keyword, route.customSearchFormat);
    return true;
  }
  switchView("browse");
  if (route.repo && repositoryExists(route.repo)) {
    showRepositoryTree(route.repo, false, route.path || "", route.source || "");
  }
  else showRepositoryList(false);
  canonicalizeBrowseRoute();
  return true;
}

export {
  ALL_SEARCH_FORMAT,
  APP_HASH_PREFIX,
  COMPONENT_SEARCH_LIMIT,
  CUSTOM_SEARCH_FORMAT,
  DEFAULT_SEARCH_FORMAT,
  applyHashRoute,
  browseListHash,
  componentBrowsePath,
  normalizeCustomSearchFormat,
  normalizeSearchFormat,
  pushBrowseRoute,
  repositoryBrowseHash,
  searchFormatLabel,
  searchHash,
  searchPageTitle,
  showWelcome,
  switchView,
  viewHash,
};
