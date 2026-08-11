package com.github.klboke.kkrepo.server.conan;

import com.github.klboke.kkrepo.core.DatabaseCompositeKey;
import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.core.RepositoryType;
import com.github.klboke.kkrepo.persistence.jdbc.api.AssetDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.ConanRegistryDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.RepositoryDataMigrationDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetBlobRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.RepositoryDataMigrationAssetRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.RepositoryRecord;
import com.github.klboke.kkrepo.protocol.conan.ConanPaths;
import com.github.klboke.kkrepo.protocol.conan.ConanReference;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntime;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntimeRegistry;
import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Component;

/** Restores Nexus Conan 2 files through the normal manifest-gated publication transaction. */
@Component
public class ConanRepositoryDataMigrationWriter {
  private static final int PAGE = 10_000;
  private static final String MIGRATION_ACTOR = "nexus-migration";

  private final ConanService service;
  private final ConanRegistryDao registry;
  private final RepositoryRuntimeRegistry runtimes;
  private final RepositoryDataMigrationDao migrations;
  private final AssetDao assets;

  ConanRepositoryDataMigrationWriter(
      ConanService service,
      ConanRegistryDao registry,
      RepositoryRuntimeRegistry runtimes,
      RepositoryDataMigrationDao migrations,
      AssetDao assets) {
    this.service = service;
    this.registry = registry;
    this.runtimes = runtimes;
    this.migrations = migrations;
    this.assets = assets;
  }

  public MigratedAsset write(
      RepositoryRecord repository,
      RepositoryDataMigrationAssetRecord source,
      InputStream body,
      String responseContentType,
      boolean validateSize) {
    if (repository.format() != RepositoryFormat.CONAN
        || repository.type() == RepositoryType.GROUP) {
      closeQuietly(body);
      throw new IllegalArgumentException(
          "Conan data migration supports hosted repositories and selected proxy caches");
    }
    ConanPaths.StorageFile storage = ConanPaths.parseStoragePath(source.sourcePath());
    RepositoryRuntime runtime = runtimes.resolveById(repository.id())
        .orElseThrow(() -> new IllegalArgumentException(
            "Conan migration target repository is unavailable: " + repository.name()));
    String expectedSha1 = sourceChecksum(source.metadata(), "sha1", 40);
    if (expectedSha1 == null) {
      closeQuietly(body);
      throw new IllegalStateException(
          "Conan migration requires a source SHA-1 for " + source.sourcePath());
    }
    try (body) {
      MigratedAsset existing = committedAsset(
          repository.id(), storage.reference(), storage.filePath()).orElse(null);
      if (existing != null) {
        validateSource(source, existing, expectedSha1, validateSize);
        retargetCommittedRevision(
            source.repositoryJobId(), repository.id(), storage.reference());
        return existing;
      }
      var response = service.putMigration(
          runtime,
          ConanPaths.fileRoute(storage.reference(), storage.filePath()),
          body,
          source.size() == null ? -1 : source.size(),
          firstNonBlank(responseContentType, source.contentType()),
          expectedSha1,
          MIGRATION_ACTOR,
          source.sourceCreatedByIp(),
          publishedAt(source));
      response.closeBodyIfOpen();
      MigratedAsset migrated = committedAsset(
              repository.id(), storage.reference(), storage.filePath())
          .orElseGet(() -> stagedAsset(repository.id(), storage.reference(), storage.filePath()));
      validateSource(source, migrated, expectedSha1, validateSize);
      if (migrated.committed()) {
        retargetCommittedRevision(
            source.repositoryJobId(), repository.id(), storage.reference());
      }
      return migrated;
    } catch (IOException e) {
      throw new IllegalStateException(
          "Failed reading Conan migration asset " + source.sourcePath(), e);
    }
  }

  public static boolean isMigratableConanPath(String path) {
    try {
      ConanPaths.parseStoragePath(path);
      return true;
    } catch (RuntimeException ignored) {
      return false;
    }
  }

  private java.util.Optional<MigratedAsset> committedAsset(
      long repositoryId, ConanReference reference, String filePath) {
    ResolvedOwner owner = owner(repositoryId, reference);
    if (owner == null) return java.util.Optional.empty();
    return registry.findFile(owner.kind(), owner.id(), filePath)
        .filter(file -> file.assetId() != null)
        .map(file -> migrated(file.assetId(), true));
  }

  private MigratedAsset stagedAsset(
      long repositoryId, ConanReference reference, String filePath) {
    String actorKey = DatabaseCompositeKey.of("MIGRATION", MIGRATION_ACTOR);
    ConanRegistryDao.UploadSession session = registry.findUploadSession(
            repositoryId, ownerKind(reference), ownerCoordinate(reference), actorKey)
        .orElseThrow(() -> new IllegalStateException(
            "Conan migration upload session disappeared before manifest commit"));
    ConanRegistryDao.UploadFile file = registry.listUploadFiles(session.id()).stream()
        .filter(candidate -> filePath.equals(candidate.path()))
        .findFirst()
        .orElseThrow(() -> new IllegalStateException(
            "Conan migration staged file disappeared: " + filePath));
    return migrated(file.stagingAssetId(), false);
  }

