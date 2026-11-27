// Evaluate an entire page module in an isolated DOM fixture. Imports are supplied
// by the caller so the fixture can control HTTP, permissions, and modal interactions.
const vm = require("node:vm");
const { readFileSync } = require("node:fs");
const { resolve, join } = require("node:path");

module.exports = function loadModule(app, name, globals) {
  const base = resolve(__dirname, "../..", app + "-ui");
  const { parseAst } = require(join(base, "target/ui-tools/node_modules/rollup/dist/parseAst.js"));
  const file = join(base, "src/main/ui/js", name + ".js");
  const source = readFileSync(file, "utf8");
  const body = [];
  const exports = [];
  for (const node of parseAst(source).body) {
    if (node.type === "ExportNamedDeclaration") {
      for (const item of node.specifiers) exports.push(item.exported.name + ": " + item.local.name);
    } else if (node.type !== "ImportDeclaration") body.push(source.slice(node.start, node.end));
  }
  const script = new vm.Script("(function () {\n" + body.join("\n") + "\nreturn {" + exports.join(",") + "};\n})()", {filename: file});
  return script.runInContext(vm.isContext(globals) ? globals : vm.createContext(globals));
};
