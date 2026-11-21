const assert = require("node:assert/strict");
const { readFileSync } = require("node:fs");
const test = require("node:test");
const vm = require("node:vm");

const source = readFileSync(require.resolve("../../main/resources/META-INF/resources/admin/assets/admin.js"), "utf8");

test("user badges distinguish active and disabled statuses regardless of case", () => {
  const table = { innerHTML: "" };
  const statuses = ["active", "ACTIVE", "Active", "disabled", "DISABLED", "changepassword", null];
  const context = vm.createContext({
    securityUsers: statuses.map((status, index) => ({ source: "Local", userId: `user-${index}`, status })),
    renderSecurityUserSourceFilter() {},
    filterValue: () => "",
    displaySource: (value) => value,
    listContainsFilter: () => true,
    escapeHtml: (value) => value,
    document: { getElementById: (id) => id === "security-user-table" ? table : null }
  });

  const render = source.slice(source.indexOf("function renderSecurityUsers()"), source.indexOf("function showSecurityUserForm("));
  vm.runInContext(render + "\nrenderSecurityUsers();", context);

  const badges = [...table.innerHTML.matchAll(/class="state-badge compact (\w+)">([^<]*)<\/span>/g)];
  assert.deepEqual(badges.map(([, tone, label]) => [tone, label]), [
    ["ok", "active"], ["ok", "ACTIVE"], ["ok", "Active"],
    ["warn", "disabled"], ["warn", "DISABLED"], ["warn", "changepassword"], ["warn", ""]
  ]);
});
