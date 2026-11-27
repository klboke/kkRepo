import { closeCleanupPolicyActionMenu } from "../cleanup/policies.js";
import { renderCopyableLocation } from "../controls/clipboard.js";
import { escapeHtml, lowerOrEmpty, lucideIcon } from "../controls/display.js";
import { setFieldRequired, updateRequiredMarker } from "../controls/fields.js";
import { closeFormModal, openFormModal } from "../controls/modal.js";
import { updateTableSortHeaders } from "../controls/table.js";
import { showToast } from "../controls/toast.js";
import { refreshRepositoryBlobStoreOptions } from "../repositories/form.js";
import { listState } from "../repositories/list.js";
import { responseErrorMessage } from "../shell/http.js";
import { switchView } from "../shell/routes.js";
import { fetchInventoryUsage, inventoryValue, renderInventoryMetric, renderUsageSummary } from "./usage.js";

let blobStores = [];

let blobStoreUsage;

let blobStoreLoadVersion = 0;

let blobStoreSort = { key: "name", direction: "asc" };

let blobActionMenuStoreId = null;

let blobActionMenuTrigger = null;

let blobStoreHealth = {};

let blobStoreFormMode = "create";

let editingBlobStoreId = null;

const blobStoreS3RequiredFields = [
  { id: "blobstore-name", label: "Name" },
  { id: "blobstore-endpoint", label: "Endpoint" },
  { id: "blobstore-region", label: "Region" },
  { id: "blobstore-bucket", label: "Bucket" },
  { id: "blobstore-access-key", label: "Access key" },
  { id: "blobstore-secret-key", label: "Secret key" }
];

const blobStoreFileRequiredFields = [
  { id: "blobstore-name", label: "Name" },
  { id: "blobstore-path", label: "Path" }
];

const blobStoreAzureRequiredFields = [
  { id: "blobstore-name", label: "Name" },
  { id: "blobstore-account-name", label: "Account name" },
  { id: "blobstore-bucket", label: "Container" },
  { id: "blobstore-azure-account-key", label: "Account key" },
  { id: "blobstore-azure-sas-token", label: "SAS token" }
];

const blobStoreAzureFields = [
  { id: "blobstore-account-name", label: "Account name" },
  { id: "blobstore-azure-client-id", label: "Client ID" },
  { id: "blobstore-azure-tenant-id", label: "Tenant ID" },
  { id: "blobstore-azure-token-file", label: "Token file path" },
  { id: "blobstore-azure-account-key", label: "Account key" },
  { id: "blobstore-azure-sas-token", label: "SAS token" }
];

// Pre-filled for new S3 stores only; it is never a valid Azure endpoint for token credentials.
const blobStoreS3DefaultEndpoint = "http://127.0.0.1:9000";

const blobStoreFormFields = [
  ...blobStoreS3RequiredFields,
  { id: "blobstore-path", label: "Path" },
  ...blobStoreAzureFields
];

function blobStoreIcon(type) {
  return lucideIcon("database", `repo-icon ${type === "s3" ? "proxy" : "hosted"}`);
}

function pathStyleBadge(enabled) {
  const label = enabled ? "Enabled" : "Disabled";
  const tone = enabled ? "ok" : "warn";
  return `<span class="state-badge compact ${tone}">${label}</span>`;
}

function engineLabel(engine) {
  if (engine === "file") return "File";
  if (engine === "oss-native") return "OSS Native SDK";
  if (lowerOrEmpty(engine).startsWith("azure")) return "Azure Blob Storage";
  return "AWS S3 SDK";
}

function isFileBlobStore(store) {
  return lowerOrEmpty(store?.engine) === "file" || lowerOrEmpty(store?.type) === "file";
}

function isAzureBlobStore(store) {
  return lowerOrEmpty(store?.type).startsWith("azure") || lowerOrEmpty(store?.engine).startsWith("azure");
}

function healthBadge(store) {
  const health = blobStoreHealth[String(store.id)] || initialBlobStoreHealth(store);
  const detail = health.message
    ? `<span class="health-detail" title="${escapeHtml(health.message)}">${escapeHtml(health.message)}</span>`
    : "";
  return `<span class="state-badge ${health.tone}">${escapeHtml(health.status)}</span>${detail}`;
}

function initialBlobStoreHealth(store) {
  if (store.id == null) {
    return {
      status: "Not saved",
      tone: "warn",
      message: "Persist configuration before checking."
    };
  }
  return {
    status: "Checking",
    tone: "checking",
    message: "Running health check..."
  };
}

function filteredBlobStores() {
  const filter = document.getElementById("blobstore-filter").value.trim().toLowerCase();
  return blobStores.filter((store) => {
    if (!filter) return true;
    const health = blobStoreHealth[String(store.id)] || initialBlobStoreHealth(store);
    return `${store.name} ${store.type} ${store.engine} ${health.status} ${health.message} ${store.bucket} ${store.prefix} ${store.endpoint} ${store.path} ${store.resolvedPath} ${store.pathStyleAccess ? "path style" : "virtual host"}`
      .toLowerCase()
      .includes(filter);
  });
}

