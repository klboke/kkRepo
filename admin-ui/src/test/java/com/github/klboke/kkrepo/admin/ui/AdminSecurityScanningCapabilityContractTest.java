package com.github.klboke.kkrepo.admin.ui;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import org.junit.jupiter.api.Test;

class AdminSecurityScanningCapabilityContractTest {

  @Test
  void deploymentGateKeepsScanningControlsDisabledUntilCapabilityIsAvailable()
      throws IOException {
    String index = resource("/META-INF/resources/admin/index.html");
    String javascript = resource("/META-INF/resources/admin/assets/admin.js");
    String css = resource("/META-INF/resources/admin/assets/admin.css");

    assertTrue(index.contains("id=\"security-scan-capability-banner\""));
    assertTrue(index.contains("id=\"security-scan-capability-content\""));
    assertTrue(index.contains("aria-disabled=\"true\" inert"));
    assertTrue(javascript.contains("applySecurityScanDeploymentState"));
    assertTrue(javascript.contains("securityScanState.summary?.deploymentEnabled === true"));
    assertTrue(javascript.contains("content.inert = !available"));
    assertTrue(javascript.contains("control.disabled = true"));
    assertTrue(javascript.contains("banner.hidden = available"));
    assertTrue(javascript.contains("banner.replaceChildren()"));
    assertFalse(javascript.contains(
        "Artifact scanning is available in this deployment."));
    assertFalse(javascript.contains(
        "Repository administrators decide which repositories to scan"));
    assertTrue(javascript.contains("KKREPO_SECURITY_SCANNING_ENABLED=true"));
    assertTrue(css.contains(".security-scan-capability-banner[hidden]"));
    assertTrue(css.contains(".is-deployment-disabled .security-scan-capability-content"));
    assertTrue(css.contains("margin: 0 24px 16px"));
    assertTrue(css.contains(".security-scan-panel > .nx-table-frame"));
    assertTrue(css.contains("margin-right: 16px"));
  }

  @Test
  void repositoryFormShowsBusinessSettingsWithoutExposingInternalIds() throws IOException {
    String index = resource("/META-INF/resources/admin/index.html");
    String javascript = resource("/META-INF/resources/admin/assets/admin.js");
    String css = resource("/META-INF/resources/admin/assets/admin.css");

    assertTrue(index.contains(
        "id=\"security-scan-repository-form-modal\" "
            + "data-form-id=\"security-scan-repository-form\""));
    assertTrue(index.contains(
        "class=\"blobstore-form modal-form security-scan-form\" "
            + "id=\"security-scan-repository-form\" hidden"));
    assertTrue(index.contains("id=\"security-scan-profile-name\" type=\"text\" disabled"));
    assertTrue(index.contains("id=\"security-scan-profile-id\" type=\"hidden\""));
    assertTrue(index.contains(
        "id=\"security-scan-repository-policy-name\" type=\"text\" disabled"));
    assertTrue(index.contains("id=\"security-scan-policy-id\" type=\"hidden\""));
    assertFalse(index.contains("<span>Profile ID</span>"));
    assertFalse(index.contains("<span>Policy ID</span>"));

    assertTrue(index.contains("<span>Result validity</span>"));
    assertTrue(index.contains("<option value=\"86400\">1 day</option>"));
    assertTrue(index.contains("<option value=\"604800\">7 days</option>"));
    assertTrue(index.contains("<option value=\"2592000\">30 days</option>"));
    assertTrue(index.contains(
        "<details class=\"security-scan-advanced\" id=\"security-scan-advanced\">"));

    assertTrue(javascript.contains("repository.profileName || \"Unavailable profile\""));
    assertTrue(javascript.contains(
        "repository.policyName || \"Built-in critical baseline\""));
    assertTrue(javascript.contains("hostedField.hidden = !showHosted"));
    assertTrue(javascript.contains("proxyField.hidden = !showProxy"));
    assertTrue(javascript.contains("repositoryType === \"PROXY\""));
    assertTrue(javascript.contains("repositoryType === \"HOSTED\""));
    assertTrue(javascript.contains(
        "openFormModal(\"security-scan-repository-form\", \"security-scan-enabled\")"));
    assertTrue(javascript.contains("closeFormModal(\"security-scan-repository-form\")"));
    assertTrue(css.contains(
        ".security-scan-config-grid > label:not(.checkbox-field) {\n"
            + "  align-self: start;\n"
            + "}"));
  }

