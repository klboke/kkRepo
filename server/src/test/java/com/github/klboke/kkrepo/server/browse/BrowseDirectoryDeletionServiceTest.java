package com.github.klboke.kkrepo.server.browse;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.core.RepositoryType;
import com.github.klboke.kkrepo.persistence.jdbc.api.*;
import com.github.klboke.kkrepo.persistence.jdbc.api.BrowseNodeDao.*;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.*;
import com.github.klboke.kkrepo.server.browse.BrowseDirectoryDeletionService.*;
import com.github.klboke.kkrepo.server.browse.RepositoryContentDeletionService.CleanupDeleteSubject;
import com.github.klboke.kkrepo.server.cleanup.CleanupSubjectScanner;
import com.github.klboke.kkrepo.server.cleanup.CleanupSubjectScanner.Subject;
import java.time.Instant;
import java.util.*;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.web.server.ResponseStatusException;

class BrowseDirectoryDeletionServiceTest {
  final RepositoryDao repositories = mock(RepositoryDao.class);
  final BrowseNodeDao nodes = mock(BrowseNodeDao.class);
  final AssetDao assets = mock(AssetDao.class);
  final ComponentDao components = mock(ComponentDao.class);
  final CleanupSubjectScanner scanner = mock(CleanupSubjectScanner.class);
  final RepositoryContentDeletionService deletion = mock(RepositoryContentDeletionService.class);
  final BrowseDirectoryDeletionService service = new BrowseDirectoryDeletionService(
      repositories, nodes, assets, components, scanner, deletion);
  RepositoryRecord repository;
  Map<Long, AssetRecord> content = new LinkedHashMap<>();

  void setup(RepositoryFormat format, String path) {
    repository = new RepositoryRecord(1L, "hosted", format, RepositoryType.HOSTED,
        "raw-hosted", true, 1L, null, null, null, null, "ALLOW", true, Map.of());
    when(repositories.findByName("hosted")).thenAnswer(call -> Optional.of(repository));
    String stored = format == RepositoryFormat.PYPI ? "packages/" + path : path;
    when(nodes.findNode(1, stored)).thenAnswer(call -> Optional.of(new BrowseChild(
        1, stored, path, 0, content.values().stream().filter(a -> a.path().equals(stored))
            .map(AssetRecord::id).findFirst().orElse(null), null, null, null, null, null, true, true)));
    when(nodes.listDeletionChildren(eq(1L), anyList(), anyInt())).thenReturn(List.of());
    when(nodes.listDeletionChildren(eq(1L), eq(List.of(1L)), anyInt())).thenAnswer(call ->
        content.values().stream().filter(row -> row.path().startsWith(stored + "/"))
            .map(row -> new DeletionNode(row.id(), row.path(), row.id())).toList());
    when(assets.findAssetsByIds(anyCollection())).thenAnswer(call -> {
      Collection<Long> ids = call.getArgument(0);
      var selected = new LinkedHashMap<Long, AssetRecord>();
      content.forEach((id, row) -> { if (ids.contains(id)) selected.put(id, row); });
      return selected;
    });
    when(assets.findAssetsByPaths(eq(1L), anyCollection())).thenAnswer(call -> {
      Collection<String> paths = call.getArgument(1);
      var selected = new LinkedHashMap<String, AssetRecord>();
      content.values().forEach(row -> { if (paths.contains(row.path())) selected.put(row.path(), row); });
      return selected;
    });
    when(assets.findAssetByIdForUpdate(anyLong())).thenAnswer(call -> Optional.ofNullable(content.get(call.getArgument(0))));
    when(deletion.deleteBatchForCleanup(eq("hosted"), anyList(), eq("admin"))).thenAnswer(call ->
        ((List<?>) call.getArgument(1)).stream().map(x -> 1).toList());
  }

  AssetRecord asset(long id, String path, Long component) {
    return new AssetRecord(id, 1, component, id + 100, repository.format(), path, new byte[0],
        path.substring(path.lastIndexOf('/') + 1), path.endsWith("root.json") ? "package-root" : "artifact", "application/octet-stream", 17L,
        null, Instant.parse("2026-10-12T00:00:00Z"), Map.of());
  }

  AssetRecord npmRoot(long id, String path) {
    var row = asset(id, path, null);
    return new AssetRecord(row.id(), row.repositoryId(), null, row.assetBlobId(), row.format(),
        path, row.pathHash(), row.name(), "package-root", row.contentType(), row.size(),
        row.lastDownloadedAt(), row.lastUpdatedAt(), row.attributes());
  }

