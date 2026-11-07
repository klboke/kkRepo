const assert = require("node:assert/strict");
const test = require("node:test");
const crypto = require("node:crypto");
const modulePath = "../../main/resources/META-INF/resources/admin/assets/ohpm-login.js";
const { command } = require(modulePath);

test("public key registration preserves the user identity and explicitly selected status", () => {
  const { publicKey } = crypto.generateKeyPairSync("rsa", { modulusLength: 2048 });
  const pem = publicKey.export({ type: "spki", format: "pem" });
  assert.deepEqual(command(" Local ", " publisher ", pem, false),
    { source: "Local", userId: "publisher", publicKey: pem.trim(), enabled: false });
  assert.equal(command("LDAP", "publisher", pem, true).enabled, true);
});

test("private keys and incomplete owners are rejected before submission", () => {
  const { publicKey, privateKey } = crypto.generateKeyPairSync("rsa", { modulusLength: 2048 });
  const pem = publicKey.export({ type: "spki", format: "pem" });
  assert.throws(() => command("", "publisher", pem, false), /existing user/);
  assert.throws(() => command("Local", " ", pem, false), /existing user/);
  assert.throws(() => command("Local", "publisher", privateKey.export({ type: "pkcs8", format: "pem" }), true),
    /public key/);
  assert.throws(() => command("Local", "publisher", "x".repeat(16385), true), /public key/);
});

async function withPage(run, initial = [], configurationEnabled = true) {
  delete require.cache[require.resolve(modulePath)];
  const { load } = require(modulePath);
  const elements = new Map();
  for (const name of ["view", "form", "source", "user", "key", "enabled", "configuration", "rows", "empty",
    "create", "form-status", "page-label", "page-size", "notice", "availability", "timing", "table", "pagination", "form-title", "page", "status", "refresh", "previous", "next", "mode", "save", "cancel", "enabled-field"]) {
    elements.set(name, {
      value: "", checked: false, disabled: false, events: {}, dataset: {},
      addEventListener(event, handler) { this.events[event] = handler; },
      focus() { this.focused = true; },
      querySelectorAll(selector) { return selector === "[data-requires-signed-login]" ? [] : [...elements.values()]; }
    });
  }
  const originals = Object.fromEntries(["document", "fetch", "escapeHtml", "responseErrorMessage", "kkrepoConfirmDelete", "openFormModal", "closeFormModal", "bindFormModalDismiss"]
    .map((name) => [name, global[name]]));
  global.document = { getElementById: (id) => elements.get(id.replace("ohpm-login-", "")) };
  global.escapeHtml = (text) => String(text).replaceAll("<", "&lt;").replaceAll(">", "&gt;");
  global.responseErrorMessage = async () => "Administrator permission required";
  const state = { records: structuredClone(initial), denied: false, confirm: true, configurationEnabled };
  elements.get("form").hidden = true;
  global.openFormModal = () => { elements.get("form").hidden = false; };
  global.closeFormModal = () => { elements.get("form").hidden = true; };
  global.bindFormModalDismiss = (id, handler) => { state.dismiss = handler; };
  const calls = [], confirmations = [];
  global.kkrepoConfirmDelete = async (options) => { confirmations.push(options); return state.confirm; };
  global.fetch = async (path, options) => {
    calls.push({ path, ...options });
    if (state.denied) return { ok: false, status: 403 };
    if (options.method === "PUT") {
      const body = JSON.parse(options.body);
      const record = state.records.find((entry) => path.includes(`/keys/${entry.publishId}/`));
      if (path.endsWith("/enabled")) record.enabled = body.enabled;
      else if (path.endsWith("/public-key")) record.fingerprint = "replacement-fingerprint";
      else state.records = [{ ...body, publishId: "publisher-id", fingerprint: "fingerprint" }];
      return { ok: true, status: 200, json: async () => record || state.records[0] };
    }
    if (options.method === "DELETE") {
      state.records = state.records.filter((entry) => !path.endsWith(`/keys/${entry.publishId}`));
      return { ok: true, status: 204 };
    }
    return { ok: true, status: 200, json: async () => path.includes("/keys?")
      ? state.records.slice(Number(path.split("offset=")[1]), Number(path.split("offset=")[1]) + 100)
      : { enabled: state.configurationEnabled, tokenTtlSeconds: 300, timestampToleranceSeconds: 300 } };
  };
  try {
    await load();
    const click = (action) => elements.get("rows").events.click({
      target: { closest: () => ({ dataset: { keyAction: action, index: "0" } }) }
    });
    const submit = () => elements.get("form").events.submit({ preventDefault() {} });
    await run({ elements, state, calls, confirmations, click, submit, load });
  } finally {
    for (const [name, value] of Object.entries(originals)) {
      if (value === undefined) delete global[name];
      else global[name] = value;
    }
  }
}

