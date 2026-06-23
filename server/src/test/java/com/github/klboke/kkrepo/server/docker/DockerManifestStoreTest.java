package com.github.klboke.kkrepo.server.docker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.core.RepositoryType;
import com.github.klboke.kkrepo.persistence.mysql.dao.AssetDao;
import com.github.klboke.kkrepo.persistence.mysql.dao.DockerRegistryDao;
import com.github.klboke.kkrepo.persistence.mysql.model.docker.DockerManifestRecord;
import com.github.klboke.kkrepo.protocol.docker.DockerDigest;
import com.github.klboke.kkrepo.protocol.docker.DockerErrorCode;
import com.github.klboke.kkrepo.protocol.docker.DockerManifestMetadata;
import com.github.klboke.kkrepo.protocol.docker.DockerProtocolException;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;

class DockerManifestStoreTest {
  @Test
  void allowOnceRejectsExistingTagBeforeUploadingManifestBytes() {
    AssetDao assetDao = mock(AssetDao.class);
    DockerRegistryDao dockerDao = mock(DockerRegistryDao.class);
    DockerBlobStore blobStore = mock(DockerBlobStore.class);
    DockerManifestParser parser = mock(DockerManifestParser.class);
    DockerManifestStore store = new DockerManifestStore(assetDao, dockerDao, blobStore, parser, null, null);
    byte[] body = "{}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    when(parser.parse(body, "application/vnd.oci.image.manifest.v1+json"))
        .thenReturn(new DockerManifestMetadata(
            "application/vnd.oci.image.manifest.v1+json", null, null, Map.of(), List.of()));
    when(dockerDao.tagExists(runtime("ALLOW_ONCE").id(), "team/app", "latest")).thenReturn(true);

    DockerProtocolException thrown = assertThrows(DockerProtocolException.class,
        () -> store.putManifest(
            runtime("ALLOW_ONCE"),
            "team/app",
            "latest",
            body,
            "application/vnd.oci.image.manifest.v1+json",
            "alice",
            "127.0.0.1",
            true));

    assertEquals(DockerErrorCode.DENIED, thrown.code());
    assertEquals(403, thrown.status());
    verify(blobStore, never()).storage(org.mockito.ArgumentMatchers.any());
  }

  @Test
  void allowOnceRejectsExistingDigestBeforeUploadingManifestBytes() {
    AssetDao assetDao = mock(AssetDao.class);
    DockerRegistryDao dockerDao = mock(DockerRegistryDao.class);
    DockerBlobStore blobStore = mock(DockerBlobStore.class);
    DockerManifestParser parser = mock(DockerManifestParser.class);
    DockerManifestStore store = new DockerManifestStore(assetDao, dockerDao, blobStore, parser, null, null);
    byte[] body = "{}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    DockerDigest digest = DockerDigest.sha256(body);
    when(parser.parse(body, "application/vnd.oci.image.manifest.v1+json"))
        .thenReturn(new DockerManifestMetadata(
            "application/vnd.oci.image.manifest.v1+json", null, null, Map.of(), List.of()));
    when(dockerDao.findManifestByDigest(runtime("ALLOW_ONCE").id(), "team/app", digest.value()))
        .thenReturn(Optional.of(manifest(digest.value())));

    DockerProtocolException thrown = assertThrows(DockerProtocolException.class,
        () -> store.putManifest(
            runtime("ALLOW_ONCE"),
            "team/app",
            digest.value(),
            body,
            "application/vnd.oci.image.manifest.v1+json",
            "alice",
            "127.0.0.1",
            true));

    assertEquals(DockerErrorCode.DENIED, thrown.code());
    assertEquals(403, thrown.status());
    verify(blobStore, never()).storage(org.mockito.ArgumentMatchers.any());
  }

  @Test
  void deleteReferenceByTagDeletesOnlyTagPointer() {
    AssetDao assetDao = mock(AssetDao.class);
    DockerRegistryDao dockerDao = mock(DockerRegistryDao.class);
    DockerManifestStore store = new DockerManifestStore(
        assetDao, dockerDao, mock(DockerBlobStore.class), mock(DockerManifestParser.class), null, null);
    when(dockerDao.deleteTag(runtime("ALLOW").id(), "team/app", "latest")).thenReturn(1);

    int deleted = store.deleteReference(runtime("ALLOW"), "team/app", "latest");

    assertEquals(1, deleted);
    verify(dockerDao).deleteTag(runtime("ALLOW").id(), "team/app", "latest");
    verify(dockerDao, never()).deleteManifest(
        org.mockito.ArgumentMatchers.anyLong(),
        org.mockito.ArgumentMatchers.anyString(),
        org.mockito.ArgumentMatchers.anyString());
  }

