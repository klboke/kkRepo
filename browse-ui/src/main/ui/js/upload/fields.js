import { escapeHtml } from "../details/format.js";
import { repositoryRows } from "../repositories/list.js";
import {
  catalogState,
  selectedUploadRepository,
  selectedUploadSpec,
  uploadEmptyStateMessage,
  uploadableRepositories,
} from "./catalog.js";
import { updateUploadPath } from "./paths.js";
import { setUploadStatus } from "./submit.js";

const fieldsState = {
  uploadAssetCount: 1,
};

// --- upload ---------------------------------------------------------------
function renderUpload() {
  const select = document.getElementById("upload-repository");
  const submit = document.getElementById("upload-submit");
  if (!catalogState.uploadRepositoriesLoaded) {
    select.disabled = true;
    submit.disabled = true;
    select.innerHTML = '<option value="">Loading repositories...</option>';
    document.getElementById("upload-fields").innerHTML =
      '<div class="muted-row upload-empty">Loading upload repositories...</div>';
    setUploadStatus("");
    return;
  }

  const uploadRepos = uploadableRepositories(repositoryRows(catalogState.uploadRepositoriesCache, "", true));
  const previous = select.value;
  select.innerHTML = uploadRepos.map((repo) =>
    `<option value="${escapeHtml(repo.name)}">${escapeHtml(repo.name)} (${escapeHtml(repo.format)})</option>`).join("");
  select.disabled = false;
  submit.disabled = uploadRepos.length === 0;
  if (previous && uploadRepos.some((repo) => repo.name === previous)) {
    select.value = previous;
  } else if (uploadRepos.length) {
    select.value = uploadRepos[0].name;
  }
  renderUploadFields();
  if (uploadRepos.length === 0) {
    select.innerHTML = '<option value="">No repositories available</option>';
    select.disabled = true;
    document.getElementById("upload-fields").innerHTML =
      `<div class="muted-row upload-empty" role="note">${escapeHtml(uploadEmptyStateMessage())}</div>`;
  }
  updateUploadPath();
}

