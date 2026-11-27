import { formatBytesDetail, pathSegments } from "../details/format.js";
import { usageSnippet } from "../details/pane.js";
import { currentRepository, repositoryBaseUrl } from "../repositories/list.js";
import { state } from "../tree/browser.js";

function condaUsageDetail(entry, detail = null) {
  const metadata = detail?.conda || {};
  const parts = pathSegments(entry.path);
  const logicalPackage = parts.length >= 5;
  const subdirIndex = logicalPackage ? parts.length - 5 : -1;
  const channel = metadata.channel ?? (subdirIndex > 0 ? parts.slice(0, subdirIndex).join("/") : "");
  const subdir = metadata.subdir || (subdirIndex >= 0 ? parts[subdirIndex] : "");
  const name = metadata.name || (logicalPackage ? parts[parts.length - 4] : "");
  const version = metadata.version || (logicalPackage ? parts[parts.length - 3] : "");
  const build = metadata.build || (logicalPackage ? parts[parts.length - 2] : "");
  const filename = metadata.filename || (entry.leaf ? parts[parts.length - 1] : "");
  const repositoryUrl = repositoryBaseUrl().replace(/\/+$/, "");
  const channelUrl = channel ? `${repositoryUrl}/${channel}` : repositoryUrl;
  const summaryRows = [
    ["Repository", state.repo],
    ["Format", "conda"],
    ["Channel", channel || "root"],
    ["Subdir", subdir || "-"],
    ["Package", name || "-"],
    ["Version", version || "-"],
    ["Build", build || "-"],
    ["Channel URL", channelUrl],
  ];
  const snippets = [
    usageSnippet(
      ".condarc",
      `channels:\n  - ${channelUrl}\nchannel_priority: strict`,
      "Use an API key or scoped token through .netrc when authentication is required",
    ),
    usageSnippet(
      "Search",
      `conda search --override-channels -c ${channelUrl} ${name || "<package>"}`,
    ),
    usageSnippet(
      "Install",
      `conda install --override-channels -c ${channelUrl} ${name || "<package>"}${version ? `=${version}` : ""}`,
    ),
  ];
  if (currentRepository()?.type === "hosted" && filename && subdir) {
    const packageUrl = `${channelUrl}/${subdir}/${filename}`;
    snippets.push(usageSnippet(
      "Publish",
      `curl --fail --user '<username>:<token>' --upload-file '${filename}' '${packageUrl}'`,
      "The archive identity and URL subdir are validated before publication",
    ));
  }
  if (metadata.sha256) summaryRows.push(["SHA-256", metadata.sha256]);
  if (metadata.size != null) summaryRows.push(["Size", formatBytesDetail(metadata.size)]);
  return { crumbText: entry.path, summaryRows, snippets };
}

export {
  condaUsageDetail,
};
