const assert = require('node:assert/strict');
const {readFileSync} = require('node:fs');
const {join} = require('node:path');
const test = require('node:test');
const vm = require('node:vm');
const source = readFileSync(join(__dirname, '../../main/resources/META-INF/resources/admin/assets/admin.js'), 'utf8');
function setup() {
  const requests = [];
  const c = {URLSearchParams, document: {querySelector: () => ({value: ' app '})},
    hideSecurityScanFindingDetail: () => {},
    fetchJson: async url => {
      requests.push(new URL(url, 'https://kkrepo.test').searchParams);
      return {items: [{id: 41}], nextAfter: 41};
    }};
  vm.createContext(c);
  require("./authorization-fixture")(c);
  vm.runInContext(source.slice(source.indexOf('let securityScanState ='), source.indexOf('let securityScanPolicyFormMode'))
    + source.slice(source.indexOf('function securityScanPageParams('), source.indexOf('function renderSecurityScanning()')), c);
  c.renderSecurityScanList = () => {};
  return {c, requests, run: code => vm.runInContext(code, c)};
}
test('current is the UI default and toggling resets pagination while retaining search and size', async () => {
  const {c, requests, run} = setup();
  await c.loadSecurityScanList('findings');
  assert.equal(requests.at(-1).get('currentOnly'), 'true');
  await c.searchSecurityScanList('findings');
  await c.resizeSecurityScanPage('findings', 25);
  await c.moveSecurityScanPage('findings', 'next');
  await c.filterCurrentSecurityScanFindings(false);
  assert.equal(requests.at(-1).get('currentOnly'), 'false');
  assert.equal(requests.at(-1).get('q'), 'app');
  assert.equal(requests.at(-1).get('limit'), '25');
  assert.equal(requests.at(-1).get('after'), '0');
  assert.equal(run('securityScanPages.findings.page'), 0);
  assert.equal(run('securityScanPages.findings.cursors.length'), 1);
  await c.clearSecurityScanListSearch('findings');
  assert.equal(requests.at(-1).get('currentOnly'), 'false');
  await c.filterCurrentSecurityScanFindings(true);
  assert.equal(requests.at(-1).get('currentOnly'), 'true');
});
test('late historical response cannot replace a newly selected current view', async () => {
  const {c, run} = setup();
  const pending = [];
  c.fetchJson = () => new Promise(resolve => pending.push(resolve));
  const old = c.filterCurrentSecurityScanFindings(false);
  const current = c.filterCurrentSecurityScanFindings(true);
  pending[1]({items: [{id: 42}], nextAfter: null});
  await current;
  pending[0]({items: [{id: 41}], nextAfter: 41});
  await old;
  assert.equal(run('securityScanState.findings[0].id'), 42);
  assert.equal(run('securityScanPages.findings.nextAfter'), null);
});
test('artifact details keep the selected filter throughout their pagination', async () => {
  const {c, run} = setup();
  vm.runInContext('let securityScanFindingArtifactDetail = null;'
    + source.slice(source.indexOf('async function loadSecurityScanFindingArtifacts('), source.indexOf('function showSecurityScanFindingDetail(')), c);
  c.renderSecurityScanFindingArtifactItems = () => {};
  const requests = [];
  c.fetch = async url => {
    requests.push(new URL(url, 'https://kkrepo.test').searchParams);
    return {ok: true, json: async () => ({items: [], nextRepositoryId: 11, nextAssetId: 23})};
  };
  await c.loadSecurityScanFindingArtifacts(41, true);
  run('securityScanPages.findings.currentOnly = false');
  await c.loadSecurityScanFindingArtifacts(41);
  assert.equal(requests[0].get('currentOnly'), 'true');
  assert.equal(requests[1].get('currentOnly'), 'true');
  assert.equal(requests[1].get('afterAssetId'), '23');
  await c.loadSecurityScanFindingArtifacts(41, true);
  assert.equal(requests.at(-1).get('currentOnly'), 'false');
});
test('waiver details and target selection carry the selected current or historical view', async () => {
  const {c, run} = setup();
  run('securityScanState.summary = {deploymentEnabled: true}; securityScanState.findings = [{id: 41, canCreateWaiver: true}]');
  c.document.getElementById = () => ({reset: () => {}});
  c.openFormModal = c.clearRequiredFieldErrors = c.showToast = () => {};
  c.responseErrorMessage = async () => 'fixture stops before rendering';
  vm.runInContext('let securityScanWaiverContext = null; const securityScanWaiverRequiredFields = [];'
    + source.slice(source.indexOf('async function showSecurityScanWaiverDetail('), source.indexOf('function hideSecurityScanWaiverDetail('))
    + source.slice(source.indexOf('async function showCreateSecurityScanWaiverForm('), source.indexOf('function hideSecurityScanWaiverForm(')), c);
  const requests = [];
  c.fetch = async url => {requests.push(new URL(url, 'https://kkrepo.test')); return {ok: false};};
  for (const currentOnly of [true, false]) {
    run(`securityScanPages.findings.currentOnly = ${currentOnly}`);
    await c.showSecurityScanWaiverDetail(41);
    await c.showCreateSecurityScanWaiverForm(41);
    assert.equal(requests.at(-2).pathname, '/internal/security/scanning/findings/41/waivers');
    assert.equal(requests.at(-1).pathname, '/internal/security/scanning/findings/41/waiver-context');
    assert.equal(requests.at(-2).searchParams.get('currentOnly'), String(currentOnly));
    assert.equal(requests.at(-1).searchParams.get('currentOnly'), String(currentOnly));
  }
});
