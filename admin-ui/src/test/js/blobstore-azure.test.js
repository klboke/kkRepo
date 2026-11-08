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
      id, value: '', dataset: {}, checked: true, disabled: false, required: false, textContent: '',
      classList: { remove() {}, toggle() {} },
      setAttribute() {}, focus() {},
    });
    return elements.get(id);
  }
  const toasts = [];
  const context = {
    document: { getElementById: element, querySelectorAll: () => [] },
    updateRequiredMarker() {}, setFieldRequired: (input, required) => { input.required = required; },
    showToast: message => toasts.push(message), openFormModal() {},
    isFileBlobStore: store => store.engine === 'file',
    isAzureBlobStore: store => (store.engine || '').startsWith('azure'),
    lowerOrEmpty: value => (value || '').toLowerCase(),
  };
  vm.createContext(context);
  vm.runInContext(`let blobStoreFormMode = 'create'; let editingBlobStoreId = null; let blobStores = [];\n`
    + source.slice(source.indexOf('const blobStoreS3RequiredFields'), source.indexOf('const repositoryRequiredFields'))
    + source.slice(source.indexOf('function blobStoreFormPayload()'), source.indexOf('function hideBlobStoreForm()')), context);
  context.showCreateBlobStoreForm();
  return { context, element, toasts, run: script => vm.runInContext(script, context) };
}

function azureForm() {
  const harness = form();
  harness.element('blobstore-name').value = 'azure-artifacts';
  harness.element('blobstore-engine').value = 'azure';
  harness.element('blobstore-account-name').value = 'myaccount';
  harness.element('blobstore-bucket').value = 'artifacts';
  harness.context.refreshBlobStoreEngineControls();
  return harness;
}

test('an Azure store posts the azure type with the container in the bucket field', () => {
  const { context, element } = azureForm();
  element('blobstore-prefix').value = 'team/eu';

  const payload = context.blobStoreFormPayload();

  assert.equal(context.validateBlobStoreForm(), true);
  assert.equal(payload.type, 'azure');
  assert.equal(payload.engine, 'azure');
  assert.equal(payload.accountName, 'myaccount');
  assert.equal(payload.bucket, 'artifacts');
  assert.equal(payload.prefix, 'team/eu');
});

test('Azure payloads omit S3-only settings', () => {
  const { context, element } = azureForm();
  element('blobstore-region').value = 'us-east-1';
  element('blobstore-access-key').value = 'leftover-key';

  const payload = context.blobStoreFormPayload();

  assert.equal(payload.region, undefined);
  assert.equal(payload.accessKey, undefined);
  assert.equal(payload.secretKey, undefined);
  assert.equal(payload.pathStyleAccess, undefined);
});

test('the default credential mode is secretless and requires no key', () => {
  const { context, element } = azureForm();

  assert.equal(context.blobStoreAzureCredentialType(), 'default');
  assert.equal(context.validateBlobStoreForm(), true);
  assert.equal(element('blobstore-azure-account-key').disabled, true);
  assert.equal(element('blobstore-azure-sas-token').disabled, true);
  const payload = context.blobStoreFormPayload();
  assert.equal(payload.credentialType, 'default');
  assert.equal(payload.accountKey, '');
  assert.equal(payload.sasToken, '');
});

test('the account name and container are required', () => {
  const { context, element } = azureForm();
  element('blobstore-account-name').value = '';
  assert.equal(context.validateBlobStoreForm(), false);

  element('blobstore-account-name').value = 'myaccount';
  element('blobstore-bucket').value = '';
  assert.equal(context.validateBlobStoreForm(), false);
});

test('the endpoint is optional because Azure derives it from the account name', () => {
  const { context, element } = azureForm();
  element('blobstore-endpoint').value = '';

  assert.equal(context.validateBlobStoreForm(), true);
});

