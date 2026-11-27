import { escapeHtml, lowerOrEmpty } from "../controls/display.js";
import { responseErrorMessage } from "../shell/http.js";

// undefined = loading; null = unavailable. A successful response contains explicit
// zeros for empty entities; missing or invalid values must never masquerade as zero.
function inventoryValue(snapshot, id, key) {
  const value = snapshot?.usage?.[String(id)]?.[key];
  if (typeof value === "bigint") return value >= 0n ? value : null;
  if (typeof value === "string" && /^(0|[1-9][0-9]*)$/.test(value)) return BigInt(value);
  // Accept older servers during rollout, but never trust an already-rounded JSON number.
  return Number.isSafeInteger(value) && value >= 0 ? BigInt(value) : null;
}

function formatInventoryBytes(value) {
  const units = ["B", "KiB", "MiB", "GiB", "TiB", "PiB", "EiB"];
  const bytes = BigInt(value);
  const locale = document.documentElement.lang || undefined;
  let unit = 0, divisor = 1n;
  while (bytes >= divisor * 1024n && unit < units.length - 1) {
    divisor *= 1024n;
    unit++;
  }
  if (unit === 0) return `${bytes.toLocaleString(locale)} B`;
  // Round to one decimal using integer arithmetic, including totals above Long.MAX_VALUE.
  const tenths = (bytes * 10n + divisor / 2n) / divisor;
  const whole = tenths / 10n, fraction = tenths % 10n;
  const formatted = fraction === 0n ? whole.toLocaleString(locale)
    : new Intl.NumberFormat(locale, { minimumFractionDigits: 1, maximumFractionDigits: 1 })
      .formatToParts(whole).map((part) => part.type === "fraction"
        ? fraction.toLocaleString(locale) : part.value).join("");
  return `${formatted} ${units[unit]}`;
}

function renderInventoryMetric(snapshot, id, key, bytes = false) {
  if (snapshot === undefined) return '<span class="health-muted" title="Loading usage…">…</span>';
  const value = inventoryValue(snapshot, id, key);
  if (value == null) return '<span class="health-muted" title="Usage unavailable. Refresh to retry.">—</span>';
  const formatted = bytes ? formatInventoryBytes(value) : value.toLocaleString(document.documentElement.lang || undefined);
  const unknown = bytes && key === "totalBytes" && inventoryValue(snapshot, id, "unknownSizeCount") > 0n;
  return unknown ? `<span title="Some asset sizes are unknown; this is a lower bound.">≥ ${escapeHtml(formatted)}</span>` : escapeHtml(formatted);
}

function renderGroupUsage() {
  return '<span class="health-muted" title="Not applicable to group repositories. Inspect member repositories for usage.">—</span>';
}

function renderRepositoryMetric(snapshot, repo, key, bytes = false) {
  return lowerOrEmpty(repo.type) === "group" ? renderGroupUsage()
    : renderInventoryMetric(snapshot, repo.id, key, bytes);
}

function renderUsageSummary(kind, rows, snapshot) {
  const blob = kind === "blobstore";
  // Groups are routing views, so their persisted cache entries are not presented
  // as independent repository usage. Physical blobs remain in blob-store totals.
  const usageRows = blob ? rows : rows.filter((row) => lowerOrEmpty(row.type) !== "group");
  const groupOnly = !blob && rows.length > 0 && usageRows.length === 0;
  const keys = blob ? ["blobCount", "totalBytes", "pendingDeletionBytes"] : ["assetCount", "totalBytes"];
  const totals = {};
  keys.forEach((key) => {
    const values = usageRows.map((row) => inventoryValue(snapshot, row.id, key));
    totals[key] = values.some((value) => value == null) ? null : values.reduce((a, b) => a + b, 0n);
  });
  totals.unknownSizeCount = usageRows.some((row) => inventoryValue(snapshot, row.id, "unknownSizeCount") > 0n) ? 1n : 0n;
  const totalSnapshot = snapshot === undefined ? undefined : snapshot === null ? null : { usage: { all: totals } };
  document.getElementById(`${kind}-usage-matching`).textContent = rows.length.toLocaleString();
  document.getElementById(`${kind}-usage-count`).innerHTML = groupOnly ? renderGroupUsage() : renderInventoryMetric(totalSnapshot, "all", keys[0]);
  document.getElementById(`${kind}-usage-size`).innerHTML = groupOnly ? renderGroupUsage() : renderInventoryMetric(totalSnapshot, "all", "totalBytes", true);
  if (blob) document.getElementById("blobstore-usage-pending").innerHTML = renderInventoryMetric(totalSnapshot, "all", "pendingDeletionBytes", true);
  // Keep the summary DOM (including its bound help trigger) stable during refreshes.
  const time = snapshot?.calculatedAt ? new Date(snapshot.calculatedAt) : null;
  const validTime = time && !Number.isNaN(time.valueOf());
  const updated = document.getElementById(`${kind}-usage-updated`);
  updated.textContent = snapshot === undefined ? "…"
    : validTime ? time.toLocaleTimeString(document.documentElement.lang || undefined) : "—";
  updated.title = snapshot === undefined ? "Loading usage…"
    : validTime ? time.toLocaleString(document.documentElement.lang || undefined) : "Usage unavailable. Refresh to retry.";
}

async function fetchInventoryUsage(url) {
  const response = await fetch(url, { cache: "no-store" });
  if (!response.ok) throw new Error(await responseErrorMessage(response));
  return response.json();
}

export {
  fetchInventoryUsage,
  inventoryValue,
  renderInventoryMetric,
  renderRepositoryMetric,
  renderUsageSummary,
};
