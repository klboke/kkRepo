package com.github.klboke.kkrepo.persistence.jdbc.api;

import com.github.klboke.kkrepo.core.DatabaseCompositeKey;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Durable Conan 2 identity, revision, upload, group-routing, and bearer-token state.
 *
 * <p>Archive and metadata bytes stay in the repository blob store. Every exact lookup in this
 * contract is backed by a repository-leading hash/identity index; callers must still compare the
 * canonical values returned after a hash hit.
 */
public interface ConanRegistryDao {
  String OWNER_RECIPE = "RECIPE";
  String OWNER_PACKAGE = "PACKAGE";
  String SOURCE_HOSTED = "HOSTED";
  String SOURCE_PROXY = "PROXY";
  String STATUS_COMMITTED = "COMMITTED";
  String STATUS_DISCOVERED = "DISCOVERED";
  String SESSION_OPEN = "OPEN";
  String SESSION_COMMITTING = "COMMITTING";

  long nextRepositoryRevision(long repositoryId);

  long currentRepositoryRevision(long repositoryId);

  default Map<Long, Long> currentRepositoryRevisions(Collection<Long> repositoryIds) {
    LinkedHashMap<Long, Long> result = new LinkedHashMap<>();
    if (repositoryIds != null) {
      repositoryIds.stream().filter(java.util.Objects::nonNull).distinct()
          .forEach(id -> result.put(id, currentRepositoryRevision(id)));
    }
    return Map.copyOf(result);
  }

  Optional<Recipe> findRecipe(RecipeCoordinate coordinate);

  Optional<Recipe> findRecipeByComponent(long repositoryId, long componentId);

  Optional<RecipeRevision> findRecipeRevision(long recipeId, String revision);

  Optional<RecipeRevision> findLatestRecipeRevision(long recipeId);

  List<RecipeRevision> listRecipeRevisions(long recipeId, Long afterId, int limit);

  List<Recipe> searchRecipes(
      long repositoryId, String pattern, boolean ignoreCase, Long afterId, int limit);

  Optional<Package> findPackage(long recipeRevisionId, String packageId);

  List<Package> listPackages(long recipeRevisionId, Long afterId, int limit);

  Optional<PackageRevision> findPackageRevision(long packageRowId, String revision);

  Optional<PackageRevision> findLatestPackageRevision(long packageRowId);

  List<PackageRevision> listPackageRevisions(long packageRowId, Long afterId, int limit);

  List<RevisionFile> listFiles(String ownerKind, long ownerId, Long afterId, int limit);

  Optional<RevisionFile> findFile(String ownerKind, long ownerId, String path);

  /** Resolves an asset through the immutable typed Conan identity written at publication time. */
  Optional<AssetFile> findFileByAssetId(long assetId);

  /** Resolves any PREV file to its package archive plus conaninfo composite scan identity. */
  Optional<PackageScanContext> findPackageScanContext(long packageAssetId);

  /** Inserts a complete immutable hosted revision, or reuses an identical committed revision. */
  CommittedRevision commitRevision(RevisionCommit commit);

  /** Restores a complete hosted revision or proxy cache snapshot during an audited migration. */
  CommittedRevision restoreRevision(RevisionCommit commit);

  /** Records a bounded proxy discovery observation without making missing files appear cached. */
  CommittedRevision recordDiscoveredRevision(RevisionCommit discovery);

  /** Binds a fetched proxy file to a previously discovered immutable revision. */
  RevisionFile bindDiscoveredFile(
      String ownerKind,
      long ownerId,
      FileCommit file,
      long expectedRepositoryRevision);

  /** Adds or replaces metadata below an already committed revision without changing latest. */
  RevisionFile upsertMetadataFile(
      String ownerKind, long ownerId, FileCommit file, long repositoryId);

  DeletedCoordinate deleteCoordinate(DeleteTarget target, String reason, Instant deletedAt);

  /** Deletes every package below one committed RREV while retaining the recipe revision itself. */
  DeletedCoordinate deleteAllPackages(
      RecipeCoordinate coordinate, String recipeRevision, String reason, Instant deletedAt);

  UploadSession openUploadSession(UploadSession candidate);

  Optional<UploadSession> findUploadSession(long sessionId);

  Optional<UploadSession> findUploadSession(
      long repositoryId, String ownerKind, String coordinateKey, String actorKey);

  UploadFile upsertUploadFile(UploadFile file);

  List<UploadFile> listUploadFiles(long sessionId);

  boolean beginSessionCommit(long sessionId, long fencingToken, Instant leaseUntil);

  int deleteUploadSession(long sessionId);

  /** Deletes an expired upload only when the caller still owns the durable cleanup fence. */
  boolean deleteClaimedUploadSession(long sessionId, String owner, long fencingToken);

  List<UploadSession> claimExpiredUploadSessions(
      String owner, Instant now, Instant leaseUntil, int limit);

  Optional<Lease> tryAcquireLease(
      long repositoryId, String coordinateKey, String owner, Instant expiresAt);

  boolean renewLease(
      long repositoryId,
      String coordinateKey,
      String owner,
      long fencingToken,
      Instant expiresAt);

  void releaseLease(
      long repositoryId, String coordinateKey, String owner, long fencingToken);

  int deleteExpiredLeases(Instant now, int limit);

  Optional<GroupBinding> findGroupBinding(
      long groupRepositoryId, String bindingKind, String coordinateKey);

  boolean upsertGroupBindingIfCurrent(GroupBinding binding);

  int deleteGroupBindings(long groupRepositoryId);

