const declarations = require("../../../../scripts/ui/test-fixture.cjs")("admin");
const assert = require('node:assert/strict');
const {readFileSync} = require('node:fs');
const {join} = require('node:path');
const test = require('node:test');
const vm = require('node:vm');

const group = (fields) => ({
  maxSeverity: 'HIGH', findingCount: 1, advisoryCount: 1, artifactCount: 1,
  criticalCount: 0, highCount: 1, mediumCount: 0, lowCount: 0, otherCount: 0, ...fields
});

function setup(respond = () => ({items: [], nextAfter: null})) {
  const requests = [];
  const c = {URLSearchParams, document: {querySelector: () => null},
    hideSecurityScanFindingDetail: () => {},
    fetchJson: async (url) => {
      const parsed = new URL(url, 'https://kkrepo.test');
      requests.push(parsed);
      return respond(parsed);
    }};
  declarations.context(c);
  require('./authorization-fixture')(c);
  vm.runInContext(declarations(
    "securityScanState", "SECURITY_SCAN_DEFAULT_PAGE_SIZE", "securityScanListEndpoints",
    "securityScanPages", "securityScanFindingTreeExpanded",
  )
    + declarations(
      "securityScanPageParams", "securityScanFindingRootGroupBy", "securityScanFindingChildGroupBy",
      "securityScanFindingGroupNode", "securityScanFindingNodes", "findSecurityScanFindingNode",
      "refreshSecurityScanFindingTree", "securityScanListItems", "setSecurityScanListItems",
      "restoreSecurityScanFindingNodes", "toggleSecurityScanFindingNode",
      "loadSecurityScanFindingNodeChildren", "showMoreSecurityScanFindingNode", "fetchSecurityScanPage",
      "resetSecurityScanPage", "renderSecurityScanPagination", "renderSecurityScanList",
      "loadSecurityScanList", "searchSecurityScanList", "clearSecurityScanListSearch",
      "moveSecurityScanPage", "sortSecurityScanPage", "filterCurrentSecurityScanFindings",
      "SECURITY_SCAN_FINDING_SWITCHES", "filterSecurityScanFindings", "resizeSecurityScanPage",
    ), c);
  c.renderSecurityScanList = () => {};
  c.renderSecurityScanFindings = () => {};
  return {c, requests, run: (code) => vm.runInContext(code, c)};
}

const flush = () => new Promise((resolve) => setImmediate(resolve));

test('hide waived and grouping switch the top-level request without losing other filters', async () => {
  const {c, requests, run} = setup((url) => url.pathname.endsWith('/groups')
    ? {items: [group({packageName: 'log4j-core', installedVersion: '2.14.1'})], nextCursor: 'g1.next'}
    : {items: [{id: 41}], nextAfter: 41});
  await c.filterSecurityScanFindings({hideWaived: true});
  let last = requests.at(-1);
  assert.equal(last.pathname, '/internal/security/scanning/findings');
  assert.equal(last.searchParams.get('hideWaived'), 'true');
  assert.equal(last.searchParams.get('after'), '0');
  assert.equal(last.searchParams.get('groupBy'), null);

  await c.filterSecurityScanFindings({groupByPackage: true});
  last = requests.at(-1);
  assert.equal(last.pathname, '/internal/security/scanning/findings/groups');
  assert.equal(last.searchParams.get('groupBy'), 'PACKAGE');
  assert.equal(last.searchParams.get('hideWaived'), 'true');
  assert.equal(last.searchParams.get('currentOnly'), 'true');
  assert.equal(last.searchParams.get('after'), null, 'group pages never send a finding ID cursor');
  assert.equal(run('securityScanState.findingGroups.length'), 1);
  assert.equal(run('securityScanListItems("findings").length'), 1);

  await c.moveSecurityScanPage('findings', 'next');
  assert.equal(requests.at(-1).searchParams.get('cursor'), 'g1.next');
  await c.filterSecurityScanFindings({groupByArtifact: true});
  assert.equal(requests.at(-1).searchParams.get('groupBy'), 'ARTIFACT', 'artifact grouping is the root');
  assert.equal(requests.at(-1).searchParams.get('cursor'), null, 'changing grouping restarts paging');
});

