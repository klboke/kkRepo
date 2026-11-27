import { latestVersion, pathSegments } from "../details/format.js";
import { usageSnippet } from "../details/pane.js";
import { repositoryBaseUrl } from "../repositories/list.js";
import { fetchChildren, state } from "../tree/browser.js";

function pubVersionFromArchiveFile(filename, packageName) {
  if (!filename || !filename.endsWith(".tar.gz")) return "";
  const base = filename.slice(0, -".tar.gz".length);
  const prefix = `${packageName}-`;
  if (base.startsWith(prefix)) return base.slice(prefix.length);
  return base === packageName ? "" : base;
}

function pubCoordinates(entry) {
  const parts = pathSegments(entry.path);
  if (!parts.length) return { name: "", version: "" };
  if (parts[0] === "packages") {
    const name = parts[1] || "";
    if (parts[2] !== "versions") return { name, version: "" };
    const file = parts[3] || "";
    return { name, version: file.endsWith(".tar.gz") ? file.slice(0, -".tar.gz".length) : "" };
  }
  if (parts[0] === "api" && parts[1] === "archives") {
    const file = parts[2] || "";
    const base = file.endsWith(".tar.gz") ? file.slice(0, -".tar.gz".length) : "";
    const separator = base.indexOf("-");
    return separator > 0 ? { name: base.slice(0, separator), version: base.slice(separator + 1) } : { name: "", version: "" };
  }
  const name = parts[0] || "";
  if (!name || name === "api") return { name: "", version: "" };
  const version = parts[1] || "";
  const file = parts[2] || "";
  if (file === "version.json") return { name, version };
  if (file.endsWith(".tar.gz")) return { name, version: pubVersionFromArchiveFile(file, name) || version };
  return { name, version: parts.length >= 2 ? version : "" };
}

async function latestPubVersion(entry, packageName) {
  const parts = pathSegments(entry.path);
  const candidates = [];
  if (parts[0] === "packages" && packageName) {
    candidates.push(`packages/${packageName}/versions`);
  }
  if (parts.length === 1 && packageName) {
    candidates.push(packageName);
  }
  if (parts.length === 2 && parts[0] === packageName) {
    return parts[1] || "";
  }
  for (const path of candidates) {
    try {
      const children = await fetchChildren(state.repo, path);
      const versions = children.map((child) => {
        if (child.leaf) return pubCoordinates(child).version;
        return child.name;
      });
      const latest = latestVersion(versions);
      if (latest) return latest;
    } catch {
      // Leave version blank when browse children are unavailable.
    }
  }
  return "";
}

async function pubUsageDetail(entry) {
  const coordinates = pubCoordinates(entry);
  if (!coordinates.name) return null;
  const name = coordinates.name;
  const version = coordinates.version || (!entry.leaf ? await latestPubVersion(entry, name) : "");
  const repoUrl = repositoryBaseUrl();
  const dependencyVersion = version || "any";
  const addTarget = version ? `${name}:${version}` : name;
  return {
    crumbText: entry.path,
    summaryRows: [
      ["Repository", state.repo],
      ["Format", "pub"],
      ["Name", name],
      ["Version", version || "latest"],
      ["Repository URL", repoUrl],
    ],
    snippets: [
      usageSnippet("pubspec.yaml", `dependencies:
  ${name}:
    hosted:
      url: ${repoUrl}
      name: ${name}
    version: ${dependencyVersion}`, "Declare a dependency from this hosted Pub repository"),
      usageSnippet("dart pub add", `dart pub add ${addTarget} --hosted-url ${repoUrl}`),
      usageSnippet("PUB_HOSTED_URL", `PUB_HOSTED_URL=${repoUrl} dart pub get`),
      usageSnippet("publish_to", `publish_to: ${repoUrl}`),
    ],
  };
}

export {
  pubCoordinates,
  pubUsageDetail,
};
