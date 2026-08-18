const assert = require("node:assert/strict");
const { readFileSync } = require("node:fs");
const { resolve } = require("node:path");
const test = require("node:test");
const vm = require("node:vm");

function loadFileIconName() {
  const source = readFileSync(resolve(
    __dirname,
    "../../main/resources/META-INF/resources/browse/assets/browse.js"), "utf8");
  const start = source.indexOf("const FILE_ICON_RULES");
  const end = source.indexOf("const ICON_FOLDER", start);
  assert.notEqual(start, -1, "FILE_ICON_RULES should exist");
  assert.notEqual(end, -1, "file icon mapping block should have a stable boundary");
  const context = vm.createContext({});
  vm.runInContext(
    `${source.slice(start, end)}\nglobalThis.fileIconName = fileIconName;`,
    context,
  );
  return context.fileIconName;
}

test("maps supported repository artifacts to precise file icons", () => {
  const fileIconName = loadFileIconName();
  const cases = [
    ["commons-codec-1.17.0.jar", "maven2", "file-java-archive"],
    ["demo-1.0.war", "maven2", "file-java-archive"],
    ["demo-1.0.module", "maven2", "file-json"],
    ["demo-1.0.pom", "maven2", "file-code"],
    ["demo-1.0.jar.sha256", "maven2", "file-check"],
    ["demo-1.0.jar.asc", "maven2", "file-key"],
    ["package-1.0.0.tgz", "npm", "file-archive"],
    ["package-1.0.0.whl", "pypi", "file-box"],
    ["package-1.0.0.tar.bz2", "pypi", "file-archive"],
    ["package-1.0.0.crate", "cargo", "file-box"],
    ["package-name", "cargo", "file-json"],
    ["v1.2.3.info", "go", "file-json"],
    ["v1.2.3.mod", "go", "file-text"],
    ["list", "go", "file-text"],
    ["chart-1.0.0.tgz.prov", "helm", "file-key"],
    ["package.1.0.0.nupkg", "nuget", "file-box"],
    ["package.nuspec", "nuget", "file-code"],
    ["package-1.0.0.gem", "rubygems", "file-box"],
    ["package-1.0.0.gemspec.rz", "rubygems", "file-archive"],
    ["package-1.0.0.rpm", "yum", "file-box"],
    ["kkrepo-alpine-demo-11-1.0.0-r0.apk", "alpine", "file-apk"],
    ["primary.xml.gz", "yum", "file-archive"],
    ["package-1.0.0.tar.gz", "pub", "file-archive"],
    ["pytorch_model.bin", "huggingface", "binary"],
    ["model.safetensors", "huggingface", "layers"],
    ["model-00001-of-00002.safetensors", "huggingface", "layers"],
    ["model.safetensors.index.json", "huggingface", "file-json"],
    ["0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef", "docker", "binary"],
    ["unknown.asset", "raw", "file"],
  ];
  for (const [name, format, expected] of cases) {
    assert.equal(fileIconName(name, format), expected, `${format}:${name}`);
  }
});

test("matches file extensions case-insensitively", () => {
  const fileIconName = loadFileIconName();
  assert.equal(fileIconName("DEMO.JAR", "maven2"), "file-java-archive");
  assert.equal(fileIconName("PACKAGE.NUPKG", "nuget"), "file-box");
  assert.equal(fileIconName("PACKAGE.APK", "alpine"), "file-apk");
  assert.equal(fileIconName("MODEL.SAFETENSORS", "huggingface"), "layers");
  assert.equal(fileIconName("PYTORCH_MODEL.BIN", "huggingface"), "binary");
  assert.equal(fileIconName("PACKAGE.APK", "raw"), "file");
});
