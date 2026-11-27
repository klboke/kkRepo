import { bindTransferKeyboard } from "../controls/transfer.js";
import { escapeHtml, lowerOrEmpty } from "../controls/display.js";
import {
  clearRequiredFieldErrors,
  commaList,
  filterValue,
  parseJsonObject,
  validateRequiredFields,
} from "../controls/fields.js";
import { closeFormModal, openFormModal } from "../controls/modal.js";
import { showToast } from "../controls/toast.js";
import { fetchJson, responseErrorMessage } from "../shell/http.js";

let securityUsers = [];

let securityRoles = [];

let securityPrivileges = [];

const accessState = {
  securityRealms: [],
  securityUserRoleFilter: "",
  securityAnonymous: null,
};

let securityUserMode = "create";

let securityRoleMode = "create";

let securityPrivilegeMode = "create";

const BUILT_IN_READ_ONLY_ROLE_IDS = new Set(["nx-admin", "nx-anonymous"]);

function createTransferState() {
  return {
    selected: [],
    highlight: { available: new Set(), selected: new Set() },
    filter: ""
  };
}

const securityTransfers = {
  userRoles: createTransferState(),
  rolePrivileges: createTransferState(),
  roleRoles: createTransferState()
};

const securityUserRequiredFields = [
  { id: "security-user-id", label: "User ID" }
];

const securityRoleRequiredFields = [
  { id: "security-role-id", label: "Role ID" }
];

const securityPrivilegeRequiredFields = [
  { id: "security-privilege-id", label: "Privilege ID" }
];

const securityAnonymousRequiredFields = [
  { id: "security-anonymous-user-id", label: "User ID" },
  { id: "security-anonymous-realm-name", label: "Realm name" }
];

function isLocalSource(source) {
  return lowerOrEmpty(source) === "local";
}

function displaySource(source) {
  return source == null ? "" : String(source);
}

function accountSourceLabel(source) {
  const value = displaySource(source).trim();
  if (!value) return "Authenticated account";
  return `${value.charAt(0).toUpperCase()}${value.slice(1)} account`;
}

// ---- Security ------------------------------------------------------------
function listContainsFilter(values, filter) {
  if (window.kkrepoAdminFilters?.matchesFilter) {
    return window.kkrepoAdminFilters.matchesFilter(values, filter);
  }
  if (!filter) return true;
  return values.map((value) => lowerOrEmpty(value)).join(" ").includes(filter);
}

function renderSecurityUserSourceFilter() {
  const select = document.getElementById("security-user-source-filter");
  if (!select) return;
  const selected = select.value;
  const sources = Array.from(new Set(securityUsers.map((user) => user.source).filter(Boolean)))
    .sort((left, right) => {
      if (isLocalSource(left)) return -1;
      if (isLocalSource(right)) return 1;
      return displaySource(left).localeCompare(displaySource(right));
    });
  select.innerHTML = [
    '<option value="">All sources</option>',
    ...sources.map((source) => `<option value="${escapeHtml(source)}">${escapeHtml(displaySource(source))}</option>`)
  ].join("");
  select.value = sources.includes(selected) ? selected : "";
}

function ensureUserSourceOption(source) {
  const select = document.getElementById("security-user-source");
  if (!select || !source) return;
  if (Array.from(select.options).some((option) => option.value === source)) return;
  select.insertAdjacentHTML("beforeend", `<option value="${escapeHtml(source)}">${escapeHtml(displaySource(source))}</option>`);
}

function uniqueStringList(values) {
  const raw = Array.isArray(values) ? values : commaList(values);
  const result = [];
  const seen = new Set();
  for (const value of raw) {
    const text = String(value ?? "").trim();
    if (!text || seen.has(text)) continue;
    result.push(text);
    seen.add(text);
  }
  return result;
}

function compareTransferItems(left, right) {
  return left.label.localeCompare(right.label, undefined, { sensitivity: "base" });
}

function securityRoleCandidates(excludedRoleId = "") {
  const excluded = String(excludedRoleId || "").trim();
  const seen = new Set();
  return securityRoles
    .filter((role) => role?.roleId && role.roleId !== excluded)
    .filter((role) => {
      if (seen.has(role.roleId)) return false;
      seen.add(role.roleId);
      return true;
    })
    .map((role) => ({
      id: role.roleId,
      label: role.roleId,
      meta: role.readOnly ? "read-only" : "role",
      filterValues: [role.roleId, role.name, role.description, role.readOnly ? "read-only" : "role"]
    }))
    .sort(compareTransferItems);
}

