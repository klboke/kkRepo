const assert = require('node:assert/strict');
const { readFileSync, readdirSync } = require('node:fs');
const { join } = require('node:path');
const test = require('node:test');
const vm = require('node:vm');

const assetsDir = join(__dirname, '../../main/resources/META-INF/resources/login/assets');
const engine = readFileSync(join(assetsDir, 'ui-i18n.js'), 'utf8');
const dictionaryDir = join(assetsDir, 'i18n');

function load({ defaultLanguage = 'browser', browserLanguages = ['en-US'] } = {}) {
  const appended = [];
  const element = () => ({
    nodeType: 1, tagName: 'DIV', childNodes: [], hasAttribute: () => false, closest: () => null,
    appendChild(child) { appended.push(child); }, setAttribute() {}, getAttribute: () => null
  });
  const store = { 'kkrepo.uiSettings': JSON.stringify({ defaultLanguage }) };
  const context = {
    console,
    Node: { TEXT_NODE: 3, ELEMENT_NODE: 1 },
    localStorage: { getItem: (key) => store[key] ?? null, setItem: (key, value) => { store[key] = value; } },
    navigator: { language: browserLanguages[0], languages: browserLanguages },
    fetch: () => Promise.reject(new Error('offline')),
    MutationObserver: class { observe() {} },
    CustomEvent: class { constructor(type, init) { this.type = type; this.detail = init?.detail; } },
    document: {
      readyState: 'complete', title: 'kkrepo', body: element(), head: element(),
      documentElement: { lang: '', ...element() },
      createElement: () => ({}), addEventListener() {}
    }
  };
  context.window = context;
  const listeners = {};
  context.window.addEventListener = (type, listener) => { (listeners[type] ||= []).push(listener); };
  context.window.dispatchEvent = (event) => { (listeners[event.type] || []).forEach((listener) => listener(event)); return true; };
  context.window.alert = () => {};
  context.window.confirm = () => true;
  vm.createContext(context);
  vm.runInContext(engine, context);
  return { i18n: context.window.kkrepoI18n, appended, context };
}

function placeholders(value) {
  return (String(value).match(/\{\w+\}/g) || []).sort();
}

test('the browser language picks the closest supported UI language', () => {
  const cases = [
    [['fr-CA'], 'fr'], [['pt-PT'], 'pt-BR'], [['zh-TW'], 'zh-CN'], [['xx-YY', 'de-AT'], 'de'],
    [['ja'], 'ja'], [['PT-br'], 'pt-BR'], [['xx'], 'en'], [['en-GB'], 'en']
  ];
  for (const [browserLanguages, expected] of cases) {
    assert.equal(load({ browserLanguages }).i18n.currentLanguage(), expected, browserLanguages.join(','));
  }
});

test('a stored default language is normalised', () => {
  assert.equal(load({ defaultLanguage: 'pt_br' }).i18n.currentLanguage(), 'pt-BR');
  assert.equal(load({ defaultLanguage: 'KO' }).i18n.currentLanguage(), 'ko');
  assert.equal(load({ defaultLanguage: 'xx' }).i18n.currentLanguage(), 'en');
});

test('a language dictionary is loaded on demand and then applied', () => {
  const { i18n, appended, context } = load({ defaultLanguage: 'fr' });
  assert.equal(i18n.text('Default language'), 'Default language', 'English until the dictionary is loaded');
  const script = appended.find((node) => String(node.src || '').startsWith('/login/assets/i18n/fr.js?v='));
  assert.ok(script, 'the French dictionary script is requested');
  vm.runInContext(readFileSync(join(dictionaryDir, 'fr.js'), 'utf8'), context);
  script.onload();
  assert.equal(i18n.text('Default language'), 'Langue par défaut');
  assert.match(i18n.text('Page 7'), /7/);
});

test('every language file covers every reference key with the same placeholders', () => {
  const reference = load({ defaultLanguage: 'zh-CN' });
  const files = readdirSync(dictionaryDir).filter((name) => name.endsWith('.js'));
  const languages = reference.i18n.languages().filter((language) => language !== 'en' && language !== 'zh-CN');
  assert.deepEqual(files.map((name) => name.slice(0, -3)).sort(), [...languages].sort());
  // The Chinese dictionary is the reference catalogue of English keys.
  const keys = [];
  const captured = {};
  const source = engine.slice(engine.indexOf('const zh = {'), engine.indexOf('\n  };\n', engine.indexOf('const zh = {')) + 4);
  Object.assign(captured, vm.runInNewContext(`(${source.slice('const zh = '.length).trim().replace(/;$/, '')})`));
  keys.push(...Object.keys(captured));
  for (const language of languages) {
    const { i18n, context } = load({ defaultLanguage: language });
    let registered;
    context.window.kkrepoI18n.registerDictionary = (code, dictionary) => { registered = { code, dictionary }; };
    vm.runInContext(readFileSync(join(dictionaryDir, `${language}.js`), 'utf8'), context);
    assert.equal(registered?.code, language, `${language}.js registers itself`);
    const missing = keys.filter((key) => typeof registered.dictionary[key] !== 'string' || !registered.dictionary[key].trim());
    assert.deepEqual(missing.slice(0, 5), [], `${language}: ${missing.length} missing key(s)`);
    for (const key of keys) {
      assert.deepEqual(placeholders(registered.dictionary[key]), placeholders(key), `${language}: ${key}`);
    }
    assert.ok(i18n.languages().includes(language));
  }
});

test('listeners are notified again once a delayed dictionary is ready', async () => {
  const { i18n, appended, context } = load({ defaultLanguage: 'en' });
  // Let start-up work settle first (the settings fetch fails here and notifies on its own).
  await new Promise((resolve) => setImmediate(resolve));
  const composed = [];
  context.window.addEventListener('kkrepo:i18n-change', () => composed.push(i18n.text('Default language')));
  i18n.setDefaultLanguage('fr');
  assert.equal(composed.at(-1), 'Default language', 'first notification while the dictionary is still loading');
  const script = appended.find((node) => String(node.src || '').startsWith('/login/assets/i18n/fr.js?v='));
  assert.ok(script, 'the French dictionary script is requested');
  vm.runInContext(readFileSync(join(dictionaryDir, 'fr.js'), 'utf8'), context);
  script.onload();
  await new Promise((resolve) => setImmediate(resolve));
  assert.equal(composed.at(-1), 'Langue par défaut', 'notified again with the dictionary ready');
});
