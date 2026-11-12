const assert = require("node:assert/strict");
const { readFileSync } = require("node:fs");
const { resolve } = require("node:path");
const test = require("node:test");
const vm = require("node:vm");

const source = readFileSync(resolve(__dirname,
  "../../main/resources/META-INF/resources/browse/assets/browse.js"), "utf8");

function extractFunction(name) {
  const start = source.indexOf(`function ${name}(`);
  assert.notEqual(start, -1, name);
  const end = source.indexOf("\n}\n", start);
  assert.notEqual(end, -1, name);
  const asyncStart = source.slice(start - 6, start) === "async " ? start - 6 : start;
  return source.slice(asyncStart, end + 3);
}

async function loadRepository(apt = { distribution: "focal", component: "universe" }) {
  const requests = [];
  const repository = {
    name: "ubuntu-focal-amd64", format: "APT", type: "HOSTED", online: true,
    url: "/r/ubuntu-focal-amd64/", apt,
  };
  const context = vm.createContext({
    repositoriesCache: [], state: { repo: repository.name, path: "" }, URL,
    window: { location: { origin: "https://repo.example" } },
    fetch: async (url) => {
      requests.push(url);
      assert.equal(url, "/internal/repositories");
      return { ok: true, json: async () => [repository] };
    },
    usageSnippet: (displayName, snippetText) => ({ displayName, snippetText }),
    detailPaneMount: () => ({ isConnected: true }),
    repositoryFormatIcon: () => "",
    canDeleteBrowseDirectory: () => false,
    canDeleteBrowseComponentDirectory: () => false,
  });
  context.renderDetailPane = (detail) => { context.rendered = detail; };
  vm.runInContext([
    "fetchRepositories", "normalizeRepository", "currentRepository", "repositoryBaseUrl",
    "aptUsageDetail", "usageDetailForEntry", "showComponentDetail",
  ].map(extractFunction).join("\n"), context);
  context.repositoriesCache = await context.fetchRepositories();
  return { context, requests };
}

function summary(detail) {
  return Object.fromEntries(detail.summaryRows);
}

function sourceList(detail) {
  return detail.snippets.find((snippet) => snippet.displayName === "sources.list.d").snippetText;
}

test("APT directory summaries retain API settings through repository normalization", async () => {
  const { context, requests } = await loadRepository();
  const parts = ["focal", "universe", "figlet", "2.2.5-3", "amd64"];
  for (let depth = 1; depth <= parts.length; depth++) {
    const path = parts.slice(0, depth).join("/");
    context.state.path = path;
    await context.showComponentDetail({ path, leaf: false });
    assert.equal(context.rendered.crumbText, path);
    assert.equal(summary(context.rendered).Distribution, "focal");
    assert.equal(summary(context.rendered).Component, "universe");
    assert.equal(sourceList(context.rendered),
      "deb [signed-by=/etc/apt/keyrings/ubuntu-focal-amd64.gpg] https://repo.example/r/ubuntu-focal-amd64 focal universe");
  }
  assert.deepEqual(requests, ["/internal/repositories"]);
});

test("APT asset metadata remains authoritative over repository defaults", async () => {
  const { context } = await loadRepository();
  const detail = await context.usageDetailForEntry({
    path: "testing/contrib/demo/1.0/arm64/demo.deb", leaf: true,
  }, { apt: {
    distribution: "testing", component: "contrib", package: "demo",
    version: "1.0", architecture: "arm64",
  } });
  assert.equal(summary(detail).Distribution, "testing");
  assert.equal(summary(detail).Component, "contrib");
  assert.equal(summary(detail).Package, "demo");
  assert.equal(summary(detail).Version, "1.0");
  assert.equal(summary(detail).Architecture, "arm64");
  assert.match(sourceList(detail), / testing contrib$/);
});

test("APT details use retained settings when asset metadata is absent", async () => {
  const { context } = await loadRepository();
  const detail = await context.usageDetailForEntry({ path: "pool/f/figlet/figlet.deb", leaf: true }, {});
  assert.equal(summary(detail).Distribution, "focal");
  assert.equal(summary(detail).Component, "universe");
  assert.match(sourceList(detail), / focal universe$/);
});

test("repositories without APT settings keep the existing fallback behavior", async () => {
  const { context } = await loadRepository(null);
  const detail = await context.usageDetailForEntry({ path: "stable/main/demo", leaf: false });
  assert.equal(summary(detail).Distribution, "stable");
  assert.equal(summary(detail).Component, "main");
  assert.match(sourceList(detail), / stable main$/);
});
