import { MINIMUM_RELEASE_AGE_FORMATS, proxyCommand, proxyFormValues } from "./proxy-form.js";
import * as proxySettings from "../proxy-settings.js";
import * as routingRules from "../routing-rules.js";
import { escapeHtml } from "../controls/display.js";
import {
  clearRequiredFieldErrors,
  markInputValidity,
  setFieldRequired,
  textInputValue,
  updateRequiredMarkers,
  validateRequiredFields,
} from "../controls/fields.js";
import { closeFormModal, openFormModal } from "../controls/modal.js";
import { showToast } from "../controls/toast.js";
import {
  conanManifestDefaults,
  conanManifestFields,
  loadConanStatus,
  repositoryConanOverrides,
  setConanManifestSettings,
} from "./conan.js";
import { listState, loadRepositories } from "./list.js";
import { memberTransfer, refreshRepositoryMemberOptions } from "./members.js";
import {
  closeRepositoryRecipeCombobox,
  currentRecipe,
  repositoryRecipes,
  syncRepositoryRecipeCombobox,
} from "./recipes.js";
import { loadAlpineStatus, loadAptStatus, refreshAlpineControls, refreshAptControls } from "./signing.js";
import { persistRepository, removeRepository } from "./api.js";
import { blobStores } from "../storage/blob-stores.js";

let repositoryFormMode = "create";

let editingRepositoryName = null;

let editingRepositoryBlobStoreName = null;

const repositoryRequiredFields = [
  {
    id: "repository-recipe",
    label: "Recipe",
    required: () => repositoryFormMode === "create"
  },
  { id: "repository-name", label: "Name" },
  { id: "repository-blobstore", label: "Blob store", required: () => repositoryFormMode === "create" },
  {
    id: "repository-remote-url",
    label: "Remote URL",
    required: () => currentRecipe()?.type === "PROXY"
  },
  {
    id: "repository-docker-connector-port",
    label: "Connector port",
    required: () => currentRecipe()?.format === "docker"
        && document.getElementById("repository-docker-connector-enabled").checked
  },
  {
    id: "repository-docker-https-port",
    label: "HTTPS connector port",
    required: () => currentRecipe()?.format === "docker"
        && document.getElementById("repository-docker-https-enabled").checked
  },
  {
    id: "repository-apt-distribution",
    label: "APT distribution",
    required: () => currentRecipe()?.format === "apt"
        && (currentRecipe()?.type === "HOSTED"
          || document.getElementById("repository-apt-enforce-distribution").checked)
  },
  {
    id: "repository-apt-component",
    label: "APT component",
    required: () => currentRecipe()?.format === "apt"
  },
  {
    id: "repository-apt-architectures",
    label: "APT architectures",
    required: () => currentRecipe()?.format === "apt"
  },
  {
    id: "repository-alpine-distributions",
    label: "Alpine distributions",
    required: () => currentRecipe()?.format === "alpine"
  },
  {
    id: "repository-alpine-channels",
    label: "Alpine channels",
    required: () => currentRecipe()?.format === "alpine"
  },
  {
    id: "repository-alpine-architectures",
    label: "Alpine architectures",
    required: () => currentRecipe()?.format === "alpine"
  }
];

function refreshRepositoryBlobStoreOptions() {
  const select = document.getElementById("repository-blobstore");
  const previous = select.value;
  const names = blobStores
    .filter((store) => store.id != null)
    .map((store) => store.name);
  if (repositoryFormMode === "edit"
      && editingRepositoryBlobStoreName
      && !names.includes(editingRepositoryBlobStoreName)) {
    names.unshift(editingRepositoryBlobStoreName);
  }
  const options = names
    .map((name) =>
      `<option value="${escapeHtml(name)}">${escapeHtml(name)}</option>`)
    .join("");
  select.innerHTML = options || '<option value="">No blob stores</option>';
  if (previous && names.includes(previous)) {
    select.value = previous;
  }
  refreshRepositoryBlobStoreLock();
}

