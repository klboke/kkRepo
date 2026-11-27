import { bindRequiredFieldErrors } from "../controls/fields.js";
import { nexusMigrationRequiredFields, runNexusMigration, runNexusMigrationPreflight } from "./nexus.js";
import {
  continueRepositoryDataMetadataMigration,
  loadRepositoryDataMigrationJobs,
  repositoryDataMigrationRequiredFields,
  retryRepositoryDataFailedPackages,
  startRepositoryDataMetadataMigration,
  startRepositoryDataPackageMigration,
} from "./repository-data.js";

export function bindMigrationEvents() {
  document.getElementById("nexus-migration-form").addEventListener("submit", (event) => {
    event.preventDefault();
    runNexusMigrationPreflight();
  });
  bindRequiredFieldErrors(nexusMigrationRequiredFields);
  document.getElementById("migration-preflight-button").addEventListener("click", runNexusMigrationPreflight);
  document.getElementById("migration-run-button").addEventListener("click", runNexusMigration);
  document.getElementById("repository-data-migration-form").addEventListener("submit", (event) => {
    event.preventDefault();
    startRepositoryDataMetadataMigration();
  });
  bindRequiredFieldErrors(repositoryDataMigrationRequiredFields);
  document.getElementById("repository-data-migration-start-button").addEventListener("click", startRepositoryDataMetadataMigration);
  document.getElementById("repository-data-migration-metadata-button").addEventListener("click", continueRepositoryDataMetadataMigration);
  document.getElementById("repository-data-migration-packages-button").addEventListener("click", startRepositoryDataPackageMigration);
  document.getElementById("repository-data-migration-retry-failed-button").addEventListener("click", retryRepositoryDataFailedPackages);
  document.getElementById("repository-data-migration-refresh-button").addEventListener("click", loadRepositoryDataMigrationJobs);
}
