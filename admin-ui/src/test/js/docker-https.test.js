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
  element('repository-recipe').value = 'docker-hosted';
  return { context, element };
}

test('HTTPS form preserves both endpoints and can clear the named TLS override', () => {
  const { context, element } = form({ name: 'docker-a', recipe: 'docker-hosted', format: 'docker', type: 'hosted',
    docker: { connectorEnabled: true, connectorPort: 5000, httpsConnectorEnabled: true,
      httpsConnectorPort: 5443, tlsBundle: 'registry' } });
  context.showEditRepositoryForm('docker-a');
  let payload = context.repositoryFormPayload();
  assert.equal(payload.docker.connectorPort, 5000);
  assert.equal(payload.docker.httpsConnectorPort, 5443);
  assert.equal(payload.docker.tlsBundle, 'registry');
  element('repository-docker-tls-bundle').value = '';
  element('repository-docker-connector-enabled').checked = false;
  payload = context.repositoryFormPayload();
  assert.equal(payload.docker.tlsBundle, '');
  assert.equal(payload.docker.httpsConnectorEnabled, true);
  assert.equal(payload.docker.connectorEnabled, false);
});

test('connector URL reflects its actual transport independently of the console', () => {
  const context = { window: { location: { protocol: 'https:', hostname: 'registry.example' } },
    lowerOrEmpty: value => (value || '').toLowerCase() };
  declarations.context(context);

  vm.runInContext(declarations("repositoryDisplayUrl"), context);
  const repo = { format: 'docker', docker: { connectorEnabled: true, connectorPort: 5000 } };
  assert.equal(context.repositoryDisplayUrl(repo), 'http://registry.example:5000/v2/');
  repo.docker.httpsConnectorEnabled = true;
  repo.docker.httpsConnectorPort = 5443;
  assert.equal(context.repositoryDisplayUrl(repo), 'https://registry.example:5443/v2/');
  repo.docker.connectorPublicUrl = 'https://external.example';
  assert.equal(context.repositoryDisplayUrl(repo), 'https://external.example');
});
