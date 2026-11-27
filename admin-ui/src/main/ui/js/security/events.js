import { bindRequiredFieldErrors, markInputValidity } from "../controls/fields.js";
import {
  accessState,
  addSecurityRoleMapping,
  deleteSecurityPrivilege,
  deleteSecurityRole,
  deleteSecurityUser,
  editSecurityRoleMappings,
  hideSecurityPrivilegeForm,
  hideSecurityRoleForm,
  hideSecurityUserForm,
  refreshSecurityTransfer,
  renderSecurityPrivileges,
  renderSecurityRoles,
  renderSecurityUsers,
  saveSecurityPrivilege,
  saveSecurityRole,
  saveSecurityUser,
  securityPrivilegeRequiredFields,
  securityPrivileges,
  securityRoleRequiredFields,
  securityRoles,
  securityUserRequiredFields,
  securityUsers,
  showSecurityPrivilegeForm,
  showSecurityRoleForm,
  showSecurityUserForm,
} from "./access.js";
import {
  copySecurityApiKey,
  deleteSecurityApiKey,
  hideSecurityApiKeyForm,
  saveSecurityApiKey,
  securityApiKeyRequiredFields,
  showSecurityApiKeyForm,
} from "./api-keys.js";
import {
  bindSecurityProviderJson,
  clearSecurityLdapRequiredErrors,
  ldapRequiredFields,
  oidcRequiredFields,
  refreshSecurityLdapRequiredMarkers,
  refreshSecurityOidcRequiredMarkers,
  saveSecurityLdap,
  saveSecurityOidc,
} from "./providers.js";
import { bindAnonymousEditor } from "./anonymous.js";
import { bindRealmEditor } from "./realms.js";
import { switchView } from "../shell/routes.js";

