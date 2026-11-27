import { escapeHtml, lucideIcon } from "../controls/display.js";
import { showToast } from "../controls/toast.js";
import { fetchJson, responseErrorMessage } from "../shell/http.js";

let saved = [];
let draft = [];
let saving = false;
let latestLoad = 0;

const endpoint = "/internal/security/realms";
const descriptions = {
  local: "Local account · Password",
  ldap: "LDAP · Directory password",
  oidc: "OpenID Connect · Single sign-on",
};

function resetDraft(realms) {
  // Disabled realms are returned last by the API. Keep their priority slots so
  // re-enabling a realm puts it back in the same order as the authentication service.
  saved = realms.map(realm => ({ ...realm, enabled: realm.realmId === "local" || realm.enabled }))
    .sort((a, b) => a.priority - b.priority || a.realmId.localeCompare(b.realmId));
  draft = saved.map(realm => ({ ...realm }));
}

function realmChanges() {
  // Reuse priority slots, opening a gap only where the ID tie-break would
  // reverse the requested order. The API accepts signed 32-bit priorities.
  const priorities = saved.map(realm => realm.priority);
  const minimumGap = i => Number(draft[i - 1].realmId.localeCompare(draft[i].realmId) > 0);
  for (let i = 1; i < priorities.length; i++) {
    priorities[i] = Math.max(priorities[i], priorities[i - 1] + minimumGap(i));
  }
  const last = priorities.length - 1;
  if (priorities[last] > 2147483647) {
    priorities[last] = 2147483647;
    for (let i = last; i > 0; i--) {
      priorities[i - 1] = Math.min(priorities[i - 1], priorities[i] - minimumGap(i));
    }
  }
  const changes = [];
  draft.forEach((realm, i) => {
    const original = saved.find(item => item.realmId === realm.realmId);
    const priority = priorities[i];
    if (priority !== original.priority || realm.enabled !== original.enabled) {
      const command = { realmId: realm.realmId, priority };
      if (realm.enabled !== original.enabled) command.enabled = realm.enabled;
      changes.push(command);
    }
  });
  return changes;
}

function realmRow(realm, index, activeCount) {
  const id = escapeHtml(realm.realmId);
  const nameId = `realm-name-${id}`;
  const defaultSource = realm.realmId === "local" ? "Local" : realm.realmId.toUpperCase();
  const source = realm.attributes?.source;
  const metadata = [
    `<span>${escapeHtml(descriptions[realm.realmId] || realm.type)}</span>`,
    realm.type !== realm.realmId.toUpperCase() && `<span>Type</span> <span data-i18n-skip>${escapeHtml(realm.type)}</span>`,
    source && source !== defaultSource && `<span>Source</span> <span data-i18n-skip>${escapeHtml(source)}</span>`,
  ].filter(Boolean).join(" · ");
  function moveButton(direction, label, boundary) {
    const buttonId = `realm-${direction}-${id}`;
    return `<button type="button" class="row-action" id="${buttonId}" data-move="${direction}" title="${label}"
      aria-label="${label}" aria-labelledby="${buttonId} ${nameId}"${saving || boundary ? " disabled" : ""}>${lucideIcon("arrow-" + direction)}</button>`;
  }
  const activation = realm.realmId === "local"
    ? `<span class="realm-required">${lucideIcon("lock")} <span>Always enabled</span></span>`
    : `<label class="form-switch realm-activation"><input type="checkbox" role="switch" id="realm-activation-${id}"
        aria-labelledby="realm-activation-label-${id} ${nameId}"${realm.enabled ? " checked" : ""}${saving ? " disabled" : ""}>
        <span id="realm-activation-label-${id}">Use for sign-in</span></label>`;
  return `<li class="realm-row" data-realm-id="${id}">
    ${realm.enabled ? `<span class="realm-rank" aria-hidden="true">${index + 1}</span>` : ""}
    <div class="realm-identity"><strong id="${nameId}" data-i18n-skip>${escapeHtml(realm.name)}</strong><span class="realm-description">${metadata}</span></div>
    ${activation}
    ${realm.enabled ? `<div class="realm-moves">${moveButton("up", "Move up", index === 0)}${moveButton("down", "Move down", index === activeCount - 1)}</div>` : ""}
  </li>`;
}

