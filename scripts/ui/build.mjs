import { createHash } from "node:crypto";
import { spawnSync } from "node:child_process";
import { readdirSync, readFileSync, writeFileSync, existsSync, mkdirSync, copyFileSync } from "node:fs";
import { dirname, resolve, join } from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";

const tooling = dirname(fileURLToPath(import.meta.url));
const root = resolve(tooling, "../..");
const app = process.argv[2];
if (!["admin", "browse"].includes(app)) throw new Error("Expected admin or browse");
const moduleDir = join(root, app + "-ui");
const ui = join(moduleDir, "src/main/ui");
const out = join(moduleDir, "target/generated-ui/META-INF/resources", app);
const work = join(moduleDir, "target/ui-tools");
const read = path => readFileSync(path, "utf8");
const digest = data => createHash("sha256").update(data).digest("hex").slice(0, 16);

// Independent Maven reactors (and parallel builds of Admin and Browse) own their install.
mkdirSync(work, { recursive: true });
const lock = read(join(tooling, "package-lock.json"));
const marker = join(work, "installed.lock");
if (!existsSync(marker) || read(marker) !== digest(lock) || !existsSync(join(work, "node_modules/rollup"))) {
  for (const file of ["package.json", "package-lock.json"]) copyFileSync(join(tooling, file), join(work, file));
  const result = spawnSync("npm", ["ci", "--omit=dev", "--ignore-scripts", "--no-audit", "--no-fund"], {
    cwd: work, stdio: "inherit",
  });
  if (result.error) throw result.error;
  if (result.status !== 0) throw new Error("Unable to install the locked UI build dependencies");
  writeFileSync(marker, digest(lock));
}
const { rollup } = await import(pathToFileURL(join(work, "node_modules/rollup/dist/es/rollup.js")));
const bundle = await rollup({
  input: join(ui, "js", app + ".js"),
  plugins: [{
    name: "kkrepo-shared-assets",
    resolveId(id) {
      if (id.startsWith("#ui-shared/")) return join(root, "admin-ui/src/main/ui/shared", id.slice("#ui-shared/".length));
    },
  }],
  onwarn(warning, warn) {
    if (warning.code === "UNRESOLVED_IMPORT") throw new Error(warning.message);
    if (warning.code !== "CIRCULAR_DEPENDENCY") warn(warning);
  },
});
const { output: [entry] } = await bundle.generate({ format: "es" });
await bundle.close();
const css = read(join(ui, "styles.css")).replace(/@import "([^"]+)";\n/g, (_, file) =>
  read(file.startsWith("#ui-shared/") ? join(root, "admin-ui/src/main/ui/shared", file.slice("#ui-shared/".length)) : join(ui, file)));
mkdirSync(join(out, "assets"), { recursive: true });
for (const [ext, content] of [["css", css], ["js", entry.code]]) writeFileSync(join(out, "assets", app + "." + ext), content);
function publicFile(url) {
  const owner = url.startsWith("/browse/") ? "browse" : "admin";
  return join(root, owner + "-ui/src/main/resources/META-INF/resources", url);
}

// The theme selector and locale loader choose a file after the shell has loaded.
function directoryVersion(url) {
  const directory = publicFile(url);
  const entries = readdirSync(directory).sort().map(name => [name, digest(readFileSync(join(directory, name)))]);
  return digest(JSON.stringify(entries));
}

const themeVersion = directoryVersion("/browse/assets/themes");
const dictionaryVersion = directoryVersion("/login/assets/i18n");
function assetVersion(url) {
  if (url.startsWith("/browse/assets/themes/")) return themeVersion;
  if (url === `/${app}/assets/${app}.js`) return digest(entry.code);
  if (url === `/${app}/assets/${app}.css`) return digest(css);
  return digest(readFileSync(publicFile(url)));
}

const html = read(join(ui, "index.html"))
  .replace(/^ *<!-- include (views\/[\w-]+\.html) -->$/gm, (_, file) => read(join(ui, file)).trimEnd())
  .replaceAll("@themeVersion@", themeVersion)
  .replaceAll("@dictionaryVersion@", dictionaryVersion)
  .replace(/(src|href)="([^"]+)\?v=@assetVersion@"/g, (_, attr, url) => {
    const path = new URL(url, "https://kkrepo.invalid/" + app + "/").pathname;
    return `${attr}="${url}?v=${assetVersion(path)}"`;
  });
writeFileSync(join(out, "index.html"), html);
