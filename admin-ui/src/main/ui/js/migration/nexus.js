import { escapeHtml, shortText } from "../controls/display.js";
import { validateRequiredFields } from "../controls/fields.js";
import { showToast } from "../controls/toast.js";
import { loadRepositories } from "../repositories/list.js";
import { loadSecurityUsers } from "../security/access.js";
import { responseErrorMessage } from "../shell/http.js";
import { loadBlobStores } from "../storage/blob-stores.js";

const nexusMigrationRequiredFields = [
  { id: "migration-source-url", label: "Source URL" },
  { id: "migration-source-username", label: "Source username" },
  { id: "migration-source-password", label: "Source password" }
];

function migrationPayload() {
  return {
    sourceBaseUrl: document.getElementById("migration-source-url").value.trim(),
    sourceUsername: document.getElementById("migration-source-username").value.trim(),
    sourcePassword: document.getElementById("migration-source-password").value
  };
}

function renderCompactTable(headers, rows, emptyText = "") {
  if (!rows.length) {
    return emptyText ? `<pre class="code-panel">${escapeHtml(emptyText)}</pre>` : "";
  }
  return `
    <table class="nx-table compact migration-detail-table">
      <thead><tr>${headers.map((header) => `<th>${escapeHtml(header.label)}</th>`).join("")}</tr></thead>
      <tbody>
        ${rows.map((row) => `
          <tr>
            ${headers.map((header) => `<td>${migrationTableCell(header, row)}</td>`).join("")}
          </tr>
        `).join("")}
      </tbody>
    </table>
  `;
}

function migrationTableCell(header, row) {
  if (header.html) {
    return header.html(row);
  }
  return escapeHtml(header.value(row));
}

function renderMigrationSection(title, html) {
  if (!html) return "";
  return `
    <div class="migration-list-title">${escapeHtml(title)}</div>
    ${html}
  `;
}

function renderMigrationList(title, values) {
  const items = values || [];
  if (!items.length) return "";
  return renderMigrationSection(title, `<pre class="code-panel">${escapeHtml(items.join("\\n"))}</pre>`);
}

function migrationValues(...sources) {
  return sources
    .flatMap((source) => Array.isArray(source) ? source : [])
    .filter((value) => value != null && String(value).trim())
    .map((value) => String(value))
    .filter((value, index, values) => values.indexOf(value) === index);
}

function renderMigrationProfile(profile, plan) {
  if (!profile) return "";
  const scriptApi = profile.scriptApi || {};
  const blobModel = profile.blobModel || {};
  return renderMigrationSection("Source profile", renderCompactTable([
    { label: "Version", value: (source) => source.nexusVersion || "" },
    { label: "Metadata", value: (source) => source.metadataEngine || "" },
    { label: "Repository model", value: (source) => source.repositoryModel || "" },
    { label: "Security model", value: (source) => source.securityModel || "" },
    { label: "Script API", value: () => migrationScriptApiSummary(scriptApi) },
    { label: "Script run type", value: () => scriptApi.runContentType || "" },
    { label: "Blob read", value: () => blobModel.readMode || "" },
    { label: "Blob types", value: () => (blobModel.sourceTypes || []).join(", ") || "-" },
    { label: "Profile hash", html: () => renderMigrationHash(plan?.profileHash) }
  ], [profile]));
}

function renderMigrationPlan(plan) {
  if (!plan) return "";
  return renderMigrationSection("Migration plan", renderCompactTable([
    { label: "Adapter", value: (value) => value.adapter || "" },
    { label: "Profile hash", html: (value) => renderMigrationHash(value.profileHash) },
    { label: "Plan hash", html: (value) => renderMigrationHash(value.planHash) },
    { label: "Plan items", value: (value) => (value.items || []).length },
    { label: "Warnings", value: (value) => (value.warnings || []).length },
    { label: "Manual actions", value: (value) => (value.manualActions || []).length }
  ], [plan]));
}

function renderMigrationPlanItems(plan) {
  const items = plan?.items || [];
  return renderMigrationSection("Plan items", renderCompactTable([
    { label: "Area", value: (item) => item.area || "" },
    { label: "Name", value: (item) => item.name || "" },
    { label: "Format", value: (item) => item.format || "" },
    { label: "Type", value: (item) => item.type || "" },
    { label: "Status", html: (item) => migrationPlanStatusBadge(item.status) },
    { label: "Source adapter", value: (item) => item.sourceAdapter || "" },
    { label: "Format adapter", value: (item) => item.formatAdapter || "" },
    { label: "Read mode", value: (item) => item.readMode || "" },
    { label: "Write mode", value: (item) => item.writeMode || "" },
    { label: "Checksum", value: (item) => item.checksumMode || "" },
    { label: "Resume key", value: (item) => item.resumeKey || "" },
    { label: "Reasons", value: (item) => (item.reasons || []).join("; ") || "-" },
    { label: "Warnings", value: (item) => (item.warnings || []).join("; ") || "-" }
  ], items));
}

