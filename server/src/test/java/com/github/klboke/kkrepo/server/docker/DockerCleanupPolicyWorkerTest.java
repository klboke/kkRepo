package com.github.klboke.kkrepo.server.docker;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.core.RepositoryType;
import com.github.klboke.kkrepo.persistence.jdbc.api.DockerRegistryDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.RepositoryDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.RepositoryRecord;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntime;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntimeRegistry;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class DockerCleanupPolicyWorkerTest {
  @Test
  void cleanupAppliesDockerTagPolicyToHostedRepositoriesOnly() {
    RepositoryDao repositoryDao = mock(RepositoryDao.class);
    DockerRegistryDao dockerDao = mock(DockerRegistryDao.class);
    RepositoryRuntimeRegistry runtimeRegistry = mock(RepositoryRuntimeRegistry.class);
    DockerManifestStore manifestStore = mock(DockerManifestStore.class);
    DockerMetrics metrics = mock(DockerMetrics.class);
    RepositoryRecord dockerHosted = repository(10L, "docker-hosted", RepositoryFormat.DOCKER, true);
    RepositoryRecord mavenHosted = repository(11L, "maven-hosted", RepositoryFormat.MAVEN2, true);
    RepositoryRecord offlineDocker = repository(12L, "offline-docker", RepositoryFormat.DOCKER, false);
    RepositoryRuntime runtime = runtime(10L, "docker-hosted", RepositoryType.HOSTED);
    DockerRegistryDao.CleanupPolicyRecord policy =
        new DockerRegistryDao.CleanupPolicyRecord(100L, "docker-snapshot-tags", Map.of("tagRegex", ".*-SNAPSHOT"));
    when(repositoryDao.list()).thenReturn(List.of(dockerHosted, mavenHosted, offlineDocker));
    when(runtimeRegistry.resolveById(10L)).thenReturn(Optional.of(runtime));
    when(dockerDao.listCleanupPolicies(10L)).thenReturn(List.of(policy));
    when(dockerDao.listTagCleanupCandidates(10L, 8)).thenReturn(List.of(
        new DockerRegistryDao.CleanupTagCandidate("team/app", "1.0.0"),
        new DockerRegistryDao.CleanupTagCandidate("team/app", "1.0.1-SNAPSHOT")));
    when(manifestStore.deleteReference(runtime, "team/app", "1.0.1-SNAPSHOT")).thenReturn(1);
    DockerCleanupPolicyWorker worker = new DockerCleanupPolicyWorker(
        repositoryDao, dockerDao, runtimeRegistry, manifestStore, metrics, true, 8);

    worker.cleanup();

    verify(manifestStore, never()).deleteReference(runtime, "team/app", "1.0.0");
    verify(manifestStore).deleteReference(runtime, "team/app", "1.0.1-SNAPSHOT");
    verify(metrics).cleanup(runtime, "docker-snapshot-tags", "tag", "deleted", 1);
    verify(dockerDao, never()).listCleanupPolicies(11L);
    verify(dockerDao, never()).listCleanupPolicies(12L);
  }

  @Test
  void cleanupDeletesUntaggedAndOldManifestsByDigest() {
    RepositoryDao repositoryDao = mock(RepositoryDao.class);
    DockerRegistryDao dockerDao = mock(DockerRegistryDao.class);
    RepositoryRuntimeRegistry runtimeRegistry = mock(RepositoryRuntimeRegistry.class);
    DockerManifestStore manifestStore = mock(DockerManifestStore.class);
    DockerMetrics metrics = mock(DockerMetrics.class);
    RepositoryRuntime runtime = runtime(10L, "docker-hosted", RepositoryType.HOSTED);
    DockerRegistryDao.CleanupPolicyRecord policy = new DockerRegistryDao.CleanupPolicyRecord(
        100L,
        "docker-untagged-old",
        Map.of("untaggedOnly", true, "lastDownloadedDays", 30, "lastUpdatedDays", 7));
    when(repositoryDao.list()).thenReturn(List.of(repository(10L, "docker-hosted", RepositoryFormat.DOCKER, true)));
    when(runtimeRegistry.resolveById(10L)).thenReturn(Optional.of(runtime));
    when(dockerDao.listCleanupPolicies(10L)).thenReturn(List.of(policy));
    when(dockerDao.listManifestCleanupCandidates(
        eq(10L), eq(true), any(Instant.class), any(Instant.class), eq(16)))
        .thenReturn(List.of(
            new DockerRegistryDao.CleanupManifestCandidate(
                "team/app", "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"),
            new DockerRegistryDao.CleanupManifestCandidate(
                "team/base", "sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb")));
    when(manifestStore.deleteReference(
        eq(runtime), any(), any())).thenReturn(1);
    DockerCleanupPolicyWorker worker = new DockerCleanupPolicyWorker(
        repositoryDao, dockerDao, runtimeRegistry, manifestStore, metrics, true, 16);

    worker.cleanup();

    verify(manifestStore).deleteReference(
        runtime, "team/app", "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
    verify(manifestStore).deleteReference(
        runtime, "team/base", "sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb");
    verify(metrics).cleanup(runtime, "docker-untagged-old", "manifest", "deleted", 2);
  }

  @Test
  void cleanupRecordsPolicyErrorsAndContinues() {
    RepositoryDao repositoryDao = mock(RepositoryDao.class);
    DockerRegistryDao dockerDao = mock(DockerRegistryDao.class);
    RepositoryRuntimeRegistry runtimeRegistry = mock(RepositoryRuntimeRegistry.class);
    DockerManifestStore manifestStore = mock(DockerManifestStore.class);
    DockerMetrics metrics = mock(DockerMetrics.class);
    RepositoryRuntime runtime = runtime(10L, "docker-hosted", RepositoryType.HOSTED);
    DockerRegistryDao.CleanupPolicyRecord policy =
        new DockerRegistryDao.CleanupPolicyRecord(100L, "broken", Map.of("tagRegex", ".*"));
    when(repositoryDao.list()).thenReturn(List.of(repository(10L, "docker-hosted", RepositoryFormat.DOCKER, true)));
    when(runtimeRegistry.resolveById(10L)).thenReturn(Optional.of(runtime));
    when(dockerDao.listCleanupPolicies(10L)).thenReturn(List.of(policy));
    when(dockerDao.listTagCleanupCandidates(anyLong(), eq(4))).thenThrow(new IllegalStateException("boom"));
    DockerCleanupPolicyWorker worker = new DockerCleanupPolicyWorker(
        repositoryDao, dockerDao, runtimeRegistry, manifestStore, metrics, true, 4);

    worker.cleanup();

    verify(metrics).cleanup(runtime, "broken", "policy", "error", 1);
  }

  private static RepositoryRecord repository(long id, String name, RepositoryFormat format, boolean online) {
    return new RepositoryRecord(
        id,
        name,
        format,
        RepositoryType.HOSTED,
        format == RepositoryFormat.DOCKER ? "docker-hosted" : "maven2-hosted",
        online,
        1L,
        null,
        null,
        null,
        null,
        "ALLOW",
        true,
        Map.of());
  }

  private static RepositoryRuntime runtime(long id, String name, RepositoryType type) {
    return new RepositoryRuntime(
        id,
        name,
        RepositoryFormat.DOCKER,
        type,
        "docker-hosted",
        true,
        1L,
        "ALLOW",
        null,
        null,
        true,
        null,
        null,
        null,
        true,
        null,
        null,
        null,
        true,
        5000,
        null,
        List.of());
  }
}
