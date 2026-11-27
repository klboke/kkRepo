import { updateTableSortHeaders } from "../controls/table.js";
import { showToast } from "../controls/toast.js";
import { hideSecurityScanFindingDetail } from "./details.js";
import { renderSecurityScanFindings } from "./findings.js";
import { renderSecurityScanRuns } from "./overview.js";
import { renderSecurityScanPolicies, renderSecurityScanRepositories } from "./policies.js";
import { renderSecurityScanTasks } from "./tasks.js";
import { renderSecurityScanWaivers } from "./waivers.js";
import { fetchJson } from "../shell/http.js";

let securityScanState = {
  summary: null,
  tasks: [],
  runs: [],
  findings: [],
  findingGroups: [],
  repositories: [],
  policies: [],
  repositoryPolicyOptions: [],
  repositoryEditRequest: 0,
  waivers: []
};

const SECURITY_SCAN_DEFAULT_PAGE_SIZE = 10;

const securityScanListEndpoints = {
  runs: "runs",
  tasks: "tasks",
  findings: "findings",
  repositories: "repositories",
  policies: "policies",
  waivers: "waivers"
};

let securityScanPages = Object.fromEntries(
  Object.keys(securityScanListEndpoints).map((key) => [
    key,
    {
      after: 0,
      cursors: [0],
      page: 0,
      size: SECURITY_SCAN_DEFAULT_PAGE_SIZE,
      query: "",
      currentOnly: key === "findings",
      ...(key === "findings"
        ? { hideWaived: false, groupByPackage: false, groupByArtifact: false }
        : {}),
      direction: "desc",
      requestVersion: 0,
      nextAfter: null
    }
  ]));

// Keys of expanded finding groups. Keys encode only the group identity, so expansions survive
// reloads, searches, and waiver changes; changing the grouping shape clears them.
const securityScanFindingTreeExpanded = new Set();

function securityScanPageParams(key) {
  const page = securityScanPages[key];
  const params = new URLSearchParams();
  if (key === "tasks" || key === "runs") {
    params.set("sort", key === "tasks" ? "finished_at" : "completed_at");
    params.set("direction", page.direction);
    if (page.after) params.set("cursor", page.after);
  } else {
    params.set("after", String(page.after || 0));
  }
  params.set("limit", String(page.size || SECURITY_SCAN_DEFAULT_PAGE_SIZE));
  if (page.query) params.set("q", page.query);
  if (key === "findings") {
    params.set("currentOnly", String(page.currentOnly));
    if (page.hideWaived) params.set("hideWaived", "true");
    const groupBy = securityScanFindingRootGroupBy(page);
    if (groupBy) {
      // Group pages use opaque keyset cursors instead of finding IDs.
      params.delete("after");
      params.set("groupBy", groupBy);
      if (page.after) params.set("cursor", page.after);
    }
  }
  return params;
}

// Findings tree shape: artifacts expand to their versions, or to package@version groups when
// package grouping is also on; package and version groups expand to finding rows.
function securityScanFindingRootGroupBy(page = securityScanPages.findings) {
  if (page.groupByArtifact) return "ARTIFACT";
  if (page.groupByPackage) return "PACKAGE";
  return null;
}

function securityScanFindingChildGroupBy(groupBy, page = securityScanPages.findings) {
  if (groupBy !== "ARTIFACT") return null;
  return page.groupByPackage ? "PACKAGE" : "ARTIFACT_VERSION";
}

function securityScanFindingGroupNode(groupBy, group, parent = null) {
  const filters = { ...(parent?.filters || {}) };
  if (groupBy === "PACKAGE") {
    filters.packageName = group.packageName ?? "";
    filters.installedVersion = group.installedVersion ?? "";
  } else {
    filters.artifactFormat = group.artifactFormat ?? "";
    filters.artifactNamespace = group.artifactNamespace ?? "";
    filters.artifactName = group.artifactName ?? "";
    if (groupBy === "ARTIFACT_VERSION") filters.artifactVersion = group.artifactVersion ?? "";
  }
  return {
    key: JSON.stringify([groupBy, filters]),
    groupBy,
    group,
    filters,
    level: parent ? parent.level + 1 : 0,
    childGroupBy: securityScanFindingChildGroupBy(groupBy),
    expanded: false,
    loading: false,
    loaded: false,
    error: "",
    children: [],
    cursor: null,
    hasMore: false,
    requestVersion: 0
  };
}