function securityPrivilegeCandidates() {
  const seen = new Set();
  return securityPrivileges
    .filter((privilege) => privilege?.privilegeId)
    .filter((privilege) => {
      if (seen.has(privilege.privilegeId)) return false;
      seen.add(privilege.privilegeId);
      return true;
    })
    .map((privilege) => ({
      id: privilege.privilegeId,
      label: privilege.privilegeId,
      meta: privilege.type || "privilege",
      filterValues: [
        privilege.privilegeId,
        privilege.type,
        privilege.name,
        privilege.description,
        privilege.permission
      ]
    }))
    .sort(compareTransferItems);
}

function isBuiltInReadOnlyRoleId(roleId) {
  return BUILT_IN_READ_ONLY_ROLE_IDS.has(String(roleId || "").trim());
}

function securityTransferConfig(key) {
  if (key === "userRoles") {
    return {
      prefix: "user-role",
      state: securityTransfers.userRoles,
      inputId: "security-user-roles",
      candidates: () => securityRoleCandidates(),
      emptyAvailable: "No available roles",
      emptySelected: "No roles granted"
    };
  }
  if (key === "rolePrivileges") {
    return {
      prefix: "role-privilege",
      state: securityTransfers.rolePrivileges,
      inputId: "security-role-privileges",
      candidates: () => securityPrivilegeCandidates(),
      emptyAvailable: "No available privileges",
      emptySelected: "No privileges given"
    };
  }
  if (key === "roleRoles") {
    return {
      prefix: "role-contained",
      state: securityTransfers.roleRoles,
      inputId: "security-role-roles",
      candidates: () => securityRoleCandidates(document.getElementById("security-role-id")?.value),
      excludedId: () => document.getElementById("security-role-id")?.value?.trim() || "",
      emptyAvailable: "No available roles",
      emptySelected: "No contained roles"
    };
  }
  return null;
}

function syncSecurityTransferInput(config) {
  const input = document.getElementById(config.inputId);
  if (input) input.value = config.state.selected.join(", ");
}

function setSecurityTransferSelection(key, values) {
  const config = securityTransferConfig(key);
  if (!config) return;
  config.state.selected = uniqueStringList(values);
  config.state.highlight.available.clear();
  config.state.highlight.selected.clear();
  config.state.filter = "";
  const filter = document.getElementById(`${config.prefix}-filter`);
  if (filter) filter.value = "";
  refreshSecurityTransfer(key);
}

function transferItemMatchesFilter(item, filter) {
  return listContainsFilter(item.filterValues || [item.label, item.meta], filter);
}

function securityTransferRowHtml(config, item, side) {
  const highlighted = config.state.highlight[side].has(item.id);
  return `<li role="option" aria-selected="${highlighted}" class="member-row${highlighted ? " is-selected" : ""}" data-id="${escapeHtml(item.id)}" data-side="${side}"><span class="member-name">${escapeHtml(item.label)}</span><span class="member-type">${escapeHtml(item.meta || "")}</span></li>`;
}

function refreshSecurityTransfer(key) {
  const config = securityTransferConfig(key);
  if (!config) return;
  const availableEl = document.getElementById(`${config.prefix}-available-list`);
  const selectedEl = document.getElementById(`${config.prefix}-selected-list`);
  if (!availableEl || !selectedEl) return;
  const readOnly = isSecurityTransferReadOnly(key);

  const excludedId = typeof config.excludedId === "function" ? config.excludedId() : "";
  config.state.selected = uniqueStringList(config.state.selected).filter((id) => id !== excludedId);
  const candidates = config.candidates();
  const byId = new Map(candidates.map((item) => [item.id, item]));
  const selectedSet = new Set(config.state.selected);
  const filter = config.state.filter.trim().toLowerCase();
  const available = candidates.filter((item) =>
    !selectedSet.has(item.id) && transferItemMatchesFilter(item, filter));
  const availableIds = new Set(available.map((item) => item.id));
  config.state.highlight.available = new Set(
    [...config.state.highlight.available].filter((id) => availableIds.has(id)));
  config.state.highlight.selected = new Set(
    [...config.state.highlight.selected].filter((id) => selectedSet.has(id)));

  availableEl.innerHTML = available.length
    ? available.map((item) => securityTransferRowHtml(config, item, "available")).join("")
    : `<li class="member-empty">${filter ? "No matches" : config.emptyAvailable}</li>`;

  selectedEl.innerHTML = config.state.selected.length
    ? config.state.selected
      .map((id) => securityTransferRowHtml(config, byId.get(id) || { id, label: id, meta: "missing" }, "selected"))
      .join("")
    : `<li class="member-empty">${config.emptySelected}</li>`;

  document.getElementById(`${config.prefix}-available-count`).textContent = String(available.length);
  document.getElementById(`${config.prefix}-selected-count`).textContent = String(config.state.selected.length);
  document.getElementById(`${config.prefix}-add`).disabled = readOnly || config.state.highlight.available.size === 0;
  document.getElementById(`${config.prefix}-add-all`).disabled = readOnly || available.length === 0;
  document.getElementById(`${config.prefix}-remove`).disabled = readOnly || config.state.highlight.selected.size === 0;
  document.getElementById(`${config.prefix}-remove-all`).disabled = readOnly || config.state.selected.length === 0;
  syncSecurityTransferInput(config);
}

