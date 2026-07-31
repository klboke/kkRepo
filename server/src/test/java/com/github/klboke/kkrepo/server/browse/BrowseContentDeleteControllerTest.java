package com.github.klboke.kkrepo.server.browse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.github.klboke.kkrepo.auth.AccessDecision;
import com.github.klboke.kkrepo.auth.PermissionSubject;
import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.core.RepositoryType;
import com.github.klboke.kkrepo.persistence.jdbc.api.AnsibleGalaxyRegistryDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.AssetDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.BrowseNodeDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.ComponentDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.MetadataRebuildDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.RepositoryDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.RepositoryIndexRebuildDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.SwiftRegistryDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.TerraformRegistryDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.RepositoryRecord;
import com.github.klboke.kkrepo.server.cache.AssetMetadataCache;
import com.github.klboke.kkrepo.server.cache.GroupMemberAssetCache;
import com.github.klboke.kkrepo.server.cache.NexusCacheType;
import com.github.klboke.kkrepo.server.cache.NexusLikeCacheController;
import com.github.klboke.kkrepo.server.npm.NpmGroupPackumentCache;
import com.github.klboke.kkrepo.server.pypi.PypiGroupSimpleIndexCache;
import com.github.klboke.kkrepo.server.security.AuthenticatedSubject;
import com.github.klboke.kkrepo.server.security.SecurityAuthenticationService;
import com.github.klboke.kkrepo.server.security.SecurityManagementService;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.server.ResponseStatusException;

class BrowseContentDeleteControllerTest {
  @Test
  void requiresAuthenticationPermissionAndPath() {
    Fixture unauthenticated = fixture(false, AccessDecision.allow());
    assertStatus(HttpStatus.UNAUTHORIZED, () -> unauthenticated.controller.delete(
        "raw", "file.txt", null, new MockHttpServletRequest()));

    Fixture forbidden = fixture(true, AccessDecision.deny("denied"));
    assertStatus(HttpStatus.FORBIDDEN, () -> forbidden.controller.delete(
        "raw", "file.txt", null, new MockHttpServletRequest()));

    Fixture blank = fixture(true, AccessDecision.allow());
    when(blank.repositoryDao.findByName("raw"))
        .thenReturn(Optional.of(repository(1L, "raw", RepositoryFormat.RAW, RepositoryType.HOSTED)));
    assertStatus(HttpStatus.BAD_REQUEST, () -> blank.controller.delete(
        "raw", " / ", null, new MockHttpServletRequest()));
  }

  @Test
  void deletesMavenAssetChecksumSiblingAndEnqueuesMetadataRebuilds() {
    Fixture fixture = fixture(true, AccessDecision.allow());
    RepositoryRecord repository =
        repository(1L, "maven", RepositoryFormat.MAVEN2, RepositoryType.HOSTED);
    String path = "com/acme/app/1.0-SNAPSHOT/app-1.0-SNAPSHOT.jar";
    AssetRecord jar = asset(11L, 21L, 31L, RepositoryFormat.MAVEN2, path, "artifact", Map.of());
    AssetRecord sha1 = asset(
        12L, null, 32L, RepositoryFormat.MAVEN2, path + ".sha1", "checksum", Map.of());
    when(fixture.repositoryDao.findByName("maven")).thenReturn(Optional.of(repository));
    when(fixture.assetDao.findAssetByPath(1L, path)).thenReturn(Optional.of(jar));
    when(fixture.assetDao.findAssetByPath(1L, path + ".sha1")).thenReturn(Optional.of(sha1));
    when(fixture.assetDao.listAssetsByPrefix(1L, path + "/")).thenReturn(List.of());

    BrowseContentDeleteController.BrowseDeleteResult result = fixture.controller.delete(
        "maven", "/" + path, null, new MockHttpServletRequest());

    assertEquals(2, result.deletedAssets());
    verify(fixture.assetDao).deleteAssetById(11L);
    verify(fixture.assetDao).deleteAssetById(12L);
    verify(fixture.assetDao).markBlobDeletedIfUnreferenced(31L, "asset unlinked");
    verify(fixture.componentDao).deleteIfNoAssets(21L);
    verify(fixture.metadataRebuildDao).enqueue(1L, "ga:com.acme/app");
    verify(fixture.metadataRebuildDao).enqueue(1L, "gav:com.acme/app/1.0-SNAPSHOT");
  }

