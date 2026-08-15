package com.github.klboke.kkrepo.server.alpine;

import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.core.RepositoryType;
import com.github.klboke.kkrepo.persistence.jdbc.api.AlpineRegistryDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.AssetDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetBlobRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.RepositoryDataMigrationAssetRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.RepositoryRecord;
import com.github.klboke.kkrepo.protocol.alpine.AlpinePath;
import com.github.klboke.kkrepo.protocol.alpine.AlpinePathParser;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntime;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntimeRegistry;
import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Component;

/** Replays verified Nexus hosted APK v2 packages through the normal Alpine importer. */
@Component
public class AlpineRepositoryDataMigrationWriter {
  private static final AlpinePathParser PATHS = new AlpinePathParser();

  private final AlpinePackageInspector inspector;
  private final AlpineService service;
  private final RepositoryRuntimeRegistry runtimes;
  private final AlpineRegistryDao registry;
  private final AssetDao assets;

  AlpineRepositoryDataMigrationWriter(
      AlpinePackageInspector inspector,
      AlpineService service,
      RepositoryRuntimeRegistry runtimes,
      AlpineRegistryDao registry,
      AssetDao assets) {
    this.inspector = inspector;
    this.service = service;
    this.runtimes = runtimes;
    this.registry = registry;
    this.assets = assets;
  }

  public MigratedAsset write(
      RepositoryRecord repository,
      RepositoryDataMigrationAssetRecord source,
      InputStream body,
      boolean validateSize) {
    if (repository.format() != RepositoryFormat.ALPINE
        || repository.type() != RepositoryType.HOSTED) {
      closeQuietly(body);
      throw new IllegalArgumentException(
          "Alpine data migration only supports hosted APK v2 packages");
    }
    AlpinePath path = packagePath(source.sourcePath());
    RepositoryRuntime runtime = runtimes.resolveById(repository.id())
        .orElseThrow(() -> new IllegalArgumentException(
            "Alpine migration target repository is unavailable: " + repository.name()));
    try (body;
         AlpinePackageInspector.InspectedPackage inspected =
             inspector.inspect(body, path.filename())) {
      validateSource(source, inspected, validateSize);
      AlpineService.PublishedPackage published = service.restoreHostedPackageForMigration(
          runtime,
          path,
          inspected,
          firstNonBlank(source.sourceCreatedBy(), "nexus-migration"),
          source.sourceCreatedByIp());
      AlpineRegistryDao.PackageRecord record = registry.findPackageByPath(
              repository.id(), published.path())
          .orElseThrow(() -> new IllegalStateException(
              "Restored Alpine package projection is missing"));
      return migrated(record);
    } catch (IOException error) {
      throw new IllegalStateException(
          "Failed reading Alpine migration package " + source.sourcePath(), error);
    }
  }

  public static boolean isMigratableAlpinePath(String rawPath) {
    try {
      packagePath(rawPath);
      return true;
    } catch (RuntimeException ignored) {
      return false;
    }
  }

  private MigratedAsset migrated(AlpineRegistryDao.PackageRecord record) {
    if (record.assetId() == null || record.componentId() == null) {
      throw new IllegalStateException("Restored Alpine package has no asset/component binding");
    }
    AssetRecord asset = assets.findAssetById(record.assetId())
        .orElseThrow(() -> new IllegalStateException("Restored Alpine package asset is missing"));
    AssetBlobRecord blob = asset.assetBlobId() == null
        ? null
        : assets.findBlobById(asset.assetBlobId()).orElse(null);
    if (blob == null || !record.sha256().equalsIgnoreCase(blob.sha256())) {
      throw new IllegalStateException("Restored Alpine package blob checksum is invalid");
    }
    return new MigratedAsset(record.componentId(), asset.id(), blob.id(), blob.objectKey());
  }

  private static AlpinePath packagePath(String rawPath) {
    AlpinePath path = PATHS.parse(normalize(rawPath));
    if (path.kind() != AlpinePath.Kind.PACKAGE) {
      throw new IllegalArgumentException(
          "Unsupported Nexus Alpine migration asset: " + rawPath);
    }
    return path;
  }

  private static void validateSource(
      RepositoryDataMigrationAssetRecord source,
      AlpinePackageInspector.InspectedPackage inspected,
      boolean validateSize) {
    if (validateSize && source.size() != null && source.size() >= 0
        && source.size() != inspected.size()) {
      throw new IllegalStateException(
          "Alpine migration size mismatch for " + source.sourcePath());
    }
    String expectedSha256 = sourceSha256(source.metadata());
    if (expectedSha256 == null) {
      throw new IllegalStateException(
          "Alpine migration requires a valid source SHA-256 for " + source.sourcePath());
    }
    if (!expectedSha256.equalsIgnoreCase(inspected.sha256())) {
      throw new IllegalStateException(
          "Alpine migration checksum mismatch for " + source.sourcePath());
    }
    if (source.name() != null && !source.name().equals(inspected.info().name())) {
      throw new IllegalStateException("Alpine migration package name disagrees with the archive");
    }
    if (source.version() != null && !source.version().equals(inspected.info().version())) {
      throw new IllegalStateException("Alpine migration package version disagrees with the archive");
    }
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
    return String.valueOf(value).replaceAll("[^A-Za-z0-9]", "")
        .toLowerCase(Locale.ROOT);
  }

  private static String normalize(String path) {
    String value = path == null ? "" : path.trim();
    while (value.startsWith("/")) value = value.substring(1);
    while (value.endsWith("/")) value = value.substring(0, value.length() - 1);
    return value;
  }

  private static String firstNonBlank(String first, String second) {
    return first != null && !first.isBlank() ? first : second;
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