function refreshRepositoryBlobStoreLock() {
  const select = document.getElementById("repository-blobstore");
  const locked = repositoryFormMode === "edit";
  select.disabled = locked;
  select.title = locked ? "Blob store is fixed after repository creation." : "";
  updateRequiredMarkers(repositoryRequiredFields);
}

function refreshRepositoryRecipeControls() {
  const recipe = currentRecipe();
  const type = recipe ? recipe.type : null;
  const format = recipe ? recipe.format : null;
  const strict = document.getElementById("repository-strict");
  strict.disabled = format === "gitlfs";
  if (format === "gitlfs") strict.checked = false;
  document.getElementById("repository-hosted-fields").hidden = type !== "HOSTED";
  document.getElementById("repository-proxy-fields").hidden = type !== "PROXY";
  document.getElementById("repository-block-outbound-field").hidden = type !== "PROXY";
  const ntlmAvailable = type === "PROXY" && format === "nuget";
  document.getElementById("repository-remote-authentication-field").hidden = !ntlmAvailable;
  if (!ntlmAvailable) document.getElementById("repository-remote-authentication-type").value = "auto";
  const ntlmSelected = ntlmAvailable && document.getElementById("repository-remote-authentication-type").value === "ntlm";
  document.getElementById("repository-remote-ntlm-domain-field").hidden = !ntlmSelected;
  document.getElementById("repository-remote-ntlm-host-field").hidden = !ntlmSelected;
  document.getElementById("repository-outbound-proxy-fields").hidden = type !== "PROXY";
  proxySettings?.refreshRepositoryForm();
  document.getElementById("repository-group-fields").hidden = type !== "GROUP";
  document.getElementById("repository-raw-fields").hidden = format !== "raw";
  document.getElementById("repository-docker-fields").hidden = format !== "docker";
  document.getElementById("repository-cargo-fields").hidden =
    format !== "cargo";
  document.getElementById("repository-conan-limits").hidden = format !== "conan" || type === "GROUP";
  document.getElementById("repository-conan-fields").hidden =
    format !== "conan" || repositoryFormMode !== "edit";
  document.getElementById("repository-apt-fields").hidden = format !== "apt";
  document.getElementById("repository-alpine-fields").hidden = format !== "alpine";
  document.getElementById("repository-swift-proxy-help").hidden =
    !(format === "swift" && type === "PROXY");
  document.getElementById("repository-yum-depth-field").hidden = format !== "yum" || type !== "HOSTED";
  const pypiIndexPathVisible = format === "pypi" && type === "PROXY";
  document.getElementById("repository-pypi-index-path-field").hidden =
    !pypiIndexPathVisible;
  const minimumReleaseAgeVisible = MINIMUM_RELEASE_AGE_FORMATS.has(format) && type === "PROXY";
  document.getElementById("repository-minimum-release-age-field").hidden =
    !minimumReleaseAgeVisible;
  refreshDockerConnectorControls();
  refreshAptControls();
  refreshAlpineControls();
  document.getElementById("repository-blobstore").closest("label").hidden = false;
  refreshRepositoryBlobStoreLock();
  document.querySelectorAll("#repository-hosted-fields .maven-only").forEach((el) => {
    el.hidden = format !== "maven2";
  });
  refreshRepositoryRemoteDefaults(recipe);
  if (type === "GROUP") refreshRepositoryMemberOptions();
  updateRequiredMarkers(repositoryRequiredFields);
  syncRepositoryRecipeCombobox();
}

