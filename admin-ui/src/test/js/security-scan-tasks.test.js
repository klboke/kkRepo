const assert = require('node:assert/strict');
const { readFileSync } = require('node:fs');
const { join } = require('node:path');
const test = require('node:test');
const vm = require('node:vm');
const source = readFileSync(join(__dirname, '../../main/resources/META-INF/resources/admin/assets/admin.js'), 'utf8');

function render(task) {
  const table = { innerHTML: '' };
  const context = {
    URLSearchParams,
    document: { getElementById: () => table },
    securityScanState: { tasks: [task] },
    securityScanTone: () => '',
    formatDateTime: () => '-'
  };
  vm.createContext(context);
  const escapeStart = source.indexOf('function escapeHtml(');
  vm.runInContext(
    source.slice(escapeStart, source.indexOf('\n}', escapeStart) + 2)
    + source.slice(source.indexOf('function renderSecurityScanTasks('), source.indexOf('function renderSecurityScanFindingRepositories('))
    + source.slice(source.indexOf('function securityScanFindingArtifactBrowseUrl('), source.indexOf('function securityScanExternalHttpUrl(')), context);
  context.renderSecurityScanTasks();
  return table.innerHTML;
}

const task = {
  id: 7, repositoryId: 1, repository: 'maven-hosted', assetId: 20,
  assetPath: 'com/acme/demo/1.0/demo-1.0.jar', browsePath: 'com/acme/demo/1.0/demo-1.0.jar',
  browseRepository: 'maven-hosted', status: 'FAILED', stage: 'CATALOG_AND_MATCH',
  reason: 'MANUAL', attempts: 1, maxAttempts: 5
};

test('task row exposes the asset path and browser link while retaining its ID and actions', () => {
  const html = render(task);
  assert.match(html, /com\/acme\/demo\/1\.0\/demo-1\.0\.jar<\/a>/);
  assert.match(html, /Asset ID: 20/);
  assert.match(html, /href="\/browse\/#browse\/browse:maven-hosted\?path=com%2Facme%2Fdemo%2F1.0%2Fdemo-1.0.jar"/);
  assert.match(html, /security-scan-task-retry/);
  assert.match(html, /security-scan-asset-rescan/);
});

test('group link retains the member source and safely encodes special characters', () => {
  const path = 'scope/demo & "<script>#?/%汉.whl';
  const html = render({ ...task, repository: 'source & repo', browseRepository: 'group #1', browsePath: path });
  const href = html.match(/href="([^"]+)"/)[1].replaceAll('&amp;', '&');
  assert.ok(href.startsWith('/browse/#browse/browse:group%20%231?'));
  const params = new URLSearchParams(href.slice(href.indexOf('?') + 1));
  assert.equal(params.get('path'), path);
  assert.equal(params.get('source'), 'source & repo');
  assert.ok(html.includes('&lt;script&gt;'));
  assert.ok(!html.includes('<script>'));
});

test('assets disappearing during lookup keep the returned ID without a broken browser link', () => {
  const html = render({ ...task, assetPath: null, browsePath: null, browseRepository: null });
  assert.match(html, /Asset ID: 20/);
  assert.match(html, /Asset unavailable/);
  assert.ok(!html.includes('href='));
});

test('deletion-nulled asset references show unavailable without inventing an ID or offering a rescan', () => {
  const html = render({ ...task, assetId: null, assetPath: null, browsePath: null, browseRepository: null });
  assert.ok(html.includes('Asset unavailable'));
  assert.ok(!html.includes('Asset ID:'));
  assert.ok(!html.includes('security-scan-asset-rescan'));
});

test('unavailable browse context leaves the path readable without an inaccessible link', () => {
  const html = render({ ...task, browsePath: null, browseRepository: null });
  assert.ok(html.includes(task.assetPath));
  assert.ok(!html.includes('href='));
});
