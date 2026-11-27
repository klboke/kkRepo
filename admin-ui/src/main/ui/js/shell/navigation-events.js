import { bind as bindDownloadOffload } from "../download-offload.js";
import {
  closeCleanupPolicyActionMenu,
  handleCleanupPolicyActionMenuKeydown,
  hideCleanupPolicyForm,
} from "../cleanup/policies.js";
import { hideCleanupRunDetail, hideCleanupTryRunDialog } from "../cleanup/runs.js";
import { bindFormModalDismiss, dismissFormModal, handleFormModalKeydown } from "../controls/modal.js";
import { hideRepositoryForm } from "../repositories/form.js";
import { toggleRepositorySort } from "../repositories/list.js";
import { hideSecurityScanFindingDetail } from "../scanning/details.js";
import { hideSecurityScanPolicyForm, hideSecurityScanRepositoryForm } from "../scanning/policies.js";
import {
  hideSecurityScanPackageWaiverForm,
  hideSecurityScanWaiverDetail,
  hideSecurityScanWaiverForm,
} from "../scanning/waivers.js";
import { hideSecurityPrivilegeForm, hideSecurityRoleForm, hideSecurityUserForm } from "../security/access.js";
import { hideSecurityApiKeyForm, hideSecurityApiKeyResult } from "../security/api-keys.js";
import { applyOriginAwarePlaceholders } from "../security/providers.js";
import { closeUserMenu, openUserMenu, scheduleCloseUserMenu, toggleUserMenu } from "./account.js";
import { applyHashRoute, initializeSideGroups, switchView } from "./routes.js";
import { AUTH_SNAPSHOT_KEY } from "./session.js";
import {
  closeBlobStoreActionMenu,
  handleBlobStoreActionMenuKeydown,
  hideBlobStoreForm,
  toggleBlobStoreSort,
} from "../storage/blob-stores.js";

export function bindShellEvents() {
  bindDownloadOffload();
  applyOriginAwarePlaceholders();
  initializeSideGroups();
  [
    ["repository-form", hideRepositoryForm],
    ["blobstore-form", hideBlobStoreForm],
    ["cleanup-policy-form", hideCleanupPolicyForm],
    ["cleanup-try-run-form", hideCleanupTryRunDialog],
    ["cleanup-run-detail", hideCleanupRunDetail],
    ["security-user-form", hideSecurityUserForm],
    ["security-role-form", hideSecurityRoleForm],
    ["security-privilege-form", hideSecurityPrivilegeForm],
    ["security-api-key-form", hideSecurityApiKeyForm],
    ["security-api-key-result", hideSecurityApiKeyResult],
    ["security-scan-repository-form", hideSecurityScanRepositoryForm],
    ["security-scan-policy-form", hideSecurityScanPolicyForm],
    ["security-scan-waiver-form", hideSecurityScanWaiverForm],
    ["security-scan-package-waiver-form", hideSecurityScanPackageWaiverForm],
    ["security-scan-waiver-detail", hideSecurityScanWaiverDetail],
    ["security-scan-finding-detail", hideSecurityScanFindingDetail]
  ].forEach(([formId, handler]) => bindFormModalDismiss(formId, handler));
  document.querySelectorAll(".side-item[data-view]").forEach((item) => {
    item.addEventListener("click", () => switchView(item.dataset.view));
  });
  window.addEventListener("hashchange", applyHashRoute);
  window.addEventListener("popstate", applyHashRoute);
  document.addEventListener("click", (event) => {
    const sortButton = event.target.closest("[data-repository-sort]");
    if (sortButton) toggleRepositorySort(sortButton.dataset.repositorySort);
    const blobSortButton = event.target.closest("[data-blobstore-sort]");
    if (blobSortButton) toggleBlobStoreSort(blobSortButton.dataset.blobstoreSort);
  });
  document.getElementById("user-menu").addEventListener("mouseenter", openUserMenu);
  document.getElementById("user-menu").addEventListener("mouseleave", scheduleCloseUserMenu);
  document.getElementById("user-menu-trigger").addEventListener("click", (event) => {
    event.stopPropagation();
    toggleUserMenu();
  });
  document.getElementById("my-token-menu-item").addEventListener("click", () => {
    closeUserMenu();
    window.location.href = "/browse/#browse/my-token";
  });
  document.getElementById("signout-button").addEventListener("click", () => {
    closeUserMenu();
    sessionStorage.removeItem(AUTH_SNAPSHOT_KEY);
    window.location.href = `/internal/security/logout?returnTo=${encodeURIComponent("/browse/#browse/welcome")}`;
  });
  document.addEventListener("click", (event) => {
    const dismissButton = event.target.closest("[data-modal-dismiss]");
    if (!dismissButton) return;
    event.preventDefault();
    dismissFormModal(dismissButton.dataset.modalDismiss);
  });
  document.addEventListener("click", (event) => {
    if (!document.getElementById("user-menu").contains(event.target)) closeUserMenu();
    const actionMenu = document.getElementById("cleanup-policy-action-menu");
    if (!actionMenu.hidden
        && !actionMenu.contains(event.target)
        && !event.target.closest(".cleanup-policy-more-actions:not(.blobstore-more-actions)")) {
      closeCleanupPolicyActionMenu();
    }
    const blobMenu = document.getElementById("blobstore-action-menu");
    if (!blobMenu.hidden
        && !blobMenu.contains(event.target)
        && !event.target.closest(".blobstore-more-actions")) {
      closeBlobStoreActionMenu();
    }
  });
  document.addEventListener("keydown", (event) => {
    if (handleFormModalKeydown(event)) return;
    if (handleCleanupPolicyActionMenuKeydown(event)) return;
    if (handleBlobStoreActionMenuKeydown(event)) return;
    if (event.key === "Escape") closeUserMenu();
  });
}