function refreshRepositoryRemoteDefaults(recipe) {
  const remote = document.getElementById("repository-remote-url");
  remote.readOnly = Boolean(recipe?.type === "PROXY" && recipe?.format === "swift");
  if (!recipe || recipe.type !== "PROXY") return;
  const defaults = {
    maven2: "https://repo.maven.apache.org/maven2/",
    npm: "https://registry.npmjs.org/",
    pypi: "https://pypi.org/",
    helm: "https://charts.bitnami.com/bitnami",
    nuget: "https://api.nuget.org/v3/index.json",
    rubygems: "https://rubygems.org/",
    yum: "https://download.fedoraproject.org/pub/fedora/linux/",
    raw: "https://example.com/",
    docker: "https://registry-1.docker.io/",
    cargo: "https://index.crates.io/",
    pub: "https://pub.dev/",
    composer: "https://repo.packagist.org/",
    terraform: "https://registry.terraform.io/",
    swift: "https://github.com/",
    ansiblegalaxy: "https://galaxy.ansible.com/",
    conda: "https://repo.anaconda.com/pkgs/main/",
    conan: "https://center2.conan.io/",
    apt: "https://deb.debian.org/debian/",
    alpine: "https://dl-cdn.alpinelinux.org/alpine/",
    r: "https://cloud.r-project.org/",
    huggingface: "https://huggingface.co/"
  };
  if (recipe.format === "swift") {
    remote.value = defaults.swift;
  }
  remote.placeholder = defaults[recipe.format] || "https://example.com/";
  if (repositoryFormMode === "create" && !remote.value.trim() && defaults[recipe.format]) {
    remote.value = defaults[recipe.format];
  }
}

