package com.github.klboke.kkrepo.persistence.jdbc.internal;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.klboke.kkrepo.persistence.jdbc.api.AssetDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.AuthTicketDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.BlobStoreDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.BrowseNodeDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.CacheVersionDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.ComponentDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.DatabaseConnectionSettings;
import com.github.klboke.kkrepo.persistence.jdbc.api.DockerAuthTokenDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.DockerRegistryDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.DockerUploadDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.MaintenanceCursorDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.MetadataRebuildDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.MigrationCheckpointDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.MigrationJobDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.PersistenceStoreFactory;
import com.github.klboke.kkrepo.persistence.jdbc.api.PersistenceStores;
import com.github.klboke.kkrepo.persistence.jdbc.api.ProxyStateDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.PubUploadSessionDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.RepositoryDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.RepositoryDataMigrationDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.RepositoryIndexRebuildDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.SecurityAuditDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.SecurityDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.UiSettingsDao;
import com.github.klboke.kkrepo.persistence.jdbc.internal.support.JsonColumns;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** Default standalone-tool factory for the shared JDBC implementation. */
public final class JdbcPersistenceStoreFactory implements PersistenceStoreFactory {
  @Override
  public PersistenceStores connect(DatabaseConnectionSettings settings) {
    DriverManagerDataSource dataSource = new DriverManagerDataSource();
    dataSource.setUrl(settings.url());
    dataSource.setUsername(settings.username());
    dataSource.setPassword(settings.password());
    JdbcTemplate jdbc = new JdbcTemplate(dataSource);
    JsonColumns json = new JsonColumns(new ObjectMapper());
    return new DefaultPersistenceStores(
        new JdbcAssetDao(jdbc, json),
        new JdbcAuthTicketDao(jdbc),
        new JdbcBlobStoreDao(jdbc, json),
        new JdbcBrowseNodeDao(jdbc),
        new JdbcCacheVersionDao(jdbc),
        new JdbcComponentDao(jdbc, json),
        new JdbcDockerAuthTokenDao(jdbc, json),
        new JdbcDockerRegistryDao(jdbc, json),
        new JdbcDockerUploadDao(jdbc, json),
        new JdbcMaintenanceCursorDao(jdbc),
        new JdbcMetadataRebuildDao(jdbc),
        new JdbcMigrationCheckpointDao(jdbc),
        new JdbcMigrationJobDao(jdbc, json),
        new JdbcProxyStateDao(jdbc, 30),
        new JdbcPubUploadSessionDao(jdbc, json),
        new JdbcRepositoryDao(jdbc, json),
        new JdbcRepositoryDataMigrationDao(jdbc, json),
        new JdbcRepositoryIndexRebuildDao(jdbc),
        new JdbcSecurityAuditDao(jdbc, json),
        new JdbcSecurityDao(jdbc, json),
        new JdbcUiSettingsDao(jdbc));
  }

  private record DefaultPersistenceStores(
      AssetDao assets,
      AuthTicketDao authTickets,
      BlobStoreDao blobStores,
      BrowseNodeDao browseNodes,
      CacheVersionDao cacheVersions,
      ComponentDao components,
      DockerAuthTokenDao dockerAuthTokens,
      DockerRegistryDao dockerRegistry,
      DockerUploadDao dockerUploads,
      MaintenanceCursorDao maintenanceCursors,
      MetadataRebuildDao metadataRebuild,
      MigrationCheckpointDao migrationCheckpoints,
      MigrationJobDao migrationJobs,
      ProxyStateDao proxyStates,
      PubUploadSessionDao pubUploadSessions,
      RepositoryDao repositories,
      RepositoryDataMigrationDao repositoryDataMigrations,
      RepositoryIndexRebuildDao repositoryIndexRebuild,
      SecurityAuditDao securityAudit,
      SecurityDao security,
      UiSettingsDao uiSettings) implements PersistenceStores {
  }
}