  @Test
  void validatesExplicitGroupSourceMembership() {
    Fixture fixture = fixture(true, AccessDecision.allow());
    RepositoryRecord group =
        repository(1L, "group", RepositoryFormat.RAW, RepositoryType.GROUP);
    RepositoryRecord outsider =
        repository(2L, "outside", RepositoryFormat.RAW, RepositoryType.HOSTED);
    when(fixture.repositoryDao.findByName("group")).thenReturn(Optional.of(group));
    when(fixture.repositoryDao.findByName("outside")).thenReturn(Optional.of(outsider));
    when(fixture.repositoryDao.listMembers(1L)).thenReturn(List.of());

    assertStatus(HttpStatus.BAD_REQUEST, () -> fixture.controller.delete(
        "group", "file.txt", "outside", new MockHttpServletRequest()));
    verify(fixture.assetDao, never()).deleteAssetById(anyLong());
  }

  @Test
  void invalidatesNpmPackageAndMemberCaches() {
    Fixture fixture = fixture(true, AccessDecision.allow());
    RepositoryRecord repository =
        repository(1L, "npm", RepositoryFormat.NPM, RepositoryType.HOSTED);
    AssetRecord tarball = asset(
        11L, 21L, 31L, RepositoryFormat.NPM, "demo/-/demo-1.0.0.tgz", "tarball",
        Map.of("packageId", "demo"));
    when(fixture.repositoryDao.findByName("npm")).thenReturn(Optional.of(repository));
    when(fixture.assetDao.findAssetByPath(1L, "demo/-/demo-1.0.0.tgz"))
        .thenReturn(Optional.of(tarball));
    when(fixture.assetDao.listAssetsByPrefix(1L, "demo/-/demo-1.0.0.tgz/"))
        .thenReturn(List.of());

    fixture.controller.delete(
        "npm", "demo/-/demo-1.0.0.tgz", null, new MockHttpServletRequest());

    verify(fixture.npmCache).invalidateMemberPackageAfterCommit(1L, "demo");
    verify(fixture.groupMemberAssetCache).invalidateMemberAfterCommit(1L);
  }

  @Test
  void mapsPypiPublicPathAndRebuildsProjectAndRootIndexes() {
    Fixture fixture = fixture(true, AccessDecision.allow());
    RepositoryRecord repository =
        repository(1L, "pypi", RepositoryFormat.PYPI, RepositoryType.HOSTED);
    String storagePath = "packages/demo-pkg/1.0.0/demo_pkg-1.0.0.whl";
    AssetRecord wheel = asset(
        11L, 21L, 31L, RepositoryFormat.PYPI, storagePath, "package",
        Map.of("normalizedName", "demo-pkg"));
    when(fixture.repositoryDao.findByName("pypi")).thenReturn(Optional.of(repository));
    when(fixture.assetDao.findAssetByPath(1L, storagePath)).thenReturn(Optional.of(wheel));
    when(fixture.assetDao.listAssetsByPrefix(1L, storagePath + "/")).thenReturn(List.of());

    BrowseContentDeleteController.BrowseDeleteResult result = fixture.controller.delete(
        "pypi", "demo-pkg/1.0.0/demo_pkg-1.0.0.whl", null, new MockHttpServletRequest());

    assertEquals("demo-pkg/1.0.0/demo_pkg-1.0.0.whl", result.path());
    verify(fixture.indexRebuildDao).enqueue(1L, RepositoryIndexRebuildDao.PYPI_ROOT);
    verify(fixture.indexRebuildDao).enqueue(
        1L, RepositoryIndexRebuildDao.PYPI_PROJECT, "demo-pkg");
    verify(fixture.pypiCache).invalidateMemberProjectAfterCommit(1L, "demo-pkg");
    verify(fixture.groupMemberAssetCache).invalidateMemberAfterCommit(1L);
  }

