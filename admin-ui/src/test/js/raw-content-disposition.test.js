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

test('Raw content disposition defaults to attachment and can be saved as inline', () => {
  const { context, element } = form();
  element('repository-recipe').value = 'raw-proxy';
  context.refreshRepositoryRecipeControls();
  assert.equal(element('repository-raw-fields').hidden, false);
  assert.equal(context.repositoryFormPayload().raw.contentDisposition, 'ATTACHMENT');
  element('repository-raw-content-disposition').value = 'INLINE';
  assert.equal(context.repositoryFormPayload().raw.contentDisposition, 'INLINE');
  element('repository-recipe').value = 'npm-proxy';
  context.refreshRepositoryRecipeControls();
  assert.equal(element('repository-raw-fields').hidden, true);
  assert.equal(context.repositoryFormPayload().raw, undefined);
});

test('Editing a Raw proxy preserves inline while legacy settings default to attachment', () => {
  for (const disposition of ['INLINE', 'ATTACHMENT', undefined]) {
    const { context, element } = form({ name: 'mirror', recipe: 'raw-proxy', format: 'raw', type: 'PROXY',
      online: true, proxy: { remoteUrl: 'https://example.test/archive/' },
      raw: disposition ? { contentDisposition: disposition } : undefined });
    context.showEditRepositoryForm('mirror');
    assert.equal(element('repository-raw-content-disposition').value, disposition || 'ATTACHMENT');
    assert.equal(context.repositoryFormPayload().raw.contentDisposition, disposition || 'ATTACHMENT');
  }
});
