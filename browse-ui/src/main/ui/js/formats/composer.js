import { pathSegments } from "../details/format.js";
import { usageSnippet } from "../details/pane.js";
import { repositoryBaseUrl } from "../repositories/list.js";
import { state } from "../tree/browser.js";

function composerCoordinates(entry, detail = null) {
  const composer = detail?.composer || {};
  let name = composer.package_name || "";
  let version = composer.version || "";
  const parts = pathSegments(entry.path);
  if (!name) {
    if (parts[0] === "p2" && parts.length >= 3) {
      const file = parts.at(-1) || "";
      const packageLeaf = file.replace(/~dev\.json$|\.json$/, "");
      name = `${parts.slice(1, -1).join("/")}/${packageLeaf}`;
    } else if (parts.length >= 2 && !["p2", "providers", "packages", "_composer"].includes(parts[0])) {
      name = `${parts[0]}/${parts[1]}`;
      if (!version && parts.length >= 3) version = parts[2];
    }
  }
  return { name, version };
}

function composerRepositoryKey(repoName) {
  return String(repoName || "kkrepo").replace(/[^A-Za-z0-9_.-]+/g, "-") || "kkrepo";
}

function composerUsageDetail(entry, detail = null) {
  const coordinates = composerCoordinates(entry, detail);
  if (!coordinates.name) return null;
  const repoUrl = repositoryBaseUrl();
  const requirement = coordinates.version
    ? `${coordinates.name}:${coordinates.version}`
    : coordinates.name;
  const constraint = coordinates.version || "*";
  const repositoryKey = composerRepositoryKey(state.repo);
  return {
    crumbText: entry.path,
    summaryRows: [
      ["Repository", state.repo],
      ["Format", "composer"],
      ["Name", coordinates.name],
      ["Version", coordinates.version || "latest"],
      ["Repository URL", repoUrl],
    ],
    snippets: [
      usageSnippet("composer require", `composer require ${requirement}`, "Install this package"),
      usageSnippet("composer config", `composer config repositories.${repositoryKey} composer ${repoUrl}`,
        "Add this repository to the current project"),
      usageSnippet("composer.json", `"repositories": [
  {"type": "composer", "url": "${repoUrl}"}
],
"require": {
  "${coordinates.name}": "${constraint}"
}`, "Add the repository and dependency to composer.json"),
    ],
  };
}

export {
  composerUsageDetail,
};
