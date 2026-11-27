import { pathSegments } from "./format.js";
import { usageSnippet } from "./pane.js";
import { alpineUsageDetail } from "../formats/alpine.js";
import { ansibleGalaxyUsageDetail } from "../formats/ansible.js";
import { aptUsageDetail } from "../formats/apt.js";
import { cargoCoordinates, cargoUsageDetail } from "../formats/cargo.js";
import { composerUsageDetail } from "../formats/composer.js";
import { conanUsageDetail } from "../formats/conan.js";
import { condaUsageDetail } from "../formats/conda.js";
import { dockerUsageDetail } from "../formats/docker.js";
import { goModulePath, goUsageDetail } from "../formats/go.js";
import { helmUsageDetail } from "../formats/helm.js";
import { huggingFaceUsageDetail } from "../formats/huggingface.js";
import { mavenUsageDetail } from "../formats/maven.js";
import { npmPackageName, npmUsageDetail } from "../formats/npm.js";
import { pubCoordinates, pubUsageDetail } from "../formats/pub.js";
import { pypiUsageDetail } from "../formats/pypi.js";
import { rUsageDetail } from "../formats/r.js";
import { swiftUsageDetail } from "../formats/swift.js";
import { terraformUsageDetail } from "../formats/terraform.js";
import { currentRepository, repositoryBaseUrl } from "../repositories/list.js";
import { state } from "../tree/browser.js";

function hasDirectoryUsage(entry) {
  const repo = currentRepository();
  if (!repo || entry.leaf) return false;
  const parts = pathSegments(entry.path);
  if (repo.format === "pypi") {
    return parts.length >= 1 && parts[0] !== "simple";
  }
  if (repo.format === "npm") {
    return Boolean(npmPackageName(entry.path));
  }
  if (repo.format === "cargo") {
    return Boolean(cargoCoordinates(entry).name);
  }
  if (repo.format === "go") {
    return goModulePath(entry.path) === entry.path;
  }
  if (repo.format === "pub") {
    return Boolean(pubCoordinates(entry).name);
  }
  if (repo.format === "composer") {
    const reserved = new Set(["p2", "providers", "packages", "_composer"]);
    return (parts.length === 2 || parts.length === 3) && !reserved.has(parts[0]);
  }
  if (repo.format === "terraform") {
    return parts[0] === "v1";
  }
  return false;
}

function gitLfsUsageDetail(entry) {
  return {
    crumbText: entry.path,
    summaryRows: [["Repository", state.repo], ["Format", "Git LFS"], ["OID (SHA-256)", entry.path]],
    snippets: [
      usageSnippet("Configure", `git config -f .lfsconfig lfs.url '${repositoryBaseUrl().replace(/\/+$/, "")}/info/lfs'`, "Keep the Git remote on your Git server; use a credential helper for kkRepo access"),
      usageSnippet("Verify", "git lfs fetch --all\ngit lfs fsck", "Deleting an LFS object can break checkout of historical commits. Automatic cleanup is disabled."),
    ],
  };
}

const usageProviders = new Map([
  ["alpine", alpineUsageDetail],
  ["ansiblegalaxy", ansibleGalaxyUsageDetail],
  ["apt", aptUsageDetail],
  ["cargo", cargoUsageDetail],
  ["composer", composerUsageDetail],
  ["conan", conanUsageDetail],
  ["conda", condaUsageDetail],
  ["docker", entry => dockerUsageDetail(entry)],
  ["gitlfs", gitLfsUsageDetail],
  ["go", goUsageDetail],
  ["helm", helmUsageDetail],
  ["huggingface", huggingFaceUsageDetail],
  ["maven2", mavenUsageDetail],
  ["npm", npmUsageDetail],
  ["pub", pubUsageDetail],
  ["pypi", pypiUsageDetail],
  ["r", rUsageDetail],
  ["swift", swiftUsageDetail],
  ["terraform", terraformUsageDetail],
]);

async function usageDetailForEntry(entry, detail = null) {
  const provider = usageProviders.get(currentRepository()?.format);
  return provider ? provider(entry, detail) : null;
}

export {
  hasDirectoryUsage,
  usageDetailForEntry,
};
