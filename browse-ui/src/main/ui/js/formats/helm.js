import { usageSnippet } from "../details/pane.js";
import { repositoryBaseUrl } from "../repositories/list.js";
import { state } from "../tree/browser.js";

function helmChartFromAsset(name) {
  if (!/\.t(?:ar\.)?gz$/i.test(name || "")) return null;
  const base = name.replace(/\.t(?:ar\.)?gz$/i, "");
  const match = base.match(/^(.+)-((?:v)?\d.*)$/);
  if (!match) return null;
  return { name: match[1], version: match[2] };
}

function releaseName(chartName) {
  return (chartName || "release").toLowerCase().replace(/[^a-z0-9-]+/g, "-").replace(/^-|-$/g, "") || "release";
}

async function helmUsageDetail(entry) {
  const chart = helmChartFromAsset(entry.name);
  if (!chart) return null;
  const repoUrl = repositoryBaseUrl();
  return {
    crumbText: entry.path,
    summaryRows: [
      ["Repository", state.repo],
      ["Format", "helm"],
      ["Name", chart.name],
      ["Version", chart.version],
      ["Repository URL", repoUrl],
    ],
    snippets: [
      usageSnippet("helm repo add", `helm repo add ${state.repo} ${repoUrl}`),
      usageSnippet("helm install", `helm install ${releaseName(chart.name)} ${chart.name} --repo ${repoUrl} --version ${chart.version}`),
      usageSnippet("Chart.yaml", `dependencies:
  - name: ${chart.name}
    version: ${chart.version}
    repository: ${repoUrl}`),
    ],
  };
}

export {
  helmUsageDetail,
};
