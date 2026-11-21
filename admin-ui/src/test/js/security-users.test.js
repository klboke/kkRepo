const assert = require("node:assert/strict");
const { readFileSync } = require("node:fs");
const test = require("node:test");
const vm = require("node:vm");

const source = readFileSync(require.resolve("../../main/resources/META-INF/resources/admin/assets/admin.js"), "utf8");

test("user badges distinguish active and disabled statuses regardless of case", () => {
  const table = { innerHTML: "" };
  const statuses = ["active", "ACTIVE", "Active", "disabled", "DISABLED", "changepassword", null];
  const context = vm.createContext({
    securityUserRoleFilter: "",
    securityUsers: statuses.map((status, index) => ({ source: "Local", userId: `user-${index}`, status })),
    renderSecurityUserSourceFilter() {},
    filterValue: () => "",
    displaySource: (value) => value,
    listContainsFilter: () => true,
    escapeHtml: (value) => value,
    document: { getElementById: (id) => id === "security-user-table" ? table : null }
  });

  const render = source.slice(source.indexOf("function securityRoleBadges("), source.indexOf("function showSecurityUserForm("));
  vm.runInContext(render + "\nrenderSecurityUsers();", context);

  const badges = [...table.innerHTML.matchAll(/class="state-badge compact (\w+)">([^<]*)<\/span>/g)];
  assert.deepEqual(badges.map(([, tone, label]) => [tone, label]), [
    ["ok", "active"], ["ok", "ACTIVE"], ["ok", "Active"],
    ["warn", "disabled"], ["warn", "DISABLED"], ["warn", "changepassword"], ["warn", ""]
  ]);
});

test("external roles are searchable and labelled with their assignment source", () => {
  const table = { innerHTML: "" };
  const context = vm.createContext({
    securityUserRoleFilter: "",
    securityUsers: [
      { source: "OIDC", userId: "alice", status: "ACTIVE", roles: [], externalRoles: ["repo-reader"] },
      { source: "LDAP", userId: "bob", status: "ACTIVE", roles: ["publisher"], externalRoles: [] }
    ],
    renderSecurityUserSourceFilter() {},
    filterValue: () => "repo-reader",
    displaySource: (value) => value,
    listContainsFilter: (values, filter) => values.includes(filter),
    escapeHtml: (value) => value,
    document: { getElementById: (id) => id === "security-user-table" ? table : null }
  });

  const render = source.slice(source.indexOf("function securityRoleBadges("), source.indexOf("function showSecurityUserForm("));
  vm.runInContext(render + "\nrenderSecurityUsers();", context);

  assert.match(table.innerHTML, /repo-reader · OIDC/);
  assert.match(table.innerHTML, /alice/);
  assert.doesNotMatch(table.innerHTML, /bob|publisher/);
});
