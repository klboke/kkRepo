const assert = require("node:assert/strict");
const { resolve } = require("node:path");
const test = require("node:test");

const productUpdate = require(resolve(
  __dirname,
  "../../main/resources/META-INF/resources/login/assets/product-update.js",
));

function fixture(responses) {
  const attributes = new Map();
  const tooltip = { textContent: "" };
  const link = {
    hidden: true,
    querySelector(selector) {
      return selector === "[data-product-update-tooltip]" ? tooltip : null;
    },
    removeAttribute(name) { attributes.delete(name); },
    setAttribute(name, value) { attributes.set(name, value); },
  };
  const version = { dataset: { currentVersion: "0.8.0" } };
  const documentRef = {
    querySelector(selector) {
      if (selector === "[data-product-update]") return link;
      if (selector === "[data-current-version]") return version;
      return null;
    },
  };
  let requestIndex = 0;
  const requests = [];
  let intervalDelay = null;
  let intervalCallback = null;
  const listeners = new Map();
  const windowRef = {
    addEventListener(type, listener) { listeners.set(type, listener); },
    async fetch(url, options) {
      requests.push({ url, options });
      const response = responses[Math.min(requestIndex, responses.length - 1)];
      requestIndex += 1;
      if (response instanceof Error) throw response;
      return {
        ok: response.ok !== false,
        async json() { return response.payload; },
      };
    },
    setInterval(callback, delay) {
      intervalCallback = callback;
      intervalDelay = delay;
      return 17;
    },
  };
  return {
    attributes,
    documentRef,
    interval: () => ({ callback: intervalCallback, delay: intervalDelay }),
    link,
    listeners,
    requests,
    tooltip,
    windowRef,
  };
}

test("checks immediately, polls every 30 seconds, and links the exact latest release", async () => {
  const releaseUrl = "https://github.com/klboke/kkRepo/releases/tag/v0.10.0";
  const view = fixture([{
    payload: {
      status: "ok",
      latestVersion: "0.10.0",
      releaseUrl,
      updateAvailable: true,
    },
  }]);

  const binding = productUpdate.bind(view.documentRef, view.windowRef);
  await binding.initialCheck;

  assert.equal(view.requests.length, 1);
  assert.equal(view.requests[0].url, "/internal/version-update?currentVersion=0.8.0");
  assert.equal(view.requests[0].options.cache, "no-store");
  assert.equal(view.interval().delay, 30_000);
  assert.equal(binding.intervalId, 17);
  assert.equal(view.link.hidden, false);
  assert.equal(view.attributes.get("href"), releaseUrl);
  assert.equal(
    view.tooltip.textContent,
    "New version v0.10.0 available. View release.",
  );
});

test("keeps the last successful update when a later request fails", async () => {
  const releaseUrl = "https://github.com/klboke/kkRepo/releases/tag/v0.9.0";
  const view = fixture([
    {
      payload: {
        status: "ok",
        latestVersion: "0.9.0",
        releaseUrl,
        updateAvailable: true,
      },
    },
    new Error("network unavailable"),
  ]);
  const binding = productUpdate.bind(view.documentRef, view.windowRef);
  await binding.initialCheck;

  assert.equal(await binding.check(), false);
  assert.equal(view.link.hidden, false);
  assert.equal(view.attributes.get("href"), releaseUrl);
});

test("keeps the last successful update when kkRepo cannot reach GitHub", async () => {
  const releaseUrl = "https://github.com/klboke/kkRepo/releases/tag/v0.9.0";
  const view = fixture([
    {
      payload: {
        status: "ok",
        latestVersion: "0.9.0",
        releaseUrl,
        updateAvailable: true,
      },
    },
    { payload: { status: "unavailable" } },
  ]);
  const binding = productUpdate.bind(view.documentRef, view.windowRef);
  await binding.initialCheck;

  assert.equal(await binding.check(), false);
  assert.equal(view.link.hidden, false);
  assert.equal(view.attributes.get("href"), releaseUrl);
});

test("hides a previously visible update after the current version catches up", async () => {
  const view = fixture([
    {
      payload: {
        status: "ok",
        latestVersion: "0.9.0",
        releaseUrl: "https://github.com/klboke/kkRepo/releases/tag/v0.9.0",
        updateAvailable: true,
      },
    },
    {
      payload: {
        status: "ok",
        latestVersion: "0.8.0",
        releaseUrl: "https://github.com/klboke/kkRepo/releases/tag/v0.8.0",
        updateAvailable: false,
      },
    },
  ]);
  const binding = productUpdate.bind(view.documentRef, view.windowRef);
  await binding.initialCheck;

  assert.equal(await binding.check(), true);
  assert.equal(view.link.hidden, true);
  assert.equal(view.attributes.has("href"), false);
});

test("rejects non-GitHub release links", () => {
  assert.equal(productUpdate.releaseDetails({
    status: "ok",
    latestVersion: "9.9.9",
    releaseUrl: "https://example.com/fake-release",
    updateAvailable: true,
  }), null);
});
