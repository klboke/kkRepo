const assert = require("node:assert/strict");
const test = require("node:test");
const { create } = require("../../main/resources/META-INF/resources/admin/assets/user-picker.js");

class Element {
  constructor(id = "") { this.id = id; this.attrs = {}; this.events = {}; this.children = []; this.value = ""; this.classList = { toggle() {} }; }
  replaceWith(wrapper) { this.wrapper = wrapper; }
  append(child) { this.children.push(child); }
  replaceChildren() { this.children = []; }
  setAttribute(name, value) { this.attrs[name] = value; }
  getAttribute(name) { return this.attrs[name]; }
  removeAttribute(name) { delete this.attrs[name]; }
  setCustomValidity(message) { this.validationMessage = message; }
  addEventListener(name, fn) { this.events[name] = fn; }
  focus() { this.focused = true; this.events.focus?.(); }
  scrollIntoView() {}
  fire(name, value) { if (value !== undefined) this.value = value; this.events[name]?.(); }
  key(key) { const event = { key, preventDefault() { this.prevented = true; }, stopPropagation() { this.stopped = true; } }; this.events.keydown(event); return event; }
  get list() { return this.wrapper.children[1]; }
}
const records = [
  { source: "Local", userId: "publisher", email: "release@example.test" },
  { source: "LDAP", userId: "publisher", email: "directory@example.test" },
  { source: "LDAP", userId: "other", email: "other@example.test" }
];
async function withPicker(run) {
  const originals = { document: global.document, fetch: global.fetch, responseErrorMessage: global.responseErrorMessage };
  global.document = { createElement: () => new Element() };
  let requests = 0;
  global.fetch = async () => { requests++; return { ok: true, json: async () => records }; };
  global.responseErrorMessage = async () => "Permission denied";
  const source = new Element("source"), user = new Element("user"), status = new Element();
  const picker = create({ source, user, status });
  try { await run({ picker, source, user, status, requests: () => requests }); }
  finally { Object.assign(global, originals); }
}

test("source selection scopes same-named users and source edits clear the chosen identity", async () => {
  await withPicker(async ({ picker, source, user, requests }) => {
    await picker.open();
    assert.equal(source.value, "Local");
    user.fire("input", "release@");
    assert.equal(user.list.children.length, 1);
    user.key("Enter");
    assert.equal(user.value, "publisher");
    assert.equal(picker.validate(), true);
    source.fire("input", "lda");
    assert.equal(user.value, "");
    assert.equal(user.disabled, true);
    assert.equal(picker.validate(), false);
    source.key("ArrowDown"); source.key("Enter");
    assert.equal(source.value, "LDAP");
    assert.equal(user.disabled, false);
    user.fire("input", "directory@"); user.key("Enter");
    assert.equal(picker.validate(), true);
    assert.equal(requests(), 1, "typing does not repeatedly fetch the user catalog");
  });
});

test("typed identities require selection and Escape closes suggestions before the modal", async () => {
  await withPicker(async ({ picker, user }) => {
    await picker.open();
    user.fire("input", "publisher");
    assert.equal(picker.validate(), false);
    const escape = user.key("Escape");
    assert.equal(escape.stopped, true);
    assert.equal(user.list.hidden, true);
    assert.equal(user.key("Escape").stopped, undefined);
    user.fire("input", "unknown");
    user.key("Enter");
    assert.equal(picker.validate(), false);
    assert.ok(user.validationMessage);
    user.fire("input", "publisher");
    user.key("ArrowDown");
    assert.ok(user.attrs["aria-activedescendant"]);
    user.key("Enter");
    assert.equal(picker.validate(), true);
  });
});

test("late loads cannot overwrite reopened rotation; closing discards the catalog", async () => {
  await withPicker(async ({ picker, source, user, requests }) => {
    let resolve;
    global.fetch = () => new Promise((done) => { resolve = done; });
    const pending = picker.open();
    assert.equal(user.disabled, true);
    picker.close();
    await picker.open({ source: "Removed source", userId: "old-owner" });
    resolve({ ok: true, json: async () => records });
    await pending;
    assert.equal(source.value, "Removed source");
    assert.equal(user.value, "old-owner");
    assert.equal(user.disabled, true);
    assert.equal(picker.validate(), true);
    picker.sync();
    assert.equal(source.disabled, true);
    picker.close();
    assert.equal(picker.validate(), false);
    assert.equal(user.value, "");
    assert.equal(requests(), 0);
  });
});

test("lookup failures keep selection blocked and reopening retries", async () => {
  await withPicker(async ({ picker, source, user, status }) => {
    global.fetch = async () => ({ ok: false });
    await picker.open();
    assert.match(status.textContent, /Permission denied/);
    assert.equal(source.disabled, true);
    assert.equal(user.disabled, true);
    assert.equal(picker.validate(), false);
    global.fetch = async () => ({ ok: true, json: async () => records });
    await picker.open();
    assert.equal(user.disabled, false);
    assert.equal(status.textContent, "");
  });
});


test("mouse selection suppresses label activation and closes the dropdown", async () => {
  await withPicker(async ({ picker, user }) => {
    await picker.open();
    user.focus();
    const event = { preventDefault() { this.prevented = true; } };
    user.list.children[0].events.click(event);
    assert.equal(event.prevented, true, "the label must not forward another click to the input");
    assert.equal(user.value, "publisher");
    assert.equal(user.list.hidden, true);
    assert.equal(user.attrs["aria-expanded"], "false");
    assert.equal(picker.validate(), true);
  });
});

test("the first ArrowUp selects the last candidate and ArrowDown wraps to the first", async () => {
  await withPicker(async ({ picker, source, user }) => {
    await picker.open();
    source.fire("input", "LDAP"); source.key("Enter");
    user.focus();
    user.key("ArrowUp");
    assert.equal(user.attrs["aria-activedescendant"], "user-options-1");
    user.key("ArrowDown");
    assert.equal(user.attrs["aria-activedescendant"], "user-options-0");
    user.key("ArrowUp"); user.key("Enter");
    assert.equal(user.value, "other");
    assert.equal(picker.validate(), true);
  });
});
