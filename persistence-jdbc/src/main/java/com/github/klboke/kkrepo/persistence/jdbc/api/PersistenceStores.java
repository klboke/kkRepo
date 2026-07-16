package com.github.klboke.kkrepo.persistence.jdbc.api;

/** Complete set of persistence contracts available to standalone tools. */
public interface PersistenceStores extends AutoCloseable {
  AssetDao assets();

  AuthTicketDao authTickets();

  BlobStoreDao blobStores();

  BrowseNodeDao browseNodes();

  CacheVersionDao cacheVersions();

  ComponentDao components();

  DockerAuthTokenDao dockerAuthTokens();

  DockerRegistryDao dockerRegistry();

  DockerUploadDao dockerUploads();

  MaintenanceCursorDao maintenanceCursors();

  MetadataRebuildDao metadataRebuild();

  MigrationCheckpointDao migrationCheckpoints();

  MigrationJobDao migrationJobs();

  ProxyStateDao proxyStates();

  PubUploadSessionDao pubUploadSessions();

  RepositoryDao repositories();

  RepositoryDataMigrationDao repositoryDataMigrations();

  RepositoryIndexRebuildDao repositoryIndexRebuild();

  SecurityAuditDao securityAudit();

  SecurityDao security();

  TerraformRegistryDao terraformRegistry();

  UiSettingsDao uiSettings();

  @Override
  default void close() {
    // Implementations without owned resources need no cleanup.
  }
}
