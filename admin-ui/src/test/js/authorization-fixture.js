const declarations = require("../../../../scripts/ui/test-fixture.cjs")("admin");
const { readFileSync } = require('node:fs');
const { join } = require('node:path');
const vm = require('node:vm');

module.exports = function authorization(context, permissions = ['nexus:*']) {
  context.currentAdminPermissions = permissions;
  for (const name of ['permissionPartMatches', 'permissionMatches', 'canAdmin', 'canAccessAdminView', 'securityScanCanMutate']) {
    vm.runInContext(declarations(name), context);
  }
};