  Command command(Preview preview) {
    return new Command(preview.path(), preview.path(), preview.selectionToken());
  }

  @Test void rawPreviewDeletesOnlyConfirmedIdsAndLocksBeforeDeleting() {
    setup(RepositoryFormat.RAW, "releases/1.0");
    content.put(10L, asset(10, "releases/1.0/a.txt", null));
    content.put(11L, asset(11, "releases/1.0/b.txt", null));
    Preview preview = service.preview("hosted", "releases/1.0");
    assertEquals(2, preview.assetCount());
    assertEquals(34, preview.estimatedBytes());
    assertEquals(0, preview.metadataAssetCount());
    assertEquals(2, service.delete("hosted", command(preview), "admin").deletedAssets());
    var order = inOrder(assets, deletion);
    order.verify(assets).findAssetByIdForUpdate(10);
    order.verify(assets).findAssetByIdForUpdate(11);
    order.verify(deletion).deleteBatchForCleanup("hosted", List.of(
        new CleanupDeleteSubject("ASSET", 10, "releases/1.0/a.txt"),
        new CleanupDeleteSubject("ASSET", 11, "releases/1.0/b.txt")), "admin");
    verify(assets, never()).listAssetsByPrefix(anyLong(), anyString());
  }

  @Test void changedBlobBindingInvalidatesThePreview() {
    setup(RepositoryFormat.RAW, "release");
    content.put(10L, asset(10, "release/a", null));
    Preview preview = service.preview("hosted", "release");
    var old = content.get(10L);
    content.put(10L, new AssetRecord(old.id(), 1, null, 999L, old.format(), old.path(), old.pathHash(),
        old.name(), old.kind(), old.contentType(), old.size(), null, old.lastUpdatedAt(), Map.of()));
    assertStatus(409, () -> service.delete("hosted", command(preview), "admin"));
    verifyNoInteractions(deletion);
  }

  @Test void additionBeforeExecutionRequiresNewPreview() {
    setup(RepositoryFormat.RAW, "release");
    content.put(10L, asset(10, "release/a", null));
    Preview preview = service.preview("hosted", "release");
    content.put(11L, asset(11, "release/b", null));
    assertStatus(409, () -> service.delete("hosted", command(preview), "admin"));
    verifyNoInteractions(deletion);
  }

  @Test void anotherReplicaChangingContentDuringLockWaitIsRejected() {
    setup(RepositoryFormat.RAW, "release");
    content.put(10L, asset(10, "release/a", null));
    Preview preview = service.preview("hosted", "release");
    when(assets.findAssetByIdForUpdate(10)).thenAnswer(call -> {
      content.put(11L, asset(11, "release/new", null));
      return Optional.of(content.get(10L));
    });
    assertStatus(409, () -> service.delete("hosted", command(preview), "admin"));
    verifyNoInteractions(deletion);
  }

  @Test void missingAssetDuringLockWaitIsRejected() {
    setup(RepositoryFormat.RAW, "release");
    content.put(10L, asset(10, "release/a", null));
    Preview preview = service.preview("hosted", "release");
    when(assets.findAssetByIdForUpdate(10)).thenReturn(Optional.empty());
    assertStatus(409, () -> service.delete("hosted", command(preview), "admin"));
    verifyNoInteractions(deletion);
  }

  @Test void packageDeletionUsesCompleteProtocolSubjectsAndSeparatesGeneratedMetadata() {
    setup(RepositoryFormat.NPM, "@scope/pkg");
    content.put(10L, asset(10, "@scope/pkg/-/pkg-1.tgz", 50L));
    content.put(11L, npmRoot(11, "@scope/pkg"));
    Subject subject = subject(50, List.of(10L));
    when(scanner.resolve(repository, "COMPONENT", 50, content.get(10L).path())).thenReturn(Optional.of(subject));
    when(scanner.resolveLocked(repository, "COMPONENT", 50, subject.deletePath())).thenReturn(Optional.of(subject));
    Preview preview = service.preview("hosted", "@scope/pkg");
    assertEquals(1, preview.assetCount());
    assertEquals(1, preview.componentCount());
    assertEquals(1, preview.metadataAssetCount());
    service.delete("hosted", command(preview), "admin");
    verify(deletion).deleteBatchForCleanup("hosted", List.of(
        new CleanupDeleteSubject("COMPONENT", 50, subject.deletePath())), "admin");
  }

