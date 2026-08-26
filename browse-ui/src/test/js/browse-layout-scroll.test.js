const assert = require("node:assert/strict");
const { readFileSync } = require("node:fs");
const { resolve } = require("node:path");
const test = require("node:test");

const browseRoot = resolve(
  __dirname,
  "../../main/resources/META-INF/resources/browse",
);

test("keeps the expanded navigation inside the sidebar scroll container", () => {
  const css = readFileSync(resolve(browseRoot, "assets/browse.css"), "utf8");
  const sidebarRule = css.match(/\.nx-sidebar\s*\{([^}]*)\}/)?.[1] || "";

  assert.match(sidebarRule, /min-height:\s*0/);
  assert.match(sidebarRule, /overflow-x:\s*hidden/);
  assert.match(sidebarRule, /overflow-y:\s*auto/);
});

test("cache-busts the tree scroll fix assets", () => {
  const html = readFileSync(resolve(browseRoot, "index.html"), "utf8");

  assert.match(html, /browse\.css\?v=20260826-tree-scroll-1/);
  assert.match(html, /browse\.js\?v=20260826-tree-scroll-1/);
});
