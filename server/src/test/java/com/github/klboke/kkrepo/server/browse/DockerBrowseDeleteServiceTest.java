package com.github.klboke.kkrepo.server.browse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.core.RepositoryType;
import com.github.klboke.kkrepo.persistence.jdbc.api.DockerRegistryDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.RepositoryDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.RepositoryRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.docker.DockerManifestRecord;
import com.github.klboke.kkrepo.server.docker.DockerManifestStore;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntime;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntimeRegistry;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class DockerBrowseDeleteServiceTest {
  private static final String DIGEST = "sha256:" + "a".repeat(64);
  private final RepositoryDao repositories = mock(RepositoryDao.class);
  private final DockerRegistryDao docker = mock(DockerRegistryDao.class);
  private final RepositoryRuntimeRegistry runtimes = mock(RepositoryRuntimeRegistry.class);
  private final DockerManifestStore manifests = mock(DockerManifestStore.class);
  private final DockerBrowseDeleteService service =
      new DockerBrowseDeleteService(repositories, docker, runtimes, manifests);
  private final RepositoryRecord hosted = repository(1, "docker", RepositoryType.HOSTED);
  private final RepositoryRecord proxy = repository(2, "docker-proxy", RepositoryType.PROXY);
  private final RepositoryRecord group = repository(3, "docker-group", RepositoryType.GROUP);

  @Test
  void tagsAndDigestsPreserveTheirReferenceIdentity() {
    for (String reference : List.of("latest", "1.0.0", DIGEST)) {
      RepositoryRuntime runtime = referenceExists(hosted, "team/app", reference);
      var result = service.delete(hosted, "team/app/manifests/" + reference, null);
      assertEquals("docker", result.repository());
      assertEquals("docker", result.sourceRepository());
      assertEquals(1, result.deletedAssets());
      verify(manifests).deleteReference(runtime, "team/app", reference);
    }
  }

  @Test
  void proxyDeletionOnlyUsesLocalRegistryState() {
    RepositoryRuntime runtime = referenceExists(proxy, "hello-world", "latest");
    when(repositories.findByName(proxy.name())).thenReturn(Optional.of(proxy));
    assertEquals(proxy.name(), service.delete(
        proxy, "hello-world/manifests/latest", "  " + proxy.name() + " ").sourceRepository());
    verify(manifests).deleteReference(runtime, "hello-world", "latest");
  }

  @Test
  void groupDeletionHonorsExplicitSourceEvenWhenAnotherMemberHasTheSameTag() {
    when(repositories.listMembers(group.id())).thenReturn(List.of(hosted, proxy));
    when(repositories.findByName(proxy.name())).thenReturn(Optional.of(proxy));
    RepositoryRuntime runtime = referenceExists(proxy, "hello-world", "latest");
    var result = service.delete(group, "hello-world/manifests/latest", proxy.name());
    assertEquals(group.name(), result.repository());
    assertEquals(proxy.name(), result.sourceRepository());
    verify(docker, never()).findManifestByReference(eq(hosted.id()), anyString(), anyString());
    verify(manifests).deleteReference(runtime, "hello-world", "latest");
  }

  @Test
  void groupWithoutSourceSelectsFirstMatchingMember() {
    when(repositories.listMembers(group.id())).thenReturn(List.of(hosted, proxy));
    referenceExists(proxy, "hello-world", "latest");
    assertEquals(proxy.name(), service.delete(
        group, "hello-world/manifests/latest", " ").sourceRepository());
    referenceExists(hosted, "hello-world", "latest");
    assertEquals(hosted.name(), service.delete(
        group, "hello-world/manifests/latest", null).sourceRepository());
  }

  @ParameterizedTest
  @ValueSource(strings = {"team", "team/app"})
  void directoryDeletionDeduplicatesDigestsAndKeepsLiteralSubtree(String path) {
    when(docker.listBrowseImages(hosted.id(), path)).thenReturn(List.of(
        image("team/app"), image("team/app/child"), image("teamb/app")));
    when(docker.listBrowseReferences(hosted.id(), "team/app"))
        .thenReturn(List.of(reference("latest"), reference("1.0.0"), reference(DIGEST)));
    when(docker.listBrowseReferences(hosted.id(), "team/app/child"))
        .thenReturn(List.of(reference(DIGEST)));
    RepositoryRuntime runtime = runtime(hosted);
    when(manifests.deleteReference(eq(runtime), anyString(), eq(DIGEST))).thenReturn(1);
    assertEquals(2, service.delete(hosted, path, null).deletedAssets());
    verify(manifests).deleteReference(runtime, "team/app", DIGEST);
    verify(manifests).deleteReference(runtime, "team/app/child", DIGEST);
    verify(docker, never()).listBrowseReferences(hosted.id(), "teamb/app");
  }

  @Test
  void sqlWildcardMatchesCannotBroadenDirectoryDeletion() {
    when(docker.listBrowseImages(hosted.id(), "team_app"))
        .thenReturn(List.of(image("team_app"), image("teamXapp")));
    when(docker.listBrowseReferences(hosted.id(), "team_app"))
        .thenReturn(List.of(reference(DIGEST)));
    RepositoryRuntime runtime = runtime(hosted);
    when(manifests.deleteReference(runtime, "team_app", DIGEST)).thenReturn(1);
    assertEquals(1, service.delete(hosted, "team_app", null).deletedAssets());
    verify(docker, never()).listBrowseReferences(hosted.id(), "teamXapp");
  }

  @Test
  void manifestsDirectoryDeletesOnlyTheExactImage() {
    when(docker.imageExists(hosted.id(), "team/app")).thenReturn(true);
    when(docker.listBrowseReferences(hosted.id(), "team/app"))
        .thenReturn(List.of(reference("latest"), reference(DIGEST)));
    RepositoryRuntime runtime = runtime(hosted);
    when(manifests.deleteReference(runtime, "team/app", DIGEST)).thenReturn(1);
    assertEquals(1, service.delete(hosted, "team/app/manifests", null).deletedAssets());
    verify(docker, never()).listBrowseImages(anyLong(), anyString());
    verify(manifests).deleteReference(runtime, "team/app", DIGEST);
  }

  @Test
  void intermediateManifestsSegmentCanBelongToAnImageName() {
    String path = "team/manifests/app/child";
    when(docker.listBrowseImages(hosted.id(), path)).thenReturn(List.of(image(path)));
    when(docker.listBrowseReferences(hosted.id(), path)).thenReturn(List.of(reference(DIGEST)));
    RepositoryRuntime runtime = runtime(hosted);
    when(manifests.deleteReference(runtime, path, DIGEST)).thenReturn(1);
    assertEquals(1, service.delete(hosted, path, null).deletedAssets());
  }

  @Test
  void missingPathsAndReferencesReturn404WithoutMutation() {
    for (String path : List.of("missing", "missing/manifests", "missing/manifests/latest")) {
      status(HttpStatus.NOT_FOUND, () -> service.delete(hosted, path, null));
    }
    when(repositories.listMembers(group.id())).thenReturn(List.of());
    status(HttpStatus.NOT_FOUND, () -> service.delete(group, "missing", null));
    verifyNoInteractions(manifests);
  }

  @Test
  void invalidPathsReturn400WithoutMutation() {
    for (String path : List.of("", "Team/app", "team/app/manifests/bad tag")) {
      status(HttpStatus.BAD_REQUEST, () -> service.delete(hosted, path, null));
    }
    verifyNoInteractions(manifests);
  }

  @Test
  void sourceMustExistAndBelongToRequestedRepository() {
    status(HttpStatus.NOT_FOUND, () -> service.delete(hosted, "team/app", "missing"));
    when(repositories.findByName(proxy.name())).thenReturn(Optional.of(proxy));
    status(HttpStatus.BAD_REQUEST, () -> service.delete(hosted, "team/app", proxy.name()));
    when(repositories.listMembers(group.id())).thenReturn(List.of(hosted));
    status(HttpStatus.BAD_REQUEST, () -> service.delete(group, "team/app", proxy.name()));
    verifyNoInteractions(docker, manifests);
  }

  @Test
  void unavailableRuntimeAndConcurrentDeletionFailWithoutFallbackToAnotherMember() {
    when(docker.findManifestByReference(hosted.id(), "team/app", "latest"))
        .thenReturn(Optional.of(mock(DockerManifestRecord.class)));
    status(HttpStatus.CONFLICT, () -> service.delete(hosted, "team/app/manifests/latest", null));
    verifyNoInteractions(manifests);
    runtime(hosted);
    when(repositories.listMembers(group.id())).thenReturn(List.of(hosted, proxy));
    status(HttpStatus.NOT_FOUND, () -> service.delete(group, "team/app/manifests/latest", null));
    verify(docker, never()).findManifestByReference(eq(proxy.id()), anyString(), anyString());
  }

  private RepositoryRuntime referenceExists(RepositoryRecord repository, String image, String value) {
    when(docker.findManifestByReference(repository.id(), image, value))
        .thenReturn(Optional.of(mock(DockerManifestRecord.class)));
    RepositoryRuntime runtime = runtime(repository);
    when(manifests.deleteReference(runtime, image, value)).thenReturn(1);
    return runtime;
  }

  private RepositoryRuntime runtime(RepositoryRecord repository) {
    RepositoryRuntime runtime = mock(RepositoryRuntime.class);
    when(runtimes.resolveById(repository.id())).thenReturn(Optional.of(runtime));
    return runtime;
  }

  private static RepositoryRecord repository(long id, String name, RepositoryType type) {
    return new RepositoryRecord(id, name, RepositoryFormat.DOCKER, type, name,
        true, 7L, null, null, null, null, "ALLOW", true, Map.of());
  }

  private static DockerRegistryDao.BrowseImageRow image(String name) {
    return new DockerRegistryDao.BrowseImageRow(name, Instant.EPOCH, 1L, "application/json");
  }

  private static DockerRegistryDao.BrowseReferenceRow reference(String value) {
    return new DockerRegistryDao.BrowseReferenceRow(value, DIGEST, 11L, 1L,
        "application/json", Instant.EPOCH);
  }

  private static void status(HttpStatus expected, Runnable action) {
    assertEquals(expected, assertThrows(ResponseStatusException.class, action::run).getStatusCode());
  }
}
