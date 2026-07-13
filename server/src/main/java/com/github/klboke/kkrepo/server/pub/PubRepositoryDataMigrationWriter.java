package com.github.klboke.kkrepo.server.pub;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.klboke.kkrepo.core.BlobStorage;
import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.RepositoryDataMigrationAssetRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.RepositoryRecord;
import com.github.klboke.kkrepo.protocol.pub.PubPackageName;
import com.github.klboke.kkrepo.protocol.pub.PubPaths;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntime;
import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;

@Component
public class PubRepositoryDataMigrationWriter {
  private static final String MIGRATION_SOURCE = "nexus-repository-data-migration";
  private static final TypeReference<Map<String, Object>> JSON_MAP = new TypeReference<>() {
  };

  private final PubAssetWriter writer;
  private final ObjectMapper objectMapper;

  PubRepositoryDataMigrationWriter(PubAssetWriter writer, ObjectMapper objectMapper) {
    this.writer = writer;
    this.objectMapper = objectMapper;
  }

  public MigratedAsset write(
      RepositoryRecord repository,
      BlobStorage storage,
      RepositoryDataMigrationAssetRecord source,
      InputStream body,
      String responseContentType,
      boolean validateSize) {
    if (repository.format() != RepositoryFormat.PUB) {
      throw new IllegalArgumentException("Pub migration writer requires a Pub repository");
    }
    RepositoryRuntime runtime = runtime(repository);
    String sourcePath = normalizePath(source.sourcePath());
    if (isArchivePath(sourcePath)) {
      return writeArchive(runtime, repository, storage, source, body, responseContentType, validateSize);
    }
    return writeMetadata(runtime, repository, storage, source, body, sourcePath, responseContentType, validateSize);
  }

  public static boolean isMigratablePubPath(String path) {
    String normalized = normalizePath(path);
    return isArchivePath(normalized) || metadataPackageName(normalized).isPresent();
  }

  private MigratedAsset writeArchive(
      RepositoryRuntime runtime,
      RepositoryRecord repository,
      BlobStorage storage,
      RepositoryDataMigrationAssetRecord source,
      InputStream body,
      String responseContentType,
      boolean validateSize) {
    PubAssetWriter.Stored stored = writer.writeArchive(
        runtime,
        storage,
        requireBlobStore(repository),
        body,
        blankToNull(source.name()),
        blankToNull(source.version()),
        sourceSha256(source).orElse(null),
        migrationAttributes(source),
        remoteAttributes(source, responseContentType, true),
        firstNonBlank(source.sourceCreatedBy(), "nexus-migration"),
        source.sourceCreatedByIp(),
        validateSize ? source.size() : null,
        publishedAt(source),
        true,
        false);
    stored.discardBody();
    return new MigratedAsset(
        stored.asset().componentId(),
        stored.asset().id(),
        stored.blob().id(),
        stored.blob().objectKey());
  }

  private MigratedAsset writeMetadata(
      RepositoryRuntime runtime,
      RepositoryRecord repository,
      BlobStorage storage,
      RepositoryDataMigrationAssetRecord source,
      InputStream body,
      String sourcePath,
      String responseContentType,
      boolean validateSize) {
    String packageName = metadataPackageName(sourcePath)
        .orElseThrow(() -> new IllegalArgumentException("Pub source asset is not migratable: " + source.sourcePath()));
    byte[] bytes = readAll(source, body);
    if (validateSize && source.size() != null && source.size() != bytes.length) {
      throw new PubExceptions.BadUpstreamException("Pub metadata size mismatch for " + source.sourcePath()
          + ": expected " + source.size() + ", actual " + bytes.length);
    }
    validateMetadataPackage(packageName, bytes);
    Map<String, Object> attributes = migrationAttributes(source);
    attributes.put("packageName", packageName);
    attributes.put("sourceMetadataPath", sourcePath);
    PubAssetWriter.Stored stored = writer.writeMetadata(
        runtime,
        storage,
        requireBlobStore(repository),
        PubPaths.metadataPath(packageName),
        bytes,
        attributes,
        remoteAttributes(source, responseContentType, false),
        false);
    stored.discardBody();
    return new MigratedAsset(
        stored.asset().componentId(),
        stored.asset().id(),
        stored.blob().id(),
        stored.blob().objectKey());
  }

