import { escapeHtml, lucideIcon } from "../controls/display.js";
import {
  SECURITY_SCAN_SEVERITY_PRESENTATION,
  renderSecurityScanSeverity,
  securityScanCanMutate,
} from "./overview.js";
import { securityScanFindingRootGroupBy, securityScanState } from "./pages.js";

function renderSecurityScanFindingRepositories(finding) {
  const artifacts = Array.isArray(finding.affectedArtifacts)
    ? finding.affectedArtifacts.filter((artifact) => artifact?.repository && artifact?.browsePath)
    : [];
  if (artifacts.length > 0) {
    const artifact = artifacts[0];
    const count = Number(finding.affectedArtifactCount || artifacts.length);
    const label = `${artifact.repository}: ${artifact.browsePath}`;
    const more = count > 1 ? ` +${count - 1}` : "";
    return `<span class="security-scan-finding-artifacts-preview"><a href="${escapeHtml(securityScanFindingArtifactBrowseUrl(artifact))}" title="${escapeHtml(`Open ${label} in Repository Browser`)}">${escapeHtml(label)}</a>${escapeHtml(more)}</span>`;
  }
  const repositories = Array.isArray(finding.repositories)
    ? finding.repositories.filter(Boolean)
    : [];
  if (repositories.length === 0) return "-";
  const fullLabel = repositories.join(", ");
  const visibleLabel = repositories.length > 1
    ? `${repositories[0]} +${repositories.length - 1}`
    : repositories[0];
  return `<span class="security-scan-finding-repositories" title="${escapeHtml(fullLabel)}">${escapeHtml(visibleLabel)}</span>`;
}

function securityScanFindingArtifactBrowseUrl(artifact) {
  const repository = String(artifact?.repository || "");
  const path = String(artifact?.browsePath || artifact?.assetPath || "");
  const source = String(artifact?.sourceRepository || "");
  const params = new URLSearchParams();
  if (path) params.set("path", path);
  if (source && source !== repository) params.set("source", source);
  const query = params.toString();
  return `/browse/#browse/browse:${encodeURIComponent(repository)}${query ? `?${query}` : ""}`;
}

function securityScanExternalHttpUrl(value) {
  if (!value) return "";
  try {
    const url = new URL(String(value));
    if (!["http:", "https:"].includes(url.protocol) || url.username || url.password) return "";
    return url.href;
  } catch {
    return "";
  }
}

function securityScanFindingAdvisoryUrl(finding) {
  return [finding.primaryUrl, finding.dataSource]
    .map(securityScanExternalHttpUrl)
    .find(Boolean) || "";
}

function renderSecurityScanFindingAdvisory(finding) {
  const label = finding.advisoryId || `#${finding.id}`;
  const href = securityScanFindingAdvisoryUrl(finding);
  const content = `<code>${escapeHtml(label)}</code>`;
  if (!href) return content;
  return `
    <a class="security-scan-advisory-link"
       href="${escapeHtml(href)}"
       target="_blank"
       rel="noopener noreferrer"
       title="Open vulnerability advisory"
       aria-label="${escapeHtml(`Open advisory ${label} in a new tab`)}">
      ${content}
      ${lucideIcon("external-link")}
    </a>`;
}

function renderSecurityScanFindingRow(finding, level = null) {
  const tree = level == null ? "" : ` class="security-scan-finding-tree-leaf" data-level="${level}"`;
  return `
      <tr${tree}>
        <td>${renderSecurityScanSeverity(finding.severity)}</td>
        <td>${renderSecurityScanFindingAdvisory(finding)}</td>
        <td>${renderSecurityScanFindingRepositories(finding)}</td>
        <td title="${escapeHtml(finding.packageUrl || "")}">${escapeHtml(finding.packageName)}</td>
        <td>${escapeHtml(finding.installedVersion || "-")}</td>
        <td>${escapeHtml((finding.fixedVersions || []).join(", ") || "-")}</td>
        <td>${renderSecurityScanFindingWaiverStatus(finding)}</td>
        <td class="actions-column security-scan-finding-actions">${renderSecurityScanFindingActions(finding)}</td>
      </tr>`;
}

