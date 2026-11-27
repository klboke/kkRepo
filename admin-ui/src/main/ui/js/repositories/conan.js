import { editingRepositoryName } from "./form.js";
import { responseErrorMessage } from "../shell/http.js";

let conanManifestDefaults = null;

let repositoryConanOverrides = {};

let repositoryConanSavedOverrides = {};

const conanManifestFields = [
  { key: "manifestMaxEntries", id: "repository-conan-manifest-max-entries",
    property: "kkrepo.conan.manifest.max-entries", env: "KKREPO_CONAN_MANIFEST_MAX_ENTRIES",
    description: "Maximum entries in a manifest." },
  { key: "manifestMaxBytes", id: "repository-conan-manifest-max-bytes",
    property: "kkrepo.conan.manifest.max-bytes", env: "KKREPO_CONAN_MANIFEST_MAX_BYTES",
    description: "Maximum manifest text size in bytes, not the package archive size." }
];

async function loadConanManifestDefaults() {
  try {
    const response = await fetch("/internal/repositories/conan/defaults", { cache: "no-store" });
    if (!response.ok) throw new Error(await responseErrorMessage(response));
    const defaults = await response.json();
    if (!conanManifestFields.every(({ key, id }) => Number.isInteger(defaults[key])
        && defaults[key] >= 1 && defaults[key] <= Number(document.getElementById(id).max))) {
      throw new Error("Invalid Conan manifest defaults");
    }
    conanManifestDefaults = defaults;
  } catch (error) {
    // An older/unavailable server must never make the UI invent effective values or pin defaults.
    conanManifestDefaults = null;
  }
}

function setConanManifestSettings(settings = {}) {
  repositoryConanOverrides = {};
  for (const { key, id } of conanManifestFields) {
    repositoryConanOverrides[key] = settings?.[key] ?? null;
    const input = document.getElementById(id);
    input.value = repositoryConanOverrides[key] ?? conanManifestDefaults?.[key] ?? "";
    input.disabled = !conanManifestDefaults;
  }
  repositoryConanSavedOverrides = { ...repositoryConanOverrides };
  updateConanManifestHelp();
}

function updateConanManifestSetting(field, restoreDefault = false) {
  if (!conanManifestDefaults) return;
  const input = document.getElementById(field.id);
  const value = input.value === "" ? null : Number(input.value);
  const originalValue = repositoryConanSavedOverrides[field.key] ?? conanManifestDefaults[field.key];
  repositoryConanOverrides[field.key] = value === originalValue
    ? repositoryConanSavedOverrides[field.key] : value;
  if (restoreDefault && value === null && !input.validity.badInput) {
    input.value = conanManifestDefaults[field.key];
  }
  updateConanManifestHelp();
}

function updateConanManifestHelp() {
  const text = value => window.kkrepoI18n?.text(value) ?? value;
  for (const field of conanManifestFields) {
    const help = document.getElementById(`${field.id}-help`);
    let explanation = text("Effective Conan limits unavailable. Reload the page to retry; saved overrides are unchanged.");
    if (conanManifestDefaults) {
      const changed = repositoryConanOverrides[field.key] !== repositoryConanSavedOverrides[field.key];
      const source = repositoryConanOverrides[field.key] == null ? "Server default" : "Repository override";
      explanation = [text(field.description),
        `${text(changed ? "Source after saving" : "Current source")}: ${text(source)}.`,
        `${text("Server default")}: ${conanManifestDefaults[field.key]} (${field.property}; ${field.env}).`,
        text("Clear this field to inherit the server default; its value reappears when you leave the field."),
        text("Saving applies to subsequent manifest validations without a restart.")].join(" ");
    }
    help.dataset.tooltip = explanation;
    help.setAttribute("aria-label", explanation);
  }
}

async function loadConanStatus(name = editingRepositoryName) {
  if (!name) return;
  const output = document.getElementById("repository-conan-status");
  output.textContent = "Loading Conan status…";
  try {
    const response = await fetch(
      `/internal/repositories/${encodeURIComponent(name)}/conan/status`);
    if (!response.ok) throw new Error(await responseErrorMessage(response));
    const status = await response.json();
    output.textContent = [
      `${status.recipes ?? 0} recipes`,
      `${status.recipeRevisions ?? 0} recipe revisions`,
      `${status.packages ?? 0} packages`,
      `${status.packageRevisions ?? 0} package revisions`,
      `${status.committedFiles ?? 0} committed files`,
      `${status.openUploadSessions ?? 0} open upload sessions`,
      `${status.cachedProxyFiles ?? 0} cached proxy files`,
    ].join(" · ");
  } catch (error) {
    output.textContent = error.message || "Conan status unavailable.";
  }
}

export {
  conanManifestDefaults,
  conanManifestFields,
  loadConanManifestDefaults,
  loadConanStatus,
  repositoryConanOverrides,
  setConanManifestSettings,
  updateConanManifestHelp,
  updateConanManifestSetting,
};
