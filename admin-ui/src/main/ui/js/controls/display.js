import { escapeHtml, lucideIcon } from "#ui-shared/markup.js";

function lowerOrEmpty(value) {
  return String(value ?? "").toLowerCase();
}

function formatInstant(value) {
  if (!value) return "";
  try {
    return new Date(value).toLocaleString();
  } catch {
    return String(value);
  }
}

function card(label, value) {
  return `<div><span>${escapeHtml(label)}</span><strong>${escapeHtml(value)}</strong></div>`;
}

export {
  card,
  escapeHtml,
  formatInstant,
  lowerOrEmpty,
  lucideIcon,
};

export function formatDateTime(value) {
  if (!value) return "-";
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return String(value);
  return date.toLocaleString();
}

export function shortText(value, max = 64) {
  const text = String(value || "");
  return text.length <= max ? text : `${text.slice(0, max - 1)}…`;
}