  @Test
  void deletesAnsibleBrowseArchiveThroughNestedGroupAfterRegistryCleanup() {
    Fixture fixture = fixture(true, AccessDecision.allow());
    RepositoryRecord hosted =
        repository(1L, "ansible-hosted", RepositoryFormat.ANSIBLEGALAXY, RepositoryType.HOSTED);
    RepositoryRecord inner =
        repository(2L, "ansible-inner", RepositoryFormat.ANSIBLEGALAXY, RepositoryType.GROUP);
    RepositoryRecord outer =
        repository(3L, "ansible-group", RepositoryFormat.ANSIBLEGALAXY, RepositoryType.GROUP);
    String filename = "acme-tools-1.2.3.tar.gz";
    String publicPath = "acme/tools/1.2.3/" + filename;
    String storagePath =
        "api/v3/plugin/ansible/content/published/collections/artifacts/" + filename;
    AssetRecord archive = asset(
        11L, 21L, 31L, RepositoryFormat.ANSIBLEGALAXY, storagePath,
        "collection-artifact", Map.of());
    AnsibleGalaxyRegistryDao.CollectionVersion version = ansibleVersion(41L, archive);
    when(fixture.repositoryDao.findByName(outer.name())).thenReturn(Optional.of(outer));
    when(fixture.repositoryDao.findByName(hosted.name())).thenReturn(Optional.of(hosted));
    when(fixture.repositoryDao.listMembers(outer.id())).thenReturn(List.of(inner));
    when(fixture.repositoryDao.listMembers(inner.id())).thenReturn(List.of(hosted));
    when(fixture.assetDao.findAssetByPath(hosted.id(), storagePath))
        .thenReturn(Optional.of(archive));
    when(fixture.assetDao.listAssetsByPrefix(hosted.id(), storagePath + "/"))
        .thenReturn(List.of());
    when(fixture.ansibleRegistryDao.findVersionByArtifactFilename(hosted.id(), filename))
        .thenReturn(Optional.of(version));
    when(fixture.ansibleRegistryDao.deleteVersion(
        hosted.id(), version.id(), archive.id())).thenReturn(true);

    BrowseContentDeleteController.BrowseDeleteResult result = fixture.controller.delete(
        outer.name(), publicPath, hosted.name(), new MockHttpServletRequest());

    assertEquals(hosted.name(), result.sourceRepository());
    assertEquals(publicPath, result.path());
    assertEquals(1, result.deletedAssets());
    InOrder deletion = inOrder(fixture.ansibleRegistryDao, fixture.assetDao);
    deletion.verify(fixture.ansibleRegistryDao)
        .deleteVersion(hosted.id(), version.id(), archive.id());
    deletion.verify(fixture.assetDao).deleteAssetById(archive.id());
    verify(fixture.componentDao).deleteIfNoAssets(version.componentId());
  }

  @Test
  void rejectsAnsibleBrowsePathThatDoesNotMatchRegistryIdentity() {
    Fixture fixture = fixture(true, AccessDecision.allow());
    RepositoryRecord hosted =
        repository(1L, "ansible-hosted", RepositoryFormat.ANSIBLEGALAXY, RepositoryType.HOSTED);
    String filename = "acme-tools-1.2.3.tar.gz";
    String storagePath =
        "api/v3/plugin/ansible/content/published/collections/artifacts/" + filename;
    AssetRecord archive = asset(
        11L, 21L, 31L, RepositoryFormat.ANSIBLEGALAXY, storagePath,
        "collection-artifact", Map.of());
    AnsibleGalaxyRegistryDao.CollectionVersion version = ansibleVersion(41L, archive);
    when(fixture.repositoryDao.findByName(hosted.name())).thenReturn(Optional.of(hosted));
    when(fixture.assetDao.findAssetByPath(hosted.id(), storagePath))
        .thenReturn(Optional.of(archive));
    when(fixture.assetDao.listAssetsByPrefix(hosted.id(), storagePath + "/"))
        .thenReturn(List.of());
    when(fixture.ansibleRegistryDao.findVersionByArtifactFilename(hosted.id(), filename))
        .thenReturn(Optional.of(version));

    assertStatus(HttpStatus.NOT_FOUND, () -> fixture.controller.delete(
        hosted.name(), "other/tools/1.2.3/" + filename, null,
        new MockHttpServletRequest()));

    verify(fixture.ansibleRegistryDao, never()).deleteVersion(anyLong(), anyLong(), anyLong());
    verify(fixture.assetDao, never()).deleteAssetById(anyLong());
  }

