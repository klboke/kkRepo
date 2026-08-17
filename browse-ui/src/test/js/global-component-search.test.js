const assert = require("node:assert/strict");
const { readFileSync } = require("node:fs");
const { resolve } = require("node:path");
const test = require("node:test");
const vm = require("node:vm");

const globalSearch = require(resolve(
  __dirname,
  "../../main/resources/META-INF/resources/browse/assets/global-component-search.js",
));

function loadBrowseSearchHelpers() {
  const source = readFileSync(resolve(
    __dirname,
    "../../main/resources/META-INF/resources/browse/assets/browse.js",
  ), "utf8");
  const constantsStart = source.indexOf("const APP_HASH_PREFIX");
  const constantsEnd = source.indexOf("function installCsrfFetch", constantsStart);
  const routeStart = source.indexOf("function normalizeSearchFormat");
  const routeEnd = source.indexOf("function repositoryBrowseHash", routeStart);
  const parseStart = source.indexOf("function parseBrowseHash");
  const parseEnd = source.indexOf("function pushBrowseRoute", parseStart);
  const paramsStart = source.indexOf("function componentSearchParams");
  const paramsEnd = source.indexOf("async function fetchSearchComponents", paramsStart);
  for (const boundary of [constantsStart, constantsEnd, routeStart, routeEnd,
    parseStart, parseEnd, paramsStart, paramsEnd]) {
    assert.notEqual(boundary, -1, "browse search helper boundary should exist");
  }
  const context = vm.createContext({
    URLSearchParams,
    readPendingLoginReturnTo: () => null,
    window: { location: { hash: "" } },
  });
  vm.runInContext(`
    ${source.slice(constantsStart, constantsEnd)}
    ${source.slice(routeStart, routeEnd)}
    ${source.slice(parseStart, parseEnd)}
    ${source.slice(paramsStart, paramsEnd)}
    globalThis.searchHash = searchHash;
    globalThis.searchFormatLabel = searchFormatLabel;
    globalThis.parseBrowseHash = parseBrowseHash;
    globalThis.componentSearchParams = componentSearchParams;
  `, context);
  return context;
}

test("builds a custom search target and restores its keyword", () => {
  assert.equal(
    globalSearch.target("  grpcur  "),
    "/browse/#browse/search/custom?q=grpcur",
  );
  assert.equal(
    globalSearch.keywordFromHash("#browse/search/custom?q=grpcurl+linux"),
    "grpcurl linux",
  );
  assert.equal(globalSearch.target("   "), null);
});

test("binds the topbar form, focuses empty input, and navigates on submit", () => {
  let submit;
  let assigned = null;
  let focused = false;
  const input = {
    value: "",
    focus() { focused = true; },
  };
  const form = {
    querySelector() { return input; },
    addEventListener(type, listener) {
      if (type === "submit") submit = listener;
    },
  };
  const documentRef = {
    querySelector(selector) {
      return selector === "[data-global-component-search]" ? form : input;
    },
  };
  const locationRef = {
    hash: "#browse/search/custom?q=existing",
    assign(value) { assigned = value; },
  };

  globalSearch.bind(documentRef, locationRef);
  assert.equal(input.value, "existing");

  input.value = " ";
  submit({ preventDefault() {} });
  assert.equal(focused, true);
  assert.equal(assigned, null);

  input.value = " grpcurl ";
  submit({ preventDefault() {} });
  assert.equal(assigned, "/browse/#browse/search/custom?q=grpcurl");
});

test("custom route omits format while format-specific search keeps it", () => {
  const helpers = loadBrowseSearchHelpers();

  assert.equal(
    helpers.searchHash("custom", " grpcur "),
    "#browse/search/custom?q=grpcur",
  );
  assert.equal(
    helpers.componentSearchParams("custom", "grpcur").toString(),
    "q=grpcur&limit=20",
  );
  assert.equal(
    helpers.componentSearchParams("maven2", "junit").toString(),
    "q=junit&format=maven2&limit=20",
  );

  helpers.window.location.hash = "#browse/search/custom?q=grpcurl+linux";
  const route = helpers.parseBrowseHash();
  assert.equal(route.view, "search");
  assert.equal(route.searchFormat, "custom");
  assert.equal(route.keyword, "grpcurl linux");
});

test("uses the selected repository format in the search page title", () => {
  const helpers = loadBrowseSearchHelpers();

  assert.equal(helpers.searchFormatLabel("custom"), "All formats");
  assert.equal(helpers.searchFormatLabel("maven2"), "Maven");
  assert.equal(helpers.searchFormatLabel("ansiblegalaxy"), "Ansible Galaxy");
  assert.equal(helpers.searchFormatLabel("alpine"), "Alpine / APK");
});
