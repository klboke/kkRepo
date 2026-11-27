(() => {
  const key = "kkrepo.workspace-switch";
  const root = document.documentElement;
  const reducedMotion = window.matchMedia("(prefers-reduced-motion: reduce)");
  let origin;
  try {
    const saved = sessionStorage.getItem(key);
    sessionStorage.removeItem(key);
    if (saved) origin = JSON.parse(saved);
  } catch { /* Navigation also works without storage. */ }

  // This script runs in the head. The new document's first paint must already show the departing position.
  if (!reducedMotion.matches && origin?.to === location.href && Date.now() - origin.time < 10000
      && [origin.x, origin.y, origin.width].every(Number.isFinite) && origin.width > 0) {
    root.style.setProperty("--workspace-origin-inset", `${origin.y}px auto auto ${origin.x}px`);
    root.style.setProperty("--workspace-origin-width", `${origin.width}px`);
    root.setAttribute("data-workspace-origin", "");
  }

  document.addEventListener("click", event => {
    const link = event.target.closest?.(".workspace-tabs a:not([aria-current])");
    if (!link || event.defaultPrevented || event.button !== 0 || reducedMotion.matches
        || event.ctrlKey || event.metaKey || event.shiftKey || event.altKey) return;
    const nav = link.parentElement;
    const bar = nav.getBoundingClientRect();
    const line = nav.querySelector(".workspace-marker").getBoundingClientRect();
    try {
      sessionStorage.setItem(key, JSON.stringify({
        to: link.href, time: Date.now(), x: line.left - bar.left, y: line.top - bar.top, width: line.width,
      }));
    } catch { /* Keep the native link when storage is unavailable. */ }
  });

  if (!root.hasAttribute("data-workspace-origin")) return;
  let animation;
  function releaseOrigin() {
    root.removeAttribute("data-workspace-origin");
    root.style.removeProperty("--workspace-origin-inset");
    root.style.removeProperty("--workspace-origin-width");
  }
  window.addEventListener("pagehide", () => {
    releaseOrigin();
    animation?.cancel();
  }, { once: true });
  document.addEventListener("DOMContentLoaded", async () => {
    await window.kkrepoI18n?.ready();
    if (!root.hasAttribute("data-workspace-origin")) return;
    const marker = document.querySelector(".workspace-marker");
    const start = marker.getBoundingClientRect();
    releaseOrigin();
    const end = marker.getBoundingClientRect();
    if (reducedMotion.matches || !end.width || !marker.animate) return;
    const from = `translate(${start.left - end.left}px, ${start.top - end.top}px) scaleX(${start.width / end.width})`;
    animation = marker.animate([{ transform: from }, { transform: "none" }], {
      duration: 200, easing: "cubic-bezier(.2,.75,.25,1)",
    });
  }, { once: true });
})();
