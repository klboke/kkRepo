package com.github.klboke.kkrepo.persistence.jdbc.internal;

import static com.github.klboke.kkrepo.persistence.jdbc.internal.support.JdbcRows.nullableInstant;
import static com.github.klboke.kkrepo.persistence.jdbc.internal.support.JdbcRows.nullableLong;

import com.fasterxml.jackson.core.type.TypeReference;
import com.github.klboke.kkrepo.persistence.jdbc.api.ConanRegistryDao;
import com.github.klboke.kkrepo.persistence.jdbc.internal.support.HashColumns;
import com.github.klboke.kkrepo.persistence.jdbc.internal.support.JdbcUpserts;
import com.github.klboke.kkrepo.persistence.jdbc.internal.support.JsonColumns;
import com.github.klboke.kkrepo.persistence.jdbc.spi.DatabaseDialect;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** Portable MySQL/PostgreSQL implementation of the indexed Conan 2 persistence contract. */
@Repository
public class JdbcConanRegistryDao implements ConanRegistryDao {
  private static final int MAX_PAGE = 10_000;
  private static final TypeReference<Map<String, String>> STRING_MAP = new TypeReference<>() {};

  private final JdbcTemplate jdbc;
  private final JsonColumns json;
  private final DatabaseDialect dialect;

  private final RowMapper<Recipe> recipeMapper = (rs, row) -> new Recipe(
      rs.getLong("id"),
      rs.getLong("repository_id"),
      nullableLong(rs, "component_id"),
      rs.getString("name_key"),
      rs.getString("version_key"),
      optionalKey(rs.getString("user_key")),
      optionalKey(rs.getString("channel_key")),
      nullableLong(rs, "latest_recipe_revision_id"),
      nullableInstant(rs, "created_at"),
      nullableInstant(rs, "updated_at"));

  private final RowMapper<RecipeRevision> recipeRevisionMapper = (rs, row) -> new RecipeRevision(
      rs.getLong("id"),
      rs.getLong("recipe_id"),
      rs.getString("rrev"),
      rs.getString("manifest_sha256"),
      rs.getString("source_kind"),
      rs.getString("status"),
      rs.getLong("repository_revision"),
      nullableInstant(rs, "published_at"),
      nullableInstant(rs, "created_at"));

  private final RowMapper<Package> packageMapper = (rs, row) -> new Package(
      rs.getLong("id"),
      rs.getLong("recipe_revision_id"),
      rs.getString("package_id"),
      stringMap(rs.getString("settings_json")),
      stringMap(rs.getString("options_json")),
      stringMap(rs.getString("requires_json")),
      nullableLong(rs, "latest_package_revision_id"),
      nullableInstant(rs, "created_at"),
      nullableInstant(rs, "updated_at"));

  private final RowMapper<PackageRevision> packageRevisionMapper = (rs, row) ->
      new PackageRevision(
          rs.getLong("id"),
          rs.getLong("conan_package_id"),
          rs.getString("prev_value"),
          rs.getString("manifest_sha256"),
          rs.getString("source_kind"),
          rs.getString("status"),
          rs.getLong("repository_revision"),
          nullableInstant(rs, "published_at"),
          nullableInstant(rs, "created_at"));

  private final RowMapper<RevisionFile> fileMapper = (rs, row) -> new RevisionFile(
      rs.getLong("id"),
      rs.getString("owner_kind"),
      rs.getLong("owner_id"),
      rs.getString("path_value"),
      nullableLong(rs, "asset_id"),
      rs.getString("md5"),
      rs.getString("sha1"),
      rs.getString("sha256"),
      rs.getLong("size_bytes"),
      rs.getString("content_type"),
      nullableLong(rs, "source_repository_id"),
      nullableInstant(rs, "created_at"),
      nullableInstant(rs, "updated_at"));

  private final RowMapper<UploadSession> uploadSessionMapper = (rs, row) -> new UploadSession(
      rs.getLong("id"),
      rs.getLong("repository_id"),
      rs.getString("owner_kind"),
      rs.getString("coordinate_key"),
      rs.getString("actor_key"),
      rs.getString("status"),
      rs.getString("owner"),
      rs.getLong("fencing_token"),
      nullableInstant(rs, "lease_until"),
      nullableInstant(rs, "expires_at"),
      nullableInstant(rs, "created_at"),
      nullableInstant(rs, "updated_at"));

  private final RowMapper<UploadFile> uploadFileMapper = (rs, row) -> new UploadFile(
      rs.getLong("id"),
      rs.getLong("session_id"),
      rs.getString("path_value"),
      rs.getLong("staging_asset_id"),
      rs.getString("md5"),
      rs.getString("sha1"),
      rs.getString("sha256"),
      rs.getLong("size_bytes"),
      rs.getString("content_type"),
      nullableInstant(rs, "created_at"),
      nullableInstant(rs, "updated_at"));

  public JdbcConanRegistryDao(
      JdbcTemplate jdbc, JsonColumns json, DatabaseDialect dialect) {
    this.jdbc = jdbc;
    this.json = json;
    this.dialect = dialect;
  }

  @Override
  @Transactional
  public long nextRepositoryRevision(long repositoryId) {
    JdbcUpserts.updateThenInsert(
        jdbc,
        "UPDATE conan_repository_state SET revision = revision WHERE repository_id = ?",
        new Object[] {repositoryId},
        "INSERT INTO conan_repository_state (repository_id, revision) VALUES (?, 0)",
        new Object[] {repositoryId});
    jdbc.update("""
        UPDATE conan_repository_state
        SET revision = revision + 1, updated_at = CURRENT_TIMESTAMP
        WHERE repository_id = ?
        """, repositoryId);
    long revision = currentRepositoryRevision(repositoryId);
    deleteGroupBindingsForMember(repositoryId);
    return revision;
  }

  @Override
  public long currentRepositoryRevision(long repositoryId) {
    List<Long> values = jdbc.queryForList(
        "SELECT revision FROM conan_repository_state WHERE repository_id = ?",
        Long.class,
        repositoryId);
    return values.isEmpty() ? 0L : values.get(0);
  }

  @Override
  public Optional<Recipe> findRecipe(RecipeCoordinate coordinate) {
    requireCoordinate(coordinate);
    List<Recipe> rows = jdbc.query("""
        SELECT * FROM conan_recipe
        WHERE repository_id = ? AND coordinate_hash = ?
        """, recipeMapper, coordinate.repositoryId(), coordinateHash(coordinate));
    return rows.stream().filter(row -> sameCoordinate(row, coordinate)).findFirst();
  }

  @Override
  public Optional<Recipe> findRecipeByComponent(long repositoryId, long componentId) {
    if (repositoryId <= 0 || componentId <= 0) return Optional.empty();
    return jdbc.query("""
        SELECT * FROM conan_recipe
        WHERE repository_id = ? AND component_id = ?
        """, recipeMapper, repositoryId, componentId).stream().findFirst();
  }

  @Override
  public Optional<RecipeRevision> findRecipeRevision(long recipeId, String revision) {
    requireText(revision, "recipe revision");
    return jdbc.query("""
        SELECT * FROM conan_recipe_revision
        WHERE recipe_id = ? AND rrev_hash = ?
        """, recipeRevisionMapper, recipeId, hash(revision)).stream()
        .filter(row -> revision.equals(row.revision()))
        .findFirst();
  }

  @Override
  public Optional<RecipeRevision> findLatestRecipeRevision(long recipeId) {
    return jdbc.query("""
        SELECT rr.*
        FROM conan_recipe r
        JOIN conan_recipe_revision rr ON rr.id = r.latest_recipe_revision_id
        WHERE r.id = ?
        """, recipeRevisionMapper, recipeId).stream().findFirst();
  }

  @Override
  public List<RecipeRevision> listRecipeRevisions(long recipeId, Long afterId, int limit) {
    return jdbc.query("""
        SELECT * FROM conan_recipe_revision
        WHERE recipe_id = ? AND id > ?
        ORDER BY id
        LIMIT ?
        """, recipeRevisionMapper, recipeId, after(afterId), page(limit));
  }

