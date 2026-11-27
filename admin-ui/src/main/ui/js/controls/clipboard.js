import { copyLocationText } from "#ui-shared/clipboard.js";
import { escapeHtml } from "./display.js";
import { showToast } from "./toast.js";

function renderCopyableLocation(value, copyLabel) {
  if (!value) return '<span class="health-muted">—</span>';
  const text = escapeHtml(value);
  return `<div class="usage-location-value">
    <details class="usage-location-details">
      <summary title="Expand or collapse full value"><code title="${text}">${text}</code></summary>
      <code class="usage-location-full">${text}</code>
    </details>
    <button class="usage-location-copy" type="button" data-copy-value="${text}" title="${escapeHtml(copyLabel)}" aria-label="${escapeHtml(copyLabel)}"><span class="lucide-icon icon-copy" aria-hidden="true"></span></button>
  </div>`;
}

async function copyLocationValue(button) {
  const value = button.dataset.copyValue;
  if (!value) return;
  try {
    await copyLocationText(value);
    showToast("Copied", "ok");
  } catch {
    showToast("Copy failed. Expand the value to select and copy it manually.", "error");
  }
}

export {
  copyLocationText,
  copyLocationValue,
  renderCopyableLocation,
};