  @Test
  void deleteReferenceByDigestDeletesManifestAssetAndMarksManifestBlobUnreferenced() {
    AssetDao assetDao = mock(AssetDao.class);
    DockerRegistryDao dockerDao = mock(DockerRegistryDao.class);
    DockerManifestStore store = new DockerManifestStore(
        assetDao, dockerDao, mock(DockerBlobStore.class), mock(DockerManifestParser.class), null, null);
    String digest = "sha256:dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd";
    when(dockerDao.deleteManifest(runtime("ALLOW").id(), "team/app", digest))
        .thenReturn(new DockerRegistryDao.DeletedManifest(1, 200L, 300L));

    int deleted = store.deleteReference(runtime("ALLOW"), "team/app", digest);

    assertEquals(1, deleted);
    verify(assetDao).deleteAssetById(200L);
    verify(assetDao).markBlobDeletedIfUnreferenced(300L, "docker manifest deleted");
  }

  @Test
  void deleteReferenceHasTransactionBoundaryForMandatoryDaoDelete() throws Exception {
    Transactional transactional = DockerManifestStore.class
        .getMethod("deleteReference", RepositoryRuntime.class, String.class, String.class)
        .getAnnotation(Transactional.class);

    assertNotNull(transactional);
  }

  @Test
  void hostedReferrersIncludeStoredManifestAnnotations() {
    DockerManifestStore manifestStore = mock(DockerManifestStore.class);
    DockerHostedService hosted = new DockerHostedService(
        mock(DockerBlobStore.class),
        manifestStore,
        mock(DockerUploadService.class));
    String subject = "sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";
    DockerDigest digest = DockerDigest.parse(subject);
    String referrer = "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    when(manifestStore.referrers(
        runtime("ALLOW"),
        subject,
        "application/vnd.nhl.peanut.butter.bagel"))
        .thenReturn(List.of(new DockerManifestRecord(
            100L,
            runtime("ALLOW").id(),
            "team/app",
            new byte[32],
            "sha256",
            referrer,
            new byte[32],
            "application/vnd.oci.image.manifest.v1+json",
            "application/vnd.nhl.peanut.butter.bagel",
            subject,
            DockerRegistryDao.hash(subject),
            200L,
            123,
            "alice",
            "127.0.0.1",
            null,
            Map.of("annotations", Map.of("org.opencontainers.conformance.test", "test config a")),
            null,
            null)));

    Map<String, Object> response = hosted.referrers(
        runtime("ALLOW"),
        digest,
        "application/vnd.nhl.peanut.butter.bagel");

    Object rawManifests = response.get("manifests");
    assertTrue(rawManifests instanceof List<?>);
    Map<?, ?> descriptor = (Map<?, ?>) ((List<?>) rawManifests).get(0);
    assertEquals(referrer, descriptor.get("digest"));
    assertEquals("application/vnd.nhl.peanut.butter.bagel", descriptor.get("artifactType"));
    assertEquals(
        "test config a",
        ((Map<?, ?>) descriptor.get("annotations")).get("org.opencontainers.conformance.test"));
  }

  private static RepositoryRuntime runtime(String writePolicy) {
    return new RepositoryRuntime(
        10L,
        "docker-hosted",
        RepositoryFormat.DOCKER,
        RepositoryType.HOSTED,
        "docker-hosted",
        true,
        1L,
        writePolicy,
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

  private static DockerManifestRecord manifest(String digest) {
    return new DockerManifestRecord(
        100L,
        10L,
        "team/app",
        new byte[32],
        "sha256",
        digest,
        new byte[32],
        "application/vnd.oci.image.manifest.v1+json",
        null,
        null,
        null,
        200L,
        2,
        "alice",
        "127.0.0.1",
        null,
        Map.of(),
        null,
        null);
  }
}
