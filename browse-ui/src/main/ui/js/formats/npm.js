import { latestVersion, pathSegments } from "../details/format.js";
import { usageSnippet } from "../details/pane.js";
import { fetchChildren, state } from "../tree/browser.js";

function npmPackageName(path) {
  const clean = (path || "").split("/-/")[0];
  const parts = pathSegments(clean);
  if (!parts.length) return "";
  if (parts[0].startsWith("@")) {
    return parts.length >= 2 ? `${parts[0]}/${parts[1]}` : "";
  }
  return parts[0];
}

function npmVersionFromTarball(path, packageName) {
  const filename = pathSegments(path).at(-1) || "";
  if (!filename.endsWith(".tgz")) return "";
  const base = filename.slice(0, -4);
  const packageBase = pathSegments(packageName).at(-1) || packageName;
  const prefix = `${packageBase}-`;
  return base.startsWith(prefix) ? base.slice(prefix.length) : "";
}

async function npmUsageDetail(entry) {
  const name = npmPackageName(entry.path);
  if (!name) return null;
  let version = npmVersionFromTarball(entry.path, name);
  if (!version && !entry.leaf) {
    const children = await fetchChildren(state.repo, name);
    version = latestVersion(children
      .filter((child) => child.leaf)
      .map((child) => npmVersionFromTarball(child.path, name)));
  }
  const installTarget = version ? `${name}@${version}` : name;
  return {
    crumbText: entry.path,
    summaryRows: [
      ["Repository", state.repo],
      ["Format", "npm"],
      ["Name", name],
      ["Version", version || "latest"],
      ["Most popular version", "", { locked: true }],
      ["Age", "", { locked: true }],
      ["Popularity", "", { locked: true }],
    ],
    snippets: [
      usageSnippet("npm", `npm install ${installTarget}`, "Install runtime dependency"),
      usageSnippet("Yarn", `yarn add ${installTarget}`, "Install runtime dependency"),
      usageSnippet("package.json", `"${name}": "${version || "latest"}"`,
        "Install runtime dependency to the package.json dependencies section"),
    ],
  };
}

export {
  npmPackageName,
  npmUsageDetail,
};
