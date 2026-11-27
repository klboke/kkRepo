const assert = require("node:assert/strict");
const { test } = require("node:test");
const { AUTH_SNAPSHOT_KEY, readSessionSnapshot, saveSessionSnapshot } = require("#ui-shared/session-snapshot.js");

test("both shells can read the previous session snapshot format and discard expired snapshots", t => {
  const values = new Map();
  t.mock.method(Date, "now", () => 1_000_000);
  global.sessionStorage = {
    getItem: key => values.get(key),
    setItem: (key, value) => values.set(key, value),
    removeItem: key => values.delete(key),
  };
  t.after(() => delete global.sessionStorage);
  const snapshot = { session: { userId: "reader" }, permissions: ["nx:read"], savedAt: Date.now() };
  saveSessionSnapshot(snapshot.session, snapshot.permissions);
  assert.deepEqual(readSessionSnapshot(), snapshot);
  for (const savedAt of [0, Date.now() + 1]) {
    values.set(AUTH_SNAPSHOT_KEY, JSON.stringify({ ...snapshot, savedAt }));
    assert.equal(readSessionSnapshot(), null);
    assert.equal(values.size, 0);
  }
  delete global.sessionStorage;
  assert.doesNotThrow(() => saveSessionSnapshot(snapshot.session, snapshot.permissions));
  assert.equal(readSessionSnapshot(), null);
});