function migrationScriptApiSummary(scriptApi) {
  const status = scriptApi.status || "unknown";
  const runnable = scriptApi.runnable ? "runnable" : "not runnable";
  const cleanup = scriptApi.deletedAfterProbe ? "deleted" : "not deleted";
  return `${status}; ${runnable}; ${cleanup}`;
}

function migrationPlanStatusBadge(status) {
  const value = String(status || "-");
  const tone = value === "FULL" ? "ok"
    : value === "CONFIG_ONLY" || value === "DATA_ONLY" ? "warn"
      : value === "UNSUPPORTED" || value === "NEEDS_MANUAL_ACTION" ? "bad" : "checking";
  return `<span class="state-badge compact ${tone}">${escapeHtml(value)}</span>`;
}

function renderMigrationHash(value) {
  const hash = String(value || "");
  if (!hash) return '<span class="health-muted">-</span>';
  return `<code class="migration-hash-cell" title="${escapeHtml(hash)}">${escapeHtml(shortText(hash, 18))}</code>`;
}

function renderMigrationUserTokens(report) {
  if (!report || !(report.tokens > 0 || report.legacyTableTokens > 0 || report.legacyTableUnreadable)) return "";
  return renderMigrationSection("Nexus Pro user tokens (not migrated)", renderCompactTable([
    { label: "Tokens", value: (value) => value.tokens ?? 0 },
    { label: "Owners", value: (value) => migrationValues(value.owners).join(", ") || "-" },
    { label: "Legacy table tokens", value: (value) => value.legacyTableUnreadable
      ? "unreadable (count unknown)" : value.legacyTableTokens ?? 0 },
    { label: "Legacy table owners", value: (value) => [...migrationValues(value.legacyTableOwners),
      ...(value.legacyTableOwnersUnverified ? ["unverified owners not shown"] : [])].join(", ") || "-" },
    { label: "Message", value: (value) => value.message || "" }
  ], [report]));
}