const binding = { source: "Local", userId: "publisher", enabled: true,
  publishId: "publisher-id", fingerprint: "original-fingerprint" };
const { publicKey: replacement } = crypto.generateKeyPairSync("rsa", { modulusLength: 2048 });
const replacementPem = replacement.export({ type: "spki", format: "pem" });

test("management feedback distinguishes loading, saving, enabling, errors and deletion", async () => {
  await withPage(async ({ elements, state, calls, click, submit }) => {
    assert.match(elements.get("configuration").textContent, /enabled/);
    assert.match(elements.get("availability").textContent, /can authenticate/);
    assert.equal(elements.get("status").textContent, "");
    assert.equal(elements.get("pagination").hidden, true);
    assert.equal(elements.get("table").hidden, true);
    assert.equal(elements.get("next").disabled, true);
    elements.get("create").events.click();
    elements.get("source").value = "Local";
    elements.get("user").value = "<publisher>";
    elements.get("key").value = replacementPem;
    await submit();
    assert.equal(elements.get("status").textContent, "Public key saved.");
    assert.equal(elements.get("key").value, "");
    assert.equal(elements.get("pagination").hidden, false);
    assert.equal(elements.get("table").hidden, false);
    assert.match(elements.get("rows").innerHTML, /&lt;publisher&gt;/);
    assert.doesNotMatch(elements.get("rows").innerHTML, /BEGIN PUBLIC KEY/);
    await click("toggle");
    assert.equal(state.records[0].enabled, true);
    assert.match(elements.get("rows").innerHTML, /Key enabled/);
    assert.deepEqual(JSON.parse(calls.find((call) => call.path.endsWith("/enabled")).body), { enabled: true });
    state.denied = true;
    await click("delete");
    assert.match(elements.get("status").textContent, /Administrator permission required/);
    assert.equal(elements.get("form").disabled, false);
    assert.equal(state.records.length, 1);
    state.denied = false;
    await click("delete");
    assert.equal(state.records.length, 0);
    assert.equal(elements.get("status").textContent, "Signing key deleted.");
    assert.equal(elements.get("empty").hidden, false);
    assert.equal(elements.get("pagination").hidden, true);
    assert.ok(calls.some((call) => call.method === "DELETE" && call.path.endsWith("/keys/publisher-id")));
  });
});

test("fresh form rotation preserves an enabled owner's state, including owners on later pages", async () => {
  const firstPage = Array.from({ length: 100 }, (_, index) => ({ ...binding, userId: `other-${index}`,
    publishId: `other-id-${index}` }));
  await withPage(async ({ elements, state, calls, confirmations, submit }) => {
    elements.get("create").events.click();
    assert.equal(elements.get("enabled").checked, false);
    elements.get("user").value = binding.userId;
    elements.get("key").value = replacementPem;
    await submit();
    assert.equal(state.records.at(-1).enabled, true);
    assert.equal(state.records.at(-1).publishId, binding.publishId);
    assert.equal(state.records.at(-1).fingerprint, "replacement-fingerprint");
    const writes = calls.filter((call) => call.method === "PUT");
    assert.equal(writes.length, 1);
    assert.equal(writes[0].path, `/internal/ohpm/login/keys/${binding.publishId}/public-key`);
    assert.deepEqual(JSON.parse(writes[0].body), { publicKey: replacementPem.trim() });
    assert.equal(confirmations[0].confirmLabel, "Rotate");
    assert.match(elements.get("status").textContent, /rotated/);
  }, [...firstPage, binding]);
});