function securityScanFindingNodes(nodes = securityScanState.findingGroups, result = []) {
  nodes.forEach((node) => {
    if (node.finding) return;
    result.push(node);
    securityScanFindingNodes(node.children, result);
  });
  return result;
}

function findSecurityScanFindingNode(key) {
  return securityScanFindingNodes().find((node) => node.key === key) || null;
}

/** Keeps detail, waiver, and waive actions working for findings loaded inside groups. */
function refreshSecurityScanFindingTree() {
  securityScanState.findings = securityScanFindingNodes()
    .filter((node) => node.expanded)
    .flatMap((node) => node.children.filter((child) => child.finding).map((child) => child.finding));
  renderSecurityScanFindings();
}

function securityScanListItems(key) {
  return key === "findings" && securityScanFindingRootGroupBy()
    ? securityScanState.findingGroups
    : securityScanState[key] || [];
}

function setSecurityScanListItems(key, items) {
  if (key !== "findings") {
    securityScanState[key] = items;
    return;
  }
  const groupBy = securityScanFindingRootGroupBy();
  if (!groupBy) {
    securityScanState.findingGroups = [];
    securityScanState.findings = items;
    return;
  }
  securityScanState.findingGroups =
    items.map((group) => securityScanFindingGroupNode(groupBy, group));
  securityScanState.findings = [];
  restoreSecurityScanFindingNodes(securityScanState.findingGroups);
}

function restoreSecurityScanFindingNodes(nodes) {
  nodes.filter((node) => securityScanFindingTreeExpanded.has(node.key)).forEach((node) => {
    node.expanded = true;
    loadSecurityScanFindingNodeChildren(node);
  });
}

async function toggleSecurityScanFindingNode(key) {
  const node = findSecurityScanFindingNode(key);
  if (!node) return;
  node.expanded = !node.expanded;
  if (node.expanded) securityScanFindingTreeExpanded.add(node.key);
  else securityScanFindingTreeExpanded.delete(node.key);
  if (node.expanded && !node.loaded && !node.loading) {
    await loadSecurityScanFindingNodeChildren(node);
  } else {
    refreshSecurityScanFindingTree();
  }
}

async function loadSecurityScanFindingNodeChildren(node, more = false) {
  const page = securityScanPages.findings;
  const rootVersion = page.requestVersion;
  const requestVersion = ++node.requestVersion;
  if (!more) {
    node.children = [];
    node.cursor = null;
    node.hasMore = false;
  }
  node.loading = true;
  node.error = "";
  refreshSecurityScanFindingTree();
  const params = new URLSearchParams();
  params.set("limit", String(page.size || SECURITY_SCAN_DEFAULT_PAGE_SIZE));
  if (page.query) params.set("q", page.query);
  params.set("currentOnly", String(page.currentOnly));
  if (page.hideWaived) params.set("hideWaived", "true");
  Object.entries(node.filters).forEach(([name, value]) => params.set(name, value));
  let path = "/internal/security/scanning/findings";
  if (node.childGroupBy) {
    path += "/groups";
    params.set("groupBy", node.childGroupBy);
    if (node.cursor) params.set("cursor", node.cursor);
  } else {
    params.set("after", String(node.cursor || 0));
  }
  const payload = await fetchJson(
    `${path}?${params.toString()}`, null, "Failed to load scan findings");
  if (requestVersion !== node.requestVersion || rootVersion !== page.requestVersion) return;
  node.loading = false;
  if (!payload) {
    node.error = "Unable to load this group. Collapse and expand it to retry.";
    node.loaded = false;
    refreshSecurityScanFindingTree();
    return;
  }
  const items = Array.isArray(payload.items) ? payload.items : [];
  const children = node.childGroupBy
    ? items.map((group) => securityScanFindingGroupNode(node.childGroupBy, group, node))
    : items.map((finding) => ({ finding }));
  node.children.push(...children);
  node.cursor = payload.nextCursor ?? payload.nextAfter ?? null;
  node.hasMore = node.cursor != null;
  node.loaded = true;
  refreshSecurityScanFindingTree();
  if (node.childGroupBy) restoreSecurityScanFindingNodes(children);
}

