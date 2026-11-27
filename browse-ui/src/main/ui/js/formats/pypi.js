import { latestVersion, pathSegments } from "../details/format.js";
import { usageSnippet } from "../details/pane.js";
import { fetchChildren, state } from "../tree/browser.js";

async function pypiUsageDetail(entry) {
  const parts = pathSegments(entry.path);
  if (!parts.length || parts[0] === "simple") return null;
  const name = parts[0];
  const children = !entry.leaf && parts.length === 1 ? await fetchChildren(state.repo, entry.path) : [];
  const version = parts[1] || latestVersion(children.filter((child) => !child.leaf).map((child) => child.name));
  const pin = version ? `${name}==${version}` : name;
  return {
    crumbText: entry.path,
    summaryRows: [
      ["Repository", state.repo],
      ["Format", "pypi"],
      ["Name", name],
      ["Version", version || "latest"],
      ["Most popular version", "", { locked: true }],
      ["Age", "", { locked: true }],
      ["Popularity", "", { locked: true }],
    ],
    snippets: [
      usageSnippet("pip", `pip install ${pin}`),
      usageSnippet("easy_install", `easy_install ${pin}`),
      usageSnippet("pipenv", `pipenv install ${pin}`),
      usageSnippet("requirements.txt", version ? `${name} == ${version}` : name),
    ],
  };
}

export {
  pypiUsageDetail,
};
