import { bindRequiredFieldErrors } from "../controls/fields.js";
import { showToast } from "../controls/toast.js";
import {
  hideSecurityScanFindingDetail,
  loadSecurityScanFindingArtifacts,
  securityScanFindingArtifactDetail,
  showSecurityScanFindingDetail,
} from "./details.js";
import { handleSecurityScanTabKeydown, loadSecurityScanning, selectSecurityScanTab } from "./overview.js";
import {
  clearSecurityScanListSearch,
  filterCurrentSecurityScanFindings,
  filterSecurityScanFindings,
  moveSecurityScanPage,
  resizeSecurityScanPage,
  searchSecurityScanList,
  securityScanPages,
  showMoreSecurityScanFindingNode,
  sortSecurityScanPage,
  toggleSecurityScanFindingNode,
} from "./pages.js";
import {
  deleteSecurityScanPolicy,
  editSecurityScanRepository,
  hideSecurityScanPolicyForm,
  hideSecurityScanRepositoryForm,
  saveSecurityScanPolicy,
  saveSecurityScanRepository,
  securityScanPolicyRequiredFields,
  showCreateSecurityScanPolicyForm,
  showEditSecurityScanPolicyForm,
  updateSecurityScanRepositoryPolicyHelp,
} from "./policies.js";
import { securityScanRescan, securityScanTaskAction } from "./tasks.js";
import {
  changeSecurityScanPackageWaiverScope,
  createSecurityScanPackageWaiver,
  createSecurityScanWaiver,
  deleteSecurityScanWaiver,
  hideSecurityScanPackageWaiverForm,
  hideSecurityScanWaiverDetail,
  hideSecurityScanWaiverForm,
  securityScanPackageWaiverRequiredFields,
  securityScanWaiverRequiredFields,
  showCreateSecurityScanWaiverForm,
  showSecurityScanPackageWaiverForm,
  showSecurityScanWaiverDetail,
  viewAllSecurityScanWaivers,
} from "./waivers.js";

function bindScanSortControls() {
  ["tasks", "runs"].forEach((key) => {
    document.querySelectorAll(`[data-${key}-sort]`).forEach((button) => {
      button.addEventListener("click", () => sortSecurityScanPage(
        key, securityScanPages[key].direction === "desc" ? "asc" : "desc"));
    });
  });
}

function bindScanPolicyTable() {
  document.getElementById("security-scan-policy-table").addEventListener("click", (event) => {
    const editButton = event.target.closest(".security-scan-policy-edit");
    if (editButton) {
      showEditSecurityScanPolicyForm(editButton.dataset.id);
      return;
    }
    const deleteButton = event.target.closest(".security-scan-policy-delete");
    if (deleteButton) deleteSecurityScanPolicy(deleteButton.dataset.id, deleteButton);
  });
}