function refreshSecurityTransfers(keys = ["userRoles", "rolePrivileges", "roleRoles"]) {
  keys.forEach(refreshSecurityTransfer);
}

function isSecurityTransferReadOnly(key) {
  return securityRoleMode === "view" && (key === "rolePrivileges" || key === "roleRoles");
}

function toggleSecurityTransferHighlight(key, side, id, additive) {
  const config = securityTransferConfig(key);
  if (isSecurityTransferReadOnly(key)) return;
  if (!config) return;
  const set = config.state.highlight[side];
  if (additive) {
    if (set.has(id)) set.delete(id); else set.add(id);
  } else {
    set.clear();
    set.add(id);
  }
  config.state.highlight[side === "available" ? "selected" : "available"].clear();
}

function addSecurityTransferItems(key, ids) {
  const config = securityTransferConfig(key);
  if (isSecurityTransferReadOnly(key)) return;
  if (!config || !ids.length) return;
  const validIds = new Set(config.candidates().map((item) => item.id));
  const existing = new Set(config.state.selected);
  const added = [];
  for (const id of ids) {
    if (validIds.has(id) && !existing.has(id)) {
      config.state.selected.push(id);
      existing.add(id);
      added.push(id);
    }
  }
  config.state.highlight.available.clear();
  config.state.highlight.selected = new Set(added);
  refreshSecurityTransfer(key);
}

function removeSecurityTransferItems(key, ids) {
  const config = securityTransferConfig(key);
  if (isSecurityTransferReadOnly(key)) return;
  if (!config || !ids.length) return;
  const removed = new Set(ids);
  config.state.selected = config.state.selected.filter((id) => !removed.has(id));
  config.state.highlight.selected.clear();
  config.state.highlight.available = new Set(ids);
  refreshSecurityTransfer(key);
}

function bindSecurityTransfer(key) {
  const config = securityTransferConfig(key);
  if (!config) return;
  const root = document.getElementById(`${config.prefix}-transfer`);
  if (!root || root.dataset.bound === "1") return;
  root.dataset.bound = "1";

  const availableList = document.getElementById(`${config.prefix}-available-list`);
  const selectedList = document.getElementById(`${config.prefix}-selected-list`);
  const listClickHandler = (side) => (event) => {
    const row = event.target.closest(".member-row");
    if (!row || row.dataset.side !== side) return;
    toggleSecurityTransferHighlight(key, side, row.dataset.id, event.ctrlKey || event.metaKey || event.shiftKey);
    refreshSecurityTransfer(key);
  };
  const listDoubleClickHandler = (side) => (event) => {
    const row = event.target.closest(".member-row");
    if (!row || row.dataset.side !== side) return;
    if (isSecurityTransferReadOnly(key)) return;
    if (side === "available") addSecurityTransferItems(key, [row.dataset.id]);
    else removeSecurityTransferItems(key, [row.dataset.id]);
  };

  for (const [list, side] of [[availableList, "available"], [selectedList, "selected"]]) {
    bindTransferKeyboard(list, row => {
      toggleSecurityTransferHighlight(key, side, row.dataset.id, true);
      refreshSecurityTransfer(key);
    }, row => side === "available" ? addSecurityTransferItems(key, [row.dataset.id]) : removeSecurityTransferItems(key, [row.dataset.id]));
  }
  availableList.addEventListener("click", listClickHandler("available"));
  availableList.addEventListener("dblclick", listDoubleClickHandler("available"));
  selectedList.addEventListener("click", listClickHandler("selected"));
  selectedList.addEventListener("dblclick", listDoubleClickHandler("selected"));
  document.getElementById(`${config.prefix}-add`).addEventListener("click", () => {
    addSecurityTransferItems(key, [...config.state.highlight.available]);
  });
  document.getElementById(`${config.prefix}-add-all`).addEventListener("click", () => {
    addSecurityTransferItems(key, Array.from(availableList.querySelectorAll(".member-row")).map((row) => row.dataset.id));
  });
  document.getElementById(`${config.prefix}-remove`).addEventListener("click", () => {
    removeSecurityTransferItems(key, [...config.state.highlight.selected]);
  });
  document.getElementById(`${config.prefix}-remove-all`).addEventListener("click", () => {
    removeSecurityTransferItems(key, [...config.state.selected]);
  });
  document.getElementById(`${config.prefix}-filter`).addEventListener("input", (event) => {
    config.state.filter = event.target.value || "";
    refreshSecurityTransfer(key);
  });
}

