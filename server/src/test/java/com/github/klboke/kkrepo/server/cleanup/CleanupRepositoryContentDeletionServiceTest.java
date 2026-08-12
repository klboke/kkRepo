package com.github.klboke.kkrepo.server.cleanup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.core.RepositoryType;
import com.github.klboke.kkrepo.server.apt.AptService;
import com.github.klboke.kkrepo.server.browse.BrowseContentDeleteController;
import com.github.klboke.kkrepo.server.browse.RepositoryContentDeletionService.CleanupDeleteSubject;
import com.github.klboke.kkrepo.server.conan.ConanService;
import com.github.klboke.kkrepo.server.docker.DockerManifestStore;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntime;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntimeRegistry;
import com.github.klboke.kkrepo.server.npm.NpmHostedService;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.api.Test;

class CleanupRepositoryContentDeletionServiceTest {
  @ParameterizedTest
  @EnumSource(
      value = RepositoryFormat.class,
      mode = EnumSource.Mode.EXCLUDE,
      names = {"DOCKER", "NPM"})
  void everyNonDockerFormatUsesTheExistingApplicationDeletionPath(RepositoryFormat format) {
    BrowseContentDeleteController browse = mock(BrowseContentDeleteController.class);
    RepositoryRuntimeRegistry runtimes = mock(RepositoryRuntimeRegistry.class);
    RepositoryRuntime runtime = mock(RepositoryRuntime.class);
    when(runtime.format()).thenReturn(format);
    when(runtimes.resolve("releases")).thenReturn(Optional.of(runtime));
    when(browse.deleteForCleanup("releases", "COMPONENT", 9L, "path", "cleanup"))
        .thenReturn(2);
    CleanupRepositoryContentDeletionService service =
        new CleanupRepositoryContentDeletionService(
            browse,
            runtimes,
            mock(DockerManifestStore.class),
            mock(NpmHostedService.class),
            mock(AptService.class));

    assertEquals(2, service.deleteForCleanup(
        "releases", "COMPONENT", 9L, "path", "cleanup"));

    verify(browse).deleteForCleanup("releases", "COMPONENT", 9L, "path", "cleanup");
  }

  @Test
  void hostedNpmComponentUsesVersionAwarePackumentDeletion() {
    RepositoryRuntimeRegistry runtimes = mock(RepositoryRuntimeRegistry.class);
    RepositoryRuntime runtime = mock(RepositoryRuntime.class);
    NpmHostedService npmHosted = mock(NpmHostedService.class);
    when(runtime.format()).thenReturn(RepositoryFormat.NPM);
    when(runtime.type()).thenReturn(RepositoryType.HOSTED);
    when(runtimes.resolve("npm-hosted")).thenReturn(Optional.of(runtime));
    when(npmHosted.deleteTarballForCleanup(
        runtime, "demo/-/demo-1.0.0.tgz", "cleanup")).thenReturn(1);
    CleanupRepositoryContentDeletionService service =
        new CleanupRepositoryContentDeletionService(
            mock(BrowseContentDeleteController.class),
            runtimes,
            mock(DockerManifestStore.class),
            npmHosted,
            mock(AptService.class));

    assertEquals(1, service.deleteForCleanup(
        "npm-hosted", "COMPONENT", 9L, "demo/-/demo-1.0.0.tgz", "cleanup"));

    verify(npmHosted).deleteTarballForCleanup(
        runtime, "demo/-/demo-1.0.0.tgz", "cleanup");
  }

  @Test
  void proxyNpmCacheStillUsesRepositoryOwnedApplicationDeletion() {
    BrowseContentDeleteController browse = mock(BrowseContentDeleteController.class);
    RepositoryRuntimeRegistry runtimes = mock(RepositoryRuntimeRegistry.class);
    RepositoryRuntime runtime = mock(RepositoryRuntime.class);
    when(runtime.format()).thenReturn(RepositoryFormat.NPM);
    when(runtime.type()).thenReturn(RepositoryType.PROXY);
    when(runtimes.resolve("npm-proxy")).thenReturn(Optional.of(runtime));
    when(browse.deleteForCleanup(
        "npm-proxy", "COMPONENT", 9L, "demo/-/demo-1.0.0.tgz", "cleanup"))
        .thenReturn(1);
    CleanupRepositoryContentDeletionService service =
        new CleanupRepositoryContentDeletionService(
            browse,
            runtimes,
            mock(DockerManifestStore.class),
            mock(NpmHostedService.class),
            mock(AptService.class));

    assertEquals(1, service.deleteForCleanup(
        "npm-proxy", "COMPONENT", 9L, "demo/-/demo-1.0.0.tgz", "cleanup"));

    verify(browse).deleteForCleanup(
        "npm-proxy", "COMPONENT", 9L, "demo/-/demo-1.0.0.tgz", "cleanup");
  }

