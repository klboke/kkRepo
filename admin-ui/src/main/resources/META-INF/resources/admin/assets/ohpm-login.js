(function (root) {
  "use strict";
  const api = "/internal/ohpm/login";
  const el = (name) => document.getElementById(`ohpm-login-${name}`);
  let initialized = false, busy = false, offset = 0, keys = [];

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

  async function refresh() {
    const configuration = await request(api);
    el("configuration").textContent = `Signed login is ${configuration.enabled ? "enabled" : "disabled"} on this server. `
      + `Token lifetime: ${configuration.tokenTtlSeconds}s. Timestamp tolerance: ${configuration.timestampToleranceSeconds}s.`;
    keys = await request(`${api}/keys?offset=${offset}`);
    el("rows").innerHTML = keys.map((key, index) => `<tr>
      <td>${escapeHtml(key.source)} / ${escapeHtml(key.userId)}</td>
      <td><code>${escapeHtml(key.publishId)}</code></td><td><code>${escapeHtml(key.fingerprint)}</code></td>
      <td>${key.enabled ? "Enabled" : "Disabled"}</td><td>
      <button type="button" class="create-button secondary" data-key-action="toggle" data-index="${index}">${key.enabled ? "Disable" : "Enable"}</button>
      <button type="button" class="create-button secondary" data-key-action="delete" data-index="${index}">Delete</button>
      </td></tr>`).join("");
    el("empty").hidden = keys.length !== 0;
    el("page").textContent = `Keys ${keys.length ? offset + 1 : 0}–${offset + keys.length}`;
  }

  async function run(action) {
    if (busy) return;
    busy = true;
    el("view").querySelectorAll("input, textarea, button").forEach((element) => { element.disabled = true; });
    el("status").textContent = "Working…";
    try {
      await action();
      el("status").textContent = "Saved keys are up to date.";
    } catch (error) {
      el("status").textContent = error.message;
    } finally {
      busy = false;
      el("view").querySelectorAll("input, textarea, button").forEach((element) => { element.disabled = false; });
      el("previous").disabled = offset === 0;
      el("next").disabled = keys.length < 100;
    }
  }

  async function load() {
    if (!initialized) {
      initialized = true;
      el("form").addEventListener("submit", (event) => {
        event.preventDefault();
        return run(async () => {
          const body = command(el("source").value, el("user").value, el("key").value, el("enabled").checked);
          await request(`${api}/keys`, "PUT", body);
          el("key").value = "";
          offset = 0;
          await refresh();
        });
      });
      el("refresh").addEventListener("click", () => run(refresh));
      el("previous").addEventListener("click", () => run(async () => { offset = Math.max(0, offset - 100); await refresh(); }));
      el("next").addEventListener("click", () => run(async () => { offset += 100; await refresh(); }));
      el("rows").addEventListener("click", (event) => {
        const button = event.target.closest("button[data-key-action]");
        if (!button) return;
        const key = keys[Number(button.dataset.index)];
        if (!key) return;
        return run(async () => {
          const path = `${api}/keys/${encodeURIComponent(key.publishId)}`;
          if (button.dataset.keyAction === "delete") await request(path, "DELETE");
          else await request(`${path}/enabled`, "PUT", { enabled: !key.enabled });
          await refresh();
        });
      });
    }
    await run(refresh);
  }

  const exported = { load, command };
  if (typeof module !== "undefined" && module.exports) module.exports = exported;
  else root.KkOhpmLogin = exported;
})(typeof window === "undefined" ? globalThis : window);
