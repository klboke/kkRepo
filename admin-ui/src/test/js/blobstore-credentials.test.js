const assert = require('node:assert/strict');
const { readFileSync } = require('node:fs');
const { join } = require('node:path');
const test = require('node:test');
const vm = require('node:vm');

const source = readFileSync(join(__dirname, '../../main/resources/META-INF/resources/admin/assets/admin.js'), 'utf8');
function form() {
  const elements = new Map();
  function element(id) {
    if (!elements.has(id)) elements.set(id, {
      id, value: '', dataset: {}, checked: true, disabled: false, required: false,
      classList: { remove() {}, toggle() {} },
      setAttribute() {}, focus() {},
    });
    return elements.get(id);
  }
  const context = {
    document: { getElementById: element, querySelectorAll: () => [] },
    updateRequiredMarker() {}, setFieldRequired: (input, required) => { input.required = required; },
    showToast() {}, openFormModal() {}, isFileBlobStore: store => store.engine === 'file', lowerOrEmpty: value => (value || '').toLowerCase(),
  };
  vm.createContext(context);
  vm.runInContext(`let blobStoreFormMode = 'create'; let editingBlobStoreId = null; let blobStores = [];\n`
    + source.slice(source.indexOf('const blobStoreS3RequiredFields'), source.indexOf('const repositoryRequiredFields'))
    + source.slice(source.indexOf('function blobStoreFormPayload()'), source.indexOf('function hideBlobStoreForm()')), context);
  context.showCreateBlobStoreForm();
  element('blobstore-name').value = 'test';
  element('blobstore-bucket').value = 'bucket';
  return { context, element, run: script => vm.runInContext(script, context) };
}

test('AWS default credentials allow blank keys and discard hidden stale inputs from payload', () => {
  const { context, element } = form();
  assert.equal(context.validateBlobStoreForm(), true);
  assert.equal(element('blobstore-access-key').required, false);
  assert.equal(element('blobstore-secret-key').disabled, true);
  element('blobstore-access-key').value = 'old-key';
  element('blobstore-secret-key').value = 'old-secret';
  const payload = context.blobStoreFormPayload();
  assert.equal(payload.credentialSource, 'default');
  assert.equal(payload.accessKey, '');
  assert.equal(payload.secretKey, '');
});

test('static credentials require a complete pair and preserve entered keys', () => {
  const { context, element } = form();
  element('blobstore-credential-source').value = 'static';
  context.refreshBlobStoreEngineControls();
  assert.equal(context.validateBlobStoreForm(), false);
  element('blobstore-access-key').value = 'key';
  assert.equal(context.validateBlobStoreForm(), false);
  element('blobstore-secret-key').value = 'secret';
  assert.equal(context.validateBlobStoreForm(), true);
  assert.equal(context.blobStoreFormPayload().secretKey, 'secret');
});

test('editing static credentials allows preserving secrets and explicitly switching to the default chain', () => {
  const { context, element, run } = form();
  run(`blobStores = [{ id: 1, name: 'existing', engine: 'aws-s3', endpoint: 'https://s3.us-east-1.amazonaws.com',
    region: 'us-east-1', bucket: 'bucket', credentialSource: 'static', accessKeyConfigured: true, secretConfigured: true }];`);
  context.showEditBlobStoreForm(1);
  assert.equal(context.validateBlobStoreForm(), true);
  assert.equal(context.blobStoreFormPayload().credentialSource, 'static');
  assert.equal(element('blobstore-secret-key').placeholder, 'Leave blank to keep existing');
  element('blobstore-credential-source').value = 'default';
  context.refreshBlobStoreEngineControls();
  assert.equal(context.validateBlobStoreForm(), true);
  assert.equal(context.blobStoreFormPayload().credentialSource, 'default');
});

test('switching an existing default store to static requires new keys', () => {
  const { context, element, run } = form();
  run(`blobStores = [{ id: 1, name: 'existing', engine: 'aws-s3', endpoint: 'https://s3.us-east-1.amazonaws.com',
    region: 'us-east-1', bucket: 'bucket', credentialSource: 'default', accessKeyConfigured: false, secretConfigured: false }];`);
  context.showEditBlobStoreForm(1);
  assert.equal(context.validateBlobStoreForm(), true);
  element('blobstore-credential-source').value = 'static';
  context.refreshBlobStoreEngineControls();
  assert.equal(context.validateBlobStoreForm(), false);
});

test('OSS Native forces static keys while file storage does not require S3 credentials', () => {
  const { context, element } = form();
  element('blobstore-engine').value = 'oss-native';
  context.refreshBlobStoreEngineControls();
  assert.equal(element('blobstore-credential-source').disabled, true);
  assert.equal(context.blobStoreFormPayload().credentialSource, 'static');
  assert.equal(context.validateBlobStoreForm(), false);
  element('blobstore-engine').value = 'file';
  context.refreshBlobStoreEngineControls();
  assert.equal(context.validateBlobStoreForm(), true);
  assert.equal(element('blobstore-secret-key').required, false);
});
