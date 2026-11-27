import {
  clearRequiredFieldErrors,
  markInputValidity,
  numberInputValue,
  setCheckboxValue,
  setFieldRequired,
  setInputValue,
  setSelectValue,
  textInputValue,
  updateRequiredMarker,
  updateRequiredMarkers,
  validateRequiredFields,
} from "../controls/fields.js";
import { showToast } from "../controls/toast.js";
import { accessState, securityAnonymousRequiredFields } from "./access.js";
import { fetchJson, responseErrorMessage } from "../shell/http.js";

let securityLdap = null;

let securityOidc = null;

const SECURITY_PROVIDER_SECRET_MASK = "********";

let securityProviderJsonSyncing = false;

const securityProviderAttributes = { ldap: {}, oidc: {} };

function applyOriginAwarePlaceholders() {
  const oidcRedirectUri = document.getElementById("security-oidc-redirect-uri");
  if (oidcRedirectUri) {
    oidcRedirectUri.placeholder = `${window.location.origin}/internal/security/oidc/callback`;
  }
}

const ldapRequiredFields = [
  {
    id: "security-ldap-url",
    label: "URL",
    required: () => document.getElementById("security-ldap-enabled").checked
  },
  {
    id: "security-ldap-host",
    label: "Host",
    required: () => document.getElementById("security-ldap-enabled").checked
  }
];

const oidcRequiredFields = [
  { id: "security-oidc-issuer", label: "Issuer" },
  { id: "security-oidc-jwks-uri", label: "JWKS URI" },
  { id: "security-oidc-client-id", label: "Client ID" },
  { id: "security-oidc-client-secret", label: "Client secret" },
  { id: "security-oidc-redirect-uri", label: "Redirect URI" }
];

function editableSecurityProviderAttributes(attributes, excludedKeys = []) {
  const editable = { ...(attributes || {}) };
  delete editable.source;
  delete editable.nexusRealm;
  excludedKeys.forEach((key) => delete editable[key]);
  return editable;
}

function securityProviderJsonScalar(value, fallback = "") {
  return typeof value === "string" || typeof value === "number" ? value : fallback;
}

function securityProviderJsonNumber(value, fallback = "") {
  if (value == null || value === "") return fallback;
  const number = Number(value);
  return Number.isFinite(number) ? number : fallback;
}

function securityProviderJsonBoolean(value, fallback = false) {
  if (value == null) return fallback;
  if (typeof value === "boolean") return value;
  if (value === "true") return true;
  if (value === "false") return false;
  return fallback;
}

function setSecurityProviderSecretFromJson(id, value) {
  if (value === SECURITY_PROVIDER_SECRET_MASK) return;
  setInputValue(id, securityProviderJsonScalar(value));
}

function securityLdapFormValue({ attributes = securityProviderAttributes.ldap, maskSecrets = true } = {}) {
  const authPassword = textInputValue("security-ldap-auth-password");
  return {
    enabled: document.getElementById("security-ldap-enabled").checked,
    priority: numberInputValue("security-ldap-priority") ?? 10,
    source: "LDAP",
    url: textInputValue("security-ldap-url"),
    protocol: textInputValue("security-ldap-protocol") || "ldap",
    host: textInputValue("security-ldap-host"),
    port: numberInputValue("security-ldap-port"),
    useTrustStore: document.getElementById("security-ldap-trust-store").checked,
    searchBase: textInputValue("security-ldap-search-base"),
    authScheme: textInputValue("security-ldap-auth-scheme"),
    authRealm: textInputValue("security-ldap-auth-realm"),
    authUsername: textInputValue("security-ldap-auth-username"),
    authPassword: maskSecrets && authPassword ? SECURITY_PROVIDER_SECRET_MASK : authPassword,
    connectionTimeout: numberInputValue("security-ldap-connection-timeout"),
    connectionRetryDelay: numberInputValue("security-ldap-retry-delay"),
    maxIncidentsCount: numberInputValue("security-ldap-max-incidents"),
    userBaseDn: textInputValue("security-ldap-user-base-dn"),
    userSubtree: document.getElementById("security-ldap-user-subtree").checked,
    userObjectClass: textInputValue("security-ldap-user-object-class"),
    userLdapFilter: textInputValue("security-ldap-user-filter"),
    userIdAttribute: textInputValue("security-ldap-user-id-attribute"),
    userRealNameAttribute: textInputValue("security-ldap-user-real-name-attribute"),
    userMemberOfAttribute: textInputValue("security-ldap-user-member-of-attribute"),
    userEmailAddressAttribute: textInputValue("security-ldap-user-email-attribute"),
    userPasswordAttribute: textInputValue("security-ldap-user-password-attribute"),
    ldapGroupsAsRoles: document.getElementById("security-ldap-groups-as-roles").checked,
    groupType: textInputValue("security-ldap-group-type"),
    groupBaseDn: textInputValue("security-ldap-group-base-dn"),
    groupSubtree: document.getElementById("security-ldap-group-subtree").checked,
    groupIdAttribute: textInputValue("security-ldap-group-id-attribute"),
    groupMemberAttribute: textInputValue("security-ldap-group-member-attribute"),
    groupMemberFormat: textInputValue("security-ldap-group-member-format"),
    groupObjectClass: textInputValue("security-ldap-group-object-class"),
    attributes: { ...attributes, legacyRoleMatching: document.getElementById("security-ldap-legacy-role-matching").checked }
  };
}

