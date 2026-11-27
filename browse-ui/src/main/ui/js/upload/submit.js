import { treeCache } from "../tree/browser.js";
import { selectedUploadRepository, selectedUploadSpec } from "./catalog.js";
import { fieldsState, renderUploadFields, uploadFieldValue, uploadSpecFieldLabel } from "./fields.js";
import { updateUploadPath } from "./paths.js";

function setUploadStatus(message, tone = "") {
  const status = document.getElementById("upload-status");
  status.textContent = message;
  status.className = `upload-status ${tone}`.trim();
}

async function uploadSelectedAsset(event) {
  event.preventDefault();
  const repo = selectedUploadRepository();
  if (!repo) {
    setUploadStatus("Repository is required.", "error");
    return;
  }
  const form = new FormData();
  try {
    buildUploadForm(repo, form);
  } catch (error) {
    setUploadStatus(error.message, "error");
    return;
  }
  const submit = document.getElementById("upload-submit");
  submit.disabled = true;
  setUploadStatus("Uploading...");
  try {
    const response = await fetch(`/service/rest/v1/components?repository=${encodeURIComponent(repo.name)}`, {
      method: "POST",
      body: form,
    });
    if (!response.ok) {
      throw new Error(await uploadError(response));
    }
    treeCache.clear();
    clearUploadForm();
    setUploadStatus(`Uploaded to ${repo.name}`, "ok");
  } catch (error) {
    setUploadStatus(`Upload failed: ${error.message}`, "error");
  } finally {
    submit.disabled = false;
  }
}

function clearUploadForm() {
  fieldsState.uploadAssetCount = 1;
  renderUploadFields();
  updateUploadPath();
}