function bindSecurityTransfers() {
  ["userRoles", "rolePrivileges", "roleRoles"].forEach(bindSecurityTransfer);
}

async function loadSecurityUsers() {
  [securityUsers, securityRoles] = await Promise.all([
    fetchJson("/internal/security/users", [], "Failed to load users"),
    fetchJson("/internal/security/roles", [], "Failed to load roles")
  ]);
  renderSecurityUsers();
  refreshSecurityTransfers(["userRoles"]);
}

function securityRoleBadges(roles, source = "", linkToRole = false) {
  const classes = `state-badge compact${source ? " external-role-badge" : ""}`;
  return (roles || []).map((role) => {
    const label = escapeHtml(role) + (source ? " · " + escapeHtml(source) : "");
    return linkToRole
      ? `<button type="button" class="${classes} security-role-link" data-role-id="${escapeHtml(role)}">${label}</button>`
      : `<span class="${classes}">${label}</span>`;
  }).join("");
}

function renderSecurityUsers() {
  renderSecurityUserSourceFilter();
  const filter = filterValue("security-user-filter");
  const sourceFilter = document.getElementById("security-user-source-filter")?.value || "";
  const rows = securityUsers.filter((user) =>
    (!sourceFilter || user.source === sourceFilter)
    && (accessState.securityUserRoleFilter
      ? [...(user.roles || []), ...(user.externalRoles || [])].includes(accessState.securityUserRoleFilter)
      : listContainsFilter([displaySource(user.source), user.source, user.userId, user.status, user.email,
        ...(user.roles || []), ...(user.externalRoles || [])], filter)))
    .map((user) => `
      <tr>
        <td>${escapeHtml(displaySource(user.source))}</td>
        <td>${escapeHtml(user.userId)}</td>
        <td><span class="state-badge compact ${user.status?.trim().toUpperCase() === "ACTIVE" ? "ok" : "warn"}">${escapeHtml(user.status || "")}</span></td>
        <td>${escapeHtml(user.email || "")}</td>
        <td><div class="security-role-badges">${securityRoleBadges(user.roles, "", true)}${securityRoleBadges(user.externalRoles, displaySource(user.source), true)}</div></td>
        <td class="actions-column">
          <button class="row-action edit-security-user-button" data-source="${escapeHtml(user.source)}" data-id="${escapeHtml(user.userId)}" type="button">edit</button>
          <button class="row-action delete-security-user-button" data-source="${escapeHtml(user.source)}" data-id="${escapeHtml(user.userId)}" type="button">delete</button>
        </td>
      </tr>
    `).join("");
  document.getElementById("security-user-table").innerHTML = rows
    || '<tr><td colspan="6" class="placeholder">No users.</td></tr>';
}