function securityOidcFormValue({ attributes = securityProviderAttributes.oidc, maskSecrets = true } = {}) {
  const clientSecret = textInputValue("security-oidc-client-secret");
  return {
    enabled: document.getElementById("security-oidc-enabled").checked,
    priority: numberInputValue("security-oidc-priority") ?? 20,
    source: "OIDC",
    issuer: textInputValue("security-oidc-issuer"),
    jwksUri: textInputValue("security-oidc-jwks-uri"),
    audience: textInputValue("security-oidc-audience"),
    clientId: textInputValue("security-oidc-client-id"),
    clientSecret: maskSecrets && clientSecret ? SECURITY_PROVIDER_SECRET_MASK : clientSecret,
    authorizationEndpoint: textInputValue("security-oidc-authorization-endpoint"),
    tokenEndpoint: textInputValue("security-oidc-token-endpoint"),
    redirectUri: textInputValue("security-oidc-redirect-uri"),
    scopes: textInputValue("security-oidc-scopes"),
    userIdClaim: textInputValue("security-oidc-user-id-claim"),
    firstNameClaim: textInputValue("security-oidc-first-name-claim"),
    lastNameClaim: textInputValue("security-oidc-last-name-claim"),
    emailClaim: textInputValue("security-oidc-email-claim"),
    groupsClaim: textInputValue("security-oidc-groups-claim"),
    rolesClaim: textInputValue("security-oidc-roles-claim"),
    clockSkewSeconds: numberInputValue("security-oidc-clock-skew") ?? 60,
    jwksCacheSeconds: numberInputValue("security-oidc-jwks-cache") ?? 300,
    attributes: { ...attributes, legacyRoleMatching: document.getElementById("security-oidc-legacy-role-matching").checked }
  };
}

