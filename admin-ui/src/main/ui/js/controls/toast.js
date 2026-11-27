import { escapeHtml } from "./display.js";

let toastTimer;

function showToast(message, tone = "info") {
  const region = document.getElementById("toast-region");
  region.innerHTML = `<div class="toast ${tone}">${escapeHtml(message)}</div>`;
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => {
    region.innerHTML = "";
  }, tone === "error" ? 6000 : 3200);
}

export {
  showToast,
};
