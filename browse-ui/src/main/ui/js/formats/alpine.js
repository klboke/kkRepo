import { usageSnippet } from "../details/pane.js";
import { currentRepository, repositoryBaseUrl } from "../repositories/list.js";
import { state } from "../tree/browser.js";

function alpineUsageDetail(entry, detail = null) {
  const metadata = detail?.alpine || {};
  const repo = currentRepository();
  const settings = repo?.alpine || {};
  const distribution = metadata.distribution || settings.distributions?.[0] || "v3.23";
  const channel = metadata.channel || settings.channels?.[0] || "main";
  const repositoryArchitecture = metadata.repository_architecture
    || settings.architectures?.[0] || "x86_64";
  const packageName = metadata.package || "<package>";
  const version = metadata.version || "";
  const repositoryUrl = `${repositoryBaseUrl().replace(/\/+$/, "")}/${distribution}/${channel}`;
  const keyFilename = settings.keyFilename || `${state.repo}.rsa.pub`;
  const summaryRows = [
    ["Repository", state.repo],
    ["Format", "alpine"],
    ["Distribution", distribution],
    ["Channel", channel],
    ["Repository architecture", repositoryArchitecture],
    ["Package", packageName],
    ["Version", version || "-"],
    ["Package architecture", metadata.package_architecture || "-"],
  ];
  if (metadata.identity) summaryRows.push(["APK identity", metadata.identity]);
  if (metadata.sha256) summaryRows.push(["SHA-256", metadata.sha256]);
  const snippets = [
    usageSnippet(
      "Trust key",
      `sudo cp '/path/to/${keyFilename}' '/etc/apk/keys/${keyFilename}'`,
      "An administrator can export the scoped public key from the repository settings",
    ),
    usageSnippet(
      "repositories",
      `echo '${repositoryUrl}' | sudo tee -a /etc/apk/repositories`,
      "For private repositories, use an apk-compatible Basic/token credential source; do not commit credentials in URLs",
    ),
    usageSnippet("Update", "sudo apk update"),
    usageSnippet(
      "Install",
      `sudo apk add '${packageName}${version ? `=${version}` : ""}'`,
    ),
    usageSnippet(
      "Fetch",
      `apk fetch --repository '${repositoryUrl}' '${packageName}${version ? `=${version}` : ""}'`,
    ),
  ];
  if (repo?.type === "hosted") {
    snippets.push(usageSnippet(
      "Publish",
      `curl --fail --user '<username>:<token>' -F 'alpine.distribution=${distribution}' -F 'alpine.channel=${channel}' -F 'alpine.repositoryArchitecture=${repositoryArchitecture}' -F 'alpine.asset=@package.apk' '${window.location.origin}/service/rest/v1/components?repository=${encodeURIComponent(state.repo)}'`,
      "The server validates APK v2 members, .PKGINFO, filename, architecture, datahash, and package identity",
    ));
  }
  return { crumbText: entry.path, summaryRows, snippets };
}

export {
  alpineUsageDetail,
};