  @Test
  void policyCreateAndEditReuseTheStandardModalForm() throws IOException {
    String index = resource("/META-INF/resources/admin/index.html");
    String javascript = resource("/META-INF/resources/admin/assets/admin.js");

    assertTrue(index.contains(
        "id=\"security-scan-policy-form-modal\" data-form-id=\"security-scan-policy-form\""));
    assertTrue(index.contains(
        "class=\"blobstore-form modal-form security-scan-form\" "
            + "id=\"security-scan-policy-form\" hidden novalidate"));
    assertTrue(index.contains("id=\"security-scan-create-policy-button\""));
    assertTrue(index.contains("id=\"security-scan-cancel-policy-button\""));
    assertTrue(index.contains("id=\"security-scan-save-policy-button\""));
    assertTrue(index.contains("<option value=\"604800\">7 days</option>"));

    assertTrue(javascript.contains("showCreateSecurityScanPolicyForm"));
    assertTrue(javascript.contains("showEditSecurityScanPolicyForm"));
    assertTrue(javascript.contains("openFormModal(\"security-scan-policy-form\""));
    assertTrue(javascript.contains("closeFormModal(\"security-scan-policy-form\")"));
    assertTrue(javascript.contains("security-scan-policy-edit"));
    assertTrue(javascript.contains("method: editing ? \"PUT\" : \"POST\""));
    assertTrue(javascript.contains("Repositories using revision"));
  }

  @Test
  void waiverCreationStartsFromAnExactFindingAndUsesTheStandardModalForm() throws IOException {
    String index = resource("/META-INF/resources/admin/index.html");
    String javascript = resource("/META-INF/resources/admin/assets/admin.js");

    assertTrue(index.contains(
        "id=\"security-scan-waiver-form-modal\" "
            + "data-form-id=\"security-scan-waiver-form\""));
    assertTrue(index.indexOf("id=\"security-scan-waiver-form-modal\"")
        < index.indexOf("data-scan-panel=\"findings\""));
    assertTrue(index.contains(
        "class=\"blobstore-form modal-form security-scan-form\" "
            + "id=\"security-scan-waiver-form\" hidden novalidate"));
    assertFalse(index.contains("id=\"security-scan-create-waiver-button\""));
    assertTrue(index.contains("id=\"security-scan-cancel-waiver-button\""));
    assertTrue(index.contains("id=\"security-scan-save-waiver-button\""));
    assertTrue(index.contains("id=\"security-scan-waiver-finding-id\" type=\"hidden\""));
    assertTrue(index.contains("id=\"security-scan-waiver-finding\" type=\"text\" disabled"));
    assertTrue(index.contains("id=\"security-scan-waiver-package\" type=\"text\" disabled"));
    assertTrue(index.contains("id=\"security-scan-waiver-target\" required"));
    assertTrue(index.contains("id=\"security-scan-waiver-duration\" required"));
    assertTrue(index.contains("<option value=\"604800\" selected>7 days</option>"));
    assertTrue(index.contains("<option value=\"never\">Never expires</option>"));
    assertTrue(index.contains(
        "id=\"security-scan-waiver-reason\" rows=\"2\" required"));
    assertFalse(index.contains("<span>Asset ID</span>"));
    assertFalse(index.contains("<span>Advisory selector</span>"));

    assertTrue(javascript.contains("showCreateSecurityScanWaiverForm"));
    assertTrue(javascript.contains(
        "/internal/security/scanning/findings/${encodeURIComponent(findingId)}/waiver-context"));
    assertTrue(javascript.contains("security-scan-finding-waive"));
    assertTrue(javascript.contains("findingId: securityScanWaiverContext.findingId"));
    assertTrue(javascript.contains("const neverExpires = durationValue === \"never\""));
    assertTrue(javascript.contains("expiresAt: neverExpires"));
    assertTrue(javascript.contains("? null"));
    assertTrue(javascript.contains(": new Date(Date.now() + durationSeconds * 1000).toISOString()"));
    assertTrue(javascript.contains("\"Never expires\""));
    assertTrue(javascript.contains("closeFormModal(\"security-scan-waiver-form\")"));
    assertTrue(javascript.contains("securityScanWaiverRequiredFields"));
    assertTrue(javascript.contains("selectSecurityScanTab(\"findings\")"));
  }

