(function (root) {
  "use strict";

  const el = id => document.getElementById(`offload-${id}`);
  const t = text => root.kkrepoI18n?.text(text) ?? text;
  const api = "/internal/download-offload";
  const requestHeader = "X-KkRepo-File-Offload";
  const probeHeader = "X-KkRepo-Offload-Probe";
  const proofHeader = "X-KkRepo-File-Served";
  const internal = "/_kkrepo_file_offload";
  const mount = "/srv/kkrepo-blobs";
  const targets = {
    "internal-uri": {
      example: `${internal}/<store-id>/<object-key>`,
      help: `The URI in the response header starts with the reserved ${internal}/ prefix, followed by the Blob Store ID and the file's relative path. `
        + "An internal proxy route maps the URI to the Blob Store's read-only mount."
    },
    "absolute-path": {
      example: `${mount}/<store-id>/<object-key>`,
      help: "The response header gives the file's absolute path on the proxy host or container. "
        + "Its Blob Store ID selects the read-only mount, and the object key is the file's relative path within that mount."
    }
  };
  const methodLogos = {
    nginx: "nginx.png",
    openresty: "openresty.webp",
    tengine: "tengine.png",
    angie: "angie.jpg",
    npm: "nginx-proxy-manager.png",
    ngf: "nginx-gateway-fabric.png",
    caddy: "caddy.png",
    apache: "apache-http-server.png",
    lighttpd: "lighttpd.png"
  };
  const nginxDocs = "https://nginx.org/en/docs/http/ngx_http_core_module.html#internal";
  const proxyDocs = {
    nginx: nginxDocs,
    openresty: "https://openresty.org/en/components.html",
    tengine: "https://tengine.taobao.org/document.html",
    angie: "https://en.angie.software/angie/docs/configuration/modules/http/http_proxy/",
    npm: "https://nginxproxymanager.com/advanced-config/#custom-nginx-configurations",
    ngf: "https://docs.nginx.com/nginx-gateway-fabric/traffic-management/snippets/",
    caddy: "https://caddyserver.com/docs/caddyfile/directives/reverse_proxy#examples",
    apache: "https://github.com/nmaier/mod_xsendfile",
    lighttpd: "https://redmine.lighttpd.net/projects/lighttpd/wiki/Docs_ModProxy"
  };
  const exampleNotes = {
    npm: "Add the header to the repository proxy location and the internal file routes to the Proxy Host.",
    ngf: "Add the header to the repository location and the file routes to the server context using enabled Snippets. Mount the stores on every gateway pod.",
    caddy: "Mount each selected store under /srv/kkrepo-offload/_kkrepo_file_offload/<store-id>.",
    apache: "Enable mod_xsendfile and mod_headers. Apply these settings in the virtual host that proxies kkRepo.",
    lighttpd: "Enable mod_proxy and mod_setenv. Limit X-Sendfile to the read-only mounts of the selected stores.",
    custom: "Configure these headers in your trusted proxy and restrict access to the selected read-only file mounts."
  };
  let saved;
  let loadRevision = 0;
  let saving = false;
  let probe;

  function canEdit() {
    return canAdmin("nexus:settings:update");
  }

  async function settingsRequest(options) {
    const response = await fetch(api, options);
    if (!response.ok) throw new Error(await responseErrorMessage(response));
    return response.json();
  }

  function option(value, label) {
    return new Option(label, value);
  }

  function renderMethodOptions(methods) {
    closeMethodPicker();
    el("method-value").removeAttribute("data-method");
    el("method").replaceChildren(...methods.map(method => option(method.id, t(method.label))));
    el("method-options").replaceChildren(...methods.map(method => {
      const row = document.createElement("div");
      row.id = `offload-method-option-${method.id}`;
      row.className = "recipe-combobox-option";
      row.dataset.method = method.id;
      row.setAttribute("role", "option");

      const label = document.createElement("span");
      label.className = "recipe-selection";
      const source = methodLogos[method.id];
      const logo = document.createElement(source ? "img" : "span");
      logo.className = "offload-method-logo";
      if (source) {
        logo.alt = "";
        logo.src = `./assets/offload-logos/${source}`;
      } else {
        logo.classList.add("lucide-icon", "icon-sliders-horizontal");
        logo.setAttribute("aria-hidden", "true");
      }
      const copy = document.createElement("span");
      copy.className = "recipe-option-copy";
      const name = document.createElement("strong");
      name.textContent = t(method.label);
      copy.append(name);
      label.append(logo, copy);

      const check = document.createElement("span");
      check.className = "offload-method-check lucide-icon icon-check";
      check.setAttribute("aria-hidden", "true");
      row.append(label, check);
      return row;
    }));
  }

  function renderSelectedMethod(id) {
    const value = el("method-value");
    if (value.dataset.method === id) return;
    for (const row of el("method-options").children) {
      const selected = row.dataset.method === id;
      row.setAttribute("aria-selected", String(selected));
      if (selected) value.replaceChildren(row.firstElementChild.cloneNode(true));
    }
    value.dataset.method = id;
  }

  function activateMethodOption(row) {
    el("method-options").querySelector(".is-active")?.classList.remove("is-active");
    row.classList.add("is-active");
    el("method-trigger").setAttribute("aria-activedescendant", row.id);
    row.scrollIntoView({ block: "nearest", inline: "nearest" });
  }

  function openMethodPicker() {
    const selected = el("method-options").querySelector('[aria-selected="true"]');
    if (!selected || el("method-trigger").matches(":disabled")) return;
    el("method-popover").hidden = false;
    el("method-trigger").setAttribute("aria-expanded", "true");
    activateMethodOption(selected);
  }

  function closeMethodPicker() {
    el("method-popover").hidden = true;
    el("method-trigger").setAttribute("aria-expanded", "false");
    el("method-trigger").removeAttribute("aria-activedescendant");
  }

  function selectMethod(row) {
    if (!row || el("method-trigger").matches(":disabled")) return;
    closeMethodPicker();
    el("method-trigger").focus();
    const method = el("method");
    if (method.value === row.dataset.method) return;
    method.value = row.dataset.method;
    method.dispatchEvent(new Event("input", { bubbles: true }));
  }

  function handleMethodKey(event) {
    const open = !el("method-popover").hidden;
    if (event.key === "Tab") {
      closeMethodPicker();
      return;
    }
    if (["ArrowDown", "ArrowUp", "Home", "End"].includes(event.key)) {
      event.preventDefault();
      if (!open) openMethodPicker();
      if (el("method-popover").hidden) return;
      const rows = Array.from(el("method-options").children);
      const active = rows.findIndex(row => row.classList.contains("is-active"));
      if (event.key === "Home") activateMethodOption(rows[0]);
      else if (event.key === "End") activateMethodOption(rows.at(-1));
      else if (open) {
        const step = event.key === "ArrowDown" ? 1 : -1;
        activateMethodOption(rows[Math.max(0, Math.min(active + step, rows.length - 1))]);
      }
    } else if (open && ["Enter", " ", "Escape"].includes(event.key)) {
      event.preventDefault();
      if (event.key === "Escape") closeMethodPicker();
      else selectMethod(el("method-options").querySelector(".is-active"));
    }
  }

  function selectedStores() {
    return Array.from(el("store-rows").querySelectorAll("input:checked"), input => Number(input.value));
  }

  function draft() {
    return {
      revision: saved.revision,
      enabled: el("enabled").checked,
      method: el("method").value,
      customHeader: el("method").value === "custom" ? el("response").value.trim() : saved.customHeader,
      customTarget: el("target").value,
      storeIds: selectedStores().sort((a, b) => a - b)
    };
  }

  function contract(settings) {
    const method = saved.methods.find(item => item.id === settings.method);
    if (method.id === "custom") {
      return {
        method, responseHeader: settings.customHeader, target: settings.customTarget,
        marker: `custom; header=${settings.customHeader || "<response-header>"}; target=${settings.customTarget}`
      };
    }
    return { method, responseHeader: method.responseHeader, target: method.target, marker: method.id };
  }

  function makeBadge(text, state) {
    const badge = document.createElement("span");
    badge.className = `state-badge compact ${state}`;
    badge.textContent = t(text);
    return badge;
  }

  function renderDraft() {
    if (!saved) return;
    const current = draft();
    const wire = contract(current);
    const custom = current.method === "custom";
    const target = targets[wire.target];
    const editable = !saving && canEdit();
    el("enabled").disabled = !editable;
    el("fields").disabled = !editable || !current.enabled;
    if (el("fields").disabled) closeMethodPicker();
    renderSelectedMethod(current.method);
    el("request").textContent = `${requestHeader}: ${wire.marker}`;
    el("custom-response-fields").hidden = !custom;
    el("response-line").textContent = `${wire.responseHeader || "<response-header>"}: ${target.example}`;
    const help = t(target.help);
    el("response-help").dataset.tooltip = help;
    el("response-help").setAttribute("aria-label", help);
    for (const checkbox of el("store-rows").querySelectorAll("input")) {
      checkbox.disabled = !editable || !current.enabled;
    }
    const count = current.storeIds.length;
    el("select-all").disabled = !editable || !current.enabled || !saved.stores.length;
    el("select-all").checked = count > 0 && count === saved.stores.length;
    el("select-all").indeterminate = count > 0 && count < saved.stores.length;
    el("store-count").textContent = `${count} ${t("selected")}`;
    const changed = current.enabled !== saved.enabled || current.method !== saved.method
      || current.customHeader !== saved.customHeader || current.customTarget !== saved.customTarget
      || JSON.stringify(current.storeIds) !== JSON.stringify(saved.storeIds);
    el("save").disabled = !editable || !changed;
    el("discard").disabled = !editable || !changed;
  }

  function applySettings(value) {
    saved = value;
    renderMethodOptions(saved.methods);
    el("store-rows").replaceChildren();
    for (const store of saved.stores) {
      const row = el("store-rows").insertRow();
      const box = document.createElement("input");
      box.type = "checkbox";
      box.value = store.id;
      box.setAttribute("aria-label", store.name);
      row.insertCell().append(box);
      row.insertCell().textContent = store.name;
      row.insertCell().textContent = store.repositories.join(", ") || "–";
    }
    if (!saved.stores.length) {
      const cell = el("store-rows").insertRow().insertCell();
      cell.colSpan = 3;
      cell.textContent = t("No File Blob Stores available.");
    }
    reset();
    el("saved-state").replaceChildren(makeBadge(saved.enabled ? "Enabled" : "Disabled", saved.enabled ? "ok" : "warn"));
    el("trusted").textContent = saved.trustedProxies || t("None configured");
    el("example").disabled = false;
    el("verify").disabled = !saved.enabled || !saved.stores.some(store => saved.storeIds.includes(store.id));
  }

  function reset() {
    el("enabled").checked = saved.enabled;
    el("method").value = saved.method;
    el("response").value = saved.customHeader;
    el("target").value = saved.customTarget;
    for (const checkbox of el("store-rows").querySelectorAll("input")) {
      checkbox.checked = saved.storeIds.includes(Number(checkbox.value));
    }
    el("status").textContent = "";
    renderDraft();
  }

  async function load() {
    const revision = ++loadRevision;
    try {
      const value = await settingsRequest();
      if (revision === loadRevision) applySettings(value);
    } catch (error) {
      if (revision === loadRevision) el("status").textContent = error.message;
    }
  }

  function nginxExample(wire, ids) {
    const preserved = [
      "ETag", "Last-Modified", "Docker-Content-Digest", "Docker-Distribution-Api-Version",
      "Digest", "Content-Version", "Content-Location", "Link", "X-Swift-Package-Signature-Format",
      "X-KkRepo-Source-Repository", "X-Checksum-Md5", "X-Checksum-Sha1", "X-Checksum-Sha256",
      "X-Checksum-Sha512", proofHeader
    ];
    const headers = preserved.map(name => `    add_header ${name} $upstream_http_${name.toLowerCase().replaceAll("-", "_")};`);
    const locations = ids.map(id => [
      `location ^~ ${internal}/${id}/ {`, "    internal;", `    alias ${mount}/${id}/;`,
      "    sendfile on;", "    disable_symlinks on;", "    etag off;",
      "    if_modified_since off;", "    max_ranges 0;", ...headers, "}"
    ].join("\n"));
    return [
      "# In the existing kkRepo proxy location",
      `proxy_set_header ${requestHeader} ${wire.marker};`, "proxy_cache off;",
      "", "# In the same virtual host, with each selected store mounted read-only", ...locations
    ].join("\n");
  }

  function deploymentExample(wire, ids) {
    if (wire.method.id === "caddy") {
      return [
        "reverse_proxy kkrepo:8080 {",
        `    header_up ${requestHeader} caddy`,
        "    @offload {", "        status 200", `        header X-Accel-Redirect ${internal}/*`, "    }",
        "    handle_response @offload {", "        root * /srv/kkrepo-offload", "        rewrite {rp.header.X-Accel-Redirect}",
        "        header >Last-Modified {rp.header.Last-Modified}",
        "        header >ETag {rp.header.ETag}",
        "        copy_response_headers {", "            exclude X-Accel-Redirect Content-Length", "        }",
        "        file_server", "    }", "}",
        ...ids.map(id => `# Read-only mount at /srv/kkrepo-offload${internal}/${id}`)
      ].join("\n");
    }
    if (wire.method.id === "apache") {
      return ["XSendFile On", ...ids.map(id => `XSendFilePath ${mount}/${id}`),
        `RequestHeader set ${requestHeader} "apache"`].join("\n");
    }
    if (wire.method.id === "lighttpd") {
      const roots = ids.map(id => `"${mount}/${id}/"`).join(", ");
      return [
        `setenv.set-request-header = ( "${requestHeader}" => "lighttpd" )`,
        'proxy.server = ( "" => ((', '    "host" => "kkrepo",', '    "port" => 8080,',
        '    "x-sendfile" => "enable",', `    "x-sendfile-docroot" => ( ${roots} )`, ")) )"
      ].join("\n");
    }
    if (wire.method.id === "custom") {
      const prefix = wire.target === "internal-uri" ? internal : mount;
      return [
        "# Proxy → kkRepo", `${requestHeader}: ${wire.marker}`, "",
        "# kkRepo → proxy following authorization",
        `${wire.responseHeader || "<response-header>"}: ${prefix}/${ids[0]}/repositories/<object-key>`, "",
        `# Preserve ${proofHeader} on the delivered response for verification.`
      ].join("\n");
    }
    return nginxExample(wire, ids);
  }

  function showExample() {
    const current = draft();
    const wire = contract(current);
    const ids = current.storeIds.length ? current.storeIds : ["<store-id>"];
    el("example-title").textContent = `${wire.method.label} ${t("deployment example")}`;
    el("example-note").textContent = t(exampleNotes[wire.method.id]
      || "Mount each selected store read-only on the proxy and add its internal file route.");
    el("example-code").value = deploymentExample(wire, ids);
    const docs = proxyDocs[wire.method.id];
    el("proxy-docs").hidden = !docs;
    if (docs) el("proxy-docs").href = docs;
    el("example-dialog").showModal();
  }

  function setProbeResult(message, tone = "") {
    const result = el("probe-result");
    result.classList.remove("ok", "warn", "bad");
    if (tone) result.classList.add(tone);
    result.textContent = message;
  }

  function probeHttpTone(status) {
    return status >= 400 && status < 500 ? "warn" : "bad";
  }

  function showVerification() {
    const stores = saved.stores.filter(store => saved.storeIds.includes(store.id));
    el("probe-store").replaceChildren(...stores.map(store => option(store.id, store.name)));
    setProbeResult("");
    el("verify-dialog").showModal();
  }

  async function verify(event) {
    event.preventDefault();
    const id = Number(el("probe-store").value);
    const path = el("probe-path").value.trim();
    let ownOrigin;
    try {
      ownOrigin = new URL(path, root.location.origin);
    } catch (_) {
      // Only a valid same-origin path can be probed.
    }
    if (!path.startsWith("/") || ownOrigin?.origin !== root.location.origin || ownOrigin.hash) {
      setProbeResult(t("Enter an artifact path on this server."), "warn");
      return;
    }
    const nonce = Array.from(crypto.getRandomValues(new Uint8Array(16)),
      byte => byte.toString(16).padStart(2, "0")).join("");
    const operation = new AbortController();
    probe = operation;
    const revision = saved.revision;
    const report = (message, tone) => {
      if (probe === operation && saved.revision === revision && !operation.signal.aborted) {
        setProbeResult(message, tone);
      }
    };
    const options = { redirect: "error", cache: "no-store", signal: operation.signal };
    el("probe-run").disabled = true;
    setProbeResult(t("Verifying download…"));
    try {
      const head = await fetch(ownOrigin, { ...options, method: "HEAD" });
      if (head.status !== 200) {
        report(`${t("Artifact is unavailable")}. HTTP ${head.status}`, probeHttpTone(head.status));
        return;
      }
      const length = Number(head.headers.get("Content-Length"));
      if (!Number.isSafeInteger(length) || length <= 0) {
        report(t("Use a non-empty artifact with a known size."), "warn");
        return;
      }
      const response = await fetch(ownOrigin, { ...options, headers: { [probeHeader]: nonce } });
      if (response.status !== 200) {
        report(`${t("Download failed")}. HTTP ${response.status}`, probeHttpTone(response.status));
        return;
      }
      if (response.headers.get(proofHeader) !== `${nonce}:${id}`) {
        await response.body?.cancel();
        report(t("Offload was not confirmed for this Blob Store. Check the proxy and artifact path."), "warn");
        return;
      }
      const reader = response.body.getReader();
      let received = 0;
      for (;;) {
        const next = await reader.read();
        if (next.done) break;
        received += next.value.byteLength;
      }
      if (received !== length) throw new Error(t("The proxy returned an incomplete download."));
      report(`${t("Proxy delivery verified")}. ${length.toLocaleString()} ${t("bytes received")}.`, "ok");
    } catch (error) {
      report(error.message, "bad");
    } finally {
      if (probe === operation) {
        probe = null;
        el("probe-run").disabled = false;
      }
    }
  }

  el("method-trigger").addEventListener("click", () => {
    if (el("method-popover").hidden) openMethodPicker();
    else closeMethodPicker();
  });
  el("method-trigger").addEventListener("keydown", handleMethodKey);
  el("method-options").addEventListener("click", event => selectMethod(event.target.closest('[role="option"]')));
  document.addEventListener("pointerdown", event => {
    if (!el("method-picker").contains(event.target) && !el("method-popover").hidden) closeMethodPicker();
  });

  el("settings-form").addEventListener("input", event => {
    if (!saved) return;
    if (event.target === el("method") && el("method").value === "custom") {
      el("response").value = saved.customHeader;
    }
    if (event.target === el("select-all")) {
      for (const checkbox of el("store-rows").querySelectorAll("input")) checkbox.checked = event.target.checked;
    }
    el("status").textContent = "";
    renderDraft();
  });
  el("settings-form").addEventListener("submit", async event => {
    event.preventDefault();
    if (saving || !saved || !canEdit()) return;
    const current = draft();
    if (current.enabled && !current.storeIds.length) {
      el("status").textContent = t("Select at least one File Blob Store.");
      return;
    }
    saving = true;
    ++loadRevision;
    renderDraft();
    try {
      const value = await settingsRequest({
        method: "PUT", headers: { "Content-Type": "application/json" }, body: JSON.stringify(current)
      });
      ++loadRevision;
      applySettings(value);
      el("status").textContent = t("Download offload settings saved.");
    } catch (error) {
      el("status").textContent = error.message;
    } finally {
      saving = false;
      renderDraft();
    }
  });
  el("discard").addEventListener("click", reset);
  el("example").addEventListener("click", showExample);
  el("copy-example").addEventListener("click", async () => {
    const code = el("example-code");
    try {
      await navigator.clipboard.writeText(code.value);
    } catch (_) {
      code.focus();
      code.select();
      document.execCommand("copy");
    }
  });
  el("verify").addEventListener("click", showVerification);
  el("probe-form").addEventListener("submit", verify);
  el("probe-close").addEventListener("click", () => el("verify-dialog").close());
  el("verify-dialog").addEventListener("close", () => {
    probe?.abort();
    probe = null;
    el("probe-run").disabled = false;
  });

  root.KkDownloadOffload = { load };
})(window);
