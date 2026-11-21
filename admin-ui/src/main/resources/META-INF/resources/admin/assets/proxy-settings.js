(function (root) {
  "use strict";
  const el = (id) => document.getElementById(id);
  const t = (text) => root.kkrepoI18n?.text(text) ?? text;
  const api = "/internal/proxy-settings";
  const repoApi = "/internal/repositories";
  const labels = {
    DIRECT: "Direct connection",
    INHERIT: "Use system settings",
    OVERRIDE: "Use repository settings"
  };
  const prefix = "repository-outbound-proxy";
  const scoped = "scoped-proxy";
  const fields = ["type", "host", "port", "username", "password", "password-clear"];
  let settings;
  let repositories = [];
  let bypassHosts = [];
  let editing;
  let batch;
  let previewing = false;
  let loadSequence = 0;

  const field = (group, key) => el(`${group}-${key}`);
  const repositoryMode = (proxy = {}) => proxy.outboundProxyMode
    || (proxy.outboundProxyHost && proxy.outboundProxyPort ? "OVERRIDE" : "DIRECT");
  const requiresDirect = (proxy) => proxy.allowedRedirectHosts?.includes("*") === true;
  const canTestConnection = (repo) => !requiresDirect(repo.proxy) && !repo.proxy.blockOutboundConnections;
  const repositoryUrl = (repo) => repo.proxy.remoteUrl;
  const canEdit = (repo) => canAdmin("nexus:*") ||
    canAdmin(`nexus:repository-admin:${String(repo.format).toLowerCase()}:${repo.name}:edit`);

  function resetProxy(group, proxy = {}) {
    field(group, "mode").value = repositoryMode(proxy);
    const type = (proxy.outboundProxyType || "").trim().toUpperCase();
    field(group, "type").value = type === "SOCKS5" ? "SOCKS" : type;
    field(group, "host").value = proxy.outboundProxyHost || "";
    field(group, "port").value = proxy.outboundProxyPort ?? "";
    field(group, "username").value = proxy.outboundProxyUsername || "";
    field(group, "password").value = "";
    field(group, "password").placeholder = proxy.outboundProxyPasswordConfigured ? t("Saved proxy password unchanged") : "";
    field(group, "password-clear").checked = false;
  }

  function refreshProxy(group, directOnly) {
    const select = field(group, "mode");
    for (const option of select.options) option.disabled = directOnly && option.value !== "DIRECT";
    if (directOnly) select.value = "DIRECT";
    const override = select.value === "OVERRIDE";
    for (const key of fields) field(group, key).disabled = !override;
    if (override && !field(group, "type").value) field(group, "type").value = "HTTP";
    field(group, "host").required = override;
    field(group, "port").required = override;
  }

  function collectProxy(group) {
    const mode = field(group, "mode").value;
    if (mode !== "OVERRIDE") return { mode };
    return {
      mode,
      type: field(group, "type").value,
      host: field(group, "host").value.trim(),
      port: field(group, "port").value === "" ? null : Number(field(group, "port").value),
      username: field(group, "username").value.trim(),
      password: field(group, "password").value || null,
      clearPassword: field(group, "password-clear").checked
    };
  }

  function repositoryPayload() {
    const proxy = collectProxy(prefix);
    return {
      outboundProxyMode: proxy.mode,
      ...(proxy.mode === "OVERRIDE" ? {
        outboundProxyType: proxy.type,
        outboundProxyHost: proxy.host,
        outboundProxyPort: proxy.port,
        outboundProxyUsername: proxy.username,
        outboundProxyPassword: proxy.password,
        outboundProxyPasswordConfigured: proxy.clearPassword ? false : null
      } : {})
    };
  }

  function refreshRepositoryForm() {
    const hosts = el("repository-allowed-redirect-hosts").value.split(",").map(host => host.trim());
    refreshProxy(prefix, hosts.includes("*"));
  }

  async function request(url, method = "GET", payload) {
    const response = await fetch(url, {
      method,
      ...(payload === undefined ? {} : {
        headers: { "Content-Type": "application/json" }, body: JSON.stringify(payload)
      })
    });
    if (!response.ok) throw new Error(await responseErrorMessage(response));
    return response.status === 204 ? null : response.json();
  }

  function cell(row, text = "") {
    const td = document.createElement("td");
    td.textContent = text;
    row.append(td);
    return td;
  }

  function action(label, click) {
    const button = document.createElement("button");
    button.type = "button";
    button.className = "row-action";
    button.textContent = t(label);
    button.addEventListener("click", click);
    return button;
  }

  function normalize(host) {
    const value = host.toLowerCase().replace(/\.$/, "");
    if (!value.includes(":")) return value;
    try {
      const literal = value.startsWith("[") ? value : `[${value}]`;
      return new URL(`http://${literal}`).hostname;
    } catch {
      return value;
    }
  }

  function route(repo, mode = repositoryMode(repo.proxy)) {
    if (mode === "DIRECT") return t("Direct connection");
    if (mode === "OVERRIDE") {
      const { outboundProxyType, outboundProxyHost, outboundProxyPort } = repo.proxy;
      return `${outboundProxyType} · ${outboundProxyHost}:${outboundProxyPort}`;
    }
    if (!settings.enabled) return `${t("Direct connection")} · ${t("System proxy disabled")}`;
    const host = normalize(new URL(repositoryUrl(repo)).hostname);
    if (settings.bypassHosts.some(pattern => pattern.startsWith("*.")
        ? host.endsWith(pattern.slice(1)) : host === normalize(pattern))) {
      return `${t("Direct connection")} · ${t("Bypass host")}`;
    }
    return `${settings.type} · ${settings.host}:${settings.port}`;
  }

  function renderBypassHosts() {
    const container = el("system-proxy-bypass-hosts");
    container.replaceChildren();
    const readOnly = !canAdmin("nexus:settings:update");
    for (const host of bypassHosts) {
      const button = action(`${host} ×`, () => {
        bypassHosts = bypassHosts.filter(item => item !== host);
        renderBypassHosts();
        el("system-proxy-bypass").focus();
      });
      button.disabled = readOnly;
      button.setAttribute("aria-label", `${t("Remove bypass host")} ${host}`);
      container.append(button);
    }
  }

  function addBypassHost() {
    const input = el("system-proxy-bypass");
    const host = normalize(input.value.trim());
    if (!host) return;
    if (!bypassHosts.includes(host)) bypassHosts = [...bypassHosts, host];
    input.value = "";
    renderBypassHosts();
    input.focus();
  }

  function refreshSystemFields() {
    const editable = Boolean(settings) && canAdmin("nexus:settings:update");
    const enabled = el("system-proxy-enabled").checked;
    const auth = el("system-proxy-auth").checked;
    el("system-proxy-enabled").disabled = !editable;
    el("system-proxy-fields").disabled = !editable || !enabled;
    for (const id of ["user", "password", "clear"]) el(`system-proxy-${id}-field`).hidden = !auth;
    el("system-proxy-username").disabled = !auth;
    el("system-proxy-username").required = auth;
    el("system-proxy-save").disabled = !editable;
    el("system-proxy-test").disabled = !editable || !repositories.some(canTestConnection);
  }

  function renderSettings() {
    el("system-proxy-enabled").checked = settings.enabled;
    el("system-proxy-type").value = settings.type || "HTTP";
    el("system-proxy-host").value = settings.host || "";
    el("system-proxy-port").value = settings.port ?? "";
    el("system-proxy-auth").checked = Boolean(settings.username || settings.passwordConfigured);
    el("system-proxy-username").value = settings.username || "";
    el("system-proxy-password").value = "";
    el("system-proxy-password").placeholder = settings.passwordConfigured ? t("Saved proxy password unchanged") : "";
    el("system-proxy-password-clear").checked = false;
    el("system-proxy-bypass").value = "";
    bypassHosts = [...settings.bypassHosts];
    renderBypassHosts();
    refreshSystemFields();
  }

  function command() {
    const enabled = el("system-proxy-enabled").checked;
    if (!enabled) {
      const { revision, type, host, port, username } = settings;
      return { revision, enabled, type, host, port, username, bypassHosts: settings.bypassHosts };
    }
    const auth = el("system-proxy-auth").checked;
    const pending = normalize(el("system-proxy-bypass").value.trim());
    return {
      revision: settings.revision, enabled,
      type: el("system-proxy-type").value,
      host: el("system-proxy-host").value.trim(),
      port: Number(el("system-proxy-port").value),
      username: auth ? el("system-proxy-username").value.trim() : null,
      password: auth ? el("system-proxy-password").value || null : null,
      clearPassword: !auth || el("system-proxy-password-clear").checked,
      bypassHosts: pending && !bypassHosts.includes(pending) ? [...bypassHosts, pending] : bypassHosts
    };
  }

  function receiveRepositories(items) {
    repositories = items.filter(repo => String(repo.type).toUpperCase() === "PROXY" && repo.proxy)
      .sort((a, b) => a.name.localeCompare(b.name));
    for (const mode of Object.keys(labels)) {
      el(`system-proxy-count-${mode.toLowerCase()}`).textContent =
        repositories.filter(repo => repositoryMode(repo.proxy) === mode).length;
    }
    el("system-proxy-list").disabled = !settings;
    el("system-proxy-batch").disabled = !repositories.some(eligible);
    refreshSystemFields();
    if (el("proxy-repository-list").open) renderRepositoryList();
  }

  async function load() {
    const sequence = ++loadSequence;
    settings = undefined;
    receiveRepositories([]);
    const report = el("system-proxy-status");
    report.textContent = t("Loading proxy settings…");
    try {
      const [saved, repos] = await Promise.all([request(api), request(`${repoApi}?purpose=admin`)]);
      if (sequence !== loadSequence) return;
      settings = saved;
      receiveRepositories(repos);
      renderSettings();
      report.textContent = "";
    } catch (error) {
      if (sequence === loadSequence) report.textContent = error.message;
    }
  }

  async function saveSettings(event) {
    event.preventDefault();
    const button = el("system-proxy-save");
    button.disabled = true;
    try {
      settings = await request(api, "PUT", command());
      renderSettings();
      showToast(t("Proxy settings saved."), "ok");
      el("system-proxy-status").textContent = "";
    } catch (error) {
      el("system-proxy-status").textContent = error.message;
    } finally {
      refreshSystemFields();
    }
  }

  function renderRepositoryList() {
    const mode = el("proxy-repository-mode-filter").value;
    const body = el("proxy-repository-list-body");
    body.replaceChildren();
    for (const repo of repositories.filter(item => !mode || repositoryMode(item.proxy) === mode)) {
      const row = body.insertRow();
      cell(row, repo.name);
      cell(row, t(labels[repositoryMode(repo.proxy)]));
      cell(row, route(repo));
      const button = action("Edit proxy", () => openRepositoryEdit(repo));
      button.dataset.repository = repo.name;
      button.disabled = !canEdit(repo);
      cell(row).append(button);
    }
    if (!body.rows.length) cell(body.insertRow(), t("No proxy repositories found.")).colSpan = 4;
  }

  function openRepositoryList(reset = true) {
    if (reset) el("proxy-repository-mode-filter").value = "";
    renderRepositoryList();
    el("proxy-repository-list").showModal();
    el("proxy-repository-mode-filter").focus();
  }

  function openRepositoryEdit(repo) {
    editing = repo;
    el("proxy-repository-list").close();
    el("proxy-repository-edit-name").textContent = repo.name;
    el("proxy-repository-edit-status").textContent = requiresDirect(repo.proxy)
      ? t("This repository requires a direct connection.") : "";
    resetProxy(scoped, repo.proxy);
    refreshProxy(scoped, requiresDirect(repo.proxy));
    el("proxy-repository-edit").showModal();
    field(scoped, "mode").focus();
  }

  function closeRepositoryEdit() {
    const name = editing.name;
    editing = undefined;
    field(scoped, "password").value = "";
    el("proxy-repository-edit").close();
    openRepositoryList(false);
    [...el("proxy-repository-list-body").querySelectorAll("[data-repository]")]
      .find(button => button.dataset.repository === name)?.focus();
  }

  async function saveRepositoryEdit(event) {
    event.preventDefault();
    const repo = editing;
    const proxy = collectProxy(scoped);
    const button = el("proxy-repository-save");
    button.disabled = true;
    try {
      await request(`${repoApi}/${encodeURIComponent(repo.name)}/outbound-proxy`, "PUT",
          { ...proxy, expectedMode: repositoryMode(repo.proxy) });
      receiveRepositories(await request(`${repoApi}?purpose=admin`));
      closeRepositoryEdit();
      showToast(t("Repository proxy saved."), "ok");
    } catch (error) {
      el("proxy-repository-edit-status").textContent = error.message;
    } finally {
      button.disabled = false;
    }
  }

  function eligible(repo) {
    return canEdit(repo) && repositoryMode(repo.proxy) !== "INHERIT" && !requiresDirect(repo.proxy);
  }

  function renderBatch() {
    const candidates = repositories.filter(eligible);
    const visible = previewing ? candidates.filter(repo => batch.has(repo.name)) : candidates;
    const body = el("proxy-batch-body");
    body.replaceChildren();
    for (const repo of visible) {
      const row = body.insertRow();
      const nameCell = cell(row);
      if (previewing) nameCell.textContent = repo.name;
      else {
        const input = document.createElement("input");
        input.type = "checkbox";
        input.checked = batch.has(repo.name);
        input.addEventListener("change", () => {
          if (input.checked) batch.set(repo.name, repositoryMode(repo.proxy));
          else batch.delete(repo.name);
          updateBatchSelection();
        });
        const label = document.createElement("label");
        label.className = "checkbox-field";
        const name = document.createElement("span");
        name.textContent = repo.name;
        label.append(input, name);
        nameCell.append(label);
      }
      cell(row, route(repo));
      cell(row, route(repo, "INHERIT"));
    }
    el("proxy-batch-select-direct").hidden = previewing;
    el("proxy-batch-back").hidden = !previewing;
    el("proxy-batch-next").textContent = t(previewing ? "Confirm switch" : "Preview changes");
    el("proxy-batch-status").textContent = previewing && [...batch.values()].includes("OVERRIDE")
      ? t("Saved repository proxy settings are kept for later use.") : "";
    updateBatchSelection();
  }

  function updateBatchSelection() {
    el("proxy-batch-count").textContent = String(batch.size);
    el("proxy-batch-next").disabled = batch.size === 0;
  }

  async function nextBatch() {
    if (!previewing) {
      previewing = true;
      renderBatch();
      return;
    }
    el("proxy-batch-next").disabled = true;
    try {
      await request(`${repoApi}/outbound-proxy/batch`, "PUT", Object.fromEntries(batch));
      receiveRepositories(await request(`${repoApi}?purpose=admin`));
      el("proxy-batch-dialog").close();
      showToast(t("Repositories now use system proxy settings."), "ok");
    } catch (error) {
      el("proxy-batch-status").textContent = error.message;
      el("proxy-batch-next").disabled = false;
    }
  }

  function openConnectionTest() {
    if (!el("system-proxy-form").reportValidity()) return;
    el("proxy-test-result").textContent = "";
    const select = el("proxy-test-repository");
    select.replaceChildren();
    for (const repo of repositories.filter(canTestConnection)) {
      select.add(new Option(repo.name, repo.name));
    }
    el("proxy-test-dialog").showModal();
  }

  async function testConnection(event) {
    event.preventDefault();
    const name = el("proxy-test-repository").value;
    const report = el("proxy-test-result");
    const button = el("proxy-test-run");
    button.disabled = true;
    report.textContent = t("Testing connection…");
    try {
      const result = await request(`${repoApi}/${encodeURIComponent(name)}/outbound-proxy/test`, "POST", command());
      const outcomes = {
        PROXY_AUTH_REQUIRED: "Proxy authentication failed",
        UPSTREAM_AUTH_REQUIRED: "Upstream authentication required",
        UPSTREAM_REACHED: "Reached upstream",
        PROXY_CONNECTION_FAILED: "Proxy connection failed",
        TIMEOUT: "Connection timed out",
        CONNECTION_FAILED: "Connection failed"
      };
      report.textContent = [t(outcomes[result.outcome]), result.status && `HTTP ${result.status}`,
        t(result.proxied ? "Via proxy" : "Direct connection")].filter(Boolean).join(" · ");
    } catch (error) {
      report.textContent = error.message;
    } finally {
      button.disabled = false;
    }
  }

  function bind() {
    el("system-proxy-form").addEventListener("submit", saveSettings);
    for (const name of ["enabled", "auth"]) el(`system-proxy-${name}`).addEventListener("change", refreshSystemFields);
    el("system-proxy-add-host").addEventListener("click", addBypassHost);
    el("system-proxy-bypass").addEventListener("keydown", event => {
      if (event.key === "Enter" && !event.isComposing) {
        event.preventDefault();
        addBypassHost();
      }
    });
    el("system-proxy-list").addEventListener("click", () => openRepositoryList());
    el("proxy-repository-mode-filter").addEventListener("change", renderRepositoryList);
    el("proxy-repository-edit-form").addEventListener("submit", saveRepositoryEdit);
    field(scoped, "mode").addEventListener("change", () => refreshProxy(scoped, requiresDirect(editing.proxy)));
    el("proxy-repository-edit").addEventListener("cancel", event => {
      event.preventDefault();
      if (!el("proxy-repository-save").disabled) closeRepositoryEdit();
    });
    document.querySelectorAll("[data-proxy-close]").forEach(button => button.addEventListener("click", () => {
      const dialog = button.closest("dialog");
      if (dialog.id === "proxy-repository-edit") {
        if (!el("proxy-repository-save").disabled) closeRepositoryEdit();
      } else dialog.close();
    }));
    el("system-proxy-test").addEventListener("click", openConnectionTest);
    el("proxy-test-form").addEventListener("submit", testConnection);
    el("system-proxy-batch").addEventListener("click", () => {
      batch = new Map();
      previewing = false;
      renderBatch();
      el("proxy-batch-dialog").showModal();
    });
    el("proxy-batch-select-direct").addEventListener("click", () => {
      repositories.filter(repo => eligible(repo) && repositoryMode(repo.proxy) === "DIRECT")
        .forEach(repo => batch.set(repo.name, "DIRECT"));
      renderBatch();
    });
    el("proxy-batch-back").addEventListener("click", () => { previewing = false; renderBatch(); });
    el("proxy-batch-next").addEventListener("click", nextBatch);
    root.addEventListener("kkrepo:i18n-change", () => {
      if (el("proxy-repository-list").open) renderRepositoryList();
      if (el("proxy-batch-dialog").open) renderBatch();
    });
  }

  root.KkProxySettings = {
    bind, load, repositoryPayload, refreshRepositoryForm,
    resetRepositoryForm(proxy) {
      resetProxy(prefix, proxy);
      refreshRepositoryForm();
    }
  };
})(window);
