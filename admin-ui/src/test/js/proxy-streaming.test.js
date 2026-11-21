const assert = require('node:assert/strict');
const { readFileSync } = require('node:fs');
const { join } = require('node:path');
const test = require('node:test');
const vm = require('node:vm');
const source = readFileSync(join(__dirname, '../../main/resources/META-INF/resources/admin/assets/admin.js'), 'utf8');
const proxySettings = readFileSync(join(__dirname, '../../main/resources/META-INF/resources/admin/assets/proxy-settings.js'), 'utf8');

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
    currentRecipe: () => ({ type: 'PROXY', format: element('repository-recipe').value.split('-')[0] }),
    textInputValue: id => element(id).value.trim(),
  };
  for (const name of ['refreshDockerConnectorControls', 'refreshAptControls', 'refreshAlpineControls',
    'refreshRepositoryBlobStoreLock', 'refreshRepositoryRemoteDefaults', 'updateRequiredMarkers',
    'syncRepositoryRecipeCombobox', 'refreshRepositoryBlobStoreOptions', 'clearRequiredFieldErrors',
    'openFormModal', 'setConanManifestSettings', 'loadConanStatus', 'loadAptStatus', 'loadAlpineStatus']) context[name] = () => {};
  vm.createContext(context);
  vm.runInContext(proxySettings, context);
  // admin.js declares this at top level; the fixtures only evaluate selected functions, so load the real one.
  vm.runInContext(source.slice(source.indexOf('const MINIMUM_RELEASE_AGE_FORMATS'),
    source.indexOf('\n', source.indexOf('const MINIMUM_RELEASE_AGE_FORMATS'))), context);
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

test('manual outbound blocking defaults off and is editable only for Maven proxies', () => {
  const { context, element } = form({ name: 'central', recipe: 'maven2-proxy', format: 'maven2', type: 'PROXY',
    online: true, proxy: { remoteUrl: 'https://repo.maven.apache.org/maven2/', blockOutboundConnections: true } });
  element('repository-recipe').value = 'maven2-proxy';
  assert.equal(context.repositoryFormPayload().proxy.blockOutboundConnections, false);
  context.showEditRepositoryForm('central');
  assert.equal(element('repository-block-outbound-field').hidden, false);
  assert.equal(context.repositoryFormPayload().proxy.blockOutboundConnections, true);
  element('repository-block-outbound').checked = false;
  assert.equal(context.repositoryFormPayload().proxy.blockOutboundConnections, false);
  element('repository-recipe').value = 'npm-proxy';
  context.refreshRepositoryRecipeControls();
  assert.equal(element('repository-block-outbound-field').hidden, true);
  assert.equal(Object.hasOwn(context.repositoryFormPayload().proxy, 'blockOutboundConnections'), false);
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
  const html = readFileSync(join(__dirname, '../../main/resources/META-INF/resources/admin/index.html'), 'utf8');
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
  context.window.KkProxySettings.refreshRepositoryForm();
  const inherited = context.repositoryFormPayload().proxy;
  assert.equal(inherited.outboundProxyMode, 'INHERIT');
  assert.equal(inherited.outboundProxyHost, undefined);
  assert.equal(element('repository-outbound-proxy-host').disabled, true);
  assert.equal(element('repository-outbound-proxy-host').value, 'egress.example.org');
});