function renderMigrationResult(payload, title) {
  const result = document.getElementById("migration-result");
  const preflight = payload.preflight || payload;
  const config = payload.config || {};
  const apiSecurity = payload.apiSecurity || null;
  const security = preflight.security || {};
  const blobStorePlans = preflight.blobStorePlans || [];
  const repositoriesToMigrate = preflight.repositoriesToMigrate || [];
  const groupRepositories = preflight.groupRepositories || [];
  const routingRules = preflight.routingRules || {};
  const unsupported = preflight.unsupported || [];
  const proxyRisks = preflight.proxyRemoteRisks || [];
  const sourceProfile = preflight.sourceProfile || payload.sourceProfile || null;
  const migrationPlan = preflight.migrationPlan || payload.migrationPlan || null;
  const passwordUsers = payload.passwordResetRequiredUsers || preflight.passwordResetRequiredUsers || [];
  const warnings = migrationValues(preflight.warnings, payload.warnings, migrationPlan?.warnings);
  const validation = payload.validation || {};
  const validationChecks = validation.checks || [];
  const manualActions = migrationValues(validation.manualActions, migrationPlan?.manualActions);
  result.hidden = false;
  result.innerHTML = `
    <div class="form-title">${escapeHtml(title)}</div>
    <div class="summary-grid">
      <div><span>Status</span><strong>${escapeHtml(payload.status || "preflight")}</strong></div>
      <div><span>Plan items</span><strong>${escapeHtml(migrationPlan?.items?.length ?? 0)}</strong></div>
      <div><span>Blob stores</span><strong>${escapeHtml(config.blobStores ?? preflight.blobStores ?? 0)}</strong></div>
      <div><span>Repositories</span><strong>${escapeHtml(config.repositories ?? preflight.supportedRepositories ?? 0)}</strong></div>
      <div><span>Unsupported</span><strong>${escapeHtml(config.unsupportedRepositories ?? preflight.unsupportedRepositories ?? 0)}</strong></div>
      <div><span>Groups</span><strong>${escapeHtml(config.groupRepositories ?? groupRepositories.length ?? 0)}</strong></div>
      <div><span>Users</span><strong>${escapeHtml(apiSecurity?.users ?? security.users ?? preflight.users ?? 0)}</strong></div>
      <div><span>Roles</span><strong>${escapeHtml(apiSecurity?.roles ?? security.roles ?? 0)}</strong></div>
      <div><span>Privileges</span><strong>${escapeHtml(apiSecurity?.privileges ?? security.privileges ?? 0)}</strong></div>
      <div><span>API keys</span><strong>${escapeHtml(apiSecurity?.apiKeys ?? security.apiKeys ?? 0)}</strong></div>
      <div><span>Password resets</span><strong>${escapeHtml(passwordUsers.length)}</strong></div>
    </div>
    ${renderMigrationProfile(sourceProfile, migrationPlan)}
    ${renderMigrationPlan(migrationPlan)}
    ${renderMigrationPlanItems(migrationPlan)}
    ${renderMigrationList("Warnings", warnings)}
    ${renderMigrationList("Routing rule migration blockers", routingRules.errors || [])}
    ${renderMigrationSection("Routing Rules", renderCompactTable([
      { label: "Name", value: (rule) => rule.name },
      { label: "Mode", value: (rule) => rule.mode },
      { label: "Matchers", value: (rule) => (rule.matchers || []).join("\n") },
      { label: "Description", value: (rule) => rule.description }
    ], routingRules.rules || [], "No routing rules found."))}
    ${renderMigrationSection("Routing rule bindings", renderCompactTable([
      { label: "Repository", value: (binding) => binding.repository },
      { label: "Routing Rule", value: (binding) => binding.rule }
    ], routingRules.bindings || []))}
    ${routingRules.checksum ? renderMigrationSection("Routing policy checksum", renderMigrationHash(routingRules.checksum)) : ""}
    ${renderMigrationSection("Blob stores", renderCompactTable([
      { label: "Source", value: (store) => store.sourceName || "" },
      { label: "Source type", value: (store) => store.sourceType || "" },
      { label: "Target", value: (store) => store.targetName || "" },
      { label: "Target type", value: (store) => store.targetType || "" },
      { label: "Bucket", value: (store) => store.targetBucket || "" },
      { label: "Prefix", value: (store) => store.targetPrefix || "" }
    ], blobStorePlans, "No source blob stores were reported; the default target blob store will be used."))}
    ${renderMigrationSection("Repositories to migrate", renderCompactTable([
      { label: "Name", value: (repo) => repo.name || "" },
      { label: "Format", value: (repo) => repo.format || "" },
      { label: "Type", value: (repo) => repo.type || "" },
      { label: "Recipe", value: (repo) => repo.recipe || "" },
      { label: "Blob store", value: (repo) => repo.blobStoreName || "" },
      { label: "Online", value: (repo) => repo.online === false ? "false" : "true" },
      { label: "Remote URL", value: (repo) => repo.remoteUrl || "" }
    ], repositoriesToMigrate, "No supported repositories were found in the source inventory."))}
    ${renderMigrationSection("Group members", renderCompactTable([
      { label: "Repository", value: (repo) => repo.repository || "" },
      { label: "Format", value: (repo) => repo.format || "" },
      { label: "Members", value: (repo) => (repo.members || []).join(", ") || "-" }
    ], groupRepositories))}
    ${renderMigrationSection("Unsupported repositories", renderCompactTable([
      { label: "Name", value: (repo) => repo.name || "" },
      { label: "Format", value: (repo) => repo.format || "" },
      { label: "Type", value: (repo) => repo.type || "" },
      { label: "Reason", value: (repo) => repo.reason || "" }
    ], unsupported))}
    ${renderMigrationSection("Proxy remotes", renderCompactTable([
      { label: "Repository", value: (risk) => risk.repository || "" },
      { label: "Format", value: (risk) => risk.format || "" },
      { label: "Remote URL", value: (risk) => risk.remoteUrl || "" },
      { label: "Status", value: (risk) => risk.status || "" }
    ], proxyRisks))}
    ${renderMigrationSection("Security users", renderCompactTable([
      { label: "Source", value: (user) => user.source || "" },
      { label: "User ID", value: (user) => user.userId || "" },
      { label: "Status", value: (user) => user.status || "" },
      { label: "Email", value: (user) => user.email || "" },
      { label: "Password hash", value: (user) => user.passwordHashPresent ? "present" : "missing" }
    ], security.userDetails || []))}
    ${renderMigrationSection("Security roles", renderCompactTable([
      { label: "Role ID", value: (role) => role.id || "" },
      { label: "Source", value: (role) => role.source || "" },
      { label: "Name", value: (role) => role.name || "" },
      { label: "Read only", value: (role) => role.readOnly ? "true" : "false" },
      { label: "Privileges", value: (role) => (role.privileges || []).join(", ") || "-" },
      { label: "Child roles", value: (role) => (role.childRoles || []).join(", ") || "-" }
    ], security.roleDetails || []))}
    ${renderMigrationSection("User role mappings", renderCompactTable([
      { label: "Source", value: (mapping) => mapping.source || "" },
      { label: "User ID", value: (mapping) => mapping.userId || "" },
      { label: "Roles", value: (mapping) => (mapping.roles || []).join(", ") || "-" }
    ], security.userRoleMappingDetails || []))}
    ${renderMigrationSection("API keys", renderCompactTable([
      { label: "Domain", value: (apiKey) => apiKey.domain || "" },
      { label: "Owner source", value: (apiKey) => apiKey.ownerSource || "" },
      { label: "Owner user", value: (apiKey) => apiKey.ownerUserId || "" },
      { label: "Display name", value: (apiKey) => apiKey.displayName || "" },
      { label: "Status", value: (apiKey) => apiKey.status || "" },
      { label: "Raw key", value: (apiKey) => apiKey.rawKeyPresent ? "present" : "missing" }
    ], security.apiKeyDetails || []))}
    ${renderMigrationUserTokens(security.userTokens)}
    ${renderMigrationSection("Content selectors", renderCompactTable([
      { label: "Name", value: (selector) => selector.name || "" },
      { label: "Type", value: (selector) => selector.type || "" },
      { label: "Format", value: (selector) => selector.format || "" },
      { label: "Expression", value: (selector) => selector.expression || "" }
    ], security.contentSelectorDetails || []))}
    ${renderMigrationList("Realm order", security.realmOrder || [])}
    ${security.anonymous ? renderMigrationSection("Anonymous access", renderCompactTable([
      { label: "Enabled", value: (anonymous) => anonymous.enabled ? "true" : "false" },
      { label: "User source", value: (anonymous) => anonymous.userSource || "" },
      { label: "User ID", value: (anonymous) => anonymous.userId || "" },
      { label: "Realm", value: (anonymous) => anonymous.realmName || "" }
    ], [security.anonymous])) : ""}
    ${renderMigrationList("Password reset required", passwordUsers)}
    ${renderMigrationList("Manual actions", manualActions)}
    ${renderMigrationSection("Validation result", renderCompactTable([
      { label: "Scope", value: (check) => check.scope || "" },
      { label: "Check", value: (check) => check.name || "" },
      { label: "Status", value: (check) => check.status || "" },
      { label: "Message", value: (check) => check.message || "" },
      { label: "Details", value: (check) => (check.details || []).join(", ") || "-" }
    ], validationChecks))}
  `;
}

