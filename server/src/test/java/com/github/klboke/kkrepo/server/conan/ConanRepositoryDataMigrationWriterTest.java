package com.github.klboke.kkrepo.server.conan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
import com.github.klboke.kkrepo.server.maven.MavenResponse;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntime;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntimeRegistry;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ConanRepositoryDataMigrationWriterTest {
  private static final String PATH =
      "conans/demo/1.0/acme/stable/revisions/rrev/files/conan_export.tgz";
  private static final Instant PUBLISHED_AT = Instant.parse("2026-08-01T02:03:04Z");

  @Test
  void selectsOnlyCanonicalConanTwoRevisionFiles() {
    assertTrue(ConanRepositoryDataMigrationWriter.isMigratableConanPath(PATH));
    assertTrue(ConanRepositoryDataMigrationWriter.isMigratableConanPath(
        "conans/demo/1.0/acme/stable/revisions/rrev/packages/pkg/revisions/prev/"
            + "files/conan_package.tgz"));
    assertFalse(ConanRepositoryDataMigrationWriter.isMigratableConanPath(
        "demo/1.0/acme/stable/package.tgz"));
    assertFalse(ConanRepositoryDataMigrationWriter.isMigratableConanPath(
        "conans/demo/1.0/acme/stable/revisions/rrev/files/../escape"));
    assertFalse(ConanRepositoryDataMigrationWriter.isMigratableConanPath(null));
  }

  @Test
  void stagesSourceThroughManifestGatedProtocolWriter() throws Exception {
    Fixture fixture = fixture();
    byte[] bytes = "recipe archive".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    String sha1 = sha1(bytes);
    ConanRegistryDao.UploadSession session = new ConanRegistryDao.UploadSession(
        11L, fixture.runtime().id(), ConanRegistryDao.OWNER_RECIPE,
        "demo/1.0@acme/stable#rrev",
        DatabaseCompositeKey.of("MIGRATION", "nexus-migration"),
        ConanRegistryDao.SESSION_OPEN, "node", 0, null,
        PUBLISHED_AT.plusSeconds(3600), PUBLISHED_AT, PUBLISHED_AT);
    ConanRegistryDao.UploadFile stagedFile = new ConanRegistryDao.UploadFile(
        12L, session.id(), "conan_export.tgz", 101L, "a".repeat(32), sha1,
        "b".repeat(64), bytes.length, "application/gzip", PUBLISHED_AT, PUBLISHED_AT);
    when(fixture.service().putMigration(
        eq(fixture.runtime()), eq("v2/conans/demo/1.0/acme/stable/revisions/rrev/"
            + "files/conan_export.tgz"), any(InputStream.class), eq((long) bytes.length),
        eq("application/gzip"), eq(sha1), eq("nexus-migration"), eq("10.0.0.8"),
        eq(PUBLISHED_AT))).thenReturn(MavenResponse.noBody(200));
    when(fixture.registry().findUploadSession(
        fixture.runtime().id(), ConanRegistryDao.OWNER_RECIPE,
        "demo/1.0@acme/stable#rrev",
        DatabaseCompositeKey.of("MIGRATION", "nexus-migration")))
        .thenReturn(Optional.of(session));
    when(fixture.registry().listUploadFiles(session.id())).thenReturn(List.of(stagedFile));
    bindAsset(fixture, sha1, bytes.length);

    var migrated = fixture.writer().write(
        fixture.repository(), source(bytes.length, sha1), new ByteArrayInputStream(bytes),
        "application/gzip", true);

    assertFalse(migrated.committed());
    assertEquals(101L, migrated.assetId());
    assertEquals(102L, migrated.assetBlobId());
    verify(fixture.service()).putMigration(
        eq(fixture.runtime()), eq("v2/conans/demo/1.0/acme/stable/revisions/rrev/"
            + "files/conan_export.tgz"), any(InputStream.class), eq((long) bytes.length),
        eq("application/gzip"), eq(sha1), eq("nexus-migration"), eq("10.0.0.8"),
        eq(PUBLISHED_AT));
  }

  @Test
  void resumesCommittedFilesAndRetargetsTheDurableMigrationRow() throws Exception {
    Fixture fixture = fixture();
    byte[] bytes = "recipe archive".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    String sha1 = sha1(bytes);
    ConanRegistryDao.Recipe recipe = new ConanRegistryDao.Recipe(
        21L, fixture.runtime().id(), 201L, "demo", "1.0", "acme", "stable",
        31L, PUBLISHED_AT, PUBLISHED_AT);
    ConanRegistryDao.RecipeRevision revision = new ConanRegistryDao.RecipeRevision(
        31L, recipe.id(), "rrev", "b".repeat(64), ConanRegistryDao.SOURCE_HOSTED,
        ConanRegistryDao.STATUS_COMMITTED, 1L, PUBLISHED_AT, PUBLISHED_AT);
    ConanRegistryDao.RevisionFile file = new ConanRegistryDao.RevisionFile(
        41L, ConanRegistryDao.OWNER_RECIPE, revision.id(), "conan_export.tgz", 101L,
        "a".repeat(32), sha1, "b".repeat(64), bytes.length, "application/gzip",
        fixture.runtime().id(), PUBLISHED_AT, PUBLISHED_AT);
    when(fixture.registry().findRecipe(any())).thenReturn(Optional.of(recipe));
    when(fixture.registry().findRecipeRevision(recipe.id(), "rrev"))
        .thenReturn(Optional.of(revision));
    when(fixture.registry().findFile(
        ConanRegistryDao.OWNER_RECIPE, revision.id(), "conan_export.tgz"))
        .thenReturn(Optional.of(file));
    when(fixture.registry().listFiles(
        ConanRegistryDao.OWNER_RECIPE, revision.id(), null, 10_000))
        .thenReturn(List.of(file));
    bindAsset(fixture, sha1, bytes.length);

    var migrated = fixture.writer().write(
        fixture.repository(), source(bytes.length, sha1), new ByteArrayInputStream(bytes),
        "application/gzip", true);

    assertTrue(migrated.committed());
    verify(fixture.service(), never()).putMigration(
        any(), any(), any(), any(Long.class), any(), any(), any(), any(), any());
    verify(fixture.migrations()).retargetMigratedAsset(
        2L, PATH, 201L, 101L, 102L);
  }

  @Test
  void rejectsMissingChecksumAndGroupTargets() throws Exception {
    Fixture fixture = fixture();
    byte[] bytes = "recipe archive".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    assertThrows(IllegalStateException.class, () -> fixture.writer().write(
        fixture.repository(), source(bytes.length, null), new ByteArrayInputStream(bytes),
        "application/gzip", true));

    RepositoryRecord group = new RepositoryRecord(
        9L, "conan-group", RepositoryFormat.CONAN, RepositoryType.GROUP, "conan-group",
        true, null, null, null, null, null, null, true, Map.of());
    assertThrows(IllegalArgumentException.class, () -> fixture.writer().write(
        group, source(bytes.length, sha1(bytes)), new ByteArrayInputStream(bytes),
        "application/gzip", true));
  }

  @Test
  void resumesCommittedPackageRevisionAndRetargetsItsBoundFiles() throws Exception {
    Fixture fixture = fixture();
    byte[] bytes = "binary archive".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    String sha1 = sha1(bytes);
    String path = "conans/demo/1.0/acme/stable/revisions/rrev/packages/pkg/revisions/prev/"
        + "files/conan_package.tgz";
    ConanRegistryDao.Recipe recipe = recipe(fixture);
    ConanRegistryDao.RecipeRevision revision = revision(recipe);
    ConanRegistryDao.Package pkg = new ConanRegistryDao.Package(
        41L, revision.id(), "pkg", Map.of("os", "Linux"), Map.of(), Map.of(),
        51L, PUBLISHED_AT, PUBLISHED_AT);
    ConanRegistryDao.PackageRevision packageRevision = new ConanRegistryDao.PackageRevision(
        51L, pkg.id(), "prev", "c".repeat(64), ConanRegistryDao.SOURCE_HOSTED,
        ConanRegistryDao.STATUS_COMMITTED, 1L, PUBLISHED_AT, PUBLISHED_AT);
    ConanRegistryDao.RevisionFile file = new ConanRegistryDao.RevisionFile(
        61L, ConanRegistryDao.OWNER_PACKAGE, packageRevision.id(), "conan_package.tgz", 101L,
        "a".repeat(32), sha1, "b".repeat(64), bytes.length, "application/gzip",
        fixture.runtime().id(), PUBLISHED_AT, PUBLISHED_AT);
    ConanRegistryDao.RevisionFile unbound = new ConanRegistryDao.RevisionFile(
        62L, ConanRegistryDao.OWNER_PACKAGE, packageRevision.id(), "metadata.json", null,
        "a".repeat(32), sha1, "b".repeat(64), bytes.length, "application/json",
        fixture.runtime().id(), PUBLISHED_AT, PUBLISHED_AT);
    when(fixture.registry().findRecipe(any())).thenReturn(Optional.of(recipe));
    when(fixture.registry().findRecipeRevision(recipe.id(), "rrev"))
        .thenReturn(Optional.of(revision));
    when(fixture.registry().findPackage(revision.id(), "pkg")).thenReturn(Optional.of(pkg));
    when(fixture.registry().findPackageRevision(pkg.id(), "prev"))
        .thenReturn(Optional.of(packageRevision));
    when(fixture.registry().findFile(
        ConanRegistryDao.OWNER_PACKAGE, packageRevision.id(), "conan_package.tgz"))
        .thenReturn(Optional.of(file));
    when(fixture.registry().listFiles(
        ConanRegistryDao.OWNER_PACKAGE, packageRevision.id(), null, 10_000))
        .thenReturn(List.of(unbound, file));
    bindAsset(fixture, sha1, bytes.length);

    var migrated = fixture.writer().write(
        fixture.repository(), source(path, bytes.length, Map.of("SHA-1", sha1)),
        new ByteArrayInputStream(bytes), "application/gzip", true);

    assertTrue(migrated.committed());
    verify(fixture.migrations()).retargetMigratedAsset(2L, path, 201L, 101L, 102L);
  }

  @Test
  void rejectsDisappearingStagingRowsAndMissingAssetBindings() throws Exception {
    byte[] bytes = "recipe archive".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    String sha1 = sha1(bytes);

    Fixture missingSession = fixture();
    stubMigrationResponse(missingSession, bytes.length, sha1, "application/gzip", PUBLISHED_AT);
    assertThrows(IllegalStateException.class, () -> missingSession.writer().write(
        missingSession.repository(), source(bytes.length, sha1),
        new ByteArrayInputStream(bytes), "application/gzip", true));

    Fixture missingFile = fixture();
    ConanRegistryDao.UploadSession session = uploadSession(missingFile);
    stubMigrationResponse(missingFile, bytes.length, sha1, "application/gzip", PUBLISHED_AT);
    when(missingFile.registry().findUploadSession(
        missingFile.runtime().id(), ConanRegistryDao.OWNER_RECIPE,
        "demo/1.0@acme/stable#rrev",
        DatabaseCompositeKey.of("MIGRATION", "nexus-migration")))
        .thenReturn(Optional.of(session));
    when(missingFile.registry().listUploadFiles(session.id())).thenReturn(List.of());
    assertThrows(IllegalStateException.class, () -> missingFile.writer().write(
        missingFile.repository(), source(bytes.length, sha1),
        new ByteArrayInputStream(bytes), "application/gzip", true));

    Fixture missingAsset = fixture();
    stubStagedFile(missingAsset, bytes.length, sha1);
    stubMigrationResponse(missingAsset, bytes.length, sha1, "application/gzip", PUBLISHED_AT);
    assertThrows(IllegalStateException.class, () -> missingAsset.writer().write(
        missingAsset.repository(), source(bytes.length, sha1),
        new ByteArrayInputStream(bytes), "application/gzip", true));

    Fixture missingBlob = fixture();
    stubStagedFile(missingBlob, bytes.length, sha1);
    stubMigrationResponse(missingBlob, bytes.length, sha1, "application/gzip", PUBLISHED_AT);
    AssetRecord assetWithoutBlob = new AssetRecord(
        101L, missingBlob.runtime().id(), 201L, null, RepositoryFormat.CONAN,
        ".conan/staging/11/conan_export.tgz", new byte[32], "conan_export.tgz",
        "conan-revision-file", "application/gzip", (long) bytes.length, null,
        PUBLISHED_AT, Map.of());
    when(missingBlob.assets().findAssetById(101L)).thenReturn(Optional.of(assetWithoutBlob));
    assertThrows(IllegalStateException.class, () -> missingBlob.writer().write(
        missingBlob.repository(), source(bytes.length, sha1),
        new ByteArrayInputStream(bytes), "application/gzip", true));
  }

  @Test
  void validatesCommittedChecksumAndOptionalSize() throws Exception {
    byte[] bytes = "recipe archive".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    String sha1 = sha1(bytes);

    Fixture checksumMismatch = fixture();
    bindCommittedRecipe(checksumMismatch, sha1, bytes.length);
    bindAsset(checksumMismatch, "0".repeat(40), bytes.length);
    assertThrows(IllegalStateException.class, () -> checksumMismatch.writer().write(
        checksumMismatch.repository(), source(bytes.length, sha1),
        new ByteArrayInputStream(bytes), "application/gzip", true));

    Fixture sizeMismatch = fixture();
    bindCommittedRecipe(sizeMismatch, sha1, bytes.length);
    bindAsset(sizeMismatch, sha1, bytes.length + 1L);
    assertThrows(IllegalStateException.class, () -> sizeMismatch.writer().write(
        sizeMismatch.repository(), source(bytes.length, sha1),
        new ByteArrayInputStream(bytes), "application/gzip", true));

    Fixture sizeIgnored = fixture();
    bindCommittedRecipe(sizeIgnored, sha1, bytes.length);
    bindAsset(sizeIgnored, sha1, bytes.length + 1L);
    assertTrue(sizeIgnored.writer().write(
        sizeIgnored.repository(), source(bytes.length, sha1),
        new ByteArrayInputStream(bytes), "application/gzip", false).committed());
  }

  @Test
  void acceptsNormalizedNestedChecksumsAndMetadataFallbacks() throws Exception {
    Fixture fixture = fixture();
    byte[] bytes = "recipe archive".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    String sha1 = sha1(bytes);
    ConanRegistryDao.UploadSession session = uploadSession(fixture);
    ConanRegistryDao.UploadFile stagedFile = new ConanRegistryDao.UploadFile(
        12L, session.id(), "conan_export.tgz", 101L, "a".repeat(32), sha1,
        "b".repeat(64), bytes.length, "application/octet-stream", PUBLISHED_AT, PUBLISHED_AT);
    when(fixture.registry().findUploadSession(
        fixture.runtime().id(), ConanRegistryDao.OWNER_RECIPE,
        "demo/1.0@acme/stable#rrev",
        DatabaseCompositeKey.of("MIGRATION", "nexus-migration")))
        .thenReturn(Optional.of(session));
    when(fixture.registry().listUploadFiles(session.id())).thenReturn(List.of(stagedFile));
    bindAsset(fixture, sha1, bytes.length);
    Instant lastUpdated = PUBLISHED_AT.plusSeconds(12);
    RepositoryDataMigrationAssetRecord source = source(
        PATH, -1L, Map.of("nested", List.of(Map.of("checksum.sha-1", sha1.toUpperCase()))),
        null, lastUpdated, null, null);
    when(fixture.service().putMigration(
        eq(fixture.runtime()), any(), any(InputStream.class), eq(-1L),
        eq("application/octet-stream"), eq(sha1), eq("nexus-migration"), eq("10.0.0.8"),
        eq(lastUpdated))).thenReturn(MavenResponse.noBody(200));

    assertFalse(fixture.writer().write(
        fixture.repository(), source, new ByteArrayInputStream(bytes), " ", false).committed());
  }

  @Test
  void rejectsUnavailableAndNonConanTargets() throws Exception {
    byte[] bytes = "recipe archive".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    String sha1 = sha1(bytes);
    Fixture unavailable = fixture();
    when(unavailable.runtimes().resolveById(unavailable.runtime().id()))
        .thenReturn(Optional.empty());
    assertThrows(IllegalArgumentException.class, () -> unavailable.writer().write(
        unavailable.repository(), source(bytes.length, sha1), new ByteArrayInputStream(bytes),
        "application/gzip", true));

    RepositoryRecord maven = new RepositoryRecord(
        9L, "maven", RepositoryFormat.MAVEN2, RepositoryType.HOSTED, "maven2-hosted",
        true, null, null, null, null, null, null, true, Map.of());
    assertThrows(IllegalArgumentException.class, () -> unavailable.writer().write(
        maven, source(bytes.length, sha1), null, "application/gzip", true));
  }

  private static Fixture fixture() {
    ConanService service = mock(ConanService.class);
    ConanRegistryDao registry = mock(ConanRegistryDao.class);
    RepositoryRuntimeRegistry runtimes = mock(RepositoryRuntimeRegistry.class);
    RepositoryDataMigrationDao migrations = mock(RepositoryDataMigrationDao.class);
    AssetDao assets = mock(AssetDao.class);
    RepositoryRuntime runtime = new RepositoryRuntime(
        7L, "conan-hosted", RepositoryFormat.CONAN, RepositoryType.HOSTED, "conan-hosted",
        true, 1L, "ALLOW_ONCE", null, null, true, null, null, null, null, null, List.of());
    RepositoryRecord repository = new RepositoryRecord(
        runtime.id(), runtime.name(), RepositoryFormat.CONAN, RepositoryType.HOSTED,
        "conan-hosted", true, 1L, null, null, null, null, "ALLOW_ONCE", true, Map.of());
    when(runtimes.resolveById(runtime.id())).thenReturn(Optional.of(runtime));
    return new Fixture(
        new ConanRepositoryDataMigrationWriter(
            service, registry, runtimes, migrations, assets),
        service, registry, runtimes, migrations, assets, runtime, repository);
  }

  private static void bindAsset(Fixture fixture, String sha1, long size) {
    AssetRecord asset = new AssetRecord(
        101L, fixture.runtime().id(), 201L, 102L, RepositoryFormat.CONAN,
        ".conan/staging/11/conan_export.tgz", new byte[32], "conan_export.tgz",
        "conan-revision-file", "application/gzip", size, null, PUBLISHED_AT, Map.of());
    AssetBlobRecord blob = new AssetBlobRecord(
        102L, 1L, "blob", new byte[32], "conan-object", new byte[32], sha1,
        "b".repeat(64), "a".repeat(32), size, "application/gzip", "nexus-migration",
        "10.0.0.8", PUBLISHED_AT, PUBLISHED_AT, Map.of());
    when(fixture.assets().findAssetById(101L)).thenReturn(Optional.of(asset));
    when(fixture.assets().findBlobById(102L)).thenReturn(Optional.of(blob));
  }

  private static ConanRegistryDao.Recipe recipe(Fixture fixture) {
    return new ConanRegistryDao.Recipe(
        21L, fixture.runtime().id(), 201L, "demo", "1.0", "acme", "stable",
        31L, PUBLISHED_AT, PUBLISHED_AT);
  }

  private static ConanRegistryDao.RecipeRevision revision(ConanRegistryDao.Recipe recipe) {
    return new ConanRegistryDao.RecipeRevision(
        31L, recipe.id(), "rrev", "b".repeat(64), ConanRegistryDao.SOURCE_HOSTED,
        ConanRegistryDao.STATUS_COMMITTED, 1L, PUBLISHED_AT, PUBLISHED_AT);
  }

  private static void bindCommittedRecipe(Fixture fixture, String sha1, long size) {
    ConanRegistryDao.Recipe recipe = recipe(fixture);
    ConanRegistryDao.RecipeRevision revision = revision(recipe);
    ConanRegistryDao.RevisionFile file = new ConanRegistryDao.RevisionFile(
        41L, ConanRegistryDao.OWNER_RECIPE, revision.id(), "conan_export.tgz", 101L,
        "a".repeat(32), sha1, "b".repeat(64), size, "application/gzip",
        fixture.runtime().id(), PUBLISHED_AT, PUBLISHED_AT);
    when(fixture.registry().findRecipe(any())).thenReturn(Optional.of(recipe));
    when(fixture.registry().findRecipeRevision(recipe.id(), "rrev"))
        .thenReturn(Optional.of(revision));
    when(fixture.registry().findFile(
        ConanRegistryDao.OWNER_RECIPE, revision.id(), "conan_export.tgz"))
        .thenReturn(Optional.of(file));
    when(fixture.registry().listFiles(
        ConanRegistryDao.OWNER_RECIPE, revision.id(), null, 10_000))
        .thenReturn(List.of(file));
  }

  private static ConanRegistryDao.UploadSession uploadSession(Fixture fixture) {
    return new ConanRegistryDao.UploadSession(
        11L, fixture.runtime().id(), ConanRegistryDao.OWNER_RECIPE,
        "demo/1.0@acme/stable#rrev",
        DatabaseCompositeKey.of("MIGRATION", "nexus-migration"),
        ConanRegistryDao.SESSION_OPEN, "node", 0, null,
        PUBLISHED_AT.plusSeconds(3600), PUBLISHED_AT, PUBLISHED_AT);
  }

  private static void stubStagedFile(Fixture fixture, long size, String sha1) {
    ConanRegistryDao.UploadSession session = uploadSession(fixture);
    ConanRegistryDao.UploadFile file = new ConanRegistryDao.UploadFile(
        12L, session.id(), "conan_export.tgz", 101L, "a".repeat(32), sha1,
        "b".repeat(64), size, "application/gzip", PUBLISHED_AT, PUBLISHED_AT);
    when(fixture.registry().findUploadSession(
        fixture.runtime().id(), ConanRegistryDao.OWNER_RECIPE,
        "demo/1.0@acme/stable#rrev",
        DatabaseCompositeKey.of("MIGRATION", "nexus-migration")))
        .thenReturn(Optional.of(session));
    when(fixture.registry().listUploadFiles(session.id())).thenReturn(List.of(file));
  }

  private static void stubMigrationResponse(
      Fixture fixture, long size, String sha1, String contentType, Instant publishedAt) {
    when(fixture.service().putMigration(
        eq(fixture.runtime()), any(), any(InputStream.class), eq(size), eq(contentType), eq(sha1),
        eq("nexus-migration"), eq("10.0.0.8"), eq(publishedAt)))
        .thenReturn(MavenResponse.noBody(200));
  }

  private static RepositoryDataMigrationAssetRecord source(long size, String sha1) {
    Map<String, Object> metadata = sha1 == null
        ? Map.of() : Map.of("checksum", Map.of("sha1", sha1));
    return source(PATH, size, metadata);
  }

  private static RepositoryDataMigrationAssetRecord source(
      String path, long size, Map<String, Object> metadata) {
    return source(path, size, metadata, PUBLISHED_AT, null, PUBLISHED_AT,
        "application/gzip");
  }

  private static RepositoryDataMigrationAssetRecord source(
      String path,
      long size,
      Map<String, Object> metadata,
      Instant blobCreatedAt,
      Instant lastUpdatedAt,
      Instant blobUpdatedAt,
      String contentType) {
    return new RepositoryDataMigrationAssetRecord(
        1L, 2L, "asset-1", "component-1", path, new byte[32], RepositoryFormat.CONAN,
        "acme/stable", "demo", "1.0", "conan-revision-file", contentType, size,
        "blob-ref", lastUpdatedAt, null, blobCreatedAt, blobUpdatedAt, "nexus-user",
        "10.0.0.8", "PENDING", 0, null, null, null, null, null, null,
        metadata, Instant.now());
  }

  private static String sha1(byte[] bytes) throws Exception {
    return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(bytes));
  }

  private record Fixture(
      ConanRepositoryDataMigrationWriter writer,
      ConanService service,
      ConanRegistryDao registry,
      RepositoryRuntimeRegistry runtimes,
      RepositoryDataMigrationDao migrations,
      AssetDao assets,
      RepositoryRuntime runtime,
      RepositoryRecord repository) {
  }
}
