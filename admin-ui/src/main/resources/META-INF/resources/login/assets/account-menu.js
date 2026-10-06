(function () {
  let userMenuCloseTimer = null;

  function closeUserMenu() {
    if (userMenuCloseTimer) {
      clearTimeout(userMenuCloseTimer);
      userMenuCloseTimer = null;
    }
    const trigger = document.getElementById("user-menu-trigger");
    const popover = document.getElementById("user-menu-popover");
    if (!trigger || !popover) return;
    trigger.setAttribute("aria-expanded", "false");
    popover.classList.remove("is-open");
    popover.setAttribute("aria-hidden", "true");
  }

  function openUserMenu() {
    if (userMenuCloseTimer) {
      clearTimeout(userMenuCloseTimer);
      userMenuCloseTimer = null;
    }
    const menu = document.getElementById("user-menu");
    const trigger = document.getElementById("user-menu-trigger");
    const popover = document.getElementById("user-menu-popover");
    if (!menu || !trigger || !popover || menu.hidden) return;
    trigger.setAttribute("aria-expanded", "true");
    popover.classList.add("is-open");
    popover.setAttribute("aria-hidden", "false");
  }

  function scheduleCloseUserMenu() {
    if (userMenuCloseTimer) clearTimeout(userMenuCloseTimer);
    userMenuCloseTimer = setTimeout(() => {
      userMenuCloseTimer = null;
      closeUserMenu();
    }, 120);
  }

  function toggleUserMenu() {
    const popover = document.getElementById("user-menu-popover");
    if (!popover) return;
    if (!popover.classList.contains("is-open")) openUserMenu();
    else closeUserMenu();
  }

  window.nexusPlusAccountMenu = {
    close: closeUserMenu,
    open: openUserMenu,
    scheduleClose: scheduleCloseUserMenu,
    toggle: toggleUserMenu,
  };
})();