async function showMoreSecurityScanFindingNode(key) {
  const node = findSecurityScanFindingNode(key);
  if (node?.hasMore && !node.loading) await loadSecurityScanFindingNodeChildren(node, true);
}

async function fetchSecurityScanPage(key) {
  if (key === "repositories") ++securityScanState.repositoryEditRequest;
  const endpoint = key === "findings" && securityScanFindingRootGroupBy()
    ? "findings/groups"
    : securityScanListEndpoints[key];
  const requestVersion = ++securityScanPages[key].requestVersion;
  const payload = await fetchJson(
    `/internal/security/scanning/${endpoint}?${securityScanPageParams(key).toString()}`,
    { items: [], nextAfter: null },
    `Failed to load scan ${key}`);
  return { ...payload, requestVersion };
}

function resetSecurityScanPage(key) {
  const page = securityScanPages[key];
  page.after = 0;
  page.cursors = [0];
  page.page = 0;
  page.nextAfter = null;
}

function renderSecurityScanPagination(key) {
  const page = securityScanPages[key];
  const items = securityScanListItems(key);
  const summary = document.querySelector(`[data-security-scan-page-summary="${key}"]`);
  const label = document.querySelector(`[data-security-scan-page-label="${key}"]`);
  const previous = document.querySelector(
    `[data-security-scan-page-action="prev"][data-security-scan-page-list="${key}"]`);
  const next = document.querySelector(
    `[data-security-scan-page-action="next"][data-security-scan-page-list="${key}"]`);
  const size = document.querySelector(`[data-security-scan-page-size="${key}"]`);
  const query = document.querySelector(`[data-security-scan-query="${key}"]`);
  if (summary) {
    summary.textContent = items.length === 1
      ? "1 result on this page"
      : `${items.length} results on this page`;
  }
  if (label) label.textContent = `Page ${page.page + 1}`;
  if (previous) previous.disabled = page.page <= 0;
  if (next) next.disabled = page.nextAfter == null;
  if (size) size.value = String(page.size);
  if (query && document.activeElement !== query) query.value = page.query;
}

function renderSecurityScanList(key) {
  const renderers = {
    runs: renderSecurityScanRuns,
    tasks: renderSecurityScanTasks,
    findings: renderSecurityScanFindings,
    repositories: renderSecurityScanRepositories,
    policies: renderSecurityScanPolicies,
    waivers: renderSecurityScanWaivers
  };
  renderers[key]?.();
  renderSecurityScanPagination(key);
}

async function loadSecurityScanList(key) {
  const payload = await fetchSecurityScanPage(key);
  const items = Array.isArray(payload?.items) ? payload.items : [];
  const page = securityScanPages[key];
  if (payload.requestVersion !== page.requestVersion) return;
  if (items.length === 0 && page.page > 0) {
    page.cursors.pop();
    page.page -= 1;
    page.after = page.cursors.at(-1) || 0;
    return loadSecurityScanList(key);
  }
  page.nextAfter = payload?.nextCursor ?? payload?.nextAfter ?? null;
  setSecurityScanListItems(key, items);
  renderSecurityScanList(key);
}

async function searchSecurityScanList(key) {
  const input = document.querySelector(`[data-security-scan-query="${key}"]`);
  securityScanPages[key].query = input?.value.trim() || "";
  resetSecurityScanPage(key);
  await loadSecurityScanList(key);
}

