const test = require('node:test');
const assert = require('node:assert/strict');
const { ACTIONS, approval, formatBytes, reportOutcome, reportConflicts } =
  require('../../main/resources/META-INF/resources/admin/assets/staging.js');

test('staging actions follow the session lifecycle', () => {
  assert.deepEqual(ACTIONS.OPEN.map(([action]) => action), ['preflight', 'approve', 'close', 'cancel']);
  assert.deepEqual(ACTIONS.PREFLIGHTED.map(([action]) => action), ['promote', 'approve', 'preflight', 'cancel']);
  assert.deepEqual(ACTIONS.PROMOTED.map(([action]) => action), ['compensate']);
  assert.deepEqual(ACTIONS.CANCELLED, []);
  assert.ok(!ACTIONS.OPEN.some(([action]) => action === 'promote'));
});

test('approval state is read from the durable session attributes', () => {
  assert.equal(approval({attributes: {}}), 'not required');
  assert.equal(approval({attributes: {approvalRequired: true}}), 'required — pending');
  assert.equal(
    approval({attributes: {approvalRequired: true, approvedByUserId: 'reviewer'}}),
    'approved by reviewer');
});

test('sizes are rendered in binary units and missing sizes stay hidden', () => {
  assert.equal(formatBytes(null), '—');
  assert.equal(formatBytes(512), '512 B');
  assert.equal(formatBytes(2048), '2.0 KiB');
  assert.equal(formatBytes(5 * 1024 * 1024), '5.0 MiB');
});

test('preflight status rows are rendered and counted like promotion results', () => {
  const rows = [
    {repositoryId: 1, path: 'a/b.jar', action: 'ADD', status: 'CONFLICT', message: 'occupied'},
    {repositoryId: 1, path: 'a/c.jar', action: 'ADD', status: 'VALIDATED'},
    {groupRepositoryId: 2, memberRepositoryId: 3, action: 'ADD', status: 'CONFLICT'},
  ];
  assert.equal(reportOutcome(rows[0]), 'CONFLICT');
  assert.equal(reportConflicts(rows).length, 2);

  // Promotion rows keep their own vocabulary and take precedence over a stale status field.
  assert.equal(reportOutcome({result: 'published', status: 'CONFLICT'}), 'published');
  assert.equal(reportConflicts([{result: 'failed'}]).length, 1);
  assert.equal(reportOutcome({}), '—');
});