test('grouping is limited to current findings', async () => {
  const {c, requests, run} = setup((url) => url.pathname.endsWith('/groups')
    ? {items: [group({packageName: 'demo', installedVersion: '1.0'})], nextCursor: null}
    : {items: [{id: 9}], nextAfter: null});
  const switches = {};
  const toasts = [];
  c.document.getElementById = (id) => (switches[id] ||= {checked: null});
  c.showToast = (message) => toasts.push(message);

  await c.filterSecurityScanFindings({currentOnly: false});
  assert.equal(requests.at(-1).searchParams.get('currentOnly'), 'false');
  assert.equal(toasts.length, 0, 'without grouping, historical findings need no adjustment');

  // Turning grouping on while historical findings are included turns current findings on.
  await c.filterSecurityScanFindings({groupByPackage: true});
  assert.equal(requests.at(-1).pathname, '/internal/security/scanning/findings/groups');
  assert.equal(requests.at(-1).searchParams.get('currentOnly'), 'true');
  assert.equal(switches['security-scan-current-findings'].checked, true);
  assert.equal(switches['security-scan-group-package'].checked, true);
  assert.match(toasts.at(-1), /current findings only/);
  await c.toggleSecurityScanFindingNode(run('securityScanState.findingGroups[0].key'));
  assert.equal(requests.at(-1).searchParams.get('currentOnly'), 'true');

  // Including historical findings turns every grouping off and returns to the flat list.
  await c.filterSecurityScanFindings({groupByArtifact: true});
  await c.filterSecurityScanFindings({currentOnly: false});
  assert.equal(requests.at(-1).pathname, '/internal/security/scanning/findings');
  assert.equal(requests.at(-1).searchParams.get('currentOnly'), 'false');
  assert.equal(requests.at(-1).searchParams.get('groupBy'), null);
  assert.equal(run('securityScanPages.findings.groupByPackage || securityScanPages.findings.groupByArtifact'), false);
  assert.equal(switches['security-scan-group-package'].checked, false);
  assert.equal(switches['security-scan-group-artifact'].checked, false);
  assert.equal(switches['security-scan-current-findings'].checked, false);
  assert.equal(run('securityScanFindingTreeExpanded.size'), 0);
  assert.equal(toasts.length, 2);
});

test('artifacts expand to versions, or to packages when both groupings are on, then to findings', async () => {
  const artifact = {artifactFormat: 'maven2', artifactNamespace: 'org.acme', artifactName: 'app'};
  const {c, requests, run} = setup((url) => {
    const groupBy = url.searchParams.get('groupBy');
    if (groupBy === 'ARTIFACT') return {items: [group(artifact)], nextCursor: null};
    if (groupBy === 'ARTIFACT_VERSION') {
      return url.searchParams.get('cursor') === 'g1.more'
        ? {items: [group({...artifact, artifactVersion: '2.0'})], nextCursor: null}
        : {items: [group({...artifact, artifactVersion: '1.0'})], nextCursor: 'g1.more'};
    }
    if (groupBy === 'PACKAGE') return {items: [group({packageName: 'zlib', installedVersion: ''})], nextCursor: null};
    return {items: [{id: 7}], nextAfter: null};
  });
  await c.filterSecurityScanFindings({groupByArtifact: true});
  const root = run('securityScanState.findingGroups[0].key');
  await c.toggleSecurityScanFindingNode(root);
  let last = requests.at(-1).searchParams;
  assert.equal(last.get('groupBy'), 'ARTIFACT_VERSION');
  assert.equal(last.get('artifactName'), 'app');
  assert.equal(last.get('artifactNamespace'), 'org.acme');
  assert.equal(last.get('artifactVersion'), null);
  assert.equal(run('securityScanState.findingGroups[0].hasMore'), true);

  const version = run('securityScanState.findingGroups[0].children[0].key');
  await c.toggleSecurityScanFindingNode(version);
  last = requests.at(-1);
  assert.equal(last.pathname, '/internal/security/scanning/findings');
  assert.equal(last.searchParams.get('artifactVersion'), '1.0');
  assert.equal(last.searchParams.get('after'), '0');
  assert.deepEqual(JSON.parse(run('JSON.stringify(securityScanState.findings.map((finding) => finding.id))')), [7],
    'findings inside groups stay addressable for view and waive actions');

  await c.showMoreSecurityScanFindingNode(root);
  assert.equal(requests.at(-1).searchParams.get('cursor'), 'g1.more');
  assert.equal(run('securityScanState.findingGroups[0].children.length'), 2);
  assert.equal(run('securityScanState.findingGroups[0].hasMore'), false);

  await c.filterSecurityScanFindings({groupByPackage: true});
  assert.equal(requests.at(-1).searchParams.get('groupBy'), 'ARTIFACT');
  await c.toggleSecurityScanFindingNode(run('securityScanState.findingGroups[0].key'));
  assert.equal(requests.at(-1).searchParams.get('groupBy'), 'PACKAGE');
  assert.equal(requests.at(-1).searchParams.get('artifactName'), 'app');
  await c.toggleSecurityScanFindingNode(run('securityScanState.findingGroups[0].children[0].key'));
  last = requests.at(-1).searchParams;
  assert.equal(last.get('artifactName'), 'app');
  assert.equal(last.get('packageName'), 'zlib');
  assert.equal(last.get('installedVersion'), '', 'a missing version is sent as an explicit empty key');
});