function blobStoreSortValue(store, key) {
  return key === "name" ? store.name || "" : inventoryValue(blobStoreUsage, store.id, key);
}

function sortBlobStores(rows) {
  return [...rows].sort((a, b) => {
    const left = blobStoreSortValue(a, blobStoreSort.key);
    const right = blobStoreSortValue(b, blobStoreSort.key);
    if (left == null || right == null) return left == null ? (right == null ? 0 : 1) : -1;
    const primary = typeof left === "bigint" ? (left < right ? -1 : left > right ? 1 : 0) : left.localeCompare(right, undefined, { numeric: true, sensitivity: "base" });
    const fallback = (a.name || "").localeCompare(b.name || "", undefined, { numeric: true, sensitivity: "base" });
    return blobStoreSort.direction === "asc" ? primary || fallback : -(primary || fallback);
  });
}

function toggleBlobStoreSort(key) {
  blobStoreSort = {
    key,
    direction: blobStoreSort.key === key ? (blobStoreSort.direction === "asc" ? "desc" : "asc")
      : key === "name" ? "asc" : "desc",
  };
  renderBlobStores();
}

function renderBlobStores() {
  const actionMenu = document.getElementById("blobstore-action-menu");
  const focusedTriggerId = actionMenu?.contains(document.activeElement)
    ? actionMenu.getAttribute("aria-labelledby")
    : document.activeElement?.closest?.(".blobstore-more-actions")?.id;
  closeBlobStoreActionMenu();
  updateTableSortHeaders("blobstore", blobStoreSort);
  const filtered = filteredBlobStores();
  renderUsageSummary("blobstore", filtered, blobStoreUsage);
  const rows = sortBlobStores(filtered);
  document.getElementById("blobstore-table").innerHTML = rows.map((store) => {
    const fileStore = isFileBlobStore(store);
    const azureStore = isAzureBlobStore(store);
    const target = fileStore ? store.path : store.bucket;
    // Azure derives its endpoint from the account when none is stored, so fall back to the account.
    const origin = azureStore && !store.endpoint ? store.accountName : store.endpoint;
    const detail = fileStore ? store.resolvedPath : [origin, store.prefix].filter(Boolean).join(" / ");
    return `
      <tr>
        <td class="icon-column">${blobStoreIcon(store.type)}</td>
        <td>${escapeHtml(store.name)}</td>
        <td>${engineLabel(store.engine)}${fileStore ? "" : `<small class="usage-secondary">${escapeHtml(store.type)}</small>`}</td>
        <td>${healthBadge(store)}</td>
        <td class="inventory-count">${renderInventoryMetric(blobStoreUsage, store.id, "blobCount")}</td>
        <td class="inventory-count">${renderInventoryMetric(blobStoreUsage, store.id, "totalBytes", true)}</td>
        <td class="inventory-count">${renderInventoryMetric(blobStoreUsage, store.id, "pendingDeletionBytes", true)}
          <small class="usage-secondary">${renderInventoryMetric(blobStoreUsage, store.id, "pendingDeletionCount")} <span>blobs</span></small></td>
        <td class="usage-location">${renderCopyableLocation(target, "Copy storage location")}
          ${detail && detail !== target ? `<div class="usage-secondary">${renderCopyableLocation(detail, "Copy storage location")}</div>` : ""}
          ${fileStore || azureStore ? "" : pathStyleBadge(Boolean(store.pathStyleAccess))}</td>
        <td class="actions-column"><div class="cleanup-policy-actions">
          ${store.id == null ? '<span class="health-muted">-</span>' : `
            <button class="row-action edit-blobstore-button" data-id="${store.id}" type="button">Edit</button>
            <button class="row-action cleanup-policy-more-actions blobstore-more-actions" id="blobstore-more-actions-${store.id}" data-id="${store.id}" type="button" aria-label="More actions for ${escapeHtml(store.name)}" aria-haspopup="menu" aria-controls="blobstore-action-menu" aria-expanded="false" title="More actions"><span aria-hidden="true">⋯</span></button>
          `}
        </div></td>
      </tr>
    `;
  }).join("") || '<tr><td colspan="9" class="placeholder">No blob stores found.</td></tr>';
  if (focusedTriggerId) document.getElementById(focusedTriggerId)?.focus();
}

