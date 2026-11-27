import { latestVersion, pathSegments } from "../details/format.js";
import { usageSnippet } from "../details/pane.js";
import { repositoryBaseUrl } from "../repositories/list.js";
import { fetchChildren, state } from "../tree/browser.js";

function goModulePath(path) {
  const marker = "/@v/";
  if ((path || "").includes(marker)) return path.slice(0, path.indexOf(marker));
  if ((path || "").endsWith("/@latest")) return path.slice(0, -"/@latest".length);
  const parts = pathSegments(path);
  if (parts.length < 2 || parts.includes("@v")) return "";
  return parts.join("/");
}

function goVersionFromPath(path) {
  const filename = pathSegments(path).at(-1) || "";
  const match = filename.match(/^(v[^/]+)\.(?:info|mod|zip)$/);
  return match ? match[1] : "";
}

async function latestGoVersion(modulePath) {
  try {
    const children = await fetchChildren(state.repo, `${modulePath}/@v`);
    return latestVersion(children.map((child) => goVersionFromPath(child.path)));
  } catch {
    return "";
  }
}

async function goUsageDetail(entry) {
  const modulePath = goModulePath(entry.path);
  if (!modulePath) return null;
  const version = goVersionFromPath(entry.path) || await latestGoVersion(modulePath);
  const repoUrl = repositoryBaseUrl();
  const target = `${modulePath}@${version || "latest"}`;
  const snippets = [
    usageSnippet("go get", `GOPROXY=${repoUrl} go get ${target}`),
    usageSnippet("go env", `go env -w GOPROXY=${repoUrl},direct`),
  ];
  if (version) {
    snippets.push(usageSnippet("go.mod", `require ${modulePath} ${version}`));
  }
  return {
    crumbText: entry.path,
    summaryRows: [
      ["Repository", state.repo],
      ["Format", "go"],
      ["Name", modulePath],
      ["Version", version || "latest"],
      ["Repository URL", repoUrl],
    ],
    snippets,
  };
}

export {
  goModulePath,
  goUsageDetail,
};
