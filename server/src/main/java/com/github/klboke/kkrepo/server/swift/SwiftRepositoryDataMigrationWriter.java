package com.github.klboke.kkrepo.server.swift;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.core.RepositoryType;
import com.github.klboke.kkrepo.persistence.jdbc.api.AssetDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.SwiftRegistryDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetBlobRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.RepositoryDataMigrationAssetRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.RepositoryRecord;
import com.github.klboke.kkrepo.protocol.swift.SwiftPackageName;
import com.github.klboke.kkrepo.protocol.swift.SwiftPath;
import com.github.klboke.kkrepo.protocol.swift.SwiftPathParser;
import com.github.klboke.kkrepo.protocol.swift.SwiftScope;
import com.github.klboke.kkrepo.protocol.swift.SwiftVersions;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntime;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntimeRegistry;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Restores proven Nexus Swift hosted archives through the normal immutable publish service. */
@Component
public class SwiftRepositoryDataMigrationWriter {
  private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {};
  private static final String CMS_SIGNATURE_FORMAT = "cms-1.0.0";
  private static final SwiftPathParser PATHS = new SwiftPathParser();
  private static final List<String> REGISTRY_METADATA_KEYS = List.of(
      "description",
      "author",
      "repositoryURLs",
      "readmeURL",
      "licenseURL",
      "originalPublicationTime");

  private final SwiftService service;
  private final SwiftRegistryDao registry;
  private final AssetDao assets;
  private final RepositoryRuntimeRegistry runtimes;
  private final ObjectMapper mapper;
  private final long maxArchiveBytes;

  SwiftRepositoryDataMigrationWriter(
      SwiftService service,
      SwiftRegistryDao registry,
      AssetDao assets,
      RepositoryRuntimeRegistry runtimes,
      ObjectMapper mapper) {
    this(service, registry, assets, runtimes, mapper, 1024L * 1024 * 1024);
  }

  @Autowired
  SwiftRepositoryDataMigrationWriter(
      SwiftService service,
      SwiftRegistryDao registry,
      AssetDao assets,
      RepositoryRuntimeRegistry runtimes,
      ObjectMapper mapper,
      @Value("${kkrepo.swift.archive.max-compressed-bytes:1073741824}") long maxArchiveBytes) {
    this.service = service;
    this.registry = registry;
    this.assets = assets;
    this.runtimes = runtimes;
    this.mapper = mapper;
    this.maxArchiveBytes = Math.max(1, maxArchiveBytes);
  }

  public MigratedAsset write(
      RepositoryRecord repository,
      RepositoryDataMigrationAssetRecord source,
      InputStream body,
      boolean validateSize) {
    try (body) {
      return writeOpenBody(repository, source, body, validateSize);
    } catch (IOException e) {
      throw new IllegalStateException(
          "Failed reading Swift migration archive " + source.sourcePath(), e);
    }
  }

