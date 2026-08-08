package com.github.klboke.kkrepo.server.conda;

import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.core.RepositoryType;
import com.github.klboke.kkrepo.persistence.jdbc.api.AssetDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.CondaRegistryDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetBlobRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.RepositoryDataMigrationAssetRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.RepositoryRecord;
import com.github.klboke.kkrepo.protocol.conda.CondaPath;
import com.github.klboke.kkrepo.protocol.conda.CondaPathParser;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntime;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntimeRegistry;
import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** Replays verified Nexus hosted packages through the normal Conda archive importer. */
@Component
public class CondaRepositoryDataMigrationWriter {
  private static final CondaPathParser PATHS = new CondaPathParser();

  private final CondaArchiveInspector inspector;
  private final CondaService service;
  private final RepositoryRuntimeRegistry runtimes;
  private final AssetDao assets;
  private final CondaPublishLimiter publications;

  @Autowired
  CondaRepositoryDataMigrationWriter(
      CondaArchiveInspector inspector,
      CondaService service,
      RepositoryRuntimeRegistry runtimes,
      AssetDao assets,
      CondaPublishLimiter publications) {
    this.inspector = inspector;
    this.service = service;
    this.runtimes = runtimes;
    this.assets = assets;
    this.publications = publications;
  }

  CondaRepositoryDataMigrationWriter(
      CondaArchiveInspector inspector,
      CondaService service,
      RepositoryRuntimeRegistry runtimes,
      AssetDao assets) {
    this(inspector, service, runtimes, assets, new CondaPublishLimiter());
  }

  public MigratedAsset write(
      RepositoryRecord repository,
      RepositoryDataMigrationAssetRecord source,
      InputStream body,
      String responseContentType,
      boolean validateSize) {
    return publications.execute(() -> writeWithinCapacity(
        repository, source, body, responseContentType, validateSize));
  }

  private MigratedAsset writeWithinCapacity(
      RepositoryRecord repository,
      RepositoryDataMigrationAssetRecord source,
      InputStream body,
      String responseContentType,
      boolean validateSize) {
    if (repository.format() != RepositoryFormat.CONDA
        || repository.type() != RepositoryType.HOSTED) {
      closeQuietly(body);
      throw new IllegalArgumentException(
          "Conda data migration only supports hosted package archives");
    }
    CondaPath path = packagePath(source.sourcePath());
    RepositoryRuntime runtime = runtimes.resolveById(repository.id())
        .orElseThrow(() -> new IllegalArgumentException(
            "Conda migration target repository is unavailable: " + repository.name()));
    CondaArchiveInspector.InspectedPackage inspected = null;
    try (body) {
      inspected = inspector.inspect(body, path.filename(), path.subdir());
      validateSource(source, inspected, validateSize);
      CondaRegistryDao.PackageRecord restored = service.restoreHostedPackageForMigration(
          runtime,
          path,
          inspected,
          firstNonBlank(responseContentType, source.contentType()),
          firstNonBlank(source.sourceCreatedBy(), "nexus-migration"),
          source.sourceCreatedByIp(),
          publishedAt(source));
      return migrated(restored);
    } catch (IOException e) {
      throw new IllegalStateException(
          "Failed reading Conda migration package " + source.sourcePath(), e);
    } finally {
      if (inspected != null) CondaArchiveInspector.delete(inspected.file());
    }
  }

  public static boolean isMigratableCondaPath(String path) {
    try {
      packagePath(path);
      return true;
    } catch (RuntimeException ignored) {
      return false;
    }
  }

  private static CondaPath packagePath(String rawPath) {
    CondaPath path = PATHS.parse(normalize(rawPath));
    if (!path.packageFile()) {
      throw new IllegalArgumentException(
          "Unsupported Nexus Conda migration asset: " + rawPath);
    }
    return path;
  }

