import { login, logout } from "../shell/session.js";
import { submitAdminBootstrap } from "./setup.js";
import { copyGeneratedToken, createCurrentApiKey, markMyTokenDisplayNameValidity, setMyTokenStatus, showMyToken } from "./tokens.js";
import { closeUserMenu, openUserMenu, scheduleCloseUserMenu, toggleUserMenu } from "../shell/account.js";
import { bootstrap } from "../shell/start.js";

export function bindAccountEvents() {
  document.getElementById("my-token-form").addEventListener("submit", createCurrentApiKey);

  document.getElementById("my-token-display-name").addEventListener("input", (event) => {
    if (event.target.value.trim()) {
      markMyTokenDisplayNameValidity(false);
      setMyTokenStatus("");
    }
  });

  document.getElementById("my-token-copy").addEventListener("click", copyGeneratedToken);

  document.getElementById("admin-bootstrap-panel").addEventListener("submit", submitAdminBootstrap);

  document.getElementById("login-button").addEventListener("click", login);

  document.getElementById("user-menu").addEventListener("mouseenter", openUserMenu);

  document.getElementById("user-menu").addEventListener("mouseleave", scheduleCloseUserMenu);

  document.getElementById("user-menu-trigger").addEventListener("click", (event) => {
    event.stopPropagation();
    toggleUserMenu();
  });

  document.getElementById("my-token-menu-item").addEventListener("click", () => {
    closeUserMenu();
    showMyToken();
  });

  document.getElementById("signout-button").addEventListener("click", logout);

  document.addEventListener("click", (event) => {
    if (!document.getElementById("user-menu").contains(event.target)) closeUserMenu();
  });

  document.addEventListener("keydown", (event) => {
    if (event.key === "Escape") closeUserMenu();
  });
}
