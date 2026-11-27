import { clearRequiredFieldErrors, validateRequiredFields } from "../controls/fields.js";
import { showToast } from "../controls/toast.js";

const uiSettingsRequiredFields = [
  { id: "ui-default-language", label: "Default language" },
  { id: "ui-default-theme", label: "Default theme" }
];

function uiThemeLabel(theme) {
  if (theme === "default") return "Default";
  if (theme === "jfrog") return "JFrog";
  return String(theme || "")
    .split("-")
    .filter(Boolean)
    .map((part) => part.charAt(0).toUpperCase() + part.slice(1))
    .join(" ");
}

function syncUiThemePreviewStatus() {
  const settings = window.kkrepoI18n?.settings?.();
  const themeSelect = document.getElementById("ui-default-theme");
  const previewStatus = document.getElementById("ui-theme-preview-status");
  if (!settings || !themeSelect || !previewStatus) return;
  previewStatus.hidden = themeSelect.value === (settings.defaultTheme || "default");
}

function previewUiTheme() {
  const settings = window.kkrepoI18n?.settings?.();
  const themeSelect = document.getElementById("ui-default-theme");
  if (!settings || !themeSelect || !window.kkrepoTheme) return;
  themeSelect.value = window.kkrepoTheme.applyTheme(
    themeSelect.value,
    settings.supportedDefaultThemes);
  syncUiThemePreviewStatus();
}

function syncUiSettingsForm() {
  const settings = window.kkrepoI18n?.settings?.();
  const languageSelect = document.getElementById("ui-default-language");
  const themeSelect = document.getElementById("ui-default-theme");
  const previewStatus = document.getElementById("ui-theme-preview-status");
  const previewTheme = themeSelect && previewStatus && !previewStatus.hidden
    ? themeSelect.value
    : null;
  if (languageSelect && settings) {
    languageSelect.value = settings.defaultLanguage || "en";
  }
  if (themeSelect && settings) {
    const themes = settings.supportedDefaultThemes?.length
      ? settings.supportedDefaultThemes
      : ["default"];
    const savedTheme = settings.defaultTheme || "default";
    const hasPreview = previewTheme
      && themes.includes(previewTheme)
      && previewTheme !== savedTheme;
    themeSelect.replaceChildren(...themes.map((theme) => {
      const option = document.createElement("option");
      option.value = theme;
      option.textContent = uiThemeLabel(theme);
      return option;
    }));
    themeSelect.value = hasPreview ? previewTheme : savedTheme;
    if (hasPreview) {
      // Loading persisted settings applies their theme before dispatching the
      // change event, so restore the administrator's unsaved preview here.
      window.kkrepoTheme?.applyTheme(themeSelect.value, themes);
    }
  }
  syncUiThemePreviewStatus();
  clearRequiredFieldErrors(uiSettingsRequiredFields);
}

async function loadUiSettings() {
  if (!window.kkrepoI18n) return;
  await window.kkrepoI18n.ready();
  syncUiSettingsForm();
}

async function saveUiSettings() {
  const button = document.getElementById("save-ui-settings-button");
  const languageSelect = document.getElementById("ui-default-language");
  const themeSelect = document.getElementById("ui-default-theme");
  if (!button || !languageSelect || !themeSelect || !window.kkrepoI18n) return;
  if (!validateRequiredFields(uiSettingsRequiredFields)) return;
  button.disabled = true;
  try {
    await window.kkrepoI18n.saveSettings(languageSelect.value, themeSelect.value);
    syncUiSettingsForm();
    showToast("UI settings saved.", "ok");
  } catch (error) {
    showToast(`Save failed: ${error.message}`, "error");
  } finally {
    button.disabled = false;
  }
}

export {
  loadUiSettings,
  previewUiTheme,
  saveUiSettings,
  syncUiSettingsForm,
  uiSettingsRequiredFields,
};
