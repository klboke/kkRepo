const assert = require("node:assert/strict");
const { readFileSync } = require("node:fs");
const { resolve } = require("node:path");
const test = require("node:test");
const vm = require("node:vm");

function uploadSpec(format, multipleUpload, componentFields, assetFields) {
  return { format, multipleUpload, componentFields, assetFields };
}

function field(name, type, optional = false, description = "") {
  return { name, type, optional, description };
}

function loadUploadHelpers(documentRef = {}) {
  const source = readFileSync(resolve(
    __dirname,
    "../../main/resources/META-INF/resources/browse/assets/browse.js",
  ), "utf8");
  const selectedStart = source.indexOf("function selectedUploadSpec");
  const selectedEnd = source.indexOf("function renderUploadFields", selectedStart);
  const renderStart = source.indexOf("function uploadSpecFieldLabel");
  const renderEnd = source.indexOf("function updateTerraformUploadKind", renderStart);
  const pathsStart = source.indexOf("function computedUploadPaths");
  const pathsEnd = source.indexOf("function updateUploadPath", pathsStart);
  const formStart = source.indexOf("function buildUploadForm");
  const formEnd = source.indexOf("async function uploadError", formStart);
  for (const boundary of [
    selectedStart, selectedEnd, renderStart, renderEnd,
    pathsStart, pathsEnd, formStart, formEnd,
  ]) {
    assert.notEqual(boundary, -1, "browse upload helper boundary should exist");
  }

  const context = vm.createContext({
    CSS: { escape: (value) => value },
    document: documentRef,
    escapeHtml: (value) => String(value),
    selectedUploadRepository: () => documentRef.selectedRepository,
  });
  vm.runInContext(`
    let uploadAssetCount = 1;
    let uploadSpecsCache = new Map();
    ${source.slice(selectedStart, selectedEnd)}
    ${source.slice(renderStart, renderEnd)}
    ${source.slice(pathsStart, pathsEnd)}
    ${source.slice(formStart, formEnd)}
    globalThis.setUploadSpecs = (specs) => { uploadSpecsCache = new Map(specs); };
    globalThis.renderUploadSpecFields = renderUploadSpecFields;
    globalThis.computedUploadPaths = computedUploadPaths;
    globalThis.buildUploadForm = buildUploadForm;
  `, context);
  return context;
}

function rawSpec() {
  return uploadSpec(
    "raw",
    true,
    [field("directory", "STRING")],
    [field("filename", "STRING"), field("asset", "FILE")],
  );
}

function row(filename, fileName) {
  const file = new File(["fixture"], fileName, { type: "application/octet-stream" });
  return {
    querySelector(selector) {
      if (selector === '[data-upload-asset-field="asset"]') return { files: [file] };
      if (selector === '[data-upload-asset-field="filename"]') return { value: filename };
      if (selector === ".upload-spec-file") return { files: [file] };
      return null;
    },
  };
}

test("renders directory and multi-asset controls only when the upload spec declares them", () => {
  const mount = { innerHTML: "" };
  const documentRef = {
    getElementById(id) {
      if (id === "upload-fields") return mount;
      return null;
    },
    querySelectorAll() { return []; },
  };
  const context = loadUploadHelpers(documentRef);
  const raw = rawSpec();
  const npm = uploadSpec("npm", false, [], [field("asset", "FILE")]);
  context.setUploadSpecs([["raw", raw], ["npm", npm]]);

  context.renderUploadSpecFields({ format: "raw" });
  assert.match(mount.innerHTML, /data-upload-component-field="directory"/);
  assert.match(mount.innerHTML, /value="\/"/);
  assert.match(mount.innerHTML, /data-upload-asset-field="filename"/);
  assert.match(mount.innerHTML, /id="upload-spec-add-asset"/);

  context.renderUploadSpecFields({ format: "npm" });
  assert.doesNotMatch(mount.innerHTML, /data-upload-component-field="directory"/);
  assert.doesNotMatch(mount.innerHTML, /data-upload-asset-field="filename"/);
  assert.doesNotMatch(mount.innerHTML, /id="upload-spec-add-asset"/);
  assert.match(mount.innerHTML, /data-upload-asset-field="asset"/);
});

test("builds Raw multi-file keys and previews each destination path", () => {
  const rows = [row("one.zip", "local-one.zip"), row("two.zip", "local-two.zip")];
  const directory = { value: " /team/releases/ " };
  const documentRef = {
    selectedRepository: { format: "raw" },
    querySelector(selector) {
      if (selector === '[data-upload-component-field="directory"]') return directory;
      return null;
    },
    querySelectorAll(selector) {
      return selector === ".upload-spec-asset-row" ? rows : [];
    },
  };
  const context = loadUploadHelpers(documentRef);
  context.setUploadSpecs([["raw", rawSpec()]]);
  const form = new FormData();

  context.buildUploadForm({ format: "raw" }, form);

  assert.deepEqual(Array.from(form.keys()), [
    "raw.directory",
    "raw.asset1",
    "raw.asset1.filename",
    "raw.asset2",
    "raw.asset2.filename",
  ]);
  assert.equal(form.get("raw.directory"), "/team/releases/");
  assert.equal(form.get("raw.asset1").name, "local-one.zip");
  assert.equal(form.get("raw.asset2.filename"), "two.zip");
  assert.deepEqual(Array.from(context.computedUploadPaths()), [
    "/team/releases/one.zip",
    "/team/releases/two.zip",
  ]);
});

test("keeps a single-asset format on its unnumbered multipart key", () => {
  const rows = [row("", "package.tgz")];
  const documentRef = {
    querySelector() { return null; },
    querySelectorAll(selector) {
      return selector === ".upload-spec-asset-row" ? rows : [];
    },
  };
  const context = loadUploadHelpers(documentRef);
  const npm = uploadSpec("npm", false, [], [field("asset", "FILE")]);
  context.setUploadSpecs([["npm", npm]]);
  const form = new FormData();

  context.buildUploadForm({ format: "npm" }, form);

  assert.deepEqual(Array.from(form.keys()), ["npm.asset"]);
  assert.equal(form.get("npm.asset").name, "package.tgz");
});
