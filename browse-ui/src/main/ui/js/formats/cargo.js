import { latestVersion, pathSegments } from "../details/format.js";
import { usageSnippet } from "../details/pane.js";
import { repositoryBaseUrl } from "../repositories/list.js";
import { fetchChildren, state } from "../tree/browser.js";

function cargoCoordinates(entry) {
  const parts = pathSegments(entry.path);
  if (!parts.length || parts[0] === "config.json") {
    return { name: "", version: "" };
  }
  if (parts[0] === "crates" && parts.length >= 2) {
    const name = parts[1] || "";
    let version = parts[2] || "";
    const file = parts.at(-1) || "";
    if (!version && file.endsWith(".crate")) {
      version = cargoVersionFromCrateFile(file, name);
    }
    return { name, version };
  }
  if (parts[0] === "api" && parts[1] === "v1" && parts[2] === "crates") {
    return { name: parts[3] || "", version: parts[4] || "" };
  }
  const leaf = parts.at(-1) || "";
  return parts.join("/") === cargoIndexPathForCrate(leaf)
    ? { name: leaf, version: "" }
    : { name: "", version: "" };
}

function cargoVersionFromCrateFile(filename, crateName) {
  if (!filename || !filename.endsWith(".crate")) return "";
  const base = filename.slice(0, -".crate".length);
  const prefix = `${crateName}-`;
  return base.startsWith(prefix) ? base.slice(prefix.length) : "";
}

function cargoIndexPathForCrate(crateName) {
  const name = String(crateName || "").toLowerCase();
  if (!/^[a-z0-9_-]+$/.test(name)) return "";
  if (name.length === 1) return `1/${name}`;
  if (name.length === 2) return `2/${name}`;
  if (name.length === 3) return `3/${name[0]}/${name}`;
  return `${name.slice(0, 2)}/${name.slice(2, 4)}/${name}`;
}

function cargoSourceName(repoName) {
  return (repoName || "kkrepo")
    .replace(/[^A-Za-z0-9_-]+/g, "-")
    .replace(/^-+|-+$/g, "") || "kkrepo";
}

function tomlString(value) {
  return `"${String(value || "").replaceAll("\\", "\\\\").replaceAll('"', '\\"')}"`;
}

function cargoSourceTable(sourceName) {
  return /^[A-Za-z0-9_-]+$/.test(sourceName) ? sourceName : tomlString(sourceName);
}

async function latestCargoVersion(cratePath) {
  try {
    const children = await fetchChildren(state.repo, cratePath);
    return latestVersion(children
      .filter((child) => !child.leaf)
      .map((child) => child.name));
  } catch {
    return "";
  }
}

async function cargoUsageDetail(entry) {
  const coordinates = cargoCoordinates(entry);
  if (!coordinates.name) return null;
  const name = coordinates.name;
  const cratePath = `crates/${name}`;
  const version = coordinates.version || (!entry.leaf && entry.path === cratePath
    ? await latestCargoVersion(cratePath)
    : "");
  const sourceName = cargoSourceName(state.repo);
  const sourceTable = cargoSourceTable(sourceName);
  const repoUrl = repositoryBaseUrl();
  const registryUrl = `sparse+${repoUrl}`;
  const addTarget = version ? `${name}@${version}` : name;
  const dependency = version ? `${name} = "${version}"` : `${name} = "*"`;
  const alternateDependency = version
    ? `${name} = { version = "${version}", registry = "${sourceName}" }`
    : `${name} = { version = "*", registry = "${sourceName}" }`;
  return {
    crumbText: entry.path,
    summaryRows: [
      ["Repository", state.repo],
      ["Format", "cargo"],
      ["Name", name],
      ["Version", version || "latest"],
      ["Registry URL", repoUrl],
    ],
    snippets: [
      usageSnippet(".cargo/config.toml", `[source.crates-io]
replace-with = "${sourceName}"

[source.${sourceTable}]
registry = "${registryUrl}"`, "Route crates.io dependencies through this repository"),
      usageSnippet("cargo add", `cargo add ${addTarget}`),
      usageSnippet("Cargo.toml", dependency),
      usageSnippet("Named registry config", `[registries.${sourceTable}]
index = "${registryUrl}"`, "Add to .cargo/config.toml when using this repository as an explicit registry"),
      usageSnippet("Cargo.toml registry", alternateDependency),
    ],
  };
}

export {
  cargoCoordinates,
  cargoUsageDetail,
};