function showSecurityUserForm(user = null) {
  securityUserMode = user ? "edit" : "create";
  document.getElementById("security-user-form-title").textContent = user ? `Edit user: ${user.userId}` : "Create user";
  ensureUserSourceOption(user?.source || "Local");
  document.getElementById("security-user-source").value = user?.source || "Local";
  document.getElementById("security-user-id").value = user?.userId || "";
  document.getElementById("security-user-source").disabled = Boolean(user);
  document.getElementById("security-user-id").disabled = Boolean(user);
  document.getElementById("security-user-first-name").value = user?.firstName || "";
  document.getElementById("security-user-last-name").value = user?.lastName || "";
  document.getElementById("security-user-email").value = user?.email || "";
  document.getElementById("security-user-status").value = user?.status || "ACTIVE";
  document.getElementById("security-user-password").value = "";
  setSecurityTransferSelection("userRoles", user?.roles || []);
  document.getElementById("security-user-external-section").hidden = !user?.external;
  document.getElementById("security-user-external-roles").innerHTML =
    securityRoleBadges(user?.externalRoles, displaySource(user?.source))
    || '<span class="field-note">No external roles assigned.</span>';
  clearRequiredFieldErrors(securityUserRequiredFields);
  openFormModal("security-user-form", user ? "security-user-first-name" : "security-user-id");
}

function hideSecurityUserForm() {
  closeFormModal("security-user-form");
  document.getElementById("security-user-source").disabled = false;
  document.getElementById("security-user-id").disabled = false;
  clearRequiredFieldErrors(securityUserRequiredFields);
}

async function saveSecurityUser() {
  if (!validateRequiredFields(securityUserRequiredFields)) return;
  const payload = {
    source: document.getElementById("security-user-source").value.trim() || "Local",
    userId: document.getElementById("security-user-id").value.trim(),
    firstName: document.getElementById("security-user-first-name").value.trim() || null,
    lastName: document.getElementById("security-user-last-name").value.trim() || null,
    email: document.getElementById("security-user-email").value.trim() || null,
    status: document.getElementById("security-user-status").value,
    password: document.getElementById("security-user-password").value || null,
    roles: commaList(document.getElementById("security-user-roles").value)
  };
  const path = securityUserMode === "edit"
    ? `/internal/security/users/${encodeURIComponent(payload.source)}/${encodeURIComponent(payload.userId)}`
    : "/internal/security/users";
  try {
    const response = await fetch(path, {
      method: securityUserMode === "edit" ? "PUT" : "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(payload)
    });
    if (!response.ok) throw new Error(await responseErrorMessage(response));
    hideSecurityUserForm();
    await loadSecurityUsers();
    showToast("User saved.", "ok");
  } catch (error) {
    showToast(`Save failed: ${error.message}`, "error");
  }
}

async function deleteSecurityUser(source, userId) {
  if (!await window.kkrepoConfirmDelete({
    title: "Delete user?",
    description: "This removes the user's kkRepo account and API keys. It does not delete accounts from an external identity provider.",
    details: [{ label: "User", value: displaySource(source) + "/" + userId }]
  })) return;
  try {
    const response = await fetch(`/internal/security/users/${encodeURIComponent(source)}/${encodeURIComponent(userId)}`, {
      method: "DELETE"
    });
    if (!response.ok) throw new Error(await responseErrorMessage(response));
    await loadSecurityUsers();
    showToast("User deleted.", "ok");
  } catch (error) {
    showToast(`Delete failed: ${error.message}`, "error");
  }
}

async function loadSecurityRoles() {
  [securityRoles, securityPrivileges] = await Promise.all([
    fetchJson("/internal/security/roles", [], "Failed to load roles"),
    fetchJson("/internal/security/privileges", [], "Failed to load privileges")
  ]);
  renderSecurityRoles();
  refreshSecurityTransfers(["rolePrivileges", "roleRoles"]);
}

function renderSecurityRoles() {
  const filter = filterValue("security-role-filter");
  const rows = securityRoles.filter((role) =>
    listContainsFilter([role.roleId, role.name, role.description,
      ...(role.externalMappings || []).flatMap(({ externalName, realmId }) => [externalName, realmId])], filter))
    .map((role) => `
      <tr>
        <td>${escapeHtml(role.roleId)}${role.readOnly ? ' <span class="state-badge compact">read-only</span>' : ""}</td>
        <td>${escapeHtml(role.name || "")}</td>
        <td>${escapeHtml(role.description || "")}</td>
        <td><div class="security-role-badges">${(role.externalMappings || []).map(({ externalName, realmId }) =>
          securityRoleBadges([externalName], realmId.toUpperCase())).join("")}</div></td>
        <td class="actions-column">
          ${role.readOnly ? `
            <button class="row-action view-security-role-button" data-id="${escapeHtml(role.roleId)}" type="button">view</button>
          ` : `
            <button class="row-action edit-security-role-button" data-id="${escapeHtml(role.roleId)}" type="button">edit</button>
            <button class="row-action delete-security-role-button" data-id="${escapeHtml(role.roleId)}" type="button">delete</button>
          `}
        </td>
      </tr>
    `).join("");
  document.getElementById("security-role-table").innerHTML = rows
    || '<tr><td colspan="5" class="placeholder">No roles.</td></tr>';
}

