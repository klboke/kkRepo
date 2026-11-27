import * as proxySettings from "../proxy-settings.js";
import { copyLocationValue } from "../controls/clipboard.js";
import { bindRequiredFieldErrors } from "../controls/fields.js";
import {
  conanManifestFields,
  loadConanStatus,
  updateConanManifestHelp,
  updateConanManifestSetting,
} from "./conan.js";
import { clearDockerCache, loadDockerTlsBundles, refreshDockerConnectors } from "./docker.js";
import {
  deleteRepository,
  hideRepositoryForm,
  refreshDockerConnectorControls,
  refreshRepositoryRecipeControls,
  repositoryRequiredFields,
  saveRepository,
  showCreateRepositoryForm,
  showEditRepositoryForm,
} from "./form.js";
import { loadRepositories, renderRepositories } from "./list.js";
import { bindMemberTransferEvents } from "./members.js";
import { bindRepositoryRecipeCombobox } from "./recipes.js";
import {
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
} from "./signing.js";
import { bindSecurityTransfers } from "../security/access.js";
import { positionBlobStoreActionMenu } from "../storage/blob-stores.js";

import { bindRepositoryRowActions } from "./row-actions.js";

export function bindRepositoriesEvents() {
  bindRepositoryRowActions(deleteRepository);
  document.getElementById("repository-filter").addEventListener("input", renderRepositories);
  document.getElementById("repository-store-filter").addEventListener("change", renderRepositories);
  document.getElementById("repository-usage-refresh").addEventListener("click", () => loadRepositories());
  window.addEventListener("resize", positionBlobStoreActionMenu);
  proxySettings.bind();
  document.getElementById("repository-outbound-proxy-mode").addEventListener("change",
      proxySettings.refreshRepositoryForm);
  document.getElementById("repository-allowed-redirect-hosts").addEventListener("input",
      proxySettings.refreshRepositoryForm);
  document.getElementById("create-repository-button").addEventListener("click", showCreateRepositoryForm);
  document.getElementById("cancel-repository-button").addEventListener("click", hideRepositoryForm);
  document.getElementById("save-repository-button").addEventListener("click", saveRepository);
  document.getElementById("repository-form").addEventListener("submit", (event) => {
    event.preventDefault();
    saveRepository();
  });
  bindRepositoryRecipeCombobox();
  document.getElementById("repository-recipe").addEventListener("change", refreshRepositoryRecipeControls);
  document.getElementById("repository-remote-authentication-type").addEventListener("change", refreshRepositoryRecipeControls);
  document.getElementById("repository-docker-connector-enabled").addEventListener("change", refreshDockerConnectorControls);
  document.getElementById("repository-docker-https-enabled").addEventListener("change", () => {
    refreshDockerConnectorControls();
    if (document.getElementById("repository-docker-https-enabled").checked) loadDockerTlsBundles();
  });
  document.getElementById("docker-tls-help").addEventListener("mouseenter", loadDockerTlsBundles);
  document.getElementById("docker-tls-help").addEventListener("focus", loadDockerTlsBundles);
  document.getElementById("repository-docker-tls-bundle").addEventListener("focus", loadDockerTlsBundles);
  document.getElementById("repository-docker-tls-bundle").addEventListener("change", loadDockerTlsBundles);
  document.getElementById("repository-apt-flat").addEventListener("change", refreshAptControls);
  for (const field of conanManifestFields) {
    const input = document.getElementById(field.id);
    input.addEventListener("input", () => updateConanManifestSetting(field));
    input.addEventListener("blur", () => {
      // Refill the inherited display without interpreting that number as a new override.
      if (input.value === "") updateConanManifestSetting(field, true);
    });
  }
  window.addEventListener("kkrepo:i18n-change", updateConanManifestHelp);
  document.getElementById("repository-conan-refresh-status").addEventListener("click", () => loadConanStatus());
  document.getElementById("repository-apt-enforce-distribution").addEventListener("change", refreshAptControls);
  document.getElementById("repository-apt-metadata-mode").addEventListener("change", refreshAptControls);
  document.getElementById("repository-apt-refresh-status").addEventListener("click", () => loadAptStatus());
  document.getElementById("repository-apt-rebuild").addEventListener("click", rebuildAptMetadata);
  document.getElementById("repository-apt-rotate-key").addEventListener("click", rotateAptSigningKey);
  document.getElementById("repository-apt-generate-key").addEventListener("click", generateAptSigningKey);
  document.getElementById("repository-alpine-metadata-mode").addEventListener("change", refreshAlpineControls);
  document.getElementById("repository-alpine-refresh-status").addEventListener("click", () => loadAlpineStatus());
  document.getElementById("repository-alpine-rebuild").addEventListener("click", rebuildAlpineMetadata);
  document.getElementById("repository-alpine-rotate-key").addEventListener("click", () => rotateAlpineSigningKey(false));
  document.getElementById("repository-alpine-generate-key").addEventListener("click", () => rotateAlpineSigningKey(true));
  document.getElementById("repository-alpine-download-key").addEventListener("click", downloadAlpinePublicKey);
  bindRequiredFieldErrors(repositoryRequiredFields);
  bindMemberTransferEvents();
  bindSecurityTransfers();
  document.getElementById("repository-table").addEventListener("click", (event) => {
    const copyButton = event.target.closest(".usage-location-copy");
    if (copyButton) {
      copyLocationValue(copyButton);
      return;
    }
    const editButton = event.target.closest(".edit-repository-button");
    if (editButton) {
      showEditRepositoryForm(editButton.dataset.name);
      return;
    }
  });
  document.getElementById("docker-connectors-refresh-button").addEventListener("click", refreshDockerConnectors);
  document.getElementById("docker-cache-clear-button").addEventListener("click", clearDockerCache);
}
