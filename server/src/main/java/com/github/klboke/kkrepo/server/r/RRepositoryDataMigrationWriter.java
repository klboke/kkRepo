package com.github.klboke.kkrepo.server.r;

import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.core.RepositoryType;
import com.github.klboke.kkrepo.persistence.jdbc.api.AssetDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.RRegistryDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetBlobRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.RepositoryDataMigrationAssetRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.RepositoryRecord;
import com.github.klboke.kkrepo.protocol.r.RPath;
import com.github.klboke.kkrepo.protocol.r.RPathParser;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntime;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntimeRegistry;
import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Component;

/** Replays checksum-verified Nexus hosted source packages through the normal R importer. */
@Component
public class RRepositoryDataMigrationWriter {
  private static final RPathParser PATHS = new RPathParser();

  private final RSourcePackageInspector inspector;
  private final RService service;
  private final RepositoryRuntimeRegistry runtimes;
  private final RRegistryDao registry;
  private final AssetDao assets;

  RRepositoryDataMigrationWriter(
      RSourcePackageInspector inspector,
      RService service,
      RepositoryRuntimeRegistry runtimes,
      RRegistryDao registry,
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
    if (repository.format() != RepositoryFormat.R
        || repository.type() != RepositoryType.HOSTED) {
      closeQuietly(body);
      throw new IllegalArgumentException(
          "R data migration only supports hosted source packages");
    }
    RPath path = packagePath(source.sourcePath());
    RepositoryRuntime runtime = runtimes.resolveById(repository.id())
        .orElseThrow(() -> new IllegalArgumentException(
            "R migration target repository is unavailable: " + repository.name()));
    try (body;
        RSourcePackageInspector.InspectedPackage inspected =
            inspector.inspect(body, path.filename())) {
      validateSource(source, inspected, validateSize);
      RService.PublishedPackage published = service.restoreHostedPackageForMigration(
          runtime,
          path,
          inspected,
          firstNonBlank(source.sourceCreatedBy(), "nexus-migration"),
          source.sourceCreatedByIp());
      RRegistryDao.PackageRecord record = registry.findPackageByPath(
              repository.id(), published.path())
          .orElseThrow(() -> new IllegalStateException(
              "Restored R package projection is missing"));
      return migrated(record);
    } catch (IOException error) {
      throw new IllegalStateException(
          "Failed reading R migration package " + source.sourcePath(), error);
    }
  }

  public static boolean isMigratableRPath(String rawPath) {
    try {
      packagePath(rawPath);
      return true;
    } catch (RuntimeException ignored) {
      return false;
    }
  }

  private MigratedAsset migrated(RRegistryDao.PackageRecord record) {
    if (record.assetId() == null || record.componentId() == null) {
      throw new IllegalStateException("Restored R package has no asset/component binding");
    }
    AssetRecord asset = assets.findAssetById(record.assetId())
        .orElseThrow(() -> new IllegalStateException("Restored R package asset is missing"));
    AssetBlobRecord blob = asset.assetBlobId() == null
        ? null
        : assets.findBlobById(asset.assetBlobId()).orElse(null);
    if (blob == null || !record.sha256().equalsIgnoreCase(blob.sha256())) {
      throw new IllegalStateException("Restored R package blob checksum is invalid");
    }
    return new MigratedAsset(record.componentId(), asset.id(), blob.id(), blob.objectKey());
  }

  private static RPath packagePath(String rawPath) {
    RPath path = PATHS.parse(normalize(rawPath));
    if (path.kind() != RPath.Kind.SOURCE_PACKAGE
        || !path.normalized().startsWith(RService.SOURCE_NAMESPACE + "/")) {
      throw new IllegalArgumentException(
          "Unsupported Nexus R migration asset: " + rawPath);
    }
    return path;
  }

  private static void validateSource(
      RepositoryDataMigrationAssetRecord source,
      RSourcePackageInspector.InspectedPackage inspected,
      boolean validateSize) {
    if (validateSize && source.size() != null && source.size() >= 0
        && source.size() != inspected.size()) {
      throw new IllegalStateException(
          "R migration size mismatch for " + source.sourcePath());
    }
    SourceChecksum checksum = sourceChecksum(source.metadata());
    if (checksum == null) {
      throw new IllegalStateException(
          "R migration requires a valid source SHA-1 or SHA-256 for " + source.sourcePath());
    }
    String actual = checksum.algorithm().equals("SHA-256")
        ? inspected.sha256() : inspected.sha1();
    if (!checksum.value().equalsIgnoreCase(actual)) {
      throw new IllegalStateException(
          "R migration checksum mismatch for " + source.sourcePath());
    }
    if (source.name() != null
        && !source.name().equals(inspected.metadata().packageName())) {
      throw new IllegalStateException("R migration package name disagrees with DESCRIPTION");
    }
    if (source.version() != null
        && !source.version().equals(inspected.metadata().version())) {
      throw new IllegalStateException("R migration package version disagrees with DESCRIPTION");
    }
  }

  private static SourceChecksum sourceChecksum(Object value) {
    Object found = findKey(value, "sha256");
    if (found == null) found = findKey(value, "checksum.sha256");
    String text = found == null ? null : found.toString().trim().toLowerCase(Locale.ROOT);
    if (text != null && text.matches("[0-9a-f]{64}")) {
      return new SourceChecksum("SHA-256", text);
    }
    found = findKey(value, "sha1");
    if (found == null) found = findKey(value, "checksum.sha1");
    text = found == null ? null : found.toString().trim().toLowerCase(Locale.ROOT);
    return text != null && text.matches("[0-9a-f]{40}")
        ? new SourceChecksum("SHA-1", text) : null;
  }

  private static Object findKey(Object value, String wanted) {
    if (value instanceof Map<?, ?> map) {
      String normalizedWanted = normalizedKey(wanted);
      for (Map.Entry<?, ?> entry : map.entrySet()) {
        if (entry.getKey() != null
            && normalizedKey(entry.getKey()).equals(normalizedWanted)) {
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

  private record SourceChecksum(String algorithm, String value) { }

  public record MigratedAsset(
      Long componentId, long assetId, long assetBlobId, String assetBlobObjectKey) { }
}
