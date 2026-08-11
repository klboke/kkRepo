package com.github.klboke.kkrepo.persistence.postgresql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.persistence.jdbc.api.AssetDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.ConanRegistryDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetRecord;
import com.github.klboke.kkrepo.persistence.jdbc.internal.support.HashColumns;
import com.github.klboke.kkrepo.persistence.postgresql.support.PostgreSqlIntegrationTestSupport;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ConanRegistryDaoPostgreSqlIntegrationTest extends PostgreSqlIntegrationTestSupport {
  private static final Instant NOW = Instant.parse("2026-08-11T00:00:00Z");
  private static final String RREV = "1".repeat(32);
  private static final String PACKAGE_ID = "2".repeat(40);
  private static final String PREV = "3".repeat(32);

  @Test
  void hostedRevisionPackageScanAndDistributedStateContractIsDurable() {
    long repositoryId = insertRepository("conan-hosted", "hosted");
    long groupId = insertRepository("conan-group", "group");
    ConanRegistryDao dao = stores().conanRegistry();
    AssetDao assets = stores().assets();
    ConanRegistryDao.RecipeCoordinate coordinate = coordinate(repositoryId);

    long conanfileId = asset(assets, repositoryId, "recipe/conanfile.py");
    long recipeManifestId = asset(assets, repositoryId, "recipe/conanmanifest.txt");
    ConanRegistryDao.RevisionCommit recipe = recipeCommit(
        coordinate, RREV, NOW, conanfileId, recipeManifestId);
    ConanRegistryDao.CommittedRevision committed = inTransaction(() -> dao.commitRevision(recipe));
    assertFalse(committed.idempotent());
    assertTrue(inTransaction(() -> dao.commitRevision(recipe)).idempotent());

    long archiveId = asset(assets, repositoryId, "package/conan_package.tzst");
    long infoId = asset(assets, repositoryId, "package/conaninfo.txt");
    long packageManifestId = asset(assets, repositoryId, "package/conanmanifest.txt");
    ConanRegistryDao.CommittedRevision binary = inTransaction(() -> dao.commitRevision(
        packageCommit(coordinate, archiveId, infoId, packageManifestId)));
    assertEquals(PREV,
        dao.findLatestPackageRevision(binary.packageRowId()).orElseThrow().revision());
    assertEquals(archiveId,
        dao.findPackageScanContext(infoId).orElseThrow().archive().file().assetId());
    assertEquals(PACKAGE_ID,
        dao.findFileByAssetId(archiveId).orElseThrow().packageId());
    assertEquals(List.of("demo_kit/1.2.3@acme/stable"),
        dao.searchRecipes(repositoryId, "demo_kit/*", true, null, 20).stream()
            .map(ConanRegistryDaoPostgreSqlIntegrationTest::reference).toList());

    assertThrows(IllegalStateException.class, () -> inTransaction(() -> dao.commitRevision(
        packageCommit(coordinate(repositoryId, "orphan"), archiveId, infoId, packageManifestId))));

    long groupRevision = dao.nextRepositoryRevision(groupId);
    long memberRevision = dao.currentRepositoryRevision(repositoryId);
    assertTrue(inTransaction(() -> dao.upsertGroupBindingIfCurrent(
        new ConanRegistryDao.GroupBinding(
            groupId, ConanRegistryDao.OWNER_PACKAGE, "binary", repositoryId, binary.ownerId(),
            memberRevision, groupRevision, null, NOW, NOW))));
    dao.nextRepositoryRevision(repositoryId);
    assertTrue(dao.findGroupBinding(
        groupId, ConanRegistryDao.OWNER_PACKAGE, "binary").isEmpty());

    ConanRegistryDao.Lease first = inTransaction(() -> dao.tryAcquireLease(
        repositoryId, "coordinate", "node-a", NOW.plusSeconds(60))).orElseThrow();
    dao.releaseLease(repositoryId, first.coordinateKey(), first.owner(), first.fencingToken());
    ConanRegistryDao.Lease second = inTransaction(() -> dao.tryAcquireLease(
        repositoryId, "coordinate", "node-b", NOW.plusSeconds(120))).orElseThrow();
    assertTrue(second.fencingToken() > first.fencingToken());

    long stagingId = asset(assets, repositoryId, ".conan/staging/1/conanfile.py");
    ConanRegistryDao.UploadSession session = dao.openUploadSession(
        new ConanRegistryDao.UploadSession(
            null, repositoryId, ConanRegistryDao.OWNER_RECIPE, "upload", "actor",
            ConanRegistryDao.SESSION_OPEN, "publisher", 0, null, NOW.minusSeconds(1), NOW, NOW));
    dao.upsertUploadFile(new ConanRegistryDao.UploadFile(
        null, session.id(), "conanfile.py", stagingId, "a".repeat(32), "b".repeat(40),
        "c".repeat(64), 10, "text/x-python", NOW, NOW));
    ConanRegistryDao.UploadSession claimed = inTransaction(() -> dao.claimExpiredUploadSessions(
        "cleaner", NOW, NOW.plusSeconds(60), 10)).getFirst();
    assertTrue(dao.deleteClaimedUploadSession(
        claimed.id(), claimed.owner(), claimed.fencingToken()));

    dao.insertAuthToken(new ConanRegistryDao.AuthToken(
        "d".repeat(64), repositoryId, "LOCAL", "user", null, null,
        NOW.plusSeconds(1), null, NOW));
    assertTrue(dao.findValidAuthToken("d".repeat(64), repositoryId, NOW).isPresent());
    assertEquals(1, inTransaction(() -> dao.deleteExpiredAuthTokens(
        NOW.plusSeconds(2), 10)));
    assertEquals(0, dao.status(repositoryId).openUploadSessions());

    assertTrue(inTransaction(() -> dao.deleteCoordinate(
        new ConanRegistryDao.DeleteTarget(coordinate, RREV, PACKAGE_ID, PREV),
        "test", NOW.plusSeconds(3))).deleted());
    assertTrue(dao.findPackage(committed.recipeRevisionId(), PACKAGE_ID).isEmpty());
  }

  @Test
  void accessShapesHaveDeclaredIndexesAndUseThemWhenSequentialScansAreDisabled() {
    assertEquals(
        "CREATE UNIQUE INDEX uk_conan_recipe_coordinate ON public.conan_recipe "
            + "USING btree (repository_id, coordinate_hash)",
        indexDefinition("uk_conan_recipe_coordinate"));
    assertEquals(
        "CREATE INDEX idx_conan_recipe_name_page ON public.conan_recipe "
            + "USING btree (repository_id, name_key, id)",
        indexDefinition("idx_conan_recipe_name_page"));
    assertEquals(
        "CREATE UNIQUE INDEX uk_conan_revision_file ON public.conan_revision_file "
            + "USING btree (owner_kind, owner_id, path_hash)",
        indexDefinition("uk_conan_revision_file"));
    assertEquals(
        "CREATE INDEX idx_conan_upload_claim ON public.conan_upload_session "
            + "USING btree (status, lease_until, id)",
        indexDefinition("idx_conan_upload_claim"));
    assertEquals(
        "CREATE UNIQUE INDEX uk_conan_group_binding ON public.conan_group_binding "
            + "USING btree (group_repository_id, binding_kind, coordinate_hash)",
        indexDefinition("uk_conan_group_binding"));

    long repositoryId = insertRepository("conan-plan", "hosted");
    ConanRegistryDao dao = stores().conanRegistry();
    AssetDao assets = stores().assets();
    ConanRegistryDao.CommittedRevision revision = inTransaction(() -> dao.commitRevision(
        recipeCommit(
            coordinate(repositoryId), RREV, NOW,
            asset(assets, repositoryId, "plan/conanfile.py"),
            asset(assets, repositoryId, "plan/conanmanifest.txt"))));
    jdbc().update("""
        INSERT INTO conan_recipe
          (repository_id, coordinate_hash, name_key, version_key, user_key, channel_key)
        SELECT ?, decode(md5(n::text) || md5('conan-' || n::text), 'hex'),
               'plan-' || n::text, '1.0.' || n::text, 'acme', 'stable'
        FROM generate_series(1, 2048) AS n
        """, repositoryId);
    jdbc().execute("ANALYZE conan_recipe");
    String exactPlan = inTransaction(() -> {
      jdbc().execute("SET LOCAL enable_seqscan = off");
      return String.join("\n", jdbc().queryForList("""
          EXPLAIN SELECT * FROM conan_recipe
          WHERE repository_id = ? AND coordinate_hash = ?
          """, String.class, repositoryId,
          HashColumns.sha256(coordinate(repositoryId).coordinateKey())));
    });
    assertTrue(exactPlan.contains("uk_conan_recipe_coordinate"), exactPlan);
    jdbc().update("""
        INSERT INTO conan_revision_file
          (owner_kind, owner_id, path_value, path_hash, size_bytes)
        SELECT 'RECIPE', ?, 'decoy/' || n::text,
               decode(md5('file-' || n::text) || md5('path-' || n::text), 'hex'), 0
        FROM generate_series(1, 2048) AS n
        """, revision.ownerId());
    jdbc().execute("ANALYZE conan_revision_file");
    String filePlan = inTransaction(() -> {
      jdbc().execute("SET LOCAL enable_seqscan = off");
      return String.join("\n", jdbc().queryForList("""
          EXPLAIN SELECT * FROM conan_revision_file
          WHERE owner_kind = 'RECIPE' AND owner_id = ? AND path_hash = ?
          """, String.class, revision.ownerId(), HashColumns.sha256("conanfile.py")));
    });
    assertTrue(filePlan.contains("uk_conan_revision_file"), filePlan);
  }

  private String indexDefinition(String index) {
    return jdbc().queryForObject("""
        SELECT indexdef FROM pg_indexes
        WHERE schemaname = current_schema() AND indexname = ?
        """, String.class, index);
  }

  private long insertRepository(String name, String type) {
    jdbc().update("""
        INSERT INTO blob_store (name, type, attributes_json)
        VALUES (?, 'S3', CAST('{}' AS jsonb))
        """, name + "-store");
    long blobStoreId = jdbc().queryForObject(
        "SELECT id FROM blob_store WHERE name = ?", Long.class, name + "-store");
    jdbc().update("""
        INSERT INTO repository
          (name, format, type, recipe_name, blob_store_id, attributes_json)
        VALUES (?, 'conan', ?, ?, ?, CAST('{}' AS jsonb))
        """, name, type, "conan-" + type, blobStoreId);
    return jdbc().queryForObject(
        "SELECT id FROM repository WHERE name = ?", Long.class, name);
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
      long archiveId,
      long infoId,
      long manifestId) {
    return new ConanRegistryDao.RevisionCommit(
        coordinate, null, ConanRegistryDao.OWNER_PACKAGE, RREV, PACKAGE_ID, PREV,
        Map.of("os", "Linux", "arch", "x86_64"), Map.of("shared", "False"),
        Map.of(), "b".repeat(64), ConanRegistryDao.SOURCE_HOSTED,
        ConanRegistryDao.STATUS_COMMITTED, NOW.plusSeconds(1),
        List.of(
            file("conan_package.tzst", archiveId, "3"),
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