test('managed identity sends only a client id', () => {
  const { context, element } = azureForm();
  element('blobstore-azure-credential-type').value = 'managed-identity';
  element('blobstore-azure-client-id').value = '11111111-1111-1111-1111-111111111111';
  element('blobstore-azure-tenant-id').value = 'ignored-tenant';
  context.refreshBlobStoreEngineControls();

  const payload = context.blobStoreFormPayload();

  assert.equal(context.validateBlobStoreForm(), true);
  assert.equal(payload.credentialType, 'managed-identity');
  assert.equal(payload.clientId, '11111111-1111-1111-1111-111111111111');
  assert.equal(payload.tenantId, '', 'tenant id is not used by managed identity');
  assert.equal(payload.accountKey, '');
});

test('workload identity sends the federated token inputs', () => {
  const { context, element } = azureForm();
  element('blobstore-azure-credential-type').value = 'workload-identity';
  element('blobstore-azure-client-id').value = 'client';
  element('blobstore-azure-tenant-id').value = 'tenant';
  element('blobstore-azure-token-file').value = '/var/run/secrets/token';
  context.refreshBlobStoreEngineControls();

  const payload = context.blobStoreFormPayload();

  assert.equal(context.validateBlobStoreForm(), true, 'the webhook may supply every value');
  assert.equal(payload.credentialType, 'workload-identity');
  assert.equal(payload.clientId, 'client');
  assert.equal(payload.tenantId, 'tenant');
  assert.equal(payload.tokenFilePath, '/var/run/secrets/token');
});

test('switching a new store to Azure drops the S3 placeholder endpoint and restores it for S3', () => {
  const { context, element } = form();
  assert.equal(element('blobstore-endpoint').value, 'http://127.0.0.1:9000');

  element('blobstore-engine').value = 'azure';
  context.refreshBlobStoreEngineControls();
  // Token credentials over plain HTTP are refused by the Azure SDK.
  assert.equal(element('blobstore-endpoint').value, '');
  assert.equal(context.blobStoreFormPayload().endpoint, '');

  element('blobstore-engine').value = 'aws-s3';
  context.refreshBlobStoreEngineControls();
  assert.equal(element('blobstore-endpoint').value, 'http://127.0.0.1:9000');
});

test('an explicit Azure endpoint is kept', () => {
  const { context, element } = azureForm();
  element('blobstore-endpoint').value = 'https://myaccount.privatelink.blob.core.windows.net';
  context.refreshBlobStoreEngineControls();

  assert.equal(context.blobStoreFormPayload().endpoint, 'https://myaccount.privatelink.blob.core.windows.net');
});

test('the client id hint matches the selected credential mode', () => {
  const { context, element } = azureForm();
  assert.match(element('blobstore-azure-client-id').placeholder, /AZURE_CLIENT_ID/);

  element('blobstore-azure-credential-type').value = 'managed-identity';
  context.refreshBlobStoreEngineControls();
  assert.match(element('blobstore-azure-client-id').placeholder, /system-assigned/);
});

test('the default chain keeps the tenant but not a token file path it cannot use', () => {
  const { context, element } = azureForm();
  element('blobstore-azure-tenant-id').value = 'tenant';
  element('blobstore-azure-token-file').value = '/var/run/secrets/token';
  context.refreshBlobStoreEngineControls();

  const payload = context.blobStoreFormPayload();

  // DefaultAzureCredential only reads AZURE_FEDERATED_TOKEN_FILE, so the field is not offered.
  assert.equal(element('blobstore-azure-token-file').disabled, true);
  assert.equal(element('blobstore-azure-tenant-id').disabled, false);
  assert.equal(payload.tenantId, 'tenant');
  assert.equal(payload.tokenFilePath, '');
});

test('account key mode requires the key and sends it', () => {
  const { context, element } = azureForm();
  element('blobstore-azure-credential-type').value = 'account-key';
  context.refreshBlobStoreEngineControls();
  assert.equal(context.validateBlobStoreForm(), false);

  element('blobstore-azure-account-key').value = 'c2VjcmV0';

  assert.equal(context.validateBlobStoreForm(), true);
  assert.equal(context.blobStoreFormPayload().accountKey, 'c2VjcmV0');
  assert.equal(context.blobStoreFormPayload().sasToken, '');
});

