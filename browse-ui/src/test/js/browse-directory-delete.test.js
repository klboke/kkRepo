const assert = require("node:assert/strict");
const { readFileSync } = require("node:fs");
const { resolve } = require("node:path");
const test = require("node:test");
const vm = require("node:vm");
const source = readFileSync(resolve(__dirname,
  "../../main/resources/META-INF/resources/browse/assets/browse.js"), "utf8");
function section(start, end) { return source.slice(source.indexOf(start), source.indexOf(end, source.indexOf(start))); }

test("file deletion waits for confirmation and cancels after the selection changes", async () => {
  for (const selectionChanged of [false, true]) {
    let answer;
    const requests = [];
    const state = { repo: "hosted", path: "release/a" };
    const context = vm.createContext({
      state, URLSearchParams, canDeleteBrowseDirectory: () => false,
      currentRepository: () => ({ format: "raw" }),
      window: { kkrepoConfirmDelete: () => new Promise(resolve => { answer = resolve; }) },
      fetch: async (...args) => { requests.push(args); return { ok: true }; },
      refreshTreeAfterDeletion: async () => {},
    });
    vm.runInContext(section("async function deleteBrowseEntry", "async function previewDirectoryDeletion"), context);
    const pending = context.deleteBrowseEntry({ path: "release/a", leaf: true }, { isConnected: true });
    assert.equal(requests.length, 0);
    if (selectionChanged) state.path = "release/b";
    answer(true);
    await pending;
    assert.equal(requests.length, selectionChanged ? 0 : 1);
    if (!selectionChanged) {
      assert.equal(requests[0][0], "/internal/browse/hosted?path=release%2Fa");
      assert.equal(requests[0][1].method, "DELETE");
    }
  }
});

class Element {
  constructor() { this.listeners = {}; this.disabled = false; this.value = ""; this.hidden = true; this.isConnected = true; }
  addEventListener(name, fn) { this.listeners[name] = fn; }
  async fire(name) { return this.listeners[name]?.({ preventDefault() {} }); }
  setAttribute() {}
  showModal() { this.open = true; }
  close() { this.open = false; this.listeners.close?.(); }
  remove() { this.removed = true; }
  focus() {}
}
function dialogContext(response) {
  const dialog = new Element();
  const controls = Object.fromEntries(["input", "form", "[data-delete-confirm]", "[data-delete-cancel]",
    "[data-delete-refresh]", ".directory-delete-error"].map(key => [key, new Element()]));
  controls["[data-delete-confirm]"].disabled = true;
  dialog.querySelector = selector => controls[selector];
  const requests = [];
  const removed = [];
  const location = {};
  const navigation = new Map();
  const context = vm.createContext({
    document: { createElement: () => dialog, body: { appendChild() {} } },
    state: { repo: "hosted", path: "release" },
    escapeHtml: value => String(value).replaceAll("<", "&lt;"), formatBytes: size => String(size),
    fetch: async (...args) => { requests.push(args); return response; },
    refreshTreeAfterDeletion: async () => {},
    deleteError: async () => "Directory contents changed",
    sessionStorage: { removeItem: key => removed.push(key) }, AUTH_SNAPSHOT_KEY: "auth",
    window: { location,
      addEventListener: (name, listener) => navigation.set(name, listener),
      removeEventListener: name => navigation.delete(name),
    },
  });
  vm.runInContext(section("function showDirectoryDeletionDialog", "async function refreshTreeAfterDeletion"), context);
  context.showDirectoryDeletionDialog("hosted", { path: "release" }, {
    path: "release", assetCount: 2, componentCount: 1, estimatedBytes: 10, metadataAssetCount: 0,
    selectionToken: "reviewed-token", items: [{ path: "release/<a>", action: "DELETE" }],
  }, new Element());
  return { context, controls, dialog, requests, removed, location, navigation };
}

test("navigation cancels a directory preview without deleting and releases listeners", () => {
  const { dialog, requests, navigation } = dialogContext({ ok: true });
  navigation.get("hashchange")();
  assert.equal(dialog.removed, true);
  assert.equal(requests.length, 0);
  assert.equal(navigation.size, 0);
});

