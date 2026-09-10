package com.github.klboke.kkrepo.server.browse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
      verify(manifests).deleteBrowseReference(runtime, "team/app", reference);
    }
  }

  @Test
  void proxyDeletionOnlyUsesLocalRegistryState() {
    RepositoryRuntime runtime = referenceExists(proxy, "hello-world", "latest");
    when(repositories.findByName(proxy.name())).thenReturn(Optional.of(proxy));
    assertEquals(proxy.name(), service.delete(
        proxy, "hello-world/manifests/latest", "  " + proxy.name() + " ").sourceRepository());
    verify(manifests).deleteBrowseReference(runtime, "hello-world", "latest");
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
    verify(manifests).deleteBrowseReference(runtime, "hello-world", "latest");
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
  @ValueSource(strings = {"team", "team/manifests", "team/app/manifests",
      "team/manifests/child/manifests", "team/app/manifests/latest/child"})
  void directoryPathsCannotBeReinterpretedAsAnotherImage(String path) {
    status(HttpStatus.BAD_REQUEST, () -> service.delete(hosted, path, null));
    verifyNoInteractions(docker, manifests);
  }

  @Test
  void imageNamesEndingInManifestsRetainTheirIdentity() {
    for (String image : List.of("team", "team/manifests", "team/manifests/child")) {
      RepositoryRuntime runtime = referenceExists(hosted, image, "latest");
      assertEquals(1, service.delete(hosted, image + "/manifests/latest", null).deletedAssets());
      verify(manifests).deleteBrowseReference(runtime, image, "latest");
    }
  }

  @Test
  void missingPathsAndReferencesReturn404WithoutMutation() {
    for (String path : List.of("missing/manifests/latest", "missing/manifests/" + DIGEST)) {
      status(HttpStatus.NOT_FOUND, () -> service.delete(hosted, path, null));
    }
    when(repositories.listMembers(group.id())).thenReturn(List.of());
    status(HttpStatus.NOT_FOUND, () -> service.delete(group, "missing/manifests/latest", null));
    verifyNoInteractions(manifests);
  }

  @Test
  void deletedDigestCannotBeDeletedAgainWhileItsTagsRetainTheBody() {
    DockerManifestRecord manifest = mock(DockerManifestRecord.class);
    when(docker.findManifestByReference(hosted.id(), "team/app", DIGEST)).thenReturn(Optional.of(manifest));
    status(HttpStatus.NOT_FOUND, () -> service.delete(hosted, "team/app/manifests/" + DIGEST, null));
    verifyNoInteractions(manifests);
  }

  @Test
  void invalidPathsReturn400WithoutMutation() {
    for (String path : List.of("", "Team/app/manifests/latest", "team/app/manifests/", "team/app/manifests/bad tag")) {
      status(HttpStatus.BAD_REQUEST, () -> service.delete(hosted, path, null));
    }
    verifyNoInteractions(manifests);
  }

  @Test
  void sourceMustExistAndBelongToRequestedRepository() {
    status(HttpStatus.NOT_FOUND, () -> service.delete(hosted, "team/app/manifests/latest", "missing"));
    when(repositories.findByName(proxy.name())).thenReturn(Optional.of(proxy));
    status(HttpStatus.BAD_REQUEST, () -> service.delete(hosted, "team/app/manifests/latest", proxy.name()));
    when(repositories.listMembers(group.id())).thenReturn(List.of(hosted));
    status(HttpStatus.BAD_REQUEST, () -> service.delete(group, "team/app/manifests/latest", proxy.name()));
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
    DockerManifestRecord manifest = mock(DockerManifestRecord.class);
    when(manifest.hasDigestReference()).thenReturn(true);
    when(docker.findManifestByReference(repository.id(), image, value)).thenReturn(Optional.of(manifest));
    RepositoryRuntime runtime = runtime(repository);
    when(manifests.deleteBrowseReference(runtime, image, value)).thenReturn(1);
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

  private static void status(HttpStatus expected, Runnable action) {
    assertEquals(expected, assertThrows(ResponseStatusException.class, action::run).getStatusCode());
  }
}