function repositoryFormPayload() {
  const recipe = currentRecipe();
  const type = recipe ? recipe.type : null;
  const payload = {
    name: document.getElementById("repository-name").value.trim(),
    recipe: document.getElementById("repository-recipe").value,
    online: document.getElementById("repository-online").checked,
    strictContentTypeValidation: document.getElementById("repository-strict").checked
  };
  if (repositoryFormMode === "create") {
    payload.blobStoreName = document.getElementById("repository-blobstore").value || null;
  }
  if (type === "HOSTED") {
    payload.hosted = {
      writePolicy: document.getElementById("repository-write-policy").value,
      versionPolicy: recipe.format === "maven2" ? document.getElementById("repository-version-policy").value : null,
      layoutPolicy: recipe.format === "maven2" ? document.getElementById("repository-layout-policy").value : null
    };
  } else if (type === "PROXY") {
    const routing = document.getElementById("repository-routing-rule");
    Object.assign(payload, proxyCommand(recipe.format, {
      routingRuleName: routing.disabled ? undefined : routing.value,
      remoteUrl: document.getElementById("repository-remote-url").value,
      allowedRedirectHosts: document.getElementById("repository-allowed-redirect-hosts").value,
      contentMaxAgeMinutes: document.getElementById("repository-content-max-age").value,
      metadataMaxAgeMinutes: document.getElementById("repository-metadata-max-age").value,
      minimumReleaseAgeMinutes: document.getElementById("repository-minimum-release-age").value,
      autoBlock: document.getElementById("repository-auto-block").checked,
      blockOutboundConnections: document.getElementById("repository-block-outbound").checked,
      streamingEnabled: document.getElementById("repository-streaming-enabled").checked,
      remoteAuthenticationType: textInputValue("repository-remote-authentication-type"),
      remoteNtlmDomain: textInputValue("repository-remote-ntlm-domain"),
      remoteNtlmHost: textInputValue("repository-remote-ntlm-host"),
      remoteUsername: textInputValue("repository-remote-username"),
      remotePassword: textInputValue("repository-remote-password"),
      clearPassword: document.getElementById("repository-remote-password-clear").checked,
      remoteBearerToken: textInputValue("repository-remote-bearer-token"),
      clearBearerToken: document.getElementById("repository-remote-bearer-token-clear").checked,
    }, proxySettings.repositoryPayload()));
    if (recipe.format === "pypi") {
      payload.pypi = {
        indexPath: document.getElementById("repository-pypi-index-path").value.trim()
      };
    }
  } else if (type === "GROUP") {
    payload.group = {
      memberNames: [...memberTransfer.selected]
    };
  }
  if (recipe?.format === "conan" && type !== "GROUP") {
    // Display values and durable nullable overrides are deliberately separate.
    if (conanManifestDefaults) payload.conan = { ...repositoryConanOverrides };
  }
  if (recipe?.format === "yum" && type === "HOSTED") {
    payload.yum = { repodataDepth: Number(document.getElementById("repository-yum-depth").value) };
  }
  if (recipe?.format === "raw") {
    payload.raw = { contentDisposition: document.getElementById("repository-raw-content-disposition").value };
  }
  if (recipe?.format === "docker") {
    const connectorPort = document.getElementById("repository-docker-connector-port").value;
    payload.docker = {
      connectorEnabled: document.getElementById("repository-docker-connector-enabled").checked,
      connectorPort: connectorPort === "" ? null : Number(connectorPort),
      connectorPublicUrl: textInputValue("repository-docker-connector-public-url"),
      httpsConnectorEnabled: document.getElementById("repository-docker-https-enabled").checked,
      httpsConnectorPort: document.getElementById("repository-docker-https-port").value === "" ? null : Number(document.getElementById("repository-docker-https-port").value),
      tlsBundle: document.getElementById("repository-docker-tls-bundle").value.trim()
    };
  }
  if (recipe?.format === "cargo") {
    payload.cargo = {
      requireAuthentication: document.getElementById("repository-cargo-require-authentication").checked
    };
  }
  if (recipe?.format === "apt") {
    const architectures = document.getElementById("repository-apt-architectures").value
      .split(",")
      .map((value) => value.trim())
      .filter(Boolean);
    const validUntil = document.getElementById("repository-apt-valid-until-days").value;
    payload.apt = {
      distribution: document.getElementById("repository-apt-distribution").value.trim(),
      component: document.getElementById("repository-apt-component").value.trim(),
      architectures,
      flat: document.getElementById("repository-apt-flat").checked,
      enforceDistribution: document.getElementById("repository-apt-enforce-distribution").checked,
      metadataMode: document.getElementById("repository-apt-metadata-mode").value,
      validUntilDays: validUntil === "" ? 0 : Number(validUntil),
      origin: document.getElementById("repository-apt-origin").value.trim(),
      label: document.getElementById("repository-apt-label").value.trim()
    };
  }
  if (recipe?.format === "alpine") {
    const list = (id) => document.getElementById(id).value
      .split(",").map((value) => value.trim()).filter(Boolean);
    const upstreamKey = document.getElementById(
      "repository-alpine-upstream-public-key").value.trim();
    payload.alpine = {
      distributions: list("repository-alpine-distributions"),
      channels: list("repository-alpine-channels"),
      architectures: list("repository-alpine-architectures"),
      metadataMode: document.getElementById("repository-alpine-metadata-mode").value,
      verifyUpstreamSignatures:
        document.getElementById("repository-alpine-verify-upstream").checked,
      staleIfError: document.getElementById("repository-alpine-stale-if-error").checked,
      keyFilename: document.getElementById("repository-alpine-key-filename").value.trim(),
      signatureType: document.getElementById("repository-alpine-signature-type").value,
      description: document.getElementById("repository-alpine-description").value.trim(),
      upstreamPublicKeys: upstreamKey ? [upstreamKey] : []
    };
  }
  return payload;
}

