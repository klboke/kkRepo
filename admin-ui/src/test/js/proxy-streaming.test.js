const declarations = require("../../../../scripts/ui/test-fixture.cjs")("admin");
const assert = require('node:assert/strict');
const { readFileSync } = require('node:fs');
const { join } = require('node:path');
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
    repositoryFormMode: 'create', repositoryRequiredFields: [], conanManifestDefaults: null,
    memberTransfer: { selected: [], highlight: { available: new Set(), selected: new Set() } },
    currentRecipe: () => {
      const [format, type] = element('repository-recipe').value.split('-');
      return { type: type?.toUpperCase(), format };
    },
    textInputValue: id => element(id).value.trim(),
  };
  for (const name of ['refreshDockerConnectorControls', 'refreshAptControls', 'refreshAlpineControls',
    'refreshRepositoryBlobStoreLock', 'refreshRepositoryRemoteDefaults', 'updateRequiredMarkers',
    'syncRepositoryRecipeCombobox', 'refreshRepositoryBlobStoreOptions', 'clearRequiredFieldErrors',
    'openFormModal', 'setConanManifestSettings', 'loadConanStatus', 'loadAptStatus', 'loadAlpineStatus']) context[name] = () => {};
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

test('every proxy form defaults streaming off, round trips it, and can disable it', () => {
  const recipes = readFileSync(join(__dirname, '../../../../core/src/main/java/com/github/klboke/kkrepo/core/RepositoryRecipes.java'), 'utf8');
  const names = [...recipes.matchAll(/new RepositoryRecipe\("([^"]+-proxy)"/g)].map(match => match[1]);
  assert.ok(names.length >= 20);
  for (const recipe of names) {
    const { context, element } = form({ name: 'feed', recipe, format: recipe.split('-')[0], type: 'PROXY',
      online: true, proxy: { remoteUrl: 'https://example.org', streamingEnabled: true } });
    element('repository-recipe').value = recipe;
    assert.equal(context.repositoryFormPayload().proxy.streamingEnabled, false, recipe);
    context.showEditRepositoryForm('feed');
    assert.equal(context.repositoryFormPayload().proxy.streamingEnabled, true, recipe);
    element('repository-streaming-enabled').checked = false;
    assert.equal(context.repositoryFormPayload().proxy.streamingEnabled, false, recipe);
    context.setRepositoryFormDefaults();
    assert.equal(element('repository-streaming-enabled').checked, false, recipe);
  }
});

test('manual outbound blocking is editable on every proxy recipe and defaults off', () => {
  const recipes = readFileSync(join(__dirname, '../../../../core/src/main/java/com/github/klboke/kkrepo/core/RepositoryRecipes.java'), 'utf8');
  const names = [...recipes.matchAll(/new RepositoryRecipe\("([^"]+-proxy)"/g)].map(match => match[1]);
  assert.ok(names.length >= 20);
  for (const recipe of names) {
    const { context, element } = form({ name: 'feed', recipe, format: recipe.split('-')[0], type: 'PROXY',
      online: true, proxy: { remoteUrl: 'https://example.org', blockOutboundConnections: true } });
    element('repository-recipe').value = recipe;
    assert.equal(context.repositoryFormPayload().proxy.blockOutboundConnections, false, recipe);
    context.showEditRepositoryForm('feed');
    assert.equal(element('repository-block-outbound-field').hidden, false, recipe);
    assert.equal(context.repositoryFormPayload().proxy.blockOutboundConnections, true, recipe);
    element('repository-block-outbound').checked = false;
    assert.equal(context.repositoryFormPayload().proxy.blockOutboundConnections, false, recipe);
    element('repository-recipe').value = 'maven2-hosted';
    context.refreshRepositoryRecipeControls();
    assert.equal(element('repository-block-outbound-field').hidden, true, recipe);
    assert.equal(context.repositoryFormPayload().proxy, undefined, recipe);
  }
});

test('repository edit round-trips legacy proxy types through the shared editor', () => {
  for (const [stored, expected] of [['socks5', 'SOCKS'], ['socks', 'SOCKS'], [' http ', 'HTTP']]) {
    const { context } = form({ name: 'feed', recipe: 'maven2-proxy', format: 'maven2', type: 'PROXY',
      online: true, proxy: {
        remoteUrl: 'https://example.org', outboundProxyType: stored,
        outboundProxyHost: 'proxy.example.org', outboundProxyPort: 3128
      } });
    context.showEditRepositoryForm('feed');
    assert.equal(context.repositoryFormPayload().proxy.outboundProxyType, expected);
  }
});

test('streaming explanation uses the shared accessible information popover', () => {
  const html = readFileSync(join(__dirname, '../../../target/generated-ui/META-INF/resources/admin/index.html'), 'utf8');
  const start = html.indexOf('id="repository-streaming-enabled"');
  const snippet = html.slice(start, html.indexOf('id="repository-auto-block"', start));
  assert.match(snippet, /class="field-help" tabindex="0"/);
  const tooltip = snippet.match(/data-tooltip="([^"]+)"/)[1];
  const accessible = snippet.match(/aria-label="([^"]+)"/)[1];
  assert.ok(tooltip.length > 20);
  assert.equal(accessible, tooltip);
  assert.doesNotMatch(snippet, /<p/);
});

test('only the repository override mode enables and sends custom proxy settings', () => {
  const { context, element } = form({ name: 'feed', recipe: 'maven2-proxy', format: 'maven2',
    type: 'PROXY', online: true, proxy: { remoteUrl: 'https://example.org',
      outboundProxyType: 'HTTP', outboundProxyHost: 'egress.example.org', outboundProxyPort: 3128 } });
  assert.equal(context.repositoryFormPayload().proxy.outboundProxyMode, 'DIRECT');
  context.showEditRepositoryForm('feed');
  assert.equal(context.repositoryFormPayload().proxy.outboundProxyMode, 'OVERRIDE');
  assert.equal(context.repositoryFormPayload().proxy.outboundProxyHost, 'egress.example.org');
  element('repository-outbound-proxy-mode').value = 'INHERIT';
  context.proxySettings.refreshRepositoryForm();
  const inherited = context.repositoryFormPayload().proxy;
  assert.equal(inherited.outboundProxyMode, 'INHERIT');
  assert.equal(inherited.outboundProxyHost, undefined);
  assert.equal(element('repository-outbound-proxy-host').disabled, true);
  assert.equal(element('repository-outbound-proxy-host').value, 'egress.example.org');
});