  @Override
  public List<Recipe> searchRecipes(
      long repositoryId, String pattern, boolean ignoreCase, Long afterId, int limit) {
    int requested = page(limit);
    String prefix = namePrefix(pattern);
    if (ignoreCase) prefix = prefix.toLowerCase(Locale.ROOT);
    String namePattern = recipeNamePattern(pattern);
    if (ignoreCase && namePattern != null) namePattern = namePattern.toLowerCase(Locale.ROOT);
    int candidateLimit = pattern == null || pattern.isBlank() ? requested : MAX_PAGE;
    List<Recipe> candidates;
    if (namePattern != null && firstWildcard(namePattern) < 0) {
      candidates = jdbc.query("""
          SELECT * FROM conan_recipe
          WHERE repository_id = ? AND name_key = ? AND id > ?
          ORDER BY id
          LIMIT ?
          """, recipeMapper, repositoryId, namePattern, after(afterId), candidateLimit);
    } else if (prefix.isEmpty()) {
      candidates = jdbc.query("""
            SELECT * FROM conan_recipe
            WHERE repository_id = ? AND id > ?
            ORDER BY id
            LIMIT ?
            """, recipeMapper, repositoryId, after(afterId), candidateLimit);
    } else {
      candidates = jdbc.query(dialect.conan().recipeNameRangeSql(), recipeMapper,
            repositoryId, prefix, prefixUpperBound(prefix),
            after(afterId), candidateLimit);
    }
    if (pattern == null || pattern.isBlank()) return candidates.stream().limit(requested).toList();
    Pattern matcher = wildcard(pattern, ignoreCase);
    return candidates.stream()
        .filter(recipe -> matcher.matcher(reference(recipe)).matches())
        .limit(requested)
        .toList();
  }

  @Override
  public Optional<Package> findPackage(long recipeRevisionId, String packageId) {
    requireText(packageId, "package id");
    return jdbc.query("""
        SELECT * FROM conan_package
        WHERE recipe_revision_id = ? AND package_id_hash = ?
        """, packageMapper, recipeRevisionId, hash(packageId)).stream()
        .filter(row -> packageId.equals(row.packageId()))
        .findFirst();
  }

  @Override
  public List<Package> listPackages(long recipeRevisionId, Long afterId, int limit) {
    return jdbc.query("""
        SELECT * FROM conan_package
        WHERE recipe_revision_id = ? AND id > ?
        ORDER BY id
        LIMIT ?
        """, packageMapper, recipeRevisionId, after(afterId), page(limit));
  }

  @Override
  public Optional<PackageRevision> findPackageRevision(long packageRowId, String revision) {
    requireText(revision, "package revision");
    return jdbc.query("""
        SELECT * FROM conan_package_revision
        WHERE conan_package_id = ? AND prev_hash = ?
        """, packageRevisionMapper, packageRowId, hash(revision)).stream()
        .filter(row -> revision.equals(row.revision()))
        .findFirst();
  }

  @Override
  public Optional<PackageRevision> findLatestPackageRevision(long packageRowId) {
    return jdbc.query("""
        SELECT pr.*
        FROM conan_package p
        JOIN conan_package_revision pr ON pr.id = p.latest_package_revision_id
        WHERE p.id = ?
        """, packageRevisionMapper, packageRowId).stream().findFirst();
  }

  @Override
  public List<PackageRevision> listPackageRevisions(
      long packageRowId, Long afterId, int limit) {
    return jdbc.query("""
        SELECT * FROM conan_package_revision
        WHERE conan_package_id = ? AND id > ?
        ORDER BY id
        LIMIT ?
        """, packageRevisionMapper, packageRowId, after(afterId), page(limit));
  }

  @Override
  public List<RevisionFile> listFiles(
      String ownerKind, long ownerId, Long afterId, int limit) {
    requireOwnerKind(ownerKind);
    return jdbc.query("""
        SELECT * FROM conan_revision_file
        WHERE owner_kind = ? AND owner_id = ? AND id > ?
        ORDER BY id
        LIMIT ?
        """, fileMapper, ownerKind, ownerId, after(afterId), page(limit));
  }

  @Override
  public Optional<RevisionFile> findFile(String ownerKind, long ownerId, String path) {
    requireOwnerKind(ownerKind);
    requireText(path, "file path");
    return jdbc.query("""
        SELECT * FROM conan_revision_file
        WHERE owner_kind = ? AND owner_id = ? AND path_hash = ?
        """, fileMapper, ownerKind, ownerId, hash(path)).stream()
        .filter(row -> path.equals(row.path()))
        .findFirst();
  }

  @Override
  public Optional<AssetFile> findFileByAssetId(long assetId) {
    if (assetId <= 0) return Optional.empty();
    Optional<RevisionFile> file = jdbc.query(
        "SELECT * FROM conan_revision_file WHERE asset_id = ?",
        fileMapper,
        assetId).stream().findFirst();
    if (file.isEmpty()) return Optional.empty();
    RevisionFile value = file.orElseThrow();
    if (OWNER_RECIPE.equals(value.ownerKind())) {
      return jdbc.query("""
          SELECT r.repository_id, r.name_key, r.version_key, r.user_key, r.channel_key, rr.rrev
          FROM conan_recipe_revision rr
          JOIN conan_recipe r ON r.id = rr.recipe_id
          WHERE rr.id = ?
          """, (rs, row) -> new AssetFile(
              value,
              new RecipeCoordinate(
                  rs.getLong("repository_id"), rs.getString("name_key"),
                  rs.getString("version_key"), optionalKey(rs.getString("user_key")),
                  optionalKey(rs.getString("channel_key"))),
              rs.getString("rrev"), null, null), value.ownerId()).stream().findFirst();
    }
    return jdbc.query("""
        SELECT r.repository_id, r.name_key, r.version_key, r.user_key, r.channel_key,
               rr.rrev, p.package_id, pr.prev_value
        FROM conan_package_revision pr
        JOIN conan_package p ON p.id = pr.conan_package_id
        JOIN conan_recipe_revision rr ON rr.id = p.recipe_revision_id
        JOIN conan_recipe r ON r.id = rr.recipe_id
        WHERE pr.id = ?
        """, (rs, row) -> new AssetFile(
            value,
            new RecipeCoordinate(
                rs.getLong("repository_id"), rs.getString("name_key"),
                rs.getString("version_key"), optionalKey(rs.getString("user_key")),
                optionalKey(rs.getString("channel_key"))),
            rs.getString("rrev"), rs.getString("package_id"), rs.getString("prev_value")),
        value.ownerId()).stream().findFirst();
  }

  @Override
  public Optional<PackageScanContext> findPackageScanContext(long packageAssetId) {
    return findFileByAssetId(packageAssetId)
        .filter(asset -> OWNER_PACKAGE.equals(asset.file().ownerKind()))
        .flatMap(asset -> {
          RevisionFile archive = listFiles(OWNER_PACKAGE, asset.file().ownerId(), null, MAX_PAGE)
              .stream()
              .filter(file -> packageArchive(file.path()))
              .findFirst()
              .orElse(null);
          RevisionFile info = findFile(
              OWNER_PACKAGE, asset.file().ownerId(), "conaninfo.txt").orElse(null);
          if (archive == null || archive.assetId() == null || info == null) return Optional.empty();
          return findFileByAssetId(archive.assetId())
              .map(archiveIdentity -> new PackageScanContext(archiveIdentity, info));
        });
  }

  @Override
  @Transactional
  public CommittedRevision commitRevision(RevisionCommit commit) {
    validateCommit(commit, true);
    return persistRevision(commit);
  }

  @Override
  @Transactional
  public CommittedRevision restoreRevision(RevisionCommit commit) {
    validateCommit(commit, false);
    boolean hosted = SOURCE_HOSTED.equals(commit.sourceKind())
        && STATUS_COMMITTED.equals(commit.status());
    boolean proxy = SOURCE_PROXY.equals(commit.sourceKind())
        && STATUS_DISCOVERED.equals(commit.status());
    if ((!hosted && !proxy) || commit.files().isEmpty()) {
      throw new IllegalArgumentException(
          "Restored Conan revision must be a complete hosted or proxy snapshot");
    }
    return persistRevision(commit);
  }

  @Override
  @Transactional
  public CommittedRevision recordDiscoveredRevision(RevisionCommit discovery) {
    validateCommit(discovery, false);
    if (!SOURCE_PROXY.equals(discovery.sourceKind())
        || !STATUS_DISCOVERED.equals(discovery.status())) {
      throw new IllegalArgumentException("Conan discovery must have PROXY source kind");
    }
    return persistRevision(discovery);
  }