function setRepositoryFormDefaults() {
  setConanManifestSettings();
  document.getElementById("repository-name").value = "";
  document.getElementById("repository-online").checked = true;
  document.getElementById("repository-strict").checked = true;
  document.getElementById("repository-write-policy").value = "ALLOW_ONCE";
  document.getElementById("repository-version-policy").value = "RELEASE";
  document.getElementById("repository-layout-policy").value = "STRICT";
  document.getElementById("repository-remote-url").value = "";
  document.getElementById("repository-yum-depth").value = "0";
  document.getElementById("repository-pypi-index-path").value = "/simple";
  document.getElementById("repository-allowed-redirect-hosts").value = "";
  document.getElementById("repository-remote-authentication-type").value = "auto";
  document.getElementById("repository-remote-ntlm-domain").value = "";
  document.getElementById("repository-remote-ntlm-host").value = "";
  document.getElementById("repository-remote-username").value = "";
  document.getElementById("repository-remote-password").value = "";
  document.getElementById("repository-remote-password").placeholder = "";
  document.getElementById("repository-remote-password-clear").checked = false;
  document.getElementById("repository-remote-bearer-token").value = "";
  document.getElementById("repository-remote-bearer-token").placeholder = "";
  document.getElementById("repository-remote-bearer-token-clear").checked = false;
  proxySettings.resetRepositoryForm();
  document.getElementById("repository-content-max-age").value = "1440";
  document.getElementById("repository-metadata-max-age").value = "1440";
  document.getElementById("repository-minimum-release-age").value = "0";
  document.getElementById("repository-auto-block").checked = true;
  document.getElementById("repository-block-outbound").checked = false;
  document.getElementById("repository-streaming-enabled").checked = false;
  document.getElementById("repository-raw-content-disposition").value = "ATTACHMENT";
  document.getElementById("repository-docker-https-enabled").checked = false;
  document.getElementById("repository-docker-https-port").value = "";
  document.getElementById("repository-docker-tls-bundle").value = "";
  document.getElementById("repository-docker-connector-enabled").checked = false;
  document.getElementById("repository-docker-connector-port").value = "";
  document.getElementById("repository-docker-connector-public-url").value = "";
  document.getElementById("repository-cargo-require-authentication").checked = false;
  document.getElementById("repository-apt-distribution").value = "stable";
  document.getElementById("repository-apt-component").value = "main";
  document.getElementById("repository-apt-architectures").value = "amd64";
  document.getElementById("repository-apt-flat").checked = false;
  document.getElementById("repository-apt-enforce-distribution").checked = false;
  document.getElementById("repository-apt-metadata-mode").value = "PASSTHROUGH";
  document.getElementById("repository-apt-valid-until-days").value = "30";
  document.getElementById("repository-apt-origin").value = "kkRepo";
  document.getElementById("repository-apt-label").value = "kkRepo";
  document.getElementById("repository-apt-private-key").value = "";
  document.getElementById("repository-apt-key-passphrase").value = "";
  document.getElementById("repository-apt-status").textContent = "APT status not loaded.";
  document.getElementById("repository-alpine-distributions").value = "v3.23";
  document.getElementById("repository-alpine-channels").value = "main";
  document.getElementById("repository-alpine-architectures").value = "x86_64, aarch64";
  document.getElementById("repository-alpine-metadata-mode").value = "PASSTHROUGH";
  document.getElementById("repository-alpine-verify-upstream").checked = false;
  document.getElementById("repository-alpine-stale-if-error").checked = true;
  document.getElementById("repository-alpine-key-filename").value = "kkrepo-alpine.rsa.pub";
  document.getElementById("repository-alpine-signature-type").value = "RSA";
  document.getElementById("repository-alpine-description").value = "kkRepo Alpine repository";
  document.getElementById("repository-alpine-upstream-public-key").value = "";
  document.getElementById("repository-alpine-private-key").value = "";
  document.getElementById("repository-alpine-status").textContent = "Alpine status not loaded.";
  memberTransfer.selected = [];
  memberTransfer.highlight.available.clear();
  memberTransfer.highlight.selected.clear();
  memberTransfer.filter = "";
  const filterInput = document.getElementById("member-filter");
  if (filterInput) filterInput.value = "";
  refreshDockerConnectorControls();
}

