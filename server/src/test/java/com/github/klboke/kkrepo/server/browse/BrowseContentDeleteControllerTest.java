package com.github.klboke.kkrepo.server.browse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.github.klboke.kkrepo.auth.AccessDecision;
import com.github.klboke.kkrepo.auth.PermissionSubject;
import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.core.RepositoryType;
import com.github.klboke.kkrepo.persistence.mysql.dao.AssetDao;
import com.github.klboke.kkrepo.persistence.mysql.dao.BrowseNodeDao;
import com.github.klboke.kkrepo.persistence.mysql.dao.ComponentDao;
import com.github.klboke.kkrepo.persistence.mysql.dao.MetadataRebuildDao;
import com.github.klboke.kkrepo.persistence.mysql.dao.RepositoryDao;
import com.github.klboke.kkrepo.persistence.mysql.dao.RepositoryIndexRebuildDao;
import com.github.klboke.kkrepo.persistence.mysql.model.AssetRecord;
import com.github.klboke.kkrepo.persistence.mysql.model.RepositoryRecord;
import com.github.klboke.kkrepo.server.cache.AssetMetadataCache;
import com.github.klboke.kkrepo.server.cache.GroupMemberAssetCache;
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

  private static void assertStatus(HttpStatus status, Runnable invocation) {
    ResponseStatusException error = assertThrows(ResponseStatusException.class, invocation::run);
    assertEquals(status, error.getStatusCode());
  }

  private static Fixture fixture(boolean authenticated, AccessDecision decision) {
    RepositoryDao repositoryDao = mock(RepositoryDao.class);
    AssetDao assetDao = mock(AssetDao.class);
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
    PermissionSubject permissionSubject = mock(PermissionSubject.class);
    AuthenticatedSubject subject =
        new AuthenticatedSubject("test", "admin", "local", null, permissionSubject);
    when(authentication.authenticate(any())).thenReturn(
        authenticated ? Optional.of(subject) : Optional.empty());
    when(security.decide(permissionSubject, "nexus:*")).thenReturn(decision);
    BrowseContentDeleteController controller = new BrowseContentDeleteController(
        repositoryDao, assetDao, browseNodeDao, componentDao, metadataRebuildDao,
        indexRebuildDao, authentication, security, assetCache, npmCache, pypiCache,
        groupMemberAssetCache);
    return new Fixture(
        repositoryDao, assetDao, browseNodeDao, componentDao, metadataRebuildDao,
        indexRebuildDao, npmCache, pypiCache, groupMemberAssetCache, controller);
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

  private record Fixture(
      RepositoryDao repositoryDao,
      AssetDao assetDao,
      BrowseNodeDao browseNodeDao,
      ComponentDao componentDao,
      MetadataRebuildDao metadataRebuildDao,
      RepositoryIndexRebuildDao indexRebuildDao,
      NpmGroupPackumentCache npmCache,
      PypiGroupSimpleIndexCache pypiCache,
      GroupMemberAssetCache groupMemberAssetCache,
      BrowseContentDeleteController controller) {
  }
}
