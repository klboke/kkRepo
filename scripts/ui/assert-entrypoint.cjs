const assert = require("node:assert/strict");
const { readFileSync } = require("node:fs");
const { resolve } = require("node:path");

module.exports = function assertSharedScriptOrder(page) {
  const base = resolve(__dirname, "../../", `${page}-ui/target/generated-ui/META-INF/resources/${page}`);
  const html = readFileSync(resolve(base, "index.html"), "utf8");
  const scripts = [...html.matchAll(/<script (?:type="module" )?src="([^"]+)"/g)].map((match) => match[1]);
  const csrfIndex = scripts.findIndex((src) => src.startsWith("/login/assets/csrf-fetch.js?"));
  const safeReturnToIndex = scripts.findIndex((src) =>
    src.startsWith("/login/assets/safe-return-to.js?"));
  const confirmationIndex = scripts.findIndex((src) => src.startsWith("/login/assets/delete-confirmation.js?"));
  const consumerIndex = scripts.findIndex((src) => src.includes(`/assets/${page}.js?`));
  assert.ok(csrfIndex >= 0 && csrfIndex < consumerIndex);
  assert.ok(safeReturnToIndex >= 0 && safeReturnToIndex < consumerIndex);
  assert.ok(confirmationIndex >= 0 && confirmationIndex < consumerIndex);
  const source = readFileSync(resolve(base, `assets/${page}.js`), "utf8");
  assert.ok(source.includes("function toggleUserMenu"));
  assert.ok(!source.includes("function installCsrfFetch"));
  if (page === "browse") {
    const loginModalIndex = scripts.findIndex((src) => src.includes("login-modal.js?"));
    assert.ok(safeReturnToIndex < loginModalIndex);
    assert.ok(csrfIndex < loginModalIndex);
  }
};