function renderRealmEditor(message = "") {
  const enabled = draft.filter(realm => realm.enabled);
  const disabled = draft.filter(realm => !realm.enabled);
  const dirty = realmChanges().length > 0;
  document.getElementById("realm-announcement").textContent = "";
  document.getElementById("realm-enabled-count").textContent = draft.length ? `${enabled.length} enabled` : "";
  document.getElementById("realm-enabled-list").innerHTML = enabled.map((realm, i) => realmRow(realm, i, enabled.length)).join("");
  document.getElementById("realm-disabled-section").hidden = disabled.length === 0;
  document.getElementById("realm-disabled-list").innerHTML = disabled.map(realm => realmRow(realm)).join("");
  document.getElementById("save-security-realms-button").disabled = saving || !dirty;
  document.getElementById("discard-security-realms-button").disabled = saving || !dirty;
  const status = document.getElementById("realm-edit-status");
  status.textContent = message || (dirty ? "Save to apply changes." : "No changes");
  status.classList.toggle("is-dirty", dirty && !saving);
}

export async function loadSecurityRealms() {
  if (saving) return;
  const load = ++latestLoad;
  resetDraft([]);
  document.getElementById("reload-security-realms-button").hidden = true;
  renderRealmEditor("Loading…");
  const realms = await fetchJson(endpoint, null, "Failed to load realms");
  if (load !== latestLoad) return;
  if (!realms) {
    renderRealmEditor("Failed to load realms");
    document.getElementById("reload-security-realms-button").hidden = false;
    return;
  }
  resetDraft(realms);
  renderRealmEditor();
}

function moveRealm(button, row) {
  const enabled = draft.filter(realm => realm.enabled);
  const i = enabled.findIndex(realm => realm.realmId === row.dataset.realmId);
  const j = i + (button.dataset.move === "up" ? -1 : 1);
  if (i < 0 || !enabled[j]) return;
  const from = draft.indexOf(enabled[i]);
  const to = draft.indexOf(enabled[j]);
  [draft[from], draft[to]] = [draft[to], draft[from]];
  renderRealmEditor();
  const moved = document.getElementById(button.id);
  (moved.disabled ? moved.parentElement.querySelector("button:enabled") : moved)?.focus();
  const announcement = document.getElementById("realm-announcement");
  announcement.innerHTML = `<span data-i18n-skip>${escapeHtml(enabled[i].name)}</span> · <span>Priority</span> ${j + 1} / ${enabled.length}`;
}

async function saveSecurityRealms() {
  const changes = realmChanges();
  if (saving || !changes.length) return;
  saving = true;
  renderRealmEditor("Saving…");
  let status;
  try {
    const response = await fetch(endpoint, {
      method: "PUT",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(changes),
    });
    if (!response.ok) throw new Error(await responseErrorMessage(response));
    resetDraft(await response.json());
    status = "Realms saved.";
  } catch (error) {
    showToast(`Save failed: ${error.message}`, "error");
  } finally {
    saving = false;
    renderRealmEditor(status);
    if (document.activeElement === document.body && document.getElementById("security-realm-editor").getClientRects().length) {
      document.getElementById("realm-edit-status").focus();
    }
  }
}

export function bindRealmEditor() {
  const editor = document.getElementById("security-realm-editor");
  editor.addEventListener("click", event => {
    const button = event.target.closest("button[data-move]");
    if (button && !saving) moveRealm(button, button.closest(".realm-row"));
  });
  editor.addEventListener("change", event => {
    const input = event.target.closest(".realm-activation input");
    if (!input || saving) return;
    const row = input.closest(".realm-row");
    const realm = draft.find(item => item.realmId === row.dataset.realmId);
    realm.enabled = input.checked;
    renderRealmEditor();
    document.getElementById(input.id).focus();
    const group = realm.enabled ? "Authentication order" : "Disabled realms";
    document.getElementById("realm-announcement").innerHTML = `<span data-i18n-skip>${escapeHtml(realm.name)}</span> · <span>${group}</span>`;
  });
  document.getElementById("reload-security-realms-button").addEventListener("click", loadSecurityRealms);
  document.getElementById("discard-security-realms-button").addEventListener("click", () => {
    resetDraft(saved);
    renderRealmEditor("Changes discarded");
    document.getElementById("realm-edit-status").focus();
  });
  document.getElementById("save-security-realms-button").addEventListener("click", saveSecurityRealms);
}
