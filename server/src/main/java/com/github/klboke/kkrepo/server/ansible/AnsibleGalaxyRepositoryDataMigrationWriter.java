package com.github.klboke.kkrepo.server.ansible;

import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.core.RepositoryType;
import com.github.klboke.kkrepo.persistence.jdbc.api.AssetDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.AnsibleGalaxyRegistryDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetBlobRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.RepositoryDataMigrationAssetRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.RepositoryRecord;
import com.github.klboke.kkrepo.protocol.ansible.AnsibleGalaxyNames;
import com.github.klboke.kkrepo.protocol.ansible.AnsibleGalaxyPathParser;
import com.github.klboke.kkrepo.protocol.ansible.AnsibleGalaxyVersions;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntime;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntimeRegistry;
import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Component;

/** Restores verified Nexus 3.93/3.94 hosted collection archives through the immutable importer. */
@Component
public class AnsibleGalaxyRepositoryDataMigrationWriter {
  private final AnsibleCollectionArchiveInspector inspector;
  private final AnsibleGalaxyService service;
  private final AnsibleGalaxyRegistryDao registry;
  private final RepositoryRuntimeRegistry runtimes;
  private final AssetDao assets;

  AnsibleGalaxyRepositoryDataMigrationWriter(
      AnsibleCollectionArchiveInspector inspector,
      AnsibleGalaxyService service,
      AnsibleGalaxyRegistryDao registry,
      RepositoryRuntimeRegistry runtimes,
      AssetDao assets) {
    this.inspector = inspector;
    this.service = service;
    this.registry = registry;
    this.runtimes = runtimes;
    this.assets = assets;
  }

  public MigratedAsset write(
      RepositoryRecord repository,
      RepositoryDataMigrationAssetRecord source,
      InputStream body,
      boolean validateSize) {
    if (repository.format() != RepositoryFormat.ANSIBLEGALAXY
        || repository.type() == RepositoryType.GROUP) {
      closeQuietly(body);
      throw new IllegalArgumentException(
          "Ansible Galaxy data migration supports hosted and explicitly selected proxy caches");
    }
    Coordinates sourceCoordinates = coordinates(source.sourcePath());
    RepositoryRuntime runtime = runtimes.resolveById(repository.id())
        .orElseThrow(() -> new IllegalArgumentException(
            "Ansible Galaxy migration target repository is unavailable: " + repository.name()));
    AnsibleCollectionArchiveInspector.InspectedCollection inspected = null;
    try (body) {
      inspected = inspector.inspect(body);
      validateSource(source, sourceCoordinates, inspected, validateSize);
      AnsibleGalaxyRegistryDao.CollectionVersion restored =
          service.restoreCollectionForMigration(
              runtime, inspected, publishedAt(source),
              firstNonBlank(source.sourceCreatedBy(), "nexus-migration"),
              source.sourceCreatedByIp());
      if (!restored.artifactSha256().equalsIgnoreCase(inspected.sha256())) {
        throw new IllegalStateException(
            "Ansible Galaxy migration checksum changed while restoring " + source.sourcePath());
      }
      return migrated(restored);
    } catch (IOException e) {
      throw new IllegalStateException(
          "Failed reading Ansible Galaxy migration archive " + source.sourcePath(), e);
    } finally {
      if (inspected != null) {
        AnsibleCollectionArchiveInspector.delete(inspected.file());
      }
    }
  }

  public static boolean isMigratableAnsiblePath(String path) {
    try {
      coordinates(path);
      return true;
    } catch (RuntimeException ignored) {
      return false;
    }
  }

