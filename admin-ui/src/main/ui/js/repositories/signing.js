import { updateRequiredMarkers } from "../controls/fields.js";
import { showToast } from "../controls/toast.js";
import { editingRepositoryName, repositoryFormMode, repositoryRequiredFields } from "./form.js";
import { currentRecipe } from "./recipes.js";
import { responseErrorMessage } from "../shell/http.js";

function refreshAptControls() {
  const recipe = currentRecipe();
  const apt = recipe?.format === "apt";
  const hosted = apt && recipe?.type === "HOSTED";
  const proxy = apt && recipe?.type === "PROXY";
  const mode = document.getElementById("repository-apt-metadata-mode");
  if (hosted) mode.value = "RESIGN";
  mode.disabled = hosted;
  document.getElementById("repository-apt-flat-field").hidden = !proxy;
  document.getElementById("repository-apt-enforce-field").hidden = !proxy;
  if (hosted) document.getElementById("repository-apt-enforce-distribution").checked = true;
  const flat = document.getElementById("repository-apt-flat");
  if (!proxy) flat.checked = false;
  if (flat.checked && mode.value === "RESIGN") mode.value = "PASSTHROUGH";
  mode.querySelector('option[value="RESIGN"]').disabled = proxy && flat.checked;
  document.getElementById("repository-apt-valid-until-days").disabled = !hosted && mode.value !== "RESIGN";
  document.getElementById("repository-apt-origin").disabled = !hosted && mode.value !== "RESIGN";
  document.getElementById("repository-apt-label").disabled = !hosted && mode.value !== "RESIGN";
  document.getElementById("repository-apt-operations").hidden =
    !apt || repositoryFormMode !== "edit";
  updateRequiredMarkers(repositoryRequiredFields);
}

function refreshAlpineControls() {
  const recipe = currentRecipe();
  const alpine = recipe?.format === "alpine";
  const proxy = alpine && recipe?.type === "PROXY";
  const locallySigned = alpine && !proxy;
  const mode = document.getElementById("repository-alpine-metadata-mode");
  if (locallySigned) mode.value = "RESIGN";
  mode.disabled = locallySigned;
  const resignProxy = proxy && mode.value === "RESIGN";
  document.getElementById("repository-alpine-verify-field").hidden = !proxy;
  document.getElementById("repository-alpine-stale-field").hidden = !proxy;
  document.getElementById("repository-alpine-upstream-key-field").hidden = !resignProxy;
  if (locallySigned || resignProxy) {
    document.getElementById("repository-alpine-verify-upstream").checked = true;
  }
  document.getElementById("repository-alpine-operations").hidden =
    !alpine || repositoryFormMode !== "edit";
  updateRequiredMarkers(repositoryRequiredFields);
}

async function loadAptStatus(name = editingRepositoryName) {
  if (!name) return;
  const output = document.getElementById("repository-apt-status");
  output.textContent = "Loading APT status…";
  try {
    const response = await fetch(
      `/internal/repositories/${encodeURIComponent(name)}/apt/status`);
    if (!response.ok) throw new Error(await responseErrorMessage(response));
    const status = await response.json();
    const key = status.activeKey
      ? `key ${status.activeKey.fingerprint} (revision ${status.activeKey.revision})`
      : "signing key will be generated on first publication";
    const suites = Array.isArray(status.suites) && status.suites.length
      ? status.suites.map((suite) => {
        const failure = suite.lastError ? `, last error: ${suite.lastError}` : "";
        return `${suite.distribution}: ${suite.publishedRevision}/${suite.desiredRevision}${failure}`;
      }).join("; ")
      : "no metadata snapshot published";
    const proxies = Array.isArray(status.proxyDistributions)
      ? status.proxyDistributions.map((item) =>
        `${item.distribution}: ${item.indexCount} Release entries`).join("; ")
      : "";
    output.textContent = `${key}. Suites: ${suites}.${proxies ? ` Upstream: ${proxies}.` : ""}`;
  } catch (error) {
    output.textContent = `Unable to load APT status: ${error.message}`;
  }
}

async function rebuildAptMetadata() {
  if (!editingRepositoryName) return;
  showToast("Rebuilding APT metadata…");
  try {
    const distribution = document.getElementById("repository-apt-distribution").value.trim();
    const response = await fetch(
      `/internal/repositories/${encodeURIComponent(editingRepositoryName)}/apt/rebuild`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ distribution: distribution || null })
      });
    if (!response.ok) throw new Error(await responseErrorMessage(response));
    showToast("APT metadata rebuilt.", "success");
    await loadAptStatus();
  } catch (error) {
    showToast(error.message || "APT metadata rebuild failed.", "error");
  }
}

async function rotateAptSigningKey() {
  if (!editingRepositoryName) return;
  const privateKey = document.getElementById("repository-apt-private-key").value.trim();
  if (!privateKey) {
    showToast("Paste an ASCII-armored OpenPGP private key first.", "error");
    return;
  }
  showToast("Importing APT signing key…");
  try {
    const response = await fetch(
      `/internal/repositories/${encodeURIComponent(editingRepositoryName)}/apt/signing-key`, {
        method: "PUT",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({
          privateKey,
          passphrase: document.getElementById("repository-apt-key-passphrase").value
        })
      });
    if (!response.ok) throw new Error(await responseErrorMessage(response));
    document.getElementById("repository-apt-private-key").value = "";
    document.getElementById("repository-apt-key-passphrase").value = "";
    showToast("APT signing key rotated and metadata republished.", "success");
    await loadAptStatus();
  } catch (error) {
    showToast(error.message || "APT signing key import failed.", "error");
  }
}

