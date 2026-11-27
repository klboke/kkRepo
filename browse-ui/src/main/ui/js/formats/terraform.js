import { pathSegments } from "../details/format.js";
import { usageSnippet } from "../details/pane.js";
import { repositoryBaseUrl } from "../repositories/list.js";
import { state } from "../tree/browser.js";

function terraformUsageDetail(entry) {
  const parts = pathSegments(entry.path);
  if (parts[0] !== "v1") return null;
  const repoUrl = repositoryBaseUrl().replace(/\/+$/, "");
  const host = window.location.host;
  const section = parts[1] || "";
  const isModule = section === "modules";
  const isProvider = section === "providers";
  const summaryRows = [
    ["Repository", state.repo],
    ["Format", "terraform"],
    ["Path", entry.path],
    ["Scope", isModule ? "Terraform modules" : isProvider ? "Terraform providers" : "Terraform registry"],
  ];
  const snippets = [];

  if (isModule) {
    const [namespace, name, system, version] = parts.slice(2, 6);
    if (namespace) summaryRows.push(["Namespace", namespace]);
    if (name) summaryRows.push(["Name", name]);
    if (system) summaryRows.push(["System", system]);
    if (version) summaryRows.push(["Version", version]);
    snippets.push(usageSnippet(
      "Terraform CLI config",
      `host "${host}" {\n  services = {\n    "modules.v1" = "${repoUrl}/v1/modules/"\n  }\n}`,
    ));
    if (namespace && name && system) {
      snippets.push(usageSnippet(
        "module",
        `module "${name}" {\n  source  = "${host}/${namespace}/${name}/${system}"\n  version = "${version || "latest"}"\n}`,
      ));
    }
  } else if (isProvider) {
    const [namespace, type, version] = parts.slice(2, 5);
    if (namespace) summaryRows.push(["Namespace", namespace]);
    if (type) summaryRows.push(["Type", type]);
    if (version) summaryRows.push(["Version", version]);
    snippets.push(usageSnippet(
      "Terraform CLI config",
      `host "${host}" {\n  services = {\n    "providers.v1" = "${repoUrl}/v1/providers/"\n  }\n}`,
    ));
    if (namespace && type) {
      snippets.push(usageSnippet(
        "required_providers",
        `terraform {\n  required_providers {\n    ${type} = {\n      source  = "${host}/${namespace}/${type}"\n      version = "${version || "latest"}"\n    }\n  }\n}`,
      ));
    }
  } else if (section) {
    summaryRows.push(["Scope", `Terraform path: ${section}`]);
  } else {
    snippets.push(usageSnippet(
      "Terraform CLI config",
      `host "${host}" {\n  services = {\n    "modules.v1" = "${repoUrl}/v1/modules/"\n    "providers.v1" = "${repoUrl}/v1/providers/"\n  }\n}`,
    ));
  }

  return {
    crumbText: entry.path,
    summaryRows,
    snippets,
  };
}

export {
  terraformUsageDetail,
};
