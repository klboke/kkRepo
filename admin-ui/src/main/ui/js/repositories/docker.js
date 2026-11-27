import { card, escapeHtml, formatInstant, lowerOrEmpty } from "../controls/display.js";
import { activeFieldHelpTrigger, showFieldHelpPopover } from "../controls/help.js";
import { showToast } from "../controls/toast.js";
import { responseErrorMessage } from "../shell/http.js";

let dockerOperations = null;

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
  const summary = document.getElementById("docker-connector-summary");
  const table = document.getElementById("docker-connector-table");
  const transfer = document.getElementById("docker-transfer-summary");
  if (!summary || !table || !transfer) return;
  summary.innerHTML = card("Status", "Loading");
  table.innerHTML = '<tr><td colspan="6" class="placeholder">Loading Docker connector runtime...</td></tr>';
  transfer.innerHTML = "";
  try {
    const response = await fetch("/internal/docker/connectors", {
      headers: { Accept: "application/json" },
      cache: "no-store",
    });
    if (!response.ok) throw new Error(await responseErrorMessage(response));
    dockerOperations = await response.json();
    renderDockerOperations(dockerOperations);
  } catch (error) {
    renderDockerOperationsError(error.message);
  }
}

function renderDockerOperations(payload) {
  const connector = payload?.connector || {};
  const transfer = payload?.transfer || {};
  const connectors = Array.isArray(connector.connectors) ? connector.connectors : [];
  const active = connectors.filter((item) => item.active).length;
  const errors = connector.lastError ? 1 : 0;
  document.getElementById("docker-connector-summary").innerHTML = [
    card("Enabled", connector.enabled ? "Yes" : "No"),
    card("Active ports", `${active} / ${connectors.length}`),
    card("Sequence", connector.sequence ?? "-"),
    card("Node", connector.nodeId || "-"),
    card("Last refreshed", formatInstant(connector.refreshedAt) || "-"),
    card("Runtime errors", errors ? "Yes" : "No"),
  ].join("");
  document.getElementById("docker-transfer-summary").innerHTML = [
    card("Active uploads", transfer.activeUploads ?? 0),
    card("Max uploads", transfer.maxConcurrentUploads ? transfer.maxConcurrentUploads : "Unlimited"),
    card("Active downloads", transfer.activeDownloads ?? 0),
    card("Max downloads", transfer.maxConcurrentDownloads ? transfer.maxConcurrentDownloads : "Unlimited"),
  ].join("");
  document.getElementById("docker-connector-table").innerHTML = connectors.map((item) => `
    <tr>
      <td>${escapeHtml(item.repositoryName || "")}</td>
      <td>${escapeHtml(lowerOrEmpty(item.repositoryType))}</td>
      <td><code>${escapeHtml(item.scheme || "http")} : ${escapeHtml(item.port ?? "")}</code></td>
      <td>${item.publicUrl ? `<code>${escapeHtml(item.publicUrl)}</code>` : '<span class="health-muted">-</span>'}</td>
      <td><span class="state-badge compact ${item.active ? "ok" : "warn"}">${escapeHtml(item.state || "")}</span></td>
      <td>${item.active ? "Yes" : "No"}${item.tlsBundle ? `<br><code title="${escapeHtml(item.certificateFingerprint || "")}">${escapeHtml(item.tlsBundle)}</code>` : ""}</td>
    </tr>
  `).join("") || '<tr><td colspan="6" class="placeholder">No Docker connector ports configured.</td></tr>';
  const status = document.getElementById("docker-operations-status");
  status.textContent = connector.lastError ? `Last runtime error: ${connector.lastError}` : "";
  status.classList.toggle("error", Boolean(connector.lastError));
}

function renderDockerOperationsError(message) {
  document.getElementById("docker-connector-summary").innerHTML = card("Status", "Failed");
  document.getElementById("docker-connector-table").innerHTML =
      `<tr><td colspan="6" class="placeholder">${escapeHtml(message || "Docker operations request failed.")}</td></tr>`;
  document.getElementById("docker-transfer-summary").innerHTML = "";
  const status = document.getElementById("docker-operations-status");
  status.textContent = message || "";
  status.classList.add("error");
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
    dockerOperations = await response.json();
    renderDockerOperations(dockerOperations);
    showToast("Docker connectors refreshed.", "ok");
  } catch (error) {
    renderDockerOperationsError(error.message);
    showToast(error.message || "Docker connector refresh failed.", "error");
  } finally {
    button.disabled = false;
  }
}

async function clearDockerCache() {
  const button = document.getElementById("docker-cache-clear-button");
  button.disabled = true;
  try {
    const response = await fetch("/internal/docker/cache/clear", {
      method: "POST",
      headers: { Accept: "application/json" },
      cache: "no-store",
    });
    if (!response.ok) throw new Error(await responseErrorMessage(response));
    const payload = await response.json();
    showToast(`Docker cache cleared for ${payload.repositories ?? 0} repositories.`, "ok");
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