function showSecurityRoleForm(role = null, options = {}) {
  const viewOnly = Boolean(options.viewOnly);
  securityRoleMode = viewOnly ? "view" : role ? "edit" : "create";
  const roleId = role?.roleId || "";
  const readOnlyField = document.getElementById("security-role-readonly-field");
  const showReadOnlyField = isBuiltInReadOnlyRoleId(roleId);
  document.getElementById("security-role-form-title").textContent = viewOnly
    ? `View role: ${role.roleId}`
    : role ? `Edit role: ${role.roleId}` : "Create role";
  document.getElementById("security-role-id").value = roleId;
  document.getElementById("security-role-id").disabled = Boolean(role);
  document.getElementById("security-role-name").value = role?.name || "";
  document.getElementById("security-role-description").value = role?.description || "";
  readOnlyField.hidden = !showReadOnlyField;
  document.getElementById("security-role-readonly").checked = showReadOnlyField;
  setSecurityTransferSelection("rolePrivileges", role?.privileges || []);
  setSecurityTransferSelection("roleRoles", role?.roles || []);
  setSecurityRoleFormReadOnly(viewOnly);
  document.getElementById("security-role-mappings").replaceChildren();
  for (const mapping of role?.externalMappings || []) addSecurityRoleMapping(mapping, viewOnly);
  document.getElementById("add-security-role-mapping").hidden = viewOnly;
  document.getElementById("edit-security-role-mappings").hidden = !viewOnly;
  document.getElementById("view-security-role-users").hidden = !role;
  clearRequiredFieldErrors(securityRoleRequiredFields);
  openFormModal("security-role-form", viewOnly ? "cancel-security-role-button" : role ? "security-role-name" : "security-role-id");
}

function hideSecurityRoleForm() {
  closeFormModal("security-role-form");
  securityRoleMode = "create";
  setSecurityRoleFormReadOnly(false);
  document.getElementById("security-role-id").disabled = false;
  clearRequiredFieldErrors(securityRoleRequiredFields);
}

function setSecurityRoleFormReadOnly(readOnly) {
  const form = document.getElementById("security-role-form");
  const saveButton = document.getElementById("save-security-role-button");
  const cancelButton = document.getElementById("cancel-security-role-button");
  form.classList.toggle("is-readonly", readOnly);
  document.getElementById("security-role-name").disabled = readOnly;
  document.getElementById("security-role-description").disabled = readOnly;
  document.getElementById("security-role-id").disabled = readOnly || securityRoleMode !== "create";
  saveButton.hidden = readOnly;
  saveButton.textContent = "Save role";
  cancelButton.textContent = readOnly ? "Close" : "Cancel";
  ["role-privilege", "role-contained"].forEach((prefix) => {
    document.getElementById(`${prefix}-transfer`)?.classList.toggle("is-readonly", readOnly);
    const filter = document.getElementById(`${prefix}-filter`);
    if (filter) filter.disabled = readOnly;
  });
  refreshSecurityTransfers(["rolePrivileges", "roleRoles"]);
}

function addSecurityRoleMapping(mapping = {}, readOnly = false) {
  const row = document.createElement("div");
  row.className = "security-role-mapping";
  row.innerHTML = `
    <label><span>Identity provider</span><select required>
      <option value="">Select a provider</option>
      <option value="oidc">OIDC</option>
      <option value="ldap">LDAP</option>
    </select></label>
    <label><span>External role or group</span><input type="text" maxlength="255" required></label>
    <button type="button" class="row-action">Remove mapping</button>`;
  const provider = row.querySelector("select");
  const name = row.querySelector("input");
  const remove = row.querySelector("button");
  provider.value = mapping.realmId || "";
  name.value = mapping.externalName || "";
  provider.disabled = readOnly;
  name.disabled = readOnly;
  remove.hidden = readOnly;
  remove.addEventListener("click", () => {
    row.remove();
    document.getElementById("add-security-role-mapping").focus();
  });
  document.getElementById("security-role-mappings").append(row);
  if (!mapping.realmId) provider.focus();
}

