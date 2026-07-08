package com.github.klboke.kkrepo.migration.nexus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.core.RepositoryType;
import com.github.klboke.kkrepo.migration.nexus.NexusApiMigrationService.NexusMigrationPreflight;
import com.github.klboke.kkrepo.migration.nexus.NexusApiMigrationService.ConfigMigrationCounts;
import com.github.klboke.kkrepo.migration.nexus.NexusApiMigrationService.NexusMigrationRequest;
import com.github.klboke.kkrepo.migration.nexus.NexusApiMigrationService.NexusMigrationTargetBlobStore;
import com.github.klboke.kkrepo.migration.nexus.NexusMigrationPlan.SupportStatus;
import com.github.klboke.kkrepo.migration.nexus.NexusSourceProfile.MetadataEngine;
import com.github.klboke.kkrepo.migration.nexus.NexusRestClient.NexusInventory;
import com.github.klboke.kkrepo.migration.nexus.NexusRestClient.RepositoryDocument;
import com.github.klboke.kkrepo.migration.nexus.NexusRestClient.SourceProbe;
import com.github.klboke.kkrepo.migration.nexus.security.NexusSecurityExport;
import com.github.klboke.kkrepo.persistence.mysql.dao.BlobStoreDao;
import com.github.klboke.kkrepo.persistence.mysql.dao.RepositoryDao;
import com.github.klboke.kkrepo.persistence.mysql.model.BlobStoreRecord;
import com.github.klboke.kkrepo.persistence.mysql.model.RepositoryRecord;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class NexusApiMigrationServiceTest {

  @Test
  void preflightIncludesMigrationPlanDetailsForResultPanel() {
    NexusApiMigrationService service = service(new FakeBlobStoreDao(), new FakeRepositoryDao());

    NexusMigrationPreflight preflight = service.preflight(new NexusInventory(
        List.of(Map.of("name", "default", "type", "File")),
        List.of(
            repository("maven-releases", "maven2", "hosted", Map.of(
                "storage", storage("default"))),
            repository("maven-central", "maven2", "proxy", Map.of(
                "storage", storage("default"),
                "proxy", Map.of("remoteUrl", "https://repo1.maven.org/maven2/"))),
            repository("maven-public", "maven2", "group", Map.of(
                "storage", storage("default"),
                "group", Map.of("memberNames", List.of("maven-releases", "maven-central")))),
            repository("go-hosted", "go", "hosted", Map.of("storage", storage("default")))),
        new NexusSecurityExport(
            List.of(Map.of(
                "id", "alice",
                "source", "default",
                "email", "alice@example.test",
                "passwordHash", "$shiro1$hash")),
            List.of(Map.of(
                "id", "nx-developer",
                "name", "Developer",
                "privileges", List.of("nx-repository-view-maven2-*-read"))),
            List.of(Map.of(
                "id", "nx-repository-view-maven2-*-read",
                "type", "repository-view",
                "properties", Map.of("repository", "*", "format", "maven2", "actions", "read"))),
            List.of(Map.of(
                "userId", "alice",
                "source", "default",
                "roles", List.of("nx-developer"))),
            List.of(Map.of(
                "domain", "NpmToken",
                "ownerSource", "default",
                "ownerUserId", "alice",
                "api_key", "raw-token-value")),
            List.of(Map.of(
                "name", "public-maven",
                "type", "csel",
                "expression", "format == 'maven2'")),
            List.of(),
            List.of("NexusAuthenticatingRealm"),
            Map.of("enabled", true, "userId", "anonymous")),
        List.of("security internals exported through script API")),
        new NexusMigrationTargetBlobStore(
            "default",
            "s3",
            "http://s3.local",
            "us-east-1",
            "kkrepo",
            "migrated",
            Map.of()));

    assertEquals(1, preflight.blobStorePlans().size());
    assertEquals("default", preflight.blobStorePlans().get(0).sourceName());
    assertEquals("s3", preflight.blobStorePlans().get(0).targetType());
    assertEquals("migrated", preflight.blobStorePlans().get(0).targetPrefix());
    assertEquals(3, preflight.repositoriesToMigrate().size());
    assertEquals("maven2-hosted", preflight.repositoriesToMigrate().get(0).recipe());
    assertEquals(1, preflight.unsupported().size());
    assertEquals("go-hosted", preflight.unsupported().get(0).name());
    assertEquals(List.of("maven-releases", "maven-central"), preflight.groupRepositories().get(0).members());
    assertEquals("https://repo1.maven.org/maven2/", preflight.proxyRemoteRisks().get(0).remoteUrl());
    assertEquals(1, preflight.security().users());
    assertEquals("alice", preflight.security().userDetails().get(0).userId());
    assertTrue(preflight.security().userDetails().get(0).passwordHashPresent());
    assertEquals("nx-developer", preflight.security().roleDetails().get(0).id());
    assertEquals(1, preflight.security().privileges());
    assertEquals("alice", preflight.security().userRoleMappingDetails().get(0).userId());
    assertEquals("NpmToken", preflight.security().apiKeyDetails().get(0).domain());
    assertTrue(preflight.security().apiKeyDetails().get(0).rawKeyPresent());
    assertEquals("public-maven", preflight.security().contentSelectorDetails().get(0).name());
    assertEquals(List.of("NexusAuthenticatingRealm"), preflight.security().realmOrder());
    assertEquals(MetadataEngine.ORIENTDB, preflight.sourceProfile().metadataEngine());
    assertEquals("OrientDbNexusAdapter", preflight.migrationPlan().adapter());
    assertEquals(
        SupportStatus.FULL,
        preflight.migrationPlan().items().stream()
            .filter(item -> "maven-releases".equals(item.name()))
            .findFirst()
            .orElseThrow()
            .status());
    assertEquals(64, preflight.migrationPlan().profileHash().length());
    assertEquals(64, preflight.migrationPlan().planHash().length());
  }

  @Test
  void datastoreSourceProfileEnablesRepositoryContentWhenSchemaFingerprintMatches() {
    NexusApiMigrationService service = service(new FakeBlobStoreDao(), new FakeRepositoryDao());
    SourceProbe probe = new SourceProbe(
        "3.77.2-02",
        true,
        true,
        true,
        "text/plain",
        "ok",
        "DATASTORE_H2",
        "H2 2.3.232",
        "jdbc:h2:file:/nexus-data/db/nexus",
        datastoreSchema(Map.of("maven2", true, "cargo", true)),
        List.of());

    NexusMigrationPreflight preflight = service.preflight(new NexusInventory(
        List.of(Map.of("name", "default", "type", "File")),
        List.of(
            repository("maven-releases", "maven2", "hosted", Map.of("storage", storage("default"))),
            repository("cargo-hosted", "cargo", "hosted", Map.of("storage", storage("default")))),
        NexusSecurityExport.empty(),
        List.of(),
        probe),
        new NexusMigrationTargetBlobStore("default", "s3", null, null, null, "", Map.of()),
        null);

    assertEquals(MetadataEngine.DATASTORE_H2, preflight.sourceProfile().metadataEngine());
    assertEquals("DatastoreH2NexusAdapter", preflight.migrationPlan().adapter());
    assertEquals("3.77.2-02", preflight.sourceProfile().nexusVersion());
    assertTrue(preflight.warnings().stream().noneMatch(value -> value.contains("Datastore-era Nexus")));
    assertEquals(
        true,
        preflight.sourceProfile().formatCapabilities().get("maven2").contentMigration());
    assertEquals(
        true,
        preflight.sourceProfile().formatCapabilities().get("cargo").contentMigration());
    assertTrue(preflight.warnings().stream()
        .noneMatch(value -> value.contains("Cargo migration remains configuration-only")));
    assertEquals(
        SupportStatus.FULL,
        preflight.migrationPlan().items().stream()
            .filter(item -> "maven-releases".equals(item.name()))
            .findFirst()
            .orElseThrow()
            .status());
    assertEquals(
        SupportStatus.FULL,
        preflight.migrationPlan().items().stream()
            .filter(item -> "cargo-hosted".equals(item.name()))
            .findFirst()
            .orElseThrow()
            .status());
  }

  @Test
  void postgresqlDatastoreSourceProfileEnablesCargoContentWhenSchemaFingerprintMatches() {
    NexusApiMigrationService service = service(new FakeBlobStoreDao(), new FakeRepositoryDao());
    SourceProbe probe = new SourceProbe(
        "3.77.2-02",
        true,
        true,
        true,
        "text/plain",
        "ok",
        "DATASTORE_POSTGRESQL",
        "PostgreSQL",
        "jdbc:postgresql://postgres:5432/nexus",
        datastoreSchema(Map.of("cargo", true)),
        List.of());

    NexusMigrationPreflight preflight = service.preflight(new NexusInventory(
        List.of(Map.of("name", "default", "type", "File")),
        List.of(repository("cargo-hosted", "cargo", "hosted", Map.of("storage", storage("default")))),
        NexusSecurityExport.empty(),
        List.of(),
        probe),
        new NexusMigrationTargetBlobStore("default", "s3", null, null, null, "", Map.of()),
        null);

    assertEquals(MetadataEngine.DATASTORE_POSTGRESQL, preflight.sourceProfile().metadataEngine());
    assertEquals("DatastorePostgresqlNexusAdapter", preflight.migrationPlan().adapter());
    assertTrue(preflight.sourceProfile().formatCapabilities().get("cargo").contentMigration());
    assertEquals(
        SupportStatus.FULL,
        preflight.migrationPlan().items().stream()
            .filter(item -> "cargo-hosted".equals(item.name()))
            .findFirst()
            .orElseThrow()
            .status());
  }

  @Test
  void securityPlanRequiresManualActionWhenSourceSecretsAreMissing() {
    NexusApiMigrationService service = service(new FakeBlobStoreDao(), new FakeRepositoryDao());
    SourceProbe probe = new SourceProbe(
        "3.77.2-02",
        true,
        true,
        true,
        "text/plain",
        "ok",
        "DATASTORE_H2",
        "H2 2.3.232",
        "jdbc:h2:file:/nexus-data/db/nexus",
        datastoreSchema(Map.of("maven2", true)),
        List.of());

    NexusMigrationPreflight preflight = service.preflight(new NexusInventory(
        List.of(Map.of("name", "default", "type", "File")),
        List.of(repository("maven-releases", "maven2", "hosted", Map.of("storage", storage("default")))),
        NexusSecurityExport.empty(),
        List.of("Source Nexus script API did not expose API keys"),
        probe),
        new NexusMigrationTargetBlobStore("default", "s3", null, null, null, "", Map.of()),
        null);

    NexusMigrationPlan.NexusMigrationPlanItem securityItem = preflight.migrationPlan().items().stream()
        .filter(item -> "security".equals(item.area()))
        .findFirst()
        .orElseThrow();
    assertEquals(SupportStatus.DATA_ONLY, securityItem.status());
    assertEquals("rest-and-script-manual-secrets", securityItem.readMode());
    assertTrue(preflight.migrationPlan().manualActions().contains("security:local-security"));
  }

  @Test
  void datastoreSourceProfilePlansRepositoryContentConfigOnlyWhenSchemaFingerprintIsIncomplete() {
    NexusApiMigrationService service = service(new FakeBlobStoreDao(), new FakeRepositoryDao());
    SourceProbe probe = new SourceProbe(
        "3.77.2-02",
        true,
        true,
        true,
        "text/plain",
        "ok",
        "DATASTORE_H2",
        "H2 2.3.232",
        "jdbc:h2:file:/nexus-data/db/nexus",
        datastoreSchema("maven2", false),
        List.of());

    NexusMigrationPreflight preflight = service.preflight(new NexusInventory(
        List.of(Map.of("name", "default", "type", "File")),
        List.of(repository("maven-releases", "maven2", "hosted", Map.of("storage", storage("default")))),
        NexusSecurityExport.empty(),
        List.of(),
        probe),
        new NexusMigrationTargetBlobStore("default", "s3", null, null, null, "", Map.of()),
        null);

    assertEquals(false, preflight.sourceProfile().formatCapabilities().get("maven2").contentMigration());
    assertEquals(
        SupportStatus.CONFIG_ONLY,
        preflight.migrationPlan().items().stream()
            .filter(item -> "maven-releases".equals(item.name()))
            .findFirst()
            .orElseThrow()
            .status());
  }

  @Test
  void pubDatastoreSourceProfileEnablesFullHostedContentMigrationWhenSchemaFingerprintMatches() {
    NexusApiMigrationService service = service(new FakeBlobStoreDao(), new FakeRepositoryDao());
    SourceProbe probe = new SourceProbe(
        "3.92.0-03",
        true,
        true,
        true,
        "text/plain",
        "ok",
        "DATASTORE_H2",
        "H2",
        "jdbc:h2:file:/nexus-data/db/nexus",
        datastoreSchema(Map.of("pub", true)),
        List.of());

    NexusMigrationPreflight preflight = service.preflight(new NexusInventory(
        List.of(Map.of("name", "default", "type", "File")),
        List.of(
            repository("pub-hosted", "pub", "hosted", Map.of("storage", storage("default"))),
            repository("pub-proxy", "pub", "proxy", Map.of(
                "storage", storage("default"),
                "proxy", Map.of("remoteUrl", "https://pub.dev/"))),
            repository("pub-group", "pub", "group", Map.of(
                "storage", storage("default"),
                "group", Map.of("memberNames", List.of("pub-hosted", "pub-proxy"))))),
        NexusSecurityExport.empty(),
        List.of(),
        probe),
        new NexusMigrationTargetBlobStore("default", "s3", null, null, null, "", Map.of()),
        null);

    assertEquals(3, preflight.supportedRepositories());
    assertEquals(
        List.of("pub-hosted", "pub-proxy", "pub-group"),
        preflight.repositoriesToMigrate().stream().map(NexusApiMigrationService.RepositoryMigrationPlan::name).toList());
    assertEquals("pub-hosted", preflight.repositoriesToMigrate().get(0).recipe());
    assertEquals("pub-proxy", preflight.repositoriesToMigrate().get(1).recipe());
    assertEquals("pub-group", preflight.repositoriesToMigrate().get(2).recipe());
    assertEquals(List.of("pub-hosted", "pub-proxy"), preflight.groupRepositories().get(0).members());
    assertEquals(true, preflight.sourceProfile().formatCapabilities().get("pub").contentMigration());
    assertEquals(
        "datastore-content-exporter:PUB",
        preflight.sourceProfile().formatCapabilities().get("pub").evidence());
    assertEquals(
        SupportStatus.FULL,
        preflight.migrationPlan().items().stream()
            .filter(item -> "pub-hosted".equals(item.name()))
            .findFirst()
            .orElseThrow()
            .status());
    assertEquals(
        SupportStatus.CONFIG_ONLY,
        preflight.migrationPlan().items().stream()
            .filter(item -> "pub-proxy".equals(item.name()))
            .findFirst()
            .orElseThrow()
            .status());
    NexusMigrationPlan proxyBackupPlan = new MigrationPlanBuilder().build(
        preflight.sourceProfile(),
        new MigrationPlanBuilder.MigrationScope(List.of("pub-proxy"), true, true));
    assertEquals(
        SupportStatus.FULL,
        proxyBackupPlan.items().stream()
            .filter(item -> "pub-proxy".equals(item.name()))
            .findFirst()
            .orElseThrow()
            .status());
    assertFalse(preflight.migrationPlan().manualActions().contains("repository:pub-hosted"));
  }

  @Test
  void migratesSupportedRepositoriesAndKeepsSourceGroupMembers() {
    FakeBlobStoreDao blobStores = new FakeBlobStoreDao();
    FakeRepositoryDao repositories = new FakeRepositoryDao();
    NexusApiMigrationService service = service(blobStores, repositories);

    ConfigMigrationCounts counts = service.migrateConfig(new NexusInventory(
        List.of(Map.of("name", "default")),
        List.of(
            repository("maven-releases", "maven2", "hosted", Map.of(
                "storage", storage("default"),
                "maven", Map.of("versionPolicy", "release", "layoutPolicy", "strict"))),
            repository("npm-hosted", "npm", "hosted", Map.of("storage", storage("default"))),
            repository("maven-central", "maven2", "proxy", Map.of(
                "storage", storage("default"),
                "proxy", Map.of("remoteUrl", "https://repo1.maven.org/maven2/"))),
            repository("maven-public", "maven2", "group", Map.of(
                "storage", storage("default"),
                "group", Map.of("memberNames", List.of("maven-releases", "maven-central"))))),
        NexusSecurityExport.empty(),
        List.of()), request("https://old-nexus.example/nexus/"));

    RepositoryRecord mavenHosted = repositories.required("maven-releases");
    assertEquals(4, counts.repositories());
    assertEquals(0, counts.unsupportedRepositories());
    assertEquals(1, counts.proxyRepositories());
    assertEquals(1, counts.groupRepositories());
    assertEquals(RepositoryFormat.MAVEN2, mavenHosted.format());
    assertEquals(RepositoryType.HOSTED, mavenHosted.type());
    assertEquals("maven2-hosted", mavenHosted.recipeName());
    assertEquals("RELEASE", mavenHosted.versionPolicy());
    assertEquals("STRICT", mavenHosted.layoutPolicy());
    assertFalse(repositories.findByName("maven-releases-source-proxy").isPresent());
    assertFalse(repositories.findByName("npm-hosted-source-proxy").isPresent());
    assertEquals(
        List.of("maven-releases", "maven-central"),
        repositories.memberNames("maven-public"));
  }

  @Test
  void migratesPubRepositoryConfigurationAndDefaultsProxyRemote() {
    FakeBlobStoreDao blobStores = new FakeBlobStoreDao();
    FakeRepositoryDao repositories = new FakeRepositoryDao();
    NexusApiMigrationService service = service(blobStores, repositories);

    ConfigMigrationCounts counts = service.migrateConfig(new NexusInventory(
        List.of(Map.of("name", "default")),
        List.of(
            repository("pub-hosted", "pub", "hosted", Map.of(
                "storage", Map.of(
                    "blobStoreName", "default",
                    "strictContentTypeValidation", true,
                    "writePolicy", "allow"))),
            repository("pub-proxy", "pub", "proxy", Map.of("storage", storage("default"))),
            repository("pub-group", "pub", "group", Map.of(
                "storage", storage("default"),
                "group", Map.of("memberNames", List.of("pub-hosted", "pub-proxy"))))),
        NexusSecurityExport.empty(),
        List.of()), request("https://old-nexus.example"));

    RepositoryRecord hosted = repositories.required("pub-hosted");
    RepositoryRecord proxy = repositories.required("pub-proxy");
    assertEquals(3, counts.repositories());
    assertEquals(1, counts.proxyRepositories());
    assertEquals(1, counts.groupRepositories());
    assertEquals(RepositoryFormat.PUB, hosted.format());
    assertEquals(RepositoryType.HOSTED, hosted.type());
    assertEquals("pub-hosted", hosted.recipeName());
    assertEquals("ALLOW", hosted.writePolicy());
    assertEquals(RepositoryType.PROXY, proxy.type());
    assertEquals("pub-proxy", proxy.recipeName());
    assertEquals("https://pub.dev/", proxy.proxyRemoteUrl());
    assertEquals("https://pub.dev/", ((Map<?, ?>) proxy.attributes().get("proxy")).get("remoteUrl"));
    assertEquals(List.of("pub-hosted", "pub-proxy"), repositories.memberNames("pub-group"));
  }

  @Test
  void unsupportedHostedRepositoryDoesNotCreateFallbackProxy() {
    FakeBlobStoreDao blobStores = new FakeBlobStoreDao();
    FakeRepositoryDao repositories = new FakeRepositoryDao();
    NexusApiMigrationService service = service(blobStores, repositories);

    ConfigMigrationCounts counts = service.migrateConfig(new NexusInventory(
        List.of(Map.of("name", "default")),
        List.of(
            repository("go-hosted", "go", "hosted", Map.of("storage", storage("default"))),
            repository("go-public", "go", "group", Map.of(
                "storage", storage("default"),
                "group", Map.of("memberNames", List.of("go-hosted"))))),
        NexusSecurityExport.empty(),
        List.of()), request("https://old-nexus.example"));

    assertEquals(1, counts.unsupportedRepositories());
    assertEquals(1, counts.repositories());
    assertEquals(1, counts.groupRepositories());
    assertTrue(repositories.findByName("go-hosted").isEmpty());
    assertTrue(repositories.findByName("go-public").isPresent());
    assertFalse(repositories.findByName("go-hosted-source-proxy").isPresent());
    assertEquals(List.of(), repositories.memberNames("go-public"));
  }

  @Test
  void migratesDockerConnectorPortFromSourceHttpPort() {
    FakeBlobStoreDao blobStores = new FakeBlobStoreDao();
    FakeRepositoryDao repositories = new FakeRepositoryDao();
    NexusApiMigrationService service = service(blobStores, repositories);

    ConfigMigrationCounts counts = service.migrateConfig(new NexusInventory(
        List.of(Map.of("name", "default")),
        List.of(repository("docker-hosted", "docker", "hosted", Map.of(
            "storage", storage("default"),
            "docker", Map.of("httpPort", 18183, "forceBasicAuth", true)))),
        NexusSecurityExport.empty(),
        List.of()), request("https://old-nexus.example"));

    RepositoryRecord record = repositories.required("docker-hosted");
    assertEquals(1, counts.repositories());
    assertEquals("docker-hosted", record.recipeName());
    assertEquals(
        Map.of("connectorEnabled", true, "connectorPort", 18183),
        record.attributes().get("docker"));
  }

  private static NexusApiMigrationService service(BlobStoreDao blobStores, RepositoryDao repositories) {
    return new NexusApiMigrationService(
        new ObjectMapper(),
        blobStores,
        repositories,
        null,
        null,
        null);
  }

  private static NexusMigrationRequest request(String sourceBaseUrl) {
    return new NexusMigrationRequest(
        sourceBaseUrl,
        "admin",
        "secret",
        "3.29.2-02",
        false,
        new NexusMigrationTargetBlobStore("default", "s3", null, null, null, "", Map.of()));
  }

  private static RepositoryDocument repository(
      String name,
      String format,
      String type,
      Map<String, Object> detail) {
    LinkedHashMap<String, Object> source = new LinkedHashMap<>();
    source.put("name", name);
    source.put("format", format);
    source.put("type", type);
    source.put("online", true);
    source.putAll(detail);
    return new RepositoryDocument(
        Map.of("name", name, "format", format, "type", type),
        source);
  }

  private static Map<String, Object> storage(String blobStoreName) {
    return Map.of(
        "blobStoreName", blobStoreName,
        "strictContentTypeValidation", true,
        "writePolicy", "allow_once");
  }

  private static Map<String, Object> datastoreSchema(String format, boolean complete) {
    return datastoreSchema(Map.of(format, complete));
  }

  private static Map<String, Object> datastoreSchema(Map<String, Boolean> formats) {
    Map<String, Object> models = new LinkedHashMap<>();
    formats.forEach((format, complete) -> {
      String prefix = "maven2".equals(format) ? "MAVEN2" : format.toUpperCase(java.util.Locale.ROOT);
      models.put(format, Map.of(
          "prefix", prefix,
          "tablesPresent", true,
          "requiredColumnsPresent", complete,
          "tables", Map.of(
              "contentRepository", prefix + "_CONTENT_REPOSITORY",
              "asset", prefix + "_ASSET",
              "assetBlob", prefix + "_ASSET_BLOB",
              "component", prefix + "_COMPONENT"),
          "columns", Map.of()));
    });
    return Map.of(
        "columns", List.of("ID", "NAME", "RECIPE_NAME", "ATTRIBUTES"),
        "datastoreContentModels", models);
  }

  private static final class FakeBlobStoreDao extends BlobStoreDao {
    private final AtomicLong ids = new AtomicLong(100);
    private final Map<String, BlobStoreRecord> records = new LinkedHashMap<>();

    private FakeBlobStoreDao() {
      super(null, null);
    }

    @Override
    public BlobStoreRecord upsertByName(BlobStoreRecord record) {
      Long id = Optional.ofNullable(records.get(record.name()))
          .map(BlobStoreRecord::id)
          .orElseGet(ids::incrementAndGet);
      BlobStoreRecord stored = new BlobStoreRecord(
          id,
          record.name(),
          record.type(),
          record.endpoint(),
          record.region(),
          record.bucket(),
          record.prefix(),
          record.attributes());
      records.put(stored.name(), stored);
      return stored;
    }

    @Override
    public Optional<BlobStoreRecord> findByName(String name) {
      return Optional.ofNullable(records.get(name));
    }
  }

  private static final class FakeRepositoryDao extends RepositoryDao {
    private final AtomicLong ids = new AtomicLong(200);
    private final Map<String, RepositoryRecord> records = new LinkedHashMap<>();
    private final Map<Long, List<Long>> members = new LinkedHashMap<>();

    private FakeRepositoryDao() {
      super(null, null);
    }

    @Override
    public RepositoryRecord upsertByName(RepositoryRecord record) {
      Long id = Optional.ofNullable(records.get(record.name()))
          .map(RepositoryRecord::id)
          .orElseGet(ids::incrementAndGet);
      RepositoryRecord stored = new RepositoryRecord(
          id,
          record.name(),
          record.format(),
          record.type(),
          record.recipeName(),
          record.online(),
          record.blobStoreId(),
          record.routingRuleId(),
          record.proxyRemoteUrl(),
          record.versionPolicy(),
          record.layoutPolicy(),
          record.writePolicy(),
          record.strictContentTypeValidation(),
          record.attributes());
      records.put(stored.name(), stored);
      return stored;
    }

    @Override
    public Optional<RepositoryRecord> findByName(String name) {
      return Optional.ofNullable(records.get(name));
    }

    @Override
    public void replaceMembers(long groupRepositoryId, List<Long> memberRepositoryIds) {
      members.put(groupRepositoryId, List.copyOf(memberRepositoryIds));
    }

    @Override
    public List<RepositoryRecord> listMembers(long groupRepositoryId) {
      return members.getOrDefault(groupRepositoryId, List.of()).stream()
          .map(this::requiredById)
          .toList();
    }

    private RepositoryRecord required(String name) {
      return findByName(name).orElseThrow();
    }

    private List<String> memberNames(String groupName) {
      return listMembers(required(groupName).id()).stream()
          .map(RepositoryRecord::name)
          .toList();
    }

    private RepositoryRecord requiredById(Long id) {
      return records.values().stream()
          .filter(record -> record.id().equals(id))
          .findFirst()
          .orElseThrow();
    }
  }
}
