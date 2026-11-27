const modalOpeners = new WeakMap();
const formModalDismissHandlers = new Map();

function formModalElement(formId) {
  return document.getElementById(`${formId}-modal`);
}

function openFormModals() {
  return Array.from(document.querySelectorAll(".form-modal:not([hidden])"));
}

function activeFormModal() {
  const modals = openFormModals();
  return modals.length > 0 ? modals[modals.length - 1] : null;
}

function updateFormModalBodyState() {
  document.body.classList.toggle("has-form-modal", openFormModals().length > 0);
}

function isFocusableModalElement(element) {
  return Boolean(element)
    && !element.disabled
    && !element.hidden
    && !element.closest("[hidden]")
    && element.type !== "hidden"
    && element.getAttribute("aria-hidden") !== "true";
}

function modalFocusCandidates(modal) {
  if (!modal) return [];
  return Array.from(modal.querySelectorAll("button, input, select, textarea, [tabindex]:not([tabindex='-1'])"))
    .filter(isFocusableModalElement);
}

function focusFormModal(formId, preferredFocusId = null) {
  const modal = formModalElement(formId);
  const preferred = preferredFocusId ? document.getElementById(preferredFocusId) : null;
  const target = isFocusableModalElement(preferred)
    ? preferred
    : modalFocusCandidates(modal)[0];
  if (target) setTimeout(() => { if (activeFormModal() === modal) target.focus(); }, 0);
}

function openFormModal(formId, preferredFocusId = null) {
  const form = document.getElementById(formId);
  if (!form) return;
  const modal = formModalElement(formId);
  form.hidden = false;
  if (modal) {
    if (modal.hidden) modalOpeners.set(modal, document.activeElement);
    modal.hidden = false;
    modal.setAttribute("aria-hidden", "false");
  }
  updateFormModalBodyState();
  focusFormModal(formId, preferredFocusId);
}

function closeFormModal(formId) {
  const form = document.getElementById(formId);
  if (form) form.hidden = true;
  const modal = formModalElement(formId);
  if (modal && !modal.hidden) {
    modal.hidden = true;
    modal.setAttribute("aria-hidden", "true");
    const opener = modalOpeners.get(modal);
    modalOpeners.delete(modal);
    if (opener?.isConnected && !opener.closest("[hidden]")) opener.focus({ preventScroll: true });
  }
  updateFormModalBodyState();
}

function bindFormModalDismiss(formId, handler) {
  formModalDismissHandlers.set(formId, handler);
}

function dismissFormModal(formId) {
  const handler = formModalDismissHandlers.get(formId);
  if (handler) {
    handler();
  } else {
    closeFormModal(formId);
  }
}

function trapFocusInFormModal(event) {
  const modal = activeFormModal();
  if (!modal) return false;
  const candidates = modalFocusCandidates(modal);
  if (candidates.length === 0) return false;
  const first = candidates[0];
  const last = candidates[candidates.length - 1];
  if (event.shiftKey && document.activeElement === first) {
    event.preventDefault();
    last.focus();
    return true;
  }
  if (!event.shiftKey && document.activeElement === last) {
    event.preventDefault();
    first.focus();
    return true;
  }
  return false;
}

function handleFormModalKeydown(event) {
  // A native confirmation dialog owns keyboard input above the form modal.
  if (event.target?.closest?.("dialog[open]")) return true;
  if (event.key === "Tab") {
    return trapFocusInFormModal(event);
  }
  if (event.key !== "Escape") return false;
  const modal = activeFormModal();
  if (!modal) return false;
  event.preventDefault();
  dismissFormModal(modal.dataset.formId);
  return true;
}

export {
  bindFormModalDismiss,
  closeFormModal,
  dismissFormModal,
  handleFormModalKeydown,
  openFormModal,
};
