const declarations = require("../../../../scripts/ui/test-fixture.cjs")("browse");
const assert = require("node:assert/strict");
const test = require("node:test");
const vm = require("node:vm");

const extractFunction = name => declarations(name);

function loadUploadHelpers(globals = {}) {
  const calls = [];
  const context = declarations.context({
    currentSession: null,
    hasUploadPermission: () => false,
    pushBrowseRoute: (hash) => calls.push(["pushBrowseRoute", hash]),
    viewHash: (view) => `#browse/${view}`,
    switchView: (view) => calls.push(["switchView", view]),
    renderUpload: () => calls.push(["renderUpload"]),
    ensureUploadableRepositories: () => calls.push(["ensureUploadableRepositories"]),
    showRepositoryList: (sync) => calls.push(["showRepositoryList", sync]),
    ...globals,
  });
  vm.runInContext(
    ["uploadEmptyStateMessage", "canShowUploadNav", "showUpload"].map(extractFunction).join("\n")
      + "\nglobalThis.uploadEmptyStateMessage = uploadEmptyStateMessage;"
      + "\nglobalThis.canShowUploadNav = canShowUploadNav;"
      + "\nglobalThis.showUpload = showUpload;",
    context,
  );
  return { context, calls };
}

test("stays on the Upload page when a signed-in user has no uploadable repository", () => {
  const { context, calls } = loadUploadHelpers({
    currentSession: { userId: "admin" },
    hasUploadPermission: () => true,
  });

  context.showUpload();

  assert.deepEqual(calls.map(([name]) => name), [
    "pushBrowseRoute",
    "switchView",
    "renderUpload",
    "ensureUploadableRepositories",
  ]);
  assert.deepEqual(calls[1], ["switchView", "upload"]);
  assert.ok(!calls.some(([name]) => name === "showRepositoryList"));
});

test("shows the Upload page without changing the hash when restoring a route", () => {
  const { context, calls } = loadUploadHelpers({
    currentSession: { userId: "admin" },
    hasUploadPermission: () => true,
  });

  context.showUpload(false);

  assert.ok(!calls.some(([name]) => name === "pushBrowseRoute"));
  assert.deepEqual(calls[0], ["switchView", "upload"]);
});

test("still redirects anonymous visitors away from the Upload page", () => {
  const { context, calls } = loadUploadHelpers({ currentSession: null });

  context.showUpload();

  assert.deepEqual(calls, [["showRepositoryList", true]]);
});

test("only offers the Upload navigation to signed-in users with upload permission", () => {
  assert.equal(loadUploadHelpers().context.canShowUploadNav(), false);
  assert.equal(loadUploadHelpers({
    currentSession: { userId: "admin" },
    hasUploadPermission: () => false,
  }).context.canShowUploadNav(), false);
  assert.equal(loadUploadHelpers({
    currentSession: { userId: "admin" },
    hasUploadPermission: () => true,
  }).context.canShowUploadNav(), true);
});

test("empty-state message does not recommend a specific permission grant", () => {
  const { context } = loadUploadHelpers();
  const message = context.uploadEmptyStateMessage();

  assert.match(message, /No repository is available for web upload/);
  assert.match(message, /permission to edit it/);
  assert.match(message, /docker push/);
  assert.doesNotMatch(message, /add access/i);
});

function loadWithRealPermissions(permissions) {
  const context = declarations.context({ currentSession: { userId: "u" }, currentPermissions: permissions });
  vm.runInContext(
    ["permissionPartMatches", "permissionMatches", "can", "hasUploadPermission",
      "hasRepositoryUploadPermission", "canShowUploadNav", "uploadEmptyStateMessage"]
      .map(extractFunction).join("\n")
      + "\nglobalThis.canShowUploadNav = canShowUploadNav;"
      + "\nglobalThis.uploadEmptyStateMessage = uploadEmptyStateMessage;",
    context,
  );
  return context;
}

test("add-only and create-only accounts get the Upload entry but no misleading grant advice", () => {
  // The backend lists uploadable repositories only for accounts that can EDIT them, so these
  // accounts reach the empty state even when a hosted Raw repository exists.
  for (const permissions of [["nexus:repository-view:raw:artifacts:add"], ["nexus:component:create"]]) {
    const context = loadWithRealPermissions(permissions);
    assert.equal(context.canShowUploadNav(), true);
    assert.doesNotMatch(context.uploadEmptyStateMessage(), /add access|create an online hosted/i);
  }
  assert.equal(loadWithRealPermissions(["nexus:repository-view:raw:artifacts:read"]).canShowUploadNav(), false);
});

test("renders the empty-state message instead of an empty upload form", () => {
  const renderUpload = extractFunction("renderUpload");

  assert.match(renderUpload, /uploadRepos\.length === 0/);
  assert.match(renderUpload, /uploadEmptyStateMessage\(\)/);
  assert.match(renderUpload, /No repositories available/);
});