  @Test
  void findingsExposeWaiverStatusAndWaiversRemainAnIndependentGovernanceTab()
      throws IOException {
    String index = resource("/META-INF/resources/admin/index.html");
    String javascript = resource("/META-INF/resources/admin/assets/admin.js");
    String css = resource("/META-INF/resources/admin/assets/admin.css");

    assertTrue(index.contains("data-scan-tab=\"policies\""));
    assertTrue(index.contains("data-scan-tab=\"waivers\""));
    assertTrue(index.contains(
        "id=\"security-scan-panel-waivers\" data-scan-panel=\"waivers\" "
            + "role=\"tabpanel\" aria-labelledby=\"security-scan-tab-waivers\" hidden"));
    assertFalse(index.contains("Policies &amp; Waivers"));
    assertTrue(index.contains(
        "<th>Waiver</th><th class=\"actions-column security-scan-finding-actions\">"
            + "Actions</th>"));
    assertTrue(index.contains("<th>Repository</th><th>Artifact</th><th>Exception</th>"));
    assertTrue(index.contains(
        "id=\"security-scan-waiver-detail-modal\" "
            + "data-form-id=\"security-scan-waiver-detail\""));
    assertTrue(index.indexOf("id=\"security-scan-waiver-detail-modal\"")
        < index.indexOf("data-scan-panel=\"findings\""));

    assertTrue(javascript.contains("activeWaiverCount"));
    assertTrue(javascript.contains("expiredWaiverCount"));
    assertTrue(javascript.contains("waiverTargetCount"));
    assertTrue(javascript.contains("waivedTargetCount"));
    assertTrue(javascript.contains("Partially waived · ${waivedTargetCount}/${targetCount}"));
    assertTrue(javascript.contains("renderSecurityScanFindingWaiverAction"));
    assertTrue(javascript.contains("waive remaining"));
    assertTrue(javascript.contains("already waived\" disabled>waived"));
    assertFalse(javascript.contains("`Waived · ${active}`"));
    assertTrue(javascript.contains("waiver.assetPath || \"All artifacts\""));
    assertTrue(javascript.contains("security-scan-finding-waiver-detail"));
    assertTrue(javascript.contains(
        "/internal/security/scanning/findings/${encodeURIComponent(findingId)}/waivers"));
    assertTrue(javascript.contains("selectSecurityScanTab(\"waivers\")"));
    assertTrue(css.contains(".security-scan-waiver-status-button"));
    assertTrue(css.contains(".security-scan-waiver-detail-card"));
  }

