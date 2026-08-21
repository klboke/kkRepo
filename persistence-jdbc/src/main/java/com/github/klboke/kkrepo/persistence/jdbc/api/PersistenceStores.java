package com.github.klboke.kkrepo.persistence.jdbc.api;

/** Complete set of persistence contracts available to standalone tools. */
public interface PersistenceStores extends AutoCloseable {
  AlpineRegistryDao alpineRegistry();

  AptRegistryDao aptRegistry();

  AnsibleGalaxyRegistryDao ansibleGalaxyRegistry();

  ArtifactChangeDao artifactChanges();

  AssetDao assets();

  AuthTicketDao authTickets();

  BlobReferenceDao blobReferences();

  BlobStoreDao blobStores();

  BrowseNodeDao browseNodes();

  CacheVersionDao cacheVersions();

  ComponentDao components();

  CleanupPolicyDao cleanupPolicies();

  CondaRegistryDao condaRegistry();

  ConanRegistryDao conanRegistry();

  DockerAuthTokenDao dockerAuthTokens();

  DockerRegistryDao dockerRegistry();

  DockerUploadDao dockerUploads();

  HuggingFaceRegistryDao huggingFaceRegistry();

  MaintenanceCursorDao maintenanceCursors();

  MetadataRebuildDao metadataRebuild();

  MigrationCheckpointDao migrationCheckpoints();

  MigrationJobDao migrationJobs();

  NpmReleaseIndexDao npmReleaseIndexes();

  ProxyStateDao proxyStates();

  RRegistryDao rRegistry();

  PubUploadSessionDao pubUploadSessions();

  RepositoryDao repositories();

  RepositoryDataMigrationDao repositoryDataMigrations();

  RepositoryIndexRebuildDao repositoryIndexRebuild();

  SecurityAuditDao securityAudit();

  SecurityDao security();

  SecurityScanDao securityScanning();

  SwiftRegistryDao swiftRegistry();

  TerraformRegistryDao terraformRegistry();

  UiSettingsDao uiSettings();

  @Override
  default void close() {
    // Implementations without owned resources need no cleanup.
  }
}
