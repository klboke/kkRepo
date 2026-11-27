import { pathSegments } from "../details/format.js";
import { usageSnippet } from "../details/pane.js";
import { currentRepository, repositoryBaseUrl } from "../repositories/list.js";
import { state } from "../tree/browser.js";

function conanUsageDetail(entry) {
  const parts = pathSegments(entry.path);
  const user = parts[0] || "_";
  const name = parts[1] || "";
  const version = parts[2] || "";
  const revisionSegment = parts[3] || "";
  const revisionSeparator = revisionSegment.lastIndexOf("#");
  const channel = revisionSeparator >= 0
    ? revisionSegment.slice(0, revisionSeparator)
    : revisionSegment;
  const rrev = revisionSeparator >= 0
    ? revisionSegment.slice(revisionSeparator + 1)
    : "";
  const packagesIndex = parts.indexOf("packages", 4);
  const packageId = packagesIndex >= 0 ? parts[packagesIndex + 1] || "" : "";
  const packageRevisionsIndex = packagesIndex >= 0
    ? parts.indexOf("revisions", packagesIndex + 2)
    : -1;
  const prev = packageRevisionsIndex >= 0 ? parts[packageRevisionsIndex + 1] || "" : "";
  const recipe = name && version
    ? `${name}/${version}${user !== "_" ? `@${user}${channel !== "_" ? `/${channel}` : ""}` : ""}`
    : "<name>/<version>";
  const recipeRevision = `${recipe}${rrev ? `#${rrev}` : ""}`;
  const packageReference = packageId
    ? `${recipeRevision}:${packageId}${prev ? `#${prev}` : ""}`
    : recipeRevision;
  const repositoryUrl = repositoryBaseUrl().replace(/\/+$/, "");
  const remoteName = String(state.repo || "kkrepo").replace(/[^A-Za-z0-9_-]/g, "-");
  const summaryRows = [
    ["Repository", state.repo],
    ["Format", "conan"],
    ["Recipe", recipe],
    ["RREV", rrev || "-"],
    ["Package ID", packageId || "-"],
    ["PREV", prev || "-"],
    ["Remote URL", repositoryUrl],
  ];
  const snippets = [
    usageSnippet(
      "Configure remote",
      `conan remote add ${remoteName} '${repositoryUrl}' --force`,
    ),
    usageSnippet(
      "Login",
      `conan remote login ${remoteName} '<username>' -p '<password-or-token>'`,
      "Use a scoped CI/API credential and do not place credentials in the repository URL",
    ),
    usageSnippet("List", `conan list '${packageReference}${packageId ? "" : ":*"}' -r=${remoteName}`),
    usageSnippet("Download", `conan download '${packageReference}${packageId ? "" : ":*"}' -r=${remoteName}`),
    usageSnippet("Install", `conan install --requires='${recipe}' -r=${remoteName}`),
  ];
  if (currentRepository()?.type === "hosted" && name && version) {
    snippets.push(usageSnippet(
      "Publish",
      `conan upload '${recipe}:*' -r=${remoteName} --confirm`,
      "The server commits each recipe or package revision only when its manifest arrives",
    ));
  }
  return { crumbText: entry.path, summaryRows, snippets };
}

export {
  conanUsageDetail,
};