function refreshDockerConnectorControls() {
  const enabled = document.getElementById("repository-docker-connector-enabled").checked;
  const portInput = document.getElementById("repository-docker-connector-port");
  portInput.disabled = !enabled;
  setFieldRequired(portInput, currentRecipe()?.format === "docker" && enabled);
  const httpsEnabled = document.getElementById("repository-docker-https-enabled").checked;
  const httpsPort = document.getElementById("repository-docker-https-port");
  httpsPort.disabled = !httpsEnabled;
  setFieldRequired(httpsPort, currentRecipe()?.format === "docker" && httpsEnabled);
  document.getElementById("repository-docker-tls-bundle").disabled = !httpsEnabled;
  document.getElementById("repository-docker-connector-public-url").disabled = !enabled && !httpsEnabled;
  updateRequiredMarkers(repositoryRequiredFields);
}

function showCreateRepositoryForm() {
  repositoryFormMode = "create";
  editingRepositoryName = null;
  editingRepositoryBlobStoreName = null;
  routingRules.loadOptions();
  document.getElementById("repository-form-title").textContent = "Create repository";
  document.getElementById("save-repository-button").textContent = "Create repository";
  document.getElementById("repository-name").disabled = false;
  document.getElementById("repository-recipe").disabled = false;
  document.getElementById("repository-blobstore").disabled = false;
  document.getElementById("repository-blobstore").title = "";
  setRepositoryFormDefaults();
  if (repositoryRecipes.length > 0) {
    document.getElementById("repository-recipe").value = repositoryRecipes[0].name;
  }
  refreshRepositoryBlobStoreOptions();
  refreshRepositoryRecipeControls();
  clearRequiredFieldErrors(repositoryRequiredFields);
  openFormModal("repository-form", "repository-name");
}