  private MigratedAsset writeOpenBody(
      RepositoryRecord repository,
      RepositoryDataMigrationAssetRecord source,
      InputStream body,
      boolean validateSize) throws IOException {
    if (repository.format() != RepositoryFormat.SWIFT
        || repository.type() != RepositoryType.HOSTED) {
      throw new IllegalArgumentException(
          "Swift data migration only supports hosted repositories");
    }
    Coordinates coordinates = coordinates(source);
    String sourceSha256 = requireSourceSha256(source);
    RepositoryRuntime runtime = runtimes.resolveById(repository.id())
        .orElseThrow(() -> new IllegalArgumentException(
            "Swift migration target repository is unavailable: " + repository.name()));
    Optional<SwiftRegistryDao.Release> existing = registry.findRelease(
        runtime.id(), coordinates.scopeLc(), coordinates.nameLc(), coordinates.version());
    if (existing.isPresent()) {
      return verifiedExisting(existing.get(), source, sourceSha256, validateSize);
    }
    byte[] sourceSignature = signature(
        source.metadata(),
        "sourceArchiveSignature",
        SwiftPublishLimits.MAX_SOURCE_ARCHIVE_SIGNATURE_BYTES);
    byte[] metadataSignature = signature(
        source.metadata(),
        "metadataSignature",
        SwiftPublishLimits.MAX_METADATA_SIGNATURE_BYTES);

    Path buffered = null;
    try {
      buffered = Files.createTempFile("kkrepo-swift-migration-", ".zip");
      DigestedArchive archive = copyAndDigest(body, buffered);
      validateSource(source, sourceSha256, archive, validateSize);
      String signatureFormat = sourceSignature == null
          ? null
          : firstNonBlank(
              text(findKey(source.metadata(), "signatureFormat")), CMS_SIGNATURE_FORMAT);
      try (InputStream replay = Files.newInputStream(buffered)) {
        SwiftRegistryDao.Release restored = service.restoreHostedReleaseForMigration(
            runtime,
            coordinates.scope(),
            coordinates.name(),
            coordinates.version(),
            replay,
            metadataJson(source.metadata()),
            sourceSignature,
            metadataSignature,
            signatureFormat,
            publishedAt(source),
            firstNonBlank(source.sourceCreatedBy(), "nexus-migration"),
            source.sourceCreatedByIp());
        if (!archive.sha256().equalsIgnoreCase(restored.archiveSha256())) {
          throw new IllegalStateException(
              "Swift migration checksum changed while restoring " + source.sourcePath());
        }
        return migrated(restored);
      }
    } finally {
      if (buffered != null) {
        try {
          Files.deleteIfExists(buffered);
        } catch (IOException ignored) {
        }
      }
    }
  }

  public static boolean isMigratableSwiftPath(String path) {
    return PATHS.parse(normalize(path)).kind() == SwiftPath.Kind.SOURCE_ARCHIVE;
  }

  private DigestedArchive copyAndDigest(InputStream input, Path target) throws IOException {
    MessageDigest digest = sha256();
    long size = 0;
    try (var output = Files.newOutputStream(target)) {
      byte[] buffer = new byte[64 * 1024];
      for (int read; (read = input.read(buffer)) >= 0;) {
        size += read;
        if (size > maxArchiveBytes) {
          throw new IllegalStateException("Swift migration archive exceeds the configured limit");
        }
        digest.update(buffer, 0, read);
        output.write(buffer, 0, read);
      }
    }
    return new DigestedArchive(size, HexFormat.of().formatHex(digest.digest()));
  }

  private static void validateSource(
      RepositoryDataMigrationAssetRecord source,
      String expectedSha256,
      DigestedArchive archive,
      boolean validateSize) {
    if (validateSize && source.size() != null && source.size() >= 0
        && source.size() != archive.size()) {
      throw new IllegalStateException(
          "Swift migration size mismatch for " + source.sourcePath()
              + ": expected " + source.size() + ", actual " + archive.size());
    }
    if (!expectedSha256.equalsIgnoreCase(archive.sha256())) {
      throw new IllegalStateException(
          "Swift migration checksum mismatch for " + source.sourcePath());
    }
  }

  private MigratedAsset verifiedExisting(
      SwiftRegistryDao.Release release,
      RepositoryDataMigrationAssetRecord source,
      String expectedSha256,
      boolean validateSize) {
    AssetRecord asset = assets.findAssetById(release.archiveAssetId())
        .orElseThrow(() -> new IllegalStateException("Migrated Swift archive asset is missing"));
    AssetBlobRecord blob = asset.assetBlobId() == null
        ? null
        : assets.findBlobById(asset.assetBlobId()).orElse(null);
    if (blob == null) {
      throw new IllegalStateException("Migrated Swift archive blob is missing");
    }
    if (validateSize && source.size() != null && source.size() >= 0
        && source.size() != blob.size()) {
      throw new IllegalStateException(
          "Existing Swift migration size does not match " + source.sourcePath());
    }
    if (!expectedSha256.equalsIgnoreCase(release.archiveSha256())) {
      throw new IllegalStateException(
          "Existing Swift migration checksum does not match " + source.sourcePath());
    }
    return new MigratedAsset(
        release.componentId(), asset.id(), blob.id(), blob.objectKey());
  }

