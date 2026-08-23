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
    },
  };
  const binding = uiTheme.bind(documentRef, windowRef);
  return { binding, rootAttributes, stylesheet, stylesheetAttributes };
}

test("boots with the current kkRepo CSS as the default theme", () => {
  const view = fixture({});

  assert.equal(view.binding.currentTheme(), "default");
  assert.equal(view.rootAttributes.get("data-theme"), "default");
  assert.equal(
    view.stylesheetAttributes.get("href"),
    "/browse/assets/themes/default.css?v=20260824-ui-themes-1",
  );
});

test("loads a registered theme template from cached UI settings", () => {
  const view = fixture({
    defaultTheme: "midnight",
    supportedDefaultThemes: ["default", "midnight"],
  });

  assert.equal(view.binding.currentTheme(), "midnight");
  assert.equal(
    view.stylesheetAttributes.get("href"),
    "/browse/assets/themes/midnight.css?v=20260824-ui-themes-1",
  );
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
    "/browse/assets/themes/default.css?v=20260824-ui-themes-1",
  );
});
