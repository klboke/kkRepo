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
    if (event.target.closest("[data-view], .side-subitem, a[href]") && sidebar.classList.contains("navigation-open")) {
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