  @Test
  void rejectsAnsibleStoragePathAndMissingRegistryIdentity() {
    Fixture hidden = fixture(true, AccessDecision.allow());
    RepositoryRecord hosted =
        repository(1L, "ansible-hosted", RepositoryFormat.ANSIBLEGALAXY, RepositoryType.HOSTED);
    when(hidden.repositoryDao.findByName(hosted.name())).thenReturn(Optional.of(hosted));
    assertStatus(HttpStatus.BAD_REQUEST, () -> hidden.controller.delete(
        hosted.name(),
        "api/v3/plugin/ansible/content/published/collections/artifacts/"
            + "acme-tools-1.2.3.tar.gz",
        null,
        new MockHttpServletRequest()));

    Fixture missing = fixture(true, AccessDecision.allow());
    String filename = "acme-tools-1.2.3.tar.gz";
    String publicPath = "acme/tools/1.2.3/" + filename;
    String storagePath =
        "api/v3/plugin/ansible/content/published/collections/artifacts/" + filename;
    AssetRecord archive = asset(
        11L, 21L, 31L, RepositoryFormat.ANSIBLEGALAXY, storagePath,
        "collection-artifact", Map.of());
    when(missing.repositoryDao.findByName(hosted.name())).thenReturn(Optional.of(hosted));
    when(missing.assetDao.findAssetByPath(hosted.id(), storagePath))
        .thenReturn(Optional.of(archive));
    when(missing.assetDao.listAssetsByPrefix(hosted.id(), storagePath + "/"))
        .thenReturn(List.of());

    assertStatus(HttpStatus.NOT_FOUND, () -> missing.controller.delete(
        hosted.name(), publicPath, null, new MockHttpServletRequest()));
    verify(missing.assetDao, never()).deleteAssetById(anyLong());
  }

  @Test
  void abortsAnsibleDeleteWhenArchiveOrRegistryStateChanges() {
    RepositoryRecord hosted =
        repository(1L, "ansible-hosted", RepositoryFormat.ANSIBLEGALAXY, RepositoryType.HOSTED);
    String filename = "acme-tools-1.2.3.tar.gz";
    String publicPath = "acme/tools/1.2.3/" + filename;
    String storagePath =
        "api/v3/plugin/ansible/content/published/collections/artifacts/" + filename;
    AssetRecord archive = asset(
        11L, 21L, 31L, RepositoryFormat.ANSIBLEGALAXY, storagePath,
        "collection-artifact", Map.of());
    AssetRecord replacedArchive = asset(
        12L, 21L, 32L, RepositoryFormat.ANSIBLEGALAXY, storagePath,
        "collection-artifact", Map.of());

    Fixture mismatched = fixture(true, AccessDecision.allow());
    when(mismatched.repositoryDao.findByName(hosted.name())).thenReturn(Optional.of(hosted));
    when(mismatched.assetDao.findAssetByPath(hosted.id(), storagePath))
        .thenReturn(Optional.of(archive));
    when(mismatched.assetDao.listAssetsByPrefix(hosted.id(), storagePath + "/"))
        .thenReturn(List.of());
    when(mismatched.ansibleRegistryDao.findVersionByArtifactFilename(hosted.id(), filename))
        .thenReturn(Optional.of(ansibleVersion(41L, replacedArchive)));
    assertStatus(HttpStatus.CONFLICT, () -> mismatched.controller.delete(
        hosted.name(), publicPath, null, new MockHttpServletRequest()));

    Fixture changed = fixture(true, AccessDecision.allow());
    AnsibleGalaxyRegistryDao.CollectionVersion version = ansibleVersion(41L, archive);
    when(changed.repositoryDao.findByName(hosted.name())).thenReturn(Optional.of(hosted));
    when(changed.assetDao.findAssetByPath(hosted.id(), storagePath))
        .thenReturn(Optional.of(archive));
    when(changed.assetDao.listAssetsByPrefix(hosted.id(), storagePath + "/"))
        .thenReturn(List.of());
    when(changed.ansibleRegistryDao.findVersionByArtifactFilename(hosted.id(), filename))
        .thenReturn(Optional.of(version));
    assertStatus(HttpStatus.CONFLICT, () -> changed.controller.delete(
        hosted.name(), publicPath, null, new MockHttpServletRequest()));

    verify(mismatched.assetDao, never()).deleteAssetById(anyLong());
    verify(changed.assetDao, never()).deleteAssetById(anyLong());
  }

