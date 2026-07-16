const assert = require("node:assert/strict");
const { readFileSync } = require("node:fs");
const { resolve } = require("node:path");
const test = require("node:test");
const vm = require("node:vm");

function loadTerraformDetails() {
  const source = readFileSync(resolve(
    __dirname,
    "../../main/resources/META-INF/resources/browse/assets/browse.js"), "utf8");
  const repoUrlStart = source.indexOf("function repositoryBaseUrl");
  const repoUrlEnd = source.indexOf("function dockerRepositoryBaseUrl", repoUrlStart);
  const pathStart = source.indexOf("function pathSegments");
  const usageStart = source.indexOf("function hasDirectoryUsage", pathStart);
  const terraformStart = source.indexOf("function terraformUsageDetail", usageStart);
  const usageEnd = source.indexOf("function mavenUsageDetail", usageStart);
  const terraformEnd = source.indexOf("async function usageDetailForEntry", terraformStart);
  assert.notEqual(repoUrlStart, -1);
  assert.notEqual(repoUrlEnd, -1);
  assert.notEqual(pathStart, -1);
  assert.notEqual(usageStart, -1);
  assert.notEqual(terraformStart, -1);
  assert.notEqual(usageEnd, -1);
  assert.notEqual(terraformEnd, -1);
  const context = vm.createContext({
    URL,
    currentRepository: () => ({ format: "terraform" }),
    state: { repo: "terraform-hosted" },
    usageSnippet: (displayName, snippetText, description = "") => ({
      displayName,
      snippetText,
      description,
    }),
    window: {
      location: {
        host: "localhost:18090",
        origin: "http://localhost:18090",
      },
    },
  });
  vm.runInContext(
    `${source.slice(repoUrlStart, repoUrlEnd)}\n`
      + `${source.slice(pathStart, usageStart)}\n`
      + `${source.slice(usageStart, usageEnd)}\n`
      + `${source.slice(terraformStart, terraformEnd)}\n`
      + "globalThis.hasDirectoryUsage = hasDirectoryUsage;\n"
      + "globalThis.terraformUsageDetail = terraformUsageDetail;",
    context,
  );
  return context;
}

test("shows Terraform details for root and partial coordinate directories", () => {
  const context = loadTerraformDetails();
  for (const path of [
    "v1",
    "v1/providers",
    "v1/providers/acme",
    "v1/providers/acme/demo",
    "v1/providers/acme/demo/1.0.1",
    "v1/modules/acme/network/aws",
  ]) {
    assert.equal(
      context.hasDirectoryUsage({ leaf: false, path }),
      true,
      `directory should render details: ${path}`,
    );
    const detail = context.terraformUsageDetail({ path });
    assert.equal(detail.crumbText, path);
    assert.ok(detail.summaryRows.length >= 4);
  }
});

test("keeps protocol snippets on complete Terraform coordinates", () => {
  const context = loadTerraformDetails();
  const provider = context.terraformUsageDetail({
    path: "v1/providers/acme/demo/1.0.1",
  });
  const module = context.terraformUsageDetail({
    path: "v1/modules/acme/network/aws/2.0.0",
  });
  assert.match(provider.snippets[1].snippetText, /required_providers/);
  assert.match(provider.snippets[1].snippetText, /version = "1\.0\.1"/);
  assert.match(
    provider.snippets[0].snippetText,
    /http:\/\/localhost:18090\/repository\/terraform-hosted\/v1\/providers\//,
  );
  assert.match(module.snippets[1].snippetText, /module "network"/);
  assert.match(module.snippets[1].snippetText, /version = "2\.0\.0"/);
});

test("uses the rendered tree entry when restoring a direct asset URL", async () => {
  const source = readFileSync(resolve(
    __dirname,
    "../../main/resources/META-INF/resources/browse/assets/browse.js"), "utf8");
  const start = source.indexOf("async function selectInitialTreePath");
  const end = source.indexOf("async function revealTreePath", start);
  assert.notEqual(start, -1);
  assert.notEqual(end, -1);

  const path = "v1/providers/acme/demo/1.0.10/download/linux/amd64/"
    + "terraform-provider-demo_1.0.10_linux_amd64.zip";
  const entry = { path, name: path.split("/").at(-1), leaf: true };
  const row = { scrollIntoView() {} };
  const node = { querySelector: () => row };
  let displayed = null;
  const context = vm.createContext({
    VERSION_DIR_RE: /^\d/,
    document: { querySelector: () => node },
    findCachedTreeEntry: () => null,
    hasDirectoryUsage: () => false,
    pathSelectorValue: (value) => value,
    revealTreePath: async () => {},
    selectRow: () => {},
    showAssetDetail: async (value) => { displayed = value; },
    showComponentDetail: async () => {},
    state: { path },
    treeNodeEntries: new WeakMap([[node, entry]]),
  });
  vm.runInContext(
    `${source.slice(start, end)}\nglobalThis.selectInitialTreePath = selectInitialTreePath;`,
    context,
  );
  await context.selectInitialTreePath();
  assert.equal(displayed, entry);
});

test("renders folder details with the repository format icon", async () => {
  const source = readFileSync(resolve(
    __dirname,
    "../../main/resources/META-INF/resources/browse/assets/browse.js"), "utf8");
  const start = source.indexOf("async function showComponentDetail");
  const end = source.indexOf("async function showAssetDetail", start);
  assert.notEqual(start, -1);
  assert.notEqual(end, -1);

  const mount = { innerHTML: "" };
  let rendered = null;
  const entry = { path: "v1/providers/acme/demo", leaf: false };
  const context = vm.createContext({
    detailPaneMount: () => mount,
    renderDetailPane: (value) => { rendered = value; },
    repositoryFormatIcon: () => "<format-icon>",
    usageDetailForEntry: async () => ({
      crumbText: entry.path,
      summaryRows: [["Format", "terraform"]],
      snippets: [],
    }),
  });
  vm.runInContext(
    `${source.slice(start, end)}\nglobalThis.showComponentDetail = showComponentDetail;`,
    context,
  );
  await context.showComponentDetail(entry);
  assert.equal(rendered.crumbIcon, "<format-icon>");
  assert.equal(rendered.crumbText, entry.path);
});

test("toggles a detail folder closed on the second click", async () => {
  const source = readFileSync(resolve(
    __dirname,
    "../../main/resources/META-INF/resources/browse/assets/browse.js"), "utf8");
  const start = source.indexOf("async function activateTreeBranch");
  const end = source.indexOf("function buildTreeNode", start);
  assert.notEqual(start, -1);
  assert.notEqual(end, -1);

  let expanded = false;
  let detailRenders = 0;
  const entry = { name: "providers", path: "v1/providers", leaf: false };
  const context = vm.createContext({
    VERSION_DIR_RE: /^\d/,
    hasDirectoryUsage: () => true,
    showComponentDetail: async () => { detailRenders += 1; },
  });
  vm.runInContext(
    `${source.slice(start, end)}\nglobalThis.activateTreeBranch = activateTreeBranch;`,
    context,
  );
  const toggleExpand = async () => { expanded = !expanded; };
  await context.activateTreeBranch(entry, toggleExpand);
  assert.equal(expanded, true);
  await context.activateTreeBranch(entry, toggleExpand);
  assert.equal(expanded, false);
  assert.equal(detailRenders, 2);
});