function renderUploadFields() {
  const repo = selectedUploadRepository();
  const fields = document.getElementById("upload-fields");
  if (!repo) {
    fields.innerHTML = '<div class="muted-row upload-empty">No supported hosted repository selected.</div>';
    return;
  }
  if (repo.format === "ohpm") {
    fields.innerHTML = `
      <label class="upload-file">
        <span>HAR archive or HSP bundle</span>
        <input id="upload-file" type="file" accept=".har,.tgz,.tar.gz" required>
      </label>
      <label class="upload-file">
        <span>Paired HSP (required for an interface HAR)</span>
        <input id="upload-ohpm-hsp" type="file" accept=".hsp">
      </label>
      <label>
        <span>Distribution tag</span>
        <input id="upload-ohpm-tag" type="text" value="latest" required>
      </label>
      <p class="upload-help">Select a HAR with its paired HSP, or a complete HSP tgz bundle. Package name and version come from the HAR. Existing versions cannot be overwritten.</p>
      <label class="upload-path">
        <span>Selected archive</span>
        <input id="upload-path" type="text" readonly>
      </label>`;
    return;
  }
  if (repo.format === "maven2") {
    fields.innerHTML = `
      <label>
        <span>Group ID</span>
        <input id="upload-group-id" type="text" placeholder="com.acme" required>
      </label>
      <label>
        <span>Artifact ID</span>
        <input id="upload-artifact-id" type="text" placeholder="demo" required>
      </label>
      <label>
        <span>Version</span>
        <input id="upload-version" type="text" placeholder="1.0.0" required>
      </label>
      <label>
        <span>Packaging</span>
        <input id="upload-packaging" type="text" placeholder="jar">
      </label>
      <label class="upload-check">
        <input id="upload-generate-pom" type="checkbox">
        <span>Generate POM</span>
      </label>
      <div class="upload-assets" id="upload-assets">
        ${mavenAssetRows()}
      </div>
      <button class="secondary-button upload-add-asset" id="upload-add-asset" type="button">Add asset</button>
      <label class="upload-path">
        <span>Path</span>
        <textarea id="upload-path" readonly></textarea>
      </label>
      <datalist id="upload-extension-options">
        <option value="jar"></option>
        <option value="pom"></option>
        <option value="xml"></option>
        <option value="zip"></option>
        <option value="asc"></option>
      </datalist>
    `;
    document.getElementById("upload-add-asset").addEventListener("click", () => {
      fieldsState.uploadAssetCount += 1;
      document.getElementById("upload-assets").innerHTML = mavenAssetRows();
      updateUploadPath();
    });
    bindRemoveAssetButtons();
    return;
  }
  if (repo.format === "conan") {
    fieldsState.uploadAssetCount = Math.max(fieldsState.uploadAssetCount, 3);
    fields.innerHTML = `
      <label>
        <span>Recipe name</span>
        <input id="upload-conan-name" type="text" placeholder="demo" required>
      </label>
      <label>
        <span>Recipe version</span>
        <input id="upload-conan-version" type="text" placeholder="1.0.0" required>
      </label>
      <label>
        <span>User (optional)</span>
        <input id="upload-conan-user" type="text" placeholder="acme">
      </label>
      <label>
        <span>Channel (optional)</span>
        <input id="upload-conan-channel" type="text" placeholder="stable">
      </label>
      <label>
        <span>Recipe revision (RREV)</span>
        <input id="upload-conan-rrev" type="text" placeholder="revision hash" required>
      </label>
      <label>
        <span>Package ID (binary only)</span>
        <input id="upload-conan-package-id" type="text" placeholder="package ID">
      </label>
      <label>
        <span>Package revision (PREV)</span>
        <input id="upload-conan-prev" type="text" placeholder="package revision">
      </label>
      <div class="muted-row full-width">Upload one complete recipe or binary revision. Include exactly one <code>conanmanifest.txt</code>; binary revisions also require <code>conaninfo.txt</code> and one <code>conan_package.tgz</code>, <code>.txz</code>, or <code>.tzst</code>. Publish a recipe before its binaries.</div>
      <div class="upload-assets" id="upload-conan-assets">
        ${conanAssetRows()}
      </div>
      <button class="secondary-button upload-add-asset" id="upload-conan-add-asset" type="button">Add revision file</button>
      <label class="upload-path">
        <span>Nexus-compatible Browse path</span>
        <textarea id="upload-path" readonly></textarea>
      </label>
    `;
    document.getElementById("upload-conan-add-asset").addEventListener("click", () => {
      fieldsState.uploadAssetCount += 1;
      document.getElementById("upload-conan-assets").innerHTML = conanAssetRows();
      bindConanAssetRows();
      updateUploadPath();
    });
    ["upload-conan-name", "upload-conan-version", "upload-conan-user",
      "upload-conan-channel", "upload-conan-rrev", "upload-conan-package-id",
      "upload-conan-prev"].forEach((id) => {
      document.getElementById(id).addEventListener("input", updateUploadPath);
    });
    bindConanAssetRows();
    updateUploadPath();
    return;
  }
  if (repo.format === "terraform") {
    fields.innerHTML = `
      <label>
        <span>Package kind</span>
        <select id="upload-terraform-kind" required>
          <option value="module">Module</option>
          <option value="provider">Provider</option>
        </select>
      </label>
      <label>
        <span>Namespace</span>
        <input id="upload-terraform-namespace" type="text" placeholder="acme" required>
      </label>
      <label>
        <span>Name</span>
        <input id="upload-terraform-name" type="text" placeholder="network" required>
      </label>
      <label>
        <span>Version</span>
        <input id="upload-terraform-version" type="text" placeholder="1.0.0" required>
      </label>
      <label data-terraform-kind="module">
        <span>Target system</span>
        <input id="upload-terraform-system" type="text" placeholder="aws" required>
      </label>
      <label data-terraform-kind="provider" hidden>
        <span>Operating system</span>
        <input id="upload-terraform-os" type="text" placeholder="linux">
      </label>
      <label data-terraform-kind="provider" hidden>
        <span>Architecture</span>
        <input id="upload-terraform-arch" type="text" placeholder="amd64">
      </label>
      <label data-terraform-kind="provider" hidden>
        <span>Provider protocols</span>
        <input id="upload-terraform-protocols" type="text" value="5.0" placeholder="5.0 or 6.0">
      </label>
      <label class="upload-file">
        <span>File</span>
        <input id="upload-file" type="file" accept=".zip" required>
      </label>
      <label class="upload-path">
        <span>Asset</span>
        <input id="upload-path" type="text" readonly>
      </label>
    `;
    document.getElementById("upload-terraform-kind")
      .addEventListener("change", updateTerraformUploadKind);
    updateTerraformUploadKind();
    return;
  }
  if (repo.format === "swift") {
    fields.innerHTML = `
      <label>
        <span>Scope</span>
        <input id="upload-swift-scope" type="text" placeholder="acme" required>
      </label>
      <label>
        <span>Package name</span>
        <input id="upload-swift-name" type="text" placeholder="library" required>
      </label>
      <label>
        <span>Version</span>
        <input id="upload-swift-version" type="text" placeholder="1.0.0" required>
      </label>
      <label class="full-width">
        <span>Registry metadata JSON</span>
        <textarea id="upload-swift-metadata" rows="4" placeholder='{"repositoryURLs":["https://github.com/acme/library"]}'>{}</textarea>
      </label>
      <label>
        <span>Signature format</span>
        <input id="upload-swift-signature-format" type="text" value="cms-1.0.0" placeholder="cms-1.0.0">
      </label>
      <label class="upload-file">
        <span>Source archive</span>
        <input id="upload-file" type="file" accept=".zip,application/zip" required>
      </label>
      <label class="upload-file">
        <span>Source archive signature (optional)</span>
        <input id="upload-swift-source-signature" type="file">
      </label>
      <label class="upload-file">
        <span>Metadata signature (optional)</span>
        <input id="upload-swift-metadata-signature" type="file">
      </label>
      <label class="upload-path">
        <span>Release coordinate</span>
        <input id="upload-path" type="text" readonly>
      </label>
    `;
    return;
  }
  if (repo.format === "conda") {
    fields.innerHTML = `
      <label>
        <span>Channel path (optional)</span>
        <input id="upload-conda-channel" type="text" placeholder="team/release">
      </label>
      <label>
        <span>Subdir</span>
        <input id="upload-conda-subdir" type="text" value="noarch" placeholder="linux-64 or noarch" required>
      </label>
      <label class="upload-file">
        <span>Conda package</span>
        <input id="upload-file" type="file" accept=".conda,.tar.bz2,application/zip,application/x-bzip2" required>
      </label>
      <div class="muted-row full-width">Upload a canonical <code>.conda</code> or <code>.tar.bz2</code> package. The server validates <code>info/index.json</code>, filename, subdir, checksums, and archive limits.</div>
      <label class="upload-path">
        <span>Package path</span>
        <input id="upload-path" type="text" readonly>
      </label>
    `;
    return;
  }
  if (repo.format === "apt") {
    fields.innerHTML = `
      <label class="upload-file">
        <span>Debian binary package</span>
        <input id="upload-file" type="file" accept=".deb,application/vnd.debian.binary-package" required>
      </label>
      <div class="muted-row full-width">The server safely inspects the control archive, validates package identity and architecture, stores the canonical pool asset, then atomically publishes signed metadata.</div>
      <label class="upload-path">
        <span>Package path</span>
        <input id="upload-path" type="text" value="Canonical pool path is determined from package control metadata" readonly>
      </label>
    `;
    return;
  }
  if (repo.format === "alpine") {
    const settings = repo.alpine || {};
    fields.innerHTML = `
      <label>
        <span>Distribution</span>
        <input id="upload-alpine-distribution" type="text" value="${escapeHtml(settings.distributions?.[0] || "v3.23")}" required>
      </label>
      <label>
        <span>Channel</span>
        <input id="upload-alpine-channel" type="text" value="${escapeHtml(settings.channels?.[0] || "main")}" required>
      </label>
      <label>
        <span>Repository architecture</span>
        <input id="upload-alpine-architecture" type="text" value="${escapeHtml(settings.architectures?.[0] || "x86_64")}" required>
      </label>
      <label class="upload-file">
        <span>APK v2 package</span>
        <input id="upload-file" type="file" accept=".apk,application/vnd.alpine.apk" required>
      </label>
      <div class="muted-row full-width">The server safely inspects concatenated gzip/tar members and validates <code>.PKGINFO</code>, datahash, filename, architecture, checksums, and resource limits before publishing a signed index snapshot.</div>
      <label class="upload-path">
        <span>Package path</span>
        <input id="upload-path" type="text" value="Package filename is read from the selected APK" readonly>
      </label>
    `;
    return;
  }
  if (repo.format === "r") {
    fields.innerHTML = `
      <label class="upload-file">
        <span>R package</span>
        <input id="upload-file" type="file" accept=".tar.gz,.tgz,.zip,application/gzip,application/x-gzip,application/zip" required>
      </label>
      <p class="upload-help">Upload a source <code>.tar.gz</code>, Windows <code>.zip</code>, macOS <code>.tgz</code>, or Linux <code>.tar.gz</code> package. Choose a directory matching the target platform and R version.</p>
      <label class="upload-path">
        <span>Package path</span>
        <input id="upload-r-namespace" type="text" value="src/contrib" list="upload-r-namespaces" required>
        <datalist id="upload-r-namespaces">
          <option value="src/contrib">Source</option>
          <option value="bin/windows/contrib/4.5">Windows / R 4.5</option>
          <option value="bin/macosx/big-sur-arm64/contrib/4.5">macOS ARM64 / R 4.5</option>
          <option value="bin/linux/noble-x86_64/4.5/src/contrib">Linux noble x86_64 / R 4.5</option>
        </datalist>
      </label>
    `;
    return;
  }
  if (repo.format === "ansiblegalaxy") {
    fields.innerHTML = `
      <label class="upload-file">
        <span>Collection artifact</span>
        <input id="upload-file" type="file" accept=".tar.gz,application/gzip,application/x-gzip" required>
      </label>
      <div class="muted-row full-width">Use the canonical <code>namespace-name-version.tar.gz</code> artifact produced by <code>ansible-galaxy collection build</code>. The server validates MANIFEST.json, FILES.json, identity, and checksums.</div>
      <label class="upload-path">
        <span>Collection artifact</span>
        <input id="upload-path" type="text" readonly>
      </label>
    `;
    return;
  }
  renderUploadSpecFields(repo);
}