  @Test
  void invalidatesTerraformMetadataAfterDelete() {
    Fixture fixture = fixture(true, AccessDecision.allow());
    RepositoryRecord repository =
        repository(1L, "terraform", RepositoryFormat.TERRAFORM, RepositoryType.HOSTED);
    AssetRecord archive = asset(
        11L, null, 31L, RepositoryFormat.TERRAFORM,
        "v1/modules/acme/network/aws/1.0.0/network.zip", "module-archive", Map.of());
    when(fixture.repositoryDao.findByName("terraform")).thenReturn(Optional.of(repository));
    when(fixture.assetDao.findAssetByPath(1L, archive.path())).thenReturn(Optional.of(archive));
    when(fixture.assetDao.listAssetsByPrefix(1L, archive.path() + "/")).thenReturn(List.of());

    fixture.controller.delete(
        "terraform", archive.path(), null, new MockHttpServletRequest());

    verify(fixture.cacheController).invalidateAfterCommit(1L, NexusCacheType.METADATA);
  }

  @Test
  void deletingAnySwiftPublicAssetTombstonesAndRemovesTheWholeRelease() {
    Fixture fixture = fixture(true, AccessDecision.allow());
    RepositoryRecord repository =
        repository(1L, "swift-hosted", RepositoryFormat.SWIFT, RepositoryType.HOSTED);
    AssetRecord archive = asset(
        11L, 21L, 31L, RepositoryFormat.SWIFT,
        "acme/library/1.2.3.zip", "swift-source-archive", Map.of());
    AssetRecord manifest = asset(
        12L, 21L, 32L, RepositoryFormat.SWIFT,
        "acme/library/1.2.3/Package.swift", "swift-manifest", Map.of());
    AssetRecord versionedManifest = asset(
        13L, 21L, 33L, RepositoryFormat.SWIFT,
        "acme/library/1.2.3/Package@swift-5.9.swift", "swift-manifest", Map.of());
    AssetRecord sourceSignature = asset(
        14L, 21L, 34L, RepositoryFormat.SWIFT,
        ".swift/signatures/acme/library/1.2.3/source.cms", "swift-signature", Map.of());
    AssetRecord metadataSignature = asset(
        15L, 21L, 35L, RepositoryFormat.SWIFT,
        ".swift/signatures/acme/library/1.2.3/metadata.sig", "swift-signature", Map.of());
    List<AssetRecord> releaseAssets = List.of(
        archive, manifest, versionedManifest, sourceSignature, metadataSignature);
    when(fixture.repositoryDao.findByName(repository.name())).thenReturn(Optional.of(repository));
    when(fixture.assetDao.findAssetByPath(repository.id(), versionedManifest.path()))
        .thenReturn(Optional.of(versionedManifest));
    when(fixture.assetDao.listAssetsByPrefix(repository.id(), versionedManifest.path() + "/"))
        .thenReturn(List.of());
    when(fixture.swiftRegistryDao.tombstoneAndDeleteReleaseState(
        eq(repository.id()), eq("acme"), eq("library"), eq("1.2.3"),
        eq("administrative delete by admin"), any()))
        .thenReturn(Optional.of(new SwiftRegistryDao.DeletedRelease(
            21L, releaseAssets.stream().map(AssetRecord::id).toList(), 9L)));
    for (AssetRecord asset : releaseAssets) {
      when(fixture.assetDao.findAssetById(asset.id())).thenReturn(Optional.of(asset));
    }

    BrowseContentDeleteController.BrowseDeleteResult result = fixture.controller.delete(
        repository.name(), versionedManifest.path(), null, new MockHttpServletRequest());

    assertEquals(5, result.deletedAssets());
    verify(fixture.swiftRegistryDao).tombstoneAndDeleteReleaseState(
        eq(repository.id()), eq("acme"), eq("library"), eq("1.2.3"),
        eq("administrative delete by admin"), any());
    for (AssetRecord asset : releaseAssets) {
      verify(fixture.assetDao).deleteAssetById(asset.id());
      verify(fixture.assetDao).markBlobDeletedIfUnreferenced(asset.assetBlobId(), "asset unlinked");
    }
    verify(fixture.componentDao).deleteIfNoAssets(21L);
  }