  @Test void partialComponentNeverExpandsBeyondTheReviewedDirectory() {
    setup(RepositoryFormat.NUGET, "flat/package");
    content.put(10L, asset(10, "flat/package/a.nupkg", 50L));
    when(scanner.resolve(repository, "COMPONENT", 50, content.get(10L).path()))
        .thenReturn(Optional.of(subject(50, List.of(10L, 99L))));
    assertStatus(400, () -> service.preview("hosted", "flat/package"));
    verifyNoInteractions(deletion);
  }

  @ParameterizedTest @ValueSource(strings = {"demo", "simple", "packages"})
  void pypiPreviewUsesPublicPathsAndProjectedTree(String name) {
    setup(RepositoryFormat.PYPI, name);
    content.put(10L, asset(10, "packages/" + name + "/demo.whl", 50L));
    when(scanner.resolve(repository, "COMPONENT", 50, content.get(10L).path()))
        .thenReturn(Optional.of(subject(50, List.of(10L))));
    assertEquals(name + "/demo.whl", service.preview("hosted", name).items().getFirst().path());
    verify(nodes).findNode(1, "packages/" + name);
  }

  @Test void truncatedPreviewAndEmptyMetadataOnlyDirectoriesCannotBeExecuted() {
    setup(RepositoryFormat.RAW, "release");
    IntStream.rangeClosed(10, 510).forEach(i -> content.put((long) i, asset(i, "release/" + i, null)));
    assertStatus(400, () -> service.preview("hosted", "release"));
    content.clear();
    assertStatus(400, () -> service.preview("hosted", "release"));
    when(nodes.listDeletionChildren(eq(1L), eq(List.of(1L)), anyInt())).thenReturn(
        IntStream.range(10, 1010).mapToObj(i -> new DeletionNode(i, "release/" + i, null)).toList());
    assertStatus(400, () -> service.preview("hosted", "release"));
    verifyNoInteractions(deletion);
  }

  @Test void tokenAndTypedPathAreBothRequired() {
    setup(RepositoryFormat.RAW, "release");
    content.put(10L, asset(10, "release/a", null));
    assertStatus(400, () -> service.delete("hosted", null, "admin"));
    assertStatus(400, () -> service.delete("hosted", new Command("release", "Release", "x"), "admin"));
    assertStatus(409, () -> service.delete("hosted", new Command("release", "release", null), "admin"));
    verifyNoInteractions(deletion);
  }

  @Test void disallowsProxyGroupUnsupportedFormatAndOfflineRepositories() {
    setup(RepositoryFormat.RAW, "release");
    for (RepositoryType type : List.of(RepositoryType.PROXY, RepositoryType.GROUP)) {
      repository = new RepositoryRecord(1L, "hosted", RepositoryFormat.RAW, type,
          "raw", true, 1L, null, null, null, null, "ALLOW", true, Map.of());
      assertStatus(400, () -> service.preview("hosted", "release"));
    }
    for (RepositoryFormat format : List.of(RepositoryFormat.DOCKER, RepositoryFormat.HELM)) {
      setup(format, "release");
      assertStatus(400, () -> service.preview("hosted", "release"));
    }
    repository = new RepositoryRecord(1L, "hosted", RepositoryFormat.RAW, RepositoryType.HOSTED,
        "raw", false, 1L, null, null, null, null, "ALLOW", true, Map.of());
    assertStatus(400, () -> service.preview("hosted", "release"));
  }

  @ParameterizedTest @ValueSource(strings = {"", "/", "/dir", "dir/", "..", "dir/../other", "dir//other", "dir\\other", " dir", "dir\n"})
  void rejectsRootAndNoncanonicalPaths(String path) {
    assertStatus(400, () -> service.preview("hosted", path));
    verifyNoInteractions(repositories, deletion);
  }

  @Test void foreignTreeNodeOrAssetIsRejected() {
    setup(RepositoryFormat.RAW, "release");
    when(nodes.listDeletionChildren(eq(1L), eq(List.of(1L)), anyInt()))
        .thenReturn(List.of(new DeletionNode(2, "release-old/file", 10L)));
    assertStatus(409, () -> service.preview("hosted", "release"));
    verifyNoInteractions(deletion);
  }

  @Test void partialComponentIsRejectedBeforeLoadingUnboundedAssets() {
    setup(RepositoryFormat.NPM, "scope");
    content.put(10L, asset(10, "scope/a.tgz", 50L));
    when(assets.hasComponentAssetsOutside(eq(50L), anyCollection())).thenReturn(true);
    assertStatus(400, () -> service.preview("hosted", "scope"));
    verifyNoInteractions(scanner, deletion);
  }