  private CommittedRevision persistRevision(RevisionCommit commit) {
    Recipe recipe = upsertRecipe(commit.coordinate(), commit.componentId());
    RecipeRevision recipeRevision = findRecipeRevision(recipe.id(), commit.recipeRevision())
        .orElse(null);
    boolean insertedRecipeRevision = false;
    long repositoryRevision;
    if (recipeRevision == null) {
      if (OWNER_PACKAGE.equals(commit.ownerKind())
          && SOURCE_HOSTED.equals(commit.sourceKind())) {
        throw new IllegalStateException(
            "Conan package publication requires an existing recipe revision");
      }
      repositoryRevision = nextRepositoryRevision(commit.coordinate().repositoryId());
      insertRecipeRevision(
          recipe.id(), commit, repositoryRevision,
          OWNER_RECIPE.equals(commit.ownerKind()) ? commit.manifestSha256() : null);
      recipeRevision = findRecipeRevision(recipe.id(), commit.recipeRevision()).orElseThrow();
      insertedRecipeRevision = true;
    } else if (OWNER_RECIPE.equals(commit.ownerKind())) {
      assertCompatible(
          "recipe revision", recipeRevision.manifestSha256(), commit.manifestSha256(),
          recipeRevision.sourceKind(), commit.sourceKind());
      repositoryRevision = recipeRevision.repositoryRevision();
    } else {
      if (!Objects.equals(recipeRevision.sourceKind(), commit.sourceKind())) {
        throw new IllegalStateException(
            "Conan package source differs from its recipe revision source");
      }
      repositoryRevision = recipeRevision.repositoryRevision();
    }

    if (OWNER_RECIPE.equals(commit.ownerKind())) {
      if (!insertedRecipeRevision && !commit.files().isEmpty()) {
        assertIdempotentFiles(OWNER_RECIPE, recipeRevision.id(), commit.files());
      } else {
        insertFiles(OWNER_RECIPE, recipeRevision.id(), commit.files());
      }
      if (insertedRecipeRevision) refreshLatestRecipe(recipe.id());
      return new CommittedRevision(
          recipe.id(), recipeRevision.id(), null, null, recipeRevision.id(),
          repositoryRevision, !insertedRecipeRevision);
    }

    Package conanPackage = upsertPackage(recipeRevision.id(), commit);
    PackageRevision packageRevision = findPackageRevision(
        conanPackage.id(), commit.packageRevision()).orElse(null);
    boolean insertedPackageRevision = false;
    if (packageRevision == null) {
      if (!insertedRecipeRevision) {
        repositoryRevision = nextRepositoryRevision(commit.coordinate().repositoryId());
      }
      insertPackageRevision(conanPackage.id(), commit, repositoryRevision);
      packageRevision = findPackageRevision(
          conanPackage.id(), commit.packageRevision()).orElseThrow();
      insertedPackageRevision = true;
    } else {
      assertCompatible(
          "package revision", packageRevision.manifestSha256(), commit.manifestSha256(),
          packageRevision.sourceKind(), commit.sourceKind());
      repositoryRevision = packageRevision.repositoryRevision();
    }
    if (!insertedPackageRevision && !commit.files().isEmpty()) {
      assertIdempotentFiles(OWNER_PACKAGE, packageRevision.id(), commit.files());
    } else {
      insertFiles(OWNER_PACKAGE, packageRevision.id(), commit.files());
    }
    if (insertedPackageRevision) refreshLatestPackage(conanPackage.id());
    if (insertedRecipeRevision) refreshLatestRecipe(recipe.id());
    return new CommittedRevision(
        recipe.id(), recipeRevision.id(), conanPackage.id(), packageRevision.id(),
        packageRevision.id(), repositoryRevision, !insertedPackageRevision);
  }

  @Override
  @Transactional
  public RevisionFile bindDiscoveredFile(
      String ownerKind,
      long ownerId,
      FileCommit file,
      long expectedRepositoryRevision) {
    long repositoryId = ownerRepositoryId(ownerKind, ownerId);
    if (currentRepositoryRevision(repositoryId) != expectedRepositoryRevision) {
      throw new IllegalStateException("Conan proxy file observation was fenced by a newer revision");
    }
    upsertFile(ownerKind, ownerId, file);
    nextRepositoryRevision(repositoryId);
    return findFile(ownerKind, ownerId, file.path()).orElseThrow();
  }

  @Override
  @Transactional
  public RevisionFile upsertMetadataFile(
      String ownerKind, long ownerId, FileCommit file, long repositoryId) {
    if (ownerRepositoryId(ownerKind, ownerId) != repositoryId) {
      throw new IllegalArgumentException("Conan metadata owner does not belong to repository");
    }
    upsertFile(ownerKind, ownerId, file);
    nextRepositoryRevision(repositoryId);
    return findFile(ownerKind, ownerId, file.path()).orElseThrow();
  }

  @Override
  @Transactional
  public DeletedCoordinate deleteCoordinate(
      DeleteTarget target, String reason, Instant deletedAt) {
    Objects.requireNonNull(target, "delete target");
    Optional<Recipe> found = findRecipe(target.coordinate());
    if (found.isEmpty()) return new DeletedCoordinate(false, List.of(), List.of(), 0);
    Recipe recipe = found.orElseThrow();
    LinkedHashSet<Long> assets = new LinkedHashSet<>();
    LinkedHashSet<Long> components = new LinkedHashSet<>();
    if (recipe.componentId() != null) components.add(recipe.componentId());

    if (target.recipeRevision() == null) {
      collectRecipeAssets(recipe.id(), assets);
      deleteRecipeFiles(recipe.id());
      jdbc.update("DELETE FROM conan_recipe WHERE id = ?", recipe.id());
    } else {
      RecipeRevision rrev = findRecipeRevision(recipe.id(), target.recipeRevision()).orElse(null);
      if (rrev == null) return new DeletedCoordinate(false, List.of(), List.of(), 0);
      if (target.packageId() == null) {
        collectRevisionAssets(rrev.id(), assets);
        deleteRevisionFiles(rrev.id());
        jdbc.update("DELETE FROM conan_recipe_revision WHERE id = ?", rrev.id());
        if (hasRows("conan_recipe_revision", "recipe_id", recipe.id())) {
          refreshLatestRecipe(recipe.id());
        } else {
          jdbc.update("DELETE FROM conan_recipe WHERE id = ?", recipe.id());
        }
      } else {
        Package pkg = findPackage(rrev.id(), target.packageId()).orElse(null);
        if (pkg == null) return new DeletedCoordinate(false, List.of(), List.of(), 0);
        if (target.packageRevision() == null) {
          collectPackageAssets(pkg.id(), assets);
          deletePackageFiles(pkg.id());
          jdbc.update("DELETE FROM conan_package WHERE id = ?", pkg.id());
        } else {
          PackageRevision prev = findPackageRevision(pkg.id(), target.packageRevision()).orElse(null);
          if (prev == null) return new DeletedCoordinate(false, List.of(), List.of(), 0);
          collectOwnerAssets(OWNER_PACKAGE, List.of(prev.id()), assets);
          deleteOwnerFiles(OWNER_PACKAGE, List.of(prev.id()));
          jdbc.update("DELETE FROM conan_package_revision WHERE id = ?", prev.id());
          if (hasRows("conan_package_revision", "conan_package_id", pkg.id())) {
            refreshLatestPackage(pkg.id());
          } else {
            jdbc.update("DELETE FROM conan_package WHERE id = ?", pkg.id());
          }
        }
      }
    }
    long revision = nextRepositoryRevision(target.coordinate().repositoryId());
    return new DeletedCoordinate(true, List.copyOf(assets), List.copyOf(components), revision);
  }

  @Override
  @Transactional
  public DeletedCoordinate deleteAllPackages(
      RecipeCoordinate coordinate, String recipeRevision, String reason, Instant deletedAt) {
    requireCoordinate(coordinate);
    requireText(recipeRevision, "recipe revision");
    Recipe recipe = findRecipe(coordinate).orElse(null);
    if (recipe == null) return new DeletedCoordinate(false, List.of(), List.of(), 0);
    RecipeRevision revision = findRecipeRevision(recipe.id(), recipeRevision).orElse(null);
    if (revision == null) return new DeletedCoordinate(false, List.of(), List.of(), 0);
    LinkedHashSet<Long> assetIds = new LinkedHashSet<>();
    List<Long> packageIds = jdbc.queryForList(
        "SELECT id FROM conan_package WHERE recipe_revision_id = ? ORDER BY id",
        Long.class,
        revision.id());
    for (Long packageId : packageIds) collectPackageAssets(packageId, assetIds);
    if (packageIds.isEmpty()) {
      return new DeletedCoordinate(
          false, List.of(), List.of(), currentRepositoryRevision(coordinate.repositoryId()));
    }
    for (Long packageId : packageIds) deletePackageFiles(packageId);
    jdbc.update(
        "DELETE FROM conan_package WHERE id IN (" + placeholders(packageIds.size()) + ")",
        packageIds.toArray());
    long repositoryRevision = nextRepositoryRevision(coordinate.repositoryId());
    return new DeletedCoordinate(true, List.copyOf(assetIds), List.of(), repositoryRevision);
  }