  @Test
  void dockerManifestUsesTheRegistryReferenceDeletionPath() {
    RepositoryRuntimeRegistry runtimes = mock(RepositoryRuntimeRegistry.class);
    RepositoryRuntime runtime = mock(RepositoryRuntime.class);
    DockerManifestStore manifests = mock(DockerManifestStore.class);
    when(runtime.format()).thenReturn(RepositoryFormat.DOCKER);
    when(runtimes.resolve("images")).thenReturn(Optional.of(runtime));
    when(manifests.deleteReference(runtime, "team/app", "sha256:abc")).thenReturn(1);
    CleanupRepositoryContentDeletionService service =
        new CleanupRepositoryContentDeletionService(
            mock(BrowseContentDeleteController.class),
            runtimes,
            manifests,
            mock(NpmHostedService.class),
            mock(AptService.class));

    assertEquals(1, service.deleteForCleanup(
        "images", "DOCKER_MANIFEST", 9L, "team/app@sha256:abc", "cleanup"));

    verify(manifests).deleteReference(runtime, "team/app", "sha256:abc");
  }

  @Test
  void dockerRejectsGenericAssetDeletion() {
    RepositoryRuntimeRegistry runtimes = mock(RepositoryRuntimeRegistry.class);
    RepositoryRuntime runtime = mock(RepositoryRuntime.class);
    when(runtime.format()).thenReturn(RepositoryFormat.DOCKER);
    when(runtimes.resolve("images")).thenReturn(Optional.of(runtime));
    CleanupRepositoryContentDeletionService service =
        new CleanupRepositoryContentDeletionService(
            mock(BrowseContentDeleteController.class),
            runtimes,
            mock(DockerManifestStore.class),
            mock(NpmHostedService.class),
            mock(AptService.class));

    assertThrows(CleanupValidationException.class, () -> service.deleteForCleanup(
        "images", "ASSET", 9L, "blob", "cleanup"));
  }

  @Test
  void hostedAptBatchDeletesEveryArchitecturePerComponentAndPublishesOnce() {
    RepositoryRuntimeRegistry runtimes = mock(RepositoryRuntimeRegistry.class);
    RepositoryRuntime runtime = mock(RepositoryRuntime.class);
    AptService apt = mock(AptService.class);
    when(runtime.format()).thenReturn(RepositoryFormat.APT);
    when(runtime.type()).thenReturn(RepositoryType.HOSTED);
    when(runtimes.resolve("apt-hosted")).thenReturn(Optional.of(runtime));
    when(apt.deleteComponentsForCleanup(
        runtime, List.of(9L, 10L), "cleanup policy delete by cleanup"))
        .thenReturn(List.of(2, 1));
    CleanupRepositoryContentDeletionService service =
        new CleanupRepositoryContentDeletionService(
            mock(BrowseContentDeleteController.class),
            runtimes,
            mock(DockerManifestStore.class),
            mock(NpmHostedService.class),
            apt);

    assertEquals(
        List.of(2, 1),
        service.deleteBatchForCleanup(
            "apt-hosted",
            List.of(
                new CleanupDeleteSubject("COMPONENT", 9L, "amd64.deb"),
                new CleanupDeleteSubject("COMPONENT", 10L, "arm64.deb")),
            "cleanup"));

    verify(apt).deleteComponentsForCleanup(
        runtime, List.of(9L, 10L), "cleanup policy delete by cleanup");
  }

  @Test
  void conanComponentDeletionUsesRevisionAwareProtocolDeletionForSingleAndBatchRequests() {
    RepositoryRuntimeRegistry runtimes = mock(RepositoryRuntimeRegistry.class);
    RepositoryRuntime runtime = mock(RepositoryRuntime.class);
    ConanService conan = mock(ConanService.class);
    when(runtime.format()).thenReturn(RepositoryFormat.CONAN);
    when(runtimes.resolve("conan-hosted")).thenReturn(Optional.of(runtime));
    when(conan.deleteComponentForCleanup(runtime, 9L, "cleanup")).thenReturn(2);
    when(conan.deleteComponentForCleanup(runtime, 10L, "cleanup")).thenReturn(3);
    CleanupRepositoryContentDeletionService service =
        new CleanupRepositoryContentDeletionService(
            mock(BrowseContentDeleteController.class),
            runtimes,
            mock(DockerManifestStore.class),
            mock(NpmHostedService.class),
            mock(AptService.class));
    service.setConanService(conan);

    assertEquals(2, service.deleteForCleanup(
        "conan-hosted", "COMPONENT", 9L, "ignored", "cleanup"));
    assertEquals(
        List.of(2, 3),
        service.deleteBatchForCleanup(
            "conan-hosted",
            List.of(
                new CleanupDeleteSubject("COMPONENT", 9L, "first"),
                new CleanupDeleteSubject("COMPONENT", 10L, "second")),
            "cleanup"));

    verify(conan, org.mockito.Mockito.times(2))
        .deleteComponentForCleanup(runtime, 9L, "cleanup");
    verify(conan).deleteComponentForCleanup(runtime, 10L, "cleanup");
  }
}