function applySecurityLdapJsonValue(value) {
  setCheckboxValue("security-ldap-enabled", securityProviderJsonBoolean(value.enabled));
  setInputValue("security-ldap-priority", securityProviderJsonNumber(value.priority, 10));
  setInputValue("security-ldap-url", securityProviderJsonScalar(value.url));
  setSelectValue("security-ldap-protocol", securityProviderJsonScalar(value.protocol), "ldap");
  setInputValue("security-ldap-host", securityProviderJsonScalar(value.host));
  setInputValue("security-ldap-port", securityProviderJsonNumber(value.port));
  setCheckboxValue("security-ldap-trust-store", securityProviderJsonBoolean(value.useTrustStore));
  setInputValue("security-ldap-search-base", securityProviderJsonScalar(value.searchBase));
  setInputValue("security-ldap-auth-scheme", securityProviderJsonScalar(value.authScheme, "simple"));
  setInputValue("security-ldap-auth-realm", securityProviderJsonScalar(value.authRealm));
  setInputValue("security-ldap-auth-username", securityProviderJsonScalar(value.authUsername));
  setSecurityProviderSecretFromJson("security-ldap-auth-password", value.authPassword);
  setInputValue("security-ldap-connection-timeout", securityProviderJsonNumber(value.connectionTimeout, 30));
  setInputValue("security-ldap-retry-delay", securityProviderJsonNumber(value.connectionRetryDelay));
  setInputValue("security-ldap-max-incidents", securityProviderJsonNumber(value.maxIncidentsCount));
  setInputValue("security-ldap-user-base-dn", securityProviderJsonScalar(value.userBaseDn));
  setCheckboxValue("security-ldap-user-subtree", securityProviderJsonBoolean(value.userSubtree, true));
  setInputValue("security-ldap-user-object-class", securityProviderJsonScalar(value.userObjectClass, "inetOrgPerson"));
  setInputValue("security-ldap-user-filter", securityProviderJsonScalar(value.userLdapFilter));
  setInputValue("security-ldap-user-id-attribute", securityProviderJsonScalar(value.userIdAttribute, "uid"));
  setInputValue("security-ldap-user-real-name-attribute", securityProviderJsonScalar(value.userRealNameAttribute, "cn"));
  setInputValue("security-ldap-user-member-of-attribute", securityProviderJsonScalar(value.userMemberOfAttribute, "memberOf"));
  setInputValue("security-ldap-user-email-attribute", securityProviderJsonScalar(value.userEmailAddressAttribute, "mail"));
  setInputValue("security-ldap-user-password-attribute", securityProviderJsonScalar(value.userPasswordAttribute, "userPassword"));
  setCheckboxValue("security-ldap-groups-as-roles", securityProviderJsonBoolean(value.ldapGroupsAsRoles, true));
  setSelectValue("security-ldap-group-type", securityProviderJsonScalar(value.groupType), "static");
  setInputValue("security-ldap-group-base-dn", securityProviderJsonScalar(value.groupBaseDn));
  setCheckboxValue("security-ldap-group-subtree", securityProviderJsonBoolean(value.groupSubtree, true));
  setInputValue("security-ldap-group-id-attribute", securityProviderJsonScalar(value.groupIdAttribute, "cn"));
  setInputValue("security-ldap-group-member-attribute", securityProviderJsonScalar(value.groupMemberAttribute, "member"));
  setInputValue("security-ldap-group-member-format", securityProviderJsonScalar(value.groupMemberFormat, "${dn}"));
  setInputValue("security-ldap-group-object-class", securityProviderJsonScalar(value.groupObjectClass, "groupOfNames"));
  const attributes = value.attributes && !Array.isArray(value.attributes) && typeof value.attributes === "object"
    ? editableSecurityProviderAttributes(value.attributes, ["name"])
    : {};
  securityProviderAttributes.ldap = attributes;
  setCheckboxValue("security-ldap-legacy-role-matching",
    securityProviderJsonBoolean(attributes.legacyRoleMatching, Boolean(value.url || value.host)));
  clearRequiredFieldErrors(ldapRequiredFields);
  refreshSecurityLdapRequiredMarkers();
}

