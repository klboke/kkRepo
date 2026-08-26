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

test("cache-busts the current Browse assets", () => {
  const html = readFileSync(resolve(browseRoot, "index.html"), "utf8");

  assert.match(html, /browse\.css\?v=20260826-nexus-a11y-1/);
  assert.match(html, /browse\.js\?v=20260826-search-menu-3/);
});

test("keeps the icon format picker below its trigger", () => {
  const html = readFileSync(resolve(browseRoot, "index.html"), "utf8");
  const css = readFileSync(resolve(browseRoot, "assets/browse.css"), "utf8");
  const comboboxRule = css.match(/\.search-format-combobox\s*\{([^}]*)\}/)?.[1] || "";
  const popoverRule = css.match(/\.search-format-popover\s*\{([^}]*)\}/)?.[1] || "";
  const filterRule = css.match(/\.search-form \.search-format-filter\s*\{([^}]*)\}/)?.[1] || "";

  assert.equal((html.match(/class="search-format-option"/g) || []).length, 22);
  assert.match(html, /aria-haspopup="listbox"/);
  assert.match(html, /placeholder="Filter formats"/);
  assert.ok(
    html.indexOf('id="component-custom-format-filter"')
      < html.indexOf('id="component-custom-format-options"'),
  );
  assert.match(html, /data-custom-search-format="maven2"[^>]*>.*format-logo-maven/);
  assert.match(html, /data-custom-search-format="raw"[^>]*>.*format-logo-raw/);
  assert.match(comboboxRule, /width:\s*240px/);
  assert.match(popoverRule, /position:\s*absolute/);
  assert.match(popoverRule, /top:\s*calc\(100% \+ 6px\)/);
  assert.doesNotMatch(popoverRule, /bottom:/);
  assert.match(filterRule, /width:\s*100%/);
});
