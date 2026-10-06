const assert = require("node:assert/strict");
const { readFileSync } = require("node:fs");
const { resolve } = require("node:path");
const test = require("node:test");
const vm = require("node:vm");

const assets = resolve(__dirname, "../../main/resources/META-INF/resources/login/assets");
const csrf = readFileSync(resolve(assets, "csrf-fetch.js"), "utf8");
const menu = readFileSync(resolve(assets, "account-menu.js"), "utf8");

function csrfContext(cookie = "KKREPO_CSRF=first") {
  const calls = [];
  const context = vm.createContext({
    URL, Headers,
    document: { cookie },
    window: {
      location: { origin: "https://repo.example" },
      fetch: (...args) => { calls.push(args); return Promise.resolve("response"); },
    },
  });
  vm.runInContext(csrf, context);
  return { context, calls };
}

test("shared CSRF wrapper covers every mutating method and preserves caller headers", async () => {
  const { context, calls } = csrfContext();
  const headers = { Accept: "application/json" };
  for (const method of ["POST", "PUT", "PATCH", "DELETE", "MKCOL"]) {
    assert.equal(await context.window.fetch("/internal/example", { method, headers }), "response");
    const [, options] = calls.at(-1);
    assert.equal(options.headers.get("X-Nexus-Plus-CSRF-Token"), "first");
    assert.equal(options.headers.get("Accept"), "application/json");
  }
  assert.deepEqual(headers, { Accept: "application/json" });
});

test("CSRF is same-origin only, reads the current cookie and installs once", async () => {
  const { context, calls } = csrfContext();
  const installed = context.window.fetch;
  vm.runInContext(csrf, context);
  assert.equal(context.window.fetch, installed);
  context.document.cookie = "other=value; KKREPO_CSRF=refreshed";
  await context.window.fetch("/internal/example", { method: "POST" });
  assert.equal(calls.at(-1)[1].headers.get("X-Nexus-Plus-CSRF-Token"), "refreshed");
  for (const [url, options] of [
    ["https://upstream.example/resource", { method: "POST" }],
    ["/internal/example", { method: "GET" }],
    ["/internal/example", { method: "HEAD" }],
  ]) {
    await context.window.fetch(url, options);
    assert.equal(calls.at(-1)[1], options);
  }
  context.document.cookie = "";
  await context.window.fetch("/internal/example", { method: "POST" });
  assert.equal(calls.at(-1)[1].headers, undefined);
});

function menuContext() {
  const elements = new Map();
  for (const id of ["user-menu", "user-menu-trigger", "user-menu-popover"]) {
    const classes = new Set();
    const attrs = new Map();
    elements.set(id, {
      hidden: false, attrs,
      setAttribute: (name, value) => attrs.set(name, value),
      classList: {
        add: (name) => classes.add(name),
        remove: (name) => classes.delete(name),
        contains: (name) => classes.has(name),
      },
    });
  }
  let sequence = 0;
  const timers = new Map();
  const context = vm.createContext({
    window: {},
    document: { getElementById: (id) => elements.get(id) },
    setTimeout: (callback, delay) => {
      assert.equal(delay, 120);
      timers.set(++sequence, callback);
      return sequence;
    },
    clearTimeout: (id) => timers.delete(id),
  });
  vm.runInContext(menu, context);
  return { api: context.window.nexusPlusAccountMenu, elements, timers };
}

test("account menu maintains accessible state and cancels stale delayed closes", () => {
  const { api, elements, timers } = menuContext();
  const trigger = elements.get("user-menu-trigger");
  const popover = elements.get("user-menu-popover");
  api.open();
  assert.equal(trigger.attrs.get("aria-expanded"), "true");
  assert.equal(popover.attrs.get("aria-hidden"), "false");
  api.scheduleClose();
  assert.equal(timers.size, 1);
  api.open();
  assert.equal(timers.size, 0);
  api.scheduleClose();
  const close = [...timers.values()][0];
  timers.clear();
  close();
  assert.equal(trigger.attrs.get("aria-expanded"), "false");
  assert.equal(popover.attrs.get("aria-hidden"), "true");
  api.toggle();
  assert.equal(popover.classList.contains("is-open"), true);
  api.toggle();
  assert.equal(popover.classList.contains("is-open"), false);
  elements.get("user-menu").hidden = true;
  api.open();
  assert.equal(popover.classList.contains("is-open"), false);
  elements.clear();
  assert.doesNotThrow(() => { api.open(); api.close(); api.toggle(); });
});

test("both UI entrypoints load shared scripts before their consumers", () => {
  const root = resolve(__dirname, "../../../..");
  for (const page of ["admin", "browse"]) {
    const base = resolve(root, `${page}-ui/src/main/resources/META-INF/resources/${page}`);
    const html = readFileSync(resolve(base, "index.html"), "utf8");
    const scripts = [...html.matchAll(/<script src="([^"]+)"/g)].map((match) => match[1]);
    const csrfIndex = scripts.findIndex((src) => src.startsWith("/login/assets/csrf-fetch.js?"));
    const menuIndex = scripts.findIndex((src) => src.startsWith("/login/assets/account-menu.js?"));
    const consumerIndex = scripts.findIndex((src) => src.includes(`/assets/${page}.js?`));
    assert.ok(csrfIndex >= 0 && csrfIndex < consumerIndex);
    assert.ok(menuIndex >= 0 && menuIndex < consumerIndex);
    const source = readFileSync(resolve(base, `assets/${page}.js`), "utf8");
    assert.ok(source.includes("window.nexusPlusAccountMenu"));
    assert.ok(!source.includes("function installCsrfFetch"));
    if (page === "browse") {
      assert.ok(csrfIndex < scripts.findIndex((src) => src.includes("login-modal.js?")));
    }
  }
});