test("directory action is limited to hosted supported formats and administrators", () => {
  let repo = { type: "hosted", format: "raw" }; let authorized = true;
  const context = vm.createContext({ currentRepository: () => repo, canDeleteBrowseContent: () => authorized });
  vm.runInContext(section("function canDeleteBrowseDirectory", "function hasUploadPermission"), context);
  const entry = { path: "release", leaf: false };
  assert.equal(context.canDeleteBrowseDirectory(entry), true);
  for (const type of ["proxy", "group"]) { repo = { type, format: "raw" }; assert.equal(context.canDeleteBrowseDirectory(entry), false); }
  for (const format of ["docker", "helm"]) {
    repo = { type: "hosted", format }; assert.equal(context.canDeleteBrowseDirectory(entry), false);
  }
  repo = { type: "hosted", format: "raw" }; authorized = false; assert.equal(context.canDeleteBrowseDirectory(entry), false);
  authorized = true; assert.equal(context.canDeleteBrowseDirectory({ leaf: true, path: "file" }), false);
  assert.equal(context.canDeleteBrowseDirectory({ leaf: false, path: "" }), false);
});

test("confirmation requires exact path and binds execution to the previewed repository", async () => {
  const { context, controls, requests, dialog } = dialogContext({ ok: true });
  const input = controls.input; const confirm = controls["[data-delete-confirm]"];
  assert.match(dialog.innerHTML, /release\/&lt;a>/);
  await controls.form.fire("submit"); assert.equal(requests.length, 0);
  input.value = "Release"; await input.fire("input"); assert.equal(confirm.disabled, true);
  input.value = "release"; await input.fire("input"); assert.equal(confirm.disabled, false);
  context.state.repo = "other";
  await controls.form.fire("submit");
  assert.equal(requests[0][0], "/internal/browse/hosted/directory-deletion");
  assert.deepEqual(JSON.parse(requests[0][1].body), { path: "release", confirmationPath: "release", selectionToken: "reviewed-token" });
  assert.equal(dialog.removed, true);
});

test("a rejected or ambiguous delete requires a new preview, never a blind retry", async () => {
  const { controls, requests } = dialogContext({ ok: false, status: 409 });
  controls.input.value = "release"; await controls.input.fire("input");
  await controls.form.fire("submit");
  assert.equal(controls["[data-delete-confirm]"].disabled, true);
  assert.equal(controls["[data-delete-refresh]"].hidden, false);
  await controls.input.fire("input"); await controls.form.fire("submit");
  assert.equal(requests.length, 1);
  assert.match(controls[".directory-delete-error"].textContent, /changed/);
});

test("cancel closes preview without making a mutation", async () => {
  const { controls, requests, dialog } = dialogContext({ ok: true });
  await controls["[data-delete-cancel]"].fire("click");
  assert.equal(dialog.removed, true); assert.equal(requests.length, 0);
});

test("deletion refresh preserves scroll and surviving expanded directories", async () => {
  const tree = { scrollTop: 850, scrollLeft: 20 };
  const root = { style: { minHeight: "" }, scrollHeight: 1200, isConnected: true, querySelector: () => null };
  const state = { repo: "hosted", path: "release/a", source: "" };
  const expanded = new Set(["hosted::release", "hosted::other", "hosted::release/a"]);
  const detail = {};
  const context = vm.createContext({
    state, expanded, treeCache: new Map(),
    document: { querySelector: () => tree, getElementById: () => root },
    loadAndRenderTreeLevel: async () => { assert.equal(root.style.minHeight, "1200px"); tree.scrollTop = 0; },
    settleExpandedTreeLoads: async () => {},
    pathSelectorValue: path => path, pathSegments: path => path.split("/"),
    window: { history: { replaceState() {} } }, repositoryBrowseHash: () => "#browse/repository/hosted",
    detailPaneMount: () => detail,
  });
  vm.runInContext(section("async function refreshTreeAfterDeletion", "async function settleExpandedTreeLoads"), context);
  await context.refreshTreeAfterDeletion("hosted", { path: "release/a" });
  assert.equal(tree.scrollTop, 850); assert.equal(tree.scrollLeft, 20);
  assert.equal(root.style.minHeight, "");
  assert.equal(expanded.has("hosted::release"), true); assert.equal(expanded.has("hosted::other"), true);
  assert.equal(expanded.has("hosted::release/a"), false);
});