function renderMigrationError(title, message) {
  const result = document.getElementById("migration-result");
  result.hidden = false;
  result.innerHTML = `
    <div class="form-title">${escapeHtml(title)}</div>
    <div class="migration-error-panel">
      <div class="migration-list-title">Error</div>
      <pre class="code-panel">${escapeHtml(message || "Migration request failed.")}</pre>
    </div>
  `;
}

async function runNexusMigrationPreflight() {
  if (!validateRequiredFields(nexusMigrationRequiredFields)) return;
  try {
    showToast("Running preflight...");
    const response = await fetch("/internal/migration/nexus/preflight", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(migrationPayload())
    });
    if (!response.ok) throw new Error(await responseErrorMessage(response));
    renderMigrationResult(await response.json(), "Preflight result");
    showToast("Preflight finished.", "ok");
  } catch (error) {
    renderMigrationError("Preflight failed", error.message);
    showToast(`Preflight failed: ${error.message}`, "error");
  }
}

async function runNexusMigration() {
  if (!validateRequiredFields(nexusMigrationRequiredFields)) return;
  try {
    showToast("Running migration...");
    const response = await fetch("/internal/migration/nexus/run", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(migrationPayload())
    });
    if (!response.ok) throw new Error(await responseErrorMessage(response));
    renderMigrationResult(await response.json(), "Migration result");
    showToast("Migration finished.", "ok");
    await Promise.all([loadBlobStores(), loadRepositories(), loadSecurityUsers()]);
  } catch (error) {
    renderMigrationError("Migration failed", error.message);
    showToast(`Migration failed: ${error.message}`, "error");
  }
}

export {
  nexusMigrationRequiredFields,
  renderMigrationList,
  renderMigrationPlan,
  renderMigrationPlanItems,
  renderMigrationProfile,
  runNexusMigration,
  runNexusMigrationPreflight,
};
