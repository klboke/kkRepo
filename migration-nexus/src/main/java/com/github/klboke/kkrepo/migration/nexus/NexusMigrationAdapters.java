package com.github.klboke.kkrepo.migration.nexus;

import com.github.klboke.kkrepo.migration.nexus.NexusMigrationPlan.SupportStatus;
import com.github.klboke.kkrepo.migration.nexus.NexusSourceProfile.MetadataEngine;
import java.util.Locale;

final class NexusMigrationAdapters {
  private static final NexusMigrationAdapter ORIENTDB = new OrientDbNexusAdapter();
  private static final NexusMigrationAdapter DATASTORE_H2 = new DatastoreH2NexusAdapter();
  private static final NexusMigrationAdapter DATASTORE_POSTGRESQL = new DatastorePostgresqlNexusAdapter();
  private static final NexusMigrationAdapter DATASTORE_UNKNOWN = new DatastoreNexusAdapter();
  private static final NexusMigrationAdapter REST_ONLY = new RestOnlyNexusAdapter();

  private NexusMigrationAdapters() {
  }

  static NexusMigrationAdapter select(NexusSourceProfile profile) {
    MetadataEngine engine = profile == null ? MetadataEngine.UNKNOWN : profile.metadataEngine();
    return switch (engine) {
      case ORIENTDB -> ORIENTDB;
      case DATASTORE_H2 -> DATASTORE_H2;
      case DATASTORE_POSTGRESQL -> DATASTORE_POSTGRESQL;
      case DATASTORE_UNKNOWN -> DATASTORE_UNKNOWN;
      case UNKNOWN -> REST_ONLY;
    };
  }

  private abstract static class AbstractNexusMigrationAdapter implements NexusMigrationAdapter {
    @Override
    public String repositoryReadMode(String format, String type, boolean migrateProxyArtifacts) {
      if (isProxy(type) && !migrateProxyArtifacts) {
        return "repository-config-rest";
      }
      return supportsContent(format, type) ? contentReadMode() : "repository-config-rest";
    }

    @Override
    public String repositoryReadMode(
        NexusSourceProfile profile,
        String format,
        String type,
        boolean migrateProxyArtifacts) {
      if (isProxy(type) && !migrateProxyArtifacts) {
        return "repository-config-rest";
      }
      return supportsContent(profile, format, type) ? contentReadMode(profile, format, type) : "repository-config-rest";
    }

    @Override
    public String checksumMode(String format, String type, boolean migrateProxyArtifacts) {
      if (isProxy(type) && !migrateProxyArtifacts) {
        return "not-applicable";
      }
      return supportsContent(format, type) ? contentChecksumMode() : "manual";
    }

    @Override
    public String checksumMode(
        NexusSourceProfile profile,
        String format,
        String type,
        boolean migrateProxyArtifacts) {
      if (isProxy(type) && !migrateProxyArtifacts) {
        return "not-applicable";
      }
      return supportsContent(profile, format, type) ? contentChecksumMode(profile, format, type) : "manual";
    }

    @Override
    public SupportStatus repositoryStatus(String format, String type, boolean migrateProxyArtifacts) {
      if (isProxy(type) && !migrateProxyArtifacts) {
        return SupportStatus.CONFIG_ONLY;
      }
      return supportsContent(format, type) ? SupportStatus.FULL : unsupportedContentStatus();
    }

    @Override
    public SupportStatus repositoryStatus(
        NexusSourceProfile profile,
        String format,
        String type,
        boolean migrateProxyArtifacts) {
      if (isProxy(type) && !migrateProxyArtifacts) {
        return SupportStatus.CONFIG_ONLY;
      }
      return supportsContent(profile, format, type) ? SupportStatus.FULL : unsupportedContentStatus();
    }

    protected SupportStatus unsupportedContentStatus() {
      return SupportStatus.CONFIG_ONLY;
    }

    protected boolean supportsContent(String format, String type) {
      return false;
    }

    protected boolean supportsContent(NexusSourceProfile profile, String format, String type) {
      return supportsContent(format, type);
    }

    protected String contentReadMode() {
      return "repository-config-rest";
    }

    protected String contentReadMode(NexusSourceProfile profile, String format, String type) {
      return contentReadMode();
    }

    protected String contentChecksumMode() {
      return "manual";
    }

    protected String contentChecksumMode(NexusSourceProfile profile, String format, String type) {
      return contentChecksumMode();
    }
  }

  private static final class OrientDbNexusAdapter extends AbstractNexusMigrationAdapter {
    @Override
    public String name() {
      return "OrientDbNexusAdapter";
    }

    @Override
    protected boolean supportsContent(String format, String type) {
      String normalized = lower(format);
      return !"cargo".equals(normalized) && !"pub".equals(normalized);
    }

    @Override
    protected String contentReadMode() {
      return "script-orientdb";
    }

    @Override
    protected String contentChecksumMode() {
      return "asset-checksum-or-http-fallback";
    }
  }

  private static final class DatastoreH2NexusAdapter extends AbstractNexusMigrationAdapter {
    @Override
    public String name() {
      return "DatastoreH2NexusAdapter";
    }

    @Override
    protected boolean supportsContent(NexusSourceProfile profile, String format, String type) {
      return hasDatastoreContentModel(profile, format);
    }

    @Override
    protected String contentReadMode(NexusSourceProfile profile, String format, String type) {
      return "script-datastore";
    }

    @Override
    protected String contentChecksumMode(NexusSourceProfile profile, String format, String type) {
      return "asset-blob-checksum-or-http-verify";
    }
  }

  private static final class DatastorePostgresqlNexusAdapter extends AbstractNexusMigrationAdapter {
    @Override
    public String name() {
      return "DatastorePostgresqlNexusAdapter";
    }

    @Override
    protected boolean supportsContent(NexusSourceProfile profile, String format, String type) {
      return hasDatastoreContentModel(profile, format);
    }

    @Override
    protected String contentReadMode(NexusSourceProfile profile, String format, String type) {
      return "script-datastore";
    }

    @Override
    protected String contentChecksumMode(NexusSourceProfile profile, String format, String type) {
      return "asset-blob-checksum-or-http-verify";
    }
  }

  private static final class DatastoreNexusAdapter extends AbstractNexusMigrationAdapter {
    @Override
    public String name() {
      return "DatastoreNexusAdapter";
    }

    @Override
    protected SupportStatus unsupportedContentStatus() {
      return SupportStatus.NEEDS_MANUAL_ACTION;
    }
  }

  private static final class RestOnlyNexusAdapter extends AbstractNexusMigrationAdapter {
    @Override
    public String name() {
      return "RestOnlyNexusAdapter";
    }

    @Override
    protected SupportStatus unsupportedContentStatus() {
      return SupportStatus.NEEDS_MANUAL_ACTION;
    }
  }

  private static boolean isProxy(String type) {
    return "proxy".equals(lower(type));
  }

  private static boolean hasDatastoreContentModel(NexusSourceProfile profile, String format) {
    if (profile == null || profile.formatCapabilities() == null) {
      return false;
    }
    NexusSourceProfile.FormatCapability capability = profile.formatCapabilities().get(lower(format));
    return capability != null && capability.contentMigration();
  }

  private static String lower(String value) {
    if (value == null || value.isBlank()) {
      return null;
    }
    return value.trim().toLowerCase(Locale.ROOT);
  }
}
