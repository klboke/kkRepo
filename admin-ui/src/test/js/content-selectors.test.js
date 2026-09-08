const test = require('node:test');
const assert = require('node:assert/strict');
const { privilegePayload } = require('../../main/resources/META-INF/resources/admin/assets/content-selectors.js');
const repos = [{ name: 'team-group', format: 'maven2', type: 'group' }];
test('selector privilege maps repository and format scopes to the Nexus REST contract', () => {
  assert.deepEqual(privilegePayload('team-read', 'Team', 'team', 'team-group', ['browse', 'read'], repos), {
    name: 'team-read', description: 'Team', contentSelector: 'team', format: 'maven2', repository: 'team-group', actions: ['browse', 'read']
  });
  const allFormat = privilegePayload('n', '', 's', '*-raw', ['read'], repos);
  assert.equal(allFormat.repository, '*'); assert.equal(allFormat.format, 'raw');
  const all = privilegePayload('n', '', 's', '*', ['read'], repos);
  assert.equal(all.repository, '*'); assert.equal(all.format, '*');
});
test('invalid privilege scopes cannot silently widen to all repositories', () => {
  assert.throws(() => privilegePayload('n', '', 's', 'deleted', ['read'], repos), /no longer exists/);
  assert.throws(() => privilegePayload('n', '', 's', '*', [], repos), /at least one/);
});