  @Test
  void findingsKeepSecondaryMetadataInAnAccessibleDetailsModal() throws IOException {
    String index = resource("/META-INF/resources/admin/index.html");
    String javascript = resource("/META-INF/resources/admin/assets/admin.js");
    String css = resource("/META-INF/resources/admin/assets/admin.css");

    assertTrue(index.contains(
        "<thead><tr><th>Severity</th><th>Advisory</th><th>Repositories</th>"
            + "<th>Package</th><th>Installed</th><th>Fixed</th><th>Waiver</th>"
            + "<th class=\"actions-column security-scan-finding-actions\">Actions</th></tr></thead>"));
    assertFalse(index.contains(
        "<th>Fixed</th><th>Source</th><th>Run</th><th>Title</th><th>Waiver</th>"));
    assertTrue(index.contains(
        "id=\"security-scan-finding-detail-modal\" "
            + "data-form-id=\"security-scan-finding-detail\""));
    assertTrue(index.indexOf("id=\"security-scan-finding-detail-modal\"")
        < index.indexOf("data-scan-panel=\"findings\""));

    assertTrue(javascript.contains("function renderSecurityScanFindingActions(finding)"));
    assertTrue(javascript.contains("function renderSecurityScanFindingRepositories(finding)"));
    assertTrue(javascript.contains("finding.repositories.filter(Boolean)"));
    assertTrue(javascript.contains(
        "CRITICAL: { tone: \"is-critical\", icon: \"octagon-alert\" }"));
    assertTrue(javascript.contains(
        "HIGH: { tone: \"is-high\", icon: \"triangle-alert\" }"));
    assertTrue(javascript.contains(
        "MEDIUM: { tone: \"is-medium\", icon: \"circle-alert\" }"));
    assertTrue(javascript.contains("LOW: { tone: \"is-low\", icon: \"info\" }"));
    assertTrue(javascript.contains(
        "UNKNOWN: { tone: \"is-unknown\", icon: \"circle-help\" }"));
    assertTrue(javascript.indexOf("renderSecurityScanSeverity(finding.severity)")
        != javascript.lastIndexOf("renderSecurityScanSeverity(finding.severity)"));
    assertTrue(javascript.contains("function securityScanExternalHttpUrl(value)"));
    assertTrue(javascript.contains("[\"http:\", \"https:\"].includes(url.protocol)"));
    assertTrue(javascript.contains("[finding.primaryUrl, finding.dataSource]"));
    assertTrue(javascript.contains("function renderSecurityScanFindingAdvisory(finding)"));
    assertTrue(javascript.contains("target=\"_blank\""));
    assertTrue(javascript.contains("rel=\"noopener noreferrer\""));
    assertTrue(javascript.indexOf("renderSecurityScanFindingAdvisory(finding)")
        != javascript.lastIndexOf("renderSecurityScanFindingAdvisory(finding)"));
    assertTrue(javascript.contains("security-scan-finding-view"));
    assertTrue(javascript.contains("function showSecurityScanFindingDetail(findingId)"));
    assertTrue(javascript.contains("[\"Source\", finding.dataSource]"));
    assertTrue(javascript.contains("[\"Scan run\", finding.scanRunId]"));
    assertTrue(javascript.contains("security-scan-finding-detail-highlights"));
    assertTrue(javascript.contains("security-scan-finding-detail-sections"));
    assertTrue(javascript.contains("openFormModal(\"security-scan-finding-detail\""));
    assertTrue(javascript.contains("closeFormModal(\"security-scan-finding-detail\")"));
    assertTrue(javascript.contains("colspan=\"8\""));
    assertFalse(css.contains(".security-scan-finding-detail-grid"));
    assertTrue(css.contains(".security-scan-finding-detail-hero"));
    assertTrue(css.contains(".security-scan-finding-detail-section dl > div"));
    assertTrue(css.contains(".security-scan-finding-actions"));
    assertTrue(css.contains(".security-scan-severity.is-critical"));
    assertTrue(css.contains(".security-scan-severity.is-high"));
    assertTrue(css.contains(".security-scan-severity.is-medium"));
    assertTrue(css.contains(".security-scan-severity.is-low"));
    assertTrue(css.contains(".security-scan-severity.is-unknown"));
    assertTrue(css.contains(".security-scan-advisory-link"));
    assertTrue(css.contains(".security-scan-advisory-link:focus-visible"));
  }

  @Test
  void scannerStatusAndSectionNavigationUseVisualAndAccessibleState() throws IOException {
    String index = resource("/META-INF/resources/admin/index.html");
    String javascript = resource("/META-INF/resources/admin/assets/admin.js");
    String css = resource("/META-INF/resources/admin/assets/admin.css");

    assertTrue(javascript.contains("{ tone: \"is-ready\", icon: \"check\" }"));
    assertTrue(javascript.contains("{ tone: \"is-degraded\", icon: \"info\" }"));
    assertTrue(javascript.contains("{ tone: \"is-disabled\", icon: \"circle-slash\" }"));
    assertTrue(javascript.contains("security-scan-scanner-state ${scannerPresentation.tone}"));
    assertTrue(css.contains(".security-scan-scanner-state.is-ready"));
    assertTrue(css.contains(".security-scan-scanner-state.is-degraded"));
    assertTrue(css.contains(".security-scan-scanner-state.is-disabled"));
    assertTrue(index.contains(
        "<thead><tr><th>Status</th><th>Repository</th><th>Format</th><th>Type</th>"));
    assertTrue(javascript.contains("function renderSecurityScanRepositoryStatus(enabled)"));
    assertTrue(javascript.contains(
        "{ label: \"Enabled\", tone: \"ok\", icon: \"check\" }"));
    assertTrue(javascript.contains(
        "{ label: \"Disabled\", tone: \"is-disabled\", icon: \"circle-slash\" }"));
    assertTrue(javascript.contains(
        "<td>${renderSecurityScanRepositoryStatus(config?.enabled === true)}</td>"));
    assertTrue(css.contains(".security-scan-repository-status.is-disabled"));
    assertTrue(css.contains(
        "grid-template-columns: repeat(10, minmax(136px, 1fr));"));
    assertTrue(css.contains(".security-scan-summary strong {\n  white-space: nowrap;"));
    assertTrue(css.contains(".security-scan-database-revision"));
    assertTrue(javascript.contains(
        "databaseRevision.match(/^(\\d{4}-\\d{2}-\\d{2})T(.+)$/)"));
    assertTrue(javascript.contains(
        "class=\"security-scan-database-revision\">${databaseRevisionMarkup}"));
    assertTrue(css.contains("overflow-x: auto;"));

    for (String tab :
        new String[] {"overview", "tasks", "findings", "repositories", "policies", "waivers"}) {
      assertTrue(index.contains(
          "class=\"security-scan-tab"
              + ("overview".equals(tab) ? " is-active" : "")
              + "\" id=\"security-scan-tab-" + tab + "\" data-scan-tab=\"" + tab + "\""));
      assertTrue(index.contains(
          "id=\"security-scan-panel-" + tab + "\" data-scan-panel=\"" + tab
              + "\" role=\"tabpanel\" aria-labelledby=\"security-scan-tab-" + tab + "\""));
    }
    assertTrue(javascript.contains("button.tabIndex = active ? 0 : -1"));
    assertTrue(javascript.contains("event.key === \"ArrowRight\""));
    assertTrue(javascript.contains("event.key === \"ArrowLeft\""));
    assertTrue(javascript.contains("event.key === \"Home\""));
    assertTrue(javascript.contains("event.key === \"End\""));
    assertTrue(javascript.contains(
        "button.addEventListener(\"keydown\", handleSecurityScanTabKeydown)"));
    assertTrue(css.contains(".security-scan-tab.is-active"));
    assertFalse(index.contains("row-action is-active\" data-scan-tab"));
  }