  private MigratedAsset migrated(SwiftRegistryDao.Release release) {
    AssetRecord asset = assets.findAssetById(release.archiveAssetId())
        .orElseThrow(() -> new IllegalStateException("Restored Swift archive asset is missing"));
    AssetBlobRecord blob = asset.assetBlobId() == null
        ? null
        : assets.findBlobById(asset.assetBlobId()).orElse(null);
    if (blob == null) {
      throw new IllegalStateException("Restored Swift archive blob is missing");
    }
    return new MigratedAsset(release.componentId(), asset.id(), blob.id(), blob.objectKey());
  }

  private static Coordinates coordinates(RepositoryDataMigrationAssetRecord source) {
    SwiftPath path = PATHS.parse(normalize(source.sourcePath()));
    if (path.kind() != SwiftPath.Kind.SOURCE_ARCHIVE) {
      throw new IllegalArgumentException(
          "Invalid Swift migration archive path: " + source.sourcePath());
    }
    String scope = firstNonBlank(source.namespace(), path.scope());
    String name = firstNonBlank(source.name(), path.name());
    String version = firstNonBlank(source.version(), path.version());
    try {
      SwiftScope.require(scope);
      SwiftPackageName.require(name);
      SwiftVersions.require(version);
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException(
          "Invalid Swift migration coordinate for " + source.sourcePath(), e);
    }
    if (!path.scope().equalsIgnoreCase(scope)
        || !path.name().equalsIgnoreCase(name)
        || !path.version().equals(version)) {
      throw new IllegalArgumentException(
          "Swift migration path and component coordinate disagree: " + source.sourcePath());
    }
    return new Coordinates(
        scope,
        name,
        version,
        SwiftScope.key(scope),
        SwiftPackageName.key(name));
  }

  private String metadataJson(Map<String, Object> sourceMetadata) {
    Object candidate = findKey(sourceMetadata, "originalMetadata");
    if (candidate == null) {
      candidate = findKey(sourceMetadata, "releaseMetadata");
    }
    if (candidate == null) {
      candidate = inferredRegistryMetadata(sourceMetadata);
    }
    if (candidate == null || (candidate instanceof Map<?, ?> map && map.isEmpty())) {
      return "{}";
    }
    if (candidate instanceof String json) {
      try {
        Map<String, Object> parsed = mapper.readValue(json, MAP);
        return mapper.writeValueAsString(parsed);
      } catch (JsonProcessingException e) {
        throw new IllegalArgumentException("Invalid Nexus Swift release metadata", e);
      }
    }
    if (candidate instanceof Map<?, ?> map) {
      try {
        return mapper.writeValueAsString(map);
      } catch (JsonProcessingException e) {
        throw new IllegalArgumentException("Invalid Nexus Swift release metadata", e);
      }
    }
    throw new IllegalArgumentException("Invalid Nexus Swift release metadata");
  }

  private static Map<String, Object> inferredRegistryMetadata(
      Map<String, Object> sourceMetadata) {
    if (sourceMetadata == null || sourceMetadata.isEmpty()) {
      return Map.of();
    }
    Map<String, Object> direct = directRegistryMetadata(sourceMetadata);
    if (!direct.isEmpty()) {
      return direct;
    }

    LinkedHashMap<String, Object> inferred = new LinkedHashMap<>();
    mergeMissing(inferred, findRegistryMetadata(sourceMetadata.get("attributes")));
    mergeMissing(inferred, findRegistryMetadata(sourceMetadata.get("componentAttributes")));
    return inferred;
  }

  private static Map<String, Object> findRegistryMetadata(Object value) {
    if (!(value instanceof Map<?, ?> map)) {
      return Map.of();
    }
    Map<String, Object> direct = directRegistryMetadata(map);
    if (!direct.isEmpty()) {
      return direct;
    }
    for (Object child : map.values()) {
      Map<String, Object> nested = findRegistryMetadata(child);
      if (!nested.isEmpty()) {
        return nested;
      }
    }
    return Map.of();
  }

