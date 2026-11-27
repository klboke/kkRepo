const assert = require("node:assert/strict");
const test = require("node:test");
const { proxyCommand, proxyFormValues } = require("../../main/ui/js/repositories/proxy-form.js");

test("proxy edits distinguish empty, zero, disabled routing, and explicit credential clearing", () => {
  const fields = proxyFormValues({ remoteUrl: " https://example.test ", allowedRedirectHosts: ["a.test", "b.test"] });
  fields.contentMaxAgeMinutes = "";
  fields.metadataMaxAgeMinutes = "0";
  fields.minimumReleaseAgeMinutes = "";
  fields.clearPassword = false;
  fields.clearBearerToken = true;
  const { proxy, ...routing } = proxyCommand("npm", fields, { outboundProxyMode: "DIRECT" });
  assert.deepEqual(routing, {});
  assert.equal(proxy.remoteUrl, "https://example.test");
  assert.deepEqual(proxy.allowedRedirectHosts, ["a.test", "b.test"]);
  assert.equal(proxy.contentMaxAgeMinutes, null);
  assert.equal(proxy.metadataMaxAgeMinutes, 0);
  assert.equal(proxy.minimumReleaseAgeMinutes, 0);
  assert.equal(proxy.remotePasswordConfigured, null);
  assert.equal(proxy.remoteBearerTokenConfigured, false);
  assert.equal(proxy.outboundProxyMode, "DIRECT");
  const raw = proxyCommand("raw", { ...fields, routingRuleName: "" }, {});
  assert.equal(raw.routingRuleName, "");
  assert.equal(raw.proxy.minimumReleaseAgeMinutes, null);
});
