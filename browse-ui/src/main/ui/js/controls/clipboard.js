import { copyLocationText as copyTextToClipboard } from "#ui-shared/clipboard.js";
import { lucideIcon } from "./icons.js";

async function copyRepositoryUrl(button) {
  const url = button.dataset.repositoryUrl;
  if (!url) return;
  if (button.copyResetTimer) clearTimeout(button.copyResetTimer);
  try {
    await copyTextToClipboard(url);
    button.classList.add("copied");
    button.classList.remove("copy-failed");
    button.innerHTML = lucideIcon("check");
    button.title = "Copied";
    button.setAttribute("aria-label", "Repository URL copied");
    announceRepositoryCopyStatus("Repository URL copied");
    button.copyResetTimer = setTimeout(() => resetRepositoryCopyButton(button), 1200);
  } catch {
    button.classList.add("copy-failed");
    button.classList.remove("copied");
    button.innerHTML = lucideIcon("x");
    button.title = "Copy failed";
    button.setAttribute("aria-label", "Unable to copy repository URL");
    announceRepositoryCopyStatus("Unable to copy repository URL");
    button.copyResetTimer = setTimeout(() => resetRepositoryCopyButton(button), 1400);
  }
}

function resetRepositoryCopyButton(button) {
  button.copyResetTimer = null;
  button.classList.remove("copied", "copy-failed");
  button.innerHTML = lucideIcon("copy");
  button.title = "Copy repository URL";
  button.setAttribute("aria-label", "Copy repository URL");
}

function announceRepositoryCopyStatus(message) {
  const status = document.getElementById("repository-copy-status");
  if (!status) return;
  status.textContent = "";
  requestAnimationFrame(() => { status.textContent = message; });
}

export {
  copyRepositoryUrl,
  copyTextToClipboard,
};