function renderSecurityScanFindings() {
  const table = document.getElementById("security-scan-finding-table");
  if (securityScanFindingRootGroupBy()) {
    const rows = [];
    securityScanState.findingGroups.forEach((node) => appendSecurityScanFindingNodeRows(rows, node));
    table.innerHTML = rows.join("")
      || '<tr><td colspan="8" class="placeholder">No known vulnerability findings are visible.</td></tr>';
    return;
  }
  table.innerHTML =
    securityScanState.findings.map((finding) => renderSecurityScanFindingRow(finding)).join("")
      || '<tr><td colspan="8" class="placeholder">No known vulnerability findings are visible.</td></tr>';
}

const SECURITY_SCAN_FINDING_GROUP_KINDS = Object.freeze({
  PACKAGE: "Package",
  ARTIFACT: "Artifact",
  ARTIFACT_VERSION: "Version"
});

function securityScanFindingGroupLabel(groupBy, group) {
  if (groupBy === "PACKAGE") {
    return {
      text: group.packageName || "-",
      detail: group.installedVersion || "no version",
      title: [group.packageName, group.installedVersion].filter(Boolean).join(" ")
    };
  }
  if (groupBy === "ARTIFACT_VERSION") {
    const version = String(group.artifactVersion || "");
    // Docker manifests are versioned by digest; keep the row readable and the full value on hover.
    const shortened = /^[a-z0-9]+:[0-9a-f]{32,}$/i.test(version)
      ? `${version.slice(0, version.indexOf(":") + 13)}…`
      : version;
    return { text: shortened || "no version", detail: "", title: version };
  }
  const namespace = String(group.artifactNamespace || "");
  const name = String(group.artifactName || "-");
  const separator = String(group.artifactFormat || "").toLowerCase() === "maven2" ? ":" : "/";
  const text = namespace ? `${namespace}${separator}${name}` : name;
  return { text, detail: group.artifactFormat || "", title: text };
}

function renderSecurityScanFindingGroupCounts(group) {
  const severities = [
    ["CRITICAL", group.criticalCount, "critical"],
    ["HIGH", group.highCount, "high"],
    ["MEDIUM", group.mediumCount, "medium"],
    ["LOW", group.lowCount, "low"],
    ["UNKNOWN", group.otherCount, "other"]
  ].filter(([, count]) => Number(count) > 0)
    .map(([severity, count, label]) => {
      const presentation = SECURITY_SCAN_SEVERITY_PRESENTATION[severity];
      return `<span class="state-badge compact security-scan-severity security-scan-severity-count ${presentation.tone}" title="${escapeHtml(`${count} ${label}`)}"><span class="lucide-icon icon-${presentation.icon}" aria-hidden="true"></span>${escapeHtml(count)}</span>`;
    })
    .join("");
  const totals = [
    [group.findingCount, "finding", "findings"],
    [group.advisoryCount, "advisory", "advisories"],
    [group.artifactCount, "artifact", "artifacts"]
  ].map(([count, one, many]) => {
    const value = Number(count) || 0;
    return `<span><strong>${escapeHtml(value)}</strong><span>${value === 1 ? one : many}</span></span>`;
  }).join("");
  return `<span class="security-scan-finding-group-counts">${severities}${totals}</span>`;
}