async function loadBlobStores(options = {}) {
  const version = ++blobStoreLoadVersion;
  blobStoreUsage = undefined;
  try {
    const response = await fetch("/internal/blob-stores", { cache: "no-store" });
    if (!response.ok) throw new Error(await responseErrorMessage(response));
    const payload = await response.json();
    if (version !== blobStoreLoadVersion) return;
    blobStores = payload.stores || [];
    // Deliberately do not await inventory: a cold aggregate must not delay the grid.
    if (!blobStores.length) blobStoreUsage = { usage: {}, calculatedAt: new Date().toISOString() };
    if (blobStores.length) {
      fetchInventoryUsage("/internal/blob-stores/statistics/usage").catch(() => null).then((snapshot) => {
        if (version !== blobStoreLoadVersion) return;
        blobStoreUsage = snapshot;
        renderBlobStores();
      });
    }
  } catch (error) {
    if (version !== blobStoreLoadVersion) return;
    blobStores = [];
    blobStoreUsage = null;
    showToast(`Failed to load blob stores: ${error.message}`, "error");
  }
  renderBlobStores();
  refreshRepositoryBlobStoreOptions();
  if (options.autoCheck) {
    autoCheckBlobStores();
  }
}

function blobStoreFormPayload() {
  const engine = document.getElementById("blobstore-engine").value;
  if (engine === "azure") return azureBlobStoreFormPayload();
  return {
    name: document.getElementById("blobstore-name").value.trim(),
    type: engine === "file" ? "file" : "s3",
    engine,
    endpoint: document.getElementById("blobstore-endpoint").value.trim(),
    region: document.getElementById("blobstore-region").value.trim(),
    bucket: document.getElementById("blobstore-bucket").value.trim(),
    prefix: document.getElementById("blobstore-prefix").value.trim(),
    path: document.getElementById("blobstore-path").value.trim(),
    credentialSource: blobStoreCredentialSource(),
    accessKey: blobStoreCredentialSource() === "default" ? "" : document.getElementById("blobstore-access-key").value.trim(),
    secretKey: blobStoreCredentialSource() === "default" ? "" : document.getElementById("blobstore-secret-key").value,
    pathStyleAccess: document.getElementById("blobstore-path-style").checked
  };
}

function azureBlobStoreFormPayload() {
  const credentialType = blobStoreAzureCredentialType();
  // Secrets are sent only for the mode that uses them, so switching to a platform identity
  // clears the stored value instead of leaving an unusable secret behind.
  return {
    name: document.getElementById("blobstore-name").value.trim(),
    type: "azure",
    engine: "azure",
    endpoint: document.getElementById("blobstore-endpoint").value.trim(),
    accountName: document.getElementById("blobstore-account-name").value.trim(),
    bucket: document.getElementById("blobstore-bucket").value.trim(),
    prefix: document.getElementById("blobstore-prefix").value.trim(),
    credentialType,
    accountKey: credentialType === "account-key" ? document.getElementById("blobstore-azure-account-key").value : "",
    sasToken: credentialType === "sas" ? document.getElementById("blobstore-azure-sas-token").value : "",
    clientId: azureIdentityFieldsVisible(credentialType)
      ? document.getElementById("blobstore-azure-client-id").value.trim() : "",
    tenantId: azureWorkloadFieldsVisible(credentialType)
      ? document.getElementById("blobstore-azure-tenant-id").value.trim() : "",
    tokenFilePath: azureTokenFileVisible(credentialType)
      ? document.getElementById("blobstore-azure-token-file").value.trim() : ""
  };
}

function blobStoreAzureCredentialType() {
  return document.getElementById("blobstore-azure-credential-type").value || "default";
}

function azureIdentityFieldsVisible(credentialType) {
  return credentialType === "default" || credentialType === "managed-identity"
    || credentialType === "workload-identity";
}

function azureWorkloadFieldsVisible(credentialType) {
  return credentialType === "default" || credentialType === "workload-identity";
}

/** DefaultAzureCredential reads the federated token file only from AZURE_FEDERATED_TOKEN_FILE. */
function azureTokenFileVisible(credentialType) {
  return credentialType === "workload-identity";
}

function blobStoreCredentialSource() {
  return document.getElementById("blobstore-engine").value === "aws-s3"
    ? document.getElementById("blobstore-credential-source").value : "static";
}

function activeBlobStoreRequiredFields() {
  const engine = document.getElementById("blobstore-engine").value;
  if (engine === "azure") return activeAzureBlobStoreRequiredFields();
  const fileMode = engine === "file";
  return (fileMode ? blobStoreFileRequiredFields : blobStoreS3RequiredFields)
    .filter((field) => !(
      (field.id === "blobstore-access-key" || field.id === "blobstore-secret-key")
      && (blobStoreCredentialSource() === "default"
        || (blobStoreFormMode === "edit" && document.getElementById(field.id).dataset.configured === "true"))
    ));
}

function activeAzureBlobStoreRequiredFields() {
  const credentialType = blobStoreAzureCredentialType();
  return blobStoreAzureRequiredFields.filter((field) => {
    if (field.id === "blobstore-azure-account-key") return azureSecretRequired(credentialType, "account-key");
    if (field.id === "blobstore-azure-sas-token") return azureSecretRequired(credentialType, "sas");
    return true;
  });
}