function applySecurityOidcJsonValue(value) {
  setCheckboxValue("security-oidc-enabled", securityProviderJsonBoolean(value.enabled));
  setInputValue("security-oidc-priority", securityProviderJsonNumber(value.priority, 20));
  setInputValue("security-oidc-issuer", securityProviderJsonScalar(value.issuer ?? value.issuerUri));
  setInputValue("security-oidc-jwks-uri", securityProviderJsonScalar(value.jwksUri));
  setInputValue("security-oidc-audience", securityProviderJsonScalar(value.audience));
  setInputValue("security-oidc-client-id", securityProviderJsonScalar(value.clientId));
  setSecurityProviderSecretFromJson("security-oidc-client-secret", value.clientSecret);
  setInputValue("security-oidc-authorization-endpoint", securityProviderJsonScalar(value.authorizationEndpoint));
  setInputValue("security-oidc-token-endpoint", securityProviderJsonScalar(value.tokenEndpoint));
  setInputValue("security-oidc-redirect-uri", securityProviderJsonScalar(value.redirectUri));
  setInputValue("security-oidc-scopes", securityProviderJsonScalar(value.scopes, "openid profile email"));
  setInputValue("security-oidc-user-id-claim", securityProviderJsonScalar(value.userIdClaim, "preferred_username"));
  setInputValue("security-oidc-first-name-claim", securityProviderJsonScalar(value.firstNameClaim, "given_name"));
  setInputValue("security-oidc-last-name-claim", securityProviderJsonScalar(value.lastNameClaim, "family_name"));
  setInputValue("security-oidc-email-claim", securityProviderJsonScalar(value.emailClaim, "email"));
  setInputValue("security-oidc-groups-claim", securityProviderJsonScalar(value.groupsClaim, "groups"));
  setInputValue("security-oidc-roles-claim", securityProviderJsonScalar(value.rolesClaim, "roles"));
  setInputValue("security-oidc-clock-skew", securityProviderJsonNumber(value.clockSkewSeconds, 60));
  setInputValue("security-oidc-jwks-cache", securityProviderJsonNumber(value.jwksCacheSeconds, 300));
  const attributes = value.attributes && !Array.isArray(value.attributes) && typeof value.attributes === "object"
    ? editableSecurityProviderAttributes(value.attributes)
    : {};
  securityProviderAttributes.oidc = attributes;
  setCheckboxValue("security-oidc-legacy-role-matching",
    securityProviderJsonBoolean(attributes.legacyRoleMatching, Boolean(value.issuer || value.issuerUri || value.jwksUri)));
  clearRequiredFieldErrors(oidcRequiredFields);
  refreshSecurityOidcRequiredMarkers();
}

function setSecurityProviderJsonStatus(provider, state, text) {
  const status = document.getElementById(`security-${provider}-json-status`);
  status.dataset.state = state;
  status.textContent = text;
}

function syncSecurityProviderJsonFromForm(provider, { force = false } = {}) {
  if (securityProviderJsonSyncing) return;
  const editor = document.getElementById(`security-${provider}-json-editor`);
  if (!force && document.activeElement === editor) return;
  const value = provider === "ldap" ? securityLdapFormValue() : securityOidcFormValue();
  editor.value = JSON.stringify(value, null, 2);
  editor.classList.remove("is-invalid");
  editor.setAttribute("aria-invalid", "false");
  editor.removeAttribute("title");
  setSecurityProviderJsonStatus(provider, "synced", "Synced");
}

function applySecurityProviderJsonEditor(provider) {
  const editor = document.getElementById(`security-${provider}-json-editor`);
  try {
    const value = JSON.parse(editor.value);
    if (!value || Array.isArray(value) || typeof value !== "object") {
      throw new Error("JSON must be an object");
    }
    securityProviderJsonSyncing = true;
    if (provider === "ldap") {
      applySecurityLdapJsonValue(value);
    } else {
      applySecurityOidcJsonValue(value);
    }
    editor.classList.remove("is-invalid");
    editor.setAttribute("aria-invalid", "false");
    editor.removeAttribute("title");
    setSecurityProviderJsonStatus(provider, "synced", "Synced");
    return true;
  } catch (error) {
    editor.classList.add("is-invalid");
    editor.setAttribute("aria-invalid", "true");
    editor.title = error.message;
    setSecurityProviderJsonStatus(provider, "error", "Waiting for valid JSON");
    return false;
  } finally {
    securityProviderJsonSyncing = false;
  }
}

function bindSecurityProviderJson(provider) {
  const form = document.getElementById(`security-${provider}-form`);
  const editor = document.getElementById(`security-${provider}-json-editor`);
  form.addEventListener("input", () => syncSecurityProviderJsonFromForm(provider));
  form.addEventListener("change", () => syncSecurityProviderJsonFromForm(provider));
  editor.addEventListener("input", () => applySecurityProviderJsonEditor(provider));
  editor.addEventListener("blur", () => {
    if (applySecurityProviderJsonEditor(provider)) {
      syncSecurityProviderJsonFromForm(provider, { force: true });
    }
  });
}