function uploadSpecFieldLabel(name) {
  const labels = {
    asset: "File",
    directory: "Directory",
    filename: "Filename",
  };
  return labels[name] || name
    .replaceAll("-", " ")
    .replace(/\b\w/g, (character) => character.toUpperCase());
}

function uploadSpecComponentField(field) {
  const label = uploadSpecFieldLabel(field.name);
  const id = `upload-spec-component-${field.name}`;
  if (field.type === "BOOLEAN") {
    return `
      <label class="upload-check">
        <input id="${escapeHtml(id)}" data-upload-component-field="${escapeHtml(field.name)}" type="checkbox">
        <span>${escapeHtml(label)}</span>
      </label>
    `;
  }
  const value = field.name === "directory" ? "/" : "";
  return `
    <label class="${field.name === "directory" ? "full-width" : ""}">
      <span>${escapeHtml(label)}</span>
      <input id="${escapeHtml(id)}" data-upload-component-field="${escapeHtml(field.name)}"
        type="text" value="${escapeHtml(value)}"
        placeholder="${escapeHtml(field.description || "")}" ${field.optional ? "" : "required"}>
    </label>
  `;
}

function uploadSpecAssetRow(spec, index) {
  const fields = [...(spec.assetFields || [])]
    .sort((left, right) => (left.type === "FILE" ? -1 : right.type === "FILE" ? 1 : 0))
    .map((field) => {
      const label = uploadSpecFieldLabel(field.name);
      if (field.type === "FILE") {
        return `
          <label class="upload-file">
            <span>${escapeHtml(label)}</span>
            <input class="upload-spec-file" data-upload-asset-field="${escapeHtml(field.name)}"
              type="file" ${field.optional ? "" : "required"}>
          </label>
        `;
      }
      return `
        <label>
          <span>${escapeHtml(label)}</span>
          <input class="upload-spec-asset-field" data-upload-asset-field="${escapeHtml(field.name)}"
            data-upload-autofill="${field.name === "filename" ? "true" : "false"}"
            type="text" placeholder="${escapeHtml(field.description || "")}" ${field.optional ? "" : "required"}>
        </label>
      `;
    }).join("");
  return `
    <div class="upload-asset-row upload-spec-asset-row" data-asset-index="${index}">
      ${fields}
      ${spec.multipleUpload
        ? `<button class="secondary-button upload-spec-remove-asset" type="button">Remove</button>`
        : ""}
    </div>
  `;
}

