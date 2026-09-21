const assert = require('node:assert/strict');
const { readFileSync } = require('node:fs');
const { join } = require('node:path');
const test = require('node:test');
const vm = require('node:vm');
const source = readFileSync(join(__dirname, '../../main/resources/META-INF/resources/admin/assets/admin.js'), 'utf8');

function form(repo) {
  const elements = new Map();
  const element = id => {
    if (!elements.has(id)) elements.set(id, {
      value: '', checked: false, hidden: false, closest: () => ({}),
    });
    return elements.get(id);
  };
  const context = {
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
    'openFormModal']) context[name] = () => {};
  vm.createContext(context);
  for (const name of ['refreshRepositoryRecipeControls', 'repositoryFormPayload',
    'setRepositoryFormDefaults', 'showEditRepositoryForm']) {
    const start = source.indexOf(`function ${name}(`);
    const end = source.indexOf('\nfunction ', start + 1);
    vm.runInContext(source.slice(start, end), context);
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