/** An already-stored secret may be kept by leaving the field blank while editing. */
function azureSecretRequired(credentialType, usedBy) {
  if (credentialType !== usedBy) return false;
  const id = usedBy === "sas" ? "blobstore-azure-sas-token" : "blobstore-azure-account-key";
  return !(blobStoreFormMode === "edit" && document.getElementById(id).dataset.configured === "true");
}

function refreshBlobStoreRequiredMarkers() {
  const active = new Set(activeBlobStoreRequiredFields().map((field) => field.id));
  blobStoreFormFields.forEach((field) => {
    updateRequiredMarker({
      ...field,
      required: () => active.has(field.id)
    });
  });
}

function validateBlobStoreForm() {
  const missing = [];
  let firstMissingInput = null;
  const requiredFields = activeBlobStoreRequiredFields();
  blobStoreFormFields.forEach((field) => {
    const input = document.getElementById(field.id);
    if (!requiredFields.some((requiredField) => requiredField.id === field.id)) {
      input.classList.remove("is-invalid");
      input.setAttribute("aria-invalid", "false");
      setFieldRequired(input, false);
      return;
    }
    setFieldRequired(input, true);
    const empty = !input.value.trim();
    input.classList.toggle("is-invalid", empty);
    input.setAttribute("aria-invalid", String(empty));
    if (empty) {
      missing.push(field.label);
      firstMissingInput = firstMissingInput || input;
    }
  });
  refreshBlobStoreRequiredMarkers();
  if (missing.length > 0) {
    showToast(`Required fields missing: ${missing.join(", ")}`, "error");
    firstMissingInput.focus();
    return false;
  }
  return true;
}

function clearBlobStoreFieldError(event) {
  if (event.target.value.trim()) {
    event.target.classList.remove("is-invalid");
    event.target.setAttribute("aria-invalid", "false");
  }
}

function clearBlobStoreFormErrors() {
  blobStoreFormFields.forEach((field) => {
    const input = document.getElementById(field.id);
    input.classList.remove("is-invalid");
    input.setAttribute("aria-invalid", "false");
  });
}

function setBlobStoreFormTitle(title, saveLabel) {
  document.getElementById("blobstore-form-title").textContent = title;
  document.getElementById("save-blobstore-button").textContent = saveLabel;
}

function setSecretFieldMode(required, placeholder = "") {
  const secretInput = document.getElementById("blobstore-secret-key");
  secretInput.required = required;
  secretInput.setAttribute("aria-required", String(required));
  secretInput.placeholder = placeholder;
  refreshBlobStoreRequiredMarkers();
}

function setAccessFieldMode(required, placeholder = "") {
  const accessInput = document.getElementById("blobstore-access-key");
  accessInput.required = required;
  accessInput.setAttribute("aria-required", String(required));
  accessInput.placeholder = placeholder;
  refreshBlobStoreRequiredMarkers();
}

/** Selectors that decide which blob store fields apply; changing any of them re-renders the form. */
const blobStoreFormModeControls = [
  "blobstore-engine",
  "blobstore-credential-source",
  "blobstore-azure-credential-type",
];

function bindBlobStoreFormModeControls() {
  blobStoreFormModeControls.forEach((id) => {
    document.getElementById(id).addEventListener("change", refreshBlobStoreEngineControls);
  });
}

