import { escapeHtml, formatInstant, lowerOrEmpty } from "../controls/display.js";
import { activeFieldHelpTrigger, showFieldHelpPopover } from "../controls/help.js";
import { showToast } from "../controls/toast.js";
import { responseErrorMessage } from "../shell/http.js";

let dockerLoadVersion = 0;

async function loadDockerTlsBundles() {
  const help = document.getElementById("docker-tls-help");
  const setup = help.dataset.setup || help.dataset.tooltip;
  help.dataset.setup = setup;
  const translate = value => window.kkrepoI18n?.text(value) ?? value;
  const updateHelp = status => {
    const message = [translate(status), translate(setup)].filter(Boolean).join(" ");
    help.dataset.tooltip = message;
    help.setAttribute("aria-label", message);
    if (activeFieldHelpTrigger === help) showFieldHelpPopover(help);
  };
  try {
    const response = await fetch("/internal/docker/tls-bundles", { headers: { Accept: "application/json" }, cache: "no-store" });
    if (!response.ok) throw new Error(await responseErrorMessage(response));
    const payload = await response.json();
    document.getElementById("docker-tls-bundles").innerHTML = (payload.bundles || [])
      .filter(bundle => bundle.usable).map(bundle =>
        `<option value="${escapeHtml(bundle.name)}">${escapeHtml((bundle.domains || []).join(", "))} · ${escapeHtml(bundle.expiresAt || "")}</option>`).join("");
    const selected = document.getElementById("repository-docker-tls-bundle").value.trim() || payload.defaultBundle;
    const bundle = (payload.bundles || []).find(item => item.name === selected);
    updateHelp(bundle?.usable
      ? `${bundle.name} · ${(bundle.domains || []).join(", ")} · ${bundle.expiresAt} · SHA-256: ${bundle.fingerprint}`
      : bundle?.error || (selected
        ? `${translate("TLS configuration not found:")} ${selected}.`
        : "No default TLS configuration is set. Select a configured name here or set the deployment default."));
  } catch (error) {
    updateHelp(error.message);
  }
}

async function loadDockerOperations() {
  const version = ++dockerLoadVersion;
  renderDockerOperations(null);
  try {
    const response = await fetch("/internal/docker/connectors", {
      headers: { Accept: "application/json" },
      cache: "no-store",
    });
    if (!response.ok) throw new Error(await responseErrorMessage(response));
    const snapshot = await response.json();
    if (version === dockerLoadVersion) renderDockerOperations(snapshot);
  } catch (error) {
    if (version === dockerLoadVersion) renderDockerOperations(null, error.message || "Docker operations request failed.");
  }
}

const connectorStates = new Map([
  ["active", "Active"], ["pending", "Pending"], ["error", "Error"],
  ["globally-disabled", "Globally disabled"], ["repository-disabled", "Repository disabled"],
  ["duplicate-port", "Port conflict"],
]);

function dockerConnectorRow(item) {
  const failed = item.state === "error" || item.state === "duplicate-port";
  const style = failed ? "bad" : item.active ? "ok" : "warn";
  const state = connectorStates.get(item.state) || item.state || "—";
  const tls = escapeHtml(item.tlsBundle || "");
  const certificate = item.certificateFingerprint && tls
    ? `<details class="docker-tls"><summary><span>TLS configuration</span> <code>${tls}</code></summary>
        <span>Certificate fingerprint</span><code data-i18n-skip>${escapeHtml(item.certificateFingerprint)}</code></details>`
    : tls && `<small class="docker-tls"><span>TLS configuration</span> <code>${tls}</code></small>`;
  return `<tr role="row">
    <td role="cell" class="docker-repo-cell"><strong data-i18n-skip>${escapeHtml(item.repositoryName)}</strong><small class="docker-repo-type" data-i18n-skip>${escapeHtml(lowerOrEmpty(item.repositoryType))}</small></td>
    <td role="cell" class="docker-listener-cell"><code>${escapeHtml((item.scheme || "http").toUpperCase())} ${escapeHtml(item.port ?? "—")}</code>${certificate}</td>
    <td role="cell" class="docker-url-cell"><span class="docker-url-label" aria-hidden="true">Public URL</span>${item.publicUrl ? `<code>${escapeHtml(item.publicUrl)}</code>` : '<span class="docker-url-unset">Not set</span>'}</td>
    <td role="cell" class="docker-state-cell"><span class="state-badge compact ${style}">${escapeHtml(state)}</span></td>
  </tr>`;
}

