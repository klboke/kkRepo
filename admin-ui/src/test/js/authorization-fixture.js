const { readFileSync } = require('node:fs');
const { join } = require('node:path');
const vm = require('node:vm');
const source = readFileSync(join(__dirname, '../../main/resources/META-INF/resources/admin/assets/admin.js'), 'utf8');
module.exports = function authorization(context, permissions = ['nexus:*']) {
  context.currentAdminPermissions = permissions;
  for (const name of ['permissionPartMatches', 'permissionMatches', 'canAdmin', 'canAccessAdminView', 'securityScanCanMutate']) {
    const start = source.indexOf(`function ${name}(`);
    vm.runInContext(source.slice(start, source.indexOf('\n}\n', start) + 3), context);
  }
};
