(function () {
  if (window.kkrepoConfirmDelete) return;
  let active = false;

  // Resolve only after an explicit confirmation. Navigation, Escape and duplicate
  // requests cancel; callers retain their existing authorization and DELETE logic.
  window.kkrepoConfirmDelete = function ({ title, description, details = [], confirmationText, confirmLabel = "Delete" } = {}) {
    if (active) return Promise.resolve(false);
    active = true;
    const trigger = document.activeElement;
    const dialog = document.createElement("dialog");
    dialog.className = "kk-confirm-dialog";
    dialog.setAttribute("aria-labelledby", "delete-confirmation-title");
    dialog.setAttribute("aria-describedby", "delete-confirmation-description delete-confirmation-warning");
    const add = (tag, parent, text, className) => {
      const element = document.createElement(tag);
      if (text != null) element.textContent = text;
      if (className) element.className = className;
      parent.appendChild(element);
      return element;
    };
    const form = add("form", dialog);
    const heading = add("div", form, null, "kk-confirm-heading");
    const icon = add("span", heading, null, "kk-confirm-icon lucide-icon icon-triangle-alert");
    icon.setAttribute("aria-hidden", "true");
    add("h2", heading, title || "Delete this item?").id = "delete-confirmation-title";
    const list = add("dl", form, null, "kk-confirm-details");
    for (const detail of details) {
      add("dt", list, detail.label);
      const value = add("code", add("dd", list), detail.value);
      value.setAttribute("data-i18n-skip", "");
    }
    add("p", form, description || "The selected item will be deleted.").id = "delete-confirmation-description";
    add("p", form, "Deletion is permanent. This action cannot be undone.", "kk-confirm-warning")
      .id = "delete-confirmation-warning";
    let input;
    if (confirmationText != null) {
      const label = add("label", form, "Type the exact name to confirm:");
      label.htmlFor = "delete-confirmation-input";
      input = add("input", form);
      input.id = "delete-confirmation-input";
      input.type = "text";
      input.autocomplete = "off";
      input.spellcheck = false;
      input.required = true;
    }
    const actions = add("div", form, null, "kk-confirm-actions");
    const cancel = add("button", actions, "Cancel", "kk-confirm-cancel");
    cancel.type = "button";
    cancel.autofocus = true;
    const confirm = add("button", actions, confirmLabel, "kk-confirm-delete");
    confirm.type = "submit";
    confirm.disabled = input != null;
    input?.addEventListener("input", () => { confirm.disabled = input.value !== confirmationText; });

    return new Promise((resolve) => {
      let settled = false;
      const finish = (accepted) => {
        if (settled) return;
        settled = true;
        window.removeEventListener("hashchange", navigate);
        window.removeEventListener("pagehide", navigate);
        dialog.close();
        dialog.remove();
        active = false;
        if (trigger?.isConnected) trigger.focus();
        resolve(accepted);
      };
      const navigate = () => finish(false);
      cancel.addEventListener("click", () => finish(false));
      dialog.addEventListener("cancel", (event) => { event.preventDefault(); finish(false); });
      dialog.addEventListener("close", () => finish(false));
      form.addEventListener("submit", (event) => {
        event.preventDefault();
        if (!confirm.disabled && (!input || input.value === confirmationText)) finish(true);
      });
      // Enter on the initially focused Cancel button must never submit the form.
      cancel.addEventListener("keydown", (event) => {
        if (event.key === "Enter") { event.preventDefault(); finish(false); }
      });
      window.addEventListener("hashchange", navigate);
      window.addEventListener("pagehide", navigate);
      document.body.appendChild(dialog);
      window.kkrepoI18n?.apply(dialog);
      dialog.showModal();
      cancel.focus();
    });
  };
})();