  @Override
  @Transactional
  public UploadSession openUploadSession(UploadSession candidate) {
    validateSession(candidate);
    byte[] coordinateHash = hash(candidate.coordinateKey());
    byte[] actorHash = hash(candidate.actorKey());
    Instant now = candidate.updatedAt() == null ? Instant.now() : candidate.updatedAt();
    JdbcUpserts.updateThenInsert(
        jdbc,
        """
        UPDATE conan_upload_session
        SET coordinate_key = ?, actor_key = ?, owner = ?, expires_at = ?, updated_at = ?
        WHERE repository_id = ? AND owner_kind = ?
          AND coordinate_hash = ? AND actor_hash = ?
        """,
        new Object[] {
          candidate.coordinateKey(), candidate.actorKey(), candidate.owner(),
          timestamp(candidate.expiresAt()), timestamp(now), candidate.repositoryId(),
          candidate.ownerKind(), coordinateHash, actorHash
        },
        """
        INSERT INTO conan_upload_session
          (repository_id, owner_kind, coordinate_key, coordinate_hash, actor_key, actor_hash,
           status, owner, fencing_token, lease_until, expires_at, created_at, updated_at)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """,
        new Object[] {
          candidate.repositoryId(), candidate.ownerKind(), candidate.coordinateKey(),
          coordinateHash, candidate.actorKey(), actorHash, SESSION_OPEN, candidate.owner(),
          Math.max(0, candidate.fencingToken()), timestamp(candidate.leaseUntil()),
          timestamp(candidate.expiresAt()), timestamp(now), timestamp(now)
        });
    UploadSession stored = findUploadSession(
        candidate.repositoryId(), candidate.ownerKind(), candidate.coordinateKey(),
        candidate.actorKey()).orElseThrow();
    if (!stored.coordinateKey().equals(candidate.coordinateKey())
        || !stored.actorKey().equals(candidate.actorKey())) {
      throw new IllegalStateException("Conan upload session hash collision");
    }
    return stored;
  }

  @Override
  public Optional<UploadSession> findUploadSession(long sessionId) {
    return jdbc.query(
        "SELECT * FROM conan_upload_session WHERE id = ?",
        uploadSessionMapper,
        sessionId).stream().findFirst();
  }

  @Override
  public Optional<UploadSession> findUploadSession(
      long repositoryId, String ownerKind, String coordinateKey, String actorKey) {
    requireOwnerKind(ownerKind);
    return jdbc.query("""
        SELECT * FROM conan_upload_session
        WHERE repository_id = ? AND owner_kind = ?
          AND coordinate_hash = ? AND actor_hash = ?
        """, uploadSessionMapper, repositoryId, ownerKind, hash(coordinateKey), hash(actorKey))
        .stream()
        .filter(row -> coordinateKey.equals(row.coordinateKey()) && actorKey.equals(row.actorKey()))
        .findFirst();
  }

  @Override
  @Transactional
  public UploadFile upsertUploadFile(UploadFile file) {
    validateUploadFile(file);
    Instant now = file.updatedAt() == null ? Instant.now() : file.updatedAt();
    JdbcUpserts.updateThenInsert(
        jdbc,
        """
        UPDATE conan_upload_file
        SET path_value = ?, staging_asset_id = ?, md5 = ?, sha1 = ?, sha256 = ?,
            size_bytes = ?, content_type = ?, updated_at = ?
        WHERE session_id = ? AND path_hash = ?
        """,
        new Object[] {
          file.path(), file.stagingAssetId(), file.md5(), file.sha1(), file.sha256(),
          file.size(), file.contentType(), timestamp(now), file.sessionId(), hash(file.path())
        },
        """
        INSERT INTO conan_upload_file
          (session_id, path_value, path_hash, staging_asset_id, md5, sha1, sha256,
           size_bytes, content_type, created_at, updated_at)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """,
        new Object[] {
          file.sessionId(), file.path(), hash(file.path()), file.stagingAssetId(), file.md5(),
          file.sha1(), file.sha256(), file.size(), file.contentType(), timestamp(now), timestamp(now)
        });
    return jdbc.query("""
        SELECT * FROM conan_upload_file
        WHERE session_id = ? AND path_hash = ?
        """, uploadFileMapper, file.sessionId(), hash(file.path())).stream()
        .filter(row -> file.path().equals(row.path()))
        .findFirst().orElseThrow();
  }

  @Override
  public List<UploadFile> listUploadFiles(long sessionId) {
    return jdbc.query("""
        SELECT * FROM conan_upload_file
        WHERE session_id = ?
        ORDER BY id
        """, uploadFileMapper, sessionId);
  }

  @Override
  public boolean beginSessionCommit(long sessionId, long fencingToken, Instant leaseUntil) {
    return jdbc.update("""
        UPDATE conan_upload_session
        SET status = ?, fencing_token = ?, lease_until = ?, updated_at = CURRENT_TIMESTAMP
        WHERE id = ? AND status = ? AND fencing_token <= ?
        """, SESSION_COMMITTING, fencingToken, timestamp(leaseUntil), sessionId, SESSION_OPEN,
        fencingToken) == 1;
  }

  @Override
  public int deleteUploadSession(long sessionId) {
    return jdbc.update("DELETE FROM conan_upload_session WHERE id = ?", sessionId);
  }

  @Override
  public boolean deleteClaimedUploadSession(
      long sessionId, String owner, long fencingToken) {
    requireText(owner, "cleanup owner");
    return jdbc.update("""
        DELETE FROM conan_upload_session
        WHERE id = ? AND owner = ? AND fencing_token = ?
        """, sessionId, owner, fencingToken) == 1;
  }

  @Override
  @Transactional
  public List<UploadSession> claimExpiredUploadSessions(
      String owner, Instant now, Instant leaseUntil, int limit) {
    requireText(owner, "claim owner");
    List<UploadSession> rows = jdbc.query("""
        SELECT * FROM conan_upload_session
        WHERE expires_at <= ? AND (lease_until IS NULL OR lease_until <= ?)
        ORDER BY id
        LIMIT ?
        FOR UPDATE SKIP LOCKED
        """, uploadSessionMapper, timestamp(now), timestamp(now), page(limit));
    for (UploadSession row : rows) {
      jdbc.update("""
          UPDATE conan_upload_session
          SET owner = ?, fencing_token = fencing_token + 1, lease_until = ?,
              updated_at = CURRENT_TIMESTAMP
          WHERE id = ?
          """, owner, timestamp(leaseUntil), row.id());
    }
    return rows.stream().map(row -> new UploadSession(
        row.id(), row.repositoryId(), row.ownerKind(), row.coordinateKey(), row.actorKey(),
        row.status(), owner, row.fencingToken() + 1, leaseUntil, row.expiresAt(),
        row.createdAt(), now)).toList();
  }

  @Override
  @Transactional
  public Optional<Lease> tryAcquireLease(
      long repositoryId, String coordinateKey, String owner, Instant expiresAt) {
    requireText(coordinateKey, "lease coordinate");
    requireText(owner, "lease owner");
    byte[] coordinateHash = hash(coordinateKey);
    Instant now = Instant.now();
    List<Lease> existing = lockedLease(repositoryId, coordinateHash);
    if (existing.isEmpty()) {
      try {
        jdbc.update("""
            INSERT INTO conan_coordinate_lease
              (repository_id, coordinate_key, coordinate_hash, owner, fencing_token,
               expires_at, updated_at)
            VALUES (?, ?, ?, ?, 1, ?, ?)
            """, repositoryId, coordinateKey, coordinateHash, owner, timestamp(expiresAt),
            timestamp(now));
        return Optional.of(new Lease(repositoryId, coordinateKey, owner, 1, expiresAt, now));
      } catch (DuplicateKeyException race) {
        existing = lockedLease(repositoryId, coordinateHash);
      }
    }
    if (existing.isEmpty()) return Optional.empty();
    Lease current = existing.get(0);
    if (!coordinateKey.equals(current.coordinateKey())) {
      throw new IllegalStateException("Conan coordinate lease hash collision");
    }
    if (current.expiresAt().isAfter(now) && !owner.equals(current.owner())) {
      return Optional.empty();
    }
    long token = current.fencingToken() + 1;
    jdbc.update("""
        UPDATE conan_coordinate_lease
        SET owner = ?, fencing_token = ?, expires_at = ?, updated_at = ?
        WHERE repository_id = ? AND coordinate_hash = ?
        """, owner, token, timestamp(expiresAt), timestamp(now), repositoryId, coordinateHash);
    return Optional.of(new Lease(repositoryId, coordinateKey, owner, token, expiresAt, now));
  }

