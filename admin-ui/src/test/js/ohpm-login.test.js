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

async function withPage(run, initial = []) {
  delete require.cache[require.resolve(modulePath)];
  const { load } = require(modulePath);
  const elements = new Map();
  for (const name of ["view", "form", "source", "user", "key", "enabled", "configuration", "rows", "empty",
    "page", "status", "refresh", "previous", "next", "mode", "save", "cancel", "enabled-field"]) {
    elements.set(name, {
      value: "", checked: false, disabled: false, events: {},
      addEventListener(event, handler) { this.events[event] = handler; },
      focus() {},
      querySelectorAll() { return [...elements.values()]; }
    });
  }
  const originals = Object.fromEntries(["document", "fetch", "escapeHtml", "responseErrorMessage", "kkrepoConfirmDelete"]
    .map((name) => [name, global[name]]));
  global.document = { getElementById: (id) => elements.get(id.replace("ohpm-login-", "")) };
  global.escapeHtml = (text) => String(text).replaceAll("<", "&lt;").replaceAll(">", "&gt;");
  global.responseErrorMessage = async () => "Administrator permission required";
  const state = { records: structuredClone(initial), denied: false, confirm: true };
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
      : { enabled: false, tokenTtlSeconds: 300, timestampToleranceSeconds: 300 } };
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
    assert.match(elements.get("configuration").textContent, /disabled/);
    assert.match(elements.get("configuration").textContent, /cannot authenticate/);
    assert.equal(elements.get("status").textContent, "Signing keys loaded.");
    assert.equal(elements.get("next").disabled, true);
    elements.get("source").value = "Local";
    elements.get("user").value = "<publisher>";
    elements.get("key").value = replacementPem;
    await submit();
    assert.equal(elements.get("status").textContent, "Public key saved.");
    assert.equal(elements.get("key").value, "");
    assert.match(elements.get("rows").innerHTML, /&lt;publisher&gt;/);
    assert.doesNotMatch(elements.get("rows").innerHTML, /BEGIN PUBLIC KEY/);
    await click("toggle");
    assert.equal(state.records[0].enabled, true);
    assert.match(elements.get("rows").innerHTML, /Login unavailable/);
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
    assert.ok(calls.some((call) => call.method === "DELETE" && call.path.endsWith("/keys/publisher-id")));
  });
});

test("fresh form rotation preserves an enabled owner's state, including owners on later pages", async () => {
  const firstPage = Array.from({ length: 100 }, (_, index) => ({ ...binding, userId: `other-${index}`,
    publishId: `other-id-${index}` }));
  await withPage(async ({ elements, state, calls, confirmations, submit }) => {
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
    assert.equal(elements.get("cancel").hidden, true);
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
    assert.equal(elements.get("enabled-field").hidden, false);
  }, [binding]);
});