  @Test void packagesFromDifferentFamiliesAreNotSentToOneProtocolBatch() {
    setup(RepositoryFormat.NPM, "scope");
    for (long id : List.of(10L, 11L)) {
      content.put(id, asset(id, "scope/pkg" + id + "/pkg.tgz", id + 40));
      Subject subject = new Subject(id + 40, "COMPONENT", "component:" + id, new byte[0],
          "family:" + id, "pkg", "pkg@1", "1", content.get(id).path(), null,
          Instant.now(), 1, 17, List.of(id), "v1", 0);
      when(scanner.resolve(repository, "COMPONENT", id + 40, content.get(id).path())).thenReturn(Optional.of(subject));
      when(scanner.resolveLocked(repository, "COMPONENT", id + 40, subject.deletePath())).thenReturn(Optional.of(subject));
    }
    var preview = service.preview("hosted", "scope");
    assertEquals(2, service.delete("hosted", command(preview), "admin").deletedAssets());
    verify(deletion, times(2)).deleteBatchForCleanup(eq("hosted"), argThat(batch -> batch.size() == 1), eq("admin"));
  }

  @Test void generatedMetadataIsLockedAndChangesInvalidateThePreview() {
    setup(RepositoryFormat.NPM, "scope");
    content.put(10L, asset(10, "scope/a.tgz", 50L));
    content.put(11L, asset(11, "scope/root.json", null));
    var subject = subject(50, List.of(10L));
    when(scanner.resolve(repository, "COMPONENT", 50, content.get(10L).path())).thenReturn(Optional.of(subject));
    when(scanner.resolveLocked(repository, "COMPONENT", 50, subject.deletePath())).thenReturn(Optional.of(subject));
    var preview = service.preview("hosted", "scope");
    when(assets.findAssetByIdForUpdate(11)).thenReturn(Optional.of(asset(11, "scope/replaced", null)));
    assertStatus(409, () -> service.delete("hosted", command(preview), "admin"));
    verifyNoInteractions(deletion);
  }

  @Test void disappearedPackageOrChangedComponentTokenIsRejected() {
    setup(RepositoryFormat.NPM, "scope");
    content.put(10L, asset(10, "scope/a.tgz", 50L));
    var subject = subject(50, List.of(10L));
    when(scanner.resolve(repository, "COMPONENT", 50, content.get(10L).path())).thenReturn(Optional.of(subject));
    var preview = service.preview("hosted", "scope");
    assertStatus(409, () -> service.delete("hosted", command(preview), "admin"));
    var changed = new Subject(50, "COMPONENT", subject.key(), new byte[0], "family", "pkg", "pkg@1", "1",
        subject.deletePath(), null, Instant.now(), 1, 17, List.of(10L), "content-v2", 0);
    when(scanner.resolveLocked(repository, "COMPONENT", 50, subject.deletePath())).thenReturn(Optional.of(changed));
    assertStatus(409, () -> service.delete("hosted", command(preview), "admin"));
    verifyNoInteractions(deletion);
  }

  @Test void unknownPackageIdentityAndInvalidAdapterResultsFailClosed() {
    setup(RepositoryFormat.NPM, "scope");
    content.put(10L, asset(10, "scope/a.tgz", null));
    assertStatus(400, () -> service.preview("hosted", "scope"));
    setup(RepositoryFormat.RAW, "release");
    content.clear();
    content.put(10L, asset(10, "release/a", null));
    var preview = service.preview("hosted", "release");
    when(deletion.deleteBatchForCleanup(anyString(), anyList(), anyString())).thenReturn(List.of());
    assertThrows(IllegalStateException.class, () -> service.delete("hosted", command(preview), "admin"));
  }

  @Test void missingDirectoryRepositoryAndInconsistentTreeCannotBeDeleted() {
    assertStatus(404, () -> service.preview("missing", "release"));
    setup(RepositoryFormat.RAW, "release");
    assertStatus(404, () -> service.preview("hosted", "absent"));
    when(nodes.listDeletionChildren(eq(1L), eq(List.of(1L)), anyInt()))
        .thenReturn(List.of(new DeletionNode(2, "release/file", 10L)));
    assertStatus(409, () -> service.preview("hosted", "release"));
    verifyNoInteractions(deletion);
  }

