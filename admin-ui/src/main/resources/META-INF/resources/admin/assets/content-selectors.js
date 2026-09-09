(function (root) {
  "use strict";
  const api = "/service/rest/v1/security/content-selectors";
  const element = (id) => document.getElementById(id);
  let options = { permissions: {}, repositories: [], usedBy: {} };
  let selectors = [];
  let editing = null;
  let initialized = false;
  let loadSequence = 0;
  let previewSequence = 0;
  let trigger = null;
  let saving = false;
  let savingPrivilege = false;

  // Exposed for the Node contract and shared by the actual request path.
  function privilegePayload(name, description, selector, scope, actions, repositories) {
    if (!actions.length) throw new Error("Select at least one action.");
    let format = "*";
    let repository = scope;
    if (scope.startsWith("*-")) { format = scope.slice(2); repository = "*"; }
    else if (scope !== "*") {
      const match = repositories.find((item) => item.name === scope);
      if (!match) throw new Error("Repository no longer exists. Refresh and try again.");
      format = match.format;
    }
    return { name, description, contentSelector: selector, format, repository, actions };
  }

  async function request(url, method = "GET", body) {
    const response = await fetch(url, {
      method,
      headers: body === undefined ? {} : { "Content-Type": "application/json" },
      ...(body === undefined ? {} : { body: JSON.stringify(body) })
    });
    if (!response.ok) throw new Error(await responseErrorMessage(response));
    return response.status === 204 || response.headers.get("content-length") === "0" ? null
      : response.text().then((text) => text ? JSON.parse(text) : null);
  }

  function setMessage(id, text) { element(id).textContent = text; }
  function cell(row, value, code = false) {
    const td = document.createElement("td");
    const content = code ? document.createElement("code") : document.createElement("span");
    content.textContent = value || "—";
    td.append(content); row.append(td);
    return td;
  }
  function button(parent, label, action, disabled) {
    const item = document.createElement("button");
    item.type = "button"; item.className = "create-button secondary";
    item.textContent = label; item.disabled = Boolean(disabled);
    item.addEventListener("click", action); parent.append(item);
  }
  function render() {
    const body = element("selector-table"); body.replaceChildren();
    const query = element("selector-filter").value.toLowerCase();
    const rows = selectors.filter((item) => [item.name, item.description, item.expression]
      .some((value) => String(value || "").toLowerCase().includes(query)));
    rows.forEach((selector) => {
      const row = document.createElement("tr");
      cell(row, selector.name, true); cell(row, selector.type);
      cell(row, selector.expression, true); cell(row, selector.description);
      const usedBy = Object.hasOwn(options.usedBy, selector.name) ? options.usedBy[selector.name] : [];
      cell(row, usedBy.join(", "));
      const actions = document.createElement("td"); actions.className = "selector-actions";
      button(actions, options.permissions.update ? "Edit" : "View", () => edit(selector));
      button(actions, "Create privilege", () => privilege(selector), !options.permissions.createPrivilege);
      button(actions, "Delete", () => remove(selector), !options.permissions.delete || usedBy.length > 0);
      row.append(actions); body.append(row);
    });
    if (!rows.length) {
      const row = document.createElement("tr");
      const td = cell(row, options.permissions.read ? "No content selectors found." : "Selector read permission is required to view the list.");
      td.colSpan = 6; body.append(row);
    }
  }
  function scopes(id, preview) {
    const select = element(id); select.replaceChildren();
    const add = (value, label) => { const option = document.createElement("option"); option.value = value; option.textContent = label; select.append(option); };
    add("*", "All repositories");
    [...new Set(options.repositories.map((item) => item.format))].sort()
      .forEach((format) => add(`*-${format}`, `All ${format} repositories`));
    options.repositories.filter((item) => !preview || item.type !== "group")
      .forEach((item) => add(item.name, `${item.name} (${item.format}, ${item.type})`));
  }
  function invalidatePreview() {
    previewSequence++;
    element("selector-preview").disabled = !(options.permissions.create || options.permissions.update);
    element("selector-preview-results").hidden = true;
    element("selector-preview-table").replaceChildren();
    setMessage("selector-preview-status", "");
  }
  function edit(selector) {
    trigger = document.activeElement; editing = selector;
    element("selector-form").reset();
    const editable = selector ? options.permissions.update : options.permissions.create;
    setMessage("selector-form-title", selector ? (editable ? "Edit selector" : "View selector") : "Create selector");
    element("selector-name").value = selector?.name || "";
    element("selector-name").readOnly = Boolean(selector);
    element("selector-type").value = selector?.type || "csel";
    element("selector-description").value = selector?.description || "";
    element("selector-expression").value = selector?.expression || 'format == "raw" and path =^ "/team/"';
    ["selector-description", "selector-expression"].forEach((id) => { element(id).readOnly = !editable; });
    element("selector-save").hidden = !editable;
    element("selector-legacy").hidden = !selector;
    scopes("selector-repository", true);
    invalidatePreview(); setMessage("selector-error", "");
    openFormModal("selector-form", selector ? "selector-expression" : "selector-name");
  }
  async function preview() {
    const sequence = ++previewSequence;
    const payload = { repository: element("selector-repository").value,
      type: element("selector-type").value, expression: element("selector-expression").value };
    element("selector-preview").disabled = true;
    setMessage("selector-preview-status", "Loading preview…");
    element("selector-preview-results").hidden = true;
    try {
      const result = await request("/service/rest/internal/ui/content-selectors/preview", "POST", payload);
      if (sequence !== previewSequence) return;
      const body = element("selector-preview-table"); body.replaceChildren();
      result.results.forEach((asset) => { const row = document.createElement("tr");
        cell(row, asset.repositoryName); cell(row, asset.format); cell(row, asset.name, true); body.append(row); });
      element("selector-preview-results").hidden = !result.results.length;
      setMessage("selector-preview-status", result.truncated
        ? "Preview is limited. Narrow the expression or repository to see more specific matches."
        : result.results.length ? "Preview complete." : "No matching stored assets.");
    } catch (error) { if (sequence === previewSequence) setMessage("selector-preview-status", error.message); }
    finally { if (sequence === previewSequence) element("selector-preview").disabled = false; }
  }
  async function save(event) {
    event.preventDefault();
    if (saving || !element("selector-form").reportValidity()) return;
    saving = true; element("selector-save").disabled = true; setMessage("selector-error", "");
    try {
      await request(editing ? `${api}/${encodeURIComponent(editing.name)}` : api, editing ? "PUT" : "POST", {
        name: element("selector-name").value.trim(), type: element("selector-type").value,
        description: element("selector-description").value, expression: element("selector-expression").value
      });
      saving = false; close("selector-form"); showToast("Content selector saved.", "success"); await load();
    } catch (error) { setMessage("selector-error", error.message); }
    finally { saving = false; element("selector-save").disabled = false; }
  }
  async function remove(selector) {
    if (!window.confirm(`Delete content selector "${selector.name}"?`)) return;
    try { await request(`${api}/${encodeURIComponent(selector.name)}`, "DELETE"); await load(); }
    catch (error) { setMessage("selector-status", error.message); }
  }
  function privilege(selector) {
    trigger = document.activeElement;
    element("selector-privilege-form").reset();
    element("selector-privilege-selector").value = selector.name;
    element("selector-privilege-name").value = `${selector.name}-read`;
    scopes("selector-privilege-repository", false);
    setMessage("selector-privilege-error", "");
    openFormModal("selector-privilege-form", "selector-privilege-name");
  }
  async function savePrivilege(event) {
    event.preventDefault();
    if (savingPrivilege || !element("selector-privilege-form").reportValidity()) return;
    savingPrivilege = true; element("selector-privilege-save").disabled = true;
    try {
      const payload = privilegePayload(element("selector-privilege-name").value.trim(),
        element("selector-privilege-description").value, element("selector-privilege-selector").value,
        element("selector-privilege-repository").value,
        [...document.querySelectorAll('[name="selector-action"]:checked')].map((item) => item.value), options.repositories);
      await request("/service/rest/v1/security/privileges/repository-content-selector", "POST", payload);
      savingPrivilege = false; close("selector-privilege-form");
      showToast("Privilege created. Assign it to a role in Security → Roles.", "success"); await load();
    } catch (error) { setMessage("selector-privilege-error", error.message); }
    finally { savingPrivilege = false; element("selector-privilege-save").disabled = false; }
  }
  function close(form) {
    if (form === "selector-form" ? saving : savingPrivilege) return;
    if (form === "selector-form") invalidatePreview();
    closeFormModal(form); trigger?.focus();
  }
  function init() {
    if (initialized) return; initialized = true;
    element("selector-create").addEventListener("click", () => edit(null));
    element("selector-refresh").addEventListener("click", load);
    element("selector-filter").addEventListener("input", render);
    element("selector-preview").addEventListener("click", preview);
    element("selector-expression").addEventListener("input", invalidatePreview);
    element("selector-repository").addEventListener("change", invalidatePreview);
    element("selector-form").addEventListener("submit", save);
    element("selector-privilege-form").addEventListener("submit", savePrivilege);
    for (const form of ["selector-form", "selector-privilege-form"]) bindFormModalDismiss(form, () => close(form));
  }
  async function load() {
    init(); const sequence = ++loadSequence;
    setMessage("selector-status", "Loading content selectors…");
    element("selector-create").disabled = true;
    try {
      const nextOptions = await request("/internal/security/content-selectors/options");
      const nextSelectors = nextOptions.permissions.read ? await request(api) : [];
      if (sequence !== loadSequence) return;
      options = nextOptions; selectors = nextSelectors;
      element("selector-create").disabled = !options.permissions.create;
      setMessage("selector-status", ""); render();
    } catch (error) {
      if (sequence !== loadSequence) return;
      selectors = []; element("selector-table").replaceChildren(); setMessage("selector-status", error.message);
    }
  }
  root.KkContentSelectors = { load, privilegePayload };
  if (typeof module !== "undefined") module.exports = { privilegePayload };
})(typeof window === "undefined" ? globalThis : window);