function renderUploadSpecFields(repo) {
  const fields = document.getElementById("upload-fields");
  const spec = selectedUploadSpec(repo);
  if (!spec) {
    fields.innerHTML = '<div class="muted-row upload-empty">No upload definition is available.</div>';
    return;
  }
  fields.innerHTML = `
    ${(spec.componentFields || []).map(uploadSpecComponentField).join("")}
    <div class="upload-assets" id="upload-spec-assets">
      ${Array.from({ length: fieldsState.uploadAssetCount }, (_, index) => uploadSpecAssetRow(spec, index + 1)).join("")}
    </div>
    ${spec.multipleUpload
      ? '<button class="secondary-button upload-add-asset" id="upload-spec-add-asset" type="button">Add asset</button>'
      : ""}
    <label class="upload-path">
      <span>Path</span>
      <textarea id="upload-path" readonly></textarea>
    </label>
  `;
  document.querySelectorAll(".upload-spec-asset-row").forEach(bindUploadSpecAssetRow);
  syncUploadSpecRemoveButtons();
  document.getElementById("upload-spec-add-asset")?.addEventListener("click", () => {
    fieldsState.uploadAssetCount += 1;
    const assets = document.getElementById("upload-spec-assets");
    assets.insertAdjacentHTML("beforeend", uploadSpecAssetRow(spec, fieldsState.uploadAssetCount));
    bindUploadSpecAssetRow(assets.lastElementChild);
    syncUploadSpecRemoveButtons();
    updateUploadPath();
  });
}