  @Test
  void everyScanningListUsesSearchAndCursorPagination() throws IOException {
    String index = resource("/META-INF/resources/admin/index.html");
    String javascript = resource("/META-INF/resources/admin/assets/admin.js");
    String css = resource("/META-INF/resources/admin/assets/admin.css");

    for (String list :
        new String[] {"runs", "tasks", "findings", "repositories", "policies", "waivers"}) {
      assertTrue(index.contains("data-security-scan-list-form=\"" + list + "\""));
      assertTrue(index.contains("data-security-scan-query=\"" + list + "\""));
      assertTrue(index.contains("data-security-scan-pagination=\"" + list + "\""));
      assertTrue(index.contains("data-security-scan-page-size=\"" + list + "\""));
      int sizeSelectStart = index.indexOf("data-security-scan-page-size=\"" + list + "\"");
      int sizeSelectEnd = index.indexOf("</select>", sizeSelectStart);
      String sizeSelect = index.substring(sizeSelectStart, sizeSelectEnd);
      assertTrue(sizeSelect.contains("<option value=\"10\" selected>10</option>"));
      assertFalse(sizeSelect.contains("<option value=\"5\""));
      assertFalse(sizeSelect.contains("<option value=\"25\" selected>"));
      assertTrue(index.contains(
          "class=\"create-button secondary\" data-security-scan-clear=\"" + list + "\""));
    }
    assertTrue(javascript.contains("const SECURITY_SCAN_DEFAULT_PAGE_SIZE = 10;"));
    assertTrue(javascript.contains("params.set(\"after\""));
    assertTrue(javascript.contains("params.set(\"limit\""));
    assertTrue(javascript.contains("params.set(\"q\", page.query)"));
    assertTrue(javascript.contains("page.cursors.push(page.after)"));
    assertTrue(javascript.contains("page.nextAfter == null"));
    assertTrue(javascript.contains("payload?.nextAfter ?? null"));
    assertFalse(javascript.contains("findings?limit=100"));
    assertFalse(javascript.contains("waivers?limit=100"));
    assertFalse(index.contains(
        "data-scan-panel=\"policies\" hidden>\n            <section class=\"ops-panel\">"));
    assertFalse(index.contains(
        "data-scan-panel=\"waivers\" hidden>\n            <section class=\"ops-panel\">"));
    assertTrue(css.contains(".security-scan-list-toolbar"));
    assertTrue(css.contains(".security-scan-pagination"));
  }

  private String resource(String path) throws IOException {
    try (InputStream stream = getClass().getResourceAsStream(path)) {
      return new String(Objects.requireNonNull(stream).readAllBytes(), StandardCharsets.UTF_8);
    }
  }
}