async function clearSecurityScanListSearch(key) {
  securityScanPages[key].query = "";
  const input = document.querySelector(`[data-security-scan-query="${key}"]`);
  if (input) input.value = "";
  resetSecurityScanPage(key);
  await loadSecurityScanList(key);
}

async function moveSecurityScanPage(key, direction) {
  const page = securityScanPages[key];
  if (direction === "next") {
    if (page.nextAfter == null) return;
    page.after = page.nextAfter;
    page.cursors.push(page.after);
    page.page += 1;
  } else {
    if (page.page <= 0) return;
    page.cursors.pop();
    page.page -= 1;
    page.after = page.cursors.at(-1) || 0;
  }
  await loadSecurityScanList(key);
}

async function sortSecurityScanPage(key, direction) {
  securityScanPages[key].direction = direction;
  updateTableSortHeaders(key, {
    key: key === "tasks" ? "finished_at" : "completed_at",
    direction,
  });
  resetSecurityScanPage(key);
  await loadSecurityScanList(key);
}

async function filterCurrentSecurityScanFindings(currentOnly) {
  await filterSecurityScanFindings({ currentOnly });
}

const SECURITY_SCAN_FINDING_SWITCHES = Object.freeze({
  currentOnly: "security-scan-current-findings",
  hideWaived: "security-scan-hide-waived",
  groupByPackage: "security-scan-group-package",
  groupByArtifact: "security-scan-group-artifact"
});

/**
 * Applies findings view options and reloads from the first page. Changing the grouping shape
 * forgets expanded groups; filters keep them so the same groups reopen after the reload.
 * Grouping aggregates every matching finding, so it is limited to current findings: turning
 * grouping on turns current findings on, and including historical findings turns grouping off.
 */
async function filterSecurityScanFindings(changes) {
  const page = securityScanPages.findings;
  const next = { ...changes };
  let notice = "";
  if (next.currentOnly === false && (page.groupByPackage || page.groupByArtifact)) {
    next.groupByPackage = false;
    next.groupByArtifact = false;
    notice = "Grouping was turned off: it shows current findings only.";
  } else if ((next.groupByPackage || next.groupByArtifact) && !page.currentOnly) {
    next.currentOnly = true;
    notice = "Grouping shows current findings only.";
  }
  const shapeChanged = ["groupByPackage", "groupByArtifact"]
    .some((name) => name in next && Boolean(next[name]) !== Boolean(page[name]));
  Object.assign(page, next);
  if (notice) {
    Object.entries(SECURITY_SCAN_FINDING_SWITCHES).forEach(([name, id]) => {
      document.getElementById(id).checked = Boolean(page[name]);
    });
    showToast(notice, "ok");
  }
  if (shapeChanged) securityScanFindingTreeExpanded.clear();
  hideSecurityScanFindingDetail();
  resetSecurityScanPage("findings");
  await loadSecurityScanList("findings");
}

async function resizeSecurityScanPage(key, size) {
  securityScanPages[key].size = Number(size) || SECURITY_SCAN_DEFAULT_PAGE_SIZE;
  resetSecurityScanPage(key);
  await loadSecurityScanList(key);
}

export {
  clearSecurityScanListSearch,
  fetchSecurityScanPage,
  filterCurrentSecurityScanFindings,
  filterSecurityScanFindings,
  findSecurityScanFindingNode,
  loadSecurityScanList,
  moveSecurityScanPage,
  renderSecurityScanPagination,
  resetSecurityScanPage,
  resizeSecurityScanPage,
  searchSecurityScanList,
  securityScanFindingRootGroupBy,
  securityScanListEndpoints,
  securityScanListItems,
  securityScanPages,
  securityScanState,
  setSecurityScanListItems,
  showMoreSecurityScanFindingNode,
  sortSecurityScanPage,
  toggleSecurityScanFindingNode,
};