  @Override
  public boolean renewLease(
      long repositoryId,
      String coordinateKey,
      String owner,
      long fencingToken,
      Instant expiresAt) {
    return jdbc.update("""
        UPDATE conan_coordinate_lease
        SET expires_at = ?, updated_at = CURRENT_TIMESTAMP
        WHERE repository_id = ? AND coordinate_hash = ? AND coordinate_key = ?
          AND owner = ? AND fencing_token = ? AND expires_at > CURRENT_TIMESTAMP
        """, timestamp(expiresAt), repositoryId, hash(coordinateKey), coordinateKey, owner,
        fencingToken) == 1;
  }

  @Override
  public void releaseLease(
      long repositoryId, String coordinateKey, String owner, long fencingToken) {
    // A fixed expired value avoids a database/JVM clock edge making an immediate reacquire look
    // busy, while retaining the row and monotonically increasing fencing token.
    jdbc.update("""
        UPDATE conan_coordinate_lease
        SET expires_at = ?, updated_at = CURRENT_TIMESTAMP
        WHERE repository_id = ? AND coordinate_hash = ? AND coordinate_key = ?
          AND owner = ? AND fencing_token = ?
        """, timestamp(Instant.EPOCH), repositoryId, hash(coordinateKey), coordinateKey, owner,
        fencingToken);
  }

  @Override
  @Transactional
  public int deleteExpiredLeases(Instant now, int limit) {
    Instant cutoff = now == null ? Instant.now() : now;
    List<Lease> rows = jdbc.query("""
        SELECT * FROM conan_coordinate_lease
        WHERE expires_at <= ?
        ORDER BY expires_at, repository_id, coordinate_hash
        LIMIT ?
        FOR UPDATE SKIP LOCKED
        """, (rs, row) -> new Lease(
            rs.getLong("repository_id"), rs.getString("coordinate_key"), rs.getString("owner"),
            rs.getLong("fencing_token"), nullableInstant(rs, "expires_at"),
            nullableInstant(rs, "updated_at")), timestamp(cutoff), page(limit));
    int deleted = 0;
    for (Lease row : rows) {
      deleted += jdbc.update("""
          DELETE FROM conan_coordinate_lease
          WHERE repository_id = ? AND coordinate_hash = ? AND coordinate_key = ?
            AND owner = ? AND fencing_token = ? AND expires_at <= ?
          """, row.repositoryId(), hash(row.coordinateKey()), row.coordinateKey(), row.owner(),
          row.fencingToken(), timestamp(cutoff));
    }
    return deleted;
  }

  @Override
  public Optional<GroupBinding> findGroupBinding(
      long groupRepositoryId, String bindingKind, String coordinateKey) {
    requireOwnerKind(bindingKind);
    return jdbc.query("""
        SELECT * FROM conan_group_binding
        WHERE group_repository_id = ? AND binding_kind = ? AND coordinate_hash = ?
        """, (rs, row) -> new GroupBinding(
            rs.getLong("group_repository_id"),
            rs.getString("binding_kind"),
            rs.getString("coordinate_key"),
            rs.getLong("member_repository_id"),
            rs.getLong("member_owner_id"),
            rs.getLong("member_revision"),
            rs.getLong("group_config_revision"),
            nullableInstant(rs, "expires_at"),
            nullableInstant(rs, "bound_at"),
            nullableInstant(rs, "updated_at")),
        groupRepositoryId, bindingKind, hash(coordinateKey)).stream()
        .filter(binding -> coordinateKey.equals(binding.coordinateKey()))
        .filter(binding -> binding.expiresAt() == null || binding.expiresAt().isAfter(Instant.now()))
        .findFirst();
  }

  @Override
  @Transactional
  public boolean upsertGroupBindingIfCurrent(GroupBinding binding) {
    Objects.requireNonNull(binding, "group binding");
    if (currentRepositoryRevision(binding.groupRepositoryId()) != binding.groupConfigRevision()) {
      return false;
    }
    if (currentRepositoryRevision(binding.memberRepositoryId()) != binding.memberRevision()) {
      return false;
    }
    Optional<GroupBinding> existing = findGroupBinding(
        binding.groupRepositoryId(), binding.bindingKind(), binding.coordinateKey());
    if (existing.isPresent()
        && existing.orElseThrow().groupConfigRevision() == binding.groupConfigRevision()) {
      return true;
    }
    Instant now = binding.updatedAt() == null ? Instant.now() : binding.updatedAt();
    JdbcUpserts.updateThenInsert(
        jdbc,
        """
        UPDATE conan_group_binding
        SET coordinate_key = ?, member_repository_id = ?, member_owner_id = ?,
            member_revision = ?, group_config_revision = ?, expires_at = ?, bound_at = ?,
            updated_at = ?
        WHERE group_repository_id = ? AND binding_kind = ? AND coordinate_hash = ?
        """,
        new Object[] {
          binding.coordinateKey(), binding.memberRepositoryId(), binding.memberOwnerId(),
          binding.memberRevision(), binding.groupConfigRevision(), timestamp(binding.expiresAt()),
          timestamp(binding.boundAt()), timestamp(now), binding.groupRepositoryId(),
          binding.bindingKind(), hash(binding.coordinateKey())
        },
        """
        INSERT INTO conan_group_binding
          (group_repository_id, binding_kind, coordinate_key, coordinate_hash,
           member_repository_id, member_owner_id, member_revision, group_config_revision,
           expires_at, bound_at, updated_at)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """,
        new Object[] {
          binding.groupRepositoryId(), binding.bindingKind(), binding.coordinateKey(),
          hash(binding.coordinateKey()), binding.memberRepositoryId(), binding.memberOwnerId(),
          binding.memberRevision(), binding.groupConfigRevision(), timestamp(binding.expiresAt()),
          timestamp(binding.boundAt()), timestamp(now)
        });
    return currentRepositoryRevision(binding.groupRepositoryId()) == binding.groupConfigRevision()
        && currentRepositoryRevision(binding.memberRepositoryId()) == binding.memberRevision();
  }

  @Override
  public int deleteGroupBindings(long groupRepositoryId) {
    return jdbc.update(
        "DELETE FROM conan_group_binding WHERE group_repository_id = ?", groupRepositoryId);
  }

  @Override
  public int deleteGroupBindingsForMember(long memberRepositoryId) {
    return jdbc.update(
        "DELETE FROM conan_group_binding WHERE member_repository_id = ?", memberRepositoryId);
  }

  @Override
  public void insertAuthToken(AuthToken token) {
    validateAuthToken(token);
    jdbc.update("""
        INSERT INTO conan_auth_token
          (token_hash, repository_id, subject_source, subject_user_id, realm_id, api_key_id,
           expires_at, last_used_at, created_at)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
        """, token.tokenHash(), token.repositoryId(), token.subjectSource(), token.subjectUserId(),
        token.realmId(), token.apiKeyId(), timestamp(token.expiresAt()),
        timestamp(token.lastUsedAt()), timestamp(token.createdAt()));
  }

  @Override
  public Optional<AuthToken> findValidAuthToken(
      String tokenHash, long repositoryId, Instant now) {
    return jdbc.query("""
        SELECT * FROM conan_auth_token
        WHERE token_hash = ? AND repository_id = ? AND expires_at > ?
        """, (rs, row) -> new AuthToken(
            rs.getString("token_hash"),
            rs.getLong("repository_id"),
            rs.getString("subject_source"),
            rs.getString("subject_user_id"),
            rs.getString("realm_id"),
            nullableLong(rs, "api_key_id"),
            nullableInstant(rs, "expires_at"),
            nullableInstant(rs, "last_used_at"),
            nullableInstant(rs, "created_at")),
        tokenHash, repositoryId, timestamp(now)).stream().findFirst();
  }

  @Override
  public int touchAuthToken(String tokenHash, Instant usedAt) {
    return jdbc.update(
        "UPDATE conan_auth_token SET last_used_at = ? WHERE token_hash = ?",
        timestamp(usedAt), tokenHash);
  }

