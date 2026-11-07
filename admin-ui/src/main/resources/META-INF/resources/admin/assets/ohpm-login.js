(function (root) {
  "use strict";
  const api = "/internal/ohpm/login";
  const el = (name) => document.getElementById(`ohpm-login-${name}`);
  let initialized = false, busy = false, offset = 0, pageSize = 10, hasNext = false, keys = [];
  let returnFocus = null;
  let signedLoginEnabled = false;
  const unavailable = "Enable signed login in the server configuration before creating, rotating or enabling keys.";
  let selectedKey = null, ownerPicker;

  async function request(path, method = "GET", body) {
    const response = await fetch(path, {
      method, cache: "no-store",
      headers: body ? { "Content-Type": "application/json" } : {},
      body: body ? JSON.stringify(body) : undefined
    });
    if (!response.ok) throw new Error(await responseErrorMessage(response));
    return response.status === 204 ? null : response.json();
  }

  function command(source, userId, publicKey, enabled) {
    source = source.trim();
    userId = userId.trim();
    publicKey = publicKey.trim();
    if (!source || !userId) throw new Error("Enter an existing user source and user ID.");
    if (publicKey.length > 16384 || !/^-----BEGIN PUBLIC KEY-----\s+[A-Za-z0-9+/=\s]+\s+-----END PUBLIC KEY-----$/.test(publicKey)) {
      throw new Error("Provide an X.509 PEM public key. Keep the private key on the publishing machine.");
    }
    return { source, userId, publicKey, enabled };
  }

  async function refresh(nextOffset = offset, nextPageSize = pageSize) {
    signedLoginEnabled = false;
    const configuration = await request(api);
    signedLoginEnabled = configuration.enabled === true;
    el("notice").hidden = signedLoginEnabled;
    el("notice").dataset.state = configuration.enabled ? "enabled" : "disabled";
    el("configuration").textContent = configuration.enabled
      ? "Signed login is enabled on this server." : "Signed login is disabled on this server.";
    el("availability").textContent = configuration.enabled
      ? "Enabled keys can authenticate signed publication."
      : "Enable signed login in the server configuration on every replica to create, rotate or enable keys. Existing keys cannot authenticate while it is disabled.";
    el("timing").textContent = `Token lifetime: ${configuration.tokenTtlSeconds}s. Timestamp tolerance: ${configuration.timestampToleranceSeconds}s.`;
    let batch = await request(`${api}/keys?offset=${nextOffset}`);
    if (batch.length === 0 && nextOffset > 0) {
      nextOffset = 0;
      batch = await request(`${api}/keys?offset=0`);
    }
    // The existing endpoint returns at most 100 keys. Only the 100-row view
    // needs a second bounded read to distinguish a full final page.
    const next = batch.length > nextPageSize || (nextPageSize === 100 && batch.length === 100
      && (await request(`${api}/keys?offset=${nextOffset + 100}`)).length > 0);
    offset = nextOffset;
    pageSize = nextPageSize;
    hasNext = next;
    keys = batch.slice(0, pageSize);
    const disabled = signedLoginEnabled ? "" : `disabled title="${unavailable}"`;
    el("rows").innerHTML = keys.map((key, index) => `<tr>
      <td>${escapeHtml(key.source)} / ${escapeHtml(key.userId)}</td>
      <td><code>${escapeHtml(key.publishId)}</code></td><td><code>${escapeHtml(key.fingerprint)}</code></td>
      <td>${key.enabled ? (configuration.enabled ? "Key enabled" : "Key enabled · Login unavailable") : "Key disabled"}</td><td><div class="credentials-key-actions">
      <button type="button" class="create-button secondary" data-key-action="rotate" data-index="${index}" data-requires-signed-login ${disabled}>Rotate</button>
      <button type="button" class="create-button secondary" data-key-action="toggle" data-index="${index}" ${key.enabled ? "" : `data-requires-signed-login ${disabled}`}>${key.enabled ? "Disable" : "Enable"}</button>
      <button type="button" class="create-button secondary" data-key-action="delete" data-index="${index}">Delete</button>
      </div></td></tr>`).join("");
    el("empty").hidden = keys.length !== 0;
    el("table").hidden = keys.length === 0;
    el("pagination").hidden = offset === 0 && keys.length === 0;
    el("page").textContent = `${keys.length ? offset + 1 : 0}–${offset + keys.length}`;
    el("page-label").textContent = `Page ${Math.floor(offset / pageSize) + 1}`;
  }

  function editKey(key = null) {
    selectedKey = key;
    el("source").value = key ? key.source : "Local";
    el("user").value = key ? key.userId : "";
    el("source").readOnly = el("user").readOnly = Boolean(key);
    el("key").value = "";
    el("enabled").checked = key ? key.enabled : false;
    el("enabled-field").hidden = Boolean(key);
    el("form-title").textContent = key ? "Rotate public key" : "Create signing key";
    el("save").textContent = key ? "Rotate public key" : "Save public key";
    el("mode").textContent = key
      ? `Rotate ${key.source} / ${key.userId}. The publish ID and enabled state are preserved. Existing signed-login tokens will be revoked.`
      : "Bind a public key to an existing user. To replace a registered key, use Rotate in the key list.";
  }

  function openKeyForm(key = null, trigger = el("create")) {
    if (busy || !signedLoginEnabled) return;
    returnFocus = trigger;
    editKey(key);
    el("form-status").textContent = "";
    ownerPicker ||= root.KkUserPicker.create({ source: el("source"), user: el("user"), status: el("owner-status") });
    ownerPicker.open(key);
    openFormModal("ohpm-login-form", "ohpm-login-key");
  }

  function closeKeyForm(restoreFocus = true) {
    ownerPicker?.close();
    closeFormModal("ohpm-login-form");
    editKey();
    el("form-status").textContent = "";
    if (restoreFocus) (returnFocus?.isConnected ? returnFocus : el("create")).focus();
  }

  function dismissKeyForm() {
    if (!busy) closeKeyForm();
  }

  async function findOwner(source, userId) {
    for (let pageOffset = 0; ; pageOffset += 100) {
      const page = await request(`${api}/keys?offset=${pageOffset}`);
      const key = page.find((entry) => entry.source === source && entry.userId === userId);
      if (key || page.length < 100) return key;
    }
  }

  function confirmKey(key, action) {
    const deleting = action === "Delete";
    return root.kkrepoConfirmDelete({
      title: `${action} signing key?`,
      confirmLabel: action,
      description: `${action} the key for ${key.source} / ${key.userId}. Existing signed-login tokens will be revoked. `
        + (deleting ? "Registering again creates a new publish ID." : action === "Disable"
          ? "Signed login will be unavailable until this key is enabled again."
          : "Publishers must use the replacement private key to sign in again. The current enabled state is preserved."),
      warning: deleting ? "Deletion is permanent. This action cannot be undone."
        : "Publishing jobs using the current signed-login token will need to authenticate again.",
      details: [
        { label: "Owner", value: `${key.source} / ${key.userId}` },
        { label: "Publish ID", value: key.publishId },
        { label: "SHA-256 fingerprint", value: key.fingerprint }
      ]
    });
  }

  async function run(action, message = "", pending = "Loading signing keys…", status = el("status")) {
    if (busy) return;
    busy = true;
    const formOperation = status === el("form-status");
    el("view").querySelectorAll("input, textarea, select, button").forEach((element) => { element.disabled = true; });
    status.dataset.state = "pending";
    status.textContent = pending;
    try {
      const outcome = await action();
      if (status === el("form-status") && el("form").hidden) status = el("status");
      status.dataset.state = "success";
      status.textContent = outcome || message;
    } catch (error) {
      if (status === el("form-status") && el("form").hidden) status = el("status");
      status.dataset.state = "error";
      status.textContent = error.message;
    } finally {
      busy = false;
      el("view").querySelectorAll("input, textarea, select, button").forEach((element) => { element.disabled = false; });
      el("previous").disabled = offset === 0;
      el("next").disabled = !hasNext;
      el("page-size").value = String(pageSize);
      el("create").disabled = el("save").disabled = !signedLoginEnabled;
      el("create").title = signedLoginEnabled ? "" : unavailable;
      el("view").querySelectorAll("[data-requires-signed-login]").forEach((button) => {
        button.disabled = !signedLoginEnabled;
        button.title = signedLoginEnabled ? "" : unavailable;
      });
      ownerPicker?.sync();
      if (formOperation && el("form").hidden) (returnFocus?.isConnected ? returnFocus : el("create")).focus();
    }
  }

  async function load() {
    if (!initialized) {
      initialized = true;
      el("form").addEventListener("submit", (event) => {
        event.preventDefault();
        if (!signedLoginEnabled || !ownerPicker?.validate()) return;
        return run(async () => {
          const body = command(el("source").value, el("user").value, el("key").value, el("enabled").checked);
          const existing = selectedKey || await findOwner(body.source, body.userId);
          if (existing && !await confirmKey(existing, "Rotate")) return "Rotation cancelled.";
          if (existing) {
            await request(`${api}/keys/${encodeURIComponent(existing.publishId)}/public-key`, "PUT",
              { publicKey: body.publicKey });
          } else {
            await request(`${api}/keys`, "PUT", body);
          }
          closeKeyForm(false);
          await refresh(0);
          return existing ? "Public key rotated. Current enabled state preserved; previous signed-login tokens revoked."
            : "Public key saved.";
        }, "Public key saved.", "Saving public key…", el("form-status"));
      });
      bindFormModalDismiss("ohpm-login-form", dismissKeyForm);
      el("create").addEventListener("click", () => openKeyForm());
      el("cancel").addEventListener("click", dismissKeyForm);
      el("refresh").addEventListener("click", () => run(() => refresh()));
      el("previous").addEventListener("click", () => run(() => refresh(Math.max(0, offset - pageSize))));
      el("next").addEventListener("click", () => run(() => refresh(offset + pageSize)));
      el("page-size").addEventListener("change", () => run(() => refresh(0, Number(el("page-size").value))));
      el("rows").addEventListener("click", (event) => {
        const button = event.target.closest("button[data-key-action]");
        if (!button) return;
        const key = keys[Number(button.dataset.index)];
        if (!key || busy) return;
        if (!signedLoginEnabled && (button.dataset.keyAction === "rotate"
          || (button.dataset.keyAction === "toggle" && !key.enabled))) return;
        if (button.dataset.keyAction === "rotate") {
          openKeyForm(key, button);
          return;
        }
        return run(async () => {
          const path = `${api}/keys/${encodeURIComponent(key.publishId)}`;
          const action = button.dataset.keyAction === "delete" ? "Delete" : key.enabled ? "Disable" : "Enable";
          if (action !== "Enable" && !await confirmKey(key, action)) return `${action} cancelled.`;
          if (button.dataset.keyAction === "delete") await request(path, "DELETE");
          else await request(`${path}/enabled`, "PUT", { enabled: !key.enabled });
          if (selectedKey?.publishId === key.publishId) editKey();
          await refresh();
          return `Signing key ${action === "Delete" ? "deleted" : action === "Disable" ? "disabled" : "enabled"}.`;
        }, "Signing key updated.", "Updating signing key…");
      });
      editKey();
    }
    await run(refresh);
  }

  const exported = { load, command };
  if (typeof module !== "undefined" && module.exports) module.exports = exported;
  else root.KkOhpmLogin = exported;
})(typeof window === "undefined" ? globalThis : window);
