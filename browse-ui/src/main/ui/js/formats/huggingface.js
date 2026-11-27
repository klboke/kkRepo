import { pathSegments } from "../details/format.js";
import { usageSnippet } from "../details/pane.js";
import { repositoryBaseUrl } from "../repositories/list.js";
import { state } from "../tree/browser.js";

function huggingFaceUsageDetail(entry, detail = null) {
  const metadata = detail?.huggingface || {};
  const parts = pathSegments(entry.path);
  const commitIndex = parts.findIndex((part) => /^[0-9a-f]{40,64}$/i.test(part));
  const repoId = metadata.repoId || (commitIndex > 0 ? parts.slice(0, commitIndex).join("/") : "<namespace>/<model>");
  const commit = metadata.commit || (commitIndex >= 0 ? parts[commitIndex] : "<commit>");
  const filePath = metadata.filePath || (commitIndex >= 0 ? parts.slice(commitIndex + 1).join("/") : entry.name || "<file>");
  const endpoint = repositoryBaseUrl().replace(/\/+$/, "");
  const summaryRows = [
    ["Repository", state.repo],
    ["Format", "huggingface"],
    ["Model", repoId],
    ["Commit", commit],
    ["File", filePath],
    ["File kind", metadata.fileKind || "-"],
    ["Cache state", metadata.cacheState || "READY"],
  ];
  if (metadata.lfsSha256) summaryRows.push(["LFS SHA-256", metadata.lfsSha256]);
  if (metadata.gitOid) summaryRows.push(["Git OID", metadata.gitOid]);
  const snippets = [
    usageSnippet(
      "Environment",
      `export HF_ENDPOINT='${endpoint}'`,
      "All Hub API and file traffic stays behind the repository permission boundary",
    ),
    usageSnippet(
      "Python file download",
      `python -c \"from huggingface_hub import hf_hub_download; print(hf_hub_download('${repoId}', '${filePath}', revision='${commit}'))\"`,
    ),
    usageSnippet(
      "Snapshot download",
      `hf download '${repoId}' --revision '${commit}'`,
    ),
  ];
  return { crumbText: entry.path, summaryRows, snippets };
}

export {
  huggingFaceUsageDetail,
};
