const assert = require("node:assert/strict");
const { readFileSync } = require("node:fs");
const { resolve } = require("node:path");
const test = require("node:test");
const vm = require("node:vm");

const MIN_PANE_PX = 220;

function loadSplitResizer(environment) {
  const source = readFileSync(resolve(
    __dirname,
    "../../main/resources/META-INF/resources/browse/assets/browse.js"), "utf8");
  const start = source.indexOf("const SPLIT_WIDTH_KEY");
  const end = source.indexOf("async function loadAndRenderTreeLevel", start);
  assert.notEqual(start, -1, "split resizer block should exist");
  assert.notEqual(end, -1, "split resizer block should have a stable boundary");
  const context = vm.createContext(environment);
  vm.runInContext(
    `${source.slice(start, end)}\nglobalThis.initSplitResizer = initSplitResizer;`
      + "\nglobalThis.clampSplitPct = clampSplitPct;",
    context,
  );
  return context;
}

// Minimal DOM double: the tree pane's pixel width follows the --tree-pane-width percentage.
function createSplit(initialWidth) {
  const properties = {};
  const handleListeners = {};
  const handle = {
    attributes: {},
    setAttribute(name, value) { this.attributes[name] = value; },
    addEventListener(type, listener) { handleListeners[type] = listener; },
  };
  const split = {
    width: initialWidth,
    isConnected: true,
    classList: { add() {}, remove() {} },
    style: { setProperty(name, value) { properties[name] = value; } },
    getBoundingClientRect() { return { width: this.width, left: 0 }; },
    querySelector(selector) {
      return selector === ".split-handle" ? handle : tree;
    },
  };
  const tree = {
    getBoundingClientRect() {
      const pct = parseFloat(properties["--tree-pane-width"] || "60");
      return { width: (split.width * pct) / 100 };
    },
  };
  return { split, handle, handleListeners, properties };
}

function createEnvironment(storage = {}) {
  const observers = [];
  class FakeResizeObserver {
    constructor(callback) {
      this.callback = callback;
      this.disconnected = false;
      observers.push(this);
    }
    observe() {}
    disconnect() { this.disconnected = true; }
  }
  return {
    observers,
    environment: {
      ResizeObserver: FakeResizeObserver,
      localStorage: {
        getItem: (key) => (key in storage ? storage[key] : null),
        setItem: (key, value) => { storage[key] = value; },
      },
      parseFloat,
      Number,
      Math,
      String,
    },
    storage,
  };
}

function paneWidths(fixture) {
  const pct = parseFloat(fixture.properties["--tree-pane-width"]);
  return {
    tree: (fixture.split.width * pct) / 100,
    detail: fixture.split.width - (fixture.split.width * pct) / 100,
  };
}

test("keeps both panes at least 220px when the container shrinks after dragging", () => {
  const { environment, observers } = createEnvironment();
  const context = loadSplitResizer(environment);
  const fixture = createSplit(1280);
  context.initSplitResizer(fixture.split);

  // Move the separator right (keyboard path) until the detail pane sits at its minimum width.
  for (let i = 0; i < 20; i += 1) {
    fixture.handleListeners.keydown({ key: "ArrowRight", preventDefault() {} });
  }
  assert.ok(paneWidths(fixture).detail >= MIN_PANE_PX);
  assert.ok(paneWidths(fixture).detail < MIN_PANE_PX + 40, "detail pane should be near its minimum");

  // Shrink the container: the observer must re-clamp so neither pane drops below 220px.
  fixture.split.width = 1000;
  observers[0].callback();
  const shrunk = paneWidths(fixture);
  assert.ok(shrunk.tree >= MIN_PANE_PX - 0.5, `tree ${shrunk.tree}`);
  assert.ok(shrunk.detail >= MIN_PANE_PX - 0.5, `detail ${shrunk.detail}`);
});

test("restores the preferred width when the container grows back", () => {
  const { environment, observers } = createEnvironment();
  const context = loadSplitResizer(environment);
  const fixture = createSplit(1280);
  context.initSplitResizer(fixture.split);
  for (let i = 0; i < 20; i += 1) {
    fixture.handleListeners.keydown({ key: "ArrowRight", preventDefault() {} });
  }
  const before = parseFloat(fixture.properties["--tree-pane-width"]);

  fixture.split.width = 600;
  observers[0].callback();
  assert.ok(parseFloat(fixture.properties["--tree-pane-width"]) < before);

  fixture.split.width = 1280;
  observers[0].callback();
  assert.equal(parseFloat(fixture.properties["--tree-pane-width"]), before);
});

test("does not overwrite the saved preference when only the container resizes", () => {
  const { environment, observers, storage } = createEnvironment();
  const context = loadSplitResizer(environment);
  const fixture = createSplit(1280);
  context.initSplitResizer(fixture.split);
  fixture.handleListeners.keydown({ key: "ArrowRight", preventDefault() {} });
  const saved = storage["kkrepo.browse.treePaneWidthPct"];
  assert.ok(saved);

  fixture.split.width = 500;
  observers[0].callback();
  assert.equal(storage["kkrepo.browse.treePaneWidthPct"], saved);
});

test("stops observing once the split is removed from the document", () => {
  const { environment, observers } = createEnvironment();
  const context = loadSplitResizer(environment);
  const fixture = createSplit(1280);
  context.initSplitResizer(fixture.split);

  fixture.split.isConnected = false;
  observers[0].callback();
  assert.equal(observers[0].disconnected, true);
});

test("resets to the default width on double-click", () => {
  const { environment } = createEnvironment({ "kkrepo.browse.treePaneWidthPct": "80" });
  const context = loadSplitResizer(environment);
  const fixture = createSplit(1280);
  context.initSplitResizer(fixture.split);
  assert.equal(parseFloat(fixture.properties["--tree-pane-width"]), 80);

  fixture.handleListeners.dblclick();
  assert.equal(parseFloat(fixture.properties["--tree-pane-width"]), 60);
});
