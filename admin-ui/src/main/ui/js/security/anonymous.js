import { bindRequiredFieldErrors, clearRequiredFieldErrors, validateRequiredFields } from "../controls/fields.js";
import { showToast } from "../controls/toast.js";
import { fetchJson, responseErrorMessage } from "../shell/http.js";

const settingsUrl = "/internal/security/anonymous";
const realmsUrl = "/service/rest/v1/security/realms/available";
const requiredFields = [
  { id: "security-anonymous-user-id", label: "User ID" },
  { id: "security-anonymous-realm-name", label: "Realm name" },
];
let saved = null;
let realmOptions = [];
let loadVersion = 0;
let saving = false;

function control(name) {
  return document.getElementById("security-anonymous-" + name);
}

function validSettings(settings) {
  return typeof settings?.enabled === "boolean" && typeof settings.userId === "string"
    && settings.userId.trim() !== "" && typeof settings.realmName === "string" && settings.realmName.trim() !== "";
}

function draftSettings() {
  return {
    enabled: control("enabled").checked,
    userSource: "Local",
    userId: control("user-id").value.trim(),
    realmName: control("realm-name").value,
  };
}

function hasChanges(draft) {
  return saved && (draft.enabled !== saved.enabled || draft.userId !== saved.userId || draft.realmName !== saved.realmName);
}

function updateDraft(message) {
  const draft = draftSettings();
  const dirty = hasChanges(draft);
  control("effect").textContent = draft.enabled
    ? "Unauthenticated requests use this account’s repository read permissions."
    : "Turning off anonymous reads keeps the account settings below.";
  control("realm-id").textContent = draft.realmName;
  control("edit-status").textContent = message || (dirty ? "Save to apply changes." : "No changes");
  control("discard").disabled = !dirty || saving;
  document.getElementById("save-security-anonymous-button").disabled = !dirty || saving;
}

function displaySaved(message) {
  const select = control("realm-name");
  select.replaceChildren(...realmOptions.map(realm => new Option(realm.name, realm.id)));
  if (!realmOptions.some(realm => realm.id === saved.realmName)) {
    // Preserve stored aliases when an administrator edits another field.
    const current = new Option(saved.realmName, saved.realmName);
    current.dataset.i18nSkip = "";
    select.add(current);
  }
  select.value = saved.realmName;
  control("enabled").checked = saved.enabled;
  control("user-id").value = saved.userId;
  control("known-users").replaceChildren(new Option(saved.userId, saved.userId));
  clearRequiredFieldErrors(requiredFields);
  updateDraft(message);
}

export async function loadSecurityAnonymous(focusAfterRetry = false) {
  if (saving) return;
  const version = ++loadVersion;
  saved = null;
  control("form").hidden = true;
  control("load").hidden = false;
  control("retry").hidden = true;
  control("load-status").textContent = "Loading…";
  const [settings, realms] = await Promise.all([
    fetchJson(settingsUrl, null, "Failed to load anonymous settings"),
    fetchJson(realmsUrl, null, "Failed to load realms"),
  ]);
  if (version !== loadVersion) return;
  const settingsReady = validSettings(settings);
  if (!settingsReady || !Array.isArray(realms) || !realms.every(realm => realm?.id && realm.name)) {
    control("load-status").textContent = settingsReady ? "Failed to load realms" : "Failed to load anonymous settings";
    control("retry").hidden = false;
    if (focusAfterRetry) control("retry").focus();
    return;
  }
  saved = settings;
  realmOptions = realms;
  displaySaved();
  control("load").hidden = true;
  control("form").hidden = false;
  if (focusAfterRetry) control("enabled").focus();
}

async function saveAnonymous(event) {
  event.preventDefault();
  const draft = draftSettings();
  if (saving || !hasChanges(draft) || !validateRequiredFields(requiredFields)) return;
  saving = true;
  control("fields").disabled = true;
  updateDraft("Saving…");
  let applied = null;
  try {
    const response = await fetch(settingsUrl, {
      method: "PUT",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(draft),
    });
    if (!response.ok) throw new Error(await responseErrorMessage(response));
    const result = await response.json();
    if (!validSettings(result)) throw new Error("Invalid anonymous settings");
    applied = result;
  } catch (error) {
    showToast(`Save failed: ${error.message}`, "error");
  } finally {
    saving = false;
    control("fields").disabled = false;
    if (applied) {
      saved = applied;
      displaySaved("Anonymous access saved.");
    } else {
      updateDraft("Save failed");
    }
    if (document.activeElement === document.body) control("edit-status").focus();
  }
}

export function bindAnonymousEditor() {
  bindRequiredFieldErrors(requiredFields);
  control("retry").addEventListener("click", () => loadSecurityAnonymous(true));
  control("form").addEventListener("input", () => updateDraft());
  control("form").addEventListener("change", () => updateDraft());
  control("form").addEventListener("submit", saveAnonymous);
  control("discard").addEventListener("click", () => {
    if (!saved || saving) return;
    displaySaved("Changes discarded");
    control("edit-status").focus();
  });
}
