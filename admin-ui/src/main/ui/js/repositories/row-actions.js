import { placeAnchoredPanel } from "#ui-shared/anchored-panel.js";
import { copyLocationValue } from "../controls/clipboard.js";

export function bindRepositoryRowActions(removeRepository) {
  const menu = document.getElementById("repository-actions");
  const table = document.getElementById("repository-table");
  const copy = menu.querySelector('[data-action="copy"]');
  let trigger;
  let release;
  function close(restore = false) {
    if (!trigger) return;
    release?.();
    trigger.setAttribute("aria-expanded", "false");
    if (restore && trigger.isConnected) trigger.focus();
    trigger = null;
  }
  table.addEventListener("click", event => {
    const button = event.target.closest(".repository-more");
    if (!button) return;
    const wasOpen = trigger === button;
    close();
    if (wasOpen) return;
    trigger = button;
    copy.disabled = !button.dataset.url;
    copy.dataset.copyValue = button.dataset.url;
    trigger.setAttribute("aria-expanded", "true");
    release = placeAnchoredPanel(trigger, menu, { alignEnd: true, onDetach: () => close() });
    menu.querySelector("button:enabled").focus();
  });
  menu.addEventListener("click", event => {
    const action = event.target.closest("button")?.dataset.action;
    if (!action || !trigger) return;
    const name = trigger.dataset.name;
    close(true);
    if (action === "copy") copyLocationValue(copy);
    if (action === "delete") removeRepository(name);
  });
  menu.addEventListener("keydown", event => {
    if (event.key === "Escape") {
      event.stopPropagation();
      close(true);
    }
  });
  document.addEventListener("focusin", event => {
    if (trigger && event.target !== trigger && !menu.contains(event.target)) close();
  });
  document.addEventListener("click", event => {
    if (trigger && !trigger.contains(event.target) && !menu.contains(event.target)) close();
  });
  new MutationObserver(() => { if (trigger && !trigger.isConnected) close(); })
    .observe(table, { childList: true });
  window.addEventListener("hashchange", () => close());
}