function buildUploadForm(repo, form) {
  if (repo.format === "ohpm") {
    const har = document.getElementById("upload-file")?.files?.[0];
    const hsp = document.getElementById("upload-ohpm-hsp")?.files?.[0];
    const bundle = har && /\.(tgz|tar\.gz)$/.test(har.name);
    if (!har || (!bundle && !har.name.endsWith(".har")) || (hsp && !hsp.name.endsWith(".hsp")) || (bundle && hsp)) {
      throw new Error("Select a HAR archive with its paired HSP, or one complete HSP tgz bundle.");
    }
    form.append("ohpm.asset", har, har.name);
    if (hsp) form.append("ohpm.hsp", hsp, hsp.name);
    form.append("ohpm.tag", uploadFieldValue("upload-ohpm-tag") || "latest");
    return;
  }
  if (repo.format === "maven2") {
    const groupId = uploadFieldValue("upload-group-id");
    const artifactId = uploadFieldValue("upload-artifact-id");
    const version = uploadFieldValue("upload-version");
    if (!groupId || !artifactId || !version) {
      throw new Error("Group ID, Artifact ID, and Version are required.");
    }
    form.append("maven2.groupId", groupId);
    form.append("maven2.artifactId", artifactId);
    form.append("maven2.version", version);
    const packaging = uploadFieldValue("upload-packaging");
    if (packaging) form.append("maven2.packaging", packaging);
    if (document.getElementById("upload-generate-pom")?.checked) {
      form.append("maven2.generate-pom", "true");
    }
    const rows = Array.from(document.querySelectorAll(".upload-asset-row"));
    if (!rows.length) throw new Error("At least one asset is required.");
    rows.forEach((row, index) => {
      const file = row.querySelector(".upload-file-input").files[0];
      const extension = row.querySelector(".upload-extension").value.trim();
      const classifier = row.querySelector(".upload-classifier").value.trim();
      if (!file || !extension) throw new Error("Each Maven asset requires File and Extension.");
      const assetKey = `maven2.asset${index + 1}`;
      form.append(assetKey, file, file.name);
      form.append(`${assetKey}.extension`, extension.replace(/^\.+/, ""));
      if (classifier) form.append(`${assetKey}.classifier`, classifier);
    });
    return;
  }
  if (repo.format === "conan") {
    const name = uploadFieldValue("upload-conan-name");
    const version = uploadFieldValue("upload-conan-version");
    const user = uploadFieldValue("upload-conan-user");
    const channel = uploadFieldValue("upload-conan-channel");
    const rrev = uploadFieldValue("upload-conan-rrev");
    const packageId = uploadFieldValue("upload-conan-package-id");
    const prev = uploadFieldValue("upload-conan-prev");
    if (!name || !version || !rrev) {
      throw new Error("Recipe name, Recipe version, and RREV are required.");
    }
    if (Boolean(packageId) !== Boolean(prev)) {
      throw new Error("Package ID and PREV must either both be provided or both be empty.");
    }
    if (channel && !user) throw new Error("A Conan channel requires a user.");
    const rows = Array.from(document.querySelectorAll(".conan-upload-asset-row"));
    const files = rows.map((row) => ({
      file: row.querySelector(".upload-conan-file")?.files?.[0],
      path: row.querySelector(".upload-conan-filename")?.value?.trim(),
    }));
    if (!files.length || files.some((entry) => !entry.file)) {
      throw new Error("Every Conan revision row requires a file.");
    }
    files.forEach((entry) => {
      if (!entry.path) entry.path = entry.file.name;
    });
    if (files.filter((entry) => entry.path === "conanmanifest.txt").length !== 1) {
      throw new Error("A Conan revision requires exactly one conanmanifest.txt.");
    }
    form.append("conan.name", name);
    form.append("conan.version", version);
    if (user) form.append("conan.user", user);
    if (channel) form.append("conan.channel", channel);
    form.append("conan.rrev", rrev);
    if (packageId) form.append("conan.package-id", packageId);
    if (prev) form.append("conan.prev", prev);
    files.forEach((entry, index) => {
      const key = `conan.asset${index + 1}`;
      form.append(key, entry.file, entry.file.name);
      form.append(`${key}.filename`, entry.path);
    });
    return;
  }
  if (repo.format === "terraform") {
    const kind = uploadFieldValue("upload-terraform-kind");
    const namespace = uploadFieldValue("upload-terraform-namespace");
    const name = uploadFieldValue("upload-terraform-name");
    const version = uploadFieldValue("upload-terraform-version");
    const file = document.getElementById("upload-file")?.files?.[0];
    if (!kind || !namespace || !name || !version || !file) {
      throw new Error("Package kind, Namespace, Name, Version, and File are required.");
    }
    form.append("terraform.kind", kind);
    form.append("terraform.namespace", namespace);
    form.append("terraform.name", name);
    form.append("terraform.version", version);
    if (kind === "module") {
      const system = uploadFieldValue("upload-terraform-system");
      if (!system) throw new Error("Target system is required for a Terraform module.");
      form.append("terraform.system", system);
    } else if (kind === "provider") {
      const os = uploadFieldValue("upload-terraform-os");
      const arch = uploadFieldValue("upload-terraform-arch");
      const protocols = uploadFieldValue("upload-terraform-protocols");
      if (!os || !arch) {
        throw new Error("Operating system and Architecture are required for a Terraform provider.");
      }
      form.append("terraform.os", os);
      form.append("terraform.arch", arch);
      if (protocols) form.append("terraform.protocols", protocols);
    } else {
      throw new Error("Package kind must be module or provider.");
    }
    form.append("terraform.asset", file, file.name);
    return;
  }
  if (repo.format === "swift") {
    const scope = uploadFieldValue("upload-swift-scope");
    const name = uploadFieldValue("upload-swift-name");
    const version = uploadFieldValue("upload-swift-version");
    const archive = document.getElementById("upload-file")?.files?.[0];
    if (!scope || !name || !version || !archive) {
      throw new Error("Scope, Package name, Version, and Source archive are required.");
    }
    form.append("swift.scope", scope);
    form.append("swift.name", name);
    form.append("swift.version", version);
    const metadata = uploadFieldValue("upload-swift-metadata");
    if (metadata) form.append("swift.metadata", metadata);
    const sourceSignature = document.getElementById("upload-swift-source-signature")?.files?.[0];
    const metadataSignature = document.getElementById("upload-swift-metadata-signature")?.files?.[0];
    const signatureFormat = uploadFieldValue("upload-swift-signature-format");
    if (sourceSignature) {
      form.append("swift.source-archive-signature", sourceSignature, sourceSignature.name);
      if (signatureFormat) form.append("swift.signature-format", signatureFormat);
    }
    if (metadataSignature) {
      form.append("swift.metadata-signature", metadataSignature, metadataSignature.name);
    }
    form.append("swift.source-archive", archive, archive.name);
    return;
  }
  if (repo.format === "conda") {
    const channel = uploadFieldValue("upload-conda-channel").replace(/^\/+|\/+$/g, "");
    const subdir = uploadFieldValue("upload-conda-subdir");
    const file = document.getElementById("upload-file")?.files?.[0];
    if (!subdir || !file || !(file.name.endsWith(".conda") || file.name.endsWith(".tar.bz2"))) {
      throw new Error("Subdir and a .conda or .tar.bz2 package are required.");
    }
    if (channel) form.append("conda.channel", channel);
    form.append("conda.subdir", subdir);
    form.append("conda.asset", file, file.name);
    return;
  }
  if (repo.format === "ansiblegalaxy") {
    const file = document.getElementById("upload-file")?.files?.[0];
    if (!file || !/^[A-Za-z0-9_]+-[A-Za-z0-9_]+-[0-9A-Za-z.+-]+\.tar\.gz$/.test(file.name)) {
      throw new Error("A canonical namespace-name-version.tar.gz collection artifact is required.");
    }
    form.append("ansiblegalaxy.asset", file, file.name);
    return;
  }
  if (repo.format === "apt") {
    const file = document.getElementById("upload-file")?.files?.[0];
    if (!file || !file.name.toLowerCase().endsWith(".deb")) {
      throw new Error("A .deb package is required.");
    }
    form.append("apt.asset", file, file.name);
    return;
  }
  if (repo.format === "alpine") {
    const file = document.getElementById("upload-file")?.files?.[0];
    const distribution = uploadFieldValue("upload-alpine-distribution");
    const channel = uploadFieldValue("upload-alpine-channel");
    const architecture = uploadFieldValue("upload-alpine-architecture");
    if (!distribution || !channel || !architecture
        || !file || !file.name.toLowerCase().endsWith(".apk")) {
      throw new Error("Distribution, channel, architecture, and an .apk package are required.");
    }
    form.append("alpine.distribution", distribution);
    form.append("alpine.channel", channel);
    form.append("alpine.repositoryArchitecture", architecture);
    form.append("alpine.asset", file, file.name);
    return;
  }
  if (repo.format === "r") {
    const file = document.getElementById("upload-file")?.files?.[0];
    const namespace = document.getElementById("upload-r-namespace")?.value?.trim();
    if (!namespace || !file || !/\.(tar\.gz|tgz|zip)$/.test(file.name)) {
      throw new Error("An R package (.tar.gz, .tgz or .zip) and Package Path are required.");
    }
    form.append("r.asset.pathId", namespace);
    form.append("r.asset", file, file.name);
    return;
  }
  const spec = selectedUploadSpec(repo);
  const rows = Array.from(document.querySelectorAll(".upload-spec-asset-row"));
  if (!spec || !rows.length) throw new Error("Upload definition is unavailable.");
  (spec.componentFields || []).forEach((field) => {
    const node = document.querySelector(`[data-upload-component-field="${CSS.escape(field.name)}"]`);
    const value = field.type === "BOOLEAN" ? (node?.checked ? "true" : "") : node?.value?.trim();
    if (!field.optional && !value) throw new Error(`${uploadSpecFieldLabel(field.name)} is required.`);
    if (value) form.append(`${repo.format}.${field.name}`, value);
  });
  if (!spec.multipleUpload && rows.length !== 1) {
    throw new Error("This repository format accepts exactly one asset.");
  }
  rows.forEach((row, index) => {
    const fileField = (spec.assetFields || []).find((field) => field.type === "FILE");
    const fileInput = fileField
      ? row.querySelector(`[data-upload-asset-field="${CSS.escape(fileField.name)}"]`)
      : null;
    const file = fileInput?.files?.[0];
    if (!fileField || !file) throw new Error("File is required.");
    const assetKey = spec.multipleUpload && fileField.name === "asset"
      ? `${repo.format}.asset${index + 1}`
      : `${repo.format}.${fileField.name}`;
    form.append(assetKey, file, file.name);
    (spec.assetFields || []).filter((field) => field.type !== "FILE").forEach((field) => {
      const node = row.querySelector(`[data-upload-asset-field="${CSS.escape(field.name)}"]`);
      const value = field.type === "BOOLEAN" ? (node?.checked ? "true" : "") : node?.value?.trim();
      if (!field.optional && !value) throw new Error(`${uploadSpecFieldLabel(field.name)} is required.`);
      if (value) form.append(`${assetKey}.${field.name}`, value);
    });
  });
}

async function uploadError(response) {
  const text = await response.text();
  if (!text) return `HTTP ${response.status}`;
  try {
    const json = JSON.parse(text);
    return json.error || json.message || text;
  } catch (_) {
    return text;
  }
}

export {
  setUploadStatus,
  uploadSelectedAsset,
};