  private void validateMetadataPackage(String packageName, byte[] bytes) {
    try {
      Map<String, Object> body = objectMapper.readValue(bytes, JSON_MAP);
      Object name = body.get("name");
      if (name != null && !PubPackageName.require(String.valueOf(name)).equals(packageName)) {
        throw new PubExceptions.BadUpstreamException("Pub metadata package name mismatch for " + packageName);
      }
    } catch (IOException e) {
      throw new PubExceptions.BadUpstreamException("Invalid Pub metadata JSON for " + packageName, e);
    }
  }

  private static byte[] readAll(RepositoryDataMigrationAssetRecord source, InputStream body) {
    try (InputStream input = body) {
      return input.readAllBytes();
    } catch (IOException e) {
      throw new PubExceptions.BadUpstreamException("Failed reading migrated Pub metadata " + source.sourcePath(), e);
    }
  }

  private static RepositoryRuntime runtime(RepositoryRecord repository) {
    return new RepositoryRuntime(
        repository.id(),
        repository.name(),
        repository.format(),
        repository.type(),
        repository.recipeName(),
        repository.online(),
        repository.blobStoreId(),
        repository.writePolicy(),
        repository.versionPolicy(),
        repository.layoutPolicy(),
        repository.strictContentTypeValidation(),
        repository.proxyRemoteUrl(),
        null,
        null,
        null,
        null,
        List.of());
  }

  private static long requireBlobStore(RepositoryRecord repository) {
    if (repository.blobStoreId() == null) {
      throw new IllegalArgumentException("target repository has no blob store: " + repository.name());
    }
    return repository.blobStoreId();
  }

  private static boolean isArchivePath(String path) {
    if (path == null) {
      return false;
    }
    String normalized = normalizePath(path);
    String lower = normalized.toLowerCase(Locale.ROOT);
    return lower.endsWith(".tar.gz")
        && (lower.startsWith("packages/")
            || lower.startsWith("api/archives/")
            || isNexusDatastoreArchivePath(normalized));
  }

  private static boolean isNexusDatastoreArchivePath(String path) {
    String[] parts = normalizePath(path).split("/", -1);
    if (parts.length != 3) {
      return false;
    }
    String packageName = parts[0];
    String version = parts[1];
    String file = parts[2];
    String suffix = ".tar.gz";
    if (!file.endsWith(suffix) || !file.startsWith(packageName + "-")) {
      return false;
    }
    String fileVersion = file.substring(packageName.length() + 1, file.length() - suffix.length());
    return version.equals(fileVersion)
        && PubPackageName.isValid(packageName)
        && com.github.klboke.kkrepo.protocol.pub.PubVersions.isValid(version);
  }

  private static Optional<String> metadataPackageName(String path) {
    String normalized = normalizePath(path);
    if (!normalized.startsWith("api/packages/")) {
      return Optional.empty();
    }
    String suffix = normalized.substring("api/packages/".length());
    if (suffix.isBlank() || suffix.contains("/")) {
      return Optional.empty();
    }
    return PubPackageName.isValid(suffix) ? Optional.of(PubPackageName.require(suffix)) : Optional.empty();
  }

  private static Map<String, Object> migrationAttributes(RepositoryDataMigrationAssetRecord source) {
    LinkedHashMap<String, Object> attributes = new LinkedHashMap<>();
    attributes.put("source", MIGRATION_SOURCE);
    putIfPresent(attributes, "sourcePath", source.sourcePath());
    putIfPresent(attributes, "sourceAssetId", source.sourceAssetId());
    putIfPresent(attributes, "sourceComponentId", source.sourceComponentId());
    putIfPresent(attributes, "sourceBlobRef", source.sourceBlobRef());
    putIfPresent(attributes, "sourceAssetKind", source.assetKind());
    putIfPresent(attributes, "sourceRepositoryType", metadataString(source, "sourceRepositoryType"));
    putIfPresent(attributes, "targetRepositoryType", metadataString(source, "targetRepositoryType"));
    putIfPresent(attributes, "sourceAttributes", metadataValue(source, "attributes"));
    putIfPresent(attributes, "sourceComponentAttributes", metadataValue(source, "componentAttributes"));
    return attributes;
  }

