import { usageSnippet } from "../details/pane.js";
import { currentRepository, repositoryBaseUrl } from "../repositories/list.js";
import { state } from "../tree/browser.js";

function rUsageDetail(entry, detail = null) {
  const metadata = detail?.r || {};
  const repo = currentRepository();
  const repositoryUrl = repositoryBaseUrl().replace(/\/+$/, "");
  const packageName = metadata.package || entry.name || "<package>";
  const version = metadata.version || entry.version || "";
  const namespace = metadata.namespace || "src/contrib";
  const source = namespace === "src/contrib";
  const packageType = namespace.startsWith("bin/windows/") ? "win.binary"
    : namespace.startsWith("bin/macosx/") ? "mac.binary" : "source";
  const location = source ? `repos = '${repositoryUrl}'`
    : `contriburl = '${repositoryUrl}/${namespace}'`;
  const extension = packageType === "win.binary" ? ".zip" : packageType === "mac.binary" ? ".tgz" : ".tar.gz";
  const filename = metadata.filename || `${packageName}_${version || "1.0.0"}${extension}`;
  const summaryRows = [
    ["Repository", state.repo],
    ["Format", "r"],
    ["Namespace", namespace],
    ["Package", packageName],
    ["Version", version || "-"],
  ];
  if (metadata.license) summaryRows.push(["License", metadata.license]);
  if (metadata.md5) summaryRows.push(["MD5", metadata.md5]);
  if (metadata.sha256) summaryRows.push(["SHA-256", metadata.sha256]);
  const snippets = [
    usageSnippet(
      ".Rprofile",
      `options(repos = c(KKRepo = '${repositoryUrl}'))`,
      "Use a credential helper or netrc for private repositories; do not commit tokens in URLs",
    ),
    usageSnippet(
      "Available packages",
      `available.packages(${location}, type = '${packageType}')`,
    ),
    usageSnippet(
      "Install",
      `install.packages('${packageName}', ${location}, type = '${packageType}')`,
    ),
  ];
  if (String(repo?.type || "").toLowerCase() === "hosted") {
    snippets.push(usageSnippet(
      "Publish",
      `curl --fail --user '<username>:<token>' -F 'r.asset.pathId=${namespace}' -F 'r.asset=@${filename}' '${window.location.origin}/service/rest/v1/components?repository=${encodeURIComponent(state.repo)}'`,
      "The server validates DESCRIPTION and archive limits without executing package code",
    ));
  }
  return { crumbText: entry.path, summaryRows, snippets };
}

export {
  rUsageDetail,
};
