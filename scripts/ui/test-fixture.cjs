// Legacy DOM fixtures evaluate selected declarations with simulated page state and HTTP.
// Resolve them by declaration identity, independently of declaration and bundle ordering.
const { readFileSync, readdirSync } = require("node:fs");
const { join, resolve } = require("node:path");
const { createContext } = require("node:vm");

module.exports = function workspaceFixtures(app) {
  const root = resolve(__dirname, "../..");
  const mod = join(root, app + "-ui");
  const { parseAst } = require(join(mod, "target/ui-tools/node_modules/rollup/dist/parseAst.js"));
  const definitions = new Map();

  function visit(directory) {
    for (const entry of readdirSync(directory, { withFileTypes: true })) {
      const file = join(directory, entry.name);
      if (entry.isDirectory()) { visit(file); continue; }
      if (!entry.name.endsWith(".js")) continue;
      const source = readFileSync(file, "utf8");
      for (const statement of parseAst(source).body) {
        const node = statement.declaration || statement;
        let name = node.id?.name;
        if (node.type === "VariableDeclaration") name = node.declarations[0].id.name;
        if (name) {
          const candidates = definitions.get(name) || [];
          candidates.push({ file, code: source.slice(node.start, node.end) });
          definitions.set(name, candidates);
        }
      }
    }
  }
  visit(join(mod, "src/main/ui/js"));
  visit(join(root, "admin-ui/src/main/ui/shared"));

  function declarations(...names) {
    return names.map(name => {
      if (!definitions.has(name)) throw new Error("Unknown " + app + " fixture declaration: " + name);
      const matches = definitions.get(name);
      if (matches.length !== 1) throw new Error("Ambiguous fixture declaration " + name + " in " + matches.map(item => item.file).join(", "));
      return matches[0].code;
    }).join("\n") + "\n";
  }
  declarations.context = (globals = {}) => {
    for (const namespace of ["listState", "catalogState", "fieldsState", "accessState", "sessionState", "formatsState", "runState"]) {
      globals[namespace] = globals[namespace] || globals;
    }
    for (const [dependency, value] of Object.entries(globals.window || {})) {
      if (dependency.startsWith("Kk")) globals[dependency[2].toLowerCase() + dependency.slice(3)] = value;
    }
    return createContext(globals);
  };
  return declarations;
};
