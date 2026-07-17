package com.github.klboke.kkrepo.migration.nexus;

import com.github.klboke.kkrepo.migration.nexus.NexusRestClient.NexusInventory;
import com.github.klboke.kkrepo.migration.nexus.NexusRestClient.RepositoryDocument;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

public record NexusSourceProfile(
    String nexusVersion,
    Instant probedAt,
    ScriptApiProfile scriptApi,
    MetadataEngine metadataEngine,
    RepositoryModel repositoryModel,
    SecurityModel securityModel,
    BlobModel blobModel,
    List<RepositoryCapability> repositories,
    Map<String, FormatCapability> formatCapabilities,
    List<String> warnings) {

  // Nexus Swift datastore layouts are verified for 3.92.x through 3.94.x.
  // Newer minors must be fingerprinted deliberately instead of being accepted optimistically.
  private static final int SWIFT_MIGRATION_MIN_MINOR = 92;
  private static final int SWIFT_MIGRATION_MAX_MINOR = 94;

  public NexusSourceProfile {
    scriptApi = scriptApi == null ? ScriptApiProfile.unknown() : scriptApi;
    metadataEngine = metadataEngine == null ? MetadataEngine.UNKNOWN : metadataEngine;
    repositoryModel = repositoryModel == null ? RepositoryModel.UNKNOWN : repositoryModel;
    securityModel = securityModel == null ? SecurityModel.UNKNOWN : securityModel;
    blobModel = blobModel == null ? BlobModel.unknown() : blobModel;
    repositories = repositories == null ? List.of() : List.copyOf(repositories);
    formatCapabilities = formatCapabilities == null
        ? Map.of()
        : Map.copyOf(new TreeMap<>(formatCapabilities));
    warnings = warnings == null ? List.of() : List.copyOf(warnings);
  }

  public static NexusSourceProfile fromInventory(
      NexusInventory inventory,
      String requestedVersion) {
    NexusInventory source = inventory == null
        ? new NexusInventory(List.of(), List.of(), null, List.of())
        : inventory;
    NexusRestClient.SourceProbe probe = source.probe();
    String version = firstNonBlank(
        probe == null ? null : probe.nexusVersion(),
        requestedVersion,
        "unknown");
    MetadataEngine metadataEngine = metadataEngine(probe);
    RepositoryModel repositoryModel = repositoryModel(metadataEngine, source.repositories());
    List<RepositoryCapability> repositories = repositoryCapabilities(source.repositories());
    Map<String, ContentModelFingerprint> datastoreContentModels = contentModelFingerprints(probe);
    return new NexusSourceProfile(
        version,
        Instant.now(),
        scriptApiProfile(probe, source.warnings()),
        metadataEngine,
        repositoryModel,
        securityModel(metadataEngine, source.warnings()),
        blobModel(source.blobStores()),
        repositories,
        formatCapabilities(
            repositories,
            metadataEngine,
            probe == null ? null : probe.nexusVersion(),
            datastoreContentModels),
        warnings(source.warnings(), probe));
  }

  private static ScriptApiProfile scriptApiProfile(
      NexusRestClient.SourceProbe probe,
      List<String> inventoryWarnings) {
    boolean scriptWarning = inventoryWarnings != null && inventoryWarnings.stream()
        .anyMatch(value -> contains(value, "script API"));
    if (probe == null) {
      return new ScriptApiProfile(
          !scriptWarning,
          !scriptWarning,
          !scriptWarning,
          "text/plain",
          scriptWarning ? "security_export_failed" : "not_probed");
    }
    return new ScriptApiProfile(
        probe.scriptApiCreatable(),
        probe.scriptApiRunnable(),
        probe.scriptApiDeleted(),
        firstNonBlank(probe.scriptRunContentType(), "text/plain"),
        firstNonBlank(probe.scriptApiStatus(), "probed"));
  }

  private static MetadataEngine metadataEngine(NexusRestClient.SourceProbe probe) {
    if (probe == null) {
      return MetadataEngine.ORIENTDB;
    }
    String engine = lower(probe.metadataEngine());
    String product = lower(probe.jdbcProduct());
    String url = lower(probe.jdbcUrl());
    if (contains(engine, "orient")) {
      return MetadataEngine.ORIENTDB;
    }
    if (contains(engine, "postgres") || contains(product, "postgres") || contains(url, "postgres")) {
      return MetadataEngine.DATASTORE_POSTGRESQL;
    }
    if (contains(engine, "h2") || contains(product, "h2") || contains(url, "jdbc:h2")) {
      return MetadataEngine.DATASTORE_H2;
    }
    if (contains(engine, "datastore")) {
      return MetadataEngine.DATASTORE_UNKNOWN;
    }
    return MetadataEngine.UNKNOWN;
  }

  private static RepositoryModel repositoryModel(
      MetadataEngine metadataEngine,
      List<RepositoryDocument> repositories) {
    if (metadataEngine == MetadataEngine.ORIENTDB) {
      return RepositoryModel.ORIENT_BUCKET_ASSET;
    }
    if (metadataEngine == MetadataEngine.DATASTORE_H2
        || metadataEngine == MetadataEngine.DATASTORE_POSTGRESQL) {
      return RepositoryModel.DATASTORE_CONTENT;
    }
    boolean hasRecipeName = repositories != null && repositories.stream()
        .anyMatch(document -> string(value(document, "recipeName")) != null
            || string(value(document, "recipe")) != null);
    return hasRecipeName ? RepositoryModel.REST_RECIPE : RepositoryModel.UNKNOWN;
  }

  private static SecurityModel securityModel(
      MetadataEngine metadataEngine,
      List<String> warnings) {
    boolean scriptWarning = warnings != null && warnings.stream()
        .anyMatch(value -> contains(value, "password hashes") || contains(value, "API keys"));
    if (metadataEngine == MetadataEngine.ORIENTDB) {
      return scriptWarning ? SecurityModel.REST_WITH_MANUAL_SECRETS : SecurityModel.ORIENT_SECURITY;
    }
    if (metadataEngine == MetadataEngine.DATASTORE_H2
        || metadataEngine == MetadataEngine.DATASTORE_POSTGRESQL) {
      return scriptWarning ? SecurityModel.REST_WITH_MANUAL_SECRETS : SecurityModel.DATASTORE_SECURITY;
    }
    return SecurityModel.UNKNOWN;
  }

  private static BlobModel blobModel(List<Map<String, Object>> blobStores) {
    List<String> types = blobStores == null
        ? List.of()
        : blobStores.stream()
            .map(source -> firstNonBlank(string(source.get("type")), string(source.get("blobStoreType")), "unknown"))
            .map(value -> value.toLowerCase(Locale.ROOT))
            .distinct()
            .sorted()
            .toList();
    return new BlobModel(
        types,
        "repository-http",
        true,
        "checksum fields are validated per format when the exporter exposes them");
  }

  private static List<RepositoryCapability> repositoryCapabilities(List<RepositoryDocument> repositories) {
    if (repositories == null || repositories.isEmpty()) {
      return List.of();
    }
    return repositories.stream()
        .map(document -> new RepositoryCapability(
            string(value(document, "name")),
            string(value(document, "format")),
            string(value(document, "type")),
            firstNonBlank(
                string(value(document, "recipeName")),
                recipeName(string(value(document, "format")), string(value(document, "type")))),
            bool(value(document, "online"), true),
            sourceBlobStoreName(document),
            attributesFingerprint(document)))
        .sorted(Comparator
            .comparing(RepositoryCapability::name, Comparator.nullsLast(String::compareTo))
            .thenComparing(RepositoryCapability::format, Comparator.nullsLast(String::compareTo))
            .thenComparing(RepositoryCapability::type, Comparator.nullsLast(String::compareTo)))
        .toList();
  }

  private static Map<String, FormatCapability> formatCapabilities(
      List<RepositoryCapability> repositories,
      MetadataEngine metadataEngine,
      String probedNexusVersion,
      Map<String, ContentModelFingerprint> datastoreContentModels) {
    LinkedHashMap<String, FormatCapability> capabilities = new LinkedHashMap<>();
    for (RepositoryCapability repository : repositories) {
      String format = lower(repository.format());
      if (format == null) {
        continue;
      }
      FormatCapability current = capabilities.get(format);
      List<String> recipes = new ArrayList<>(current == null ? List.of() : current.recipes());
      if (repository.recipe() != null && !recipes.contains(repository.recipe())) {
        recipes.add(repository.recipe());
        recipes.sort(String::compareTo);
      }
      boolean config = NexusRepositorySupport.supportedRecipe(repository.format(), repository.type());
      ContentModelFingerprint contentModel = datastoreContentModels.get(format);
      boolean datastoreContent = contentModel != null
          && contentModel.supported()
          && (!"swift".equals(format)
              || swiftContentModelSupported(probedNexusVersion, contentModel));
      boolean content = config
          && ((metadataEngine == MetadataEngine.ORIENTDB
                  && !"cargo".equals(format) && !"pub".equals(format) && !"swift".equals(format))
              || ((metadataEngine == MetadataEngine.DATASTORE_H2
                  || metadataEngine == MetadataEngine.DATASTORE_POSTGRESQL)
                  && datastoreContent));
      capabilities.put(format, new FormatCapability(
          format,
          recipes,
          config,
          content,
          content ? contentEvidence(metadataEngine, contentModel)
              : config ? contentBlockedEvidence(
                  format, metadataEngine, probedNexusVersion, contentModel)
              : "unsupported-format",
          contentModel));
    }
    return Map.copyOf(capabilities);
  }

  private static List<String> warnings(
      List<String> inventoryWarnings,
      NexusRestClient.SourceProbe probe) {
    ArrayList<String> warnings = new ArrayList<>(inventoryWarnings == null ? List.of() : inventoryWarnings);
    if (probe == null) {
      warnings.add("Source profile uses legacy defaults because the source probe did not return metadata.");
    }
    return List.copyOf(warnings.stream().distinct().toList());
  }

  private static String contentEvidence(
      MetadataEngine metadataEngine,
      ContentModelFingerprint contentModel) {
    if (metadataEngine == MetadataEngine.ORIENTDB) {
      return "orientdb-exporter";
    }
    return contentModel == null ? "datastore-content-exporter" : "datastore-content-exporter:" + contentModel.prefix();
  }

  private static String contentBlockedEvidence(
      String format,
      MetadataEngine metadataEngine,
      String probedNexusVersion,
      ContentModelFingerprint contentModel) {
    if (metadataEngine == MetadataEngine.DATASTORE_H2
        || metadataEngine == MetadataEngine.DATASTORE_POSTGRESQL) {
      if (contentModel == null) {
        return "datastore-content-schema-missing";
      }
      if ("swift".equals(format)
          && !knownSwiftMigrationVersion(probedNexusVersion)) {
        return "swift-source-version-unverified";
      }
      if ("swift".equals(format) && !swiftFormatShapeSupported(contentModel.formatShape())) {
        return "swift-content-shape-incomplete";
      }
      return "datastore-content-schema-incomplete";
    }
    return "configuration-only";
  }

  private static Map<String, ContentModelFingerprint> contentModelFingerprints(NexusRestClient.SourceProbe probe) {
    if (probe == null || probe.repositorySchema() == null) {
      return Map.of();
    }
    Object models = probe.repositorySchema().get("datastoreContentModels");
    if (!(models instanceof Map<?, ?> rawModels)) {
      return Map.of();
    }
    LinkedHashMap<String, ContentModelFingerprint> fingerprints = new LinkedHashMap<>();
    for (Map.Entry<?, ?> entry : rawModels.entrySet()) {
      String format = lower(string(entry.getKey()));
      if (format == null || !(entry.getValue() instanceof Map<?, ?> rawModel)) {
        continue;
      }
      Map<String, Object> model = objectMap(rawModel);
      ContentModelFingerprint fingerprint = new ContentModelFingerprint(
          firstNonBlank(string(model.get("prefix")), format.toUpperCase(Locale.ROOT)),
          bool(model.get("tablesPresent"), false),
          bool(model.get("requiredColumnsPresent"), false),
          objectMapValue(model.get("tables")),
          objectMapValue(model.get("columns")),
          objectMapValue(model.get("formatShape")));
      fingerprints.put(format, fingerprint);
    }
    return Map.copyOf(fingerprints);
  }

  private static boolean swiftContentModelSupported(
      String probedNexusVersion,
      ContentModelFingerprint contentModel) {
    return knownSwiftMigrationVersion(probedNexusVersion)
        && swiftFormatShapeSupported(contentModel.formatShape());
  }

  private static boolean knownSwiftMigrationVersion(String version) {
    String normalized = string(version);
    if (normalized == null) {
      return false;
    }
    String core = normalized.split("[-+]", 2)[0];
    String[] parts = core.split("\\.");
    if (parts.length < 3) {
      return false;
    }
    try {
      int major = Integer.parseInt(parts[0]);
      int minor = Integer.parseInt(parts[1]);
      Integer.parseInt(parts[2]);
      return major == 3
          && minor >= SWIFT_MIGRATION_MIN_MINOR
          && minor <= SWIFT_MIGRATION_MAX_MINOR;
    } catch (NumberFormatException ignored) {
      return false;
    }
  }

  private static boolean swiftFormatShapeSupported(Map<String, Object> shape) {
    return shape != null
        && bool(shape.get("archiveAssetPath"), false)
        && bool(shape.get("manifestShape"), false)
        && bool(shape.get("swiftAssetAttributes"), false)
        && bool(shape.get("signatureAttributes"), false)
        && bool(shape.get("sha256Checksum"), false);
  }

  private static Map<String, Object> attributesFingerprint(RepositoryDocument document) {
    LinkedHashMap<String, Object> fingerprint = new LinkedHashMap<>();
    putIfPresent(fingerprint, "storage", value(document, "storage"));
    putIfPresent(fingerprint, "maven", value(document, "maven"));
    putIfPresent(fingerprint, "proxy", value(document, "proxy"));
    putIfPresent(fingerprint, "group", value(document, "group"));
    putIfPresent(fingerprint, "attributes", value(document, "attributes"));
    return Map.copyOf(fingerprint);
  }

  private static Object value(RepositoryDocument document, String key) {
    Object detailValue = document.detail().get(key);
    return detailValue == null ? document.summary().get(key) : detailValue;
  }

  private static String sourceBlobStoreName(RepositoryDocument document) {
    Object storage = value(document, "storage");
    if (storage instanceof Map<?, ?> map) {
      String name = string(map.get("blobStoreName"));
      if (name != null) {
        return name;
      }
    }
    Object attributes = value(document, "attributes");
    if (attributes instanceof Map<?, ?> attrs) {
      Object storageAttributes = attrs.get("storage");
      if (storageAttributes instanceof Map<?, ?> storageMap) {
        String name = string(storageMap.get("blobStoreName"));
        if (name != null) {
          return name;
        }
      }
    }
    return "default";
  }

  private static String recipeName(String format, String type) {
    return NexusRepositorySupport.recipe(format, type)
        .map(com.github.klboke.kkrepo.core.RepositoryRecipe::name)
        .orElseGet(() -> {
          String normalizedFormat = lower(format);
          String normalizedType = lower(type);
          if (normalizedFormat == null || normalizedType == null) {
            return null;
          }
          return normalizedFormat + "-" + normalizedType;
        });
  }

  private static boolean bool(Object value, boolean fallback) {
    if (value instanceof Boolean bool) {
      return bool;
    }
    String text = string(value);
    return text == null ? fallback : Boolean.parseBoolean(text);
  }

  private static void putIfPresent(Map<String, Object> target, String key, Object value) {
    if (value != null) {
      target.put(key, value);
    }
  }

  private static Map<String, Object> objectMapValue(Object value) {
    if (!(value instanceof Map<?, ?> map)) {
      return Map.of();
    }
    return objectMap(map);
  }

  private static Map<String, Object> objectMap(Map<?, ?> map) {
    if (map == null || map.isEmpty()) {
      return Map.of();
    }
    LinkedHashMap<String, Object> values = new LinkedHashMap<>();
    for (Map.Entry<?, ?> entry : map.entrySet()) {
      if (entry.getKey() != null) {
        values.put(String.valueOf(entry.getKey()), entry.getValue());
      }
    }
    return Map.copyOf(values);
  }

  private static boolean contains(String value, String token) {
    return value != null
        && token != null
        && value.toLowerCase(Locale.ROOT).contains(token.toLowerCase(Locale.ROOT));
  }

  private static String firstNonBlank(String... values) {
    for (String value : values) {
      String normalized = string(value);
      if (normalized != null) {
        return normalized;
      }
    }
    return null;
  }

  private static String lower(String value) {
    String normalized = string(value);
    return normalized == null ? null : normalized.toLowerCase(Locale.ROOT);
  }

  private static String string(Object value) {
    if (value == null) {
      return null;
    }
    String text = String.valueOf(value).trim();
    return text.isEmpty() ? null : text;
  }

  public enum MetadataEngine {
    ORIENTDB,
    DATASTORE_H2,
    DATASTORE_POSTGRESQL,
    DATASTORE_UNKNOWN,
    UNKNOWN
  }

  public enum RepositoryModel {
    ORIENT_BUCKET_ASSET,
    DATASTORE_CONTENT,
    REST_RECIPE,
    UNKNOWN
  }

  public enum SecurityModel {
    ORIENT_SECURITY,
    DATASTORE_SECURITY,
    REST_WITH_MANUAL_SECRETS,
    UNKNOWN
  }

  public record ScriptApiProfile(
      boolean creatable,
      boolean runnable,
      boolean deletedAfterProbe,
      String runContentType,
      String status) {

    static ScriptApiProfile unknown() {
      return new ScriptApiProfile(false, false, false, "text/plain", "unknown");
    }
  }

  public record BlobModel(
      List<String> sourceTypes,
      String readMode,
      boolean repositoryHttpFallback,
      String checksumModel) {

    public BlobModel {
      sourceTypes = sourceTypes == null ? List.of() : List.copyOf(sourceTypes);
    }

    static BlobModel unknown() {
      return new BlobModel(List.of(), "unknown", false, "unknown");
    }
  }

  public record RepositoryCapability(
      String name,
      String format,
      String type,
      String recipe,
      boolean online,
      String blobStoreName,
      Map<String, Object> attributesFingerprint) {

    public RepositoryCapability {
      attributesFingerprint = attributesFingerprint == null ? Map.of() : Map.copyOf(attributesFingerprint);
    }
  }

  public record FormatCapability(
      String format,
      List<String> recipes,
      boolean configurationMigration,
      boolean contentMigration,
      String evidence,
      ContentModelFingerprint contentModel) {

    public FormatCapability {
      recipes = recipes == null ? List.of() : List.copyOf(recipes);
    }
  }

  public record ContentModelFingerprint(
      String prefix,
      boolean tablesPresent,
      boolean requiredColumnsPresent,
      Map<String, Object> tables,
      Map<String, Object> columns,
      Map<String, Object> formatShape) {

    public ContentModelFingerprint(
        String prefix,
        boolean tablesPresent,
        boolean requiredColumnsPresent,
        Map<String, Object> tables,
        Map<String, Object> columns) {
      this(prefix, tablesPresent, requiredColumnsPresent, tables, columns, Map.of());
    }

    public ContentModelFingerprint {
      tables = tables == null ? Map.of() : Map.copyOf(tables);
      columns = columns == null ? Map.of() : Map.copyOf(columns);
      formatShape = formatShape == null ? Map.of() : Map.copyOf(formatShape);
    }

    public boolean supported() {
      return tablesPresent && requiredColumnsPresent;
    }
  }
}