function refreshBlobStoreEngineControls() {
  const engine = document.getElementById("blobstore-engine").value;
  const fileMode = engine === "file";
  const azureMode = engine === "azure";
  const source = document.getElementById("blobstore-credential-source");
  source.disabled = engine !== "aws-s3";
  if (engine === "oss-native") source.value = "static";
  const defaultCredentials = blobStoreCredentialSource() === "default";
  const credentialType = blobStoreAzureCredentialType();
  const pathStyle = document.getElementById("blobstore-path-style");
  const pathInput = document.getElementById("blobstore-path");
  refreshBlobStoreEndpointDefault(azureMode);
  document.querySelectorAll(".s3-only").forEach((element) => {
    element.hidden = fileMode || azureMode;
  });
  document.querySelectorAll(".azure-only").forEach((element) => {
    element.hidden = !azureMode;
  });
  document.querySelectorAll(".object-store-only").forEach((element) => {
    element.hidden = fileMode;
  });
  document.querySelectorAll(".file-only").forEach((element) => {
    element.hidden = !fileMode;
  });
  document.querySelectorAll(".azure-identity-fields").forEach((element) => {
    element.hidden = !azureMode || !azureIdentityFieldsVisible(credentialType);
  });
  document.querySelectorAll(".azure-workload-fields").forEach((element) => {
    element.hidden = !azureMode || !azureWorkloadFieldsVisible(credentialType);
  });
  document.querySelectorAll(".azure-token-file-fields").forEach((element) => {
    element.hidden = !azureMode || !azureTokenFileVisible(credentialType);
  });
  document.querySelectorAll(".azure-account-key-fields").forEach((element) => {
    element.hidden = !azureMode || credentialType !== "account-key";
  });
  document.querySelectorAll(".azure-sas-fields").forEach((element) => {
    element.hidden = !azureMode || credentialType !== "sas";
  });
  ["blobstore-endpoint", "blobstore-bucket", "blobstore-prefix"].forEach((id) => {
    document.getElementById(id).disabled = fileMode;
  });
  ["blobstore-region", "blobstore-access-key", "blobstore-secret-key", "blobstore-path-style"].forEach((id) => {
    document.getElementById(id).disabled = fileMode || azureMode;
  });
  document.querySelectorAll(".static-credentials").forEach((element) => {
    element.hidden = fileMode || azureMode || defaultCredentials;
  });
  ["blobstore-access-key", "blobstore-secret-key"].forEach((id) => {
    const input = document.getElementById(id);
    input.disabled = fileMode || azureMode || defaultCredentials;
    input.classList.remove("is-invalid");
    input.setAttribute("aria-invalid", "false");
  });
  document.getElementById("blobstore-account-name").disabled = !azureMode;
  document.getElementById("blobstore-azure-credential-type").disabled = !azureMode;
  document.getElementById("blobstore-azure-client-id").disabled = !azureMode || !azureIdentityFieldsVisible(credentialType);
  document.getElementById("blobstore-azure-tenant-id").disabled =
    !azureMode || !azureWorkloadFieldsVisible(credentialType);
  document.getElementById("blobstore-azure-token-file").disabled =
    !azureMode || !azureTokenFileVisible(credentialType);
  document.getElementById("blobstore-azure-account-key").disabled = !azureMode || credentialType !== "account-key";
  document.getElementById("blobstore-azure-sas-token").disabled = !azureMode || credentialType !== "sas";
  setBlobStoreFieldLabel("blobstore-bucket-label", azureMode ? "Container" : "Bucket");
  document.getElementById("blobstore-azure-client-id").placeholder = credentialType === "managed-identity"
    ? "User-assigned identity; blank uses system-assigned"
    : "Blank uses AZURE_CLIENT_ID from the service account";
  const requiredFields = new Set(activeBlobStoreRequiredFields().map((field) => field.id));
  blobStoreFormFields.forEach((field) => {
    setFieldRequired(document.getElementById(field.id), requiredFields.has(field.id));
  });
  pathInput.disabled = !fileMode;
  pathInput.required = fileMode;
  pathInput.setAttribute("aria-required", String(fileMode));
  pathStyle.disabled = fileMode || azureMode;
  pathStyle.title = "";
  refreshBlobStoreRequiredMarkers();
}

/**
 * Azure derives its endpoint from the account name, and the SDK refuses bearer tokens over plain
 * HTTP, so the S3 placeholder endpoint is dropped in Azure mode and restored if the operator
 * switches back to an S3 engine without typing another endpoint.
 */
function refreshBlobStoreEndpointDefault(azureMode) {
  const endpoint = document.getElementById("blobstore-endpoint");
  if (azureMode && endpoint.value.trim() === blobStoreS3DefaultEndpoint) {
    endpoint.value = "";
    endpoint.dataset.clearedS3Default = "true";
  } else if (!azureMode && endpoint.dataset.clearedS3Default === "true") {
    if (!endpoint.value.trim()) endpoint.value = blobStoreS3DefaultEndpoint;
    endpoint.dataset.clearedS3Default = "false";
  }
  endpoint.placeholder = azureMode ? "Blank uses https://<account>.blob.core.windows.net" : "";
}

function setBlobStoreFieldLabel(id, label) {
  const element = document.getElementById(id);
  if (element) element.textContent = label;
}

function showCreateBlobStoreForm() {
  blobStoreFormMode = "create";
  editingBlobStoreId = null;
  setBlobStoreFormTitle("Create blob store", "Create blob store");
  setAccessFieldMode(true);
  setSecretFieldMode(true);
  document.getElementById("blobstore-name").disabled = false;
  document.getElementById("blobstore-name").value = "";
  document.getElementById("blobstore-engine").value = "aws-s3";
  document.getElementById("blobstore-credential-source").value = "default";
  document.getElementById("blobstore-access-key").dataset.configured = "false";
  document.getElementById("blobstore-secret-key").dataset.configured = "false";
  document.getElementById("blobstore-endpoint").value = blobStoreS3DefaultEndpoint;
  document.getElementById("blobstore-endpoint").dataset.clearedS3Default = "false";
  document.getElementById("blobstore-region").value = "cn-hangzhou";
  document.getElementById("blobstore-bucket").value = "";
  document.getElementById("blobstore-prefix").value = "";
  document.getElementById("blobstore-path").value = "default";
  document.getElementById("blobstore-access-key").value = "";
  document.getElementById("blobstore-secret-key").value = "";
  document.getElementById("blobstore-path-style").checked = true;
  document.getElementById("blobstore-account-name").value = "";
  document.getElementById("blobstore-azure-credential-type").value = "default";
  ["blobstore-azure-client-id", "blobstore-azure-tenant-id", "blobstore-azure-token-file",
    "blobstore-azure-account-key", "blobstore-azure-sas-token"].forEach((id) => {
    document.getElementById(id).value = "";
    document.getElementById(id).dataset.configured = "false";
  });
  refreshBlobStoreEngineControls();
  clearBlobStoreFormErrors();
  openFormModal("blobstore-form", "blobstore-name");
}

