const assert = require('node:assert/strict');
const { readFileSync } = require('node:fs');
const { join } = require('node:path');
const test = require('node:test');
const vm = require('node:vm');
const source = readFileSync(join(__dirname, '../../main/resources/META-INF/resources/admin/assets/admin.js'), 'utf8');

function form(defaults = { manifestMaxEntries: 50000, manifestMaxBytes: 16777216 }) {
  const elements = new Map();
  const element = id => {
    if (!elements.has(id)) elements.set(id, {
      value: '', dataset: {}, disabled: false, checked: false, validity: { badInput: false },
      max: id.endsWith('entries') ? '2147483647' : '2147483646',
      setAttribute(name, value) { this[name] = value; },
    });
    return elements.get(id);
  };
  const context = {
    document: { getElementById: element }, window: {},
    repositoryFormMode: "create",
    memberTransfer: { selected: [] },
    currentRecipe: () => ({ format: 'conan', type: 'HOSTED' }),
    textInputValue: id => element(id).value.trim(),
    responseErrorMessage: async () => 'unavailable',
  };
  vm.createContext(context);
  vm.runInContext(source.slice(source.indexOf('let conanManifestDefaults'), source.indexOf('let blobStores')), context);
  for (const name of ['loadConanManifestDefaults', 'setConanManifestSettings', 'updateConanManifestSetting',
    'updateConanManifestHelp', 'repositoryFormPayload']) {
    const start = source.search(new RegExp(`(?:async )?function ${name}\\(`));
    const rest = source.slice(start);
    const end = rest.slice(1).search(/\n(?:async )?function /) + 1;
    vm.runInContext(rest.slice(0, end), context);
  }
  context.defaults = defaults;
  vm.runInContext('conanManifestDefaults = defaults', context);
  context.setConanManifestSettings();
  const change = (key, value, blur = false) => {
    context.key = key; context.value = value; context.blur = blur;
    vm.runInContext(`const field = conanManifestFields.find(f => f.key === key);
      document.getElementById(field.id).value = value;
      updateConanManifestSetting(field, blur);`.replace('const field', 'var field'), context);
  };
  const payload = () => JSON.parse(JSON.stringify(context.repositoryFormPayload()));
  return { context, element, change, payload };
}

test('new and existing inheriting repositories display resolved environment values without pinning them', () => {
  const { context, element, payload, change } = form();
  const id = 'repository-conan-manifest-max-entries';
  assert.equal(element(id).value, 50000);
  assert.equal(element('repository-conan-manifest-max-bytes').value, 16777216);
  assert.deepEqual(payload().conan, { manifestMaxEntries: null, manifestMaxBytes: null });
  assert.match(element(`${id}-help`).dataset.tooltip, /Current source: Server default/);
  assert.match(element(`${id}-help`).dataset.tooltip, /KKREPO_CONAN_MANIFEST_MAX_ENTRIES/);
  context.setConanManifestSettings({ manifestMaxEntries: null });
  change('manifestMaxEntries', '60000');
  assert.equal(payload().conan.manifestMaxEntries, 60000);
  assert.match(element(`${id}-help`).dataset.tooltip, /Source after saving: Repository override/);
  change('manifestMaxEntries', '50000');
  assert.equal(payload().conan.manifestMaxEntries, null);
});

test('independent explicit overrides, including equal-to-default values, roundtrip and can be cleared', () => {
  const { context, element, payload, change } = form();
  context.setConanManifestSettings({ manifestMaxEntries: 50000, manifestMaxBytes: 2000000 });
  assert.deepEqual(payload().conan, { manifestMaxEntries: 50000, manifestMaxBytes: 2000000 });
  assert.match(element('repository-conan-manifest-max-entries-help').dataset.tooltip, /Current source: Repository override/);
  change('manifestMaxEntries', '', true);
  assert.equal(element('repository-conan-manifest-max-entries').value, 50000);
  assert.deepEqual(payload().conan, { manifestMaxEntries: null, manifestMaxBytes: 2000000 });
  assert.match(element('repository-conan-manifest-max-entries-help').dataset.tooltip, /Source after saving: Server default/);
  context.setConanManifestSettings(payload().conan);
  assert.match(element('repository-conan-manifest-max-entries-help').dataset.tooltip, /Current source: Server default/);
});

test('other repository types do not submit Conan configuration', () => {
  const { context, payload } = form();
  for (const recipe of [{ format: 'conan', type: 'GROUP' }, { format: 'maven2', type: 'HOSTED' }]) {
    context.currentRecipe = () => recipe;
    assert.equal(Object.hasOwn(payload(), 'conan'), false);
  }
});

test('unavailable or invalid defaults never fabricate a value or overwrite saved settings', async () => {
  const { context, element, payload } = form();
  for (const response of [{ ok: false }, { ok: true, json: async () => ({}) },
    { ok: true, json: async () => ({ manifestMaxEntries: 0, manifestMaxBytes: 100 }) }]) {
    context.fetch = async () => response;
    await context.loadConanManifestDefaults();
    context.setConanManifestSettings({ manifestMaxEntries: 7922 });
    assert.equal(element('repository-conan-manifest-max-entries').value, 7922);
    assert.equal(element('repository-conan-manifest-max-bytes').value, '');
    assert.equal(element('repository-conan-manifest-max-bytes').disabled, true);
    assert.equal(Object.hasOwn(payload(), 'conan'), false);
    assert.match(element('repository-conan-manifest-max-bytes-help').dataset.tooltip, /unavailable/);
  }
  context.fetch = async () => ({ ok: true, json: async () => ({ manifestMaxEntries: 4096, manifestMaxBytes: 1048576 }) });
  await context.loadConanManifestDefaults();
  context.setConanManifestSettings();
  assert.equal(element('repository-conan-manifest-max-entries').value, 4096);
  assert.equal(element('repository-conan-manifest-max-entries').disabled, false);
});