function bindUploadSpecAssetRow(row) {
  const file = row.querySelector(".upload-spec-file");
  const filename = row.querySelector('[data-upload-asset-field="filename"]');
  file?.addEventListener("change", () => {
    if (filename && (filename.dataset.uploadAutofill === "true" || !filename.value.trim())) {
      filename.value = file.files?.[0]?.name || "";
      filename.dataset.uploadAutofill = "true";
    }
    updateUploadPath();
  });
  filename?.addEventListener("input", () => {
    filename.dataset.uploadAutofill = "false";
  });
  row.querySelector(".upload-spec-remove-asset")?.addEventListener("click", () => {
    if (document.querySelectorAll(".upload-spec-asset-row").length <= 1) return;
    row.remove();
    fieldsState.uploadAssetCount = document.querySelectorAll(".upload-spec-asset-row").length;
    syncUploadSpecRemoveButtons();
    updateUploadPath();
  });
}

function syncUploadSpecRemoveButtons() {
  const disabled = document.querySelectorAll(".upload-spec-asset-row").length <= 1;
  document.querySelectorAll(".upload-spec-remove-asset").forEach((button) => {
    button.disabled = disabled;
  });
}

function updateTerraformUploadKind() {
  const kind = uploadFieldValue("upload-terraform-kind") || "module";
  document.querySelectorAll("[data-terraform-kind]").forEach((label) => {
    const active = label.dataset.terraformKind === kind;
    label.hidden = !active;
    const input = label.querySelector("input");
    if (input) input.required = active;
  });
  updateUploadPath();
}

