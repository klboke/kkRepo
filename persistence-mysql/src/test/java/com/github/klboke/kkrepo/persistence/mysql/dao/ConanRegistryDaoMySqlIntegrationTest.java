package com.github.klboke.kkrepo.persistence.jdbc.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.persistence.jdbc.api.AssetDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.ConanRegistryDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.ComponentRecord;
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
  void queryRestoreBindingAndValidationBranchesAreDurable() {
    long repositoryId = insertRepository("conan-query-restore", "conan");
    long otherRepositoryId = insertRepository("conan-query-other", "conan");
    ConanRegistryDao dao = stores().conanRegistry();
    AssetDao assets = stores().assets();
    ConanRegistryDao.RecipeCoordinate coordinate = coordinate(repositoryId, "casekit");
    long componentId = stores().components().insert(new ComponentRecord(
        null, repositoryId, RepositoryFormat.CONAN, "acme/stable", "casekit", "1.2.3",
        "conan-recipe", HashColumns.componentCoordinateHash("acme/stable", "casekit", "1.2.3"),
        Map.of(), NOW));

    assertEquals(0, dao.currentRepositoryRevision(repositoryId));
    assertTrue(dao.findRecipeByComponent(0, componentId).isEmpty());
    assertTrue(dao.findRecipeByComponent(repositoryId, 0).isEmpty());
    assertTrue(dao.findFileByAssetId(0).isEmpty());
    assertTrue(dao.findFileByAssetId(Long.MAX_VALUE).isEmpty());

    ConanRegistryDao.RevisionCommit recipe = withComponent(
        recipeCommit(
            coordinate, RREV_ONE, NOW,
            asset(assets, repositoryId, "query/conanfile.py"),
            asset(assets, repositoryId, "query/conanmanifest.txt")),
        componentId);
    ConanRegistryDao.CommittedRevision committed = inTransaction(() -> dao.commitRevision(recipe));
    assertEquals(componentId,
        dao.findRecipeByComponent(repositoryId, componentId).orElseThrow().componentId());
    assertEquals(1, dao.listRecipeRevisions(committed.recipeId(), null, 0).size());
    assertTrue(dao.listRecipeRevisions(
        committed.recipeId(), committed.recipeRevisionId(), 10).isEmpty());
    assertEquals(1, dao.searchRecipes(repositoryId, null, false, null, 1).size());
    assertEquals(1, dao.searchRecipes(repositoryId, " ", false, -1L, 1).size());
    assertEquals(1, dao.searchRecipes(repositoryId, "CASEKIT/*", true, null, 1).size());
    assertEquals(1, dao.searchRecipes(repositoryId, "c?se*/*", false, null, 1).size());
    assertTrue(dao.searchRecipes(repositoryId, "missing", false, null, 1).isEmpty());

    ConanRegistryDao.RevisionCommit packageCommit = packageCommit(
        coordinate, RREV_ONE, PACKAGE_ID, PREV, NOW.plusSeconds(1),
        asset(assets, repositoryId, "query/conan_package.tgx"),
        asset(assets, repositoryId, "query/conaninfo.txt"),
        asset(assets, repositoryId, "query/package-manifest.txt"));
    ConanRegistryDao.CommittedRevision binary = inTransaction(() -> dao.commitRevision(packageCommit));
    assertEquals(1, dao.listPackages(committed.recipeRevisionId(), null, 0).size());
    assertTrue(dao.listPackages(
        committed.recipeRevisionId(), binary.packageRowId(), 10).isEmpty());
    assertEquals(1, dao.listPackageRevisions(binary.packageRowId(), null, 0).size());
    assertTrue(dao.listPackageRevisions(
        binary.packageRowId(), binary.packageRevisionId(), 10).isEmpty());
    assertEquals(3, dao.listFiles(
        ConanRegistryDao.OWNER_PACKAGE, binary.ownerId(), null, 10).size());
    assertTrue(dao.findPackageScanContext(packageCommit.files().get(1).assetId()).isPresent());
    jdbc().update("""
        UPDATE conan_revision_file
        SET path_value = 'archive.bin'
        WHERE owner_kind = 'PACKAGE' AND owner_id = ? AND path_value = 'conan_package.tgz'
        """, binary.ownerId());
    assertTrue(dao.findPackageScanContext(packageCommit.files().get(1).assetId()).isEmpty(),
        "an unrecognised archive suffix must not be projected as a scan context");
    assertTrue(dao.findPackageScanContext(recipe.files().get(0).assetId()).isEmpty());

    assertThrows(IllegalStateException.class, () -> inTransaction(() -> dao.commitRevision(
        new ConanRegistryDao.RevisionCommit(
            packageCommit.coordinate(), packageCommit.componentId(), packageCommit.ownerKind(),
            packageCommit.recipeRevision(), packageCommit.packageId(), packageCommit.packageRevision(),
            packageCommit.settings(), packageCommit.options(), packageCommit.requires(),
            packageCommit.manifestSha256(), packageCommit.sourceKind(), packageCommit.status(),
            packageCommit.publishedAt(), packageCommit.files().subList(0, 2)))));
    assertThrows(IllegalStateException.class, () -> inTransaction(() -> dao.commitRevision(
        new ConanRegistryDao.RevisionCommit(
            packageCommit.coordinate(), packageCommit.componentId(), packageCommit.ownerKind(),
            packageCommit.recipeRevision(), packageCommit.packageId(), packageCommit.packageRevision(),
            packageCommit.settings(), packageCommit.options(), packageCommit.requires(),
            packageCommit.manifestSha256(), packageCommit.sourceKind(), packageCommit.status(),
            packageCommit.publishedAt(), List.of(
                new ConanRegistryDao.FileCommit(
                    packageCommit.files().get(0).path(), packageCommit.files().get(0).assetId(),
                    packageCommit.files().get(0).md5(), packageCommit.files().get(0).sha1(),
                    "f".repeat(64), packageCommit.files().get(0).size(),
                    packageCommit.files().get(0).contentType(), null),
                packageCommit.files().get(1), packageCommit.files().get(2))))));

    ConanRegistryDao.RecipeCoordinate proxyCoordinate = coordinate(repositoryId, "proxy-kit");
    ConanRegistryDao.RevisionCommit proxyRecipe = revisionCommit(
        proxyCoordinate, ConanRegistryDao.OWNER_RECIPE, RREV_TWO, null, null,
        Map.of(), ConanRegistryDao.SOURCE_PROXY, ConanRegistryDao.STATUS_DISCOVERED,
        List.of(file(
            "conanfile.py", asset(assets, repositoryId, "proxy/conanfile.py"), "6")));
    ConanRegistryDao.CommittedRevision proxy = inTransaction(() -> dao.restoreRevision(proxyRecipe));
    assertThrows(IllegalArgumentException.class, () -> dao.restoreRevision(new ConanRegistryDao.RevisionCommit(
        proxyCoordinate, null, ConanRegistryDao.OWNER_RECIPE, "bad", null, null,
        Map.of(), Map.of(), Map.of(), null, ConanRegistryDao.SOURCE_PROXY,
        ConanRegistryDao.STATUS_DISCOVERED, NOW, List.of())));
    assertThrows(IllegalArgumentException.class, () -> dao.restoreRevision(new ConanRegistryDao.RevisionCommit(
        proxyCoordinate, null, ConanRegistryDao.OWNER_RECIPE, "bad", null, null,
        Map.of(), Map.of(), Map.of(), null, ConanRegistryDao.SOURCE_HOSTED,
        ConanRegistryDao.STATUS_DISCOVERED, NOW, List.of(file(
            "conanfile.py", asset(assets, repositoryId, "proxy/bad.py"), "7")))));
    assertThrows(IllegalArgumentException.class, () -> dao.recordDiscoveredRevision(recipe));

    long observedId = asset(assets, repositoryId, "proxy/metadata");
    long revisionBeforeBind = dao.currentRepositoryRevision(repositoryId);
    ConanRegistryDao.RevisionFile observed = inTransaction(() -> dao.bindDiscoveredFile(
        ConanRegistryDao.OWNER_RECIPE, proxy.ownerId(),
        file("metadata", observedId, "8"), revisionBeforeBind));
    assertEquals(observedId, observed.assetId());
    assertThrows(IllegalStateException.class, () -> inTransaction(() -> dao.bindDiscoveredFile(
        ConanRegistryDao.OWNER_RECIPE, proxy.ownerId(), file("metadata/old", observedId, "9"),
        revisionBeforeBind)));
    assertEquals("metadata/sign",
        inTransaction(() -> dao.upsertMetadataFile(
            ConanRegistryDao.OWNER_RECIPE, proxy.ownerId(),
            file("metadata/sign", asset(assets, repositoryId, "proxy/sign"), "a"), repositoryId))
            .path());
    assertThrows(IllegalArgumentException.class, () -> inTransaction(() -> dao.upsertMetadataFile(
        ConanRegistryDao.OWNER_RECIPE, proxy.ownerId(), file("metadata/bad", observedId, "b"),
        otherRepositoryId)));
    assertThrows(IllegalArgumentException.class, () -> inTransaction(() -> dao.upsertMetadataFile(
        ConanRegistryDao.OWNER_RECIPE, Long.MAX_VALUE, file("metadata/missing", observedId, "c"),
        repositoryId)));

    ConanRegistryDao.RevisionCommit discoveredPackage = revisionCommit(
        proxyCoordinate, ConanRegistryDao.OWNER_PACKAGE, RREV_TWO, "proxy-package", "proxy-prev",
        Map.of(), ConanRegistryDao.SOURCE_PROXY, ConanRegistryDao.STATUS_DISCOVERED, List.of());
    ConanRegistryDao.CommittedRevision discovered = inTransaction(
        () -> dao.recordDiscoveredRevision(discoveredPackage));
    ConanRegistryDao.RevisionCommit enrichedPackage = revisionCommit(
        proxyCoordinate, ConanRegistryDao.OWNER_PACKAGE, RREV_TWO, "proxy-package", "proxy-prev",
        Map.of("os", "Linux"), ConanRegistryDao.SOURCE_PROXY,
        ConanRegistryDao.STATUS_DISCOVERED, List.of());
    assertTrue(inTransaction(() -> dao.recordDiscoveredRevision(enrichedPackage)).idempotent());
    assertEquals("Linux", dao.findPackage(
        proxy.recipeRevisionId(), "proxy-package").orElseThrow().settings().get("os"));
    assertThrows(IllegalStateException.class, () -> inTransaction(() ->
        dao.recordDiscoveredRevision(revisionCommit(
            proxyCoordinate, ConanRegistryDao.OWNER_PACKAGE, RREV_TWO,
            "proxy-package", "proxy-prev", Map.of("os", "Windows"),
            ConanRegistryDao.SOURCE_PROXY, ConanRegistryDao.STATUS_DISCOVERED, List.of()))));
    assertEquals(proxy.ownerId(), dao.findFileByAssetId(observedId).orElseThrow().file().ownerId(),
        "recipe metadata remains bound to its recipe owner");

    assertThrows(IllegalArgumentException.class, () -> dao.findFile("invalid", 1, "path"));
    assertThrows(IllegalArgumentException.class, () -> dao.findRecipeRevision(1, " "));
    assertThrows(IllegalArgumentException.class, () -> dao.findPackageRevision(1, null));
    assertThrows(NullPointerException.class, () -> dao.commitRevision(null));
    assertThrows(IllegalArgumentException.class, () -> inTransaction(() -> dao.commitRevision(
        revisionCommit(
            coordinate(repositoryId, "duplicate-files"), ConanRegistryDao.OWNER_RECIPE,
            "duplicate", null, null, Map.of(), ConanRegistryDao.SOURCE_HOSTED,
            ConanRegistryDao.STATUS_COMMITTED,
            List.of(
                file("conanfile.py", asset(assets, repositoryId, "duplicate/one"), "d"),
                file("conanfile.py", asset(assets, repositoryId, "duplicate/two"), "e"))))));
  }

  @Test
  void uploadLeaseGroupTokenAndRepositoryCleanupBranchesAreDurable() {
    long repositoryId = insertRepository("conan-state-branches", "conan");
    long groupId = insertRepository("conan-state-group", "conan");
    ConanRegistryDao dao = stores().conanRegistry();
    AssetDao assets = stores().assets();
    Instant clock = Instant.now();

    ConanRegistryDao.UploadSession candidate = new ConanRegistryDao.UploadSession(
        null, repositoryId, ConanRegistryDao.OWNER_RECIPE, "upload-coordinate", "actor",
        ConanRegistryDao.SESSION_OPEN, "publisher-a", -1, null, clock.plusSeconds(60),
        null, clock);
    ConanRegistryDao.UploadSession session = dao.openUploadSession(candidate);
    assertEquals(session.id(), dao.findUploadSession(session.id()).orElseThrow().id());
    assertEquals(session.id(), dao.findUploadSession(
        repositoryId, ConanRegistryDao.OWNER_RECIPE, "upload-coordinate", "actor")
        .orElseThrow().id());
    ConanRegistryDao.UploadSession updated = dao.openUploadSession(new ConanRegistryDao.UploadSession(
        null, repositoryId, ConanRegistryDao.OWNER_RECIPE, "upload-coordinate", "actor",
        ConanRegistryDao.SESSION_OPEN, "publisher-b", 2, null, clock.plusSeconds(120),
        clock, clock.plusSeconds(1)));
    assertEquals("publisher-b", updated.owner());

    long stagingId = asset(assets, repositoryId, "state/staged");
    ConanRegistryDao.UploadFile upload = new ConanRegistryDao.UploadFile(
        null, session.id(), "conanfile.py", stagingId, "a".repeat(32), "b".repeat(40),
        "c".repeat(64), 10, null, null, clock);
    dao.upsertUploadFile(upload);
    dao.upsertUploadFile(new ConanRegistryDao.UploadFile(
        null, session.id(), "conanfile.py", stagingId, "d".repeat(32), "e".repeat(40),
        "f".repeat(64), 11, "text/x-python", clock, clock.plusSeconds(1)));
    assertEquals(11, dao.listUploadFiles(session.id()).getFirst().size());
    assertTrue(dao.beginSessionCommit(session.id(), 3, clock.plusSeconds(30)));
    assertFalse(dao.beginSessionCommit(session.id(), 3, clock.plusSeconds(30)));
    assertEquals(1, dao.deleteUploadSession(session.id()));
    assertEquals(0, dao.deleteUploadSession(session.id()));
    assertTrue(inTransaction(() -> dao.claimExpiredUploadSessions(
        "cleaner", clock, clock.plusSeconds(30), 0)).isEmpty());

    assertThrows(NullPointerException.class, () -> dao.openUploadSession(null));
    assertThrows(IllegalArgumentException.class, () -> dao.openUploadSession(
        new ConanRegistryDao.UploadSession(
            null, repositoryId, "bad", "coordinate", "actor", ConanRegistryDao.SESSION_OPEN,
            "owner", 0, null, clock, clock, clock)));
    assertThrows(IllegalArgumentException.class, () -> dao.upsertUploadFile(
        new ConanRegistryDao.UploadFile(
            null, 0, "path", 0, "a", "b", "c", -1, null, clock, clock)));

    ConanRegistryDao.Lease lease = inTransaction(() -> dao.tryAcquireLease(
        repositoryId, "lease-coordinate", "node-a", clock.plusSeconds(120))).orElseThrow();
    assertTrue(inTransaction(() -> dao.tryAcquireLease(
        repositoryId, "lease-coordinate", "node-b", clock.plusSeconds(120))).isEmpty());
    assertTrue(dao.renewLease(
        repositoryId, lease.coordinateKey(), lease.owner(), lease.fencingToken(),
        clock.plusSeconds(180)));
    assertFalse(dao.renewLease(
        repositoryId, lease.coordinateKey(), "wrong", lease.fencingToken(),
        clock.plusSeconds(180)));
    dao.releaseLease(
        repositoryId, lease.coordinateKey(), lease.owner(), lease.fencingToken());
    assertEquals(1, inTransaction(() -> dao.deleteExpiredLeases(clock, 0)));
    assertTrue(inTransaction(() -> dao.deleteExpiredLeases(null, 10)) >= 0);

    long groupRevision = dao.nextRepositoryRevision(groupId);
    long memberRevision = dao.nextRepositoryRevision(repositoryId);
    ConanRegistryDao.GroupBinding binding = new ConanRegistryDao.GroupBinding(
        groupId, ConanRegistryDao.OWNER_RECIPE, "coordinate", repositoryId, 10,
        memberRevision, groupRevision, null, clock, null);
    assertFalse(inTransaction(() -> dao.upsertGroupBindingIfCurrent(new ConanRegistryDao.GroupBinding(
        groupId, ConanRegistryDao.OWNER_RECIPE, "group-stale", repositoryId, 10,
        memberRevision, groupRevision + 1, null, clock, clock))));
    assertFalse(inTransaction(() -> dao.upsertGroupBindingIfCurrent(new ConanRegistryDao.GroupBinding(
        groupId, ConanRegistryDao.OWNER_RECIPE, "member-stale", repositoryId, 10,
        memberRevision + 1, groupRevision, null, clock, clock))));
    assertTrue(inTransaction(() -> dao.upsertGroupBindingIfCurrent(binding)));
    assertTrue(inTransaction(() -> dao.upsertGroupBindingIfCurrent(binding)));
    assertEquals(repositoryId, dao.findGroupBinding(
        groupId, ConanRegistryDao.OWNER_RECIPE, "coordinate").orElseThrow().memberRepositoryId());
    assertTrue(inTransaction(() -> dao.upsertGroupBindingIfCurrent(new ConanRegistryDao.GroupBinding(
        groupId, ConanRegistryDao.OWNER_PACKAGE, "expired", repositoryId, 11,
        memberRevision, groupRevision, clock.minusSeconds(1), clock, clock))));
    assertTrue(dao.findGroupBinding(
        groupId, ConanRegistryDao.OWNER_PACKAGE, "expired").isEmpty());
    assertEquals(2, dao.deleteGroupBindings(groupId));
    assertEquals(0, dao.deleteGroupBindingsForMember(repositoryId));

    assertThrows(IllegalArgumentException.class, () -> dao.insertAuthToken(
        new ConanRegistryDao.AuthToken(
            "ABC", repositoryId, "LOCAL", "user", null, null,
            clock.plusSeconds(60), null, clock)));
    assertThrows(IllegalArgumentException.class, () -> dao.insertAuthToken(
        new ConanRegistryDao.AuthToken(
            "a".repeat(64), repositoryId, " ", "user", null, null,
            clock.plusSeconds(60), null, clock)));
    assertEquals(0, inTransaction(() -> dao.deleteExpiredAuthTokens(clock, 10)));
    dao.insertAuthToken(new ConanRegistryDao.AuthToken(
        "a".repeat(64), repositoryId, "LOCAL", "user", "realm", 12L,
        clock.plusSeconds(60), clock, clock));
    assertEquals("realm", dao.findValidAuthToken(
        "a".repeat(64), repositoryId, clock).orElseThrow().realmId());

    inTransaction(() -> dao.commitRevision(recipeCommit(
        coordinate(repositoryId, "cleanup-state"), RREV_ONE, clock,
        asset(assets, repositoryId, "cleanup-state/conanfile.py"),
        asset(assets, repositoryId, "cleanup-state/conanmanifest.txt"))));
    assertTrue(dao.status(repositoryId).recipes() > 0);
    assertEquals(1, inTransaction(() -> dao.deleteRepositoryState(repositoryId)));
    assertEquals(0, dao.status(repositoryId).recipes());
    assertEquals(0, inTransaction(() -> dao.deleteRepositoryState(repositoryId)));
  }

  @Test
  void deleteVariantsCollectAssetsAndRefreshLatestPointers() {
    long repositoryId = insertRepository("conan-delete-branches", "conan");
    ConanRegistryDao dao = stores().conanRegistry();
    AssetDao assets = stores().assets();

    assertFalse(inTransaction(() -> dao.deleteCoordinate(
        new ConanRegistryDao.DeleteTarget(
            coordinate(repositoryId, "missing-delete"), null, null, null),
        "test", NOW)).deleted());
    assertThrows(NullPointerException.class, () -> dao.deleteCoordinate(null, "test", NOW));

    ConanRegistryDao.RecipeCoordinate previousCoordinate =
        coordinate(repositoryId, "delete-previous");
    ConanRegistryDao.CommittedRevision previousRecipe = publishRecipe(
        dao, assets, previousCoordinate, RREV_ONE, "delete-previous/recipe-one", NOW);
    ConanRegistryDao.CommittedRevision previousOne = publishPackage(
        dao, assets, previousCoordinate, RREV_ONE, PACKAGE_ID, PREV,
        "delete-previous/package-one", NOW.plusSeconds(1));
    String secondPrev = "5".repeat(32);
    publishPackage(
        dao, assets, previousCoordinate, RREV_ONE, PACKAGE_ID, secondPrev,
        "delete-previous/package-two", NOW.plusSeconds(2));
    assertFalse(inTransaction(() -> dao.deleteCoordinate(
        new ConanRegistryDao.DeleteTarget(
            previousCoordinate, "missing-rrev", PACKAGE_ID, PREV),
        "test", NOW)).deleted());
    assertFalse(inTransaction(() -> dao.deleteCoordinate(
        new ConanRegistryDao.DeleteTarget(
            previousCoordinate, RREV_ONE, "missing-package", PREV),
        "test", NOW)).deleted());
    assertFalse(inTransaction(() -> dao.deleteCoordinate(
        new ConanRegistryDao.DeleteTarget(
            previousCoordinate, RREV_ONE, PACKAGE_ID, "missing-prev"),
        "test", NOW)).deleted());
    assertTrue(inTransaction(() -> dao.deleteCoordinate(
        new ConanRegistryDao.DeleteTarget(previousCoordinate, RREV_ONE, PACKAGE_ID, PREV),
        "test", NOW)).deleted());
    assertEquals(secondPrev,
        dao.findLatestPackageRevision(previousOne.packageRowId()).orElseThrow().revision());
    assertTrue(inTransaction(() -> dao.deleteCoordinate(
        new ConanRegistryDao.DeleteTarget(previousCoordinate, RREV_ONE, PACKAGE_ID, secondPrev),
        "test", NOW)).deleted());
    assertTrue(dao.findPackage(previousRecipe.recipeRevisionId(), PACKAGE_ID).isEmpty());

    ConanRegistryDao.RecipeCoordinate packageCoordinate =
        coordinate(repositoryId, "delete-package");
    ConanRegistryDao.CommittedRevision packageRecipe = publishRecipe(
        dao, assets, packageCoordinate, RREV_ONE, "delete-package/recipe", NOW);
    publishPackage(
        dao, assets, packageCoordinate, RREV_ONE, PACKAGE_ID, PREV,
        "delete-package/package", NOW.plusSeconds(1));
    assertTrue(inTransaction(() -> dao.deleteCoordinate(
        new ConanRegistryDao.DeleteTarget(packageCoordinate, RREV_ONE, PACKAGE_ID, null),
        "test", NOW)).deleted());
    assertTrue(dao.findPackage(packageRecipe.recipeRevisionId(), PACKAGE_ID).isEmpty());

    ConanRegistryDao.RecipeCoordinate revisionCoordinate =
        coordinate(repositoryId, "delete-revision");
    ConanRegistryDao.CommittedRevision oldRevision = publishRecipe(
        dao, assets, revisionCoordinate, RREV_ONE, "delete-revision/one", NOW);
    publishPackage(
        dao, assets, revisionCoordinate, RREV_ONE, PACKAGE_ID, PREV,
        "delete-revision/package", NOW.plusSeconds(1));
    ConanRegistryDao.CommittedRevision newRevision = publishRecipe(
        dao, assets, revisionCoordinate, RREV_TWO, "delete-revision/two", NOW.plusSeconds(2));
    assertTrue(inTransaction(() -> dao.deleteCoordinate(
        new ConanRegistryDao.DeleteTarget(revisionCoordinate, RREV_ONE, null, null),
        "test", NOW)).deleted());
    assertEquals(RREV_TWO,
        dao.findLatestRecipeRevision(newRevision.recipeId()).orElseThrow().revision());
    assertTrue(dao.findRecipeRevision(oldRevision.recipeId(), RREV_ONE).isEmpty());
    assertTrue(inTransaction(() -> dao.deleteCoordinate(
        new ConanRegistryDao.DeleteTarget(revisionCoordinate, RREV_TWO, null, null),
        "test", NOW)).deleted());
    assertTrue(dao.findRecipe(revisionCoordinate).isEmpty());

    ConanRegistryDao.RecipeCoordinate allPackagesCoordinate =
        coordinate(repositoryId, "delete-all-packages");
    publishRecipe(
        dao, assets, allPackagesCoordinate, RREV_ONE, "delete-all/recipe", NOW);
    assertFalse(inTransaction(() -> dao.deleteAllPackages(
        coordinate(repositoryId, "missing-all"), RREV_ONE, "test", NOW)).deleted());
    assertFalse(inTransaction(() -> dao.deleteAllPackages(
        allPackagesCoordinate, "missing-rrev", "test", NOW)).deleted());
    ConanRegistryDao.DeletedCoordinate empty = inTransaction(() -> dao.deleteAllPackages(
        allPackagesCoordinate, RREV_ONE, "test", NOW));
    assertFalse(empty.deleted());
    assertTrue(empty.repositoryRevision() > 0);
    publishPackage(
        dao, assets, allPackagesCoordinate, RREV_ONE, PACKAGE_ID, PREV,
        "delete-all/package-one", NOW.plusSeconds(1));
    publishPackage(
        dao, assets, allPackagesCoordinate, RREV_ONE, "6".repeat(40), PREV,
        "delete-all/package-two", NOW.plusSeconds(2));
    ConanRegistryDao.DeletedCoordinate all = inTransaction(() -> dao.deleteAllPackages(
        allPackagesCoordinate, RREV_ONE, "test", NOW));
    assertTrue(all.deleted());
    assertEquals(6, all.assetIds().size());

    ConanRegistryDao.RecipeCoordinate entireCoordinate =
        coordinate(repositoryId, "delete-entire-recipe");
    publishRecipe(dao, assets, entireCoordinate, RREV_ONE, "delete-entire/recipe", NOW);
    publishPackage(
        dao, assets, entireCoordinate, RREV_ONE, PACKAGE_ID, PREV,
        "delete-entire/package", NOW.plusSeconds(1));
    ConanRegistryDao.DeletedCoordinate entire = inTransaction(() -> dao.deleteCoordinate(
        new ConanRegistryDao.DeleteTarget(entireCoordinate, null, null, null),
        "test", NOW));
    assertTrue(entire.deleted());
    assertEquals(5, entire.assetIds().size());
    assertTrue(dao.findRecipe(entireCoordinate).isEmpty());
  }

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

  private static ConanRegistryDao.RevisionCommit withComponent(
      ConanRegistryDao.RevisionCommit commit, long componentId) {
    return new ConanRegistryDao.RevisionCommit(
        commit.coordinate(), componentId, commit.ownerKind(), commit.recipeRevision(),
        commit.packageId(), commit.packageRevision(), commit.settings(), commit.options(),
        commit.requires(), commit.manifestSha256(), commit.sourceKind(), commit.status(),
        commit.publishedAt(), commit.files());
  }

  private ConanRegistryDao.CommittedRevision publishRecipe(
      ConanRegistryDao dao,
      AssetDao assets,
      ConanRegistryDao.RecipeCoordinate coordinate,
      String revision,
      String assetPrefix,
      Instant publishedAt) {
    return inTransaction(() -> dao.commitRevision(recipeCommit(
        coordinate, revision, publishedAt,
        asset(assets, coordinate.repositoryId(), assetPrefix + "/conanfile.py"),
        asset(assets, coordinate.repositoryId(), assetPrefix + "/conanmanifest.txt"))));
  }

  private ConanRegistryDao.CommittedRevision publishPackage(
      ConanRegistryDao dao,
      AssetDao assets,
      ConanRegistryDao.RecipeCoordinate coordinate,
      String recipeRevision,
      String packageId,
      String packageRevision,
      String assetPrefix,
      Instant publishedAt) {
    return inTransaction(() -> dao.commitRevision(packageCommit(
        coordinate, recipeRevision, packageId, packageRevision, publishedAt,
        asset(assets, coordinate.repositoryId(), assetPrefix + "/conan_package.tgz"),
        asset(assets, coordinate.repositoryId(), assetPrefix + "/conaninfo.txt"),
        asset(assets, coordinate.repositoryId(), assetPrefix + "/conanmanifest.txt"))));
  }

  private static ConanRegistryDao.RevisionCommit revisionCommit(
      ConanRegistryDao.RecipeCoordinate coordinate,
      String ownerKind,
      String recipeRevision,
      String packageId,
      String packageRevision,
      Map<String, String> settings,
      String sourceKind,
      String status,
      List<ConanRegistryDao.FileCommit> files) {
    return new ConanRegistryDao.RevisionCommit(
        coordinate, null, ownerKind, recipeRevision, packageId, packageRevision,
        settings, Map.of(), Map.of(), files.isEmpty() ? null : "e".repeat(64),
        sourceKind, status, NOW, files);
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