  private static void validateSource(
      RepositoryDataMigrationAssetRecord source,
      Coordinates coordinates,
      AnsibleCollectionArchiveInspector.InspectedCollection inspected,
      boolean validateSize) {
    if ((coordinates.namespace() != null
            && (!coordinates.namespace().equals(inspected.namespace())
                || !coordinates.name().equals(inspected.name())
                || !coordinates.version().equals(inspected.version())))
        || !coordinates.filename().equals(inspected.filename())) {
      throw new IllegalArgumentException(
          "Ansible Galaxy migration path and MANIFEST.json identity disagree: "
              + source.sourcePath());
    }
    if (source.namespace() != null
        && !source.namespace().equalsIgnoreCase(inspected.namespace())) {
      throw new IllegalArgumentException("Ansible Galaxy migration namespace disagrees with the archive");
    }
    if (source.name() != null) {
      String sourceName = source.name().contains(".")
          ? source.name().substring(source.name().lastIndexOf('.') + 1)
          : source.name();
      if (!sourceName.equalsIgnoreCase(inspected.name())) {
        throw new IllegalArgumentException("Ansible Galaxy migration name disagrees with the archive");
      }
    }
    if (source.version() != null && !source.version().equals(inspected.version())) {
      throw new IllegalArgumentException("Ansible Galaxy migration version disagrees with the archive");
    }
    if (validateSize && source.size() != null && source.size() >= 0
        && source.size() != inspected.size()) {
      throw new IllegalStateException(
          "Ansible Galaxy migration size mismatch for " + source.sourcePath());
    }
    String sourceSha256 = sourceSha256(source.metadata());
    if (sourceSha256 == null) {
      throw new IllegalStateException(
          "Ansible Galaxy migration requires a valid source SHA-256 for " + source.sourcePath());
    }
    if (!sourceSha256.equalsIgnoreCase(inspected.sha256())) {
      throw new IllegalStateException(
          "Ansible Galaxy migration checksum mismatch for " + source.sourcePath());
    }
  }

  private MigratedAsset migrated(AnsibleGalaxyRegistryDao.CollectionVersion version) {
    AssetRecord asset = assets.findAssetById(version.artifactAssetId())
        .orElseThrow(() -> new IllegalStateException("Restored Ansible Galaxy asset is missing"));
    AssetBlobRecord blob = asset.assetBlobId() == null
        ? null
        : assets.findBlobById(asset.assetBlobId()).orElse(null);
    if (blob == null) {
      throw new IllegalStateException("Restored Ansible Galaxy blob is missing");
    }
    return new MigratedAsset(version.componentId(), asset.id(), blob.id(), blob.objectKey());
  }

  private static Coordinates coordinates(String path) {
    String normalized = path == null ? "" : path.trim();
    while (normalized.startsWith("/")) normalized = normalized.substring(1);
    String[] parts = normalized.split("/", -1);
    if (normalized.startsWith(AnsibleGalaxyPathParser.ARTIFACT_BASE)) {
      String filename = normalized.substring(AnsibleGalaxyPathParser.ARTIFACT_BASE.length());
      if (filename.contains("/") || !filename.endsWith(".tar.gz")) {
        throw new IllegalArgumentException("Invalid Ansible Galaxy proxy artifact path: " + path);
      }
      return new Coordinates(null, null, null, filename);
    }
    if (parts.length != 4) {
      throw new IllegalArgumentException("Invalid Ansible Galaxy migration archive path: " + path);
    }
    String namespace = AnsibleGalaxyNames.requireNamespace(parts[0]);
    String name = AnsibleGalaxyNames.requireCollection(parts[1]);
    String version = AnsibleGalaxyVersions.require(parts[2]);
    String filename = AnsibleGalaxyPathParser.canonicalFilename(namespace, name, version);
    if (!filename.equals(parts[3])) {
      throw new IllegalArgumentException("Ansible Galaxy migration archive filename is not canonical");
    }
    return new Coordinates(namespace, name, version, filename);
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

  private static String firstNonBlank(String first, String fallback) {
    return first == null || first.isBlank() ? fallback : first;
  }

  private static void closeQuietly(InputStream body) {
    if (body == null) return;
    try {
      body.close();
    } catch (IOException ignored) {
    }
  }

  public record MigratedAsset(long componentId, long assetId, long assetBlobId, String assetBlobObjectKey) {
  }

  private record Coordinates(String namespace, String name, String version, String filename) {
  }
}
