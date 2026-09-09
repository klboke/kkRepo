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

test('loading selectors with Object prototype names keeps every row and its own references', async () => {
  const fs = require('node:fs');
  const vm = require('node:vm');
  const source = fs.readFileSync(require.resolve('../../main/resources/META-INF/resources/admin/assets/content-selectors.js'), 'utf8');
  const names = ['constructor', 'toString', 'hasOwnProperty', '__proto__'];
  for (const references of [{}, JSON.parse('{"constructor":["constructor-read"],"__proto__":["legacy-read"]}')]) {
    const elements = new Map();
    const makeElement = () => ({
      value: '', textContent: '', children: [],
      append(child) { this.children.push(child); },
      replaceChildren(...children) { this.children = children; },
      addEventListener() {}
    });
    const context = vm.createContext({
      document: {
        getElementById(id) {
          if (!elements.has(id)) elements.set(id, makeElement());
          return elements.get(id);
        },
        createElement: makeElement
      },
      bindFormModalDismiss() {},
      fetch: async (url) => ({
        ok: true, status: 200, headers: { get: () => null },
        text: async () => JSON.stringify(url.endsWith('/options')
          ? { permissions: { read: true, delete: true }, repositories: [], usedBy: references }
          : names.map(name => ({ name, type: 'csel', expression: 'path == "/team"' })))
      })
    });
    vm.runInContext(source, context);
    await context.KkContentSelectors.load();
    assert.equal(elements.get('selector-status').textContent, '');
    const rows = elements.get('selector-table').children;
    assert.equal(rows.length, names.length);
    names.forEach((name, index) => {
      const expected = Object.hasOwn(references, name) ? references[name] : [];
      assert.equal(rows[index].children[4].children[0].textContent, expected.join(', ') || '—');
      assert.equal(rows[index].children[5].children[2].disabled, expected.length > 0);
    });
  }
});
