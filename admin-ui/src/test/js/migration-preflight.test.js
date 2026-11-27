const declarations = require("../../../../scripts/ui/test-fixture.cjs")("admin");
const assert = require('node:assert/strict');
const test = require('node:test');
const vm = require('node:vm');

function render(security) {
  const result = { hidden: true, innerHTML: '' };
  const context = { document: { getElementById: () => result }, shortText: String };
  declarations.context(context);
  vm.runInContext(declarations('escapeHtml')
    + declarations(
      "renderCompactTable", "migrationTableCell", "renderMigrationSection", "renderMigrationList",
      "migrationValues", "renderMigrationProfile", "renderMigrationPlan", "renderMigrationPlanItems",
      "migrationScriptApiSummary", "migrationPlanStatusBadge", "renderMigrationHash",
      "renderMigrationUserTokens", "renderMigrationResult",
    ), context);
  context.renderMigrationResult({ security, warnings: ['Nexus Pro user tokens are not migrated; see security.userTokens'] }, 'Preflight result');
  return result.innerHTML;
}

const message = 'Nexus Repository Pro user tokens (User Token Realm) are not migrated by this version';

test('preflight shows the Nexus Pro user token count and owners', () => {
  const html = render({ apiKeys: 2, userTokens: {
    tokens: 2, owners: ['LDAP/bob', 'Local/alice'], legacyTableTokens: 0, legacyTableOwners: [], message,
    someFutureField: true } });
  assert.match(html, /Nexus Pro user tokens \(not migrated\)/);
  assert.match(html, /<td>2<\/td>/);
  assert.match(html, /<td>LDAP\/bob, Local\/alice<\/td>/);
  assert.match(html, new RegExp(message.replace(/[()]/g, '\\$&')));
});

test('preflight shows legacy user_token table owners when only those exist', () => {
  const html = render({ userTokens: { tokens: 0, owners: [], legacyTableTokens: 3, legacyTableOwners: ['carol'], message } });
  assert.match(html, /<td>3<\/td>/);
  assert.match(html, /<td>carol<\/td>/);
});

test('preflight marks legacy user_token owners it could not verify', () => {
  const html = render({ userTokens: { tokens: 0, owners: [], legacyTableTokens: 4, legacyTableOwners: ['carol'],
    legacyTableOwnersUnverified: true, message } });
  assert.match(html, /<td>4<\/td>/);
  assert.match(html, /<td>carol, unverified owners not shown<\/td>/);
  const onlyUnverified = render({ userTokens: { tokens: 0, owners: [], legacyTableTokens: 2, legacyTableOwners: [],
    legacyTableOwnersUnverified: true, message } });
  assert.match(onlyUnverified, /<td>unverified owners not shown<\/td>/);
});

test('preflight flags an unreadable legacy user_token table as an unknown count', () => {
  const html = render({ userTokens: {
    tokens: 0, owners: [], legacyTableTokens: 0, legacyTableOwners: [], legacyTableUnreadable: true, message } });
  assert.match(html, /Nexus Pro user tokens \(not migrated\)/);
  assert.match(html, /unreadable \(count unknown\)/);
});

test('preflight renders no user token section without user tokens', () => {
  for (const userTokens of [undefined, null, { tokens: 0, owners: [], legacyTableTokens: 0, legacyTableOwners: [], message: null }]) {
    assert.doesNotMatch(render({ userTokens }), /user tokens \(not migrated\)/);
  }
});

test('user token owners from the source Nexus are escaped', () => {
  const html = render({ userTokens: { tokens: 1, owners: ['Local/<script>alert(1)</script>'], message: '<img src=x>' } });
  assert.doesNotMatch(html, /<script>|<img/);
  assert.match(html, /Local\/&lt;script&gt;alert\(1\)&lt;\/script&gt;/);
});