function appendSecurityScanFindingNodeRows(rows, node) {
  const label = securityScanFindingGroupLabel(node.groupBy, node.group);
  rows.push(`
      <tr class="security-scan-finding-group-row" data-level="${node.level}">
        <td>${renderSecurityScanSeverity(node.group.maxSeverity)}</td>
        <td colspan="7">
          <div class="security-scan-finding-group">
            <button class="security-scan-finding-group-toggle" data-key="${escapeHtml(node.key)}" type="button" aria-expanded="${node.expanded}">
              <span class="lucide-icon icon-chevron-${node.expanded ? "down" : "right"}" aria-hidden="true"></span>
              <span class="security-scan-finding-group-kind">${SECURITY_SCAN_FINDING_GROUP_KINDS[node.groupBy]}</span>
              <span class="security-scan-finding-group-label" title="${escapeHtml(label.title)}">${escapeHtml(label.text)}</span>
              ${label.detail ? `<span class="security-scan-finding-group-detail">${escapeHtml(label.detail)}</span>` : ""}
            </button>
            ${renderSecurityScanFindingGroupCounts(node.group)}
            ${node.groupBy === "PACKAGE" && securityScanCanMutate("nexus:security-scanning-waivers:create")
              ? `<button class="row-action security-scan-finding-group-waive" data-key="${escapeHtml(node.key)}" type="button" title="Waive every advisory of this package version">waive package</button>`
              : ""}
          </div>
        </td>
      </tr>`);
  if (!node.expanded) return;
  const childLevel = node.level + 1;
  node.children.forEach((child) => {
    if (child.finding) rows.push(renderSecurityScanFindingRow(child.finding, childLevel));
    else appendSecurityScanFindingNodeRows(rows, child);
  });
  const status = node.loading
    ? "Loading…"
    : node.error || (node.loaded && node.children.length === 0
      ? "No findings remain in this group."
      : "");
  if (status) {
    rows.push(`<tr class="security-scan-finding-tree-status" data-level="${childLevel}"><td colspan="8" class="placeholder">${escapeHtml(status)}</td></tr>`);
  } else if (node.hasMore) {
    rows.push(`<tr class="security-scan-finding-tree-status" data-level="${childLevel}"><td colspan="8"><button class="row-action security-scan-finding-group-more" data-key="${escapeHtml(node.key)}" type="button">Show more</button></td></tr>`);
  }
}

function renderSecurityScanFindingWaiverStatus(finding) {
  const expired = Number(finding.expiredWaiverCount || 0);
  const targetCount = Number(finding.waiverTargetCount || 0);
  const waivedTargetCount = Number(finding.waivedTargetCount || 0);
  if (waivedTargetCount > 0) {
    const label = waivedTargetCount < targetCount
      ? `Partially waived · ${waivedTargetCount}/${targetCount}`
      : "Waived";
    return `<button class="state-badge compact ok security-scan-waiver-status-button security-scan-finding-waiver-detail" data-id="${escapeHtml(finding.id)}" type="button" title="View applicable waiver details">${escapeHtml(label)}</button>`;
  }
  if (expired > 0) {
    const label = expired === 1 ? "Expired" : `Expired · ${expired}`;
    return `<button class="state-badge compact warn security-scan-waiver-status-button security-scan-finding-waiver-detail" data-id="${escapeHtml(finding.id)}" type="button" title="View expired waiver details">${escapeHtml(label)}</button>`;
  }
  return '<span class="state-badge compact">Not waived</span>';
}

function renderSecurityScanFindingWaiverAction(finding) {
  if (finding.canCreateWaiver !== true) return "";
  const targetCount = Number(finding.waiverTargetCount || 0);
  const waivedTargetCount = Number(finding.waivedTargetCount || 0);
  const fullyWaived = targetCount > 0 && waivedTargetCount >= targetCount;
  if (fullyWaived) {
    return `<button class="row-action security-scan-finding-waive" data-id="${escapeHtml(finding.id)}" type="button" title="All associated repository artifacts are already waived" disabled>waived</button>`;
  }
  const label = waivedTargetCount > 0 ? "waive remaining" : "waive";
  return `<button class="row-action security-scan-finding-waive" data-id="${escapeHtml(finding.id)}" type="button">${label}</button>`;
}

function renderSecurityScanFindingActions(finding) {
  const view = `<button class="row-action security-scan-finding-view" data-id="${escapeHtml(finding.id)}" type="button" title="View finding details">view</button>`;
  return `${view}${renderSecurityScanFindingWaiverAction(finding)}`;
}

export {
  renderSecurityScanFindingAdvisory,
  renderSecurityScanFindings,
  securityScanFindingArtifactBrowseUrl,
};
