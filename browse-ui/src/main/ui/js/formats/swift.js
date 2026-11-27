import { pathSegments } from "../details/format.js";
import { usageSnippet } from "../details/pane.js";
import { currentRepository, repositoryBaseUrl } from "../repositories/list.js";
import { state } from "../tree/browser.js";

function swiftUsageDetail(entry) {
  const parts = pathSegments(entry.path);
  const scope = parts[0] || "";
  const name = parts[1] || "";
  const rawVersion = parts[2] || "";
  const archiveAsset = entry.leaf === true && parts.length === 3;
  const version = archiveAsset && rawVersion.endsWith(".zip")
    ? rawVersion.slice(0, -4)
    : rawVersion;
  const identity = scope && name ? `${scope}.${name}` : "";
  const repoUrl = repositoryBaseUrl();
  const summaryRows = [
    ["Repository", state.repo],
    ["Format", "swift"],
    ["Scope", scope || "-"],
    ["Name", name || "-"],
    ["Version", version || "latest"],
    ["Registry URL", repoUrl],
  ];
  const snippets = [
    usageSnippet("Configure registry", `swift package-registry set "${repoUrl}"`),
    usageSnippet("Login", `swift package-registry login "${repoUrl}" --username <username>`),
    usageSnippet(
      "registries.json",
      JSON.stringify({
        registries: {
          "[default]": {
            url: repoUrl,
            supportsAvailability: false,
          },
        },
        version: 1,
      }, null, 2),
      "Nexus-compatible Swift registry configuration",
    ),
  ];
  if (identity) {
    snippets.push(usageSnippet(
      "Package.swift",
      `.package(id: "${identity}", from: "${version || "1.0.0"}")`,
      "Declare a registry identity dependency",
    ));
  }
  if (currentRepository()?.type === "hosted" && identity && version) {
    snippets.push(usageSnippet(
      "Publish",
      `swift package-registry publish ${identity} ${version}`,
      "Publishes the source archive through the configured registry",
    ));
  }
  if (window.location.protocol !== "https:") {
    summaryRows.push(["Transport warning", "Use HTTPS in production for credentials and archive integrity"]);
  }
  return { crumbText: entry.path, summaryRows, snippets };
}

export {
  swiftUsageDetail,
};
