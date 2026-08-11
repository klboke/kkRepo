package com.github.klboke.kkrepo.persistence.jdbc.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.persistence.jdbc.api.AssetDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.ConanRegistryDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetRecord;
import com.github.klboke.kkrepo.persistence.jdbc.internal.support.HashColumns;
import com.github.klboke.kkrepo.persistence.mysql.support.MySqlIntegrationTestSupport;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ConanRegistryDaoMySqlIntegrationTest extends MySqlIntegrationTestSupport {
  private static final Instant NOW = Instant.parse("2026-08-11T00:00:00Z");
  private static final String RREV_ONE = "1".repeat(32);
  private static final String RREV_TWO = "2".repeat(32);
  private static final String PACKAGE_ID = "3".repeat(40);
  private static final String PREV = "4".repeat(32);

  @Test
  void revisionPackageScanUploadLeaseTokenAndGroupContractIsDurable() {
    long repositoryId = insertRepository("conan-hosted", "conan");
    long groupId = insertRepository("conan-group", "conan");
    jdbc().update(
        "UPDATE repository SET type = 'group', recipe_name = 'conan-group' WHERE id = ?",
        groupId);
    ConanRegistryDao dao = stores().conanRegistry();
    AssetDao assets = stores().assets();
    ConanRegistryDao.RecipeCoordinate coordinate = coordinate(repositoryId);

    long conanfileId = asset(assets, repositoryId, "conanfile.py");
    long recipeManifestId = asset(assets, repositoryId, "recipe/conanmanifest.txt");
    ConanRegistryDao.RevisionCommit firstRecipe = recipeCommit(
        coordinate, RREV_ONE, NOW, conanfileId, recipeManifestId);
    ConanRegistryDao.CommittedRevision first = inTransaction(
        () -> dao.commitRevision(firstRecipe));
    assertFalse(first.idempotent());
    assertEquals(RREV_ONE, dao.findLatestRecipeRevision(first.recipeId()).orElseThrow().revision());
    assertEquals(conanfileId, dao.findFileByAssetId(conanfileId).orElseThrow().file().assetId());

    long archiveId = asset(assets, repositoryId, "package/conan_package.tgz");
    long infoId = asset(assets, repositoryId, "package/conaninfo.txt");
    long packageManifestId = asset(assets, repositoryId, "package/conanmanifest.txt");
    ConanRegistryDao.CommittedRevision binary = inTransaction(() -> dao.commitRevision(
        packageCommit(
            coordinate, RREV_ONE, PACKAGE_ID, PREV, NOW.plusSeconds(1),
            archiveId, infoId, packageManifestId)));
    assertEquals(PREV, dao.findLatestPackageRevision(binary.packageRowId()).orElseThrow().revision());
    ConanRegistryDao.PackageScanContext scan = dao.findPackageScanContext(infoId).orElseThrow();
    assertEquals(archiveId, scan.archive().file().assetId());
    assertEquals(infoId, scan.conanInfo().assetId());
    assertEquals("Linux", dao.findPackage(first.recipeRevisionId(), PACKAGE_ID)
        .orElseThrow().settings().get("os"));

    long secondConanfileId = asset(assets, repositoryId, "second/conanfile.py");
    long secondManifestId = asset(assets, repositoryId, "second/conanmanifest.txt");
    ConanRegistryDao.CommittedRevision second = inTransaction(() -> dao.commitRevision(
        recipeCommit(coordinate, RREV_TWO, NOW.plusSeconds(60),
            secondConanfileId, secondManifestId)));
    assertEquals(RREV_TWO,
        dao.findLatestRecipeRevision(second.recipeId()).orElseThrow().revision());
    assertTrue(inTransaction(() -> dao.commitRevision(firstRecipe)).idempotent());
    assertEquals(RREV_TWO,
        dao.findLatestRecipeRevision(second.recipeId()).orElseThrow().revision(),
        "an idempotent replay of an older RREV must not regress latest");
    assertEquals(List.of("demo_kit/1.2.3@acme/stable"),
        dao.searchRecipes(repositoryId, "demo_kit/1.*@acme/stable", false, null, 20)
            .stream().map(ConanRegistryDaoMySqlIntegrationTest::reference).toList());

    assertThrows(IllegalStateException.class, () -> inTransaction(() -> dao.commitRevision(
        packageCommit(coordinate(repositoryId, "missing"), RREV_ONE, PACKAGE_ID, PREV,
            NOW, archiveId, infoId, packageManifestId))));

    long groupRevision = dao.nextRepositoryRevision(groupId);
    long memberRevision = dao.currentRepositoryRevision(repositoryId);
    assertTrue(inTransaction(() -> dao.upsertGroupBindingIfCurrent(
        new ConanRegistryDao.GroupBinding(
            groupId, ConanRegistryDao.OWNER_PACKAGE, "binary-coordinate", repositoryId,
            binary.ownerId(), memberRevision, groupRevision, null, NOW, NOW))));
    assertEquals(repositoryId, dao.findGroupBinding(
        groupId, ConanRegistryDao.OWNER_PACKAGE, "binary-coordinate")
        .orElseThrow().memberRepositoryId());
    dao.nextRepositoryRevision(repositoryId);
    assertTrue(dao.findGroupBinding(
        groupId, ConanRegistryDao.OWNER_PACKAGE, "binary-coordinate").isEmpty());

    ConanRegistryDao.Lease lease = inTransaction(() -> dao.tryAcquireLease(
        repositoryId, "recipe-coordinate", "node-a", NOW.plusSeconds(60))).orElseThrow();
    dao.releaseLease(
        repositoryId, lease.coordinateKey(), lease.owner(), lease.fencingToken());
    ConanRegistryDao.Lease replacement = inTransaction(() -> dao.tryAcquireLease(
        repositoryId, "recipe-coordinate", "node-b", NOW.plusSeconds(120))).orElseThrow();
    assertTrue(replacement.fencingToken() > lease.fencingToken());

    long stagingId = asset(assets, repositoryId, ".conan/staging/1/conanfile.py");
    ConanRegistryDao.UploadSession session = dao.openUploadSession(
        new ConanRegistryDao.UploadSession(
            null, repositoryId, ConanRegistryDao.OWNER_RECIPE, "upload-coordinate", "actor",
            ConanRegistryDao.SESSION_OPEN, "publisher", 0, null, NOW.minusSeconds(1), NOW, NOW));
    dao.upsertUploadFile(new ConanRegistryDao.UploadFile(
        null, session.id(), "conanfile.py", stagingId, "a".repeat(32), "b".repeat(40),
        "c".repeat(64), 10, "text/x-python", NOW, NOW));
    ConanRegistryDao.UploadSession claimed = inTransaction(() -> dao.claimExpiredUploadSessions(
        "cleaner", NOW, NOW.plusSeconds(60), 10)).getFirst();
    assertFalse(dao.deleteClaimedUploadSession(claimed.id(), "wrong", claimed.fencingToken()));
    assertTrue(dao.deleteClaimedUploadSession(
        claimed.id(), claimed.owner(), claimed.fencingToken()));

    dao.insertAuthToken(new ConanRegistryDao.AuthToken(
        "d".repeat(64), repositoryId, "LOCAL", "user", null, null,
        NOW.plusSeconds(60), null, NOW));
    assertTrue(dao.findValidAuthToken("d".repeat(64), repositoryId, NOW).isPresent());
    assertEquals(1, dao.touchAuthToken("d".repeat(64), NOW.plusSeconds(1)));
    assertEquals(1, inTransaction(() -> dao.deleteExpiredAuthTokens(
        NOW.plusSeconds(61), 10)));

    ConanRegistryDao.RepositoryStatus status = dao.status(repositoryId);
    assertEquals(1, status.recipes());
    assertEquals(0, status.openUploadSessions());

    ConanRegistryDao.DeletedCoordinate deleted = inTransaction(() -> dao.deleteCoordinate(
        new ConanRegistryDao.DeleteTarget(coordinate, RREV_ONE, PACKAGE_ID, PREV),
        "test", NOW.plusSeconds(120)));
    assertTrue(deleted.deleted());
    assertTrue(dao.findPackage(first.recipeRevisionId(), PACKAGE_ID).isEmpty());
  }

  @Test
  void accessShapesHaveRepositoryLeadingIndexesAndIndexedPlans() {
    assertEquals(List.of("repository_id", "coordinate_hash"),
        indexColumns("conan_recipe", "uk_conan_recipe_coordinate"));
    assertEquals(List.of("repository_id", "name_key", "id"),
        indexColumns("conan_recipe", "idx_conan_recipe_name_page"));
    assertEquals(List.of("recipe_id", "rrev_hash"),
        indexColumns("conan_recipe_revision", "uk_conan_rrev"));
    assertEquals(List.of("recipe_revision_id", "package_id_hash"),
        indexColumns("conan_package", "uk_conan_package"));
    assertEquals(List.of("conan_package_id", "prev_hash"),
        indexColumns("conan_package_revision", "uk_conan_prev"));
    assertEquals(List.of("owner_kind", "owner_id", "path_hash"),
        indexColumns("conan_revision_file", "uk_conan_revision_file"));
    assertEquals(List.of("repository_id", "owner_kind", "coordinate_hash", "actor_hash"),
        indexColumns("conan_upload_session", "uk_conan_upload_session"));
    assertEquals(List.of("status", "lease_until", "id"),
        indexColumns("conan_upload_session", "idx_conan_upload_claim"));
    assertEquals(List.of("group_repository_id", "binding_kind", "coordinate_hash"),
        indexColumns("conan_group_binding", "uk_conan_group_binding"));
    assertEquals(List.of("expires_at", "token_hash"),
        indexColumns("conan_auth_token", "idx_conan_token_expiry"));

    long repositoryId = insertRepository("conan-plan", "conan");
    ConanRegistryDao dao = stores().conanRegistry();
    AssetDao assets = stores().assets();
    inTransaction(() -> dao.commitRevision(recipeCommit(
        coordinate(repositoryId), RREV_ONE, NOW,
        asset(assets, repositoryId, "plan/conanfile.py"),
        asset(assets, repositoryId, "plan/conanmanifest.txt"))));
    String exactPlan = jdbc().queryForObject("""
        EXPLAIN FORMAT=JSON
        SELECT * FROM conan_recipe WHERE repository_id = ? AND coordinate_hash = ?
        """, String.class, repositoryId,
        HashColumns.sha256(coordinate(repositoryId).coordinateKey()));
    assertTrue(exactPlan.contains("uk_conan_recipe_coordinate"), exactPlan);
    String filePlan = jdbc().queryForObject("""
        EXPLAIN FORMAT=JSON
        SELECT * FROM conan_revision_file
        WHERE owner_kind = 'RECIPE' AND owner_id = ? AND path_hash = ?
        """, String.class,
        dao.findRecipeRevision(
            dao.findRecipe(coordinate(repositoryId)).orElseThrow().id(), RREV_ONE)
            .orElseThrow().id(),
        HashColumns.sha256("conanfile.py"));
    assertTrue(filePlan.contains("uk_conan_revision_file"), filePlan);
    String prefixPlan = jdbc().queryForObject("""
        EXPLAIN FORMAT=JSON
        SELECT * FROM conan_recipe
        WHERE repository_id = ?
          AND name_key >= CAST(? AS CHAR CHARACTER SET ascii) COLLATE ascii_bin
          AND name_key < CAST(? AS CHAR CHARACTER SET ascii) COLLATE ascii_bin
          AND id > ?
        ORDER BY id
        LIMIT ?
        """, String.class, repositoryId, "demo", "demp", 0, 20);
    assertTrue(prefixPlan.contains("idx_conan_recipe_name_page"), prefixPlan);
  }

  private List<String> indexColumns(String table, String index) {
    return jdbc().queryForList("""
        SELECT column_name
        FROM information_schema.statistics
        WHERE table_schema = DATABASE() AND table_name = ? AND index_name = ?
        ORDER BY seq_in_index
        """, String.class, table, index);
  }

  private static ConanRegistryDao.RevisionCommit recipeCommit(
      ConanRegistryDao.RecipeCoordinate coordinate,
      String revision,
      Instant publishedAt,
      long conanfileId,
      long manifestId) {
    return new ConanRegistryDao.RevisionCommit(
        coordinate, null, ConanRegistryDao.OWNER_RECIPE, revision, null, null,
        Map.of(), Map.of(), Map.of(), "a".repeat(64), ConanRegistryDao.SOURCE_HOSTED,
        ConanRegistryDao.STATUS_COMMITTED, publishedAt,
        List.of(
            file("conanfile.py", conanfileId, "1"),
            file("conanmanifest.txt", manifestId, "2")));
  }

  private static ConanRegistryDao.RevisionCommit packageCommit(
      ConanRegistryDao.RecipeCoordinate coordinate,
      String recipeRevision,
      String packageId,
      String packageRevision,
      Instant publishedAt,
      long archiveId,
      long infoId,
      long manifestId) {
    return new ConanRegistryDao.RevisionCommit(
        coordinate, null, ConanRegistryDao.OWNER_PACKAGE, recipeRevision, packageId,
        packageRevision, Map.of("os", "Linux", "arch", "x86_64"),
        Map.of("shared", "False"), Map.of("zlib/1.3.1", ""), "b".repeat(64),
        ConanRegistryDao.SOURCE_HOSTED, ConanRegistryDao.STATUS_COMMITTED, publishedAt,
        List.of(
            file("conan_package.tgz", archiveId, "3"),
            file("conaninfo.txt", infoId, "4"),
            file("conanmanifest.txt", manifestId, "5")));
  }

  private static ConanRegistryDao.FileCommit file(String path, long assetId, String seed) {
    return new ConanRegistryDao.FileCommit(
        path, assetId, seed.repeat(32), seed.repeat(40), seed.repeat(64), 10,
        "application/octet-stream", null);
  }

  private static ConanRegistryDao.RecipeCoordinate coordinate(long repositoryId) {
    return coordinate(repositoryId, "demo_kit");
  }

  private static ConanRegistryDao.RecipeCoordinate coordinate(
      long repositoryId, String name) {
    return new ConanRegistryDao.RecipeCoordinate(
        repositoryId, name, "1.2.3", "acme", "stable");
  }

  private static String reference(ConanRegistryDao.Recipe recipe) {
    return recipe.name() + "/" + recipe.version() + "@" + recipe.user() + "/" + recipe.channel();
  }

  private static long asset(AssetDao dao, long repositoryId, String path) {
    return dao.insertAsset(new AssetRecord(
        null, repositoryId, null, null, RepositoryFormat.CONAN, path,
        HashColumns.pathHash(path), path.substring(path.lastIndexOf('/') + 1), "CONAN_FILE",
        "application/octet-stream", 10L, null, NOW, Map.of()));
  }
}