export function bindScanningEvents() {
  document.getElementById("security-scan-refresh-button").addEventListener("click", loadSecurityScanning);
  document.querySelectorAll("[data-scan-tab]").forEach((button) => {
    button.addEventListener("click", () => selectSecurityScanTab(button.dataset.scanTab));
    button.addEventListener("keydown", handleSecurityScanTabKeydown);
  });
  document.querySelectorAll("[data-security-scan-list-form]").forEach((form) => {
    form.addEventListener("submit", (event) => {
      event.preventDefault();
      searchSecurityScanList(form.dataset.securityScanListForm);
    });
  });
  document.querySelectorAll("[data-security-scan-clear]").forEach((button) => {
    button.addEventListener(
      "click",
      () => clearSecurityScanListSearch(button.dataset.securityScanClear));
  });
  document.querySelectorAll("[data-security-scan-page-action]").forEach((button) => {
    button.addEventListener(
      "click",
      () => moveSecurityScanPage(
        button.dataset.securityScanPageList,
        button.dataset.securityScanPageAction));
  });
  document.getElementById("security-scan-current-findings").addEventListener("change", (event) => {
    filterCurrentSecurityScanFindings(event.target.checked)
      .catch((error) => showToast(error.message, "error"));
  });
  [
    ["security-scan-group-package", "groupByPackage"],
    ["security-scan-group-artifact", "groupByArtifact"],
    ["security-scan-hide-waived", "hideWaived"]
  ].forEach(([id, option]) => {
    document.getElementById(id).addEventListener("change", (event) => {
      filterSecurityScanFindings({ [option]: event.target.checked })
        .catch((error) => showToast(error.message, "error"));
    });
  });
  bindScanSortControls();
  document.querySelectorAll("[data-security-scan-page-size]").forEach((select) => {
    select.addEventListener(
      "change",
      () => resizeSecurityScanPage(select.dataset.securityScanPageSize, select.value));
  });
  document.getElementById("security-scan-repository-table").addEventListener("click", (event) => {
    const editButton = event.target.closest(".security-scan-repository-edit");
    if (editButton) editSecurityScanRepository(editButton.dataset.id);
  });
  document.getElementById("security-scan-repository-form").addEventListener("submit", saveSecurityScanRepository);
  ["security-scan-policy-id", "security-scan-max-age"].forEach((id) => {
    document.getElementById(id).addEventListener("change", updateSecurityScanRepositoryPolicyHelp);
  });
  document.getElementById("security-scan-cancel-repository-button").addEventListener(
    "click", hideSecurityScanRepositoryForm);
  document.getElementById("security-scan-create-policy-button").addEventListener(
    "click", showCreateSecurityScanPolicyForm);
  document.getElementById("security-scan-cancel-policy-button").addEventListener(
    "click", hideSecurityScanPolicyForm);
  document.getElementById("security-scan-policy-form").addEventListener(
    "submit", saveSecurityScanPolicy);
  bindRequiredFieldErrors(securityScanPolicyRequiredFields);
  bindScanPolicyTable();
  document.getElementById("security-scan-cancel-waiver-button").addEventListener(
    "click", hideSecurityScanWaiverForm);
  document.getElementById("security-scan-waiver-form").addEventListener("submit", createSecurityScanWaiver);
  document.getElementById("security-scan-package-waiver-form").addEventListener(
    "submit", createSecurityScanPackageWaiver);
  document.getElementById("security-scan-cancel-package-waiver-button").addEventListener(
    "click", hideSecurityScanPackageWaiverForm);
  document.getElementById("security-scan-package-waiver-scope").addEventListener("change", (event) => {
    changeSecurityScanPackageWaiverScope(event.target.value);
  });
  document.getElementById("security-scan-close-waiver-detail-button").addEventListener(
    "click", hideSecurityScanWaiverDetail);
  document.getElementById("security-scan-view-all-waivers-button").addEventListener(
    "click", viewAllSecurityScanWaivers);
  document.getElementById("security-scan-close-finding-detail-button").addEventListener(
    "click", hideSecurityScanFindingDetail);
  document.getElementById("security-scan-finding-detail-content").addEventListener("click", (event) => {
    const more = event.target.closest(".security-scan-finding-artifacts-more");
    if (more && securityScanFindingArtifactDetail) {
      loadSecurityScanFindingArtifacts(securityScanFindingArtifactDetail.findingId);
    }
  });
  bindRequiredFieldErrors(securityScanWaiverRequiredFields);
  bindRequiredFieldErrors(securityScanPackageWaiverRequiredFields);
  document.getElementById("security-scan-finding-table").addEventListener("click", (event) => {
    const groupToggle = event.target.closest(".security-scan-finding-group-toggle");
    if (groupToggle) {
      toggleSecurityScanFindingNode(groupToggle.dataset.key)
        .catch((error) => showToast(error.message, "error"));
      return;
    }
    const packageWaiveButton = event.target.closest(".security-scan-finding-group-waive");
    if (packageWaiveButton) {
      showSecurityScanPackageWaiverForm(packageWaiveButton.dataset.key);
      return;
    }
    const moreButton = event.target.closest(".security-scan-finding-group-more");
    if (moreButton) {
      showMoreSecurityScanFindingNode(moreButton.dataset.key)
        .catch((error) => showToast(error.message, "error"));
      return;
    }
    const viewButton = event.target.closest(".security-scan-finding-view");
    if (viewButton) {
      showSecurityScanFindingDetail(viewButton.dataset.id);
      return;
    }
    const detailButton = event.target.closest(".security-scan-finding-waiver-detail");
    if (detailButton) {
      showSecurityScanWaiverDetail(detailButton.dataset.id);
      return;
    }
    const waiveButton = event.target.closest(".security-scan-finding-waive");
    if (waiveButton) showCreateSecurityScanWaiverForm(waiveButton.dataset.id);
  });
  document.getElementById("security-scan-task-table").addEventListener("click", (event) => {
    const retryButton = event.target.closest(".security-scan-task-retry");
    if (retryButton) {
      securityScanTaskAction("retry", retryButton.dataset.id);
      return;
    }
    const cancelButton = event.target.closest(".security-scan-task-cancel");
    if (cancelButton) {
      securityScanTaskAction("cancel", cancelButton.dataset.id);
      return;
    }
    const rescanButton = event.target.closest(".security-scan-asset-rescan");
    if (rescanButton) securityScanRescan(rescanButton.dataset.id);
  });
  document.getElementById("security-scan-waiver-table").addEventListener("click", (event) => {
    const deleteButton = event.target.closest(".security-scan-waiver-delete");
    if (deleteButton) deleteSecurityScanWaiver(deleteButton.dataset.id);
  });
}
