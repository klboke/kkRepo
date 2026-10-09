const assert = require("node:assert/strict");
const { readFileSync } = require("node:fs");
const { resolve } = require("node:path");
const test = require("node:test");
const vm = require("node:vm");

const resources = resolve(__dirname, "../../main/resources/META-INF/resources/browse");
const source = readFileSync(resolve(resources, "assets/browse.js"), "utf8");

function extractFunction(name) {
  const start = source.indexOf(`function ${name}(`);
  assert.notEqual(start, -1, name);
  const end = source.indexOf("\n}\n", start);
  return source.slice(source.slice(start - 6, start) === "async " ? start - 6 : start, end + 3);
}

function deferred() {
  let resolve, reject;
  const promise = new Promise((yes, no) => { resolve = yes; reject = no; });
  return { promise, resolve, reject };
}

function start(hash) {
  const views = [];
  const history = [];
  const trees = [];
  const searches = [];
  const contextResponse = deferred();
  const repositoriesResponse = deferred();
  const uploadSpecsResponse = deferred();
  const location = new URL(`https://example.test/browse/${hash}`);
  const navigate = (kind, target) => {
    history.push([kind, target]);
    location.href = new URL(target, location).href;
  };
  const context = vm.createContext({
    URLSearchParams,
    APP_HASH_PREFIX: "browse", BROWSE_HASH: "browse/browse",
    DEFAULT_SEARCH_FORMAT: "maven2", CUSTOM_SEARCH_FORMAT: "custom", ALL_SEARCH_FORMAT: "all",
    normalizeSearchFormat: (format) => format,
    normalizeCustomSearchFormat: (format) => format,
    initialDataLoaded: false,
    discoveryVersion: 0,
    repositoriesCache: [], uploadSpecsCache: new Map(), currentSession: null,
    currentApiKeysLoaded: true,
    window: { location, history: {
      pushState: (_state, _title, target) => navigate("push", target),
      replaceState: (_state, _title, target) => navigate("replace", target),
    } },
    document: { getElementById: () => ({ innerHTML: "" }) },
    hydrateAuthSnapshot: () => {},
    fetchUiContext: () => contextResponse.promise,
    fetchRepositories: () => repositoriesResponse.promise,
    fetchUploadSpecs: () => uploadSpecsResponse.promise,
    applyUiContext: (value) => { context.currentSession = value.session; },
    switchView: (view) => views.push(view),
    showRepositoryList: (sync) => {
      if (sync) context.pushBrowseRoute("#browse/browse");
      views.push("browse");
    },
    showRepositoryTree: (...args) => { trees.push(args); views.push("browse"); },
    showSearch: (...args) => { searches.push(args); views.push("search"); },
    viewHash: (view) => `#browse/${view}`,
    renderUpload: () => {}, ensureUploadableRepositories: () => {}, renderMyToken: () => {},
    openPendingLoginIfRequested: () => {},
  });
  vm.runInContext([
    "parseBrowseHash", "pushBrowseRoute", "canonicalizeBrowseRoute", "repositoryExists",
    "showWelcome", "showUpload", "showMyToken", "applyHashRoute", "bootstrap",
    "safeLocalReturnTo", "currentReturnTo", "handleLoginSuccess",
  ].map(extractFunction).join("\n"), context);
  const completion = context.bootstrap();
  return { context, views, history, trees, searches, location, completion,
    contextResponse, repositoriesResponse, uploadSpecsResponse,
    finish(session = { userId: "reader" }, repositories = []) {
      contextResponse.resolve({ session });
      repositoriesResponse.resolve(repositories);
      uploadSpecsResponse.resolve(new Map());
      return completion;
    } };
}

test("the HTML first paint does not select Welcome before routing", () => {
  const html = readFileSync(resolve(resources, "index.html"), "utf8");
  assert.match(html, /class="view is-active" id="loading-view" role="status"/);
  assert.match(html, /class="view" id="welcome-view"/);
  assert.doesNotMatch(html, /class="side-item is-active" data-view="welcome"/);
});

for (const route of ["#browse", "#browse/browse", "#browse/upload", "#browse/my-token",
  "#browse/search/npm?q=hello"]) {
  test(`opens ${route} without a Welcome view or history detour`, async () => {
    const app = start(route);
    assert.deepEqual(app.views, ["loading"]);
    assert.equal(app.location.hash, route);
    assert.deepEqual(app.history, []);
    // A hash/popstate event during discovery must not apply anonymous-session fallbacks.
    app.context.applyHashRoute();
    assert.equal(app.location.hash, route);
    await app.finish();
    assert.ok(!app.views.includes("welcome"));
    assert.ok(!app.views.slice(2).includes("loading"));
    assert.equal(app.location.hash, route);
    assert.ok(app.history.every(([kind]) => kind === "replace"));
  });
}

test("repository path and source survive a slow repository response", async () => {
  const hash = "#browse/browse:group%20repo?path=%40scope%2Fpackage&source=upstream";
  const app = start(hash);
  app.contextResponse.resolve({ session: { userId: "reader" } });
  app.uploadSpecsResponse.resolve(new Map());
  await new Promise(setImmediate);
  assert.deepEqual(app.views, ["loading"]);
  assert.deepEqual(app.trees, []);
  app.repositoriesResponse.resolve([{ name: "group repo" }]);
  await app.completion;
  assert.deepEqual(app.trees, [["group repo", false, "@scope/package", "upstream"]]);
  assert.equal(app.location.hash, hash);
});