test('expanded groups reopen after reloads but not after the grouping shape changes', async () => {
  const {c, requests, run} = setup((url) => url.pathname.endsWith('/groups')
    ? {items: [group({packageName: 'demo', installedVersion: '1.0'})], nextCursor: null}
    : {items: [{id: 9}], nextAfter: null});
  await c.filterSecurityScanFindings({groupByPackage: true});
  await c.toggleSecurityScanFindingNode(run('securityScanState.findingGroups[0].key'));
  const count = requests.length;
  await c.filterSecurityScanFindings({hideWaived: true});
  await flush();
  assert.equal(requests.length, count + 2, 'the reloaded group reopens and reloads its findings');
  assert.equal(requests.at(-1).searchParams.get('hideWaived'), 'true');
  assert.equal(run('securityScanState.findingGroups[0].expanded'), true);
  assert.deepEqual(JSON.parse(run('JSON.stringify(securityScanState.findings.map((finding) => finding.id))')), [9]);

  await c.filterSecurityScanFindings({groupByArtifact: true});
  await c.filterSecurityScanFindings({groupByArtifact: false});
  await flush();
  assert.equal(run('securityScanState.findingGroups[0].expanded'), false);
  assert.equal(run('securityScanFindingTreeExpanded.size'), 0);
});

test('a group response that arrives after the list reloaded is ignored', async () => {
  const pending = [];
  const {c, run} = setup();
  c.fetchJson = async () => ({items: [group({packageName: 'demo', installedVersion: '1.0'})], nextCursor: null});
  await c.filterSecurityScanFindings({groupByPackage: true});
  c.fetchJson = () => new Promise((resolve) => pending.push(resolve));
  const expanding = c.toggleSecurityScanFindingNode(run('securityScanState.findingGroups[0].key'));
  const reloading = c.filterSecurityScanFindings({hideWaived: true});
  pending[1]({items: [group({packageName: 'other', installedVersion: '2.0'})], nextCursor: null});
  await reloading;
  pending[0]({items: [{id: 99}], nextAfter: null});
  await expanding;
  assert.equal(run('securityScanState.findingGroups[0].group.packageName'), 'other');
  assert.deepEqual(JSON.parse(run('JSON.stringify(securityScanState.findings.map((finding) => finding.id))')), []);
});