  @Test void npmTarballDirectoryFingerprintsAndLocksItsExternalPackument() {
    setup(RepositoryFormat.NPM, "@scope/pkg/-");
    content.put(10L, asset(10, "@scope/pkg/-/pkg-1.tgz", 50L));
    content.put(11L, npmRoot(11, "@scope/pkg"));
    var subject = subject(50, List.of(10L));
    when(scanner.resolve(repository, "COMPONENT", 50, content.get(10L).path())).thenReturn(Optional.of(subject));
    when(scanner.resolveLocked(repository, "COMPONENT", 50, subject.deletePath())).thenReturn(Optional.of(subject));
    var preview = service.preview("hosted", "@scope/pkg/-");
    assertEquals(1, preview.assetCount());
    assertEquals(1, preview.metadataAssetCount());
    assertTrue(preview.items().stream().anyMatch(item -> item.path().equals("@scope/pkg")
        && item.action().equals("PROTOCOL_METADATA")));
    service.delete("hosted", command(preview), "admin");
    var order = inOrder(assets, deletion);
    order.verify(assets).findAssetByIdForUpdate(11);
    order.verify(deletion).deleteBatchForCleanup(eq("hosted"), anyList(), eq("admin"));
    clearInvocations(deletion);
    var before = content.get(11L);
    content.put(11L, new AssetRecord(before.id(), 1, null, 999L, before.format(), before.path(),
        before.pathHash(), before.name(), before.kind(), before.contentType(), before.size(), null,
        before.lastUpdatedAt(), before.attributes()));
    assertStatus(409, () -> service.delete("hosted", command(preview), "admin"));
    verifyNoInteractions(deletion);
  }

  @Test void npmCannotDeleteWithoutValidPackumentOrExceedTheBudgetWithDependencies() {
    setup(RepositoryFormat.NPM, "pkg/-");
    content.put(10L, asset(10, "pkg/-/pkg-1.tgz", 50L));
    assertStatus(400, () -> service.preview("hosted", "pkg/-"));
    content.put(11L, asset(11, "pkg", null));
    assertStatus(400, () -> service.preview("hosted", "pkg/-"));
    content.clear();
    IntStream.range(10, 510).forEach(i -> content.put((long) i, asset(i, "pkg/-/pkg-" + i + ".tgz", 50L)));
    content.put(510L, npmRoot(510, "pkg"));
    assertStatus(400, () -> service.preview("hosted", "pkg/-"));
    verifyNoInteractions(scanner, deletion);
  }

  @Test void directAssetsLockTheirComponentsInOrderBeforeLockingAnyAsset() {
    setup(RepositoryFormat.MAVEN2, "org/example");
    content.put(10L, asset(10, "org/example/a.jar", 60L));
    content.put(11L, asset(11, "org/example/b.jar", 50L));
    for (long id : List.of(50L, 60L)) {
      when(components.findByIdForUpdate(id)).thenReturn(Optional.of(new ComponentRecord(
          id, 1, RepositoryFormat.MAVEN2, "org", "example", "1", "jar", new byte[0], Map.of(), Instant.now())));
    }
    var preview = service.preview("hosted", "org/example");
    service.delete("hosted", command(preview), "admin");
    var order = inOrder(components, assets, deletion);
    order.verify(components).findByIdForUpdate(50);
    order.verify(components).findByIdForUpdate(60);
    order.verify(assets).findAssetByIdForUpdate(10);
    order.verify(assets).findAssetByIdForUpdate(11);
    order.verify(deletion).deleteBatchForCleanup(eq("hosted"), anyList(), eq("admin"));
    clearInvocations(deletion, assets);
    when(components.findByIdForUpdate(50)).thenReturn(Optional.empty());
    assertStatus(409, () -> service.delete("hosted", command(preview), "admin"));
    verify(assets, never()).findAssetByIdForUpdate(anyLong());
    verifyNoInteractions(deletion);
  }


  Subject subject(long id, List<Long> assetIds) {
    return new Subject(id, "COMPONENT", "component:" + id, new byte[0], "family", "pkg", "pkg@1", "1",
        "@scope/pkg/-/pkg-1.tgz", null, Instant.now(), assetIds.size(), 17,
        assetIds, "content-v1", 0);
  }

  void assertStatus(int expected, org.junit.jupiter.api.function.Executable action) {
    assertEquals(expected, assertThrows(ResponseStatusException.class, action).getStatusCode().value());
  }
}