function editSecurityRoleMappings() {
  securityRoleMode = "mappings";
  const container = document.getElementById("security-role-mappings");
  container.querySelectorAll("input, select").forEach((field) => { field.disabled = false; });
  container.querySelectorAll("button").forEach((button) => { button.hidden = false; });
  document.getElementById("save-security-role-button").hidden = false;
  document.getElementById("save-security-role-button").textContent = "Save mappings";
  document.getElementById("cancel-security-role-button").textContent = "Cancel";
  document.getElementById("edit-security-role-mappings").hidden = true;
  const add = document.getElementById("add-security-role-mapping");
  add.hidden = false;
  add.focus();
}

async function saveSecurityRole() {
  if (securityRoleMode === "view") return;
  if (securityRoleMode !== "mappings" && !validateRequiredFields(securityRoleRequiredFields)) return;
  const mappings = [];
  for (const row of document.querySelectorAll("#security-role-mappings .security-role-mapping")) {
    const provider = row.querySelector("select");
    const name = row.querySelector("input");
    if (!provider.value || !name.value.trim()) {
      (provider.value ? name : provider).focus();
      showToast("Choose a provider and enter an external role or group for each mapping.", "error");
      return;
    }
    mappings.push({ realmId: provider.value, externalName: name.value.trim() });
  }
  const payload = {
    roleId: document.getElementById("security-role-id").value.trim(),
    name: document.getElementById("security-role-name").value.trim() || null,
    description: document.getElementById("security-role-description").value.trim() || null,
    readOnly: isBuiltInReadOnlyRoleId(document.getElementById("security-role-id").value),
    privileges: commaList(document.getElementById("security-role-privileges").value),
    roles: commaList(document.getElementById("security-role-roles").value),
    externalMappings: mappings
  };
  const create = securityRoleMode === "create";
  const mappingsOnly = securityRoleMode === "mappings";
  const rolePath = `/internal/security/roles/${encodeURIComponent(payload.roleId)}`;
  const path = create ? "/internal/security/roles" : mappingsOnly ? `${rolePath}/external-mappings` : rolePath;
  try {
    const response = await fetch(path, {
      method: create ? "POST" : "PUT",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(mappingsOnly ? mappings : payload)
    });
    if (!response.ok) throw new Error(await responseErrorMessage(response));
    hideSecurityRoleForm();
    await loadSecurityRoles();
    showToast("Role saved.", "ok");
  } catch (error) {
    showToast(`Save failed: ${error.message}`, "error");
  }
}

async function deleteSecurityRole(roleId) {
  if (!await window.kkrepoConfirmDelete({
    title: "Delete role?",
    description: "Users and roles that rely on this role may lose access.",
    details: [{ label: "Role", value: roleId }]
  })) return;
  try {
    const response = await fetch(`/internal/security/roles/${encodeURIComponent(roleId)}`, { method: "DELETE" });
    if (!response.ok) throw new Error(await responseErrorMessage(response));
    await loadSecurityRoles();
    showToast("Role deleted.", "ok");
  } catch (error) {
    showToast(`Delete failed: ${error.message}`, "error");
  }
}

async function loadSecurityPrivileges() {
  securityPrivileges = await fetchJson("/internal/security/privileges", [], "Failed to load privileges");
  renderSecurityPrivileges();
}

function renderSecurityPrivileges() {
  const filter = filterValue("security-privilege-filter");
  const rows = securityPrivileges.filter((privilege) =>
    listContainsFilter([privilege.privilegeId, privilege.type, privilege.name, privilege.description, privilege.permission], filter))
    .map((privilege) => `
      <tr>
        <td>${escapeHtml(privilege.privilegeId)}${privilege.readOnly ? ' <span class="state-badge compact">read-only</span>' : ""}</td>
        <td>${escapeHtml(privilege.type)}</td>
        <td>${escapeHtml(privilege.name || "")}</td>
        <td><code>${escapeHtml(privilege.permission || "")}</code></td>
        <td class="actions-column">
          ${privilege.readOnly ? "" : `
            <button class="row-action edit-security-privilege-button" data-id="${escapeHtml(privilege.privilegeId)}" type="button">edit</button>
            <button class="row-action delete-security-privilege-button" data-id="${escapeHtml(privilege.privilegeId)}" type="button">delete</button>
          `}
        </td>
      </tr>
    `).join("");
  document.getElementById("security-privilege-table").innerHTML = rows
    || '<tr><td colspan="5" class="placeholder">No privileges.</td></tr>';
}

