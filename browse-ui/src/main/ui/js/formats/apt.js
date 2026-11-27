import { usageSnippet } from "../details/pane.js";
import { currentRepository, repositoryBaseUrl } from "../repositories/list.js";
import { state } from "../tree/browser.js";

function aptUsageDetail(entry, detail = null) {
  const metadata = detail?.apt || {};
  const repo = currentRepository();
  const settings = repo?.apt || {};
  const distribution = metadata.distribution || settings.distribution || "stable";
  const component = metadata.component || settings.component || "main";
  const architecture = metadata.architecture || "-";
  const packageName = metadata.package || metadata.name || "<package>";
  const version = metadata.version || "";
  const repositoryUrl = repositoryBaseUrl().replace(/\/+$/, "");
  const keyring = `/etc/apt/keyrings/${state.repo}.gpg`;
  const summaryRows = [
    ["Repository", state.repo],
    ["Format", "apt"],
    ["Distribution", distribution || "multi-distribution proxy"],
    ["Component", component],
    ["Package", packageName],
    ["Version", version || "-"],
    ["Architecture", architecture],
  ];
  if (metadata.sourcePackage) summaryRows.push(["Source package", metadata.sourcePackage]);
  if (metadata.section) summaryRows.push(["Section", metadata.section]);
  if (metadata.priority) summaryRows.push(["Priority", metadata.priority]);
  if (metadata.sha256) summaryRows.push(["SHA-256", metadata.sha256]);
  const snippets = [
    usageSnippet(
      "Scoped keyring",
      `curl --fail '${repositoryUrl}/gpg.key' | gpg --dearmor | sudo tee '${keyring}' >/dev/null`,
      "For private repositories, place credentials in /etc/apt/auth.conf.d instead of the URL",
    ),
    usageSnippet(
      "sources.list.d",
      `deb [signed-by=${keyring}] ${repositoryUrl} ${distribution || "<distribution>"} ${component}`,
    ),
    usageSnippet("Update", "sudo apt-get update"),
    usageSnippet(
      "Install",
      `sudo apt-get install '${packageName}${version ? `=${version}` : ""}'`,
    ),
  ];
  if (repo?.type === "hosted") {
    snippets.push(usageSnippet(
      "Publish",
      `curl --fail --user '<username>:<token>' -F 'apt.asset=@package.deb' '${window.location.origin}/service/rest/v1/components?repository=${encodeURIComponent(state.repo)}'`,
      "Package identity and canonical pool path are read from the .deb control archive",
    ));
  }
  return { crumbText: entry.path, summaryRows, snippets };
}

export {
  aptUsageDetail,
};
