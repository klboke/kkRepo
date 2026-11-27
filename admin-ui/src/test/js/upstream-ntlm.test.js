const declarations = require("../../../../scripts/ui/test-fixture.cjs")("admin");
const assert = require('node:assert/strict');
const test = require('node:test');
const vm = require('node:vm');

function form(repo) {
  const elements = new Map();
  const element = id => {
    if (!elements.has(id)) elements.set(id, {
      value: '', options: ['DIRECT', 'INHERIT', 'OVERRIDE'].map(value => ({ value })), checked: false, hidden: false, closest: () => ({}),
    });
    return elements.get(id);
  };
  const context = {
    window: { KkRoutingRules: { loadOptions: () => {} } },
    document: { getElementById: element, querySelectorAll: () => [] },
    repositories: repo ? [repo] : [],
    repositoryFormMode: 'create', repositoryRequiredFields: [],
    memberTransfer: { selected: [], highlight: { available: new Set(), selected: new Set() } },
    currentRecipe: () => ({ type: 'PROXY', format: element('repository-recipe').value.split('-')[0] }),
    textInputValue: id => element(id).value.trim(),
  };
  for (const name of ['refreshDockerConnectorControls', 'refreshAptControls', 'refreshAlpineControls',
    'refreshRepositoryBlobStoreLock', 'refreshRepositoryRemoteDefaults', 'updateRequiredMarkers',
    'syncRepositoryRecipeCombobox', 'refreshRepositoryBlobStoreOptions', 'clearRequiredFieldErrors',
    'openFormModal', 'setConanManifestSettings']) context[name] = () => {};
  declarations.context(context);
  context.proxySettings = require("../../../../scripts/ui/module-fixture.cjs")("admin", "proxy-settings", context);
  // admin.js declares this at top level; the fixtures only evaluate selected functions, so load the real one.
  vm.runInContext(declarations("MINIMUM_RELEASE_AGE_FORMATS", "proxyCommand", "proxyFormValues"), context);
  for (const name of ['refreshRepositoryRecipeControls', 'repositoryFormPayload',
    'setRepositoryFormDefaults', 'showEditRepositoryForm']) {
    vm.runInContext(declarations(name), context);
  }
  context.setRepositoryFormDefaults();
  element('repository-recipe').value = 'nuget-proxy';
  return { context, element };
}

test('NTLM repository edit/save retains mode, domain and saved password', () => {
  const { context, element } = form({ name: 'feed', recipe: 'nuget-proxy', format: 'nuget', type: 'PROXY',
    online: true, proxy: { remoteUrl: 'https://devops.example/index.json', remoteAuthenticationType: 'ntlm',
      remoteUsername: 'User', remotePasswordConfigured: true, remoteNtlmDomain: 'Domain', remoteNtlmHost: 'KKREPO' } });
  context.showEditRepositoryForm('feed');
  assert.equal(element('repository-remote-ntlm-domain-field').hidden, false);
  const proxy = context.repositoryFormPayload().proxy;
  assert.equal(proxy.remoteAuthenticationType, 'ntlm');
  assert.equal(proxy.remoteNtlmDomain, 'Domain');
  assert.equal(proxy.remoteNtlmHost, 'KKREPO');
  assert.equal(proxy.remotePassword, '');
  assert.equal(proxy.remotePasswordConfigured, null);
  element('repository-remote-password').value = 'rotated-password';
  assert.equal(context.repositoryFormPayload().proxy.remotePassword, 'rotated-password');
});

test('legacy feeds stay in Basic/Bearer mode and other formats do not inherit NTLM', () => {
  const { context, element } = form();
  assert.equal(context.repositoryFormPayload().proxy.remoteAuthenticationType, 'auto');
  element('repository-remote-authentication-type').value = 'ntlm';
  context.refreshRepositoryRecipeControls();
  assert.equal(element('repository-remote-ntlm-domain-field').hidden, false);
  element('repository-recipe').value = 'npm-proxy';
  context.refreshRepositoryRecipeControls();
  assert.equal(element('repository-remote-authentication-field').hidden, true);
  assert.equal(element('repository-remote-ntlm-domain-field').hidden, true);
  assert.equal(context.repositoryFormPayload().proxy.remoteAuthenticationType, 'auto');
  context.setRepositoryFormDefaults();
  assert.equal(element('repository-remote-ntlm-domain').value, '');
});

test('existing redirect editor round-trips standalone star with exact and domain rules', () => {
  const { context, element } = form({ name: 'feed', recipe: 'nuget-proxy', format: 'nuget', type: 'PROXY',
    online: true, proxy: { remoteUrl: 'https://repo.example', allowedRedirectHosts: ['*', 'cdn.example', '*.example.net'] } });
  context.showEditRepositoryForm('feed');
  assert.equal(element('repository-allowed-redirect-hosts').value, '*, cdn.example, *.example.net');
  assert.deepEqual(Array.from(context.repositoryFormPayload().proxy.allowedRedirectHosts), ['*', 'cdn.example', '*.example.net']);
  assert.equal(Object.hasOwn(context.repositoryFormPayload().proxy, 'redirectPolicy'), false);
});