  private static Map<String, Object> directRegistryMetadata(Map<?, ?> source) {
    LinkedHashMap<String, Object> result = new LinkedHashMap<>();
    for (String canonicalKey : REGISTRY_METADATA_KEYS) {
      for (Map.Entry<?, ?> entry : source.entrySet()) {
        if (entry.getKey() != null
            && normalizedKey(entry.getKey()).equals(normalizedKey(canonicalKey))) {
          result.put(canonicalKey, entry.getValue());
          break;
        }
      }
    }
    return result;
  }

  private static void mergeMissing(
      Map<String, Object> target,
      Map<String, Object> source) {
    source.forEach(target::putIfAbsent);
  }

  private static byte[] signature(Map<String, Object> metadata, String key, int limit) {
    Object value = findKey(metadata, key);
    if (!(value instanceof String encoded) || encoded.isBlank()) {
      return null;
    }
    try {
      byte[] decoded = Base64.getDecoder().decode(encoded.trim());
      if (decoded.length == 0 || decoded.length > limit) {
        throw new IllegalArgumentException("Nexus Swift " + key + " exceeds the size limit");
      }
      return decoded;
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException("Invalid Nexus Swift " + key, e);
    }
  }

  private static String requireSourceSha256(RepositoryDataMigrationAssetRecord source) {
    Map<String, Object> metadata = source.metadata();
    String value = text(findKey(metadata, "sha256"));
    if (value == null) {
      value = text(findKey(metadata, "checksum"));
    }
    String normalized = value == null ? null : value.trim().toLowerCase(Locale.ROOT);
    if (normalized == null
        || normalized.length() != 64
        || !normalized.chars().allMatch(SwiftRepositoryDataMigrationWriter::isHex)) {
      throw new IllegalStateException(
          "Swift migration requires a valid source SHA-256 for " + source.sourcePath());
    }
    return normalized;
  }

  private static Instant publishedAt(RepositoryDataMigrationAssetRecord source) {
    for (Object value : new Object[] {
        findKey(source.metadata(), "publishedAt"),
        findKey(source.metadata(), "originalPublicationTime"),
        source.sourceBlobCreatedAt(),
        source.sourceLastUpdatedAt(),
        source.sourceBlobUpdatedAt()}) {
      Instant parsed = instant(value);
      if (parsed != null) {
        return parsed;
      }
    }
    return Instant.now();
  }

  private static Object findKey(Object value, String wanted) {
    if (value instanceof Map<?, ?> map) {
      for (Map.Entry<?, ?> entry : map.entrySet()) {
        if (entry.getKey() != null
            && normalizedKey(entry.getKey()).equals(normalizedKey(wanted))) {
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

  private static Instant instant(Object value) {
    if (value instanceof Instant timestamp) {
      return timestamp;
    }
    if (value == null) {
      return null;
    }
    try {
      return Instant.parse(value.toString());
    } catch (RuntimeException ignored) {
      return null;
    }
  }

  private static MessageDigest sha256() {
    try {
      return MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is unavailable", e);
    }
  }

  private static String normalize(String path) {
    String normalized = path == null ? "" : path.trim();
    while (normalized.startsWith("/")) {
      normalized = normalized.substring(1);
    }
    return normalized;
  }

  private static String normalizedKey(Object value) {
    return String.valueOf(value).replace("_", "").replace("-", "")
        .toLowerCase(Locale.ROOT);
  }

  private static String firstNonBlank(String first, String second) {
    return first != null && !first.isBlank() ? first.trim()
        : second == null || second.isBlank() ? null : second.trim();
  }

  private static String text(Object value) {
    return value == null ? null : value.toString();
  }

  private static boolean isHex(int character) {
    return (character >= '0' && character <= '9')
        || (character >= 'a' && character <= 'f');
  }

  public record MigratedAsset(
      long componentId, long assetId, long assetBlobId, String assetBlobObjectKey) {}

  private record Coordinates(
      String scope, String name, String version, String scopeLc, String nameLc) {}

  private record DigestedArchive(long size, String sha256) {}
}