function showEditRepositoryForm(name) {
  const repo = listState.repositories.find((r) => r.name === name);
  if (!repo) {
    showToast("Repository no longer exists. Refresh.", "error");
    return;
  }
  repositoryFormMode = "edit";
  editingRepositoryName = repo.name;
  routingRules.loadOptions(repo.routingRuleName || "");
  editingRepositoryBlobStoreName = repo.blobStoreName || null;
  document.getElementById("repository-form-title").textContent = `Edit repository: ${repo.name}`;
  document.getElementById("save-repository-button").textContent = "Save changes";
  document.getElementById("repository-name").disabled = true;
  document.getElementById("repository-recipe").disabled = true;
  setRepositoryFormDefaults();
  document.getElementById("repository-name").value = repo.name;
  document.getElementById("repository-recipe").value = repo.recipe;
  document.getElementById("repository-online").checked = Boolean(repo.online);
  document.getElementById("repository-strict").checked = Boolean(repo.strictContentTypeValidation);
  document.getElementById("repository-raw-content-disposition").value = repo.raw?.contentDisposition || "ATTACHMENT";
  refreshRepositoryBlobStoreOptions();
  if (repo.blobStoreName) {
    document.getElementById("repository-blobstore").value = repo.blobStoreName;
  }
  if (repo.hosted) {
    if (repo.hosted.writePolicy) document.getElementById("repository-write-policy").value = repo.hosted.writePolicy;
    if (repo.hosted.versionPolicy) document.getElementById("repository-version-policy").value = repo.hosted.versionPolicy;
    if (repo.hosted.layoutPolicy) document.getElementById("repository-layout-policy").value = repo.hosted.layoutPolicy;
  }
  if (repo.proxy) {
    const fields = proxyFormValues(repo.proxy);
    document.getElementById("repository-remote-url").value = fields.remoteUrl;
    document.getElementById("repository-allowed-redirect-hosts").value =
      fields.allowedRedirectHosts;
    document.getElementById("repository-remote-authentication-type").value = fields.remoteAuthenticationType;
    document.getElementById("repository-remote-ntlm-domain").value = fields.remoteNtlmDomain;
    document.getElementById("repository-remote-ntlm-host").value = fields.remoteNtlmHost;
    document.getElementById("repository-remote-username").value = fields.remoteUsername;
    document.getElementById("repository-remote-password").value = "";
    document.getElementById("repository-remote-password").placeholder =
      repo.proxy.remotePasswordConfigured ? "Saved password unchanged" : "";
    document.getElementById("repository-remote-password-clear").checked = false;
    document.getElementById("repository-remote-bearer-token").value = "";
    document.getElementById("repository-remote-bearer-token").placeholder =
      repo.proxy.remoteBearerTokenConfigured ? "Saved bearer token unchanged" : "";
    document.getElementById("repository-remote-bearer-token-clear").checked = false;
    // Normalize legacy/alias values stored before the backend canonicalized the type,
    // so the select (HTTP/SOCKS only) still shows the effective choice.
    proxySettings.resetRepositoryForm(repo.proxy);
    document.getElementById("repository-content-max-age").value = fields.contentMaxAgeMinutes;
    document.getElementById("repository-metadata-max-age").value = fields.metadataMaxAgeMinutes;
    document.getElementById("repository-minimum-release-age").value =
      fields.minimumReleaseAgeMinutes;
    document.getElementById("repository-auto-block").checked = fields.autoBlock;
    document.getElementById("repository-block-outbound").checked = fields.blockOutboundConnections;
    document.getElementById("repository-streaming-enabled").checked = fields.streamingEnabled;
  }
  document.getElementById("repository-yum-depth").value = repo.yum?.repodataDepth ?? 0;
  setConanManifestSettings(repo.conan);
  document.getElementById("repository-pypi-index-path").value =
    repo.pypi?.indexPath ?? "/simple";
  if (repo.type === "GROUP" && repo.group && Array.isArray(repo.group.memberNames)) {
    memberTransfer.selected = [...repo.group.memberNames];
  }
  if (repo.docker) {
    document.getElementById("repository-docker-https-enabled").checked = Boolean(repo.docker.httpsConnectorEnabled);
    document.getElementById("repository-docker-https-port").value = repo.docker.httpsConnectorPort ?? "";
    document.getElementById("repository-docker-tls-bundle").value = repo.docker.tlsBundle || "";
    document.getElementById("repository-docker-connector-enabled").checked = Boolean(repo.docker.connectorEnabled);
    document.getElementById("repository-docker-connector-port").value = repo.docker.connectorPort ?? "";
    document.getElementById("repository-docker-connector-public-url").value = repo.docker.connectorPublicUrl || "";
  }
  if (repo.cargo) {
    document.getElementById("repository-cargo-require-authentication").checked =
      Boolean(repo.cargo.requireAuthentication);
  }
  if (repo.apt) {
    document.getElementById("repository-apt-distribution").value = repo.apt.distribution || "";
    document.getElementById("repository-apt-component").value = repo.apt.component || "main";
    document.getElementById("repository-apt-architectures").value =
      Array.isArray(repo.apt.architectures) ? repo.apt.architectures.join(", ") : "amd64";
    document.getElementById("repository-apt-flat").checked = Boolean(repo.apt.flat);
    document.getElementById("repository-apt-enforce-distribution").checked =
      Boolean(repo.apt.enforceDistribution);
    document.getElementById("repository-apt-metadata-mode").value =
      repo.apt.metadataMode || (repo.type === "HOSTED" ? "RESIGN" : "PASSTHROUGH");
    document.getElementById("repository-apt-valid-until-days").value =
      repo.apt.validUntilDays ?? "0";
    document.getElementById("repository-apt-origin").value = repo.apt.origin || "kkRepo";
    document.getElementById("repository-apt-label").value = repo.apt.label || "kkRepo";
  }
  if (repo.alpine) {
    document.getElementById("repository-alpine-distributions").value =
      Array.isArray(repo.alpine.distributions) ? repo.alpine.distributions.join(", ") : "v3.23";
    document.getElementById("repository-alpine-channels").value =
      Array.isArray(repo.alpine.channels) ? repo.alpine.channels.join(", ") : "main";
    document.getElementById("repository-alpine-architectures").value =
      Array.isArray(repo.alpine.architectures)
        ? repo.alpine.architectures.join(", ") : "x86_64, aarch64";
    document.getElementById("repository-alpine-metadata-mode").value =
      repo.alpine.metadataMode || (repo.type === "PROXY" ? "PASSTHROUGH" : "RESIGN");
    document.getElementById("repository-alpine-verify-upstream").checked =
      Boolean(repo.alpine.verifyUpstreamSignatures);
    document.getElementById("repository-alpine-stale-if-error").checked =
      repo.alpine.staleIfError !== false;
    document.getElementById("repository-alpine-key-filename").value =
      repo.alpine.keyFilename || `${repo.name}.rsa.pub`;
    document.getElementById("repository-alpine-signature-type").value =
      repo.alpine.signatureType || "RSA";
    document.getElementById("repository-alpine-description").value =
      repo.alpine.description || "kkRepo Alpine repository";
    document.getElementById("repository-alpine-upstream-public-key").value =
      Array.isArray(repo.alpine.upstreamPublicKeys)
        ? (repo.alpine.upstreamPublicKeys[0] || "") : "";
  }
  refreshRepositoryRecipeControls();
  if (repo.format === "conan") loadConanStatus(repo.name);
  if (repo.format === "apt") loadAptStatus(repo.name);
  if (repo.format === "alpine") loadAlpineStatus(repo.name);
  clearRequiredFieldErrors(repositoryRequiredFields);
  openFormModal("repository-form", "repository-online");
}

