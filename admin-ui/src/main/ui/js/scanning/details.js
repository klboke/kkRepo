import { escapeHtml, lucideIcon } from "../controls/display.js";
import { closeFormModal, openFormModal } from "../controls/modal.js";
import { showToast } from "../controls/toast.js";
import { renderSecurityScanFindingAdvisory, securityScanFindingArtifactBrowseUrl } from "./findings.js";
import { renderSecurityScanSeverity } from "./overview.js";
import { securityScanPages, securityScanState } from "./pages.js";
import { responseErrorMessage } from "../shell/http.js";

let securityScanFindingArtifactDetail = null;

function securityScanFindingDetailValue(value) {
  if (Array.isArray(value)) return value.filter(Boolean).join(", ") || "-";
  return value == null || value === "" ? "-" : String(value);
}

function renderSecurityScanFindingDetailSection(title, fields) {
  return `
    <section class="security-scan-finding-detail-section">
      <h3>${escapeHtml(title)}</h3>
      <dl>
        ${fields.map(([label, value]) => `
          <div>
            <dt>${escapeHtml(label)}</dt>
            <dd>${escapeHtml(securityScanFindingDetailValue(value))}</dd>
          </div>`).join("")}
      </dl>
    </section>`;
}

function renderSecurityScanFindingArtifactDetail(finding) {
  const count = Number(finding.affectedArtifactCount || 0);
  return `
    <section class="security-scan-finding-detail-section security-scan-finding-artifact-section">
      <div class="security-scan-finding-artifact-heading">
        <h3>Affected artifacts</h3>
        <span>${escapeHtml(count)} visible</span>
      </div>
      <div class="security-scan-finding-artifact-list" id="security-scan-finding-artifact-list">
        <div class="placeholder">Loading affected artifacts…</div>
      </div>
      <button class="row-action security-scan-finding-artifacts-more" id="security-scan-finding-artifacts-more" type="button" hidden>Show more</button>
    </section>`;
}

function renderSecurityScanFindingArtifactItems() {
  const state = securityScanFindingArtifactDetail;
  if (!state) return;
  const list = document.getElementById("security-scan-finding-artifact-list");
  const more = document.getElementById("security-scan-finding-artifacts-more");
  if (!list || !more) return;
  list.innerHTML = state.items.map((artifact) => {
    const source = artifact.sourceRepository && artifact.sourceRepository !== artifact.repository
      ? `<span>Source: ${escapeHtml(artifact.sourceRepository)}</span>`
      : "";
    return `
      <a class="security-scan-finding-artifact-link"
         href="${escapeHtml(securityScanFindingArtifactBrowseUrl(artifact))}">
        <strong>${escapeHtml(artifact.repository)}</strong>
        <code>${escapeHtml(artifact.browsePath || artifact.assetPath)}</code>
        ${source}
        ${lucideIcon("external-link")}
      </a>`;
  }).join("") || '<div class="placeholder">No currently visible artifact is associated with this finding.</div>';
  more.hidden = state.nextRepositoryId == null || state.nextAssetId == null;
  more.disabled = state.loading;
  more.textContent = state.loading ? "Loading…" : "Show more";
}