async function loadSecurityLdap() {
  securityLdap = await fetchJson(
    "/internal/security/ldap",
    {
      enabled: false,
      priority: 10,
      source: "LDAP",
      protocol: "ldap",
      authScheme: "simple",
      connectionTimeout: 30,
      userSubtree: true,
      userObjectClass: "inetOrgPerson",
      userIdAttribute: "uid",
      userRealNameAttribute: "cn",
      userMemberOfAttribute: "memberOf",
      userEmailAddressAttribute: "mail",
      userPasswordAttribute: "userPassword",
      ldapGroupsAsRoles: true,
      groupType: "static",
      groupSubtree: true,
      groupIdAttribute: "cn",
      groupMemberAttribute: "member",
      groupMemberFormat: "${dn}",
      groupObjectClass: "groupOfNames",
      attributes: {}
    },
    "Failed to load LDAP settings");
  renderSecurityLdap();
}

function renderSecurityLdap() {
  const settings = securityLdap || {};
  clearRequiredFieldErrors(ldapRequiredFields);
  setCheckboxValue("security-ldap-enabled", settings.enabled);
  setInputValue("security-ldap-priority", Number(settings.priority ?? 10));
  setSelectValue("security-ldap-protocol", settings.protocol, "ldap");
  setInputValue("security-ldap-host", settings.host);
  setInputValue("security-ldap-port", settings.port);
  setCheckboxValue("security-ldap-trust-store", settings.useTrustStore);
  setInputValue("security-ldap-url", settings.url);
  setInputValue("security-ldap-search-base", settings.searchBase);
  setInputValue("security-ldap-auth-scheme", settings.authScheme, "simple");
  setInputValue("security-ldap-auth-realm", settings.authRealm);
  setInputValue("security-ldap-auth-username", settings.authUsername);
  setInputValue("security-ldap-auth-password", settings.authPassword);
  setInputValue("security-ldap-connection-timeout", Number(settings.connectionTimeout ?? 30));
  setInputValue("security-ldap-retry-delay", settings.connectionRetryDelay);
  setInputValue("security-ldap-max-incidents", settings.maxIncidentsCount);
  setInputValue("security-ldap-user-base-dn", settings.userBaseDn);
  setCheckboxValue("security-ldap-user-subtree", settings.userSubtree, true);
  setInputValue("security-ldap-user-object-class", settings.userObjectClass, "inetOrgPerson");
  setInputValue("security-ldap-user-filter", settings.userLdapFilter);
  setInputValue("security-ldap-user-id-attribute", settings.userIdAttribute, "uid");
  setInputValue("security-ldap-user-real-name-attribute", settings.userRealNameAttribute, "cn");
  setInputValue("security-ldap-user-member-of-attribute", settings.userMemberOfAttribute, "memberOf");
  setInputValue("security-ldap-user-email-attribute", settings.userEmailAddressAttribute, "mail");
  setInputValue("security-ldap-user-password-attribute", settings.userPasswordAttribute, "userPassword");
  setCheckboxValue("security-ldap-groups-as-roles", settings.ldapGroupsAsRoles, true);
  setSelectValue("security-ldap-group-type", settings.groupType, "static");
  setInputValue("security-ldap-group-base-dn", settings.groupBaseDn);
  setCheckboxValue("security-ldap-group-subtree", settings.groupSubtree, true);
  setInputValue("security-ldap-group-id-attribute", settings.groupIdAttribute, "cn");
  setInputValue("security-ldap-group-member-attribute", settings.groupMemberAttribute, "member");
  setInputValue("security-ldap-group-member-format", settings.groupMemberFormat, "${dn}");
  setInputValue("security-ldap-group-object-class", settings.groupObjectClass, "groupOfNames");
  securityProviderAttributes.ldap = editableSecurityProviderAttributes(settings.attributes, ["name"]);
  refreshSecurityLdapRequiredMarkers();
  setCheckboxValue("security-ldap-legacy-role-matching",
    securityProviderJsonBoolean(settings.attributes?.legacyRoleMatching, Boolean(settings.url || settings.host)));
  syncSecurityProviderJsonFromForm("ldap", { force: true });
}

function refreshSecurityLdapRequiredMarkers() {
  updateRequiredMarkers(ldapRequiredFields);
  const required = document.getElementById("security-ldap-enabled").checked;
  setFieldRequired(document.getElementById("security-ldap-url"), false);
  setFieldRequired(document.getElementById("security-ldap-host"), false);
  document.getElementById("security-ldap-url").setAttribute("aria-required", String(required));
  document.getElementById("security-ldap-host").setAttribute("aria-required", String(required));
  if (!required) {
    clearRequiredFieldErrors(ldapRequiredFields);
  }
}