  @Override
  @Transactional
  public int deleteExpiredAuthTokens(Instant now, int limit) {
    List<String> hashes = jdbc.queryForList("""
        SELECT token_hash FROM conan_auth_token
        WHERE expires_at <= ?
        ORDER BY expires_at, token_hash
        LIMIT ?
        """, String.class, timestamp(now), page(limit));
    return deleteByValues("conan_auth_token", "token_hash", hashes);
  }

  @Override
  @Transactional
  public int deleteRepositoryState(long repositoryId) {
    deleteGroupBindings(repositoryId);
    deleteGroupBindingsForMember(repositoryId);
    jdbc.update("DELETE FROM conan_auth_token WHERE repository_id = ?", repositoryId);
    jdbc.update("DELETE FROM conan_coordinate_lease WHERE repository_id = ?", repositoryId);
    jdbc.update("DELETE FROM conan_upload_session WHERE repository_id = ?", repositoryId);
    jdbc.update("DELETE FROM conan_recipe WHERE repository_id = ?", repositoryId);
    return jdbc.update(
        "DELETE FROM conan_repository_state WHERE repository_id = ?", repositoryId);
  }

  @Override
  public RepositoryStatus status(long repositoryId) {
    return new RepositoryStatus(
        currentRepositoryRevision(repositoryId),
        count("conan_recipe", "repository_id = ?", repositoryId),
        count("conan_recipe_revision rr JOIN conan_recipe r ON r.id = rr.recipe_id",
            "r.repository_id = ?", repositoryId),
        count("conan_package p JOIN conan_recipe_revision rr ON rr.id = p.recipe_revision_id "
                + "JOIN conan_recipe r ON r.id = rr.recipe_id",
            "r.repository_id = ?", repositoryId),
        count("conan_package_revision pr JOIN conan_package p ON p.id = pr.conan_package_id "
                + "JOIN conan_recipe_revision rr ON rr.id = p.recipe_revision_id "
                + "JOIN conan_recipe r ON r.id = rr.recipe_id",
            "r.repository_id = ?", repositoryId),
        count("conan_revision_file f", "f.source_repository_id = ? AND f.asset_id IS NOT NULL",
            repositoryId),
        count("conan_upload_session", "repository_id = ? AND status IN ('OPEN','COMMITTING')",
            repositoryId),
        cachedProxyFiles(repositoryId));
  }

  private long cachedProxyFiles(long repositoryId) {
    Long value = jdbc.queryForObject("""
        SELECT COUNT(*)
        FROM conan_revision_file f
        WHERE f.asset_id IS NOT NULL
          AND (
            (f.owner_kind = 'RECIPE' AND EXISTS (
              SELECT 1
              FROM conan_recipe_revision rr
              JOIN conan_recipe r ON r.id = rr.recipe_id
              WHERE rr.id = f.owner_id AND r.repository_id = ? AND rr.source_kind = 'PROXY'
            ))
            OR
            (f.owner_kind = 'PACKAGE' AND EXISTS (
              SELECT 1
              FROM conan_package_revision pr
              JOIN conan_package p ON p.id = pr.conan_package_id
              JOIN conan_recipe_revision rr ON rr.id = p.recipe_revision_id
              JOIN conan_recipe r ON r.id = rr.recipe_id
              WHERE pr.id = f.owner_id AND r.repository_id = ? AND pr.source_kind = 'PROXY'
            ))
          )
        """, Long.class, repositoryId, repositoryId);
    return value == null ? 0 : value;
  }

  private Recipe upsertRecipe(RecipeCoordinate coordinate, Long componentId) {
    Optional<Recipe> existing = findRecipe(coordinate);
    if (existing.isPresent()) {
      Recipe recipe = existing.orElseThrow();
      if (componentId != null && !componentId.equals(recipe.componentId())) {
        jdbc.update("""
            UPDATE conan_recipe
            SET component_id = ?, updated_at = CURRENT_TIMESTAMP
            WHERE id = ?
            """, componentId, recipe.id());
        return findRecipe(coordinate).orElseThrow();
      }
      return recipe;
    }
    try {
      jdbc.update("""
          INSERT INTO conan_recipe
            (repository_id, component_id, coordinate_hash, name_key, version_key,
             user_key, channel_key, created_at, updated_at)
          VALUES (?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
          """, coordinate.repositoryId(), componentId, coordinateHash(coordinate), coordinate.name(),
          coordinate.version(), key(coordinate.user()), key(coordinate.channel()));
    } catch (DuplicateKeyException ignored) {
      // A concurrent publisher won the exact coordinate. Canonical comparison below is decisive.
    }
    return findRecipe(coordinate).orElseThrow(() ->
        new IllegalStateException("Conan recipe hash collision or concurrent insert invisibility"));
  }

  private void insertRecipeRevision(
      long recipeId,
      RevisionCommit commit,
      long repositoryRevision,
      String manifestSha256) {
    jdbc.update("""
        INSERT INTO conan_recipe_revision
          (recipe_id, rrev, rrev_hash, manifest_sha256, source_kind, status,
           repository_revision, published_at, created_at)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
        """, recipeId, commit.recipeRevision(), hash(commit.recipeRevision()),
        manifestSha256, commit.sourceKind(), commit.status(), repositoryRevision,
        timestamp(commit.publishedAt()));
  }

  private Package upsertPackage(long recipeRevisionId, RevisionCommit commit) {
    Optional<Package> existing = findPackage(recipeRevisionId, commit.packageId());
    if (existing.isPresent()) {
      Package value = existing.orElseThrow();
      if (value.settings().isEmpty() && value.options().isEmpty() && value.requires().isEmpty()
          && (!commit.settings().isEmpty() || !commit.options().isEmpty()
              || !commit.requires().isEmpty())) {
        jdbc.update("""
            UPDATE conan_package
            SET settings_json = ?, options_json = ?, requires_json = ?,
                setting_os = ?, setting_arch = ?, setting_compiler = ?,
                setting_compiler_version = ?, setting_build_type = ?,
                updated_at = CURRENT_TIMESTAMP
            WHERE id = ?
            """,
            json.parameter(objectMap(commit.settings())),
            json.parameter(objectMap(commit.options())),
            json.parameter(objectMap(commit.requires())),
            commit.settings().get("os"), commit.settings().get("arch"),
            commit.settings().get("compiler"), commit.settings().get("compiler.version"),
            commit.settings().get("build_type"), value.id());
        return findPackage(recipeRevisionId, commit.packageId()).orElseThrow();
      }
      if (!value.settings().equals(commit.settings())
          || !value.options().equals(commit.options())
          || !value.requires().equals(commit.requires())) {
        throw new IllegalStateException(
            "Conan package id was reused with different conaninfo projection");
      }
      return value;
    }
    jdbc.update("""
        INSERT INTO conan_package
          (recipe_revision_id, package_id, package_id_hash, settings_json, options_json,
           requires_json, setting_os, setting_arch, setting_compiler,
           setting_compiler_version, setting_build_type, created_at, updated_at)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
        """, recipeRevisionId, commit.packageId(), hash(commit.packageId()),
        json.parameter(objectMap(commit.settings())),
        json.parameter(objectMap(commit.options())),
        json.parameter(objectMap(commit.requires())),
        commit.settings().get("os"), commit.settings().get("arch"),
        commit.settings().get("compiler"), commit.settings().get("compiler.version"),
        commit.settings().get("build_type"));
    return findPackage(recipeRevisionId, commit.packageId()).orElseThrow();
  }

  private void insertPackageRevision(
      long packageRowId, RevisionCommit commit, long repositoryRevision) {
    jdbc.update("""
        INSERT INTO conan_package_revision
          (conan_package_id, prev_value, prev_hash, manifest_sha256, source_kind, status,
           repository_revision, published_at, created_at)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
        """, packageRowId, commit.packageRevision(), hash(commit.packageRevision()),
        commit.manifestSha256(), commit.sourceKind(), commit.status(), repositoryRevision,
        timestamp(commit.publishedAt()));
  }

  private void insertFiles(String ownerKind, long ownerId, List<FileCommit> files) {
    Set<String> paths = new HashSet<>();
    for (FileCommit file : files) {
      validateFile(file, false);
      if (!paths.add(file.path())) {
        throw new IllegalArgumentException("Duplicate Conan revision file: " + file.path());
      }
      upsertFile(ownerKind, ownerId, file);
    }
  }

