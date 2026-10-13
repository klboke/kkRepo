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
    const confirmationIndex = scripts.findIndex((src) => src.startsWith("/login/assets/delete-confirmation.js?"));
    const consumerIndex = scripts.findIndex((src) => src.includes(`/assets/${page}.js?`));
    assert.ok(csrfIndex >= 0 && csrfIndex < consumerIndex);
    assert.ok(menuIndex >= 0 && menuIndex < consumerIndex);
    assert.ok(confirmationIndex >= 0 && confirmationIndex < consumerIndex);
    const source = readFileSync(resolve(base, `assets/${page}.js`), "utf8");
    assert.ok(source.includes("window.nexusPlusAccountMenu"));
    assert.ok(!source.includes("function installCsrfFetch"));
    if (page === "browse") {
      assert.ok(csrfIndex < scripts.findIndex((src) => src.includes("login-modal.js?")));
    }
  }
});

function confirmationContext() {
  const elements = [];
  const navigation = new Map();
  const document = {
    createElement(tag) {
      const listeners = new Map();
      const element = {
        tag, children: [], attributes: {}, value: "", isConnected: false,
        appendChild(child) { this.children.push(child); child.isConnected = true; },
        setAttribute(name, value) { this.attributes[name] = value; },
        addEventListener(name, listener) { listeners.set(name, listener); },
        fire(name, values = {}) {
          const event = { preventDefault() { this.prevented = true; }, ...values };
          listeners.get(name)?.(event);
          return event;
        },
        showModal() { this.open = true; },
        close() { this.open = false; this.fire("close"); },
        remove() { this.isConnected = false; },
        focus() { document.activeElement = this; },
      };
      elements.push(element);
      return element;
    },
  };
  document.body = document.createElement("body");
  const trigger = document.createElement("button");
  document.body.appendChild(trigger);
  trigger.focus();
  const context = vm.createContext({ document, window: {
    addEventListener: (name, listener) => navigation.set(name, listener),
    removeEventListener: name => navigation.delete(name),
  } });
  vm.runInContext(readFileSync(resolve(assets, "delete-confirmation.js"), "utf8"), context);
  return { confirm: context.window.kkrepoConfirmDelete, document, trigger, elements, navigation,
    get: tag => elements.findLast(element => element.tag === tag),
    button: text => elements.findLast(element => element.tag === "button" && element.textContent === text),
  };
}

test("delete confirmation defaults to Cancel and Enter, Escape and navigation never accept", async () => {
  for (const cancelWith of ["click", "Enter", "Escape", "hashchange", "pagehide"]) {
    const h = confirmationContext();
    const result = h.confirm({ title: "Delete file?" });
    assert.equal(h.document.activeElement, h.button("Cancel"));
    if (cancelWith === "click") h.button("Cancel").fire("click");
    else if (cancelWith === "Enter") assert.equal(h.button("Cancel").fire("keydown", { key: "Enter" }).prevented, true);
    else if (cancelWith === "Escape") assert.equal(h.get("dialog").fire("cancel").prevented, true);
    else h.navigation.get(cancelWith)();
    assert.equal(await result, false);
    assert.equal(h.get("dialog").isConnected, false);
    assert.equal(h.document.activeElement, h.trigger);
    assert.equal(h.navigation.size, 0);
  }
});

test("typed confirmation requires an exact name before submitting", async () => {
  const h = confirmationContext();
  const result = h.confirm({ confirmationText: "my-repo" });
  const input = h.get("input");
  assert.equal(h.button("Delete").disabled, true);
  for (const wrong of ["", "MY-REPO", "my-repo "]) {
    input.value = wrong; input.fire("input"); h.get("form").fire("submit");
    assert.equal(h.button("Delete").disabled, true);
    assert.equal(h.get("dialog").open, true);
  }
  input.value = "my-repo"; input.fire("input");
  assert.equal(h.button("Delete").disabled, false);
  h.get("form").fire("submit");
  assert.equal(await result, true);
  assert.equal(h.navigation.size, 0);
});

test("duplicate prompts cancel and names remain literal text", async () => {
  const h = confirmationContext();
  const name = '<img src=x onerror="alert(1)">';
  const result = h.confirm({ details: [{ label: "Repository", value: name }], confirmLabel: "Run cleanup" });
  assert.equal(h.get("code").textContent, name);
  assert.equal(h.get("code").children.length, 0);
  assert.equal(h.get("code").attributes["data-i18n-skip"], "");
  assert.equal(await h.confirm(), false);
  assert.equal(h.elements.filter(element => element.tag === "dialog").length, 1);
  assert.ok(h.button("Run cleanup"));
  h.get("form").fire("submit");
  assert.equal(await result, true);
  const next = h.confirm();
  h.button("Cancel").fire("click");
  assert.equal(await next, false);
});
