const assert = require('node:assert/strict');
const { readFileSync } = require('node:fs');
const { join } = require('node:path');
const test = require('node:test');
const vm = require('node:vm');
const source = readFileSync(join(__dirname, '../../main/resources/META-INF/resources/admin/assets/admin.js'), 'utf8');

const policy = (id, revision, overrides = {}) => ({
  id, revision, name: 'default-audit', enabled: true, maxResultAgeSeconds: 604800, ...overrides,
});
const repository = (id = 5, config = {}) => ({
  id, name: `repo-${id}`, type: 'PROXY', profileName: 'syft-grype-v1',
  config: { profileId: 1, enabled: true, enforcementMode: 'AUDIT', ...config },
});

function setup(repositories = [repository()]) {
  const elements = new Map();
  function element(id) {
    if (!elements.has(id)) elements.set(id, {
      classList: { contains: () => true },
      value: '', textContent: '', innerHTML: '', dataset: {}, checked: false,
      options: ['', '86400', '604800', '2592000'].map(value => ({ value })),
      querySelectorAll: () => [], appendChild(option) { this.options.push(option); },
    });
    return elements.get(id);
  }
  const requests = [], saves = [], toasts = [], opened = [];
  const context = {
    securityScanState: { summary: { deploymentEnabled: true }, repositories, policies: [], repositoryPolicyOptions: [], repositoryEditRequest: 0 },
    SECURITY_SCAN_TABS: new Set(['overview', 'repositories', 'policies']),
    currentAdminPermissions: ['nexus:*'], updateCurrentSideGroup() {}, loadUiSettings() {},
    securityScanTabFromHash: () => 'repositories',
    securityScanPages: { repositories: { requestVersion: 0 } },
    securityScanListEndpoints: { repositories: 'repositories' },
    securityScanPageParams: () => new URLSearchParams(),
    document: { querySelectorAll: () => [], getElementById: element, createElement: () => ({ dataset: {} }) },
    fetchJson: async url => { requests.push(url); return { items: [policy(4, 4)] }; },
    fetch: async (url, options) => { saves.push({ url, payload: JSON.parse(options.body) }); return { ok: true }; },
    showToast: (...args) => toasts.push(args),
    openFormModal: (...args) => opened.push(args), closeFormModal() {}, loadSecurityScanning: async () => {},
  };
  vm.createContext(context);
  for (const [start, end] of [
    ['function escapeHtml(', 'function '],
    ['async function fetchSecurityScanPage(', 'function resetSecurityScanPage('],
    ['function switchView(', 'function applyHashRoute('],
    ['function selectSecurityScanTab(', 'function handleSecurityScanTabKeydown('],
    ['function formatSecurityScanValidity(', 'function showCreateSecurityScanPolicyForm('],
  ]) {
    const begin = source.indexOf(start), finish = source.indexOf(end, begin + start.length);
    assert.ok(begin >= 0 && finish > begin);
    vm.runInContext(source.slice(begin, finish), context);
  }
  return { context, element, requests, saves, toasts, opened };
}

test('options read every page independently of the policy tab and preserve assigned older revisions', async () => {
  const { context, requests } = setup();
  context.securityScanState.policies = [policy(999, 1, { name: 'filtered-tab' })];
  context.fetchJson = async url => {
    requests.push(url);
    return requests.length === 1
      ? { items: [policy(1, 1), policy(2, 2)], nextAfter: 2 }
      : { items: [policy(3, 3), policy(4, 4, { enabled: false }), policy(5, 1, { name: 'other' })] };
  };
  const options = await context.loadSecurityScanPolicyOptions(1);
  assert.deepEqual(Array.from(options, item => item.id), [4, 1, 5]);
  assert.deepEqual(requests, [
    '/internal/security/scanning/policies?limit=100&after=0',
    '/internal/security/scanning/policies?limit=100&after=2',
  ]);
});

test('unassigned repository stays unassigned until selection, then saves the selected policy and inherits its limit', async () => {
  const { context, element, saves } = setup();
  await context.editSecurityScanRepository(5);
  assert.equal(element('security-scan-policy-id').value, '');
  assert.match(element('security-scan-policy-help').textContent, /No policy is assigned/);
  assert.match(element('security-scan-validity-help').textContent, /Effective validity: No expiry/);
  element('security-scan-policy-id').value = '4';
  context.updateSecurityScanRepositoryPolicyHelp();
  assert.match(element('security-scan-validity-help').textContent, /Effective validity: 7 days/);
  await context.saveSecurityScanRepository({ preventDefault() {} });
  assert.equal(saves[0].payload.policyId, 4);
  assert.equal(saves[0].payload.maxResultAgeSeconds, null);
  assert.equal(saves[0].payload.scanHostedContent, false);
  assert.equal(saves[0].payload.scanProxyContent, true);
});

