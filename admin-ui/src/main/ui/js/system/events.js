import { bindRequiredFieldErrors } from "../controls/fields.js";
import {
  previewUiTheme,
  saveUiSettings,
  syncUiSettingsForm,
  uiSettingsRequiredFields,
} from "./appearance.js";

export function bindSystemEvents() {
  document.getElementById("save-ui-settings-button").addEventListener("click", saveUiSettings);
  document.getElementById("ui-settings-form").addEventListener("submit", (event) => {
    event.preventDefault();
    saveUiSettings();
  });
  document.getElementById("ui-default-theme").addEventListener("change", previewUiTheme);
  bindRequiredFieldErrors(uiSettingsRequiredFields);
  window.addEventListener("kkrepo:i18n-change", syncUiSettingsForm);
}
