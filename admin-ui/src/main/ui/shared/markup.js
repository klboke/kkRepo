function escapeHtml(value) {
  return String(value ?? "")
    .replaceAll("&", "&amp;")
    .replaceAll("<", "&lt;")
    .replaceAll(">", "&gt;")
    .replaceAll('"', "&quot;")
    .replaceAll("'", "&#39;");
}

function lucideIcon(name, className = "") {
  return `<span class="lucide-icon icon-${name}${className ? ` ${className}` : ""}" aria-hidden="true"></span>`;
}

export { escapeHtml, lucideIcon };
