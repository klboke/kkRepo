import { selectedUploadRepository } from "./catalog.js";
import { uploadFieldValue } from "./fields.js";

function computedUploadPaths() {
  const repo = selectedUploadRepository();
  if (!repo) return [];
  const specRows = Array.from(document.querySelectorAll(".upload-spec-asset-row"));
  if (specRows.length) {
    const directory = document.querySelector('[data-upload-component-field="directory"]')?.value || "/";
    const prefix = directory.trim().replaceAll("\\", "/").replace(/^\/+|\/+$/g, "");
    return specRows.map((row) => {
      const filename = row.querySelector('[data-upload-asset-field="filename"]')?.value?.trim()
        || row.querySelector(".upload-spec-file")?.files?.[0]?.name;
      return filename ? `/${[prefix, filename.replace(/^\/+/, "")].filter(Boolean).join("/")}` : "";
    }).filter(Boolean);
  }
  if (repo.format === "conan") {
    const name = uploadFieldValue("upload-conan-name");
    const version = uploadFieldValue("upload-conan-version");
    const user = uploadFieldValue("upload-conan-user") || "_";
    const channel = uploadFieldValue("upload-conan-channel") || "_";
    const rrev = uploadFieldValue("upload-conan-rrev");
    const packageId = uploadFieldValue("upload-conan-package-id");
    const prev = uploadFieldValue("upload-conan-prev");
    if (!name || !version || !rrev) return [];
    let root = `${user}/${name}/${version}/${channel}#${rrev}`;
    if (packageId && prev) root += `/packages/${packageId}/revisions/${prev}/files`;
    return Array.from(document.querySelectorAll(".conan-upload-asset-row"))
      .map((row) => {
        const file = row.querySelector(".upload-conan-file")?.files?.[0];
        const path = row.querySelector(".upload-conan-filename")?.value?.trim() || file?.name;
        return path ? `${root}/${path}` : "";
      })
      .filter(Boolean);
  }
  if (repo.format === "terraform") {
    const kind = uploadFieldValue("upload-terraform-kind");
    const namespace = uploadFieldValue("upload-terraform-namespace");
    const name = uploadFieldValue("upload-terraform-name");
    const version = uploadFieldValue("upload-terraform-version");
    const file = document.getElementById("upload-file")?.files?.[0];
    if (!kind || !namespace || !name || !version || !file) return [];
    if (kind === "module") {
      const system = uploadFieldValue("upload-terraform-system");
      return system
        ? [`v1/modules/${namespace}/${name}/${system}/${version}/${file.name}`]
        : [];
    }
    const os = uploadFieldValue("upload-terraform-os");
    const arch = uploadFieldValue("upload-terraform-arch");
    return os && arch
      ? [`v1/providers/${namespace}/${name}/${version}/download/${os}/${arch}`]
      : [];
  }
  if (repo.format === "swift") {
    const scope = uploadFieldValue("upload-swift-scope");
    const name = uploadFieldValue("upload-swift-name");
    const version = uploadFieldValue("upload-swift-version");
    const archive = document.getElementById("upload-file")?.files?.[0];
    return scope && name && version && archive ? [`${scope}/${name}/${version}`] : [];
  }
  if (repo.format === "conda") {
    const channel = uploadFieldValue("upload-conda-channel").replace(/^\/+|\/+$/g, "");
    const subdir = uploadFieldValue("upload-conda-subdir");
    const file = document.getElementById("upload-file")?.files?.[0];
    return subdir && file ? [[channel, subdir, file.name].filter(Boolean).join("/")] : [];
  }
  if (repo.format === "ansiblegalaxy") {
    const file = document.getElementById("upload-file")?.files?.[0];
    return file ? [file.name] : [];
  }
  if (repo.format !== "maven2") {
    const file = document.getElementById("upload-file")?.files?.[0];
    return file ? [file.name] : [];
  }
  const groupId = uploadFieldValue("upload-group-id");
  const artifactId = uploadFieldValue("upload-artifact-id");
  const version = uploadFieldValue("upload-version");
  if (!groupId || !artifactId || !version) return [];
  const paths = [];
  document.querySelectorAll(".upload-asset-row").forEach((row) => {
    const classifier = row.querySelector(".upload-classifier").value.trim();
    const extension = row.querySelector(".upload-extension").value.trim() || "jar";
    const filename = `${artifactId}-${version}${classifier ? `-${classifier}` : ""}.${extension.replace(/^\.+/, "")}`;
    paths.push(`${groupId.replaceAll(".", "/")}/${artifactId}/${version}/${filename}`);
  });
  const generatePom = document.getElementById("upload-generate-pom")?.checked;
  const hasPom = paths.some((path) => path.endsWith(".pom"));
  if (generatePom && !hasPom) {
    paths.push(`${groupId.replaceAll(".", "/")}/${artifactId}/${version}/${artifactId}-${version}.pom`);
  }
  return paths;
}

function updateUploadPath() {
  const target = document.getElementById("upload-path");
  if (!target) return;
  const value = computedUploadPaths().join("\n");
  target.value = value;
}

export {
  updateUploadPath,
};
