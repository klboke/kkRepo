const assert = require("node:assert/strict");
const { readFileSync } = require("node:fs");
const { resolve } = require("node:path");
const test = require("node:test");
const vm = require("node:vm");

function loadSwiftDetails() {
  const source = readFileSync(resolve(
    __dirname,
    "../../main/resources/META-INF/resources/browse/assets/browse.js"), "utf8");
  const repoUrlStart = source.indexOf("function repositoryBaseUrl");
  const repoUrlEnd = source.indexOf("function dockerRepositoryBaseUrl", repoUrlStart);
  const pathStart = source.indexOf("function pathSegments");
  const usageStart = source.indexOf("function hasDirectoryUsage", pathStart);
  const swiftStart = source.indexOf("function swiftUsageDetail");
  const swiftEnd = source.indexOf("async function usageDetailForEntry", swiftStart);
  assert.notEqual(repoUrlStart, -1);
  assert.notEqual(repoUrlEnd, -1);
  assert.notEqual(pathStart, -1);
  assert.notEqual(usageStart, -1);
  assert.notEqual(swiftStart, -1);
  assert.notEqual(swiftEnd, -1);
  const context = vm.createContext({
    currentRepository: () => ({ format: "swift", type: "hosted" }),
    state: { repo: "swift-hosted" },
    usageSnippet: (displayName, snippetText, description = "") => ({
      displayName,
      snippetText,
      description,
    }),
    URL,
    window: {
      location: {
        host: "localhost:18090",
        origin: "http://localhost:18090",
        protocol: "http:",
      },
    },
  });
  vm.runInContext(
    `${source.slice(repoUrlStart, repoUrlEnd)}\n`
      + `${source.slice(pathStart, usageStart)}\n`
      + `${source.slice(swiftStart, swiftEnd)}\n`
      + "globalThis.swiftUsageDetail = swiftUsageDetail;",
    context,
  );
  return context;
}

function detailVersion(detail) {
  return detail.summaryRows.find(([label]) => label === "Version")[1];
}

test("preserves a .zip build-metadata suffix on Swift release coordinates", () => {
  const context = loadSwiftDetails();
  const detail = context.swiftUsageDetail({
    leaf: false,
    path: "acme/library/1.0.0+linux.zip",
  });

  assert.equal(detailVersion(detail), "1.0.0+linux.zip");
  assert.match(detail.snippets[3].snippetText, /from: "1\.0\.0\+linux\.zip"/);
  assert.match(detail.snippets[4].snippetText, /acme\.library 1\.0\.0\+linux\.zip$/);
});

test("strips only the archive extension from Swift archive assets", () => {
  const context = loadSwiftDetails();
  const archive = context.swiftUsageDetail({
    leaf: true,
    path: "acme/library/1.0.0+linux.zip.zip",
  });
  const manifest = context.swiftUsageDetail({
    leaf: true,
    path: "acme/library/1.0.0+linux.zip/Package.swift",
  });

  assert.equal(detailVersion(archive), "1.0.0+linux.zip");
  assert.equal(detailVersion(manifest), "1.0.0+linux.zip");
});
