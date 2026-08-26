const assert = require("node:assert/strict");
const { resolve } = require("node:path");
const test = require("node:test");

const uiTheme = require(resolve(
  __dirname,
  "../../main/resources/META-INF/resources/login/assets/ui-theme.js",
));

function fixture(cachedSettings) {
  const stylesheetAttributes = new Map();
  const rootAttributes = new Map();
  const storageWrites = [];
  const stylesheet = {
    onerror: null,
    setAttribute(name, value) { stylesheetAttributes.set(name, value); },
  };
  const documentRef = {
    documentElement: {
      setAttribute(name, value) { rootAttributes.set(name, value); },
    },
    getElementById(id) {
      return id === "kkrepo-theme-stylesheet" ? stylesheet : null;
    },
  };
  const windowRef = {
    localStorage: {
      getItem(key) {
        assert.equal(key, "kkrepo.uiSettings");
        return JSON.stringify(cachedSettings || {});
      },
      setItem(key, value) {
        storageWrites.push([key, value]);
      },
    },
  };
  const binding = uiTheme.bind(documentRef, windowRef);
  return { binding, rootAttributes, stylesheet, stylesheetAttributes, storageWrites };
}

test("boots with the current kkRepo CSS as the default theme", () => {
  const view = fixture({});

  assert.equal(view.binding.currentTheme(), "default");
  assert.equal(view.rootAttributes.get("data-theme"), "default");
  assert.equal(
    view.stylesheetAttributes.get("href"),
    "/browse/assets/themes/default.css?v=20260824-ui-themes-3",
  );
});

test("loads every registered bundled theme template from cached UI settings", () => {
  const themes = ["indigo", "ocean", "sunset", "jfrog"];

  for (const theme of themes) {
    const view = fixture({
      defaultTheme: theme,
      supportedDefaultThemes: ["default", ...themes],
    });

    assert.equal(view.binding.currentTheme(), theme);
    assert.equal(
      view.stylesheetAttributes.get("href"),
      `/browse/assets/themes/${theme}.css?v=20260824-ui-themes-3`,
    );
  }
});

test("previews a selected theme without persisting browser settings", () => {
  const themes = ["default", "indigo", "ocean", "sunset", "jfrog"];
  const view = fixture({ defaultTheme: "default", supportedDefaultThemes: themes });

  assert.equal(view.binding.applyTheme("jfrog", themes), "jfrog");
  assert.equal(view.binding.currentTheme(), "jfrog");
  assert.equal(view.rootAttributes.get("data-theme"), "jfrog");
  assert.equal(
    view.stylesheetAttributes.get("href"),
    "/browse/assets/themes/jfrog.css?v=20260824-ui-themes-3",
  );
  assert.deepEqual(view.storageWrites, []);
});

test("rejects unregistered and path-like theme identifiers", () => {
  assert.equal(uiTheme.normalizeTheme("midnight", ["default"]), "default");
  assert.equal(uiTheme.normalizeTheme("../admin", ["default", "../admin"]), "default");
  assert.deepEqual(
    uiTheme.normalizeSupportedThemes(["default", "midnight", "midnight", "../admin"]),
    ["default", "midnight"],
  );
});

test("falls back to the default template when a selected stylesheet is missing", () => {
  const view = fixture({
    defaultTheme: "midnight",
    supportedDefaultThemes: ["default", "midnight"],
  });

  view.stylesheet.onerror();

  assert.equal(view.binding.currentTheme(), "default");
  assert.equal(
    view.stylesheetAttributes.get("href"),
    "/browse/assets/themes/default.css?v=20260824-ui-themes-3",
  );
});
