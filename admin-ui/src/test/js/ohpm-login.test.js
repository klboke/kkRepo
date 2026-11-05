const assert = require("node:assert/strict");
const test = require("node:test");
const crypto = require("node:crypto");
const { command, load } = require("../../main/resources/META-INF/resources/admin/assets/ohpm-login.js");

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

test("management actions save public material, toggle status, report failures and delete by publish ID", async () => {
  const elements = new Map();
  for (const name of ["view", "form", "source", "user", "key", "enabled", "configuration", "rows", "empty",
    "page", "status", "refresh", "previous", "next"]) {
    elements.set(name, {
      value: "", checked: false, disabled: false, events: {},
      addEventListener(event, handler) { this.events[event] = handler; },
      querySelectorAll() { return [...elements.values()]; }
    });
  }
  const originals = Object.fromEntries(["document", "fetch", "escapeHtml", "responseErrorMessage"]
    .map((name) => [name, global[name]]));
  global.document = { getElementById: (id) => elements.get(id.replace("ohpm-login-", "")) };
  global.escapeHtml = (text) => String(text).replaceAll("<", "&lt;").replaceAll(">", "&gt;");
  global.responseErrorMessage = async () => "Administrator permission required";
  let records = [], denied = false;
  const calls = [];
  global.fetch = async (path, options) => {
    calls.push({ path, ...options });
    if (denied) return { ok: false, status: 403 };
    if (options.method === "PUT") {
      const body = JSON.parse(options.body);
      if (path.endsWith("/enabled")) records[0].enabled = body.enabled;
      else records = [{ ...body, publishId: "publisher-id", fingerprint: "fingerprint" }];
      return { ok: true, status: 200, json: async () => records[0] };
    }
    if (options.method === "DELETE") {
      records = [];
      return { ok: true, status: 204 };
    }
    return { ok: true, status: 200, json: async () => path.includes("/keys?")
      ? records : { enabled: false, tokenTtlSeconds: 300, timestampToleranceSeconds: 300 } };
  };
  try {
    await load();
    assert.match(elements.get("configuration").textContent, /disabled/);
    assert.equal(elements.get("next").disabled, true);
    const { publicKey } = crypto.generateKeyPairSync("rsa", { modulusLength: 2048 });
    elements.get("source").value = "Local";
    elements.get("user").value = "<publisher>";
    elements.get("key").value = publicKey.export({ type: "spki", format: "pem" });
    await elements.get("form").events.submit({ preventDefault() {} });
    assert.equal(elements.get("key").value, "");
    assert.match(elements.get("rows").innerHTML, /&lt;publisher&gt;/);
    assert.doesNotMatch(elements.get("rows").innerHTML, /BEGIN PUBLIC KEY/);
    const click = (action) => elements.get("rows").events.click({
      target: { closest: () => ({ dataset: { keyAction: action, index: "0" } }) }
    });
    await click("toggle");
    assert.equal(records[0].enabled, true);
    assert.deepEqual(JSON.parse(calls.find((call) => call.path.endsWith("/enabled")).body), { enabled: true });
    denied = true;
    await click("delete");
    assert.match(elements.get("status").textContent, /Administrator permission required/);
    assert.equal(elements.get("form").disabled, false);
    assert.equal(records.length, 1);
    denied = false;
    await click("delete");
    assert.equal(records.length, 0);
    assert.equal(elements.get("empty").hidden, false);
    assert.ok(calls.some((call) => call.method === "DELETE" && call.path.endsWith("/keys/publisher-id")));
  } finally {
    for (const [name, value] of Object.entries(originals)) {
      if (value === undefined) delete global[name];
      else global[name] = value;
    }
  }
});