  private void upsertFile(String ownerKind, long ownerId, FileCommit file) {
    validateFile(file, true);
    JdbcUpserts.updateThenInsert(
        jdbc,
        """
        UPDATE conan_revision_file
        SET path_value = ?, asset_id = ?, md5 = ?, sha1 = ?, sha256 = ?, size_bytes = ?,
            content_type = ?, source_repository_id = ?, updated_at = CURRENT_TIMESTAMP
        WHERE owner_kind = ? AND owner_id = ? AND path_hash = ?
        """,
        new Object[] {
          file.path(), file.assetId(), file.md5(), file.sha1(), file.sha256(), file.size(),
          file.contentType(), file.sourceRepositoryId(), ownerKind, ownerId, hash(file.path())
        },
        """
        INSERT INTO conan_revision_file
          (owner_kind, owner_id, path_value, path_hash, asset_id, md5, sha1, sha256,
           size_bytes, content_type, source_repository_id, created_at, updated_at)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
        """,
        new Object[] {
          ownerKind, ownerId, file.path(), hash(file.path()), file.assetId(), file.md5(),
          file.sha1(), file.sha256(), file.size(), file.contentType(), file.sourceRepositoryId()
        });
    RevisionFile stored = findFile(ownerKind, ownerId, file.path()).orElseThrow();
    if (!file.path().equals(stored.path())) {
      throw new IllegalStateException("Conan revision file hash collision");
    }
  }

  private void assertIdempotentFiles(
      String ownerKind, long ownerId, List<FileCommit> requested) {
    Map<String, RevisionFile> stored = new LinkedHashMap<>();
    Long cursor = null;
    do {
      List<RevisionFile> page = listFiles(ownerKind, ownerId, cursor, 500);
      page.forEach(file -> stored.put(file.path(), file));
      cursor = page.isEmpty() ? null : page.get(page.size() - 1).id();
      if (page.size() < 500) break;
    } while (cursor != null);
    if (stored.size() != requested.size()) {
      throw new IllegalStateException("Conan revision already exists with a different file set");
    }
    for (FileCommit file : requested) {
      RevisionFile current = stored.get(file.path());
      if (current == null
          || !Objects.equals(current.sha256(), file.sha256())
          || current.size() != file.size()) {
        throw new IllegalStateException("Conan revision already exists with different content");
      }
    }
  }

  private long ownerRepositoryId(String ownerKind, long ownerId) {
    requireOwnerKind(ownerKind);
    String sql = OWNER_RECIPE.equals(ownerKind)
        ? """
          SELECT r.repository_id
          FROM conan_recipe_revision rr
          JOIN conan_recipe r ON r.id = rr.recipe_id
          WHERE rr.id = ?
          """
        : """
          SELECT r.repository_id
          FROM conan_package_revision pr
          JOIN conan_package p ON p.id = pr.conan_package_id
          JOIN conan_recipe_revision rr ON rr.id = p.recipe_revision_id
          JOIN conan_recipe r ON r.id = rr.recipe_id
          WHERE pr.id = ?
          """;
    List<Long> values = jdbc.queryForList(sql, Long.class, ownerId);
    if (values.isEmpty()) throw new IllegalArgumentException("Unknown Conan revision owner");
    return values.get(0);
  }

  private void collectRecipeAssets(long recipeId, Set<Long> assets) {
    List<Long> recipeOwners = jdbc.queryForList(
        "SELECT id FROM conan_recipe_revision WHERE recipe_id = ?", Long.class, recipeId);
    collectOwnerAssets(OWNER_RECIPE, recipeOwners, assets);
    if (!recipeOwners.isEmpty()) {
      List<Long> packageOwners = jdbc.queryForList("""
          SELECT pr.id
          FROM conan_package_revision pr
          JOIN conan_package p ON p.id = pr.conan_package_id
          WHERE p.recipe_revision_id IN (%s)
          """.formatted(placeholders(recipeOwners.size())), Long.class, recipeOwners.toArray());
      collectOwnerAssets(OWNER_PACKAGE, packageOwners, assets);
    }
  }

  private void collectRevisionAssets(long recipeRevisionId, Set<Long> assets) {
    collectOwnerAssets(OWNER_RECIPE, List.of(recipeRevisionId), assets);
    List<Long> packageOwners = jdbc.queryForList("""
        SELECT pr.id
        FROM conan_package_revision pr
        JOIN conan_package p ON p.id = pr.conan_package_id
        WHERE p.recipe_revision_id = ?
        """, Long.class, recipeRevisionId);
    collectOwnerAssets(OWNER_PACKAGE, packageOwners, assets);
  }

  private void collectPackageAssets(long packageRowId, Set<Long> assets) {
    List<Long> owners = jdbc.queryForList(
        "SELECT id FROM conan_package_revision WHERE conan_package_id = ?",
        Long.class,
        packageRowId);
    collectOwnerAssets(OWNER_PACKAGE, owners, assets);
  }

  private void collectOwnerAssets(String ownerKind, List<Long> owners, Set<Long> assets) {
    if (owners.isEmpty()) return;
    assets.addAll(jdbc.queryForList("""
        SELECT asset_id FROM conan_revision_file
        WHERE owner_kind = ? AND owner_id IN (%s) AND asset_id IS NOT NULL
        """.formatted(placeholders(owners.size())), Long.class,
        concat(ownerKind, owners).toArray()));
  }

  private void deleteRecipeFiles(long recipeId) {
    List<Long> rrevs = jdbc.queryForList(
        "SELECT id FROM conan_recipe_revision WHERE recipe_id = ?", Long.class, recipeId);
    deleteOwnerFiles(OWNER_RECIPE, rrevs);
    if (!rrevs.isEmpty()) {
      List<Long> prevs = jdbc.queryForList("""
          SELECT pr.id
          FROM conan_package_revision pr
          JOIN conan_package p ON p.id = pr.conan_package_id
          WHERE p.recipe_revision_id IN (%s)
          """.formatted(placeholders(rrevs.size())), Long.class, rrevs.toArray());
      deleteOwnerFiles(OWNER_PACKAGE, prevs);
    }
  }

  private void deleteRevisionFiles(long recipeRevisionId) {
    deleteOwnerFiles(OWNER_RECIPE, List.of(recipeRevisionId));
    List<Long> prevs = jdbc.queryForList("""
        SELECT pr.id FROM conan_package_revision pr
        JOIN conan_package p ON p.id = pr.conan_package_id
        WHERE p.recipe_revision_id = ?
        """, Long.class, recipeRevisionId);
    deleteOwnerFiles(OWNER_PACKAGE, prevs);
  }

  private void deletePackageFiles(long packageRowId) {
    deleteOwnerFiles(OWNER_PACKAGE, jdbc.queryForList(
        "SELECT id FROM conan_package_revision WHERE conan_package_id = ?",
        Long.class,
        packageRowId));
  }

  private void deleteOwnerFiles(String ownerKind, List<Long> ownerIds) {
    if (ownerIds.isEmpty()) return;
    jdbc.update("""
        DELETE FROM conan_revision_file
        WHERE owner_kind = ? AND owner_id IN (%s)
        """.formatted(placeholders(ownerIds.size())), concat(ownerKind, ownerIds).toArray());
  }

  private void refreshLatestRecipe(long recipeId) {
    List<Long> latest = jdbc.queryForList("""
        SELECT id FROM conan_recipe_revision
        WHERE recipe_id = ?
        ORDER BY published_at DESC, id DESC
        LIMIT 1
        """, Long.class, recipeId);
    jdbc.update(
        "UPDATE conan_recipe SET latest_recipe_revision_id = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ?",
        latest.isEmpty() ? null : latest.get(0), recipeId);
  }

  private void refreshLatestPackage(long packageRowId) {
    List<Long> latest = jdbc.queryForList("""
        SELECT id FROM conan_package_revision
        WHERE conan_package_id = ?
        ORDER BY published_at DESC, id DESC
        LIMIT 1
        """, Long.class, packageRowId);
    jdbc.update(
        "UPDATE conan_package SET latest_package_revision_id = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ?",
        latest.isEmpty() ? null : latest.get(0), packageRowId);
  }

  private List<Lease> lockedLease(long repositoryId, byte[] coordinateHash) {
    return jdbc.query("""
        SELECT * FROM conan_coordinate_lease
        WHERE repository_id = ? AND coordinate_hash = ?
        FOR UPDATE
        """, (rs, row) -> new Lease(
            rs.getLong("repository_id"),
            rs.getString("coordinate_key"),
            rs.getString("owner"),
            rs.getLong("fencing_token"),
            nullableInstant(rs, "expires_at"),
            nullableInstant(rs, "updated_at")),
        repositoryId, coordinateHash);
  }

