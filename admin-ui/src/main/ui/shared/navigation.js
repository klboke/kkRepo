export function bindWorkspaceSwitch() {
  const nav = document.querySelector(".workspace-tabs");
  const marker = nav.querySelector(".workspace-marker");
  const reducedMotion = window.matchMedia("(prefers-reduced-motion: reduce)");
  const key = "kkrepo.workspace-switch";

  function markerBox() {
    const bar = nav.getBoundingClientRect();
    const line = marker.getBoundingClientRect();
    return { x: line.left - bar.left, y: line.top - bar.top, width: line.width };
  }

  // Native links load a new document. Carry only the underline's last position across that navigation.
  let previous;
  try {
    const saved = sessionStorage.getItem(key);
    sessionStorage.removeItem(key);
    if (saved) previous = JSON.parse(saved);
  } catch { /* Storage is optional. */ }
  if (!reducedMotion.matches && previous?.to === location.href && Date.now() - previous.time < 10000
      && [previous.x, previous.y, previous.width].every(Number.isFinite)) {
    const end = markerBox();
    if (end.width && marker.animate) {
      const from = `translate(${previous.x - end.x}px, ${previous.y - end.y}px) scaleX(${previous.width / end.width})`;
      marker.animate([{ transform: from }, { transform: "none" }], {
        duration: 200, easing: "cubic-bezier(.2,.75,.25,1)",
      });
    }
  }

  nav.addEventListener("click", event => {
    const link = event.target.closest("a:not([aria-current])");
    if (!link || event.defaultPrevented || event.button !== 0 || reducedMotion.matches
        || event.ctrlKey || event.metaKey || event.shiftKey || event.altKey) return;
    try {
      sessionStorage.setItem(key, JSON.stringify({ ...markerBox(), to: link.href, time: Date.now() }));
    } catch { /* The link still navigates when storage is unavailable. */ }
  });
}

export function bindNavigationDisclosure() {
  const toggle = document.getElementById("navigation-toggle");
  const sidebar = document.getElementById("workspace-navigation");
  function setOpen(open) {
    toggle.setAttribute("aria-expanded", String(open));
    sidebar.classList.toggle("navigation-open", open);
  }
  toggle.addEventListener("click", () => {
    const open = toggle.getAttribute("aria-expanded") === "false";
    setOpen(open);
    if (open) (sidebar.querySelector(".side-item.is-active") || sidebar.querySelector("button, a"))?.focus();
  });
  sidebar.addEventListener("click", event => {
    const destination = event.target.closest("[data-view]:not([aria-expanded]), .side-subitem, a[href]");
    if (destination && sidebar.classList.contains("navigation-open")) {
      setOpen(false);
      toggle.focus();
    }
  });
  document.addEventListener("keydown", event => {
    if (event.key === "Escape" && sidebar.classList.contains("navigation-open")) {
      setOpen(false);
      toggle.focus();
      event.preventDefault();
    }
  });
  document.addEventListener("pointerdown", event => {
    if (!toggle.contains(event.target) && !sidebar.contains(event.target)) setOpen(false);
  });
  document.addEventListener("focusin", event => {
    if (event.target !== toggle && !sidebar.contains(event.target)) setOpen(false);
  });
  window.matchMedia("(max-width: 760px)").addEventListener("change", () => setOpen(false));
  window.addEventListener("hashchange", () => setOpen(false));
}
