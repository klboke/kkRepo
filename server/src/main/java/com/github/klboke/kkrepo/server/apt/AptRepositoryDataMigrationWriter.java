package com.github.klboke.kkrepo.server.apt;

import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.core.RepositoryType;
import com.github.klboke.kkrepo.persistence.jdbc.api.AptRegistryDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.AssetDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetBlobRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.RepositoryDataMigrationAssetRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.RepositoryRecord;
import com.github.klboke.kkrepo.protocol.apt.AptPath;
import com.github.klboke.kkrepo.protocol.apt.AptPathParser;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntime;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntimeRegistry;
import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Component;

/** Replays verified Nexus hosted Debian packages through the normal APT importer. */
@Component
public class AptRepositoryDataMigrationWriter {
  private static final AptPathParser PATHS = new AptPathParser();

  private final AptDebPackageInspector inspector;
  private final AptService service;
  private final AptRepositorySettings settings;
  private final RepositoryRuntimeRegistry runtimes;
  private final AptRegistryDao registry;
  private final AssetDao assets;

  AptRepositoryDataMigrationWriter(
      AptDebPackageInspector inspector,
      AptService service,
      AptRepositorySettings settings,
      RepositoryRuntimeRegistry runtimes,
      AptRegistryDao registry,
      AssetDao assets) {
    this.inspector = inspector;
    this.service = service;
    this.settings = settings;
    this.runtimes = runtimes;
    this.registry = registry;
    this.assets = assets;
  }

  public MigratedAsset write(
      RepositoryRecord repository,
      RepositoryDataMigrationAssetRecord source,
      InputStream body,
      boolean validateSize) {
    if (repository.format() != RepositoryFormat.APT
        || repository.type() != RepositoryType.HOSTED) {
      closeQuietly(body);
      throw new IllegalArgumentException(
          "APT data migration only supports hosted Debian packages");
    }
    String sourcePath = migratablePath(source.sourcePath());
    RepositoryRuntime runtime = runtimes.resolveById(repository.id())
        .orElseThrow(() -> new IllegalArgumentException(
            "APT migration target repository is unavailable: " + repository.name()));
    AptRepositorySettings.Settings target = settings.get(runtime);
    try (body;
         AptDebPackageInspector.InspectedPackage inspected =
             inspector.inspect(body, filename(sourcePath))) {
      validateSource(source, inspected, validateSize);
      AptService.PublishedPackage published = service.restoreHostedPackageForMigration(
          runtime,
          inspected,
          target.distribution(),
          target.component(),
          firstNonBlank(source.sourceCreatedBy(), "nexus-migration"),
          source.sourceCreatedByIp(),
          sourcePath);
      AptRegistryDao.PackageRecord record = registry.findPackageByPath(
              repository.id(), published.path())
          .orElseThrow(() -> new IllegalStateException(
              "Restored APT package projection is missing"));
      return migrated(record);
    } catch (IOException error) {
      throw new IllegalStateException(
          "Failed reading APT migration package " + source.sourcePath(), error);
    }
  }

  public static boolean isMigratableAptPath(String path) {
    try {
      migratablePath(path);
      return true;
    } catch (RuntimeException ignored) {
      return false;
    }
  }

  private MigratedAsset migrated(AptRegistryDao.PackageRecord record) {
    if (record.assetId() == null || record.componentId() == null) {
      throw new IllegalStateException("Restored APT package has no asset/component binding");
    }
    AssetRecord asset = assets.findAssetById(record.assetId())
        .orElseThrow(() -> new IllegalStateException("Restored APT package asset is missing"));
    AssetBlobRecord blob = asset.assetBlobId() == null
        ? null
        : assets.findBlobById(asset.assetBlobId()).orElse(null);
    if (blob == null || !record.sha256().equalsIgnoreCase(blob.sha256())) {
      throw new IllegalStateException("Restored APT package blob checksum is invalid");
    }
    return new MigratedAsset(record.componentId(), asset.id(), blob.id(), blob.objectKey());
  }

  private static void validateSource(
      RepositoryDataMigrationAssetRecord source,
      AptDebPackageInspector.InspectedPackage inspected,
      boolean validateSize) {
    if (validateSize && source.size() != null && source.size() >= 0
        && source.size() != inspected.size()) {
      throw new IllegalStateException(
          "APT migration size mismatch for " + source.sourcePath());
    }
    String expectedSha256 = sourceSha256(source.metadata());
    if (expectedSha256 == null) {
      throw new IllegalStateException(
          "APT migration requires a valid source SHA-256 for " + source.sourcePath());
    }
    if (!expectedSha256.equalsIgnoreCase(inspected.sha256())) {
      throw new IllegalStateException(
          "APT migration checksum mismatch for " + source.sourcePath());
    }
    if (source.name() != null && !source.name().equals(inspected.control().packageName())) {
      throw new IllegalStateException("APT migration package name disagrees with the archive");
    }
    if (source.version() != null && !source.version().equals(inspected.control().version())) {
      throw new IllegalStateException("APT migration package version disagrees with the archive");
    }
  }

  private static String sourceSha256(Object value) {
    Object found = findKey(value, "sha256");
    String text = found == null ? null : found.toString().trim().toLowerCase(Locale.ROOT);
    return text != null && text.matches("[0-9a-f]{64}") ? text : null;
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

  private static String normalizedKey(Object value) {
    return String.valueOf(value).replaceAll("[^A-Za-z0-9]", "")
        .toLowerCase(Locale.ROOT);
  }

  private static String migratablePath(String rawPath) {
    String path = normalize(rawPath);
    AptPath parsed = PATHS.parse(path);
    if (parsed.kind() != AptPath.Kind.PACKAGE || !path.startsWith("pool/")) {
      throw new IllegalArgumentException(
          "Unsupported Nexus APT migration asset: " + rawPath);
    }
    return parsed.normalized();
  }

  private static String filename(String path) {
    return path.substring(path.lastIndexOf('/') + 1);
  }

  private static String normalize(String path) {
    String value = path == null ? "" : path.trim();
    while (value.startsWith("/")) {
      value = value.substring(1);
    }
    while (value.endsWith("/")) {
      value = value.substring(0, value.length() - 1);
    }
    return value;
  }

  private static String firstNonBlank(String first, String second) {
    return first != null && !first.isBlank() ? first : second;
  }

  private static void closeQuietly(InputStream body) {
    if (body == null) {
      return;
    }
    try {
      body.close();
    } catch (IOException ignored) {
    }
  }

  public record MigratedAsset(
      Long componentId, long assetId, long assetBlobId, String assetBlobObjectKey) { }
}