function showSecurityPrivilegeForm(privilege = null) {
  securityPrivilegeMode = privilege ? "edit" : "create";
  document.getElementById("security-privilege-form-title").textContent = privilege ? `Edit privilege: ${privilege.privilegeId}` : "Create privilege";
  document.getElementById("security-privilege-id").value = privilege?.privilegeId || "";
  document.getElementById("security-privilege-id").disabled = Boolean(privilege);
  document.getElementById("security-privilege-name").value = privilege?.name || "";
  document.getElementById("security-privilege-type").value = privilege?.type || "wildcard";
  document.getElementById("security-privilege-description").value = privilege?.description || "";
  document.getElementById("security-privilege-readonly").checked = Boolean(privilege?.readOnly);
  document.getElementById("security-privilege-properties").value = JSON.stringify(privilege?.properties || { pattern: "nexus:*" }, null, 2);
  clearRequiredFieldErrors(securityPrivilegeRequiredFields);
  openFormModal("security-privilege-form", privilege ? "security-privilege-name" : "security-privilege-id");
}

function hideSecurityPrivilegeForm() {
  closeFormModal("security-privilege-form");
  document.getElementById("security-privilege-id").disabled = false;
  clearRequiredFieldErrors(securityPrivilegeRequiredFields);
}

async function saveSecurityPrivilege() {
  if (!validateRequiredFields(securityPrivilegeRequiredFields)) return;
  let properties;
  try {
    properties = parseJsonObject("security-privilege-properties");
  } catch (_) {
    return;
  }
  const payload = {
    privilegeId: document.getElementById("security-privilege-id").value.trim(),
    name: document.getElementById("security-privilege-name").value.trim() || null,
    type: document.getElementById("security-privilege-type").value,
    description: document.getElementById("security-privilege-description").value.trim() || null,
    readOnly: document.getElementById("security-privilege-readonly").checked,
    properties
  };
  const path = securityPrivilegeMode === "edit"
    ? `/internal/security/privileges/${encodeURIComponent(payload.privilegeId)}`
    : "/internal/security/privileges";
  try {
    const response = await fetch(path, {
      method: securityPrivilegeMode === "edit" ? "PUT" : "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(payload)
    });
    if (!response.ok) throw new Error(await responseErrorMessage(response));
    hideSecurityPrivilegeForm();
    await loadSecurityPrivileges();
    showToast("Privilege saved.", "ok");
  } catch (error) {
    showToast(`Save failed: ${error.message}`, "error");
  }
}

async function deleteSecurityPrivilege(privilegeId) {
  if (!await window.kkrepoConfirmDelete({
    title: "Delete privilege?",
    description: "Roles that rely on this privilege may lose access.",
    details: [{ label: "Privilege", value: privilegeId }]
  })) return;
  try {
    const response = await fetch(`/internal/security/privileges/${encodeURIComponent(privilegeId)}`, { method: "DELETE" });
    if (!response.ok) throw new Error(await responseErrorMessage(response));
    await loadSecurityPrivileges();
    showToast("Privilege deleted.", "ok");
  } catch (error) {
    showToast(`Delete failed: ${error.message}`, "error");
  }
}

export {
  accessState,
  accountSourceLabel,
  addSecurityRoleMapping,
  bindSecurityTransfers,
  deleteSecurityPrivilege,
  deleteSecurityRole,
  deleteSecurityUser,
  displaySource,
  editSecurityRoleMappings,
  hideSecurityPrivilegeForm,
  hideSecurityRoleForm,
  hideSecurityUserForm,
  loadSecurityPrivileges,
  loadSecurityRoles,
  loadSecurityUsers,
  refreshSecurityTransfer,
  renderSecurityPrivileges,
  renderSecurityRoles,
  renderSecurityUsers,
  saveSecurityPrivilege,
  saveSecurityRole,
  saveSecurityUser,
  securityAnonymousRequiredFields,
  securityPrivilegeRequiredFields,
  securityPrivileges,
  securityRoleRequiredFields,
  securityRoles,
  securityUserRequiredFields,
  securityUsers,
  showSecurityPrivilegeForm,
  showSecurityRoleForm,
  showSecurityUserForm,
};
