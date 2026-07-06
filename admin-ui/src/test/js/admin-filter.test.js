const assert = require("node:assert/strict");
const { readFileSync } = require("node:fs");
const { join } = require("node:path");
const test = require("node:test");
const vm = require("node:vm");

const filterScript = join(__dirname, "../../main/resources/META-INF/resources/admin/assets/admin-filter.js");
const filters = require(filterScript);

test("keeps plain contains matching case insensitive", () => {
  assert.equal(
    filters.matchesFilter(["nx-repository-view-maven2-maven-public-read"], "MAVEN-PUBLIC"),
    true
  );
  assert.equal(
    filters.matchesFilter(["nx-repository-view-maven2-maven-public-read"], "maven-dcmes"),
    false
  );
});

test("uses star as an ordered wildcard between search fragments", () => {
  assert.equal(
    filters.matchesFilter(["nx-repository-view-maven2-maven-aiot-add"], "view*aiot"),
    true
  );
  assert.equal(
    filters.matchesFilter(["nx-repository-view-maven2-maven-aiot-add"], "view**aiot"),
    true
  );
  assert.equal(
    filters.matchesFilter(["nx-repository-view-maven2-maven-ja-tdd-read"], "view*maven*tdd"),
    true
  );
  assert.equal(
    filters.matchesFilter(["nx-repository-view-maven2-maven-tdd-ja-read"], "view*maven*ja*tdd"),
    false
  );
});

test("matches Nexus repository privilege examples", () => {
  assert.equal(
    filters.matchesFilter(["nx-repository-view-maven2-maven-dcmes-add"], "maven-dcmes-*"),
    true
  );
  assert.equal(
    filters.matchesFilter(["nx-repository-view-maven2-maven-dcmes-add"], "*add"),
    true
  );
  assert.equal(
    filters.matchesFilter(["nx-repository-view-maven2-maven-dcmes-delete"], "*add"),
    false
  );
});

test("escapes regexp metacharacters in user input", () => {
  assert.equal(
    filters.matchesFilter(["nx-repository-view-maven2-maven.dcmes-add"], "maven.dcmes-*"),
    true
  );
  assert.equal(
    filters.matchesFilter(["nx-repository-view-maven2-mavenXdcmes-add"], "maven.dcmes-*"),
    false
  );
});

test("joins all searchable fields before wildcard matching", () => {
  assert.equal(
    filters.matchesFilter(
      [
        "nx-repository-view-maven2-team-dcmes-maven-group-*",
        "All privileges for team-dcmes-maven-group repository views"
      ],
      "view*team*group"
    ),
    true
  );
});

test("exports the same browser global API", () => {
  const source = readFileSync(filterScript, "utf8");
  const context = {};
  vm.runInNewContext(source, context);

  assert.equal(
    context.kkrepoAdminFilters.matchesFilter(["nx-repository-view-maven2-maven-aiot-read"], "view*aiot"),
    true
  );
});