async function loadSecurityScanFindingArtifacts(findingId, reset = false) {
  if (reset || !securityScanFindingArtifactDetail
      || Number(securityScanFindingArtifactDetail.findingId) !== Number(findingId)) {
    securityScanFindingArtifactDetail = {
      findingId: Number(findingId),
      currentOnly: securityScanPages.findings.currentOnly,
      items: [],
      nextRepositoryId: 0,
      nextAssetId: 0,
      loading: false
    };
  }
  const state = securityScanFindingArtifactDetail;
  if (state.loading || state.nextRepositoryId == null || state.nextAssetId == null) return;
  state.loading = true;
  renderSecurityScanFindingArtifactItems();
  const params = new URLSearchParams({
    currentOnly: String(state.currentOnly),
    afterRepositoryId: String(state.nextRepositoryId),
    afterAssetId: String(state.nextAssetId),
    limit: "25"
  });
  try {
    const response = await fetch(
      `/internal/security/scanning/findings/${encodeURIComponent(findingId)}/artifacts?${params.toString()}`,
      { cache: "no-store" });
    if (!response.ok) throw new Error(await responseErrorMessage(response));
    const page = await response.json();
    const seen = new Set(state.items.map(
      (artifact) => `${artifact.repositoryId}:${artifact.assetId}`));
    for (const artifact of page.items || []) {
      const key = `${artifact.repositoryId}:${artifact.assetId}`;
      if (seen.has(key)) continue;
      seen.add(key);
      state.items.push(artifact);
    }
    state.nextRepositoryId = page.nextRepositoryId ?? null;
    state.nextAssetId = page.nextAssetId ?? null;
  } catch (error) {
    showToast(`Unable to load affected artifacts: ${error.message}`, "error");
    state.nextRepositoryId = null;
    state.nextAssetId = null;
  } finally {
    state.loading = false;
    renderSecurityScanFindingArtifactItems();
  }
}

function showSecurityScanFindingDetail(findingId) {
  const finding = securityScanState.findings.find(
    (item) => Number(item.id) === Number(findingId));
  if (!finding) {
    showToast("Finding details are no longer available. Refresh and try again.", "error");
    return;
  }
  const waiverCoverage = Number(finding.waiverTargetCount || 0) > 0
    ? `${Number(finding.waivedTargetCount || 0)} of ${Number(finding.waiverTargetCount)} artifacts waived`
    : `${Number(finding.activeWaiverCount || 0)} active, ${Number(finding.expiredWaiverCount || 0)} expired`;
  const packageLabel =
    [finding.packageName || finding.packageUrl || "Unknown package", finding.installedVersion]
      .filter(Boolean)
      .join(" @ ");
  document.getElementById("security-scan-finding-detail-title").textContent = "Finding details";
  document.getElementById("security-scan-finding-detail-content").innerHTML = `
    <div class="security-scan-finding-detail-hero">
      <div class="security-scan-finding-detail-kicker">
        ${renderSecurityScanSeverity(finding.severity)}
        ${renderSecurityScanFindingAdvisory(finding)}
      </div>
      <h3>${escapeHtml(finding.title || "Known vulnerability finding")}</h3>
      <p>${escapeHtml(packageLabel)}</p>
    </div>
    <div class="security-scan-finding-detail-highlights">
      <div><span>Repositories</span><strong>${escapeHtml(securityScanFindingDetailValue(finding.repositories))}</strong></div>
      <div><span>Fixed versions</span><strong>${escapeHtml(securityScanFindingDetailValue(finding.fixedVersions))}</strong></div>
      <div><span>Waiver coverage</span><strong>${escapeHtml(waiverCoverage)}</strong></div>
    </div>
    ${renderSecurityScanFindingArtifactDetail(finding)}
    <div class="security-scan-finding-detail-sections">
      ${renderSecurityScanFindingDetailSection("Vulnerability", [
        ["Aliases", finding.aliases],
        ["CVSS score", finding.cvssScore],
        ["CVSS vector", finding.cvssVector],
        ["Severity source", finding.severitySource],
        ["Source", finding.dataSource],
        ["Source status", finding.sourceStatus]
      ])}
      ${renderSecurityScanFindingDetailSection("Traceability", [
        ["Scan run", finding.scanRunId],
        ["Finding ID", finding.id],
        ["Package URL", finding.packageUrl],
        ["Locations", finding.locations],
        ["Primary URL", finding.primaryUrl]
      ])}
    </div>`;
  openFormModal("security-scan-finding-detail", "security-scan-close-finding-detail-button");
  loadSecurityScanFindingArtifacts(finding.id, true);
}

function hideSecurityScanFindingDetail() {
  securityScanFindingArtifactDetail = null;
  closeFormModal("security-scan-finding-detail");
}

export {
  hideSecurityScanFindingDetail,
  loadSecurityScanFindingArtifacts,
  securityScanFindingArtifactDetail,
  showSecurityScanFindingDetail,
};