function showEditBlobStoreForm(id) {
  const store = blobStores.find((item) => String(item.id) === String(id));
  if (!store) {
    showToast("Blob store no longer exists. Refresh and try again.", "error");
    return;
  }
  blobStoreFormMode = "edit";
  editingBlobStoreId = store.id;
  setBlobStoreFormTitle(`Edit blob store: ${store.name}`, "Save changes");
  setAccessFieldMode(false, store.accessKeyConfigured ? "Leave blank to keep existing" : "");
  setSecretFieldMode(false, store.secretConfigured ? "Leave blank to keep existing" : "");
  document.getElementById("blobstore-name").disabled = true;
  document.getElementById("blobstore-name").value = store.name || "";
  document.getElementById("blobstore-engine").value = normalizeBlobStoreEngine(store.engine);
  document.getElementById("blobstore-credential-source").value = store.credentialSource || "static";
  document.getElementById("blobstore-access-key").dataset.configured = String(!!store.accessKeyConfigured);
  document.getElementById("blobstore-secret-key").dataset.configured = String(!!store.secretConfigured);
  document.getElementById("blobstore-endpoint").value = store.endpoint || "";
  document.getElementById("blobstore-region").value = store.region || "cn-hangzhou";
  document.getElementById("blobstore-bucket").value = store.bucket || "";
  document.getElementById("blobstore-prefix").value = store.prefix || "";
  document.getElementById("blobstore-path").value = store.path || "";
  document.getElementById("blobstore-access-key").value = "";
  document.getElementById("blobstore-secret-key").value = "";
  document.getElementById("blobstore-path-style").checked = store.pathStyleAccess !== false;
  document.getElementById("blobstore-account-name").value = store.accountName || "";
  // Azure reports its credential mode through the shared credentialSource field.
  document.getElementById("blobstore-azure-credential-type").value =
    isAzureBlobStore(store) ? (store.credentialSource || "default") : "default";
  document.getElementById("blobstore-azure-client-id").value = store.clientId || "";
  document.getElementById("blobstore-azure-tenant-id").value = store.tenantId || "";
  document.getElementById("blobstore-azure-token-file").value = store.tokenFilePath || "";
  ["blobstore-azure-account-key", "blobstore-azure-sas-token"].forEach((id) => {
    document.getElementById(id).value = "";
  });
  document.getElementById("blobstore-azure-account-key").dataset.configured = String(!!store.accountKeyConfigured);
  document.getElementById("blobstore-azure-sas-token").dataset.configured = String(!!store.sasTokenConfigured);
  document.getElementById("blobstore-azure-account-key").placeholder =
    store.accountKeyConfigured ? "Leave blank to keep existing" : "";
  document.getElementById("blobstore-azure-sas-token").placeholder =
    store.sasTokenConfigured ? "Leave blank to keep existing" : "";
  refreshBlobStoreEngineControls();
  clearBlobStoreFormErrors();
  openFormModal("blobstore-form", blobStoreInitialFocusField(store));
}

function blobStoreInitialFocusField(store) {
  if (isFileBlobStore(store)) return "blobstore-path";
  if (isAzureBlobStore(store)) return "blobstore-account-name";
  return "blobstore-endpoint";
}

function normalizeBlobStoreEngine(engine) {
  const normalized = lowerOrEmpty(engine);
  if (normalized === "file" || normalized === "oss-native") return normalized;
  if (normalized.startsWith("azure")) return "azure";
  return "aws-s3";
}

function hideBlobStoreForm() {
  blobStoreFormMode = "create";
  editingBlobStoreId = null;
  clearBlobStoreFormErrors();
  document.getElementById("blobstore-name").disabled = false;
  setAccessFieldMode(true);
  setSecretFieldMode(true);
  document.getElementById("blobstore-path-style").disabled = false;
  closeFormModal("blobstore-form");
}

