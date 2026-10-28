const assert = require('node:assert/strict');
const { readFileSync } = require('node:fs');
const { resolve } = require('node:path');
const test = require('node:test');
const vm = require('node:vm');
const source = readFileSync(resolve(__dirname, '../../main/resources/META-INF/resources/admin/assets/admin.js'), 'utf8');
const browse = readFileSync(resolve(__dirname, '../../../../browse-ui/src/main/resources/META-INF/resources/browse/assets/browse.js'), 'utf8');
function extract(name, text = source) {
  const start = text.indexOf(`function ${name}(`);
  assert.ok(start >= 0);
  return text.slice(text.slice(start - 6, start) === 'async ' ? start - 6 : start,
    text.indexOf('\n}\n', start) + 3);
}
function setup(permissions = ['nexus:security-scanning:read']) {
  const elements = new Map();
  const writes = [];
  const policyButton = { hidden: true, disabled: false, dataset: { scanPermission: 'nexus:security-scanning:update' } };
  const refresh = { disabled: false, dataset: {} };
  function element(id) {
    if (!elements.has(id)) elements.set(id, {
      innerHTML: '', value: '1', classList: { toggle() {} }, setAttribute() {}, replaceChildren() {},
      querySelectorAll: selector => selector === '[data-scan-permission]' ? [policyButton]
        : selector === 'button, input, select, textarea' ? [policyButton, refresh] : [],
    });
    return elements.get(id);
  }
  const context = vm.createContext({
    securityScanState: { summary: { deploymentEnabled: true },
      tasks: [{ id: 1, assetId: 1, status: 'FAILED', canManage: false, canRescan: false }],
      repositories: [{ id: 1, canConfigure: false, config: {} }],
      findings: [{ id: 1, canCreateWaiver: false }],
      policies: [{ id: 1, latestRevision: true }], waivers: [{ id: 1, canDelete: false }],
    },
    document: { getElementById: element }, URLSearchParams,
    formatDateTime: () => '-', formatSecurityScanValidity: () => '-',
    securityScanTone: () => '', bindFieldHelpTooltips() {},
    fetch: async (...args) => { writes.push(args); throw new Error('Unexpected request'); },
  });
  require('./authorization-fixture')(context, permissions);
  for (const name of ['escapeHtml', 'applySecurityScanDeploymentState', 'renderSecurityScanTasks',
    'renderSecurityScanTaskAsset', 'renderSecurityScanFindingWaiverAction',
    'renderSecurityScanFindingActions', 'renderSecurityScanPolicies', 'renderSecurityScanWaivers',
    'editSecurityScanRepository', 'saveSecurityScanRepository', 'showCreateSecurityScanPolicyForm',
    'showEditSecurityScanPolicyForm', 'saveSecurityScanPolicy', 'deleteSecurityScanPolicy',
    'showCreateSecurityScanWaiverForm', 'createSecurityScanWaiver', 'deleteSecurityScanWaiver',
    'securityScanTaskAction', 'securityScanRescan']) vm.runInContext(extract(name), context);
  return { context, element, writes, policyButton, refresh };
}

test('readers keep detail and browse controls but see no mutation actions', () => {
  const { context, element } = setup();
  context.renderSecurityScanTasks();
  context.renderSecurityScanPolicies();
  context.renderSecurityScanWaivers();
  assert.doesNotMatch(element('security-scan-task-table').innerHTML, /task-retry|task-cancel|asset-rescan/);
  assert.doesNotMatch(element('security-scan-policy-table').innerHTML, /policy-edit|policy-delete/);
  assert.doesNotMatch(element('security-scan-waiver-table').innerHTML, /waiver-delete/);
  const actions = context.renderSecurityScanFindingActions({ id: 1, canCreateWaiver: false });
  assert.match(actions, /finding-view/);
  assert.doesNotMatch(actions, /finding-waive/);
});

test('deployment pending disabled and available transitions cannot reveal reader write controls', () => {
  const { context, policyButton, refresh } = setup();
  for (const [enabled, options] of [[false, { pending: true }], [false, {}], [true, {}]]) {
    context.applySecurityScanDeploymentState(enabled, options);
    assert.equal(policyButton.hidden, true);
    assert.equal(refresh.disabled, !enabled);
  }
});

test('update and waiver grants remain independent and deployment must also be enabled', () => {
  const { context, policyButton } = setup(['nexus:security-scanning:read,update']);
  context.applySecurityScanDeploymentState(true);
  assert.equal(policyButton.hidden, false);
  assert.equal(context.securityScanCanMutate(), true);
  assert.equal(context.securityScanCanMutate('nexus:security-scanning-waivers:create'), false);
  context.currentAdminPermissions = ['nexus:security-scanning:read', 'nexus:security-scanning-waivers:create'];
  context.applySecurityScanDeploymentState(true);
  assert.equal(policyButton.hidden, true);
  assert.equal(context.securityScanCanMutate(), false);
  assert.equal(context.securityScanCanMutate('nexus:security-scanning-waivers:create'), true);
  context.securityScanState.summary.deploymentEnabled = false;
  assert.equal(context.securityScanCanMutate('nexus:security-scanning-waivers:create'), false);
});

test('direct mutation handlers send no requests for a reader', async () => {
  const { context, writes } = setup();
  const event = { preventDefault() {} };
  await context.editSecurityScanRepository(1);
  await context.saveSecurityScanRepository(event);
  context.showCreateSecurityScanPolicyForm();
  context.showEditSecurityScanPolicyForm(1);
  await context.saveSecurityScanPolicy(event);
  await context.deleteSecurityScanPolicy(1);
  await context.showCreateSecurityScanWaiverForm(1);
  await context.createSecurityScanWaiver(event);
  await context.deleteSecurityScanWaiver(1);
  await context.securityScanTaskAction('retry', 1);
  await context.securityScanRescan(1);
  assert.deepEqual(writes, []);
});

for (const [permissions, expected] of [
  [[], false], [['nexus:security-scanning:read'], true], [['nexus:security-scanning:read,update'], true],
  [['nexus:selectors:read'], true], [['nexus:*'], true], [['nexus:security-scanning:*'], true],
  [['nexus:security-scanning:update'], false], [['nexus:security-scanning-waivers:create'], false],
]) {
  test(`Browse Admin entry agrees with Admin routing for ${permissions}`, () => {
    const { context } = setup(permissions);
    const browseContext = vm.createContext({ currentPermissions: permissions });
    for (const name of ['permissionPartMatches', 'permissionMatches', 'can', 'hasAdminEntryPermission'])
      vm.runInContext(extract(name, browse), browseContext);
    assert.equal(browseContext.hasAdminEntryPermission(), expected);
    assert.equal(context.canAccessAdminView('security-scanning')
      || context.canAccessAdminView('content-selectors'), expected);
  });
}