function mavenAssetRows() {
  return Array.from({ length: fieldsState.uploadAssetCount }, (_, index) => {
    const number = index + 1;
    return `
      <div class="upload-asset-row" data-asset-index="${number}">
        <label>
          <span>Classifier</span>
          <input class="upload-classifier" type="text">
        </label>
        <label>
          <span>Extension</span>
          <input class="upload-extension" list="upload-extension-options" type="text" value="${number === 1 ? "jar" : ""}" required>
        </label>
        <label class="upload-file">
          <span>File</span>
          <input class="upload-file-input" type="file" required>
        </label>
        <button class="secondary-button upload-remove-asset" type="button" ${fieldsState.uploadAssetCount === 1 ? "disabled" : ""}>Remove</button>
      </div>
    `;
  }).join("");
}

function conanAssetRows() {
  return Array.from({ length: fieldsState.uploadAssetCount }, (_, index) => {
    const number = index + 1;
    const suggested = number === 1
      ? "conanfile.py"
      : number === 2 ? "conanmanifest.txt" : "";
    return `
      <div class="upload-asset-row conan-upload-asset-row" data-asset-index="${number}">
        <label>
          <span>Relative path</span>
          <input class="upload-conan-filename" type="text" value="${suggested}" placeholder="conan_package.tgz">
        </label>
        <label class="upload-file">
          <span>File</span>
          <input class="upload-conan-file" type="file" required>
        </label>
        <button class="secondary-button upload-remove-asset upload-conan-remove-asset" type="button" ${fieldsState.uploadAssetCount === 1 ? "disabled" : ""}>Remove</button>
      </div>
    `;
  }).join("");
}

function bindConanAssetRows() {
  document.querySelectorAll(".upload-conan-filename, .upload-conan-file").forEach((input) => {
    input.addEventListener("input", updateUploadPath);
    input.addEventListener("change", updateUploadPath);
  });
  document.querySelectorAll(".upload-conan-remove-asset").forEach((button) => {
    button.addEventListener("click", () => {
      if (fieldsState.uploadAssetCount <= 1) return;
      fieldsState.uploadAssetCount -= 1;
      document.getElementById("upload-conan-assets").innerHTML = conanAssetRows();
      bindConanAssetRows();
      updateUploadPath();
    });
  });
}

function bindRemoveAssetButtons() {
  document.querySelectorAll(".upload-remove-asset").forEach((button) => {
    button.addEventListener("click", () => {
      if (fieldsState.uploadAssetCount <= 1) return;
      fieldsState.uploadAssetCount -= 1;
      document.getElementById("upload-assets").innerHTML = mavenAssetRows();
      bindRemoveAssetButtons();
      updateUploadPath();
    });
  });
}

function uploadFieldValue(id) {
  const node = document.getElementById(id);
  return node ? node.value.trim() : "";
}

export {
  fieldsState,
  renderUpload,
  renderUploadFields,
  uploadFieldValue,
  uploadSpecFieldLabel,
};
