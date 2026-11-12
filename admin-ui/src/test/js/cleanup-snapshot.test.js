const assert = require('node:assert/strict');
const { readFileSync } = require('node:fs');
const { join } = require('node:path');
const test = require('node:test');
const vm = require('node:vm');
const source = readFileSync(join(__dirname, '../../main/resources/META-INF/resources/admin/assets/admin.js'), 'utf8');

function harness() {
  const fields = new Map();
  const element = id => {
    if (!fields.has(id)) fields.set(id, { value: '', hidden: false, dataset: {}, setAttribute() {} });
    return fields.get(id);
  };
  const context = { document: { getElementById: element }, editingCleanupPolicyId: null,
    repositories: [{ id: 1, name: 'hosted', format: 'maven2', type: 'hosted' },
      { id: 2, name: 'proxy', format: 'maven2', type: 'proxy' },
      { id: 3, name: 'group', format: 'maven2', type: 'group' }],
    lowerOrEmpty: value => (value || '').toLowerCase(),
    cleanupCapability: () => ({ retainCountSupported: true, lastDownloadedSupported: true, executeSupported: true }),
    setCleanupFieldHelp() {}, cleanupSelectedRepositoryIds: () => [1],
    cleanupPolicyView: () => null, escapeHtml: value => String(value),
  };
  vm.createContext(context);
  for (const [start, end] of [['function cleanupRuleBadges(', 'function cleanupScheduleLabel('],
    ['function cleanupRepositoryCandidates(', 'function cleanupVisibleRepositories('],
    ['function refreshCleanupCapabilityHelp(', 'function setCleanupFieldHelp('],
    ['function cleanupOptionalNumber(', 'async function saveCleanupPolicy('],
    ['function cleanupRunRuleSnapshot(', 'function cleanupRunRepositorySnapshot(']]) {
    vm.runInContext(source.slice(source.indexOf(start), source.indexOf(end)), context);
  }
  element('cleanup-policy-format').value = 'maven2';
  element('cleanup-policy-mode').value = 'MAVEN_SNAPSHOT_BUILDS';
  element('cleanup-policy-retain-count').value = '5';
  element('cleanup-policy-published-days').value = '10';
  element('cleanup-policy-downloaded-days').value = '7';
  return { context, element };
}

test('snapshot rules use a nested discriminator while legacy payloads keep their contract', () => {
  const { context, element } = harness();
  const criteria = context.cleanupPolicyPayload().criteria;
  assert.equal(criteria.mode, 'MAVEN_SNAPSHOT_BUILDS');
  assert.equal(criteria.retainCount, undefined);
  assert.equal(criteria.snapshot.retainCount, 5);
  assert.equal(criteria.snapshot.publishedOlderThanDays, 10);
  assert.equal(criteria.snapshot.lastDownloadedOlderThanDays, 7);
  element('cleanup-policy-mode').value = 'COMPONENT';
  assert.equal(context.cleanupPolicyPayload().criteria.retainCount, 5);
  assert.equal(context.cleanupPolicyPayload().criteria.mode, undefined);
});

test('snapshot mode permits hosted repositories and requires at least one build', () => {
  const { context, element } = harness();
  assert.deepEqual(Array.from(context.cleanupRepositoryCandidates(), repo => repo.id), [1]);
  context.refreshCleanupCapabilityHelp();
  assert.equal(element('cleanup-policy-retain-count').min, '1');
  assert.equal(element('cleanup-policy-retain-count').required, true);
  assert.match(element('cleanup-policy-retain-label').textContent, /builds per SNAPSHOT/);
  element('cleanup-policy-mode').value = 'COMPONENT';
  context.refreshCleanupCapabilityHelp();
  assert.deepEqual(Array.from(context.cleanupRepositoryCandidates(), repo => repo.id), [1, 2]);
  assert.equal(element('cleanup-policy-retain-count').min, '0');
  assert.equal(element('cleanup-policy-retain-count').required, false);
});

test('changing to another format clears snapshot mode', () => {
  const { context, element } = harness();
  element('cleanup-policy-format').value = 'npm';
  context.refreshCleanupCapabilityHelp();
  assert.equal(element('cleanup-policy-mode').value, 'COMPONENT');
  assert.equal(element('cleanup-policy-mode-field').hidden, true);
  assert.equal(element('cleanup-policy-snapshot-help').hidden, true);
});

test('policy and run summaries display the nested snapshot rules', () => {
  const { context } = harness();
  const criteria = context.cleanupPolicyPayload().criteria;
  assert.match(context.cleanupRuleBadges(criteria), /snapshot builds/i);
  assert.match(context.cleanupRuleBadges(criteria), /10/);
  assert.match(context.cleanupRunRuleSnapshot(criteria), /5/);
  assert.match(context.cleanupRunRuleSnapshot(criteria), /builds/);
});