function clearSecurityLdapRequiredErrors() {
  if (textInputValue("security-ldap-url") || textInputValue("security-ldap-host")) {
    clearRequiredFieldErrors(ldapRequiredFields);
  }
}

function validateSecurityLdapRequiredFields() {
  refreshSecurityLdapRequiredMarkers();
  const enabled = document.getElementById("security-ldap-enabled").checked;
  const url = textInputValue("security-ldap-url");
  const host = textInputValue("security-ldap-host");
  const invalid = enabled && !url && !host;
  markInputValidity(document.getElementById("security-ldap-url"), invalid);
  markInputValidity(document.getElementById("security-ldap-host"), invalid);
  if (invalid) {
    showToast("LDAP is enabled but URL or Host is required.", "error");
    document.getElementById("security-ldap-url").focus();
    return false;
  }
  return true;
}

async function saveSecurityLdap() {
  if (!validateSecurityLdapRequiredFields()) {
    return;
  }
  const payload = securityLdapFormValue({ maskSecrets: false });
  try {
    const response = await fetch("/internal/security/ldap", {
      method: "PUT",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(payload)
    });
    if (!response.ok) throw new Error(await responseErrorMessage(response));
    securityLdap = await response.json();
    renderSecurityLdap();
    showToast("LDAP settings saved.", "ok");
  } catch (error) {
    showToast(`Save failed: ${error.message}`, "error");
  }
}

async function loadSecurityOidc() {
  securityOidc = await fetchJson(
    "/internal/security/oidc",
    {
      enabled: false,
      priority: 20,
      source: "OIDC",
      userIdClaim: "preferred_username",
      firstNameClaim: "given_name",
      lastNameClaim: "family_name",
      emailClaim: "email",
      groupsClaim: "groups",
      rolesClaim: "roles",
      clockSkewSeconds: 60,
      jwksCacheSeconds: 300,
      attributes: {}
    },
    "Failed to load OIDC settings");
  renderSecurityOidc();
}

function renderSecurityOidc() {
  const settings = securityOidc || {};
  oidcRequiredFields.forEach((field) => {
    markInputValidity(document.getElementById(field.id), false);
  });
  document.getElementById("security-oidc-enabled").checked = Boolean(settings.enabled);
  document.getElementById("security-oidc-priority").value = Number(settings.priority ?? 20);
  document.getElementById("security-oidc-issuer").value = settings.issuerUri || settings.issuer || "";
  document.getElementById("security-oidc-jwks-uri").value = settings.jwksUri || "";
  document.getElementById("security-oidc-audience").value = settings.audience || "";
  document.getElementById("security-oidc-client-id").value = settings.clientId || "";
  document.getElementById("security-oidc-client-secret").value = settings.clientSecret || "";
  document.getElementById("security-oidc-authorization-endpoint").value = settings.authorizationEndpoint || "";
  document.getElementById("security-oidc-token-endpoint").value = settings.tokenEndpoint || "";
  document.getElementById("security-oidc-redirect-uri").value = settings.redirectUri || "";
  document.getElementById("security-oidc-scopes").value = settings.scopes || "openid profile email";
  document.getElementById("security-oidc-user-id-claim").value = settings.userIdClaim || "preferred_username";
  document.getElementById("security-oidc-first-name-claim").value = settings.firstNameClaim || "given_name";
  document.getElementById("security-oidc-last-name-claim").value = settings.lastNameClaim || "family_name";
  document.getElementById("security-oidc-email-claim").value = settings.emailClaim || "email";
  document.getElementById("security-oidc-groups-claim").value = settings.groupsClaim || "groups";
  document.getElementById("security-oidc-roles-claim").value = settings.rolesClaim || "roles";
  document.getElementById("security-oidc-clock-skew").value = Number(settings.clockSkewSeconds ?? 60);
  document.getElementById("security-oidc-jwks-cache").value = Number(settings.jwksCacheSeconds ?? 300);
  securityProviderAttributes.oidc = editableSecurityProviderAttributes(settings.attributes);
  refreshSecurityOidcRequiredMarkers();
  setCheckboxValue("security-oidc-legacy-role-matching",
    securityProviderJsonBoolean(settings.attributes?.legacyRoleMatching, Boolean(settings.issuer || settings.issuerUri || settings.jwksUri)));
  syncSecurityProviderJsonFromForm("oidc", { force: true });
}

