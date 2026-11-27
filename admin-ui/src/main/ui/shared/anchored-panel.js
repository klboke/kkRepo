// Keep a popup in its owning dialog's DOM for focus handling, outside its scroll clipping.
export function placeAnchoredPanel(anchor, panel, { alignEnd = false, maxHeight = Infinity, onDetach } = {}) {
  const gap = 6;
  const edge = 10;
  panel.hidden = false;
  if (typeof panel.showPopover === "function") panel.showPopover();
  function position() {
    if (!anchor.getClientRects().length) {
      onDetach();
      return;
    }
    const rect = anchor.getBoundingClientRect();
    const width = Math.min(Math.max(rect.width, panel.offsetWidth), window.innerWidth - 2 * edge);
    const below = window.innerHeight - rect.bottom - gap - edge;
    const above = rect.top - gap - edge;
    const upwards = below < Math.min(maxHeight, panel.scrollHeight) && above > below;
    panel.style.maxHeight = Math.max(0, Math.min(maxHeight, upwards ? above : below)) + "px";
    const left = alignEnd ? rect.right - width : rect.left;
    panel.style.width = width + "px";
    panel.style.left = Math.max(edge, Math.min(left, window.innerWidth - width - edge)) + "px";
    panel.style.top = (upwards ? Math.max(edge, rect.top - gap - panel.offsetHeight) : rect.bottom + gap) + "px";
  }
  function onScroll(event) {
    if (!panel.contains(event.target)) position();
  }
  position();
  window.addEventListener("resize", position);
  document.addEventListener("scroll", onScroll, true);
  return () => {
    window.removeEventListener("resize", position);
    document.removeEventListener("scroll", onScroll, true);
    if (typeof panel.hidePopover === "function") panel.hidePopover();
    panel.hidden = true;
    for (const property of ["max-height", "left", "top", "width"]) panel.style.removeProperty(property);
  };
}