test('SAS mode requires the token and sends it', () => {
  const { context, element } = azureForm();
  element('blobstore-azure-credential-type').value = 'sas';
  context.refreshBlobStoreEngineControls();
  assert.equal(context.validateBlobStoreForm(), false);

  element('blobstore-azure-sas-token').value = 'sv=2024-01-01&sig=abc';

  assert.equal(context.validateBlobStoreForm(), true);
  assert.equal(context.blobStoreFormPayload().sasToken, 'sv=2024-01-01&sig=abc');
  assert.equal(context.blobStoreFormPayload().accountKey, '');
});

test('switching away from a secret mode clears the secret from the payload', () => {
  const { context, element } = azureForm();
  element('blobstore-azure-credential-type').value = 'account-key';
  element('blobstore-azure-account-key').value = 'c2VjcmV0';
  context.refreshBlobStoreEngineControls();
  assert.equal(context.blobStoreFormPayload().accountKey, 'c2VjcmV0');

  element('blobstore-azure-credential-type').value = 'workload-identity';
  context.refreshBlobStoreEngineControls();

  // The server rejects a stored secret alongside a platform identity, so the UI must not send one.
  assert.equal(context.blobStoreFormPayload().accountKey, '');
  assert.equal(context.validateBlobStoreForm(), true);
});

test('editing an Azure store restores its credential mode and keeps an existing secret', () => {
  const { context, element, run } = form();
  run(`blobStores = [{ id: 4, name: 'azure-artifacts', type: 'azure', engine: 'azure',
    accountName: 'myaccount', bucket: 'artifacts', prefix: '', credentialSource: 'account-key',
    accountKeyConfigured: true }];`);

  context.showEditBlobStoreForm(4);

  assert.equal(element('blobstore-engine').value, 'azure');
  assert.equal(element('blobstore-azure-credential-type').value, 'account-key');
  assert.equal(element('blobstore-account-name').value, 'myaccount');
  assert.equal(element('blobstore-azure-account-key').placeholder, 'Leave blank to keep existing');
  // A configured secret may be retained by leaving the field blank.
  assert.equal(context.validateBlobStoreForm(), true);
});

test('editing an Azure store focuses the account name field', () => {
  const { context } = form();

  assert.equal(
    context.blobStoreInitialFocusField({ type: 'azure', engine: 'azure' }),
    'blobstore-account-name');
  assert.equal(
    context.blobStoreInitialFocusField({ type: 'file', engine: 'file' }),
    'blobstore-path');
  assert.equal(
    context.blobStoreInitialFocusField({ type: 's3', engine: 'aws-s3' }),
    'blobstore-endpoint');
});

test('stored Azure engine aliases normalize onto the azure option', () => {
  const { context } = form();

  assert.equal(context.normalizeBlobStoreEngine('azure'), 'azure');
  assert.equal(context.normalizeBlobStoreEngine('azure-default'), 'azure');
  assert.equal(context.normalizeBlobStoreEngine('azure-workload-identity'), 'azure');
  assert.equal(context.normalizeBlobStoreEngine('oss-native'), 'oss-native');
  assert.equal(context.normalizeBlobStoreEngine('file'), 'file');
  assert.equal(context.normalizeBlobStoreEngine('anything-else'), 'aws-s3');
});

test('the bucket label reads Container for Azure and Bucket for S3', () => {
  const { context, element } = azureForm();
  assert.equal(element('blobstore-bucket-label').textContent, 'Container');

  element('blobstore-engine').value = 'aws-s3';
  context.refreshBlobStoreEngineControls();

  assert.equal(element('blobstore-bucket-label').textContent, 'Bucket');
});