export function bindSecurityEvents() {
  document.getElementById("security-user-filter").addEventListener("input", () => {
    accessState.securityUserRoleFilter = "";
    renderSecurityUsers();
  });
  document.getElementById("security-user-source-filter").addEventListener("change", renderSecurityUsers);
  document.getElementById("create-security-user-button").addEventListener("click", () => showSecurityUserForm());
  document.getElementById("cancel-security-user-button").addEventListener("click", hideSecurityUserForm);
  document.getElementById("save-security-user-button").addEventListener("click", saveSecurityUser);
  document.getElementById("security-user-form").addEventListener("submit", (event) => {
    event.preventDefault();
    saveSecurityUser();
  });
  bindRequiredFieldErrors(securityUserRequiredFields);
  document.getElementById("security-user-table").addEventListener("click", (event) => {
    const roleLink = event.target.closest(".security-role-link");
    if (roleLink) {
      switchView("security-roles", { roleId: roleLink.dataset.roleId });
      return;
    }
    const editButton = event.target.closest(".edit-security-user-button");
    if (editButton) {
      const user = securityUsers.find((item) => item.source === editButton.dataset.source && item.userId === editButton.dataset.id);
      if (user) showSecurityUserForm(user);
      return;
    }
    const deleteButton = event.target.closest(".delete-security-user-button");
    if (deleteButton) deleteSecurityUser(deleteButton.dataset.source, deleteButton.dataset.id);
  });
  document.getElementById("security-role-filter").addEventListener("input", renderSecurityRoles);
  document.getElementById("create-security-role-button").addEventListener("click", () => showSecurityRoleForm());
  document.getElementById("cancel-security-role-button").addEventListener("click", hideSecurityRoleForm);
  document.getElementById("save-security-role-button").addEventListener("click", saveSecurityRole);
  document.getElementById("add-security-role-mapping").addEventListener("click", () => addSecurityRoleMapping());
  document.getElementById("edit-security-role-mappings").addEventListener("click", editSecurityRoleMappings);
  document.getElementById("view-security-role-users").addEventListener("click", () => {
    accessState.securityUserRoleFilter = document.getElementById("security-role-id").value;
    document.getElementById("security-user-filter").value = accessState.securityUserRoleFilter;
    document.getElementById("security-user-source-filter").value = "";
    hideSecurityRoleForm();
    switchView("security-users");
  });
  document.getElementById("security-role-id").addEventListener("input", () => refreshSecurityTransfer("roleRoles"));
  document.getElementById("security-role-form").addEventListener("submit", (event) => {
    event.preventDefault();
    saveSecurityRole();
  });
  bindRequiredFieldErrors(securityRoleRequiredFields);
  document.getElementById("security-role-table").addEventListener("click", (event) => {
    const viewButton = event.target.closest(".view-security-role-button");
    if (viewButton) {
      const role = securityRoles.find((item) => item.roleId === viewButton.dataset.id);
      if (role) showSecurityRoleForm(role, { viewOnly: true });
      return;
    }
    const editButton = event.target.closest(".edit-security-role-button");
    if (editButton) {
      const role = securityRoles.find((item) => item.roleId === editButton.dataset.id);
      if (role) showSecurityRoleForm(role);
      return;
    }
    const deleteButton = event.target.closest(".delete-security-role-button");
    if (deleteButton) deleteSecurityRole(deleteButton.dataset.id);
  });
  document.getElementById("security-privilege-filter").addEventListener("input", renderSecurityPrivileges);
  document.getElementById("create-security-privilege-button").addEventListener("click", () => showSecurityPrivilegeForm());
  document.getElementById("cancel-security-privilege-button").addEventListener("click", hideSecurityPrivilegeForm);
  document.getElementById("save-security-privilege-button").addEventListener("click", saveSecurityPrivilege);
  document.getElementById("security-privilege-form").addEventListener("submit", (event) => {
    event.preventDefault();
    saveSecurityPrivilege();
  });
  bindRequiredFieldErrors(securityPrivilegeRequiredFields);
  document.getElementById("security-privilege-table").addEventListener("click", (event) => {
    const editButton = event.target.closest(".edit-security-privilege-button");
    if (editButton) {
      const privilege = securityPrivileges.find((item) => item.privilegeId === editButton.dataset.id);
      if (privilege) showSecurityPrivilegeForm(privilege);
      return;
    }
    const deleteButton = event.target.closest(".delete-security-privilege-button");
    if (deleteButton) deleteSecurityPrivilege(deleteButton.dataset.id);
  });
  bindRealmEditor();
  document.getElementById("save-security-ldap-button").addEventListener("click", saveSecurityLdap);
  document.getElementById("security-ldap-form").addEventListener("submit", (event) => {
    event.preventDefault();
    saveSecurityLdap();
  });
  document.getElementById("security-ldap-enabled").addEventListener("change", refreshSecurityLdapRequiredMarkers);
  ldapRequiredFields.forEach((field) => {
    const input = document.getElementById(field.id);
    input.addEventListener("input", clearSecurityLdapRequiredErrors);
    input.addEventListener("change", clearSecurityLdapRequiredErrors);
  });
  bindSecurityProviderJson("ldap");
  document.getElementById("save-security-oidc-button").addEventListener("click", saveSecurityOidc);
  document.getElementById("security-oidc-form").addEventListener("submit", (event) => {
    event.preventDefault();
    saveSecurityOidc();
  });
  document.getElementById("security-oidc-enabled").addEventListener("change", refreshSecurityOidcRequiredMarkers);
  oidcRequiredFields.forEach((field) => {
    document.getElementById(field.id).addEventListener("input", (event) => {
      markInputValidity(event.target, false);
    });
  });
  bindSecurityProviderJson("oidc");
  bindAnonymousEditor();
  document.getElementById("copy-security-api-key").addEventListener("click", copySecurityApiKey);
  document.getElementById("create-security-api-key-button").addEventListener("click", showSecurityApiKeyForm);
  document.getElementById("cancel-security-api-key-button").addEventListener("click", hideSecurityApiKeyForm);
  document.getElementById("save-security-api-key-button").addEventListener("click", saveSecurityApiKey);
  document.getElementById("security-api-key-form").addEventListener("submit", (event) => {
    event.preventDefault();
    saveSecurityApiKey();
  });
  bindRequiredFieldErrors(securityApiKeyRequiredFields);
  document.getElementById("security-api-key-table").addEventListener("click", (event) => {
    const deleteButton = event.target.closest(".delete-security-api-key-button");
    if (deleteButton) deleteSecurityApiKey(deleteButton.dataset.id);
  });
}
