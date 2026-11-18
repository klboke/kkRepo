const assert = require("node:assert/strict");
const test = require("node:test");
const { File } = require("node:buffer");
const { groupFiles, form } = require("../../main/resources/META-INF/resources/admin/assets/ohpm-import.js");

function file(name, content = "archive", directory = "packages") {
  const value = new File([content], name);
  Object.defineProperty(value, "webkitRelativePath", { value: `${directory}/${name}` });
  return value;
}

test("directory grouping preserves paths, pairs HSP and attaches version metadata", async () => {
  const har = file("library.har");
  const hsp = file("library.hsp");
  const metadata = file("library.metadata.json", '{"name":"@scope/library","dist-tags":{"latest":"1.0.0"}}');
  const [entry] = groupFiles([metadata, hsp, har, file("README.md")]);
  assert.deepEqual(entry, { sourceKey: "packages/library.har", asset: har, hsp, metadata });
  const request = await form(entry);
  assert.equal(request.get("asset").name, "library.har");
  assert.equal(request.get("hsp").name, "library.hsp");
  assert.equal(request.get("metadata").name, "library.metadata.json");
  assert.equal(request.get("packageName"), "@scope/library");
  assert.equal(request.get("sourceKey"), "packages/library.har");
});

test("complete HSP bundles submit one file and preserve relative directory identities", async () => {
  const entries = groupFiles([file("bundle.tgz", "bundle", "first"), file("bundle.tar.gz", "bundle", "second")]);
  assert.equal(entries.length, 2);
  const request = await form(entries[0]);
  assert.equal(request.get("hsp"), null);
  assert.equal(request.get("metadata"), null);
  assert.equal(request.get("asset").name, "bundle.tgz");
});

test("ambiguous or incomplete directory inputs fail before job execution", () => {
  assert.throws(() => groupFiles([file("library.hsp")]), /Missing interface HAR/);
  assert.throws(() => groupFiles([file("library.tgz"), file("library.hsp")]), /extra HSP/);
  assert.throws(() => groupFiles([file("library.har"), file("library.har")]), /Duplicate source path/);
  assert.throws(() => groupFiles([file("library.har"), file("other.metadata.json")]), /No archive matches/);
  assert.throws(() => groupFiles([file("README.md")]), /Select a directory/);
});

test("invalid metadata is reported before sending a multipart upload", async () => {
  const [invalid] = groupFiles([file("library.har"), file("library.metadata.json", "{}")]);
  await assert.rejects(form(invalid), /requires a package name/);
  const [malformed] = groupFiles([file("library.har"), file("library.metadata.json", "{")]);
  await assert.rejects(form(malformed), SyntaxError);
});