  int deleteGroupBindingsForMember(long memberRepositoryId);

  void insertAuthToken(AuthToken token);

  Optional<AuthToken> findValidAuthToken(String tokenHash, long repositoryId, Instant now);

  int touchAuthToken(String tokenHash, Instant usedAt);

  int deleteExpiredAuthTokens(Instant now, int limit);

  int deleteRepositoryState(long repositoryId);

  RepositoryStatus status(long repositoryId);

  record RecipeCoordinate(
      long repositoryId,
      String name,
      String version,
      String user,
      String channel) {
    public String coordinateKey() {
      return DatabaseCompositeKey.of(name, version, user, channel);
    }
  }

  record Recipe(
      long id,
      long repositoryId,
      Long componentId,
      String name,
      String version,
      String user,
      String channel,
      Long latestRecipeRevisionId,
      Instant createdAt,
      Instant updatedAt) {
    public RecipeCoordinate coordinate() {
      return new RecipeCoordinate(repositoryId, name, version, user, channel);
    }
  }

  record RecipeRevision(
      long id,
      long recipeId,
      String revision,
      String manifestSha256,
      String sourceKind,
      String status,
      long repositoryRevision,
      Instant publishedAt,
      Instant createdAt) {
  }

  record Package(
      long id,
      long recipeRevisionId,
      String packageId,
      Map<String, String> settings,
      Map<String, String> options,
      Map<String, String> requires,
      Long latestPackageRevisionId,
      Instant createdAt,
      Instant updatedAt) {
    public Package {
      settings = immutable(settings);
      options = immutable(options);
      requires = immutable(requires);
    }
  }

  record PackageRevision(
      long id,
      long packageRowId,
      String revision,
      String manifestSha256,
      String sourceKind,
      String status,
      long repositoryRevision,
      Instant publishedAt,
      Instant createdAt) {
  }

  record RevisionFile(
      long id,
      String ownerKind,
      long ownerId,
      String path,
      Long assetId,
      String md5,
      String sha1,
      String sha256,
      long size,
      String contentType,
      Long sourceRepositoryId,
      Instant createdAt,
      Instant updatedAt) {
  }

  record FileCommit(
      String path,
      Long assetId,
      String md5,
      String sha1,
      String sha256,
      long size,
      String contentType,
      Long sourceRepositoryId) {
  }

  record AssetFile(
      RevisionFile file,
      RecipeCoordinate coordinate,
      String recipeRevision,
      String packageId,
      String packageRevision) {
  }

  record PackageScanContext(
      AssetFile archive,
      RevisionFile conanInfo) {
  }

  record RevisionCommit(
      RecipeCoordinate coordinate,
      Long componentId,
      String ownerKind,
      String recipeRevision,
      String packageId,
      String packageRevision,
      Map<String, String> settings,
      Map<String, String> options,
      Map<String, String> requires,
      String manifestSha256,
      String sourceKind,
      String status,
      Instant publishedAt,
      List<FileCommit> files) {
    public RevisionCommit {
      settings = immutable(settings);
      options = immutable(options);
      requires = immutable(requires);
      files = files == null ? List.of() : List.copyOf(files);
    }
  }

  record CommittedRevision(
      long recipeId,
      long recipeRevisionId,
      Long packageRowId,
      Long packageRevisionId,
      long ownerId,
      long repositoryRevision,
      boolean idempotent) {
  }

  record DeleteTarget(
      RecipeCoordinate coordinate,
      String recipeRevision,
      String packageId,
      String packageRevision) {
  }

  record DeletedCoordinate(
      boolean deleted,
      List<Long> assetIds,
      List<Long> componentIds,
      long repositoryRevision) {
    public DeletedCoordinate {
      assetIds = assetIds == null ? List.of() : List.copyOf(assetIds);
      componentIds = componentIds == null ? List.of() : List.copyOf(componentIds);
    }
  }

  record UploadSession(
      Long id,
      long repositoryId,
      String ownerKind,
      String coordinateKey,
      String actorKey,
      String status,
      String owner,
      long fencingToken,
      Instant leaseUntil,
      Instant expiresAt,
      Instant createdAt,
      Instant updatedAt) {
  }

  record UploadFile(
      Long id,
      long sessionId,
      String path,
      long stagingAssetId,
      String md5,
      String sha1,
      String sha256,
      long size,
      String contentType,
      Instant createdAt,
      Instant updatedAt) {
  }

  record Lease(
      long repositoryId,
      String coordinateKey,
      String owner,
      long fencingToken,
      Instant expiresAt,
      Instant updatedAt) {
  }

  record GroupBinding(
      long groupRepositoryId,
      String bindingKind,
      String coordinateKey,
      long memberRepositoryId,
      long memberOwnerId,
      long memberRevision,
      long groupConfigRevision,
      Instant expiresAt,
      Instant boundAt,
      Instant updatedAt) {
  }

  record AuthToken(
      String tokenHash,
      long repositoryId,
      String subjectSource,
      String subjectUserId,
      String realmId,
      Long apiKeyId,
      Instant expiresAt,
      Instant lastUsedAt,
      Instant createdAt) {
  }

  record RepositoryStatus(
      long repositoryRevision,
      long recipes,
      long recipeRevisions,
      long packages,
      long packageRevisions,
      long committedFiles,
      long openUploadSessions,
      long cachedProxyFiles) {
  }

  private static Map<String, String> immutable(Map<String, String> values) {
    return java.util.Collections.unmodifiableMap(
        new LinkedHashMap<>(values == null ? Map.of() : values));
  }
}
