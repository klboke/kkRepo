const declarations = require("../../../../scripts/ui/test-fixture.cjs")("admin");
const assert = require("node:assert/strict");
const test = require("node:test");
const vm = require("node:vm");

const key = "GenericToken.result-fixture";

function setup() {
  const elements = new Map();
  function element(id) {
    if (!elements.has(id)) elements.set(id, {
      value: "", focus() {}, select() { this.selected = true; }
    });
    return elements.get(id);
  }
  const messages = [], copied = [];
  const ctx = declarations.context({
    document: { getElementById: element },
    apiKeyOwnerPicker: { validate: () => true, close() {} }, apiKeyFormGeneration: 0,
    validateRequiredFields: () => true, clearRequiredFieldErrors() {}, securityApiKeyRequiredFields: [],
    fetch: async () => ({ ok: true, json: async () => ({ token: key }) }), loadSecurityApiKeys: async () => {},
    openFormModal: id => { element(id).hidden = false; }, closeFormModal: id => { element(id).hidden = true; },
    copyLocationText: async value => copied.push(value), showToast: message => messages.push(message)
  });
  vm.runInContext(declarations("hideSecurityApiKeyForm", "hideSecurityApiKeyResult", "copySecurityApiKey", "saveSecurityApiKey"), ctx);
  return { ctx, element, copied, messages };
}

test("create presents the full value before the list refresh and only copies on request", async () => {
  const { ctx, element, copied, messages } = setup();
  const input = element("security-api-key-value");
  let resultAtRefresh;
  ctx.loadSecurityApiKeys = async () => {
    resultAtRefresh = [element("security-api-key-result").hidden, input.value];
  };
  await ctx.saveSecurityApiKey();
  assert.deepEqual(resultAtRefresh, [false, key]);
  assert.deepEqual(copied, []);
  await ctx.copySecurityApiKey();
  assert.deepEqual(copied, [key]);
  assert.deepEqual(messages, ["Copied"]);
  assert.equal(input.value, key);
  ctx.copyLocationText = async () => { throw Error("blocked"); };
  await ctx.copySecurityApiKey();
  assert.equal(input.selected, true);
  ctx.hideSecurityApiKeyResult();
  assert.equal(input.value, "");
  assert.equal(element("security-api-key-result").hidden, true);
});

test("duplicate submits are ignored and a dismissed dialog cannot reveal a delayed response", async () => {
  const { ctx, element, messages } = setup();
  let finish, requests = 0;
  ctx.fetch = () => {
    requests++;
    return new Promise(resolve => { finish = resolve; });
  };
  const pending = ctx.saveSecurityApiKey();
  await ctx.saveSecurityApiKey();
  assert.equal(requests, 1);
  ctx.hideSecurityApiKeyForm();
  finish({ ok: true, json: async () => ({ token: key }) });
  await pending;
  assert.equal(element("security-api-key-value").value, "");
  assert.deepEqual(messages, []);
});

test("a response without a token keeps the import confirmation", async () => {
  const { ctx, element, messages } = setup();
  ctx.fetch = async () => ({ ok: true, json: async () => ({ token: null }) });
  await ctx.saveSecurityApiKey();
  assert.equal(element("security-api-key-value").value, "");
  assert.deepEqual(messages, ["API key imported."]);
});