test('group rows escape labels, shorten digests, and offer more rows only when a cursor remains', () => {
  const c = {escapeHtml: (value) => String(value).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/"/g, '&quot;'),
    renderSecurityScanSeverity: (value) => `[${value}]`,
    renderSecurityScanFindingAdvisory: (finding) => finding.advisoryId,
    renderSecurityScanFindingRepositories: () => '',
    renderSecurityScanFindingWaiverStatus: () => '',
    renderSecurityScanFindingActions: () => '',
    securityScanFindingRootGroupBy: () => 'ARTIFACT',
    securityScanCanMutate: () => true,
    securityScanState: {findingGroups: [], findings: []},
    document: {getElementById: () => c.table}, table: {innerHTML: ''}};
  declarations.context(c);
  vm.runInContext(declarations("SECURITY_SCAN_SEVERITY_PRESENTATION")
    + declarations(
      "renderSecurityScanFindingRow", "renderSecurityScanFindings", "SECURITY_SCAN_FINDING_GROUP_KINDS",
      "securityScanFindingGroupLabel", "renderSecurityScanFindingGroupCounts",
      "appendSecurityScanFindingNodeRows",
    ), c);
  const digest = `sha256:${'a'.repeat(64)}`;
  c.securityScanState.findingGroups = [{
    key: '["ARTIFACT",{}]', groupBy: 'ARTIFACT', level: 0, expanded: true, loaded: true,
    loading: false, error: '', hasMore: true,
    group: group({artifactFormat: 'docker', artifactNamespace: '', artifactName: 'team/<img>'}),
    children: [{
      key: 'v', groupBy: 'ARTIFACT_VERSION', level: 1, expanded: true, loaded: true,
      loading: false, error: '', hasMore: false, children: [{finding: {id: 3, advisoryId: 'CVE-1', severity: 'LOW', packageName: 'zlib'}}],
      group: group({artifactVersion: digest})
    }]
  }];
  c.renderSecurityScanFindings();
  const html = c.table.innerHTML;
  assert.match(html, /team\/&lt;img>/);
  assert.doesNotMatch(html, /team\/<img>/);
  assert.match(html, /sha256:aaaaaaaaaaaa…/);
  assert.match(html, new RegExp(`title="${digest}"`));
  assert.match(html, /security-scan-finding-tree-leaf" data-level="2"/);
  assert.equal((html.match(/security-scan-finding-group-more/g) || []).length, 1);
  assert.match(html, /aria-expanded="true"/);
  assert.match(html, /title="1 high"><span class="lucide-icon icon-triangle-alert"/);
  assert.match(html, /<strong>1<\/strong><span>finding<\/span>/, 'counts of one are singular');
  assert.doesNotMatch(html, /<strong>1<\/strong><span>findings<\/span>/);
  assert.doesNotMatch(html, /security-scan-finding-group-waive/, 'only package groups can be waived as a package');
  c.securityScanState.findingGroups = [{key: 'p', groupBy: 'PACKAGE', level: 0, expanded: false, children: [],
    group: group({packageName: 'snakeyaml', installedVersion: '1.30'})}];
  c.renderSecurityScanFindings();
  assert.match(c.table.innerHTML, /security-scan-finding-group-waive" data-key="p"/);
  c.securityScanCanMutate = () => false;
  c.renderSecurityScanFindings();
  assert.doesNotMatch(c.table.innerHTML, /security-scan-finding-group-waive/);
});

function packageWaiverSetup({answer = true, replaced = [], postStatus = 200} = {}) {
  const elements = {};
  const element = (id) => (elements[id] ||= {id, value: '', innerHTML: '', hidden: false, disabled: false, rows: 1, reset() {}});
  const calls = {fetches: [], confirms: [], toasts: [], closed: 0, opened: 0, reloaded: 0};
  const context = (scope) => ({
    packageName: 'snakeyaml', installedVersion: '1.30', canWaiveGlobally: true,
    repositories: [{id: 11, name: 'releases'}], repositoryId: scope === 'all' ? null : 11,
    packageUrls: ['pkg:maven/org.yaml/snakeyaml@1.30'], missingPackageUrl: false,
    replacedWaivers: replaced
  });
  const c = {URLSearchParams,
    document: {getElementById: element},
    escapeHtml: (value) => String(value),
    findSecurityScanFindingNode: () => ({groupBy: 'PACKAGE', group: {packageName: 'snakeyaml', installedVersion: '1.30'}}),
    securityScanCanMutate: () => true,
    clearRequiredFieldErrors: () => {}, validateRequiredFields: () => true,
    openFormModal: () => { calls.opened++; }, closeFormModal: () => { calls.closed++; },
    showToast: (message, tone) => calls.toasts.push([message, tone]),
    responseErrorMessage: async () => 'Waivers for this package changed.',
    loadSecurityScanning: async () => { calls.reloaded++; }, selectSecurityScanTab: () => {},
    window: {kkrepoConfirmDelete: async (options) => { calls.confirms.push(options); return answer; }},
    fetch: async (url, init = {}) => {
      const parsed = new URL(url, 'https://kkrepo.test');
      calls.fetches.push({url: parsed, init});
      if (parsed.pathname.endsWith('/package-context')) {
        const scope = parsed.searchParams.get('allRepositories') === 'true' ? 'all' : '';
        return {ok: true, json: async () => context(scope)};
      }
      return {ok: postStatus === 200, status: postStatus, json: async () => ({created: [{id: 70}], replaced})};
    }};
  declarations.context(c);
  vm.runInContext('const securityScanPackageWaiverRequiredFields = [];'
    + declarations(
      "securityScanPackageWaiverPackage", "securityScanPackageWaiverContext",
      "securityScanPackageWaiverRequest", "securityScanPackageWaiverScopeValue",
      "securityScanPackageLabel", "securityScanPackageWaiverScopeLabel",
      "fetchSecurityScanPackageWaiverContext", "renderSecurityScanPackageWaiverReplaced",
      "renderSecurityScanPackageWaiverContext", "showSecurityScanPackageWaiverForm",
      "changeSecurityScanPackageWaiverScope", "hideSecurityScanPackageWaiverForm",
      "createSecurityScanPackageWaiver",
    ), c);
  return {c, calls, element, run: (code) => vm.runInContext(code, c)};
}

const submit = (c) => c.createSecurityScanPackageWaiver({preventDefault() {}});
const replacedWaivers = [
  {id: 7, active: true, scopeType: 'FINDING', repository: 'releases', exception: 'CVE-2022-1471'},
  {id: 9, active: false, scopeType: 'ASSET', repository: 'releases', assetPath: 'app.jar',
    exception: 'pkg:maven/org.yaml/snakeyaml@1.30'}];

test('answering No to replacing individual waivers cancels without changing anything', async () => {
  const {c, calls, element} = packageWaiverSetup({answer: false, replaced: replacedWaivers});
  await c.showSecurityScanPackageWaiverForm('p');
  assert.equal(calls.opened, 1);
  assert.equal(element('security-scan-package-waiver-scope').value, 'repository:11');
  assert.match(element('security-scan-package-waiver-replaced').innerHTML, /<strong>2<\/strong> existing waivers/);
  element('security-scan-package-waiver-reason').value = ' shaded ';
  await submit(c);
  assert.equal(calls.confirms.length, 1);
  assert.equal(calls.confirms[0].confirmLabel, 'Yes, replace');
  assert.equal(calls.confirms[0].cancelLabel, 'No');
  assert.match(calls.confirms[0].description,
    /All 2 individual waivers for snakeyaml @ 1\.30 in releases will be deleted/);
  assert.equal(calls.fetches.filter((entry) => entry.init.method === 'POST').length, 0);
  assert.equal(calls.closed, 1);
  assert.match(calls.toasts.at(-1)[0], /cancelled/);
});

test('answering Yes posts the confirmed waivers with the package scope', async () => {
  const {c, calls, element} = packageWaiverSetup({answer: true, replaced: replacedWaivers});
  await c.showSecurityScanPackageWaiverForm('p');
  element('security-scan-package-waiver-reason').value = ' shaded ';
  await submit(c);
  const post = calls.fetches.find((entry) => entry.init.method === 'POST');
  assert.equal(post.url.pathname, '/internal/security/scanning/waivers/package');
  const body = JSON.parse(post.init.body);
  assert.equal(body.packageName, 'snakeyaml');
  assert.equal(body.installedVersion, '1.30');
  assert.equal(body.repositoryId, 11);
  assert.equal(body.reason, 'shaded');
  assert.deepEqual(body.replaceWaiverIds, [7, 9]);
  assert.deepEqual(body.packageUrls, ['pkg:maven/org.yaml/snakeyaml@1.30'],
    'the previewed package URLs are confirmed too');
  const days = (Date.parse(body.expiresAt) - Date.now()) / 86400000;
  assert.ok(days > 89.9 && days <= 90, 'package waivers default to 90 days');
  assert.equal(calls.reloaded, 1);
  assert.match(calls.toasts.at(-1)[0], /2 individual waivers replaced/);
});

test('with nothing to replace there is no confirmation, and a concurrent change refreshes the list', async () => {
  const {c, calls, element} = packageWaiverSetup({replaced: [], postStatus: 409});
  await c.showSecurityScanPackageWaiverForm('p');
  await c.changeSecurityScanPackageWaiverScope('all');
  assert.equal(calls.fetches.at(-1).url.searchParams.get('allRepositories'), 'true');
  assert.equal(element('security-scan-package-waiver-scope').value, 'all');
  element('security-scan-package-waiver-reason').value = 'shaded';
  await submit(c);
  assert.equal(calls.confirms.length, 0);
  const body = JSON.parse(calls.fetches.find((entry) => entry.init.method === 'POST').init.body);
  assert.equal(body.repositoryId, null);
  assert.deepEqual(body.replaceWaiverIds, []);
  assert.equal(calls.fetches.at(-1).url.pathname, '/internal/security/scanning/waivers/package-context',
    'a 409 reloads the waivers to replace for the same scope');
  assert.equal(calls.fetches.at(-1).url.searchParams.get('allRepositories'), 'true');
  assert.equal(calls.closed, 0, 'the form stays open for another confirmation');
  assert.equal(calls.toasts.at(-1)[1], 'error');
});

test('a scope that is still loading or failed to load cannot be submitted', async () => {
  const {c, calls, element, run} = packageWaiverSetup();
  await c.showSecurityScanPackageWaiverForm('p');
  await c.changeSecurityScanPackageWaiverScope('all');
  element('security-scan-package-waiver-reason').value = 'shaded';
  const posts = () => calls.fetches.filter((entry) => entry.init.method === 'POST').length;

  // The operator narrows All repositories to one repository; its preview is delayed.
  const realFetch = c.fetch;
  const pending = [];
  c.fetch = (url, init = {}) => init.method === 'POST'
    ? realFetch(url, init)
    : new Promise((resolve) => pending.push({url, resolve}));
  element('security-scan-package-waiver-scope').value = 'repository:11';
  const slow = c.changeSecurityScanPackageWaiverScope('repository:11');
  assert.equal(element('security-scan-save-package-waiver-button').disabled, true);
  assert.equal(run('securityScanPackageWaiverContext'), null);
  await submit(c);
  assert.equal(posts(), 0, 'the previous all-repositories preview must not be submitted');
  assert.match(calls.toasts.at(-1)[0], /still loading/);

  // A newer selection supersedes the slow one; the slow response is discarded when it arrives.
  element('security-scan-package-waiver-scope').value = 'all';
  const latest = c.changeSecurityScanPackageWaiverScope('all');
  pending[1].resolve(await realFetch(pending[1].url));
  assert.equal(await latest, true);
  pending[0].resolve(await realFetch(pending[0].url));
  assert.equal(await slow, false);
  assert.equal(run('securityScanPackageWaiverContext.repositoryId'), null);
  assert.equal(element('security-scan-package-waiver-scope').value, 'all');
  assert.equal(element('security-scan-save-package-waiver-button').disabled, false);

  // A failed load keeps submission blocked.
  c.fetch = async (url, init = {}) => init.method === 'POST' ? realFetch(url, init) : {ok: false};
  element('security-scan-package-waiver-scope').value = 'repository:11';
  assert.equal(await c.changeSecurityScanPackageWaiverScope('repository:11'), false);
  assert.equal(element('security-scan-save-package-waiver-button').disabled, true);
  await submit(c);
  assert.equal(posts(), 0);

  // Closing the dialog discards anything still in flight.
  c.fetch = (url) => new Promise((resolve) => pending.push({url, resolve}));
  const abandoned = c.changeSecurityScanPackageWaiverScope('all');
  c.hideSecurityScanPackageWaiverForm();
  pending.at(-1).resolve(await realFetch(pending.at(-1).url));
  assert.equal(await abandoned, false);
  assert.equal(run('securityScanPackageWaiverContext'), null);
});

test('a superseded first preview leaves the newer dialog able to change scope', async () => {
  const {c, calls, run} = packageWaiverSetup();
  const realFetch = c.fetch;
  const pending = [];
  c.fetch = (url) => new Promise((resolve) => pending.push({url, resolve}));
  // A double click starts two previews; the older response arrives first and is discarded.
  const older = c.showSecurityScanPackageWaiverForm('p');
  const newer = c.showSecurityScanPackageWaiverForm('p');
  pending[0].resolve(await realFetch(pending[0].url));
  await older;
  assert.equal(calls.opened, 0);
  assert.notEqual(run('securityScanPackageWaiverPackage'), null,
    'the newer request still owns the dialog target');
  pending[1].resolve(await realFetch(pending[1].url));
  await newer;
  assert.equal(calls.opened, 1);
  c.fetch = realFetch;
  assert.equal(await c.changeSecurityScanPackageWaiverScope('all'), true);
  assert.equal(run('securityScanPackageWaiverContext.repositoryId'), null);
});

test('the dialog does not open when the first preview cannot be loaded', async () => {
  const {c, calls, run} = packageWaiverSetup();
  c.fetch = async () => ({ok: false});
  await c.showSecurityScanPackageWaiverForm('p');
  assert.equal(calls.opened, 0);
  assert.equal(run('securityScanPackageWaiverPackage'), null);
  assert.equal(calls.toasts.at(-1)[1], 'error');
});
