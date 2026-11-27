import { copyTextToClipboard } from "../controls/clipboard.js";
import { escapeHtml, formatInstant } from "../details/format.js";
import { responseMessage } from "../shell/http.js";
import { pushBrowseRoute, showWelcome, switchView, viewHash } from "../shell/routes.js";
import { displaySource, sessionState, updateTopbarAuth, writeAuthSnapshot } from "../shell/session.js";

let currentApiKeysCache = [];

let currentApiKeysLoaded = false;

let latestGeneratedApiToken = "";

function showMyToken(syncHash = true) {
  if (!sessionState.currentSession || !sessionState.currentSession.userId) {
    showWelcome(true);
    return;
  }
  if (syncHash) pushBrowseRoute(viewHash("my-token"));
  switchView("my-token");
  renderMyToken();
  if (!currentApiKeysLoaded) {
    loadCurrentApiKeys();
  }
}

function currentOwnerLabel() {
  if (!sessionState.currentSession || !sessionState.currentSession.userId) return "";
  const source = sessionState.currentSession.source ? `${displaySource(sessionState.currentSession.source)}/` : "";
  return `${source}${sessionState.currentSession.userId}`;
}

function setMyTokenStatus(message, type = "") {
  const status = document.getElementById("my-token-status");
  if (!status) return;
  status.textContent = message || "";
  status.classList.toggle("ok", type === "ok");
  status.classList.toggle("error", type === "error");
}

function markMyTokenDisplayNameValidity(invalid) {
  const input = document.getElementById("my-token-display-name");
  if (!input) return;
  input.classList.toggle("is-invalid", invalid);
  input.setAttribute("aria-invalid", String(invalid));
}

function setMyTokenBusy(busy) {
  document.getElementById("my-token-create").disabled = busy;
  document.querySelectorAll("[data-token-reset], [data-token-delete]").forEach((button) => {
    button.disabled = busy;
  });
}

function clearMyTokenSecret() {
  latestGeneratedApiToken = "";
  document.getElementById("my-token-secret").hidden = true;
  document.getElementById("my-token-value").value = "";
}

function revealGeneratedToken(token) {
  latestGeneratedApiToken = token || "";
  const panel = document.getElementById("my-token-secret");
  document.getElementById("my-token-value").value = latestGeneratedApiToken;
  document.getElementById("my-token-usage").hidden =
    !/^[A-Za-z0-9]{10}:[A-Za-z0-9_-]{43}$/.test(latestGeneratedApiToken);
  panel.hidden = !latestGeneratedApiToken;
}

function expireCurrentSession() {
  sessionState.currentSession = null;
  sessionState.currentPermissions = [];
  currentApiKeysCache = [];
  currentApiKeysLoaded = false;
  clearMyTokenSecret();
  writeAuthSnapshot();
  updateTopbarAuth();
  showWelcome();
}

async function fetchCurrentApiKeys() {
  const res = await fetch("/internal/security/api-keys/current", {
    headers: { Accept: "application/json" },
    cache: "no-store",
  });
  if (res.status === 401 || res.status === 403) {
    expireCurrentSession();
    throw new Error("Session expired.");
  }
  if (!res.ok) throw new Error(await responseMessage(res, `Failed to load API keys: ${res.status}`));
  return res.json();
}

async function loadCurrentApiKeys() {
  if (!sessionState.currentSession || !sessionState.currentSession.userId) return;
  setMyTokenStatus("Loading...");
  try {
    currentApiKeysCache = await fetchCurrentApiKeys();
    currentApiKeysLoaded = true;
    renderMyToken();
    setMyTokenStatus("");
  } catch (error) {
    if (sessionState.currentSession) {
      setMyTokenStatus(error.message || "Failed to load API keys.", "error");
    }
  }
}