  @Test
  void deletingManifestPreservesSemverEndingInZip() {
    Fixture fixture = fixture(true, AccessDecision.allow());
    RepositoryRecord repository =
        repository(1L, "swift-hosted", RepositoryFormat.SWIFT, RepositoryType.HOSTED);
    String version = "1.2.3+linux.zip";
    AssetRecord manifest = asset(
        12L, 21L, 32L, RepositoryFormat.SWIFT,
        "acme/library/" + version + "/Package.swift", "swift-manifest", Map.of());
    when(fixture.repositoryDao.findByName(repository.name())).thenReturn(Optional.of(repository));
    when(fixture.assetDao.findAssetByPath(repository.id(), manifest.path()))
        .thenReturn(Optional.of(manifest));
    when(fixture.assetDao.listAssetsByPrefix(repository.id(), manifest.path() + "/"))
        .thenReturn(List.of());
    when(fixture.swiftRegistryDao.tombstoneAndDeleteReleaseState(
        eq(repository.id()), eq("acme"), eq("library"), eq(version),
        eq("administrative delete by admin"), any()))
        .thenReturn(Optional.of(new SwiftRegistryDao.DeletedRelease(
            21L, List.of(manifest.id()), 9L)));
    when(fixture.assetDao.findAssetById(manifest.id())).thenReturn(Optional.of(manifest));

    BrowseContentDeleteController.BrowseDeleteResult result = fixture.controller.delete(
        repository.name(), manifest.path(), null, new MockHttpServletRequest());

    assertEquals(1, result.deletedAssets());
    verify(fixture.swiftRegistryDao).tombstoneAndDeleteReleaseState(
        eq(repository.id()), eq("acme"), eq("library"), eq(version),
        eq("administrative delete by admin"), any());
  }

  @Test
  void deletingSwiftReleaseDirectoryFromComponentDetailsDeletesWholeRelease() {
    Fixture fixture = fixture(true, AccessDecision.allow());
    RepositoryRecord repository =
        repository(1L, "swift-hosted", RepositoryFormat.SWIFT, RepositoryType.HOSTED);
    String version = "1.2.3+linux.zip";
    String releasePath = "acme/library/" + version;
    AssetRecord archive = asset(
        11L, 21L, 31L, RepositoryFormat.SWIFT,
        releasePath + ".zip", "swift-source-archive", Map.of());
    AssetRecord manifest = asset(
        12L, 21L, 32L, RepositoryFormat.SWIFT,
        releasePath + "/Package.swift", "swift-manifest", Map.of());
    when(fixture.repositoryDao.findByName(repository.name())).thenReturn(Optional.of(repository));
    when(fixture.assetDao.listAssetsByPrefix(repository.id(), releasePath + "/"))
        .thenReturn(List.of(manifest));
    when(fixture.swiftRegistryDao.tombstoneAndDeleteReleaseState(
        eq(repository.id()), eq("acme"), eq("library"), eq(version),
        eq("administrative delete by admin"), any()))
        .thenReturn(Optional.of(new SwiftRegistryDao.DeletedRelease(
            21L, List.of(archive.id(), manifest.id()), 9L)));
    when(fixture.assetDao.findAssetById(archive.id())).thenReturn(Optional.of(archive));
    when(fixture.assetDao.findAssetById(manifest.id())).thenReturn(Optional.of(manifest));

    BrowseContentDeleteController.BrowseDeleteResult result = fixture.controller.delete(
        repository.name(), releasePath, null, new MockHttpServletRequest());

    assertEquals(2, result.deletedAssets());
    verify(fixture.swiftRegistryDao).tombstoneAndDeleteReleaseState(
        eq(repository.id()), eq("acme"), eq("library"), eq(version),
        eq("administrative delete by admin"), any());
    verify(fixture.assetDao).deleteAssetById(archive.id());
    verify(fixture.assetDao).deleteAssetById(manifest.id());
  }

