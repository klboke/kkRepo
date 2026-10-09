const assert = require("node:assert/strict");
const { readFileSync } = require("node:fs");
const { resolve } = require("node:path");
const test = require("node:test");
const vm = require("node:vm");

const resources = resolve(__dirname, "../../main/resources/META-INF/resources/admin");
const source = readFileSync(resolve(resources, "assets/admin.js"), "utf8");
const html = readFileSync(resolve(resources, "index.html"), "utf8");

function extractFunction(name) {
  const start = source.indexOf(`function ${name}(`);
  assert.notEqual(start, -1, name);
  const end = source.indexOf("\n}\n", start);
  return source.slice(source.slice(start - 6, start) === "async " ? start - 6 : start, end + 3);
}

function deferred() {
  let resolve;
  const promise = new Promise((yes) => { resolve = yes; });
  return { promise, resolve };
}

function start(hash, permissions = ["nexus:*"]) {
  const views = [], history = [], loads = [], tabs = [];
  const elements = new Map();
  const viewNames = [...html.matchAll(/<section class="view[^\"]*" id="([^\"]+)-view"/g)]
    .map((match) => match[1]);
  for (const view of viewNames) {
    const classes = new Set(view === "loading" ? ["is-active"] : []);
    elements.set(`${view}-view`, {
      id: `${view}-view`, textContent: view === "loading" ? "Loading…" : "",
      classList: {
        contains: (value) => classes.has(value),
        toggle: (value, active) => {
          if (active) { classes.add(value); views.push(view); }
          else classes.delete(value);
        },
      },
    });
  }
  const items = viewNames.filter((view) => view !== "loading").map((view) => ({
    dataset: { view }, hidden: false, classList: { toggle() {} },
  }));
  const sessionResponse = deferred(), recipesResponse = deferred();
  const location = new URL(`https://example.test/admin/${hash}`);
  const navigate = (kind, target) => {
    history.push([kind, target]);
    location.href = new URL(target, location).href;
  };
  const context = vm.createContext({
    initialDataLoaded: false, currentAdminPermissions: [],
    securityScanState: { repositoryEditRequest: 0 },
    window: { location, history: {
      pushState: (_state, _title, target) => navigate("push", target),
      replaceState: (_state, _title, target) => navigate("replace", target),
    }, KkContentSelectors: { load: () => loads.push("content-selectors") } },
    document: {
      getElementById: (id) => elements.get(id),
      querySelectorAll: (selector) => selector === ".view" ? [...elements.values()]
        : selector === ".side-item" ? items : [],
    },
    hydrateSessionControls: () => loads.push("hydrate"),
    loadCurrentSession: async () => {
      loads.push("session");
      const session = await sessionResponse.promise;
      context.currentAdminPermissions = session ? permissions : [];
      return session;
    },
    loadRepositoryRecipes: () => { loads.push("recipes"); return recipesResponse.promise; },
    updateCurrentSideGroup() {},
    selectSecurityScanTab: (tab) => tabs.push(["scan", tab]),
    selectCleanupTab: (tab) => tabs.push(["cleanup", tab]),
  });
  for (const name of extractFunction("switchView").matchAll(/\b(load\w+)\(/g)) {
    context[name[1]] = () => loads.push(name[1]);
  }
  vm.runInContext(source.slice(source.indexOf("const SECURITY_SCAN_ROUTE_BASE"),
    source.indexOf("const memberTransfer")) + [
    "normalizeAdminHash", "securityScanTabFromHash", "cleanupTabFromHash", "viewFromHash",
    "updateHashForView", "switchView", "applyHashRoute", "bootstrap",
  ].map(extractFunction).join("\n"), context);
  const completion = context.bootstrap();
  return { context, location, views, history, loads, tabs, elements, items,
    completion, sessionResponse, recipesResponse,
    finish(session = { userId: "admin" }) {
      sessionResponse.resolve(session);
      recipesResponse.resolve();
      return completion;
    } };
}

test("Admin first paint shows a loading status without selecting Repositories", () => {
  assert.match(html, /class="view is-active" id="loading-view" role="status"/);
  assert.match(html, /class="view fixed-table-view" id="repositories-view"/);
  assert.doesNotMatch(html, /class="side-item is-active" data-view="repositories"/);
});

for (const [hash, view] of [
  ["#admin/security/users", "security-users"],
  ["#admin/repository/blobstores", "blobstores"],
  ["#admin/repository/content-selectors", "content-selectors"],
  ["#admin/security/artifact-scanning/tasks", "security-scanning"],
  ["#admin/repository/cleanup/runs", "cleanup-policies"],
]) {
  test(`opens ${hash} directly after discovery`, async () => {
    const app = start(hash);
    assert.deepEqual(app.loads, ["hydrate", "session"]);
    app.context.applyHashRoute();
    assert.deepEqual(app.views, []);
    assert.equal(app.location.hash, hash);
    app.sessionResponse.resolve({ userId: "admin" });
    await new Promise(setImmediate);
    app.context.applyHashRoute();
    assert.deepEqual(app.views, []);
    app.recipesResponse.resolve();
    await app.completion;
    assert.deepEqual(app.views, [view]);
    assert.deepEqual(app.history, []);
    assert.equal(app.location.hash, hash);
    if (view === "security-scanning") assert.deepEqual(app.tabs, [["scan", "tasks"]]);
    if (view === "cleanup-policies") assert.deepEqual(app.tabs, [["cleanup", "runs"]]);
  });
}

test("navigation while recipes are pending selects the latest URL", async () => {
  const app = start("#admin/security/users");
  app.sessionResponse.resolve({ userId: "admin" });
  await new Promise(setImmediate);
  app.location.hash = "#admin/security/artifact-scanning/policies";
  app.context.applyHashRoute();
  await app.finish();
  assert.deepEqual(app.views, ["security-scanning"]);
  assert.deepEqual(app.tabs, [["scan", "policies"]]);
});

for (const hash of ["", "#unknown", "#admin"]) {
  test(`default route ${hash} opens Repositories after discovery`, async () => {
    const app = start(hash);
    await app.finish();
    assert.deepEqual(app.views, ["repositories"]);
    assert.equal(app.location.hash, hash);
    assert.deepEqual(app.history, []);
  });
}

test("selector-only accounts retain their authorized landing page without a history detour", async () => {
  const app = start("#admin/security/users", ["nexus:selectors:read"]);
  assert.equal(app.context.switchView("content-selectors"), false);
  assert.deepEqual(app.views, []);
  await app.finish({ userId: "selector-reader" });
  assert.deepEqual(app.views, ["content-selectors"]);
  assert.deepEqual(app.history, [["replace", "#admin/repository/content-selectors"]]);
  assert.ok(app.items.every((item) => item.hidden === (item.dataset.view !== "content-selectors")));
  assert.ok(!app.loads.includes("recipes"));
  assert.equal(app.context.switchView("security-users"), false);
});

test("session failure leaves a visible error without rendering a protected page", async () => {
  const app = start("#admin/security/users");
  await app.finish(null);
  assert.deepEqual(app.views, []);
  assert.equal(app.elements.get("loading-view").textContent, "Failed to load session");
  assert.equal(app.context.applyHashRoute(), false);
  assert.equal(app.location.hash, "#admin/security/users");
});

test("hash navigation after startup changes pages and nested tabs without pushing history", async () => {
  const app = start("#admin/security/users");
  await app.finish();
  app.location.hash = "#admin/security/artifact-scanning/tasks";
  app.context.applyHashRoute();
  app.location.hash = "#admin/security/artifact-scanning/findings";
  app.context.applyHashRoute();
  app.location.hash = "#admin/security/users";
  app.context.applyHashRoute();
  assert.deepEqual(app.views, ["security-users", "security-scanning", "security-users"]);
  assert.deepEqual(app.tabs, [["scan", "tasks"], ["scan", "findings"]]);
  assert.deepEqual(app.history, []);
});