async function generateAptSigningKey() {
  if (!editingRepositoryName) return;
  if (!window.confirm("Generate a new APT signing key and republish repository metadata?")) return;
  showToast("Generating APT signing key…");
  try {
    const response = await fetch(
      `/internal/repositories/${encodeURIComponent(editingRepositoryName)}/apt/signing-key`, {
        method: "PUT",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ generate: true })
      });
    if (!response.ok) throw new Error(await responseErrorMessage(response));
    showToast("APT signing key rotated and metadata republished.", "success");
    await loadAptStatus();
  } catch (error) {
    showToast(error.message || "APT signing key generation failed.", "error");
  }
}

async function loadAlpineStatus(name = editingRepositoryName) {
  if (!name) return;
  const output = document.getElementById("repository-alpine-status");
  output.textContent = "Loading Alpine status…";
  try {
    const response = await fetch(
      `/internal/repositories/${encodeURIComponent(name)}/alpine/status`);
    if (!response.ok) throw new Error(await responseErrorMessage(response));
    const status = await response.json();
    const key = status.activeKey
      ? `${status.activeKey.filename} · ${status.activeKey.fingerprint} · revision ${status.activeKey.revision}`
      : "no active signing key";
    const namespaces = Array.isArray(status.namespaces) && status.namespaces.length
      ? status.namespaces.map((item) => {
        const failure = item.lastError ? `, last error: ${item.lastError}` : "";
        return `${item.namespace}: ${item.publishedRevision}/${item.desiredRevision}${failure}`;
      }).join("; ")
      : "no signed index snapshot published";
    const proxies = Array.isArray(status.proxyNamespaces) && status.proxyNamespaces.length
      ? status.proxyNamespaces.map((item) =>
        `${item.namespace}: ${item.signatureVerified ? "verified" : "unverified"}`).join("; ")
      : "";
    output.textContent = `Key: ${key}. Namespaces: ${namespaces}.${proxies ? ` Upstream: ${proxies}.` : ""}`;
  } catch (error) {
    output.textContent = `Unable to load Alpine status: ${error.message}`;
  }
}

function firstAlpineNamespace() {
  const first = (id, fallback) => document.getElementById(id).value
    .split(",").map((value) => value.trim()).filter(Boolean)[0] || fallback;
  return [
    first("repository-alpine-distributions", "v3.23"),
    first("repository-alpine-channels", "main"),
    first("repository-alpine-architectures", "x86_64")
  ].join("/");
}

async function rebuildAlpineMetadata() {
  if (!editingRepositoryName) return;
  const namespace = firstAlpineNamespace();
  showToast(`Rebuilding Alpine index ${namespace}…`);
  try {
    const response = await fetch(
      `/internal/repositories/${encodeURIComponent(editingRepositoryName)}/alpine/rebuild`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ namespace })
      });
    if (!response.ok) throw new Error(await responseErrorMessage(response));
    showToast("Alpine index rebuilt.", "success");
    await loadAlpineStatus();
  } catch (error) {
    showToast(error.message || "Alpine index rebuild failed.", "error");
  }
}

async function rotateAlpineSigningKey(generate = false) {
  if (!editingRepositoryName) return;
  const privateKey = document.getElementById("repository-alpine-private-key").value.trim();
  if (!generate && !privateKey) {
    showToast("Paste a PKCS#8 RSA private key first.", "error");
    return;
  }
  if (generate && !window.confirm(
      "Generate a new Alpine signing key and republish every known index?")) return;
  showToast(generate ? "Generating Alpine signing key…" : "Importing Alpine signing key…");
  try {
    const response = await fetch(
      `/internal/repositories/${encodeURIComponent(editingRepositoryName)}/alpine/signing-key`, {
        method: "PUT",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({
          generate,
          privateKey: generate ? null : privateKey,
          keyFilename: document.getElementById("repository-alpine-key-filename").value.trim(),
          signatureType: document.getElementById("repository-alpine-signature-type").value
        })
      });
    if (!response.ok) throw new Error(await responseErrorMessage(response));
    document.getElementById("repository-alpine-private-key").value = "";
    showToast("Alpine signing key rotated and indexes republished.", "success");
    await loadAlpineStatus();
  } catch (error) {
    showToast(error.message || "Alpine signing key rotation failed.", "error");
  }
}

async function downloadAlpinePublicKey() {
  if (!editingRepositoryName) return;
  try {
    const response = await fetch(
      `/internal/repositories/${encodeURIComponent(editingRepositoryName)}/alpine/public-key`);
    if (!response.ok) throw new Error(await responseErrorMessage(response));
    const blob = await response.blob();
    const url = URL.createObjectURL(blob);
    const link = document.createElement("a");
    link.href = url;
    link.download = document.getElementById("repository-alpine-key-filename").value.trim()
      || `${editingRepositoryName}.rsa.pub`;
    link.click();
    URL.revokeObjectURL(url);
  } catch (error) {
    showToast(error.message || "Alpine public key download failed.", "error");
  }
}

export {
  downloadAlpinePublicKey,
  generateAptSigningKey,
  loadAlpineStatus,
  loadAptStatus,
  rebuildAlpineMetadata,
  rebuildAptMetadata,
  refreshAlpineControls,
  refreshAptControls,
  rotateAlpineSigningKey,
  rotateAptSigningKey,
};
