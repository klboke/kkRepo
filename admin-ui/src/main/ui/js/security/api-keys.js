import * as userPicker from "../user-picker.js";
import { copyLocationText } from "../controls/clipboard.js";
import { escapeHtml } from "../controls/display.js";
import { clearRequiredFieldErrors, validateRequiredFields } from "../controls/fields.js";
import { closeFormModal, openFormModal } from "../controls/modal.js";
import { showToast } from "../controls/toast.js";
import { fetchJson, responseErrorMessage } from "../shell/http.js";

let securityApiKeys = [];

const securityApiKeyRequiredFields = [
  { id: "security-api-key-owner-user-id", label: "Owner user ID" }
];

async function loadSecurityApiKeys() {
  securityApiKeys = await fetchJson("/internal/security/api-keys", [], "Failed to load API keys");
  renderSecurityApiKeys();
}

function renderSecurityApiKeys() {
  const rows = securityApiKeys.map((key) => `
    <tr>
      <td>${escapeHtml(key.domain)}</td>
      <td>${escapeHtml(key.ownerSource)}/${escapeHtml(key.ownerUserId)}</td>
      <td><span class="state-badge compact ${key.status === "ACTIVE" ? "ok" : "warn"}">${escapeHtml(key.status || "")}</span></td>
      <td><code>${escapeHtml(key.tokenPrefix || "")}</code></td>
      <td class="actions-column"><button class="row-action delete-security-api-key-button" data-id="${key.id}" type="button">delete</button></td>
    </tr>
  `).join("");
  document.getElementById("security-api-key-table").innerHTML = rows
    || '<tr><td colspan="5" class="placeholder">No API keys.</td></tr>';
}

let apiKeyOwnerPicker;

let apiKeyFormGeneration = 0;

function showSecurityApiKeyForm() {
  apiKeyOwnerPicker ||= userPicker.create({
    source: document.getElementById("security-api-key-owner-source"),
    user: document.getElementById("security-api-key-owner-user-id"),
    status: document.getElementById("security-api-key-owner-status")
  });
  document.getElementById("security-api-key-domain").value = "NpmToken";
  document.getElementById("security-api-key-owner-source").value = "Local";
  document.getElementById("security-api-key-owner-user-id").value = "";
  document.getElementById("security-api-key-display-name").value = "";
  clearRequiredFieldErrors(securityApiKeyRequiredFields);
  apiKeyOwnerPicker.open();
  openFormModal("security-api-key-form", "security-api-key-domain");
}

function hideSecurityApiKeyForm() {
  ++apiKeyFormGeneration;
  document.getElementById("save-security-api-key-button").disabled = false;
  apiKeyOwnerPicker?.close();
  closeFormModal("security-api-key-form");
  clearRequiredFieldErrors(securityApiKeyRequiredFields);
}

function hideSecurityApiKeyResult() {
  document.getElementById("security-api-key-value").value = "";
  closeFormModal("security-api-key-result");
  document.getElementById("create-security-api-key-button").focus();
}

async function copySecurityApiKey() {
  const input = document.getElementById("security-api-key-value");
  if (!input.value) return;
  try {
    await copyLocationText(input.value);
    showToast("Copied", "ok");
  } catch {
    if (!input.value) return;
    input.focus();
    input.select();
    showToast("Ctrl+C / Cmd+C", "info");
  }
}

async function saveSecurityApiKey() {
  const save = document.getElementById("save-security-api-key-button");
  if (save.disabled) return;
  if (!apiKeyOwnerPicker?.validate()) return;
  if (!validateRequiredFields(securityApiKeyRequiredFields)) return;
  const payload = {
    domain: document.getElementById("security-api-key-domain").value.trim() || "NpmToken",
    ownerSource: document.getElementById("security-api-key-owner-source").value.trim() || "Local",
    ownerUserId: document.getElementById("security-api-key-owner-user-id").value.trim(),
    displayName: document.getElementById("security-api-key-display-name").value.trim() || null
  };
  const generation = apiKeyFormGeneration;
  save.disabled = true;
  try {
    const response = await fetch("/internal/security/api-keys", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(payload)
    });
    if (!response.ok) throw new Error(await responseErrorMessage(response));
    const created = await response.json();
    if (generation === apiKeyFormGeneration) {
      hideSecurityApiKeyForm();
      if (created.token) {
        document.getElementById("security-api-key-value").value = created.token;
        openFormModal("security-api-key-result", "security-api-key-value");
      } else {
        showToast("API key imported.", "ok");
      }
    }
    await loadSecurityApiKeys();
  } catch (error) {
    if (generation === apiKeyFormGeneration) showToast(`Create failed: ${error.message}`, "error");
  } finally {
    if (generation === apiKeyFormGeneration) save.disabled = false;
  }
}

async function deleteSecurityApiKey(id) {
  if (!await window.kkrepoConfirmDelete({
    title: "Delete API key?",
    description: "Clients and CI jobs using this key will no longer be able to authenticate with it.",
    details: [{ label: "API key ID", value: String(id) }]
  })) return;
  try {
    const response = await fetch(`/internal/security/api-keys/${encodeURIComponent(id)}`, { method: "DELETE" });
    if (!response.ok) throw new Error(await responseErrorMessage(response));
    await loadSecurityApiKeys();
    showToast("API key deleted.", "ok");
  } catch (error) {
    showToast(`Delete failed: ${error.message}`, "error");
  }
}

export {
  copySecurityApiKey,
  deleteSecurityApiKey,
  hideSecurityApiKeyForm,
  hideSecurityApiKeyResult,
  loadSecurityApiKeys,
  saveSecurityApiKey,
  securityApiKeyRequiredFields,
  showSecurityApiKeyForm,
};