  @Test
  void mapsTerraformProviderPublicAliasesBeforeDelete() {
    String base = "v1/providers/acme/demo/1.0.10";
    String filename = "terraform-provider-demo_1.0.10_linux_amd64.zip";
    assertTerraformProviderAliasDeleted(
        base, filename, base + "/package/linux/" + filename, 11L);
    assertTerraformProviderAliasDeleted(
        base, "SHA256SUMS",
        base + "/metadata-r2/terraform-provider-demo_1.0.10_SHA256SUMS", 12L);
    assertTerraformProviderAliasDeleted(
        base, "SHA256SUMS.sig",
        base + "/metadata-r2/terraform-provider-demo_1.0.10_SHA256SUMS.sig", 13L);
  }

  private static void assertTerraformProviderAliasDeleted(
      String base, String publicFilename, String storagePath, long assetId) {
    Fixture fixture = fixture(true, AccessDecision.allow());
    RepositoryRecord group =
        repository(2L, "terraform-group", RepositoryFormat.TERRAFORM, RepositoryType.GROUP);
    RepositoryRecord hosted =
        repository(1L, "terraform-hosted", RepositoryFormat.TERRAFORM, RepositoryType.HOSTED);
    String archivePath = base + "/package/linux/"
        + "terraform-provider-demo_1.0.10_linux_amd64.zip";
    String sumsPath = base + "/metadata-r2/terraform-provider-demo_1.0.10_SHA256SUMS";
    String signaturePath = sumsPath + ".sig";
    AssetRecord archive = asset(
        11L, null, 31L, RepositoryFormat.TERRAFORM, archivePath, "provider-archive", Map.of());
    AssetRecord target = storagePath.equals(archivePath)
        ? archive
        : asset(
            assetId, null, 30L + assetId, RepositoryFormat.TERRAFORM, storagePath,
            "provider-metadata", Map.of());
    when(fixture.repositoryDao.findByName("terraform-group")).thenReturn(Optional.of(group));
    when(fixture.repositoryDao.findByName("terraform-hosted")).thenReturn(Optional.of(hosted));
    when(fixture.repositoryDao.listMembers(group.id())).thenReturn(List.of(hosted));
    when(fixture.terraformRegistryDao.listProviderPlatforms(
        hosted.id(), "acme", "demo", "1.0.10"))
        .thenReturn(List.of(new TerraformRegistryDao.ProviderPlatform(
            hosted.id(), "acme", "demo", "1.0.10", "linux", "amd64",
            "terraform-provider-demo_1.0.10_linux_amd64.zip", archivePath,
            "sha256", "5.0", 2, Instant.EPOCH)));
    when(fixture.terraformRegistryDao.findProviderState(
        hosted.id(), "acme", "demo", "1.0.10"))
        .thenReturn(Optional.of(new TerraformRegistryDao.ProviderState(
            hosted.id(), "acme", "demo", "1.0.10", 2,
            sumsPath, signaturePath, 1, Instant.EPOCH)));
    when(fixture.assetDao.findAssetByPath(hosted.id(), archivePath))
        .thenReturn(Optional.of(archive));
    when(fixture.assetDao.findAssetByPath(hosted.id(), storagePath))
        .thenReturn(Optional.of(target));
    when(fixture.assetDao.listAssetsByPrefix(hosted.id(), storagePath + "/"))
        .thenReturn(List.of());
    String publicPath = base + "/download/linux/amd64/" + publicFilename;

    BrowseContentDeleteController.BrowseDeleteResult result = fixture.controller.delete(
        "terraform-group", publicPath, "terraform-hosted", new MockHttpServletRequest());

    assertEquals(publicPath, result.path());
    assertEquals("terraform-hosted", result.sourceRepository());
    assertEquals(1, result.deletedAssets());
    verify(fixture.assetDao).deleteAssetById(assetId);
    verify(fixture.cacheController).invalidateAfterCommit(
        hosted.id(), NexusCacheType.METADATA);
  }

  private static void assertStatus(HttpStatus status, Runnable invocation) {
    ResponseStatusException error = assertThrows(ResponseStatusException.class, invocation::run);
    assertEquals(status, error.getStatusCode());
  }