  private MigratedAsset migrated(long assetId, boolean committed) {
    AssetRecord asset = assets.findAssetById(assetId)
        .orElseThrow(() -> new IllegalStateException("Migrated Conan asset is missing"));
    AssetBlobRecord blob = asset.assetBlobId() == null
        ? null : assets.findBlobById(asset.assetBlobId()).orElse(null);
    if (blob == null) throw new IllegalStateException("Migrated Conan blob is missing");
    return new MigratedAsset(
        asset.componentId(), asset.id(), blob.id(), blob.objectKey(), blob.sha1(), blob.size(),
        committed);
  }

  private void retargetCommittedRevision(
      long repositoryJobId, long repositoryId, ConanReference reference) {
    ResolvedOwner owner = owner(repositoryId, reference);
    if (owner == null) return;
    List<ConanRegistryDao.RevisionFile> files = registry.listFiles(
        owner.kind(), owner.id(), null, PAGE);
    for (ConanRegistryDao.RevisionFile file : files) {
      if (file.assetId() == null) continue;
      MigratedAsset migrated = migrated(file.assetId(), true);
      migrations.retargetMigratedAsset(
          repositoryJobId,
          ConanPaths.storagePath(reference, file.path()),
          migrated.componentId(),
          migrated.assetId(),
          migrated.assetBlobId());
    }
  }

  private ResolvedOwner owner(long repositoryId, ConanReference reference) {
    ConanRegistryDao.RecipeCoordinate coordinate = new ConanRegistryDao.RecipeCoordinate(
        repositoryId, reference.name(), reference.version(),
        reference.user(), reference.channel());
    ConanRegistryDao.Recipe recipe = registry.findRecipe(coordinate).orElse(null);
    if (recipe == null) return null;
    ConanRegistryDao.RecipeRevision revision = registry.findRecipeRevision(
        recipe.id(), reference.recipeRevision()).orElse(null);
    if (revision == null) return null;
    if (reference.packageId() == null) {
      return new ResolvedOwner(ConanRegistryDao.OWNER_RECIPE, revision.id());
    }
    ConanRegistryDao.Package pkg = registry.findPackage(
        revision.id(), reference.packageId()).orElse(null);
    if (pkg == null) return null;
    ConanRegistryDao.PackageRevision packageRevision = registry.findPackageRevision(
        pkg.id(), reference.packageRevision()).orElse(null);
    return packageRevision == null
        ? null : new ResolvedOwner(ConanRegistryDao.OWNER_PACKAGE, packageRevision.id());
  }

  private static void validateSource(
      RepositoryDataMigrationAssetRecord source,
      MigratedAsset migrated,
      String expectedSha1,
      boolean validateSize) {
    if (!expectedSha1.equalsIgnoreCase(migrated.sha1())) {
      throw new IllegalStateException(
          "Conan migration checksum mismatch for " + source.sourcePath());
    }
    if (validateSize && source.size() != null && source.size() >= 0
        && source.size() != migrated.size()) {
      throw new IllegalStateException(
          "Conan migration size mismatch for " + source.sourcePath());
    }
  }

  private static String sourceChecksum(Object value, String wanted, int length) {
    Object found = findKey(value, wanted);
    if (found == null) found = findKey(value, "checksum." + wanted);
    String text = found == null ? null : found.toString().trim().toLowerCase(Locale.ROOT);
    return text != null && text.matches("[0-9a-f]{" + length + "}") ? text : null;
  }

  private static Object findKey(Object value, String wanted) {
    if (value instanceof Map<?, ?> map) {
      String key = normalizeKey(wanted);
      for (Map.Entry<?, ?> entry : map.entrySet()) {
        if (entry.getKey() != null && normalizeKey(entry.getKey()).equals(key)) {
          return entry.getValue();
        }
      }
      for (Object child : map.values()) {
        Object found = findKey(child, wanted);
        if (found != null) return found;
      }
    } else if (value instanceof Iterable<?> values) {
      for (Object child : values) {
        Object found = findKey(child, wanted);
        if (found != null) return found;
      }
    }
    return null;
  }

  private static String normalizeKey(Object value) {
    return String.valueOf(value).replaceAll("[^A-Za-z0-9]", "").toLowerCase(Locale.ROOT);
  }

  private static Instant publishedAt(RepositoryDataMigrationAssetRecord source) {
    for (Instant candidate : new Instant[] {
        source.sourceBlobCreatedAt(), source.sourceLastUpdatedAt(),
        source.sourceBlobUpdatedAt()}) {
      if (candidate != null) return candidate;
    }
    return Instant.now();
  }

  private static String ownerKind(ConanReference reference) {
    return reference.packageId() == null
        ? ConanRegistryDao.OWNER_RECIPE : ConanRegistryDao.OWNER_PACKAGE;
  }

  private static String ownerCoordinate(ConanReference reference) {
    return reference.packageId() == null
        ? reference.recipeWithRevision() : reference.packageReference();
  }

  private static String firstNonBlank(String first, String second) {
    if (first != null && !first.isBlank()) return first;
    return second == null || second.isBlank() ? "application/octet-stream" : second;
  }

  private static void closeQuietly(InputStream body) {
    if (body == null) return;
    try {
      body.close();
    } catch (IOException ignored) {
    }
  }

  private record ResolvedOwner(String kind, long id) {
  }

  public record MigratedAsset(
      Long componentId,
      long assetId,
      long assetBlobId,
      String assetBlobObjectKey,
      String sha1,
      long size,
      boolean committed) {
  }
}
