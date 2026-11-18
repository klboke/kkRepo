const assert = require("node:assert/strict");
const { readFileSync } = require("node:fs");
const { resolve } = require("node:path");
const test = require("node:test");
const vm = require("node:vm");
const source = readFileSync(resolve(__dirname,
  "../../main/resources/META-INF/resources/browse/assets/browse.js"), "utf8");

function section(start, end) {
  return source.slice(source.indexOf(start), source.indexOf(end, source.indexOf(start)));
}

test("OHPM uploads send both files and the chosen distribution tag", () => {
  const har = { name: "example.har" };
  const hsp = { name: "example.hsp" };
  const controls = {
    "upload-file": { files: [har] },
    "upload-ohpm-hsp": { files: [hsp] },
  };
  const context = vm.createContext({
    document: { getElementById: id => controls[id] },
    uploadFieldValue: () => "beta",
  });
  vm.runInContext(section("function buildUploadForm", "async function uploadError"), context);
  const fields = [];
  context.buildUploadForm({ format: "ohpm" }, { append: (...values) => fields.push(values) });
  assert.deepEqual(fields, [
    ["ohpm.asset", har, "example.har"],
    ["ohpm.hsp", hsp, "example.hsp"],
    ["ohpm.tag", "beta"],
  ]);
  controls["upload-file"].files = [{ name: "bundle.tgz" }];
  assert.throws(() => context.buildUploadForm({ format: "ohpm" }, { append() {} }), /one complete HSP/);
  controls["upload-ohpm-hsp"].files = [];
  const bundleFields = [];
  context.buildUploadForm({ format: "ohpm" }, { append: (...values) => bundleFields.push(values) });
  assert.deepEqual(bundleFields, [
    ["ohpm.asset", controls["upload-file"].files[0], "bundle.tgz"],
    ["ohpm.tag", "beta"],
  ]);
  controls["upload-file"].files = [];
  assert.throws(() => context.buildUploadForm({ format: "ohpm" }, { append() {} }), /HAR archive/);
});

test("OHPM package and version directories can be unpublished, scope directories cannot", () => {
  const context = vm.createContext({
    currentRepository: () => ({ format: "ohpm" }),
    canDeleteBrowseContent: () => true,
    pathSegments: path => path.split("/"),
    VERSION_DIR_RE: /^\d/,
  });
  vm.runInContext(section("function canDeleteBrowseComponentDirectory", "function hasUploadPermission"), context);
  for (const path of ["example", "example/1.0.0", "@scope/example", "@scope/example/1.0.0"]) {
    assert.equal(context.canDeleteBrowseComponentDirectory({ path, leaf: false }), true, path);
  }
  for (const path of ["@scope", "@scope/example/other", "example/1.0.0/extra"]) {
    assert.equal(context.canDeleteBrowseComponentDirectory({ path, leaf: false }), false, path);
  }
});

test("OHPM deletion confirmation describes the whole release and paired HSP", async () => {
  let confirmation;
  const context = vm.createContext({
    state: { repo: "ohpm" },
    canDeleteBrowseDirectory: () => false,
    currentRepository: () => ({ format: "ohpm" }),
    window: { kkrepoConfirmDelete: async value => { confirmation = value; return false; } },
  });
  vm.runInContext(section("async function deleteBrowseEntry", "async function previewDirectoryDeletion"), context);
  await context.deleteBrowseEntry({ path: "example/1.0.0/example-1.0.0.har", leaf: true }, {});
  assert.match(confirmation.description, /unpublishes/);
  assert.match(confirmation.description, /paired HSP/);
});