  private static Fixture fixture(boolean authenticated, AccessDecision decision) {
    RepositoryDao repositoryDao = mock(RepositoryDao.class);
    AssetDao assetDao = mock(AssetDao.class);
    TerraformRegistryDao terraformRegistryDao = mock(TerraformRegistryDao.class);
    SwiftRegistryDao swiftRegistryDao = mock(SwiftRegistryDao.class);
    AnsibleGalaxyRegistryDao ansibleRegistryDao = mock(AnsibleGalaxyRegistryDao.class);
    BrowseNodeDao browseNodeDao = mock(BrowseNodeDao.class);
    ComponentDao componentDao = mock(ComponentDao.class);
    MetadataRebuildDao metadataRebuildDao = mock(MetadataRebuildDao.class);
    RepositoryIndexRebuildDao indexRebuildDao = mock(RepositoryIndexRebuildDao.class);
    SecurityAuthenticationService authentication = mock(SecurityAuthenticationService.class);
    SecurityManagementService security = mock(SecurityManagementService.class);
    AssetMetadataCache assetCache = mock(AssetMetadataCache.class);
    NpmGroupPackumentCache npmCache = mock(NpmGroupPackumentCache.class);
    PypiGroupSimpleIndexCache pypiCache = mock(PypiGroupSimpleIndexCache.class);
    GroupMemberAssetCache groupMemberAssetCache = mock(GroupMemberAssetCache.class);
    NexusLikeCacheController cacheController = mock(NexusLikeCacheController.class);
    PermissionSubject permissionSubject = mock(PermissionSubject.class);
    AuthenticatedSubject subject =
        new AuthenticatedSubject("test", "admin", "local", null, permissionSubject);
    when(authentication.authenticate(any())).thenReturn(
        authenticated ? Optional.of(subject) : Optional.empty());
    when(security.decide(permissionSubject, "nexus:*")).thenReturn(decision);
    BrowseContentDeleteController controller = new BrowseContentDeleteController(
        repositoryDao, assetDao, terraformRegistryDao, swiftRegistryDao, ansibleRegistryDao,
        browseNodeDao, componentDao, metadataRebuildDao,
        indexRebuildDao, authentication, security, assetCache, npmCache, pypiCache,
        groupMemberAssetCache, cacheController);
    return new Fixture(
        repositoryDao, assetDao, terraformRegistryDao, swiftRegistryDao, ansibleRegistryDao,
        browseNodeDao, componentDao, metadataRebuildDao,
        indexRebuildDao, npmCache, pypiCache, groupMemberAssetCache, cacheController, controller);
  }

  private static RepositoryRecord repository(
      long id, String name, RepositoryFormat format, RepositoryType type) {
    return new RepositoryRecord(
        id, name, format, type, name, true, 7L, null, null,
        null, null, "ALLOW", true, Map.of());
  }

  private static AssetRecord asset(
      long id,
      Long componentId,
      Long blobId,
      RepositoryFormat format,
      String path,
      String kind,
      Map<String, Object> attributes) {
    return new AssetRecord(
        id, 1L, componentId, blobId, format, path, null,
        path.substring(path.lastIndexOf('/') + 1), kind, "application/octet-stream",
        4L, null, Instant.EPOCH, attributes);
  }

  private static AnsibleGalaxyRegistryDao.CollectionVersion ansibleVersion(
      long id, AssetRecord archive) {
    return new AnsibleGalaxyRegistryDao.CollectionVersion(
        id, archive.repositoryId(), archive.componentId(), archive.id(),
        "acme", "acme", "tools", "tools", "1.2.3", "1.2.3", archive.name(),
        "a".repeat(64), archive.size(), Map.of(), Map.of(), ">=2.16", "HOSTED", 1L,
        AnsibleGalaxyRegistryDao.VERSION_READY, Instant.EPOCH, Instant.EPOCH, Instant.EPOCH);
  }

  private record Fixture(
      RepositoryDao repositoryDao,
      AssetDao assetDao,
      TerraformRegistryDao terraformRegistryDao,
      SwiftRegistryDao swiftRegistryDao,
      AnsibleGalaxyRegistryDao ansibleRegistryDao,
      BrowseNodeDao browseNodeDao,
      ComponentDao componentDao,
      MetadataRebuildDao metadataRebuildDao,
      RepositoryIndexRebuildDao indexRebuildDao,
      NpmGroupPackumentCache npmCache,
      PypiGroupSimpleIndexCache pypiCache,
      GroupMemberAssetCache groupMemberAssetCache,
      NexusLikeCacheController cacheController,
      BrowseContentDeleteController controller) {
  }
}