test('validity preview takes the shorter limit and ignores disabled policy age; clearing assignment saves null', async () => {
  const { context, element, saves } = setup([repository(5, { policyId: 4, maxResultAgeSeconds: 3600 })]);
  await context.editSecurityScanRepository(5);
  assert.equal(element('security-scan-max-age').value, '3600');
  assert.match(element('security-scan-validity-help').textContent, /Effective validity: 1 hour/);
  element('security-scan-max-age').value = '2592000';
  context.updateSecurityScanRepositoryPolicyHelp();
  assert.match(element('security-scan-validity-help').textContent, /Effective validity: 7 days/);
  context.securityScanState.repositoryPolicyOptions[0].enabled = false;
  context.updateSecurityScanRepositoryPolicyHelp();
  assert.match(element('security-scan-policy-help').textContent, /policy is disabled/);
  assert.match(element('security-scan-validity-help').textContent, /Effective validity: 30 days/);
  element('security-scan-policy-id').value = '';
  await context.saveSecurityScanRepository({ preventDefault() {} });
  assert.equal(saves[0].payload.policyId, null);
  assert.equal(saves[0].payload.maxResultAgeSeconds, 2592000);
});

test('unknown current assignment is retained, and names are escaped in option markup', async () => {
  const { context, element, saves } = setup([repository(5, { policyId: 99 })]);
  context.fetchJson = async () => ({ items: [policy(4, 4, { name: '<img src=x onerror="alert(1)">' })] });
  await context.editSecurityScanRepository(5);
  assert.equal(element('security-scan-policy-id').value, '99');
  assert.match(element('security-scan-policy-id').innerHTML, /&lt;img/);
  assert.doesNotMatch(element('security-scan-policy-id').innerHTML, /<img/);
  assert.match(element('security-scan-validity-help').textContent, /Effective validity is unavailable/);
  await context.saveSecurityScanRepository({ preventDefault() {} });
  assert.equal(saves[0].payload.policyId, 99);
});

test('policy load failure does not open a partially populated form', async () => {
  const { context, opened, toasts } = setup();
  context.fetchJson = async () => { throw new Error('Access denied'); };
  await context.editSecurityScanRepository(5);
  assert.equal(opened.length, 0);
  assert.match(toasts[0][0], /Access denied/);
});

test('a slower earlier load cannot reopen or overwrite the latest repository editor', async () => {
  const { context, element, opened } = setup([repository(5), repository(6)]);
  let resolveFirst;
  context.fetchJson = () => new Promise(resolve => { resolveFirst = resolve; });
  const first = context.editSecurityScanRepository(5);
  context.fetchJson = async () => ({ items: [] });
  await context.editSecurityScanRepository(6);
  resolveFirst({ items: [policy(4, 4)] });
  await first;
  assert.equal(element('security-scan-repository-id').value, 6);
  assert.equal(opened.length, 1);
  assert.equal(context.securityScanState.repositoryPolicyOptions.length, 0);
});


test('policy loading cannot open a modal after navigation or deployment disablement', async () => {
  for (const change of [
    ({ element }) => { element('security-scanning-view').classList.contains = () => false; },
    ({ context }) => { context.securityScanState.summary.deploymentEnabled = false; },
  ]) {
    const state = setup();
    let resolve;
    state.context.fetchJson = () => new Promise(done => { resolve = done; });
    const pending = state.context.editSecurityScanRepository(5);
    change(state);
    resolve({ items: [] });
    await pending;
    assert.equal(state.opened.length, 0);
  }
});


test('leaving the repository tab cancels pending loads even if the user returns before completion', async () => {
  for (const returnToRepositories of [false, true]) {
    const { context, opened } = setup();
    let resolve;
    context.fetchJson = () => new Promise(done => { resolve = done; });
    const pending = context.editSecurityScanRepository(5);
    context.selectSecurityScanTab('policies', { updateHash: false });
    if (returnToRepositories) context.selectSecurityScanTab('repositories', { updateHash: false });
    resolve({ items: [] });
    await pending;
    assert.equal(opened.length, 0, 'must not open a hidden or previously abandoned editor');
  }
});


test('leaving the scanning view cancels a pending editor even when browser history returns directly to repositories', async () => {
  const { context, opened } = setup();
  let resolve;
  context.fetchJson = () => new Promise(done => { resolve = done; });
  const pending = context.editSecurityScanRepository(5);
  context.switchView('ui-settings', { updateHash: false });
  context.switchView('security-scanning', { updateHash: false });
  resolve({ items: [] });
  await pending;
  assert.equal(opened.length, 0);
});

test('refreshing or paging the repository list cancels an editor based on the previous list', async () => {
  const { context, opened } = setup();
  let resolve;
  context.fetchJson = () => new Promise(done => { resolve = done; });
  const pending = context.editSecurityScanRepository(5);
  context.fetchJson = async () => ({ items: [] });
  await context.fetchSecurityScanPage('repositories');
  resolve({ items: [] });
  await pending;
  assert.equal(opened.length, 0);
});

test('closing the editor cancels a pending load and suppresses an abandoned request error', async () => {
  const { context, opened, toasts } = setup();
  let reject;
  context.fetchJson = () => new Promise((_, fail) => { reject = fail; });
  const pending = context.editSecurityScanRepository(5);
  context.hideSecurityScanRepositoryForm();
  reject(new Error('delayed request failure'));
  await pending;
  assert.equal(opened.length, 0);
  assert.equal(toasts.length, 0);
});