async function postBlobStoreAction(path, options, pendingMessage, successFallback = "Operation completed.") {
  showToast(pendingMessage);
  let actionMessage = "";
  let tone = "ok";
  try {
    const response = await fetch(path, options);
    if (!response.ok) throw new Error(await responseErrorMessage(response));
    const payload = await response.json();
    if (payload.ok === false) {
      actionMessage = payload.message || "Operation failed.";
      tone = "error";
    } else {
      actionMessage = payload.message || payload.summary?.message || successFallback;
    }
  } catch (error) {
    actionMessage = `Operation failed: ${error.message}`;
    tone = "error";
  }
  await loadBlobStores({ autoCheck: true });
  showToast(actionMessage, tone);
  return tone === "ok";
}

function applyBlobStoreCheckResult(id, result) {
  const key = String(id);
  const summary = result.summary || {};
  blobStoreHealth[key] = result.ok
    ? {
        status: "Healthy",
        tone: "ok",
        message: result.message || summary.message || "Read/write check passed."
      }
    : {
        status: summary.bucketExists === false ? "Missing" : "Failed",
        tone: summary.bucketExists === false ? "warn" : "bad",
        message: result.message || summary.message || "Health check failed."
      };
  blobStores = blobStores.map((store) => {
    if (String(store.id) !== key) return store;
    return {
      ...store,
      bucketExists: Boolean(summary.bucketExists)
    };
  });
}

function applyBlobStoreCheckError(id, message) {
  blobStoreHealth[String(id)] = {
    status: "Failed",
    tone: "bad",
    message
  };
}

async function runBlobStoreCheck(store, options = {}) {
  if (!store || store.id == null) return false;
  const key = String(store.id);
  blobStoreHealth[key] = {
    status: "Checking",
    tone: "checking",
    message: "Running health check..."
  };
  renderBlobStores();
  if (options.toast) {
    showToast(`Checking ${store.name}...`);
  }
  try {
    const response = await fetch(`/internal/blob-stores/${encodeURIComponent(store.id)}/check`, { method: "POST" });
    if (!response.ok) throw new Error(await responseErrorMessage(response));
    const payload = await response.json();
    applyBlobStoreCheckResult(store.id, payload);
    renderBlobStores();
    if (options.toast) {
      showToast(payload.message || "Blob store check completed.", payload.ok ? "ok" : "error");
    }
    return Boolean(payload.ok);
  } catch (error) {
    applyBlobStoreCheckError(store.id, error.message);
    renderBlobStores();
    if (options.toast) {
      showToast(`Check failed: ${error.message}`, "error");
    }
    return false;
  }
}

function autoCheckBlobStores() {
  blobStores
    .filter((store) => store.id != null)
    .forEach((store) => {
      runBlobStoreCheck(store);
    });
}

async function saveBlobStore(event) {
  if (event) event.preventDefault();
  if (!validateBlobStoreForm()) return;
  const creating = blobStoreFormMode === "create";
  if (!creating && editingBlobStoreId == null) {
    showToast("Select a blob store before saving changes.", "error");
    return;
  }
  const payload = blobStoreFormPayload();
  if (creating && blobStores.some((store) => store.name === payload.name)) {
    showToast("Blob store name already exists.", "error");
    document.getElementById("blobstore-name").classList.add("is-invalid");
    document.getElementById("blobstore-name").setAttribute("aria-invalid", "true");
    document.getElementById("blobstore-name").focus();
    return;
  }
  const path = creating
    ? "/internal/blob-stores"
    : `/internal/blob-stores/${encodeURIComponent(editingBlobStoreId)}`;
  const saved = await postBlobStoreAction(
    path,
    {
      method: creating ? "POST" : "PUT",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(payload)
    },
    creating ? "Creating blob store..." : "Saving blob store changes...",
    creating ? "Blob store created." : "Blob store updated.");
  if (saved) {
    hideBlobStoreForm();
  }
}

async function checkBlobStore(id, name) {
  const store = blobStores.find((item) => String(item.id) === String(id)) || { id, name };
  await runBlobStoreCheck(store, { toast: true });
}

async function deleteBlobStore(id) {
  const store = blobStores.find((item) => String(item.id) === String(id));
  if (!store || !await window.kkrepoConfirmDelete({
    title: "Delete blob store?",
    description: "This removes the configuration only. Storage files and buckets are not deleted.",
    details: [{ label: "Blob store", value: store.name }]
  })) return;
  showToast(`Deleting blob store ${store.name}...`);
  try {
    const response = await fetch(`/internal/blob-stores/${encodeURIComponent(id)}`, { method: "DELETE" });
    if (!response.ok) throw new Error(await responseErrorMessage(response));
    await loadBlobStores();
    showToast(`Blob store ${store.name} deleted.`, "ok");
  } catch (error) {
    showToast(`Delete failed: ${error.message}`, "error");
  }
}

function showBlobStoreRepositories(name) {
  const select = document.getElementById("repository-store-filter");
  select.innerHTML = `<option value="${escapeHtml(name)}">${escapeHtml(name)}</option>`;
  select.value = name;
  document.getElementById("repository-filter").value = "";
  listState.repositorySort = { key: "totalBytes", direction: "desc" };
  switchView("repositories");
}

