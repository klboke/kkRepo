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
      id: `${view}-view`, textContent: "",
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
  const groups = ['repository', 'security', 'system'].map(sideGroup => ({ dataset: { sideGroup }, hidden: false }));
  const groupItems = groups.map(group => ({
    dataset: { sideGroupItems: group.dataset.sideGroup },
    querySelectorAll: () => items.filter(item => group.dataset.sideGroup === 'security'
      ? item.dataset.view.startsWith('security-')
      : group.dataset.sideGroup === 'repository' ? item.dataset.view === 'content-selectors' : false),
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
        : selector === ".side-item" ? items : selector === ".side-group" ? groups
        : selector === ".side-group-items[data-side-group-items]" ? groupItems : [],
    },
    hydrateSessionControls: () => loads.push("hydrate"),
    loadCurrentSession: async () => {
      loads.push("session");
      const session = await sessionResponse.promise;
      context.currentAdminPermissions = session ? permissions : [];
      return session;
    },
    loadConanManifestDefaults: async () => {},
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
    "sideGroupItems", "permissionPartMatches", "permissionMatches", "canAdmin", "canAccessAdminView",
    "normalizeAdminHash", "securityScanTabFromHash", "cleanupTabFromHash", "viewFromHash",
    "updateHashForView", "switchView", "applyHashRoute", "bootstrap",
  ].map(extractFunction).join("\n"), context);
  const completion = context.bootstrap();
  return { context, location, views, history, loads, tabs, elements, items, groups,
    completion, sessionResponse, recipesResponse,
    finish(session = { userId: "admin" }) {
      sessionResponse.resolve(session);
      recipesResponse.resolve();
      return completion;
    } };
}

test("Admin first paint does not select Repositories before routing", () => {
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

for (const phase of ["session", "recipes"]) {
  test(`sidebar clicks while ${phase} discovery is pending preserve the latest selection`, async () => {
    const app = start("#admin/security/users");
    if (phase === "recipes") {
      app.sessionResponse.resolve({ userId: "admin" });
      await new Promise(setImmediate);
    }
    app.context.switchView("blobstores");
    app.context.switchView("security-scanning");
    assert.equal(app.location.hash, "#admin/security/artifact-scanning");
    assert.deepEqual(app.views, []);
    assert.ok(!app.loads.includes("loadSecurityScanning"));
    await app.finish();
    assert.deepEqual(app.views, ["security-scanning"]);
    assert.deepEqual(app.tabs, [["scan", "overview"]]);
  });
}

test("Back during discovery supersedes a queued sidebar selection", async () => {
  const app = start("#admin/security/users");
  app.context.switchView("blobstores");
  assert.equal(app.location.hash, "#admin/repository/blobstores");
  app.location.hash = "#admin/security/users";
  app.context.applyHashRoute();
  await app.finish();
  assert.deepEqual(app.views, ["security-users"]);
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
  assert.deepEqual(app.views, []);
  await app.finish({ userId: "selector-reader" });
  assert.deepEqual(app.views, ["content-selectors"]);
  assert.deepEqual(app.history, [["replace", "#admin/repository/content-selectors"]]);
  assert.ok(app.items.every((item) => item.hidden === (item.dataset.view !== "content-selectors")));
  assert.ok(!app.loads.includes("recipes"));
  assert.equal(app.context.switchView("security-users"), false);
});

test("a queued sidebar click cannot bypass the resolved selector-only permissions", async () => {
  const app = start("#admin/security/users", ["nexus:selectors:read"]);
  app.context.switchView("security-roles");
  assert.equal(app.location.hash, "#admin/security/roles");
  assert.deepEqual(app.views, []);
  await app.finish({ userId: "selector-reader" });
  assert.deepEqual(app.views, ["content-selectors"]);
  assert.ok(!app.loads.includes("loadSecurityRoles"));
  assert.equal(app.location.hash, "#admin/repository/content-selectors");
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

for (const grants of [
  ['nexus:security-scanning:read'], ['nexus:security-scanning:read,update'],
  ['nexus:security-scanning:*'], ['nexus:selectors,security-scanning:read'],
]) {
  test(`scan readers honor the nested route with ${grants}`, async () => {
    const app = start('#admin/security/artifact-scanning/findings', grants);
    await app.finish({ userId: 'scan-reader' });
    assert.deepEqual(app.views, ['security-scanning']);
    assert.deepEqual(app.tabs, [['scan', 'findings']]);
    assert.deepEqual(app.history, []);
    assert.ok(!app.loads.includes('recipes'));
    assert.ok(!app.loads.includes('loadBlobStores'));
    assert.equal(app.context.switchView('security-users'), false);
    assert.equal(app.context.switchView('repositories'), false);
    assert.equal(app.items.find(item => item.dataset.view === 'security-scanning').hidden, false);
  });
}

for (const hash of ['', '#unknown', '#admin/security/users', '#admin/repository/content-selectors']) {
  test(`scan-only fallback from ${hash} shows only the scan view`, async () => {
    const app = start(hash, ['nexus:security-scanning:read']);
    await app.finish();
    assert.deepEqual(app.views, ['security-scanning']);
    assert.equal(app.location.hash, '#admin/security/artifact-scanning');
    assert.equal(app.groups.find(group => group.dataset.sideGroup === 'security').hidden, false);
    assert.equal(app.groups.find(group => group.dataset.sideGroup === 'repository').hidden, true);
    assert.ok(app.items.every(item => item.hidden === (item.dataset.view !== 'security-scanning')));
  });
}

test('mixed reader honors selectors and browser history between both authorized views', async () => {
  const app = start('#admin/repository/content-selectors', ['nexus:selectors:read', 'nexus:security-scanning:read']);
  await app.finish();
  assert.deepEqual(app.views, ['content-selectors']);
  app.location.hash = '#admin/security/artifact-scanning/waivers';
  app.context.applyHashRoute();
  app.location.hash = '#admin/repository/content-selectors';
  app.context.applyHashRoute();
  assert.deepEqual(app.views, ['content-selectors', 'security-scanning', 'content-selectors']);
  assert.deepEqual(app.history, []);
});

for (const grants of [[], ['nexus:security-scanning:update'], ['nexus:security-scanning-waivers:create']]) {
  test(`no read grant renders no authorized page: ${grants}`, async () => {
    const app = start('#admin/security/artifact-scanning', grants);
    await app.finish();
    assert.deepEqual(app.views, []);
    assert.ok(app.items.every(item => item.hidden));
    assert.equal(app.context.switchView('content-selectors'), false);
    assert.equal(app.elements.get('loading-view').textContent, 'No administration views are available.');
  });
}
