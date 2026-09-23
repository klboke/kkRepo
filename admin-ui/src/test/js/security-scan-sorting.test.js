const assert = require('node:assert/strict');
const {readFileSync} = require('node:fs');
const {join} = require('node:path');
const test = require('node:test');
const vm = require('node:vm');
const source = readFileSync(join(__dirname, '../../main/resources/META-INF/resources/admin/assets/admin.js'), 'utf8');

function setup() {
  const requests = [];
  const context = {
    URLSearchParams,
    document: {querySelector: () => null},
    fetchJson: async url => {
      requests.push(new URL(url, 'https://kkrepo.test').searchParams);
      return {items: [{id: 99}], nextCursor: 'opaque-boundary'};
    }
  };
  vm.createContext(context);
  vm.runInContext(source.slice(source.indexOf('let securityScanState ='), source.indexOf('let securityScanPolicyFormMode'))
    + source.slice(source.indexOf('function securityScanPageParams('), source.indexOf('function renderSecurityScanning()')), context);
  context.renderSecurityScanList = () => {};
  return {c: context, requests, run: code => vm.runInContext(code, context)};
}

test('task and run requests explicitly default to descending completion time', async () => {
  const {c, requests} = setup();
  for (const [key, field] of [['tasks', 'finished_at'], ['runs', 'completed_at']]) {
    await c.loadSecurityScanList(key);
    const params = requests.at(-1);
    assert.equal(params.get('sort'), field);
    assert.equal(params.get('direction'), 'desc');
    assert.equal(params.has('after'), false);
    assert.equal(params.has('cursor'), false);
  }
  assert.equal(c.securityScanPageParams('findings').get('after'), '0');
});

test('next and previous use opaque cursors and changing order resets pagination', async () => {
  const {c, requests, run} = setup();
  await c.loadSecurityScanList('tasks');
  await c.moveSecurityScanPage('tasks', 'next');
  assert.equal(requests.at(-1).get('cursor'), 'opaque-boundary');
  assert.equal(run('securityScanPages.tasks.page'), 1);
  await c.moveSecurityScanPage('tasks', 'prev');
  assert.equal(requests.at(-1).has('cursor'), false);
  await c.moveSecurityScanPage('tasks', 'next');
  await c.sortSecurityScanPage('tasks', 'asc');
  assert.equal(requests.at(-1).get('direction'), 'asc');
  assert.equal(requests.at(-1).has('cursor'), false);
  assert.equal(run('securityScanPages.tasks.page'), 0);
  assert.equal(run('securityScanPages.tasks.cursors.length'), 1);
});

test('search and page size changes reset the cursor but retain sort direction', async () => {
  const {c, requests} = setup();
  await c.sortSecurityScanPage('runs', 'asc');
  await c.moveSecurityScanPage('runs', 'next');
  c.document.querySelector = () => ({value: ' failed '});
  await c.searchSecurityScanList('runs');
  assert.equal(requests.at(-1).get('q'), 'failed');
  assert.equal(requests.at(-1).get('direction'), 'asc');
  assert.equal(requests.at(-1).has('cursor'), false);
  await c.moveSecurityScanPage('runs', 'next');
  await c.resizeSecurityScanPage('runs', 25);
  assert.equal(requests.at(-1).get('limit'), '25');
  assert.equal(requests.at(-1).get('q'), 'failed');
  assert.equal(requests.at(-1).has('cursor'), false);
  assert.equal(requests.at(-1).get('direction'), 'asc');
});

test('a slower previous request cannot overwrite a newly selected sort order', async () => {
  const {c, run} = setup();
  const pending = [];
  c.fetchJson = () => new Promise(resolve => pending.push(resolve));
  const oldRequest = c.sortSecurityScanPage('tasks', 'asc');
  const newRequest = c.sortSecurityScanPage('tasks', 'desc');
  pending[1]({items: [{id: 2}], nextCursor: 'new-boundary'});
  await newRequest;
  pending[0]({items: [{id: 1}], nextCursor: 'old-boundary'});
  await oldRequest;
  assert.equal(run('securityScanState.tasks[0].id'), 2);
  assert.equal(run('securityScanPages.tasks.nextAfter'), 'new-boundary');
  assert.equal(run('securityScanPages.tasks.direction'), 'desc');
});
