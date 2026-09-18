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

test('Escape dismisses credential help before closing the form and preserves unsaved values', () => {
  const { context, element } = form();
  const listeners = new Map();
  const trigger = element('blobstore-credential-help');
  const popover = element('field-help-popover');
  const modal = element('blobstore-form-modal');
  trigger.dataset.tooltip = 'Default AWS credentials';
  trigger.addEventListener = (type, listener) => listeners.set(type, listener);
  trigger.removeAttribute = () => {};
  trigger.getBoundingClientRect = () => ({ left: 100, top: 100, bottom: 118, width: 18 });
  popover.hidden = true;
  popover.style = {};
  popover.addEventListener = () => {};
  popover.getBoundingClientRect = () => ({ width: 200, height: 60 });
  modal.hidden = false;
  modal.dataset.formId = 'blobstore-form';
  context.document.querySelectorAll = selector => selector === '.field-help' ? [trigger] : [];
  context.document.addEventListener = () => {};
  context.window = { innerWidth: 1200, innerHeight: 800, addEventListener() {} };
  context.clearTimeout = clearTimeout;
  context.activeFormModal = () => modal.hidden ? null : modal;
  context.dismissFormModal = formId => {
    assert.equal(formId, 'blobstore-form');
    modal.hidden = true;
    element('blobstore-name').value = '';
  };
  vm.runInContext(`let activeFieldHelpTrigger = null; let fieldHelpHideTimer = null;\n`
    + source.slice(source.indexOf('function clearFieldHelpHideTimer()'), source.indexOf('function refreshCleanupScheduleFields()'))
    + source.slice(source.indexOf('function handleFormModalKeydown(event)'), source.indexOf('const blobStoreS3RequiredFields')), context);
  context.bindFieldHelpTooltips();

  function press(key) {
    const event = {
      key, defaultPrevented: false, propagationStopped: false,
      preventDefault() { this.defaultPrevented = true; },
      stopPropagation() { this.propagationStopped = true; },
    };
    listeners.get('keydown')(event);
    if (!event.propagationStopped) context.handleFormModalKeydown(event);
    return event;
  }

  element('blobstore-name').value = 'unsaved-store';
  listeners.get('focus')();
  assert.equal(popover.hidden, false);
  assert.equal(press('Enter').defaultPrevented, false);
  assert.equal(popover.hidden, false);

  const firstEscape = press('Escape');
  assert.equal(popover.hidden, true);
  assert.equal(modal.hidden, false, 'dismissing the tooltip must leave the form open');
  assert.equal(element('blobstore-name').value, 'unsaved-store');
  assert.equal(firstEscape.defaultPrevented, true);

  const secondEscape = press('Escape');
  assert.equal(secondEscape.propagationStopped, false);
  assert.equal(modal.hidden, true, 'Escape must still close the form once the tooltip is hidden');
});
