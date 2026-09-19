const assert = require('node:assert/strict');
const { readFileSync } = require('node:fs');
const { join } = require('node:path');
const test = require('node:test');
const vm = require('node:vm');
const source = readFileSync(join(__dirname, '../../main/resources/META-INF/resources/admin/assets/admin.js'), 'utf8');
const flush = () => new Promise(resolve => setImmediate(resolve));
function setup(fetch) {
  const elements = new Map();
  const element = id => {
    if (!elements.has(id)) elements.set(id, { value: '', innerHTML: '', textContent: '' });
    return elements.get(id);
  };
  const context = {
    document: { documentElement: { lang: 'en' }, getElementById: element, querySelectorAll: () => [] },
    fetch, escapeHtml: String, responseErrorMessage: async () => 'unavailable', showToast() {},
    renderBlobStores() { context.blobRenders++; }, renderRepositories() { context.repoRenders++; },
    refreshRepositoryBlobStoreOptions() {}, refreshRepositoryMemberOptions() {}, autoCheckBlobStores() {},
    lowerOrEmpty: value => String(value || '').toLowerCase(), repositoryDisplayUrl: () => '',
    blobRenders: 0, repoRenders: 0,
  };
  vm.createContext(context);
  vm.runInContext('let repositories = [], blobStores = [], repositoryUsage, blobStoreUsage; let repositoryLoadVersion = 0, blobStoreLoadVersion = 0; let repositorySort = {key:"name",direction:"asc"};\n'
    + source.slice(source.indexOf('function filteredRepositories()'), source.indexOf('function toggleRepositorySort('))
    + source.slice(source.indexOf('function inventoryValue('), source.indexOf('function blobStoreFormPayload()')), context);
  return { context, element, run: code => vm.runInContext(code, context) };
}
const response = value => ({ ok: true, json: async () => value });
test('distinguishes pending, failed, missing and zero usage; formats binary units and unknown sizes', () => {
  const { context: c } = setup();
  assert.match(c.renderInventoryMetric(undefined, 1, 'assetCount'), /…/);
  assert.match(c.renderInventoryMetric(null, 1, 'assetCount'), /—/);
  assert.match(c.renderInventoryMetric({usage:{}}, 1, 'assetCount'), /—/);
  assert.equal(c.renderInventoryMetric({usage:{1:{assetCount:0}}}, 1, 'assetCount'), '0');
  assert.equal(c.renderInventoryMetric({usage:{1:{assetCount:1000000}}}, 1, 'assetCount'), '1,000,000');
  assert.equal(c.formatInventoryBytes(0), '0 B');
  assert.equal(c.formatInventoryBytes(1024 ** 3), '1 GiB');
  assert.match(c.renderInventoryMetric({usage:{1:{totalBytes:1024,unknownSizeCount:1}}}, 1, 'totalBytes', true), /≥ 1 KiB/);
  assert.match(c.renderInventoryMetric({usage:{1:{assetCount:'injected'}}}, 1, 'assetCount'), /—/);
});
test('summary follows filtered rows and does not turn partial missing statistics into zero', () => {
  const { context: c, element } = setup();
  c.renderUsageSummary('repository', [{id:1}], {usage:{1:{assetCount:2,totalBytes:2048,unknownSizeCount:0},2:{assetCount:99,totalBytes:999}},calculatedAt:'2026-09-19T10:00:00Z'});
  assert.match(element('repository-usage-summary').innerHTML, /2 KiB/);
  assert.doesNotMatch(element('repository-usage-summary').innerHTML, /999/);
  c.renderUsageSummary('repository', [{id:1},{id:3}], {usage:{1:{assetCount:2,totalBytes:2048}},calculatedAt:'2026-09-19T10:00:00Z'});
  assert.match(element('repository-usage-summary').innerHTML, /—/);
});
for (const kind of ['repository', 'blobstore']) {
  test(`${kind} list renders before slow statistics and ignores a stale refresh`, async () => {
    const pending = [];
    const { context: c, run } = setup(async url => {
      if (url.endsWith('/statistics/usage')) return new Promise(resolve => pending.push(resolve));
      return response(kind === 'repository' ? [{id:1,name:'repo',blobStoreName:'store'}] : {stores:[{id:1,name:'store'}]});
    });
    const load = () => kind === 'repository' ? c.loadRepositories() : c.loadBlobStores();
    await load();
    assert.equal(c[kind === 'repository' ? 'repoRenders' : 'blobRenders'], 1);
    assert.equal(run(kind === 'repository' ? 'repositoryUsage' : 'blobStoreUsage'), undefined);
    await load();
    pending[1](response({usage:{1:{assetCount:20,blobCount:20}}}));
    await flush();
    pending[0](response({usage:{1:{assetCount:1,blobCount:1}}}));
    await flush();
    assert.equal(run(`${kind === 'repository' ? 'repositoryUsage' : 'blobStoreUsage'}.usage[1].${kind === 'repository' ? 'assetCount' : 'blobCount'}`), 20);
  });
  test(`${kind} failed statistics keep the configuration list usable`, async () => {
    const { context: c, run } = setup(async url => {
      if (url.endsWith('/statistics/usage')) throw new Error('offline');
      return response(kind === 'repository' ? [{id:1,name:'repo'}] : {stores:[{id:1,name:'store'}]});
    });
    await (kind === 'repository' ? c.loadRepositories() : c.loadBlobStores());
    await flush();
    assert.equal(run(kind === 'repository' ? 'repositories.length' : 'blobStores.length'), 1);
    assert.equal(run(kind === 'repository' ? 'repositoryUsage' : 'blobStoreUsage'), null);
  });
}
test('repository usage sorts numerically with unavailable rows last and filters exact store membership', () => {
  const { context: c, run, element } = setup();
  run('repositoryUsage = {usage:{1:{totalBytes:9},2:{totalBytes:100}}}; repositorySort={key:"totalBytes",direction:"desc"};');
  assert.equal(c.sortRepositories([{id:1,name:'one'},{id:2,name:'two'},{id:3,name:'three'}]).map(r=>r.id).join(','), '2,1,3');
  run('repositories=[{id:1,name:"one",blobStoreName:"store"},{id:2,name:"two",blobStoreName:"store-backup"}];');
  element('repository-store-filter').value='store';
  assert.equal(c.filteredRepositories().map(r=>r.id).join(','),'1');
});
