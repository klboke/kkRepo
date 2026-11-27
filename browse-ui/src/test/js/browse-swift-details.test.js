const declarations = require("../../../../scripts/ui/test-fixture.cjs")("browse");
const assert = require("node:assert/strict");
const test = require("node:test");
const vm = require("node:vm");

function loadSwiftDetails() {

  const context = declarations.context({
    repositoriesCache: [],
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
    `${declarations("repositoryBaseUrl")}\n`
      + `${declarations("pathSegments", "latestVersion")}\n`
      + `${declarations(
        "swiftUsageDetail", "ansibleGalaxyUsageDetail", "condaUsageDetail", "conanUsageDetail",
        "aptUsageDetail", "alpineUsageDetail", "rUsageDetail", "huggingFaceUsageDetail",
      )}\n`
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
  assert.equal(
    detail.snippets[1].snippetText,
    'swift package-registry login "http://localhost:18090/repository/swift-hosted/" --username <username>',
  );
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

test("uses the server-advertised repository prefix in client instructions", () => {
  const context = loadSwiftDetails();
  context.repositoriesCache = [{ name: "swift-hosted", url: "/r/swift-hosted/" }];
  const detail = context.swiftUsageDetail({ leaf: false, path: "acme/library/1.0.0" });
  assert.equal(detail.snippets[1].snippetText,
    'swift package-registry login "http://localhost:18090/r/swift-hosted/" --username <username>');
});
