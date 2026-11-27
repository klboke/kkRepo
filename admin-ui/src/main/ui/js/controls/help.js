let activeFieldHelpTrigger = null;

let fieldHelpHideTimer = null;

function clearFieldHelpHideTimer() {
  clearTimeout(fieldHelpHideTimer);
  fieldHelpHideTimer = null;
}

function positionFieldHelpPopover(trigger) {
  const popover = document.getElementById("field-help-popover");
  const triggerRect = trigger.getBoundingClientRect();
  if (triggerRect.bottom < 0 || triggerRect.top > window.innerHeight) {
    hideFieldHelpPopover();
    return;
  }
  const popoverRect = popover.getBoundingClientRect();
  const viewportPadding = 10;
  const gap = 7;
  let left = triggerRect.left + (triggerRect.width / 2) - (popoverRect.width / 2);
  left = Math.max(viewportPadding, Math.min(left, window.innerWidth - popoverRect.width - viewportPadding));
  let top = triggerRect.bottom + gap;
  if (top + popoverRect.height > window.innerHeight - viewportPadding
      && triggerRect.top - popoverRect.height - gap >= viewportPadding) {
    top = triggerRect.top - popoverRect.height - gap;
  }
  popover.style.left = `${Math.round(left)}px`;
  popover.style.top = `${Math.round(top)}px`;
}

function showFieldHelpPopover(trigger) {
  const message = trigger?.dataset.tooltip?.trim();
  if (!message) return;
  clearFieldHelpHideTimer();
  if (activeFieldHelpTrigger && activeFieldHelpTrigger !== trigger) {
    activeFieldHelpTrigger.removeAttribute("aria-describedby");
  }
  activeFieldHelpTrigger = trigger;
  const popover = document.getElementById("field-help-popover");
  popover.textContent = message;
  popover.hidden = false;
  trigger.setAttribute("aria-describedby", popover.id);
  positionFieldHelpPopover(trigger);
}

function hideFieldHelpPopover() {
  clearFieldHelpHideTimer();
  if (activeFieldHelpTrigger) activeFieldHelpTrigger.removeAttribute("aria-describedby");
  activeFieldHelpTrigger = null;
  document.getElementById("field-help-popover").hidden = true;
}

function scheduleFieldHelpPopoverHide() {
  clearFieldHelpHideTimer();
  fieldHelpHideTimer = setTimeout(() => {
    const popover = document.getElementById("field-help-popover");
    const triggerActive = activeFieldHelpTrigger
      && (activeFieldHelpTrigger.matches(":hover") || document.activeElement === activeFieldHelpTrigger);
    if (!triggerActive && !popover.matches(":hover")) hideFieldHelpPopover();
  }, 220);
}

function bindFieldHelpTrigger(trigger) {
  trigger.addEventListener("mouseenter", () => showFieldHelpPopover(trigger));
  trigger.addEventListener("mouseleave", scheduleFieldHelpPopoverHide);
  trigger.addEventListener("focus", () => showFieldHelpPopover(trigger));
  trigger.addEventListener("blur", scheduleFieldHelpPopoverHide);
  trigger.addEventListener("keydown", (event) => {
    if (event.key === "Escape" && !document.getElementById("field-help-popover").hidden) {
      event.preventDefault();
      event.stopPropagation();
      hideFieldHelpPopover();
    }
  });
}

function bindFieldHelpTooltips() {
  const popover = document.getElementById("field-help-popover");
  document.querySelectorAll(".field-help").forEach(bindFieldHelpTrigger);
  popover.addEventListener("mouseenter", clearFieldHelpHideTimer);
  popover.addEventListener("mouseleave", scheduleFieldHelpPopoverHide);
  document.addEventListener("scroll", () => {
    if (activeFieldHelpTrigger && !popover.hidden) positionFieldHelpPopover(activeFieldHelpTrigger);
  }, true);
  window.addEventListener("resize", () => {
    if (activeFieldHelpTrigger && !popover.hidden) positionFieldHelpPopover(activeFieldHelpTrigger);
  });
}

export {
  activeFieldHelpTrigger,
  bindFieldHelpTooltips,
  bindFieldHelpTrigger,
  hideFieldHelpPopover,
  showFieldHelpPopover,
};
