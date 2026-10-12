const assert = require("node:assert/strict");
const { readFileSync } = require("node:fs");
const { resolve } = require("node:path");
const test = require("node:test");
const vm = require("node:vm");
const source = readFileSync(resolve(__dirname,
  "../../main/resources/META-INF/resources/browse/assets/browse.js"), "utf8");
function section(start, end) { return source.slice(source.indexOf(start), source.indexOf(end, source.indexOf(start))); }

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
  const context = vm.createContext({
    document: { createElement: () => dialog, body: { appendChild() {} } },
    state: { repo: "hosted", path: "release" },
    escapeHtml: value => String(value).replaceAll("<", "&lt;"), formatBytes: size => String(size),
    fetch: async (...args) => { requests.push(args); return response; },
    refreshTreeAfterDeletion: async () => {},
    deleteError: async () => "Directory contents changed",
  });
  vm.runInContext(section("function showDirectoryDeletionDialog", "async function refreshTreeAfterDeletion"), context);
  context.showDirectoryDeletionDialog("hosted", { path: "release" }, {
    path: "release", assetCount: 2, componentCount: 1, estimatedBytes: 10, metadataAssetCount: 0,
    selectionToken: "reviewed-token", items: [{ path: "release/<a>", action: "DELETE" }],
  }, new Element());
  return { context, controls, dialog, requests };
}

test("directory action is limited to hosted supported formats and administrators", () => {
  let repo = { type: "hosted", format: "raw" }; let authorized = true;
  const context = vm.createContext({ currentRepository: () => repo, canDeleteBrowseContent: () => authorized });
  vm.runInContext(section("function canDeleteBrowseDirectory", "function hasUploadPermission"), context);
  const entry = { path: "release", leaf: false };
  assert.equal(context.canDeleteBrowseDirectory(entry), true);
  for (const type of ["proxy", "group"]) { repo = { type, format: "raw" }; assert.equal(context.canDeleteBrowseDirectory(entry), false); }
  repo = { type: "hosted", format: "docker" }; assert.equal(context.canDeleteBrowseDirectory(entry), false);
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