  private int deleteByValues(String table, String column, List<String> values) {
    if (values.isEmpty()) return 0;
    return jdbc.update(
        "DELETE FROM " + table + " WHERE " + column + " IN (" + placeholders(values.size()) + ")",
        values.toArray());
  }

  private long count(String table, String predicate, long repositoryId) {
    Long value = jdbc.queryForObject(
        "SELECT COUNT(*) FROM " + table + " WHERE " + predicate,
        Long.class,
        repositoryId);
    return value == null ? 0L : value;
  }

  private static void validateCommit(RevisionCommit commit, boolean hosted) {
    Objects.requireNonNull(commit, "revision commit");
    requireCoordinate(commit.coordinate());
    requireOwnerKind(commit.ownerKind());
    requireText(commit.recipeRevision(), "recipe revision");
    requireText(commit.sourceKind(), "source kind");
    requireText(commit.status(), "revision status");
    Objects.requireNonNull(commit.publishedAt(), "publishedAt");
    if (OWNER_PACKAGE.equals(commit.ownerKind())) {
      requireText(commit.packageId(), "package id");
      requireText(commit.packageRevision(), "package revision");
    } else if (commit.packageId() != null || commit.packageRevision() != null) {
      throw new IllegalArgumentException("Recipe commit cannot carry package identity");
    }
    if (hosted && (!SOURCE_HOSTED.equals(commit.sourceKind())
        || !STATUS_COMMITTED.equals(commit.status()) || commit.files().isEmpty())) {
      throw new IllegalArgumentException("Hosted Conan commit must be complete and committed");
    }
  }

  private static void validateFile(FileCommit file, boolean allowMissingAsset) {
    Objects.requireNonNull(file, "file");
    requireText(file.path(), "file path");
    if (!allowMissingAsset && file.assetId() == null) {
      throw new IllegalArgumentException("Committed Conan file requires an asset");
    }
    if (file.size() < 0) throw new IllegalArgumentException("Conan file size cannot be negative");
  }

  private static void validateSession(UploadSession session) {
    Objects.requireNonNull(session, "upload session");
    requireOwnerKind(session.ownerKind());
    requireText(session.coordinateKey(), "upload coordinate");
    requireText(session.actorKey(), "upload actor");
    requireText(session.owner(), "upload owner");
    Objects.requireNonNull(session.expiresAt(), "expiresAt");
  }

  private static void validateUploadFile(UploadFile file) {
    Objects.requireNonNull(file, "upload file");
    requireText(file.path(), "upload path");
    requireText(file.md5(), "upload md5");
    requireText(file.sha1(), "upload sha1");
    requireText(file.sha256(), "upload sha256");
    if (file.sessionId() <= 0 || file.stagingAssetId() <= 0 || file.size() < 0) {
      throw new IllegalArgumentException("Invalid Conan upload file identity");
    }
  }

  private static void validateAuthToken(AuthToken token) {
    Objects.requireNonNull(token, "auth token");
    if (token.tokenHash() == null || !token.tokenHash().matches("[0-9a-f]{64}")) {
      throw new IllegalArgumentException("Conan auth token hash must be lowercase SHA-256");
    }
    requireText(token.subjectSource(), "token subject source");
    requireText(token.subjectUserId(), "token subject user");
    Objects.requireNonNull(token.expiresAt(), "expiresAt");
    Objects.requireNonNull(token.createdAt(), "createdAt");
  }

  private static void assertCompatible(
      String kind,
      String existingManifest,
      String requestedManifest,
      String existingSource,
      String requestedSource) {
    if (!Objects.equals(existingManifest, requestedManifest)
        || !Objects.equals(existingSource, requestedSource)) {
      throw new IllegalStateException("Conan " + kind + " is immutable and already differs");
    }
  }

  private static boolean sameCoordinate(Recipe row, RecipeCoordinate coordinate) {
    return row.repositoryId() == coordinate.repositoryId()
        && row.name().equals(coordinate.name())
        && row.version().equals(coordinate.version())
        && Objects.equals(row.user(), coordinate.user())
        && Objects.equals(row.channel(), coordinate.channel());
  }

  private static void requireCoordinate(RecipeCoordinate coordinate) {
    Objects.requireNonNull(coordinate, "recipe coordinate");
    requireText(coordinate.name(), "recipe name");
    requireText(coordinate.version(), "recipe version");
  }

  private static void requireOwnerKind(String ownerKind) {
    if (!OWNER_RECIPE.equals(ownerKind) && !OWNER_PACKAGE.equals(ownerKind)) {
      throw new IllegalArgumentException("Invalid Conan owner kind: " + ownerKind);
    }
  }

  private static void requireText(String value, String field) {
    if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " is required");
  }

  private static String reference(Recipe recipe) {
    String value = recipe.name() + "/" + recipe.version();
    if (recipe.user() != null) value += "@" + recipe.user();
    if (recipe.channel() != null) value += "/" + recipe.channel();
    return value;
  }

  private static Pattern wildcard(String raw, boolean ignoreCase) {
    StringBuilder regex = new StringBuilder("^");
    for (int index = 0; index < raw.length(); index++) {
      char character = raw.charAt(index);
      switch (character) {
        case '*' -> regex.append(".*");
        case '?' -> regex.append('.');
        default -> regex.append(Pattern.quote(String.valueOf(character)));
      }
    }
    regex.append('$');
    return Pattern.compile(regex.toString(), ignoreCase ? Pattern.CASE_INSENSITIVE : 0);
  }

  private static String namePrefix(String pattern) {
    if (pattern == null || pattern.isBlank()) return "";
    int slash = pattern.indexOf('/');
    String name = slash < 0 ? pattern : pattern.substring(0, slash);
    int wildcard = firstWildcard(name);
    return wildcard < 0 ? name : name.substring(0, wildcard);
  }

  private static String recipeNamePattern(String pattern) {
    if (pattern == null || pattern.isBlank()) return null;
    int slash = pattern.indexOf('/');
    return slash < 0 ? pattern : pattern.substring(0, slash);
  }

  private static int firstWildcard(String value) {
    int star = value.indexOf('*');
    int question = value.indexOf('?');
    if (star < 0) return question;
    if (question < 0) return star;
    return Math.min(star, question);
  }

  private static String prefixUpperBound(String prefix) {
    char[] value = prefix.toCharArray();
    for (int index = value.length - 1; index >= 0; index--) {
      if (value[index] < 0x7f) {
        value[index]++;
        return new String(value, 0, index + 1);
      }
    }
    return prefix + '\u007f';
  }

  private static boolean packageArchive(String path) {
    return path != null && (path.endsWith("conan_package.tgz")
        || path.endsWith("conan_package.txz") || path.endsWith("conan_package.tzst"));
  }

  private boolean hasRows(String table, String column, long value) {
    List<Integer> rows = jdbc.queryForList(
        "SELECT 1 FROM " + table + " WHERE " + column + " = ? LIMIT 1", Integer.class, value);
    return !rows.isEmpty();
  }

  private Map<String, String> stringMap(String value) {
    Map<String, String> result = json.readValue(value, STRING_MAP);
    return result == null ? Map.of() : Map.copyOf(result);
  }

  private static Map<String, Object> objectMap(Map<String, String> values) {
    return new LinkedHashMap<>(values == null ? Map.of() : values);
  }

  private static byte[] coordinateHash(RecipeCoordinate coordinate) {
    return hash(coordinate.coordinateKey());
  }

  private static byte[] hash(String value) {
    requireText(value, "hash input");
    return HashColumns.sha256(value);
  }

  private static int page(int limit) {
    return Math.max(1, Math.min(MAX_PAGE, limit));
  }

  private static long after(Long value) {
    return value == null ? 0L : Math.max(0, value);
  }

  private static String key(String value) {
    return value == null ? "" : value;
  }

  private static String optionalKey(String value) {
    return value == null || value.isEmpty() ? null : value;
  }

  private static Timestamp timestamp(Instant value) {
    return value == null ? null : Timestamp.from(value);
  }

  private static String placeholders(int size) {
    return String.join(",", java.util.Collections.nCopies(size, "?"));
  }

  private static List<Object> concat(Object first, Collection<?> rest) {
    ArrayList<Object> values = new ArrayList<>(rest.size() + 1);
    values.add(first);
    values.addAll(rest);
    return values;
  }
}
