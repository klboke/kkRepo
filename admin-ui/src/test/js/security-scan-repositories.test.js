const assert = require('node:assert/strict');
const { readFileSync } = require('node:fs');
const { join } = require('node:path');
const test = require('node:test');
const vm = require('node:vm');
const source = readFileSync(join(__dirname, '../../main/resources/META-INF/resources/admin/assets/admin.js'), 'utf8');

function setup(repository, deploymentEnabled = true) {
  const table = { innerHTML: '', querySelectorAll: () => [] };
  const popover = { hidden: false };
  const context = {
    securityScanState: { repositories: repository ? [repository] : [], summary: { deploymentEnabled } },
    document: { getElementById: id => id === 'field-help-popover' ? popover : table },
    showFieldHelpPopover: () => { popover.hidden = false; },
    hideFieldHelpPopover: () => { popover.hidden = true; },
    scheduleFieldHelpPopoverHide() {},
  };
  vm.createContext(context);
  for (const [start, end] of [
    ['function escapeHtml(', 'function ',],
    ['function renderSecurityScanRepositoryStatus(', 'function renderSecurityScanPolicies('],
    ['function formatSecurityScanValidity(', 'function setSecurityScanResultValidity('],
    ['function bindFieldHelpTrigger(', 'function bindFieldHelpTooltips('],
  ]) {
    const begin = source.indexOf(start);
    const finish = source.indexOf(end, begin + start.length);
    assert.ok(begin >= 0 && finish > begin, `Source section ${start}`);
    vm.runInContext(source.slice(begin, finish), context);
  }
  return { context, table, popover };
}
const repository = overrides => ({
  id: 1, name: 'maven-group', format: 'MAVEN2', type: 'GROUP',
  profileName: 'default', policyName: 'critical', policyEnabled: true,
  resultValidity: { maxResultAgeSeconds: 86400, source: 'POLICY' },
  config: { policyId: 1, enabled: true, enforcementMode: 'ENFORCE', pendingAction: 'BLOCK', failureAction: 'ALLOW', partialAction: 'BLOCK' },
  ...overrides,
});

test('repository rows display resolved validity without requiring the policy listing and keep eight columns', () => {
  const { context, table } = setup(repository());
  context.renderSecurityScanRepositories();
  assert.equal((table.innerHTML.match(/<td[ >]/g) || []).length, 8);
  assert.match(table.innerHTML, />1 day</);
  assert.match(table.innerHTML, /Inherited from the assigned scan policy/);
  assert.match(table.innerHTML, /Format: MAVEN2. Type: GROUP/);
  assert.doesNotMatch(table.innerHTML, /<td>MAVEN2<\/td>/);
  assert.match(table.innerHTML, /Pending: Block/);
  assert.match(table.innerHTML, /Failure: Allow/);
  assert.match(table.innerHTML, /Partial: Block/);
});

test('validity distinguishes no expiry, missing API data, repository limits and combined limits', () => {
  const { context } = setup();
  for (const [source, age, label, detail] of [
    ['NO_EXPIRY', null, 'No expiry', 'does not mean the artifact is safe'],
    ['REPOSITORY', 604800, '7 days', 'Set by the repository'],
    ['BOTH', 3600, '1 hour', 'shorter of the repository'],
  ]) {
    const html = context.renderSecurityScanResultValidity(repository({ resultValidity: { maxResultAgeSeconds: age, source } }));
    assert.ok(html.includes(label));
    assert.ok(html.includes(detail));
  }
  assert.match(context.renderSecurityScanResultValidity({}), /Unavailable/);
  assert.doesNotMatch(context.renderSecurityScanResultValidity({}), /No expiry/);
});

test('audit and disabled scanning identify configured actions without promising blocking', () => {
  for (const [enabled, mode, deployment, expected] of [
    [true, 'AUDIT', true, 'Block (audit)'],
    [false, 'ENFORCE', true, 'Block (inactive)'],
    [true, 'ENFORCE', false, 'Block (inactive)'],
  ]) {
    const row = repository();
    row.config.enabled = enabled;
    row.config.enforcementMode = mode;
    const { context } = setup(row, deployment);
    const html = context.renderSecurityScanExceptionHandling(row);
    assert.ok(html.includes(`Pending: ${expected}`));
    assert.ok(html.includes('Configured action: Block'));
    assert.match(html, /Other applicable repository policies may still block/);
  }
});

test('disabled policy bypasses partial checks but preserves pending and failure actions', () => {
  const row = repository({ policyEnabled: false });
  const { context, table } = setup(row);
  context.renderSecurityScanRepositories();
  assert.match(table.innerHTML, /critical \(disabled\)/);
  assert.match(table.innerHTML, /Pending: Block/);
  assert.match(table.innerHTML, /Partial: Allow/);
  assert.match(table.innerHTML, /partial-result checks are bypassed/);
  assert.match(table.innerHTML, /Allow continues vulnerability evaluation/);
});

test('repository names and tooltip text are HTML escaped, and empty results retain the column span', () => {
  const { context, table } = setup(repository({ name: '<img src=x onerror="alert(1)">', type: '" onfocus="alert(2)' }));
  context.renderSecurityScanRepositories();
  assert.doesNotMatch(table.innerHTML, /<img|Type: " onfocus=/);
  assert.match(table.innerHTML, /&lt;img/);
  assert.match(table.innerHTML, /tabindex="0" role="note"/);
  context.securityScanState.repositories = [];
  context.renderSecurityScanRepositories();
  assert.match(table.innerHTML, /colspan="8"/);
});

test('newly rendered tooltips bind hover, focus and Escape dismissal on every page render', () => {
  const { context, table, popover } = setup(repository());
  let events;
  const trigger = { addEventListener: (type, listener) => { events[type] = listener; } };
  table.querySelectorAll = () => [trigger];
  for (let render = 0; render < 2; render++) {
    events = {};
    context.renderSecurityScanRepositories();
    assert.equal(typeof events.mouseenter, 'function');
    assert.equal(typeof events.blur, 'function');
    events.focus();
    assert.equal(popover.hidden, false);
    let prevented = false, stopped = false;
    events.keydown({ key: 'Escape', preventDefault() { prevented = true; }, stopPropagation() { stopped = true; } });
    assert.equal(popover.hidden, true);
    assert.equal(prevented, true);
    assert.equal(stopped, true);
  }
});

test('an unassigned repository explains the built-in fallback instead of implying a named policy', () => {
  const row = repository({ policyName: null, config: { enabled: true }, resultValidity: { source: 'NO_EXPIRY' } });
  const { context, table } = setup(row);
  context.renderSecurityScanRepositories();
  assert.match(table.innerHTML, /Built-in rules \(unassigned\)/);
  assert.match(table.innerHTML, /not a policy record in the Policies tab/);
  assert.match(table.innerHTML, /No policy is assigned; the built-in rules have no age limit/);
});
