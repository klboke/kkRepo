package com.github.klboke.kkrepo.server.docker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.github.klboke.kkrepo.core.BlobReference;
import com.github.klboke.kkrepo.core.BlobStorage;
import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.core.RepositoryType;
import com.github.klboke.kkrepo.persistence.jdbc.api.AssetDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.DockerRegistryDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.PersistenceHashes;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetBlobRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.docker.DockerManifestRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.docker.DockerManifestReferenceRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.docker.DockerTagRecord;
import com.github.klboke.kkrepo.protocol.docker.DockerDigest;
import com.github.klboke.kkrepo.protocol.docker.DockerErrorCode;
import com.github.klboke.kkrepo.protocol.docker.DockerManifestDescriptor;
import com.github.klboke.kkrepo.protocol.docker.DockerManifestMetadata;
import com.github.klboke.kkrepo.protocol.docker.DockerProtocolException;
import com.github.klboke.kkrepo.server.cache.AssetMetadataCache;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntime;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import org.mockito.ArgumentCaptor;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;

class DockerManifestStoreTest {
  @Test
  void persistsManifestIdentityHashesAcrossBlobAssetManifestTagAndReferences() {
    AssetDao assetDao = mock(AssetDao.class);
    DockerRegistryDao dockerDao = mock(DockerRegistryDao.class);
    DockerBlobStore blobStore = mock(DockerBlobStore.class);
    DockerManifestParser parser = mock(DockerManifestParser.class);
    AssetMetadataCache assetMetadataCache = mock(AssetMetadataCache.class);
    BlobStorage storage = mock(BlobStorage.class);
    RepositoryRuntime runtime = runtime("ALLOW");
    byte[] body = "{}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    DockerDigest digest = DockerDigest.sha256(body);
    String subjectDigest = "sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";
    String referencedDigest = "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    DockerManifestMetadata metadata = new DockerManifestMetadata(
        "application/vnd.oci.image.manifest.v1+json",
        "application/vnd.example.sbom",
        subjectDigest,
        Map.of("org.opencontainers.image.title", "example"),
        List.of(new DockerManifestDescriptor(
            "layer",
            referencedDigest,
            "application/vnd.oci.image.layer.v1.tar+gzip",
            123L,
            Map.of("os", "linux"),
            Map.of())));
    BlobReference uploaded = new BlobReference("bucket", "objects/manifest", digest.hex(), body.length);
    when(parser.parse(body, metadata.mediaType())).thenReturn(metadata);
    when(blobStore.storage(runtime)).thenReturn(storage);
    when(storage.put(anyString(), anyString(), any(InputStream.class), anyLong(), anyString()))
        .thenReturn(uploaded);
    when(assetDao.insertBlobOrFindExisting(any(AssetBlobRecord.class)))
        .thenAnswer(invocation -> invocation.<AssetBlobRecord>getArgument(0).withId(11L));
    when(assetDao.findAssetByPath(runtime.id(), DockerManifestStore.manifestPath("team/app", digest)))
        .thenReturn(Optional.empty());
    when(assetDao.tryInsertAsset(any(AssetRecord.class))).thenReturn(OptionalLong.of(22L));
    when(dockerDao.upsertManifest(any(DockerManifestRecord.class)))
        .thenAnswer(invocation -> manifestWithId(invocation.getArgument(0), 33L));
    DockerManifestStore store = new DockerManifestStore(
        assetDao, dockerDao, blobStore, parser, assetMetadataCache, null);

    DockerManifestStore.StoredManifest stored = store.putManifest(
        runtime,
        "team/app",
        "latest",
        body,
        metadata.mediaType(),
        "alice",
        "127.0.0.1",
        false);

    ArgumentCaptor<AssetBlobRecord> blobCaptor = ArgumentCaptor.forClass(AssetBlobRecord.class);
    ArgumentCaptor<AssetRecord> assetCaptor = ArgumentCaptor.forClass(AssetRecord.class);
    ArgumentCaptor<DockerManifestRecord> manifestCaptor = ArgumentCaptor.forClass(DockerManifestRecord.class);
    ArgumentCaptor<DockerTagRecord> tagCaptor = ArgumentCaptor.forClass(DockerTagRecord.class);
    @SuppressWarnings("unchecked")
    ArgumentCaptor<List<DockerManifestReferenceRecord>> referencesCaptor =
        (ArgumentCaptor<List<DockerManifestReferenceRecord>>) (ArgumentCaptor<?>)
            ArgumentCaptor.forClass(List.class);
    verify(assetDao).insertBlobOrFindExisting(blobCaptor.capture());
    verify(assetDao).tryInsertAsset(assetCaptor.capture());
    verify(dockerDao).upsertManifest(manifestCaptor.capture());
    verify(dockerDao).replaceManifestReferences(eq(33L), referencesCaptor.capture());
    verify(dockerDao).upsertTag(tagCaptor.capture());

    AssetBlobRecord blob = blobCaptor.getValue();
    AssetRecord asset = assetCaptor.getValue();
    DockerManifestRecord manifest = manifestCaptor.getValue();
    DockerTagRecord tag = tagCaptor.getValue();
    DockerManifestReferenceRecord manifestReference = referencesCaptor.getValue().get(0);
    assertAllHashes(blob, asset, manifest, tag, manifestReference);
    assertEquals(11L, stored.blob().id());
    assertEquals(22L, stored.asset().id());
    assertEquals(33L, stored.manifest().id());
    verify(assetMetadataCache).evictAfterCommit(runtime.id(), asset.path());
  }

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
            PersistenceHashes.sha256(subject),
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

  private static DockerManifestRecord manifestWithId(DockerManifestRecord record, long id) {
    return new DockerManifestRecord(
        id,
        record.repositoryId(),
        record.imageName(),
        record.imageNameHash(),
        record.digestAlgorithm(),
        record.digest(),
        record.digestHash(),
        record.mediaType(),
        record.artifactType(),
        record.subjectDigest(),
        record.subjectDigestHash(),
        record.assetId(),
        record.size(),
        record.pushedBy(),
        record.pushedByIp(),
        record.deletedAt(),
        record.attributes(),
        record.createdAt(),
        record.updatedAt());
  }

  private static void assertAllHashes(
      AssetBlobRecord blob,
      AssetRecord asset,
      DockerManifestRecord manifest,
      DockerTagRecord tag,
      DockerManifestReferenceRecord manifestReference) {
    assertArrayEquals(PersistenceHashes.blobRefHash(blob.blobRef()), blob.blobRefHash());
    assertArrayEquals(PersistenceHashes.objectKeyHash(blob.objectKey()), blob.objectKeyHash());
    assertArrayEquals(PersistenceHashes.pathHash(asset.path()), asset.pathHash());
    assertArrayEquals(PersistenceHashes.sha256(manifest.imageName()), manifest.imageNameHash());
    assertArrayEquals(PersistenceHashes.sha256(manifest.digest()), manifest.digestHash());
    assertArrayEquals(PersistenceHashes.sha256(manifest.subjectDigest()), manifest.subjectDigestHash());
    assertArrayEquals(PersistenceHashes.sha256(tag.imageName()), tag.imageNameHash());
    assertArrayEquals(PersistenceHashes.sha256(tag.tag()), tag.tagHash());
    assertArrayEquals(PersistenceHashes.sha256(manifestReference.digest()), manifestReference.digestHash());
  }
}
