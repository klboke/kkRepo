import { pathSegments } from "../details/format.js";
import { usageSnippet } from "../details/pane.js";
import { currentRepository, repositoryBaseUrl } from "../repositories/list.js";
import { state } from "../tree/browser.js";

function ansibleGalaxyUsageDetail(entry) {
  const parts = pathSegments(entry.path);
  const namespace = parts[0] || "";
  const name = parts[1] || "";
  const rawVersion = parts[2] || "";
  const version = rawVersion.endsWith(".tar.gz") ? rawVersion.slice(0, -7) : rawVersion;
  const collection = namespace && name ? `${namespace}.${name}` : "namespace.collection";
  const repoUrl = repositoryBaseUrl();
  const serverName = String(state.repo || "kkrepo").replace(/[^A-Za-z0-9_]/g, "_");
  const summaryRows = [
    ["Repository", state.repo],
    ["Format", "ansiblegalaxy"],
    ["Collection", namespace && name ? collection : "-"],
    ["Version", version || "latest"],
    ["Galaxy server URL", repoUrl],
  ];
  const snippets = [
    usageSnippet(
      "ansible.cfg",
      `[galaxy]\nserver_list = ${serverName}\n\n[galaxy_server.${serverName}]\nurl = ${repoUrl}\ntoken = GenericToken.<token>`,
      "Use a GenericToken/API key in preference to a reusable password",
    ),
    usageSnippet(
      "requirements.yml",
      `collections:\n  - name: ${collection}\n    version: "${version || ">=1.0.0"}"\n    source: ${repoUrl}`,
    ),
    usageSnippet(
      "Install",
      `ansible-galaxy collection install ${collection}${version ? `:${version}` : ""} --server ${repoUrl}`,
    ),
  ];
  if (currentRepository()?.type === "hosted") {
    snippets.push(usageSnippet(
      "Publish",
      `ansible-galaxy collection publish ${namespace || "namespace"}-${name || "collection"}-${version || "1.0.0"}.tar.gz --server ${repoUrl}`,
      "The archive filename and MANIFEST.json identity must match",
    ));
  }
  if (window.location.protocol !== "https:") {
    summaryRows.push(["Transport warning", "Use HTTPS in production for Galaxy credentials"]);
  }
  return { crumbText: entry.path, summaryRows, snippets };
}

export {
  ansibleGalaxyUsageDetail,
};
