const assert = require('node:assert/strict');
const { readFileSync } = require('node:fs');
const { join } = require('node:path');
const test = require('node:test');
const vm = require('node:vm');
const source = readFileSync(join(__dirname, '../../main/resources/META-INF/resources/admin/assets/admin.js'), 'utf8');

function setup(clipboard, copyResult = true) {
  const copied = [], messages = [];
  let removed = false, restoredFocus = false, selected = false;
  const textarea = { style: {}, select() { selected = true; }, remove() { removed = true; } };
  const context = {
    navigator: { clipboard }, window: { getSelection: () => null },
    document: {
      activeElement: { focus() { restoredFocus = true; } },
      createElement: () => textarea,
      body: { appendChild() {} },
      execCommand(command) {
        assert.equal(command, 'copy');
        assert.equal(selected, true);
        copied.push(textarea.value);
        if (copyResult instanceof Error) throw copyResult;
        return copyResult;
      },
    },
    showToast: (message, tone) => messages.push({message, tone}),
  };
  vm.createContext(context);
  vm.runInContext(source.slice(source.indexOf('function escapeHtml('), source.indexOf('\n}', source.indexOf('function escapeHtml(')) + 2)
    + source.slice(source.indexOf('function renderCopyableLocation('), source.indexOf('// undefined = loading;')), context);
  return { context, copied, messages, cleanup: () => ({removed, restoredFocus}) };
}

const value = '/data/目录 with spaces/' + 'long-segment/'.repeat(60) + 'file?x="quote"&y=<tag>#fragment';

test('modern clipboard copies the complete original value without fallback', async () => {
  const actual = [];
  const { context, copied, messages } = setup({ writeText: async text => actual.push(text) });
  await context.copyLocationValue({dataset:{copyValue:value}});
  assert.deepEqual(actual, [value]);
  assert.deepEqual(copied, []);
  assert.deepEqual(messages, [{message:'Copied',tone:'ok'}]);
});

test('HTTP or denied clipboard permission falls back to copying the full value and restores focus', async () => {
  for (const clipboard of [undefined, {writeText: async () => { throw new Error('denied'); }}]) {
    const { context, copied, cleanup, messages } = setup(clipboard);
    await context.copyLocationValue({dataset:{copyValue:value}});
    assert.deepEqual(copied, [value]);
    assert.deepEqual(cleanup(), {removed:true,restoredFocus:true});
    assert.equal(messages[0].tone, 'ok');
  }
});

test('failed copy never announces success and offers manual selection while cleaning up', async () => {
  for (const result of [false, new Error('blocked')]) {
    const { context, cleanup, messages } = setup(undefined, result);
    await context.copyLocationValue({dataset:{copyValue:value}});
    assert.deepEqual(cleanup(), {removed:true,restoredFocus:true});
    assert.equal(messages.length, 1);
    assert.equal(messages[0].tone, 'error');
    assert.match(messages[0].message, /Expand the value to select and copy it manually/);
  }
});

test('location values are escaped in attributes and text without truncating the underlying content', () => {
  const { context } = setup();
  const html = context.renderCopyableLocation(value, 'Copy URL');
  assert.ok(html.includes(`data-copy-value="${context.escapeHtml(value)}"`));
  assert.ok(html.includes(`<code class="usage-location-full">${context.escapeHtml(value)}</code>`));
  assert.doesNotMatch(html, /<tag>|x="quote"/);
  assert.doesNotMatch(context.renderCopyableLocation('', 'Copy URL'), /button|details/);
});