test("ordinary directories without package coordinates still render their action panel", async () => {
  let rendered;
  const context = vm.createContext({
    state: { repo: "hosted", path: "releases" }, detailPaneMount: () => ({ isConnected: true }),
    usageDetailForEntry: async () => null, repositoryFormatIcon: () => "icon", canDeleteBrowseDirectory: () => true,
    renderDetailPane: detail => { rendered = detail; },
  });
  vm.runInContext(section("async function showComponentDetail", "async function showAssetDetail"), context);
  await context.showComponentDetail({ path: "releases", leaf: false });
  assert.equal(rendered.crumbText, "releases");
  assert.equal(rendered.deleteEntry.path, "releases");
  assert.equal(rendered.summaryRows[0][1], "hosted");
});


test("expired session closes confirmation, clears authentication and redirects", async () => {
  const { controls, dialog, removed, location } = dialogContext({ ok: false, status: 401 });
  controls.input.value = "release";
  await controls.form.fire("submit");
  assert.equal(dialog.removed, true);
  assert.deepEqual(removed, ["auth"]);
  assert.equal(location.href, "/browse/#browse/welcome");
  assert.equal(controls["[data-delete-refresh]"].hidden, true);
});

test("expired preview clears authentication without opening a dialog or reporting a delete error", async () => {
  const removed = []; const location = {}; const button = { disabled: false, textContent: "Delete" };
  const context = vm.createContext({
    canDeleteBrowseDirectory: () => true, state: { repo: "hosted", path: "release" },
    URLSearchParams, fetch: async () => ({ ok: false, status: 401 }),
    sessionStorage: { removeItem: key => removed.push(key) }, AUTH_SNAPSHOT_KEY: "auth",
    window: { location, alert: () => assert.fail("must redirect to reauthenticate") },
    showDirectoryDeletionDialog: () => assert.fail("expired session must not open preview"),
    deleteError: () => assert.fail("401 is not a deletion error"),
  });
  vm.runInContext(section("async function previewDirectoryDeletion", "function showDirectoryDeletionDialog"), context);
  await context.previewDirectoryDeletion({ path: "release" }, button);
  assert.deepEqual(removed, ["auth"]);
  assert.equal(location.href, "/browse/#browse/welcome");
  assert.equal(button.disabled, false);
  assert.equal(button.textContent, "Delete");
});


test("usage snippets for unsupported prefixes never grant a delete action", async () => {
  let repo; let rendered; let entry;
  const state = { repo: "repo", path: "" };
  const context = vm.createContext({
    state, currentRepository: () => repo, canDeleteBrowseContent: () => true,
    pathSegments: path => path.split("/").filter(Boolean), VERSION_DIR_RE: /^\d/,
    hasDirectoryUsage: () => false,
    detailPaneMount: () => ({ isConnected: true }),
    usageDetailForEntry: async () => ({ crumbText: "Has usage", snippets: [] }),
    repositoryFormatIcon: () => "icon", renderDetailPane: data => { rendered = data; },
    window: { kkrepoConfirmDelete: () => assert.fail("unsupported directory must not confirm deletion") },
    fetch: () => assert.fail("unsupported directory must not call the legacy DELETE endpoint"),
  });
  vm.runInContext(section("function canDeleteBrowseDirectory", "function hasUploadPermission"), context);
  vm.runInContext(section("async function showComponentDetail", "async function showAssetDetail"), context);
  vm.runInContext(section("async function deleteBrowseEntry", "async function previewDirectoryDeletion"), context);
  for (const format of ["huggingface", "conan", "raw", "terraform"]) {
    for (const type of ["hosted", "proxy", "group"]) {
      for (const path of ["", "123", "owner/model", "v1"]) {
        repo = { format, type }; entry = { path, leaf: false }; state.path = path;
        await context.showComponentDetail(entry);
        const bounded = format === "raw" && type === "hosted" && path !== "";
        assert.equal(rendered.deleteEntry, bounded ? entry : null, `${format}/${type}/${path}`);
        if (!bounded) await context.deleteBrowseEntry(entry, { isConnected: true });
      }
    }
  }
  for (const [format, path] of [["maven2", "org/example/demo/1.0"], ["nuget", "demo/1.0"],
      ["swift", "scope/demo/1.0"], ["terraform", "v1/modules/acme/demo/aws/1.0"],
      ["terraform", "v1/providers/acme/demo/1.0"]]) {
    repo = { format, type: "proxy" }; entry = { path, leaf: false }; state.path = path;
    await context.showComponentDetail(entry);
    assert.equal(rendered.deleteEntry, entry, `${format} component action retained`);
  }
});