function blobActionMenuItems() {
  return Array.from(document.querySelectorAll("#blobstore-action-menu [role='menuitem']"));
}

function positionBlobStoreActionMenu() {
  const menu = document.getElementById("blobstore-action-menu");
  const trigger = blobActionMenuTrigger;
  if (!trigger?.isConnected || menu.hidden) return;
  const triggerRect = trigger.getBoundingClientRect();
  if (triggerRect.bottom < 0 || triggerRect.top > window.innerHeight) {
    closeBlobStoreActionMenu();
    return;
  }
  const menuRect = menu.getBoundingClientRect();
  const padding = 10, gap = 6;
  menu.style.left = `${Math.round(Math.max(padding, Math.min(
    triggerRect.right - menuRect.width, window.innerWidth - menuRect.width - padding)))}px`;
  const above = triggerRect.top - menuRect.height - gap;
  menu.style.top = `${Math.round(triggerRect.bottom + menuRect.height + gap > window.innerHeight - padding
    && above >= padding ? above : triggerRect.bottom + gap)}px`;
}

function closeBlobStoreActionMenu(options = {}) {
  const menu = document.getElementById("blobstore-action-menu");
  const trigger = blobActionMenuTrigger;
  if (trigger) trigger.setAttribute("aria-expanded", "false");
  blobActionMenuStoreId = null;
  blobActionMenuTrigger = null;
  menu.hidden = true;
  menu.setAttribute("aria-hidden", "true");
  menu.removeAttribute("aria-labelledby");
  menu.innerHTML = "";
  if (options.restoreFocus && trigger?.isConnected) trigger.focus();
}

function openBlobStoreActionMenu(id, trigger, focusTarget = null) {
  const store = blobStores.find((item) => String(item.id) === String(id));
  if (!store) return;
  const menu = document.getElementById("blobstore-action-menu");
  if (!menu.hidden && String(blobActionMenuStoreId) === String(id)) {
    if (focusTarget === "first") blobActionMenuItems()[0]?.focus();
    else if (focusTarget === "last") blobActionMenuItems().at(-1)?.focus();
    else closeBlobStoreActionMenu({ restoreFocus: true });
    return;
  }
  closeBlobStoreActionMenu();
  closeCleanupPolicyActionMenu();
  blobActionMenuStoreId = id;
  blobActionMenuTrigger = trigger;
  menu.innerHTML = `
    <button class="cleanup-policy-action-menu-item" data-blobstore-action="repositories" type="button" role="menuitem">Repositories</button>
    <button class="cleanup-policy-action-menu-item" data-blobstore-action="check" type="button" role="menuitem">Check</button>
    <div class="cleanup-policy-action-menu-separator" role="separator"></div>
    <button class="cleanup-policy-action-menu-item is-danger" data-blobstore-action="delete" type="button" role="menuitem">Delete blob store</button>`;
  trigger.setAttribute("aria-expanded", "true");
  menu.setAttribute("aria-hidden", "false");
  menu.setAttribute("aria-labelledby", trigger.id);
  menu.hidden = false;
  positionBlobStoreActionMenu();
  if (focusTarget === "first") blobActionMenuItems()[0]?.focus();
  if (focusTarget === "last") blobActionMenuItems().at(-1)?.focus();
}

function handleBlobStoreActionMenuKeydown(event) {
  const menu = document.getElementById("blobstore-action-menu");
  if (menu.hidden || !menu.contains(event.target)) return false;
  if (event.key === "Escape") {
    event.preventDefault();
    closeBlobStoreActionMenu({ restoreFocus: true });
    return true;
  }
  const items = blobActionMenuItems();
  const current = items.indexOf(document.activeElement);
  let next = null;
  if (event.key === "ArrowDown") next = (current + 1) % items.length;
  if (event.key === "ArrowUp") next = (current - 1 + items.length) % items.length;
  if (event.key === "Home") next = 0;
  if (event.key === "End") next = items.length - 1;
  if (next == null) return false;
  event.preventDefault();
  items[next]?.focus();
  return true;
}

export {
  bindBlobStoreFormModeControls,
  blobActionMenuStoreId,
  blobActionMenuTrigger,
  blobStoreFormFields,
  blobStores,
  checkBlobStore,
  clearBlobStoreFieldError,
  closeBlobStoreActionMenu,
  deleteBlobStore,
  handleBlobStoreActionMenuKeydown,
  hideBlobStoreForm,
  loadBlobStores,
  openBlobStoreActionMenu,
  positionBlobStoreActionMenu,
  renderBlobStores,
  saveBlobStore,
  showBlobStoreRepositories,
  showCreateBlobStoreForm,
  showEditBlobStoreForm,
  toggleBlobStoreSort,
};