function hideRepositoryForm() {
  closeRepositoryRecipeCombobox();
  repositoryFormMode = "create";
  editingRepositoryName = null;
  editingRepositoryBlobStoreName = null;
  document.getElementById("repository-blobstore").disabled = false;
  document.getElementById("repository-blobstore").title = "";
  clearRequiredFieldErrors(repositoryRequiredFields);
  closeFormModal("repository-form");
}

async function saveRepository() {
  const recipe = currentRecipe();
  if (!recipe) {
    markInputValidity(document.getElementById("repository-recipe"), true);
    showToast("Pick a recipe before saving.", "error");
    document.getElementById("repository-recipe-trigger").focus();
    return;
  }
  if (!validateRequiredFields(repositoryRequiredFields)) {
    return;
  }
  if (recipe.format === "conan" && recipe.type !== "GROUP" && conanManifestDefaults) {
    for (const field of conanManifestFields) {
      const input = document.getElementById(field.id);
      if (!input.reportValidity()) return;
    }
  }
  const payload = repositoryFormPayload();
  const creating = repositoryFormMode === "create";
  showToast(creating ? "Creating repository..." : "Saving repository...");
  try {
    await persistRepository(creating ? null : editingRepositoryName, payload);
    await loadRepositories();
    hideRepositoryForm();
    showToast(creating ? "Repository created." : "Repository updated.", "ok");
  } catch (error) {
    showToast(`Save failed: ${error.message}`, "error");
  }
}

async function deleteRepository(name) {
  if (!await window.kkrepoConfirmDelete({
    title: "Delete repository?",
    description: "The repository configuration and its repository-specific privileges will be removed. Repositories that still contain components cannot be deleted.",
    details: [{ label: "Repository", value: name }],
    confirmationText: name
  })) return;
  showToast(`Deleting ${name}...`);
  try {
    await removeRepository(name);
    await loadRepositories();
    showToast(`Repository ${name} deleted.`, "ok");
  } catch (error) {
    showToast(`Delete failed: ${error.message}`, "error");
  }
}

export {
  deleteRepository,
  editingRepositoryName,
  hideRepositoryForm,
  refreshDockerConnectorControls,
  refreshRepositoryBlobStoreOptions,
  refreshRepositoryRecipeControls,
  repositoryFormMode,
  repositoryRequiredFields,
  saveRepository,
  showCreateRepositoryForm,
  showEditRepositoryForm,
};