test("selected rotation locks the owner and does not send a stale enabled state", async () => {
  await withPage(async ({ elements, state, calls, click, submit }) => {
    await click("rotate");
    assert.equal(elements.get("source").readOnly, true);
    assert.equal(elements.get("user").value, binding.userId);
    assert.equal(elements.get("enabled-field").hidden, true);
    assert.equal(elements.get("enabled").checked, true);
    state.records[0].enabled = false;
    elements.get("key").value = replacementPem;
    await submit();
    assert.equal(state.records[0].enabled, false);
    assert.equal("enabled" in JSON.parse(calls.find((call) => call.method === "PUT").body), false);
    assert.equal(elements.get("user").readOnly, false);
    assert.equal(elements.get("form").hidden, true);
  }, [binding]);
});

test("disable and delete require identity-specific confirmation and cancellation makes no mutation", async () => {
  await withPage(async ({ elements, state, calls, confirmations, click }) => {
    state.confirm = false;
    for (const action of ["toggle", "delete"]) await click(action);
    assert.equal(calls.filter((call) => call.method !== "GET").length, 0);
    for (const confirmation of confirmations) {
      assert.ok(confirmation.details.some((detail) => detail.value === "Local / publisher"));
      assert.ok(confirmation.details.some((detail) => detail.value === binding.fingerprint));
      assert.match(confirmation.description, /tokens will be revoked/);
    }
    assert.doesNotMatch(confirmations[0].warning, /Deletion is permanent/);
    assert.match(confirmations[1].description, /new publish ID/);
    state.confirm = true;
    await click("toggle");
    assert.equal(state.records[0].enabled, false);
    assert.equal(elements.get("status").textContent, "Signing key disabled.");
    await click("delete");
    assert.equal(state.records.length, 0);
  }, [binding]);
});

test("cancelling rotation keeps the PEM and never submits a mutation", async () => {
  await withPage(async ({ elements, state, calls, click, submit }) => {
    await click("rotate");
    elements.get("key").value = replacementPem;
    state.confirm = false;
    await submit();
    assert.equal(elements.get("key").value, replacementPem);
    assert.equal(calls.filter((call) => call.method !== "GET").length, 0);
    elements.get("cancel").events.click();
    assert.equal(elements.get("key").value, "");
    assert.equal(elements.get("pagination").hidden, false);
    assert.equal(elements.get("table").hidden, false);
    assert.equal(elements.get("enabled-field").hidden, false);
  }, [binding]);
});


test("deleting the last key on a later page returns to the first page", async () => {
  const records = Array.from({ length: 11 }, (_, index) => ({ ...binding,
    userId: `publisher-${index}`, publishId: `key-${index}` }));
  await withPage(async ({ elements, click, state }) => {
    await elements.get("next").events.click();
    assert.equal(elements.get("page").textContent, "11–11");
    await click("delete");
    assert.equal(state.records.length, 10);
    assert.equal(elements.get("page").textContent, "1–10");
    assert.equal(elements.get("previous").disabled, true);
    assert.equal(elements.get("empty").hidden, true);
    assert.equal(elements.get("status").textContent, "Signing key deleted.");
  }, records);
});