function renderDockerOperations(snapshot, requestError = "") {
  const connector = snapshot?.connector;
  const transfer = snapshot?.transfer;
  const connectors = connector?.connectors || [];
  const active = connectors.filter(item => item.active).length;
  const summary = document.getElementById("docker-connector-summary");
  if (connector) {
    summary.innerHTML = `<span>${connector.enabled ? "Enabled" : "Disabled"}</span> · <span>Active ports</span> ${active} / ${connectors.length}`;
  } else {
    summary.textContent = requestError ? "Failed" : "Loading…";
  }
  const error = requestError || connector?.lastError || "";
  const status = document.getElementById("docker-operations-status");
  status.textContent = error;
  status.hidden = !error;
  const emptyMessage = connector ? "No Docker connector ports configured." : requestError ? "Failed" : "Loading…";
  document.getElementById("docker-connector-table").innerHTML = connectors.length
    ? connectors.map(dockerConnectorRow).join("")
    : `<tr role="row"><td role="cell" colspan="4" class="empty-cell">${emptyMessage}</td></tr>`;

  for (const [direction, current, limit] of [
    ["uploads", transfer?.activeUploads, transfer?.maxConcurrentUploads],
    ["downloads", transfer?.activeDownloads, transfer?.maxConcurrentDownloads],
  ]) {
    document.getElementById("docker-active-" + direction).textContent = current ?? "—";
    document.getElementById("docker-max-" + direction).textContent = limit === 0 ? "Unlimited" : limit ?? "—";
  }
  const refreshed = formatInstant(connector?.refreshedAt);
  document.getElementById("docker-last-sync").innerHTML = refreshed
    ? `<span>Last refreshed</span> · <time datetime="${escapeHtml(connector.refreshedAt)}">${escapeHtml(new Date(connector.refreshedAt).toLocaleTimeString())}</time>` : "";
  document.getElementById("docker-node-id").textContent = connector?.nodeId || "—";
  document.getElementById("docker-sequence").textContent = connector?.sequence ?? "—";
  document.getElementById("docker-refreshed-at").textContent = refreshed || "—";
  document.getElementById("docker-runtime-errors").textContent = connector ? (connector.lastError ? "Yes" : "No") : "—";
}

async function refreshDockerConnectors() {
  const button = document.getElementById("docker-connectors-refresh-button");
  button.disabled = true;
  try {
    const response = await fetch("/internal/docker/connectors/refresh", {
      method: "POST",
      headers: { Accept: "application/json" },
      cache: "no-store",
    });
    if (!response.ok) throw new Error(await responseErrorMessage(response));
    const snapshot = await response.json();
    ++dockerLoadVersion;
    renderDockerOperations(snapshot);
    showToast("Docker connectors refreshed.", "ok");
  } catch (error) {
    ++dockerLoadVersion;
    renderDockerOperations(null, error.message || "Docker connector refresh failed.");
    showToast(error.message || "Docker connector refresh failed.", "error");
  } finally {
    button.disabled = false;
  }
}

async function clearDockerCache() {
  const button = document.getElementById("docker-cache-clear-button");
  if (!await window.kkrepoConfirmDelete({
    title: "Clear cache for all Docker repositories?",
    description: "Invalidate cached content lookups and metadata across all Docker repositories.",
    warning: "Cached misses are cleared too. Stored images and tags are preserved.",
    confirmLabel: "Clear Docker cache",
  })) return;
  button.disabled = true;
  try {
    const response = await fetch("/internal/docker/cache/clear", {
      method: "POST",
      headers: { Accept: "application/json" },
      cache: "no-store",
    });
    if (!response.ok) throw new Error(await responseErrorMessage(response));
    const payload = await response.json();
    showToast(`Docker repositories with cache cleared: ${payload.repositories ?? 0}`, "ok");
    await loadDockerOperations();
  } catch (error) {
    showToast(error.message || "Docker cache clear failed.", "error");
  } finally {
    button.disabled = false;
  }
}

export {
  clearDockerCache,
  loadDockerOperations,
  loadDockerTlsBundles,
  refreshDockerConnectors,
};