function refreshSecurityOidcRequiredMarkers() {
  const enabled = document.getElementById("security-oidc-enabled").checked;
  oidcRequiredFields.forEach((field) => {
    const input = document.getElementById(field.id);
    setFieldRequired(input, enabled);
    updateRequiredMarker({
      ...field,
      required: () => enabled
    });
  });
  if (!enabled) {
    clearRequiredFieldErrors(oidcRequiredFields);
  }
}

function validateSecurityOidcRequiredFields() {
  const enabled = document.getElementById("security-oidc-enabled").checked;
  const missing = [];
  oidcRequiredFields.forEach((field) => {
    const input = document.getElementById(field.id);
    const invalid = enabled && !input.value.trim();
    markInputValidity(input, invalid);
    if (invalid) {
      missing.push(field.label);
    }
  });
  refreshSecurityOidcRequiredMarkers();
  if (missing.length) {
    showToast(`OIDC is enabled but required fields are missing: ${missing.join(", ")}`, "error");
    document.getElementById(oidcRequiredFields.find((field) => {
      const input = document.getElementById(field.id);
      return input.classList.contains("is-invalid");
    }).id).focus();
    return false;
  }
  return true;
}

async function saveSecurityOidc() {
  if (!validateSecurityOidcRequiredFields()) {
    return;
  }
  const payload = securityOidcFormValue({ maskSecrets: false });
  payload.issuerUri = payload.issuer;
  try {
    const response = await fetch("/internal/security/oidc", {
      method: "PUT",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(payload)
    });
    if (!response.ok) throw new Error(await responseErrorMessage(response));
    securityOidc = await response.json();
    renderSecurityOidc();
    showToast("OIDC settings saved.", "ok");
  } catch (error) {
    showToast(`Save failed: ${error.message}`, "error");
  }
}

async function loadSecurityAnonymous() {
  accessState.securityAnonymous = await fetchJson(
    "/internal/security/anonymous",
    { enabled: false, userSource: "Local", userId: "anonymous", realmName: "NexusAuthorizingRealm" },
    "Failed to load anonymous settings");
  renderSecurityAnonymous();
}

function renderSecurityAnonymous() {
  const settings = accessState.securityAnonymous || {};
  document.getElementById("security-anonymous-enabled").checked = Boolean(settings.enabled);
  document.getElementById("security-anonymous-source").value = "Local";
  document.getElementById("security-anonymous-user-id").value = settings.userId || "anonymous";
  document.getElementById("security-anonymous-realm-name").value = settings.realmName || "NexusAuthorizingRealm";
}

async function saveSecurityAnonymous() {
  if (!validateRequiredFields(securityAnonymousRequiredFields)) return;
  const payload = {
    enabled: document.getElementById("security-anonymous-enabled").checked,
    userSource: "Local",
    userId: document.getElementById("security-anonymous-user-id").value.trim() || "anonymous",
    realmName: document.getElementById("security-anonymous-realm-name").value.trim() || "NexusAuthorizingRealm"
  };
  try {
    const response = await fetch("/internal/security/anonymous", {
      method: "PUT",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(payload)
    });
    if (!response.ok) throw new Error(await responseErrorMessage(response));
    accessState.securityAnonymous = await response.json();
    renderSecurityAnonymous();
    showToast("Anonymous access saved.", "ok");
  } catch (error) {
    showToast(`Save failed: ${error.message}`, "error");
  }
}

export {
  applyOriginAwarePlaceholders,
  bindSecurityProviderJson,
  clearSecurityLdapRequiredErrors,
  ldapRequiredFields,
  loadSecurityAnonymous,
  loadSecurityLdap,
  loadSecurityOidc,
  oidcRequiredFields,
  refreshSecurityLdapRequiredMarkers,
  refreshSecurityOidcRequiredMarkers,
  saveSecurityAnonymous,
  saveSecurityLdap,
  saveSecurityOidc,
};