function renderMyToken() {
  const table = document.getElementById("my-token-table");
  if (!table) return;
  if (!sessionState.currentSession || !sessionState.currentSession.userId) {
    table.innerHTML = '<tr><td colspan="8" class="muted-row">Sign in required.</td></tr>';
    return;
  }
  if (!currentApiKeysLoaded) {
    table.innerHTML = '<tr><td colspan="8" class="muted-row">Loading...</td></tr>';
    return;
  }
  if (!currentApiKeysCache.length) {
    table.innerHTML = '<tr><td colspan="8" class="muted-row">No API keys.</td></tr>';
    return;
  }
  table.innerHTML = currentApiKeysCache.map((apiKey) => {
    const owner = `${apiKey.ownerSource || ""}/${apiKey.ownerUserId || ""}`.replace(/^\//, "");
    const scopes = Array.isArray(apiKey.scopes) && apiKey.scopes.length ? apiKey.scopes.join(", ") : "-";
    const id = Number(apiKey.id);
    return `
      <tr>
        <td>${escapeHtml(apiKey.domain || "")}</td>
        <td>${escapeHtml(apiKey.displayName || "-")}</td>
        <td>${escapeHtml(owner || currentOwnerLabel())}</td>
        <td>${escapeHtml(apiKey.status || "")}</td>
        <td><code>${escapeHtml(apiKey.tokenPrefix || "-")}</code></td>
        <td>${escapeHtml(scopes)}</td>
        <td>${escapeHtml(formatInstant(apiKey.updatedAt || apiKey.createdAt) || "-")}</td>
        <td>
          <div class="token-actions">
            <button class="secondary-button" type="button" data-token-reset="${id}">Reset</button>
            <button class="secondary-button token-danger-button" type="button" data-token-delete="${id}">Delete</button>
          </div>
        </td>
      </tr>
    `;
  }).join("");
  table.querySelectorAll("[data-token-reset]").forEach((button) => {
    button.addEventListener("click", () => resetCurrentApiKey(button.dataset.tokenReset));
  });
  table.querySelectorAll("[data-token-delete]").forEach((button) => {
    button.addEventListener("click", () => deleteCurrentApiKey(button.dataset.tokenDelete));
  });
}

async function createCurrentApiKey(event) {
  event.preventDefault();
  if (!sessionState.currentSession || !sessionState.currentSession.userId) {
    showWelcome();
    return;
  }
  const domain = document.getElementById("my-token-domain").value || "NpmToken";
  const displayNameInput = document.getElementById("my-token-display-name");
  const displayName = displayNameInput.value.trim();
  if (!displayName) {
    markMyTokenDisplayNameValidity(true);
    setMyTokenStatus("Display name is required.", "error");
    displayNameInput.focus();
    return;
  }
  markMyTokenDisplayNameValidity(false);
  setMyTokenBusy(true);
  setMyTokenStatus("Generating...");
  try {
    const res = await fetch("/internal/security/api-keys/current", {
      method: "POST",
      headers: { "Content-Type": "application/json", Accept: "application/json" },
      cache: "no-store",
      body: JSON.stringify({ domain, displayName, scopes: [] }),
    });
    if (res.status === 401 || res.status === 403) {
      expireCurrentSession();
      return;
    }
    if (res.status === 409) {
      clearMyTokenSecret();
      currentApiKeysLoaded = false;
      await loadCurrentApiKeys();
      if (!sessionState.currentSession) return;
    }
    if (!res.ok) throw new Error(await responseMessage(res, `Failed to generate API key: ${res.status}`));
    const payload = await res.json();
    revealGeneratedToken(payload.token || "");
    currentApiKeysLoaded = false;
    await loadCurrentApiKeys();
    setMyTokenStatus("Generated.", "ok");
  } catch (error) {
    setMyTokenStatus(error.message || "Failed to generate API key.", "error");
  } finally {
    setMyTokenBusy(false);
  }
}

async function resetCurrentApiKey(id) {
  if (!id) return;
  setMyTokenBusy(true);
  setMyTokenStatus("Resetting...");
  try {
    const res = await fetch(`/internal/security/api-keys/current/${encodeURIComponent(id)}/reset`, {
      method: "POST",
      headers: { Accept: "application/json" },
      cache: "no-store",
    });
    if (res.status === 401 || res.status === 403) {
      expireCurrentSession();
      return;
    }
    if (!res.ok) throw new Error(await responseMessage(res, `Failed to reset API key: ${res.status}`));
    const payload = await res.json();
    revealGeneratedToken(payload.token || "");
    currentApiKeysLoaded = false;
    await loadCurrentApiKeys();
    setMyTokenStatus("Reset.", "ok");
  } catch (error) {
    setMyTokenStatus(error.message || "Failed to reset API key.", "error");
  } finally {
    setMyTokenBusy(false);
  }
}

async function deleteCurrentApiKey(id) {
  if (!id || !await window.kkrepoConfirmDelete({
    title: "Delete API key?",
    description: "Clients and CI jobs using this key will no longer be able to authenticate with it.",
    details: [{ label: "API key ID", value: String(id) }]
  })) return;
  setMyTokenBusy(true);
  setMyTokenStatus("Deleting...");
  try {
    const res = await fetch(`/internal/security/api-keys/current/${encodeURIComponent(id)}`, {
      method: "DELETE",
      headers: { Accept: "application/json" },
      cache: "no-store",
    });
    if (res.status === 401 || res.status === 403) {
      expireCurrentSession();
      return;
    }
    if (!res.ok) throw new Error(await responseMessage(res, `Failed to delete API key: ${res.status}`));
    clearMyTokenSecret();
    currentApiKeysLoaded = false;
    await loadCurrentApiKeys();
    setMyTokenStatus("Deleted.", "ok");
  } catch (error) {
    setMyTokenStatus(error.message || "Failed to delete API key.", "error");
  } finally {
    setMyTokenBusy(false);
  }
}

async function copyGeneratedToken() {
  if (!latestGeneratedApiToken) return;
  const button = document.getElementById("my-token-copy");
  await copyTextToClipboard(latestGeneratedApiToken);
  button.textContent = "Copied";
  setTimeout(() => { button.textContent = "Copy"; }, 1200);
}

export {
  copyGeneratedToken,
  createCurrentApiKey,
  markMyTokenDisplayNameValidity,
  setMyTokenStatus,
  showMyToken,
};
