import { copyLocationValue } from "../controls/clipboard.js";
import {
  bindBlobStoreFormModeControls,
  blobActionMenuStoreId,
  blobActionMenuTrigger,
  blobStoreFormFields,
  blobStores,
  checkBlobStore,
  clearBlobStoreFieldError,
  closeBlobStoreActionMenu,
  deleteBlobStore,
  hideBlobStoreForm,
  loadBlobStores,
  openBlobStoreActionMenu,
  positionBlobStoreActionMenu,
  renderBlobStores,
  saveBlobStore,
  showBlobStoreRepositories,
  showCreateBlobStoreForm,
  showEditBlobStoreForm,
} from "./blob-stores.js";

function bindBlobStoreRowActions() {
  document.getElementById("blobstore-table").addEventListener("click", (event) => {
    const editButton = event.target.closest(".edit-blobstore-button");
    if (editButton) {
      showEditBlobStoreForm(editButton.dataset.id);
      return;
    }
    const moreButton = event.target.closest(".blobstore-more-actions");
    if (moreButton) {
      openBlobStoreActionMenu(moreButton.dataset.id, moreButton, event.detail === 0 ? "first" : null);
    }
  });
}

function bindBlobStoreTableKeys() {
  document.getElementById("blobstore-table").addEventListener("keydown", (event) => {
    const moreButton = event.target.closest(".blobstore-more-actions");
    if (!moreButton || !["ArrowDown", "ArrowUp"].includes(event.key)) return;
    event.preventDefault();
    openBlobStoreActionMenu(moreButton.dataset.id, moreButton,
      event.key === "ArrowDown" ? "first" : "last");
  });
}

function bindBlobStoreMenuActions() {
  document.getElementById("blobstore-action-menu").addEventListener("click", (event) => {
    const item = event.target.closest("[data-blobstore-action]");
    if (!item || blobActionMenuStoreId == null) return;
    const id = blobActionMenuStoreId;
    const store = blobStores.find((entry) => String(entry.id) === String(id));
    const action = item.dataset.blobstoreAction;
    closeBlobStoreActionMenu({ restoreFocus: true });
    if (!store) return;
    if (action === "repositories") showBlobStoreRepositories(store.name);
    if (action === "check") checkBlobStore(id, store.name);
    if (action === "delete") deleteBlobStore(id);
  });
}

export function bindStorageEvents() {
  document.getElementById("blobstore-usage-refresh").addEventListener("click", () => loadBlobStores());
  document.getElementById("blobstore-table").addEventListener("click", (event) => {
    const copyButton = event.target.closest(".usage-location-copy");
    if (copyButton) {
      copyLocationValue(copyButton);
      return;
    }
  });
  document.getElementById("blobstore-filter").addEventListener("input", renderBlobStores);
  document.getElementById("create-blobstore-button").addEventListener("click", showCreateBlobStoreForm);
  document.getElementById("cancel-blobstore-button").addEventListener("click", hideBlobStoreForm);
  document.getElementById("blobstore-form").addEventListener("submit", saveBlobStore);
  document.getElementById("save-blobstore-button").addEventListener("click", saveBlobStore);
  bindBlobStoreFormModeControls();
  blobStoreFormFields.forEach((field) => {
    document.getElementById(field.id).addEventListener("input", clearBlobStoreFieldError);
  });
  bindBlobStoreRowActions();
  bindBlobStoreTableKeys();
  bindBlobStoreMenuActions();
  document.getElementById("blobstore-action-menu").addEventListener("focusout", () => {
    setTimeout(() => {
      const menu = document.getElementById("blobstore-action-menu");
      if (!menu.contains(document.activeElement)
          && document.activeElement !== blobActionMenuTrigger) {
        closeBlobStoreActionMenu();
      }
    }, 0);
  });
  document.addEventListener("scroll", () => positionBlobStoreActionMenu(), true);
}
