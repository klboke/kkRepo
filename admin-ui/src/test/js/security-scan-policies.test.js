const assert = require('node:assert/strict');
const { readFileSync } = require('node:fs');
const { join } = require('node:path');
const test = require('node:test');
const vm = require('node:vm');
const source = readFileSync(join(__dirname, '../../main/resources/META-INF/resources/admin/assets/admin.js'), 'utf8');

function setup(confirm = true, response = { ok: true }) {
  const calls = [];
  const table = { innerHTML: '' };
  const context = {
    securityScanState: { policies: [{ id: 4, name: 'default-audit', revision: 4, blockSeverity: 'HIGH' }] },
    window: { confirm: message => { calls.push(['confirm', message]); return confirm; } },
    document: { getElementById: () => table },
    fetch: async (url, options) => { calls.push(['fetch', url, options.method]); return response; },
    responseErrorMessage: async response => response.message,
    showToast: (...args) => calls.push(['toast', ...args]),
    resetSecurityScanPage: key => calls.push(['reset', key]),
    loadSecurityScanList: async key => calls.push(['reload', key]),
    formatSecurityScanValidity: () => '7 days',
  };
  vm.createContext(context);
  for (const [start, end] of [
    ['function escapeHtml(', 'function '],
    ['function renderSecurityScanPolicies(', 'function renderSecurityScanWaivers('],
    ['async function deleteSecurityScanPolicy(', 'function securityScanWaiverTargetLabel('],
  ]) {
    const begin = source.indexOf(start);
    const finish = source.indexOf(end, begin + start.length);
    assert.ok(begin >= 0 && finish > begin);
    vm.runInContext(source.slice(begin, finish), context);
  }
  return { context, calls, table };
}

test('policy rows expose a delete action and escape stored names', () => {
  const { context, table } = setup();
  context.securityScanState.policies[0].name = '<img onerror=alert(1)>';
  context.renderSecurityScanPolicies();
  assert.match(table.innerHTML, /security-scan-policy-delete/);
  assert.match(table.innerHTML, /Use its latest revision/);
  assert.doesNotMatch(table.innerHTML, /<img/);
  const begin = source.indexOf('document.getElementById("security-scan-policy-table").addEventListener');
  const handler = source.slice(begin, source.indexOf('\n});', begin) + 4);
  assert.match(handler, /deleteSecurityScanPolicy\(deleteButton.dataset.id, deleteButton\)/);
});

test('cancelled or unavailable policy never sends a delete request', async () => {
  const { context, calls } = setup(false);
  await context.deleteSecurityScanPolicy(4);
  await context.deleteSecurityScanPolicy(999);
  assert.equal(calls.length, 1);
  assert.match(calls[0][1], /default-audit.*ALL its revisions/);
});

test('204 success resets only the policy cursor and reloads without forcing navigation', async () => {
  const { context, calls } = setup();
  const button = { disabled: false };
  await context.deleteSecurityScanPolicy(4, button);
  assert.deepEqual(calls.slice(1), [
    ['fetch', '/internal/security/scanning/policies/4', 'DELETE'],
    ['toast', 'Security scan policy and all revisions deleted.', 'ok'],
    ['reset', 'policies'], ['reload', 'policies'],
  ]);
  assert.equal(button.disabled, false);
});

for (const status of [403, 404, 409, 500]) {
  test(`HTTP ${status} preserves the list and displays the server explanation`, async () => {
    const { context, calls } = setup(true, { ok: false, status, message: 'policy is referenced' });
    const button = { disabled: false };
    await context.deleteSecurityScanPolicy(4, button);
    assert.deepEqual(calls.at(-1), ['toast', 'Policy deletion failed: policy is referenced', 'error']);
    assert.ok(!calls.some(call => call[0] === 'reload' || call[0] === 'reset'));
    assert.equal(context.securityScanState.policies.length, 1);
    assert.equal(button.disabled, false);
  });
}

test('duplicate clicks are ignored while deletion is pending and network failures reenable the action', async () => {
  const { context, calls } = setup();
  let reject;
  context.fetch = () => new Promise((resolve, fail) => { reject = fail; });
  const button = { disabled: false };
  const pending = context.deleteSecurityScanPolicy(4, button);
  await context.deleteSecurityScanPolicy(4, button);
  assert.equal(calls.filter(call => call[0] === 'confirm').length, 1);
  assert.equal(button.disabled, true);
  reject(new Error('offline'));
  await pending;
  assert.equal(button.disabled, false);
  assert.deepEqual(calls.at(-1), ['toast', 'Policy deletion failed: offline', 'error']);
});
