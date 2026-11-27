import { copyLocationText as copyTextToClipboard } from "#ui-shared/clipboard.js";
import { lucideIcon } from "./icons.js";

const pendingCopies = new WeakMap();

async function copyWithFeedback(button, value, label, success, failure, status) {
  const attempt = {};
  pendingCopies.set(button, attempt);
  let copied = true;
  try {
    await copyTextToClipboard(value);
  } catch {
    copied = false;
  }
  if (pendingCopies.get(button) !== attempt || !button.isConnected) return;
  const message = copied ? success : failure;
  button.classList.toggle("copied", copied);
  button.classList.toggle("copy-failed", !copied);
  button.innerHTML = lucideIcon(copied ? "check" : "x");
  button.title = message;
  button.setAttribute("aria-label", message);
  status.textContent = message;
  setTimeout(() => {
    if (pendingCopies.get(button) !== attempt) return;
    button.classList.remove("copied", "copy-failed");
    button.innerHTML = lucideIcon("copy");
    button.title = label;
    button.setAttribute("aria-label", label);
    pendingCopies.delete(button);
  }, copied ? 1500 : 3500);
}

function copyRepositoryUrl(button) {
  return copyWithFeedback(button, button.dataset.repositoryUrl, "Copy repository URL",
    "Repository URL copied", "Unable to copy repository URL",
    document.getElementById("repository-copy-status"));
}

export { copyRepositoryUrl, copyTextToClipboard, copyWithFeedback };
