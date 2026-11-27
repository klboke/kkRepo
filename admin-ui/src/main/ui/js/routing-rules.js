import { bindFormModalDismiss, closeFormModal, openFormModal } from "./controls/modal.js";
import { showToast } from "./controls/toast.js";
import { responseErrorMessage } from "./shell/http.js";

const api = "/service/rest/v1/routing-rules";
const el = (id) => document.getElementById(id);
let rules = [], editing = null, initialized = false, saving = false;
let loadSequence = 0, previewSequence = 0, optionsSequence = 0;

function matcherLines(value) { return value.split(/\r?\n/).filter((line) => line.length > 0); }
async function request(url, method = "GET", body) {
  const response = await fetch(url, { method,
    ...(body === undefined ? {} : { headers: { "Content-Type": "application/json" }, body: JSON.stringify(body) }) });
  if (!response.ok) throw new Error(await responseErrorMessage(response));
  const text = await response.text(); return text ? JSON.parse(text) : null;
}
function cell(row, text) { const td = document.createElement("td"); td.textContent = text; row.append(td); return td; }
function action(parent, title, handler) {
  const button = document.createElement("button"); button.type = "button";
  button.className = "create-button secondary"; button.textContent = title;
  button.addEventListener("click", handler); parent.append(button);
}
function render() {
  const body = el("routing-rule-table"); body.replaceChildren();
  const query = el("routing-rule-filter").value.toLowerCase();
  const visible = rules.filter((rule) => [rule.name, rule.description, ...rule.matchers]
    .some((value) => value.toLowerCase().includes(query)));
  visible.forEach((rule) => {
    const row = document.createElement("tr");
    cell(row, rule.name); cell(row, rule.mode); cell(row, rule.matchers.join("\n")).className = "routing-rule-matchers";
    cell(row, rule.description || "—");
    const actions = cell(row, ""); actions.className = "selector-actions";
    action(actions, "Edit", () => edit(rule)); action(actions, "Delete", () => remove(rule)); body.append(row);
  });
  if (!visible.length) { const row = document.createElement("tr"); cell(row, "No routing rules found.").colSpan = 5; body.append(row); }
}
function invalidatePreview() {
  previewSequence++; el("routing-rule-preview-status").textContent = "";
  el("routing-rule-preview").disabled = false;
}
function close() { previewSequence++; closeFormModal("routing-rule-form"); }
async function edit(rule) {
  editing = rule?.name || null; el("routing-rule-form").reset();
  el("routing-rule-form-title").textContent = rule ? "Edit routing rule" : "Create routing rule";
  el("routing-rule-name").value = rule?.name || "";
  el("routing-rule-name").readOnly = Boolean(rule);
  el("routing-rule-description").value = rule?.description || "";
  el("routing-rule-mode").value = rule?.mode || "BLOCK";
  el("routing-rule-matchers").value = rule?.matchers.join("\n") || "";
  el("routing-rule-error").textContent = ""; el("routing-rule-used-by").textContent = "";
  invalidatePreview(); openFormModal("routing-rule-form", rule ? "routing-rule-matchers" : "routing-rule-name");
  if (rule) {
    try {
      const names = await request(`/internal/routing-rules/${encodeURIComponent(rule.name)}/repositories`);
      if (editing === rule.name) el("routing-rule-used-by").textContent = names.length
        ? `Used by: ${names.join(", ")}. Changes apply to all these proxies.` : "This rule is not assigned to a repository.";
    } catch (error) { if (editing === rule.name) el("routing-rule-error").textContent = error.message; }
  }
}
async function preview() {
  const sequence = ++previewSequence;
  el("routing-rule-preview").disabled = true;
  el("routing-rule-preview-status").textContent = "Testing path…";
  try {
    const result = await request("/internal/routing-rules/preview", "POST", {
      mode: el("routing-rule-mode").value, matchers: matcherLines(el("routing-rule-matchers").value),
      path: el("routing-rule-test-path").value });
    if (sequence !== previewSequence) return;
    el("routing-rule-preview-status").textContent = `${result.allowed ? "Allowed" : "Blocked"}. `
      + (result.matchingExpressions.length ? `Matching expressions: ${result.matchingExpressions.join(", ")}` : "No expressions match.");
  } catch (error) { if (sequence === previewSequence) el("routing-rule-preview-status").textContent = error.message; }
  finally { if (sequence === previewSequence) el("routing-rule-preview").disabled = false; }
}
async function save(event) {
  event.preventDefault(); if (saving || !el("routing-rule-form").reportValidity()) return;
  saving = true; el("routing-rule-save").disabled = true;
  try {
    const input = { name: el("routing-rule-name").value.trim(), description: el("routing-rule-description").value,
      mode: el("routing-rule-mode").value, matchers: matcherLines(el("routing-rule-matchers").value) };
    await request(editing ? `${api}/${encodeURIComponent(editing)}` : api, editing ? "PUT" : "POST", input);
    close(); await load(); showToast("Routing rule saved.", "success");
  } catch (error) { el("routing-rule-error").textContent = error.message; }
  finally { saving = false; el("routing-rule-save").disabled = false; }
}
async function remove(rule) {
  if (!await globalThis.kkrepoConfirmDelete({ title: "Delete routing rule?",
    description: "Rules assigned to proxy repositories cannot be deleted. Remove those assignments first.",
    details: [{ label: "Routing rule", value: rule.name }] })) return;
  try { await request(`${api}/${encodeURIComponent(rule.name)}`, "DELETE"); await load(); }
  catch (error) { el("routing-rule-status").textContent = error.message; }
}
function init() {
  if (initialized) return; initialized = true;
  el("routing-rule-create").addEventListener("click", () => edit(null));
  el("routing-rule-refresh").addEventListener("click", load);
  el("routing-rule-filter").addEventListener("input", render);
  el("routing-rule-form").addEventListener("submit", save);
  el("routing-rule-preview").addEventListener("click", preview);
  for (const id of ["routing-rule-mode", "routing-rule-matchers", "routing-rule-test-path"]) el(id).addEventListener("input", invalidatePreview);
  bindFormModalDismiss("routing-rule-form", close);
}
async function load() {
  init(); const sequence = ++loadSequence; el("routing-rule-status").textContent = "Loading routing rules…";
  try { const next = await request(api); if (sequence !== loadSequence) return;
    rules = next; render(); el("routing-rule-status").textContent = "";
  } catch (error) { if (sequence === loadSequence) el("routing-rule-status").textContent = error.message; }
}
async function loadOptions(selected = "") {
  const sequence = ++optionsSequence, select = el("repository-routing-rule");
  select.replaceChildren(new Option("None", ""));
  if (selected) select.add(new Option(selected, selected));
  select.value = selected; select.disabled = true;
  el("repository-routing-rule-status").textContent = "Loading rules…";
  try {
    const choices = await request(api); if (sequence !== optionsSequence) return;
    select.replaceChildren(new Option("None", ""));
    choices.forEach((rule) => select.add(new Option(`${rule.name} (${rule.mode})`, rule.name)));
    if (selected && !choices.some((rule) => rule.name === selected)) select.add(new Option(`${selected} (unavailable)`, selected));
    select.value = selected; select.disabled = false; el("repository-routing-rule-status").textContent = "";
  } catch (error) { if (sequence === optionsSequence) el("repository-routing-rule-status").textContent = error.message; }
}

export { load, loadOptions, matcherLines };
