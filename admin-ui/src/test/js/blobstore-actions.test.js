const assert = require('node:assert/strict');
const { readFileSync } = require('node:fs');
const { join } = require('node:path');
const test = require('node:test');
const vm = require('node:vm');

const source = readFileSync(join(__dirname, '../../main/resources/META-INF/resources/admin/assets/admin.js'), 'utf8');
const index = readFileSync(join(__dirname, '../../main/resources/META-INF/resources/admin/index.html'), 'utf8');

function actionHarness() {
  const attributes = new Map();
  const menu = {
    hidden: true, innerHTML: '', style: {},
    setAttribute: (key, value) => attributes.set(key, value),
    removeAttribute: key => attributes.delete(key),
    getBoundingClientRect: () => ({ width: 190, height: 160 }),
    contains: element => items.includes(element),
  };
  const items = ['repositories', 'check', 'delete'].map(action => ({
    action, focus() { document.activeElement = this; },
  }));
  const trigger = {
    id: 'blobstore-more-actions-7', isConnected: true,
    setAttribute: (key, value) => attributes.set(`trigger-${key}`, value),
    getBoundingClientRect: () => ({ top: 100, bottom: 130, right: 400 }),
    focus() { document.activeElement = this; },
  };
  const document = {
    activeElement: trigger,
    getElementById: id => id === 'blobstore-action-menu' ? menu : null,
    querySelectorAll: selector => selector.startsWith('#blobstore-action-menu') ? items : [],
  };
  const requests = [];
  const toasts = [];
  const context = {
    document, window: { innerWidth: 800, innerHeight: 600 },
    confirm: () => true,
    fetch: async (path, options) => { requests.push([path, options]); return { ok: true }; },
    loadBlobStores: async () => {},
    responseErrorMessage: async () => 'in use',
    showToast: (message, type) => toasts.push([message, type]),
    closeCleanupPolicyActionMenu() {},
  };
  vm.createContext(context);
  vm.runInContext(`let blobStores = [{ id: 7, name: 'unused' }];
    let blobActionMenuStoreId = null, blobActionMenuTrigger = null;
    ${source.slice(source.indexOf('async function deleteBlobStore('), source.indexOf('// ---- Repository form'))}`, context);
  return { context, menu, trigger, document, attributes, requests, toasts };
}

test('the blob store action menu groups secondary actions and supports keyboard dismissal', () => {
  assert.match(index, /id="blobstore-action-menu" role="menu"/);
  const { context, menu, trigger, document, attributes } = actionHarness();
  context.openBlobStoreActionMenu(7, trigger, 'first');
  assert.equal(menu.hidden, false);
  assert.equal(attributes.get('trigger-aria-expanded'), 'true');
  assert.match(menu.innerHTML, /Repositories[\s\S]*Check[\s\S]*role="separator"[\s\S]*Delete blob store/);
  assert.equal(document.activeElement.action, 'repositories');
  const down = { key: 'ArrowDown', target: document.activeElement, preventDefault() {} };
  assert.equal(context.handleBlobStoreActionMenuKeydown(down), true);
  assert.equal(document.activeElement.action, 'check');
  const escape = { key: 'Escape', target: document.activeElement, preventDefault() {} };
  assert.equal(context.handleBlobStoreActionMenuKeydown(escape), true);
  assert.equal(menu.hidden, true);
  assert.equal(document.activeElement, trigger);
  assert.equal(attributes.get('trigger-aria-expanded'), 'false');
});

test('each store row keeps Edit visible and moves secondary actions into overflow', () => {
  const table = { innerHTML: '' };
  const context = {
    document: { getElementById: id => id === 'blobstore-table' ? table : null },
    closeBlobStoreActionMenu() {}, updateTableSortHeaders() {}, renderUsageSummary() {},
    filteredBlobStores: () => [{ id: 7, name: 'unused', type: 'file', engine: 'file', path: 'unused' }],
    sortBlobStores: rows => rows, isFileBlobStore: () => true,
    blobStoreIcon: () => '', engineLabel: () => 'File', healthBadge: () => '',
    renderInventoryMetric: () => '0', renderCopyableLocation: () => '',
    escapeHtml: String, pathStyleBadge: () => '',
  };
  vm.createContext(context);
  vm.runInContext(`let blobStoreSort = { key: 'name', direction: 'asc' }, blobStoreUsage = null;
    ${source.slice(source.indexOf('function renderBlobStores()'), source.indexOf('function renderCopyableLocation('))}`, context);
  context.renderBlobStores();
  assert.match(table.innerHTML, /edit-blobstore-button[^>]*>Edit<\/button>/);
  assert.match(table.innerHTML, /blobstore-more-actions[^>]*aria-haspopup="menu"/);
  assert.doesNotMatch(table.innerHTML, /store-repositories-button|check-blobstore-button|Delete blob store/);
});

test('deletion requires confirmation and sends only the configuration DELETE request', async () => {
  const { context, requests, toasts } = actionHarness();
  context.confirm = () => false;
  await context.deleteBlobStore(7);
  assert.equal(requests.length, 0);
  context.confirm = message => {
    assert.match(message, /Storage files and buckets are not deleted/);
    return true;
  };
  await context.deleteBlobStore(7);
  assert.equal(requests.length, 1);
  assert.equal(requests[0][0], '/internal/blob-stores/7');
  assert.equal(requests[0][1].method, 'DELETE');
  assert.match(toasts.at(-1)[0], /unused deleted/);
});
