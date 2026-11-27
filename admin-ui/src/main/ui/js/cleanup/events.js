import {
  bindCleanupRepositoryPicker,
  changeCleanupPolicyPage,
  cleanupActionMenuPolicyId,
  cleanupActionMenuTrigger,
  closeCleanupPolicyActionMenu,
  deleteCleanupPolicy,
  handleCleanupTabKeydown,
  hideCleanupPolicyForm,
  loadCleanupPolicies,
  openCleanupPolicyActionMenu,
  positionCleanupPolicyActionMenu,
  refreshCleanupCapabilityHelp,
  refreshCleanupRepositoryOptions,
  refreshCleanupScheduleFields,
  resizeCleanupPolicyPage,
  saveCleanupPolicy,
  scheduleCleanupSchedulePreview,
  selectCleanupTab,
  setCleanupPolicyScheduleEnabled,
  showCreateCleanupPolicyForm,
  showEditCleanupPolicyForm,
} from "./policies.js";
import {
  cancelCleanupRun,
  changeCleanupRunPage,
  hideCleanupTryRunDialog,
  resizeCleanupRunPage,
  setCleanupTryRunError,
  startCleanupRun,
  submitCleanupTryRun,
  viewCleanupRun,
} from "./runs.js";
import { bindFieldHelpTooltips } from "../controls/help.js";

export function bindCleanupEvents() {
  document.getElementById("create-cleanup-policy-button").addEventListener("click", showCreateCleanupPolicyForm);
  document.getElementById("refresh-cleanup-policy-button").addEventListener("click", loadCleanupPolicies);
  document.getElementById("cleanup-policy-page-prev").addEventListener(
    "click", () => changeCleanupPolicyPage("prev"));
  document.getElementById("cleanup-policy-page-next").addEventListener(
    "click", () => changeCleanupPolicyPage("next"));
  document.getElementById("cleanup-policy-page-size").addEventListener(
    "change", (event) => resizeCleanupPolicyPage(event.currentTarget.value));
  document.getElementById("cleanup-run-page-prev").addEventListener(
    "click", () => changeCleanupRunPage("prev"));
  document.getElementById("cleanup-run-page-next").addEventListener(
    "click", () => changeCleanupRunPage("next"));
  document.getElementById("cleanup-run-page-size").addEventListener(
    "change", (event) => resizeCleanupRunPage(event.currentTarget.value));
  document.getElementById("cancel-cleanup-policy-button").addEventListener("click", hideCleanupPolicyForm);
  document.getElementById("cleanup-policy-form").addEventListener("submit", saveCleanupPolicy);
  document.getElementById("cancel-cleanup-try-run-button").addEventListener("click", hideCleanupTryRunDialog);
  document.getElementById("cleanup-try-run-form").addEventListener("submit", submitCleanupTryRun);
  document.getElementById("cleanup-try-run-scan-limit").addEventListener("input", () => setCleanupTryRunError());
  bindFieldHelpTooltips();
  bindCleanupRepositoryPicker();
  document.querySelectorAll("[data-cleanup-tab]").forEach((button) => {
    button.addEventListener("click", () => selectCleanupTab(button.dataset.cleanupTab));
    button.addEventListener("keydown", handleCleanupTabKeydown);
  });
  document.getElementById("cleanup-policy-mode").addEventListener("change", () => {
    refreshCleanupCapabilityHelp();
    refreshCleanupRepositoryOptions([]);
  });
  document.getElementById("cleanup-policy-format").addEventListener("change", () => {
    refreshCleanupCapabilityHelp();
    refreshCleanupRepositoryOptions([]);
    refreshCleanupScheduleFields();
  });
  document.getElementById("cleanup-policy-cron-expression").addEventListener("input", () => {
    refreshCleanupScheduleFields();
    scheduleCleanupSchedulePreview();
  });
  document.getElementById("cleanup-policy-time-zone").addEventListener("input", scheduleCleanupSchedulePreview);
  document.getElementById("cleanup-policy-time-zone").addEventListener("change", scheduleCleanupSchedulePreview);
  document.getElementById("cleanup-policy-table").addEventListener("click", (event) => {
    const editButton = event.target.closest(".cleanup-policy-edit");
    if (editButton) {
      showEditCleanupPolicyForm(editButton.dataset.id);
      return;
    }
    const moreButton = event.target.closest(".cleanup-policy-more-actions");
    if (moreButton) openCleanupPolicyActionMenu(moreButton.dataset.id, moreButton);
  });
  document.getElementById("cleanup-policy-table").addEventListener("keydown", (event) => {
    const moreButton = event.target.closest(".cleanup-policy-more-actions");
    if (!moreButton || !["ArrowDown", "ArrowUp"].includes(event.key)) return;
    event.preventDefault();
    openCleanupPolicyActionMenu(
      moreButton.dataset.id,
      moreButton,
      event.key === "ArrowDown" ? "first" : "last");
  });
  document.getElementById("cleanup-policy-action-menu").addEventListener("click", (event) => {
    const item = event.target.closest("[data-cleanup-action]");
    if (!item || item.disabled || cleanupActionMenuPolicyId == null) return;
    const policyId = cleanupActionMenuPolicyId;
    const actionTrigger = cleanupActionMenuTrigger;
    const action = item.dataset.cleanupAction;
    const enableSchedule = item.dataset.enable === "true";
    closeCleanupPolicyActionMenu();
    if (action === "try-run") startCleanupRun(policyId, "TRY_RUN", actionTrigger);
    if (action === "execute") startCleanupRun(policyId, "EXECUTE");
    if (action === "schedule") setCleanupPolicyScheduleEnabled(policyId, enableSchedule, null);
    if (action === "delete") deleteCleanupPolicy(policyId);
  });
  document.getElementById("cleanup-policy-action-menu").addEventListener("focusout", () => {
    setTimeout(() => {
      const menu = document.getElementById("cleanup-policy-action-menu");
      if (!menu.contains(document.activeElement)
          && document.activeElement !== cleanupActionMenuTrigger) {
        closeCleanupPolicyActionMenu();
      }
    }, 0);
  });
  document.addEventListener("scroll", () => positionCleanupPolicyActionMenu(), true);
  window.addEventListener("resize", positionCleanupPolicyActionMenu);
  document.getElementById("cleanup-run-table").addEventListener("click", (event) => {
    const viewButton = event.target.closest(".cleanup-run-view");
    if (viewButton) {
      viewCleanupRun(viewButton.dataset.id, viewButton);
      return;
    }
    const cancelButton = event.target.closest(".cleanup-run-cancel");
    if (cancelButton) cancelCleanupRun(cancelButton.dataset.id);
  });
  document.getElementById("cleanup-run-detail-content").addEventListener("click", (event) => {
    const cancelButton = event.target.closest(".cleanup-run-cancel");
    if (cancelButton) cancelCleanupRun(cancelButton.dataset.id);
  });
}
