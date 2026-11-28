const assert = require("node:assert/strict");
const test = require("node:test");
const vm = require("node:vm");
const declarations = require("../../../../scripts/ui/test-fixture.cjs")("browse");

test("repository names remain literal breadcrumb text with an encoded HTML View link", () => {
  const repo = "reports <archive> & releases";
  const crumb = {};
  const drill = { querySelector: selector => selector === ".crumb-current" ? crumb : { addEventListener() {} } };
  const elements = {
    "browse-view": { querySelector: () => null },
    "repository-table": { parentElement: { parentElement: { style: {} } } },
    "browse-drill": drill,
  };
  const context = declarations.context({
    state: { repo }, document: { getElementById: id => elements[id] },
    renderBrowseHeading() {}, repoTopIcon: () => "", repositoryFormatIcon: () => "",
    navigateTree() {}, initSplitResizer() {}, ensureTreeLevelLoaded: async () => {}, selectInitialTreePath() {},
  });
  vm.runInContext(declarations("renderTree"), context);
  context.renderTree();
  assert.equal(crumb.textContent, repo);
  assert.ok(!drill.innerHTML.includes(repo));
  assert.ok(drill.innerHTML.includes(`/service/rest/repository/browse/${encodeURIComponent(repo)}/`));
});

test("browse errors display the original exception message as text", async () => {
  const message = "Expected <entries> & received an empty document";
  const error = {};
  const context = declarations.context({
    state: { repo: "hosted" }, document: { createElement: () => error },
    fetchChildren: async () => { throw new Error(message); },
  });
  vm.runInContext(declarations("loadAndRenderTreeLevel"), context);
  let child;
  await context.loadAndRenderTreeLevel("", { replaceChildren(element) { child = element; } }, 0);
  assert.equal(child, error);
  assert.equal(error.className, "tree-error");
  assert.equal(error.textContent, "Failed to load: " + message);
});