  private static void validateSource(
      RepositoryDataMigrationAssetRecord source,
      CondaArchiveInspector.InspectedPackage inspected,
      boolean validateSize) {
    if (validateSize && source.size() != null && source.size() >= 0
        && source.size() != inspected.size()) {
      throw new IllegalStateException(
          "Conda migration size mismatch for " + source.sourcePath());
    }
    String expectedSha256 = sourceSha256(source.metadata());
    if (expectedSha256 == null) {
      throw new IllegalStateException(
          "Conda migration requires a valid source SHA-256 for " + source.sourcePath());
    }
    if (!expectedSha256.equalsIgnoreCase(inspected.sha256())) {
      throw new IllegalStateException(
          "Conda migration checksum mismatch for " + source.sourcePath());
    }
    if (source.name() != null && !source.name().equals(inspected.name())) {
      throw new IllegalStateException("Conda migration package name disagrees with the archive");
    }
    if (source.version() != null && !source.version().equals(inspected.version())) {
      throw new IllegalStateException("Conda migration package version disagrees with the archive");
    }
  }

  private MigratedAsset migrated(CondaRegistryDao.PackageRecord record) {
    if (record.assetId() == null) {
      throw new IllegalStateException("Restored Conda package has no asset binding");
    }
    AssetRecord asset = assets.findAssetById(record.assetId())
        .orElseThrow(() -> new IllegalStateException("Restored Conda package asset is missing"));
    AssetBlobRecord blob = asset.assetBlobId() == null
        ? null
        : assets.findBlobById(asset.assetBlobId()).orElse(null);
    if (blob == null || !record.sha256().equalsIgnoreCase(blob.sha256())) {
      throw new IllegalStateException("Restored Conda package blob checksum is invalid");
    }
    return new MigratedAsset(record.componentId(), asset.id(), blob.id(), blob.objectKey());
  }

  private static String sourceSha256(Object value) {
    Object found = findKey(value, "sha256");
    if (found == null) found = findKey(value, "checksum.sha256");
    String text = found == null ? null : found.toString().trim().toLowerCase(Locale.ROOT);
    return text != null && text.matches("[0-9a-f]{64}") ? text : null;
  }

  private static Object findKey(Object value, String wanted) {
    if (value instanceof Map<?, ?> map) {
      String normalizedWanted = normalizedKey(wanted);
      for (Map.Entry<?, ?> entry : map.entrySet()) {
        if (entry.getKey() != null && normalizedKey(entry.getKey()).equals(normalizedWanted)) {
          return entry.getValue();
        }
      }
      for (Object child : map.values()) {
        Object found = findKey(child, wanted);
        if (found != null) return found;
      }
    } else if (value instanceof Iterable<?> iterable) {
      for (Object child : iterable) {
        Object found = findKey(child, wanted);
        if (found != null) return found;
      }
    }
    return null;
  }

  private static String normalizedKey(Object value) {
    return String.valueOf(value).replaceAll("[^A-Za-z0-9]", "").toLowerCase(Locale.ROOT);
  }

  private static Instant publishedAt(RepositoryDataMigrationAssetRecord source) {
    for (Instant candidate : new Instant[] {
        source.sourceBlobCreatedAt(), source.sourceLastUpdatedAt(), source.sourceBlobUpdatedAt()}) {
      if (candidate != null) return candidate;
    }
    return Instant.now();
  }

  private static String firstNonBlank(String first, String second) {
    if (first != null && !first.isBlank()) return first;
    return second == null || second.isBlank() ? "application/octet-stream" : second;
  }

  private static String normalize(String path) {
    String value = path == null ? "" : path.trim();
    while (value.startsWith("/")) value = value.substring(1);
    while (value.endsWith("/")) value = value.substring(0, value.length() - 1);
    return value;
  }

  private static void closeQuietly(InputStream body) {
    if (body == null) return;
    try {
      body.close();
    } catch (IOException ignored) {
    }
  }

  public record MigratedAsset(
      Long componentId, long assetId, long assetBlobId, String assetBlobObjectKey) { }
}