test("uses the latest route after navigation during startup", async () => {
  const app = start("#browse/browse");
  app.location.hash = "#browse/search/npm?q=new";
  app.context.applyHashRoute();
  await app.finish();
  assert.equal(app.views.at(-1), "search");
  assert.deepEqual(app.searches, [["npm", false, "new", undefined]]);
  assert.ok(!app.views.includes("welcome"));
});

for (const route of ["", "#browse/welcome", "#unknown"]) {
  test(`default/Welcome route ${route} does not wait for startup data`, async () => {
    const app = start(route);
    assert.deepEqual(app.views, ["welcome"]);
    await app.finish();
    assert.equal(app.views.at(-1), "welcome");
    assert.equal(app.location.hash, route);
  });
}

for (const [route, fallback] of [["upload", "browse"], ["my-token", "welcome"]]) {
  test(`anonymous ${route} fallback waits for the session response`, async () => {
    const app = start(`#browse/${route}`);
    assert.deepEqual(app.views, ["loading"]);
    await app.finish(null);
    assert.equal(app.views.at(-1), fallback);
    assert.equal(app.location.hash, `#browse/${fallback}`);
  });
}

test("failed discovery releases the loading state", async () => {
  const app = start("#browse/browse");
  app.contextResponse.reject(new Error("offline"));
  app.repositoriesResponse.reject(new Error("offline"));
  app.uploadSpecsResponse.resolve(new Map());
  await app.completion;
  assert.equal(app.views.at(-1), "browse");
  assert.equal(app.location.hash, "#browse/browse");
});

for (const staleOutcome of ["success", "failure"]) {
  test(`login refresh unblocks a deep link and ignores a late bootstrap ${staleOutcome}`, async () => {
    const hash = "#browse/browse:private?path=folder&source=hosted";
    const app = start(hash);
    const freshSession = { userId: "signed-in" };
    const freshRepositories = [{ name: "private" }];
    const freshSpecs = new Map([["raw", { format: "raw" }]]);
    app.context.fetchUiContext = async () => ({ session: freshSession });
    app.context.fetchRepositories = async () => freshRepositories;
    app.context.fetchUploadSpecs = async () => freshSpecs;

    await app.context.handleLoginSuccess({ detail: {} });
    // The original bootstrap requests are deliberately still unresolved.
    assert.equal(app.views.at(-1), "browse");
    assert.deepEqual(app.trees, [["private", false, "folder", "hosted"]]);
    const renderedViews = [...app.views];

    app.contextResponse.resolve({ session: null });
    if (staleOutcome === "success") app.repositoriesResponse.resolve([]);
    else app.repositoriesResponse.reject(new Error("old request failed"));
    app.uploadSpecsResponse.resolve(new Map());
    await app.completion;
    assert.equal(app.context.currentSession, freshSession);
    assert.equal(app.context.repositoriesCache, freshRepositories);
    assert.equal(app.context.uploadSpecsCache, freshSpecs);
    assert.deepEqual(app.views, renderedViews);
    assert.equal(app.location.hash, hash);
  });
}

test("bootstrap completion during login refresh cannot apply the anonymous fallback", async () => {
  const app = start("#browse/my-token");
  const loginContext = deferred();
  app.context.fetchUiContext = () => loginContext.promise;
  app.context.fetchRepositories = async () => [];
  app.context.fetchUploadSpecs = async () => new Map();
  const login = app.context.handleLoginSuccess({ detail: {} });
  await app.finish(null);
  assert.equal(app.location.hash, "#browse/my-token");
  assert.ok(!app.views.includes("welcome"));
  loginContext.resolve({ session: { userId: "signed-in" } });
  await login;
  assert.equal(app.views.at(-1), "my-token");
  assert.equal(app.location.hash, "#browse/my-token");
});

for (const staleOutcome of ["success", "failure"]) {
  test(`a superseded login ${staleOutcome} cannot replace newer data or navigate away`, async () => {
    const app = start("#browse/my-token");
    await app.finish();
    const olderContext = deferred();
    app.context.fetchUiContext = () => olderContext.promise;
    app.context.fetchRepositories = async () => [];
    app.context.fetchUploadSpecs = async () => new Map();
    const olderLogin = app.context.handleLoginSuccess({ detail: { returnTo: "/old-target" } });
    const latestSession = { userId: "latest-login" };
    app.context.fetchUiContext = async () => ({ session: latestSession });
    await app.context.handleLoginSuccess({ detail: {} });
    const renderedViews = [...app.views];
    if (staleOutcome === "success") olderContext.resolve({ session: { userId: "older-login" } });
    else olderContext.reject(new Error("old login request failed"));
    await olderLogin;
    assert.equal(app.context.currentSession, latestSession);
    assert.equal(app.location.hash, "#browse/my-token");
    assert.deepEqual(app.views, renderedViews);
  });
}