  private static Map<String, String> remoteAttributes(
      RepositoryDataMigrationAssetRecord source,
      String responseContentType,
      boolean archive) {
    LinkedHashMap<String, String> attributes = new LinkedHashMap<>();
    putIfPresent(attributes, "migrationSource", MIGRATION_SOURCE);
    putIfPresent(attributes, "sourceContentType", firstNonBlank(source.contentType(), responseContentType));
    putIfPresent(attributes, "sourceAssetId", source.sourceAssetId());
    putIfPresent(attributes, "sourceBlobRef", source.sourceBlobRef());
    if (archive) {
      sourceSha256(source).ifPresent(value -> attributes.put("remoteArchiveSha256", value));
    }
    return attributes;
  }

  private static Optional<String> sourceSha256(RepositoryDataMigrationAssetRecord source) {
    Object value = findKey(source.metadata(), "sha256");
    String text = value == null ? null : String.valueOf(value).trim().toLowerCase(Locale.ROOT);
    if (text == null || text.length() != 64 || !text.chars().allMatch(PubRepositoryDataMigrationWriter::isHexDigit)) {
      return Optional.empty();
    }
    return Optional.of(text);
  }

  private static Instant publishedAt(RepositoryDataMigrationAssetRecord source) {
    for (Object value : new Object[] {
        findKey(source.metadata(), "publishedAt"),
        findKey(source.metadata(), "published"),
        source.sourceBlobCreatedAt(),
        source.sourceLastUpdatedAt(),
        source.sourceBlobUpdatedAt()}) {
      Instant instant = instant(value);
      if (instant != null) {
        return instant;
      }
    }
    return null;
  }

  private static Object findKey(Object value, String wanted) {
    if (value instanceof Map<?, ?> map) {
      for (Map.Entry<?, ?> entry : map.entrySet()) {
        if (entry.getKey() != null && normalizedKey(entry.getKey()).equals(normalizedKey(wanted))) {
          return entry.getValue();
        }
      }
      for (Object child : map.values()) {
        Object found = findKey(child, wanted);
        if (found != null) {
          return found;
        }
      }
    } else if (value instanceof Iterable<?> iterable) {
      for (Object child : iterable) {
        Object found = findKey(child, wanted);
        if (found != null) {
          return found;
        }
      }
    }
    return null;
  }

  private static Object metadataValue(RepositoryDataMigrationAssetRecord source, String key) {
    return source.metadata() == null ? null : source.metadata().get(key);
  }

  private static String metadataString(RepositoryDataMigrationAssetRecord source, String key) {
    Object value = metadataValue(source, key);
    return value == null ? null : blankToNull(String.valueOf(value));
  }

  private static Instant instant(Object value) {
    if (value == null) {
      return null;
    }
    if (value instanceof Instant instant) {
      return instant;
    }
    try {
      return Instant.parse(String.valueOf(value));
    } catch (RuntimeException e) {
      return null;
    }
  }

  private static String normalizePath(String path) {
    String normalized = path == null ? "" : path.trim();
    while (normalized.startsWith("/")) {
      normalized = normalized.substring(1);
    }
    while (normalized.endsWith("/")) {
      normalized = normalized.substring(0, normalized.length() - 1);
    }
    return normalized;
  }

  private static String normalizedKey(Object key) {
    return String.valueOf(key).replace("_", "").replace("-", "").toLowerCase(Locale.ROOT);
  }

  private static boolean isHexDigit(int ch) {
    return (ch >= '0' && ch <= '9') || (ch >= 'a' && ch <= 'f');
  }

  private static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value.trim();
  }

  private static String firstNonBlank(String first, String second) {
    String normalized = blankToNull(first);
    return normalized == null ? blankToNull(second) : normalized;
  }

  private static void putIfPresent(Map<String, Object> target, String key, Object value) {
    if (value != null) {
      target.put(key, value);
    }
  }

  private static void putIfPresent(Map<String, String> target, String key, String value) {
    if (value != null && !value.isBlank()) {
      target.put(key, value);
    }
  }

  public record MigratedAsset(Long componentId, long assetId, long assetBlobId, String assetBlobObjectKey) {
  }
}