test("page size changes reset the page and exact full pages disable Next", async () => {
  const records = Array.from({ length: 100 }, (_, index) => ({ ...binding, publishId: `key-${index}` }));
  await withPage(async ({ elements }) => {
    assert.equal(elements.get("page").textContent, "1–10");
    assert.equal(elements.get("page-label").textContent, "Page 1");
    await elements.get("next").events.click();
    assert.equal(elements.get("page").textContent, "11–20");
    assert.equal(elements.get("page-label").textContent, "Page 2");
    elements.get("page-size").value = "25";
    await elements.get("page-size").events.change();
    assert.equal(elements.get("page").textContent, "1–25");
    assert.equal(elements.get("previous").disabled, true);
    for (let index = 0; index < 3; index++) await elements.get("next").events.click();
    assert.equal(elements.get("page").textContent, "76–100");
    assert.equal(elements.get("next").disabled, true);
    elements.get("page-size").value = "100";
    await elements.get("page-size").events.change();
    assert.equal(elements.get("page").textContent, "1–100");
    assert.equal(elements.get("next").disabled, true);
  }, records);
});

test("100-row pages detect additional keys and failed navigation preserves the visible page", async () => {
  const records = Array.from({ length: 101 }, (_, index) => ({ ...binding, publishId: `key-${index}` }));
  await withPage(async ({ elements, state }) => {
    elements.get("page-size").value = "100";
    await elements.get("page-size").events.change();
    assert.equal(elements.get("next").disabled, false);
    state.denied = true;
    await elements.get("next").events.click();
    assert.equal(elements.get("page").textContent, "1–100");
    state.denied = false;
    await elements.get("next").events.click();
    assert.equal(elements.get("page").textContent, "101–101");
    assert.equal(elements.get("next").disabled, true);
    await elements.get("previous").events.click();
    assert.equal(elements.get("page").textContent, "1–100");
  }, records);
});

test("the shared modal keeps failed submissions open and dismissal clears drafts", async () => {
  await withPage(async ({ elements, state, calls, submit }) => {
    assert.equal(elements.get("form").hidden, true);
    elements.get("create").events.click();
    assert.equal(elements.get("form").hidden, false);
    elements.get("user").value = "publisher";
    elements.get("key").value = replacementPem;
    state.denied = true;
    await submit();
    assert.equal(elements.get("form").hidden, false);
    assert.equal(elements.get("key").value, replacementPem);
    assert.match(elements.get("form-status").textContent, /permission required/);
    state.dismiss();
    assert.equal(elements.get("create").focused, true);
    assert.equal(elements.get("form").hidden, true);
    assert.equal(elements.get("key").value, "");
    assert.equal(calls.filter((call) => call.method !== "GET").length, 0);
    state.denied = false;
    elements.get("create").events.click();
    elements.get("user").value = "publisher";
    elements.get("key").value = replacementPem;
    await submit();
    assert.equal(elements.get("form").hidden, true);
    assert.equal(elements.get("status").textContent, "Public key saved.");
  });
});


test("disabled signed login blocks creation, rotation and enabling while allowing revocation", async () => {
  await withPage(async ({ elements, state, calls, click, submit, load }) => {
    assert.equal(elements.get("create").disabled, true);
    assert.equal(elements.get("save").disabled, true);
    assert.match(elements.get("rows").innerHTML, /data-requires-signed-login disabled/);
    elements.get("create").events.click();
    assert.equal(elements.get("form").hidden, true);
    await click("rotate");
    assert.equal(elements.get("form").hidden, true);
    await click("toggle");
    await submit();
    assert.equal(calls.filter((call) => call.method !== "GET").length, 0);
    state.configurationEnabled = true;
    await load();
    assert.equal(elements.get("create").disabled, false);
    await click("toggle");
    assert.equal(state.records[0].enabled, true);
    state.configurationEnabled = false;
    await load();
    assert.equal(elements.get("create").disabled, true);
    await click("toggle");
    assert.equal(state.records[0].enabled, false);
    await click("delete");
    assert.equal(state.records.length, 0);
    assert.equal(elements.get("create").disabled, true);
  }, [{ ...binding, enabled: false }], false);
});
