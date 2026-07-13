package com.github.klboke.kkrepo.server.docker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.github.klboke.kkrepo.core.BlobObjectMetadata;
import com.github.klboke.kkrepo.core.BlobReference;
import com.github.klboke.kkrepo.core.BlobStorage;
import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.core.RepositoryType;
import com.github.klboke.kkrepo.persistence.jdbc.api.DockerUploadDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.PersistenceHashes;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetBlobRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.docker.DockerUploadChunkRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.docker.DockerUploadSessionRecord;
import com.github.klboke.kkrepo.protocol.docker.DockerDigest;
import com.github.klboke.kkrepo.protocol.docker.DockerErrorCode;
import com.github.klboke.kkrepo.protocol.docker.DockerProtocolException;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntime;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class DockerUploadServiceTest {
  @Test
  void crossImageMountRequiresSourceImageReference() {
    DockerUploadDao uploadDao = mock(DockerUploadDao.class);
    DockerBlobStore blobStore = mock(DockerBlobStore.class);
    DockerManifestStore manifestStore = mock(DockerManifestStore.class);
    DockerUploadService service = new DockerUploadService(uploadDao, blobStore, manifestStore, 3600);
    RepositoryRuntime runtime = hostedRuntime();
    DockerDigest digest = DockerDigest.parse("sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
    DockerBlobStore.StoredBlob sourceBlob = storedBlob(runtime, digest);
    DockerBlobStore.StoredBlob mountedBlob = storedBlob(runtime, digest);
    when(manifestStore.referencesBlob(runtime, "library/base", digest)).thenReturn(true);
    when(blobStore.findBlob(runtime, digest)).thenReturn(java.util.Optional.of(sourceBlob));
    when(blobStore.mountBlob(runtime, runtime, sourceBlob, digest, "team/app", "user", "127.0.0.1"))
        .thenReturn(java.util.Optional.of(mountedBlob));

    DockerUploadService.UploadStatus status = service.start(
        runtime, "team/app", digest.value(), "library/base", runtime, "user", "127.0.0.1");

    assertEquals(true, status.mounted());
    verify(uploadDao, never()).insertSession(any(DockerUploadSessionRecord.class));
  }

  @Test
  void crossImageMountFallsBackToUploadWhenSourceImageDoesNotReferenceDigest() {
    DockerUploadDao uploadDao = mock(DockerUploadDao.class);
    DockerBlobStore blobStore = mock(DockerBlobStore.class);
    DockerManifestStore manifestStore = mock(DockerManifestStore.class);
    DockerUploadService service = new DockerUploadService(uploadDao, blobStore, manifestStore, 3600);
    RepositoryRuntime runtime = hostedRuntime();
    DockerDigest digest = DockerDigest.parse("sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
    when(manifestStore.referencesBlob(runtime, "library/base", digest)).thenReturn(false);

    DockerUploadService.UploadStatus status = service.start(
        runtime, "team/app", digest.value(), "library/base", runtime, "user", "127.0.0.1");

    assertEquals(false, status.mounted());
    verify(blobStore, never()).mountBlob(eq(runtime), eq(runtime), any(), eq(digest), eq("team/app"), eq("user"), eq("127.0.0.1"));
    verify(uploadDao).insertSession(any(DockerUploadSessionRecord.class));
  }

  @Test
  void completeReadsFinalChunkWhenContentLengthIsUnknown() {
    DockerUploadDao uploadDao = mock(DockerUploadDao.class);
    DockerBlobStore blobStore = mock(DockerBlobStore.class);
    DockerUploadService service = new DockerUploadService(uploadDao, blobStore, null, 3600);
    RepositoryRuntime runtime = hostedRuntime();
    InMemoryBlobStorage storage = new InMemoryBlobStorage();
    List<DockerUploadChunkRecord> chunks = new ArrayList<>();
    AtomicReference<byte[]> storedBody = new AtomicReference<>();
    DockerDigest digest = DockerDigest.parse(
        "sha256:ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
    when(blobStore.storage(runtime)).thenReturn(storage);
    when(uploadDao.lockSession("upload-1"))
        .thenReturn(Optional.of(session("upload-1", runtime, 0)))
        .thenReturn(Optional.of(session("upload-1", runtime, 0)))
        .thenReturn(Optional.of(session("upload-1", runtime, 3)));
    when(uploadDao.nextChunkIndex("upload-1")).thenReturn(0);
    when(uploadDao.listChunks("upload-1")).thenAnswer(invocation -> List.copyOf(chunks));
    org.mockito.Mockito.doAnswer(invocation -> {
      chunks.add(new DockerUploadChunkRecord(
          1L,
          invocation.getArgument(0),
          invocation.getArgument(1),
          invocation.getArgument(2),
          invocation.getArgument(3),
          invocation.getArgument(4),
          invocation.getArgument(5),
          invocation.getArgument(6),
          invocation.getArgument(7),
          Instant.now()));
      return null;
    }).when(uploadDao).appendChunk(
        eq("upload-1"), eq(0), eq(0L), eq(2L), any(), any(), any(), eq(3L), eq(3L));
    when(blobStore.putVerifiedBlob(
        eq(runtime),
        eq(digest),
        any(InputStream.class),
        eq(3L),
        eq("application/octet-stream"),
        eq("user"),
        eq("127.0.0.1")))
        .thenAnswer(invocation -> {
          try (InputStream in = invocation.getArgument(2)) {
            storedBody.set(in.readAllBytes());
          }
          return storedBlob(runtime, digest);
        });

    DockerUploadService.CompleteResult result = service.complete(
        runtime,
        "upload-1",
        new ByteArrayInputStream("abc".getBytes(java.nio.charset.StandardCharsets.UTF_8)),
        -1L,
        null,
        digest,
        "user",
        "127.0.0.1");

    assertEquals(digest, result.digest());
    assertEquals(3L, result.size());
    assertArrayEquals("abc".getBytes(java.nio.charset.StandardCharsets.UTF_8), storedBody.get());
    verify(uploadDao).completeSession("upload-1", digest.value(), digest.algorithm());
  }

  @Test
  void completeMergesMultipleStoredChunksInOrder() {
    DockerUploadDao uploadDao = mock(DockerUploadDao.class);
    DockerBlobStore blobStore = mock(DockerBlobStore.class);
    DockerUploadService service = new DockerUploadService(uploadDao, blobStore, null, 3600);
    RepositoryRuntime runtime = hostedRuntime();
    InMemoryBlobStorage storage = new InMemoryBlobStorage();
    List<DockerUploadChunkRecord> chunks = new ArrayList<>();
    AtomicReference<byte[]> storedBody = new AtomicReference<>();
    DockerDigest digest = DockerDigest.parse(
        "sha256:bef57ec7f53a6d40beb640a780a639c83bc29ac8a9816f1fc6c5c6dcd93c4721");
    when(blobStore.storage(runtime)).thenReturn(storage);
    when(uploadDao.lockSession("upload-2"))
        .thenReturn(Optional.of(session("upload-2", runtime, 0)))
        .thenReturn(Optional.of(session("upload-2", runtime, 3)))
        .thenReturn(Optional.of(session("upload-2", runtime, 3)))
        .thenReturn(Optional.of(session("upload-2", runtime, 6)));
    when(uploadDao.nextChunkIndex("upload-2")).thenReturn(0, 1);
    when(uploadDao.listChunks("upload-2")).thenAnswer(invocation -> List.copyOf(chunks));
    org.mockito.Mockito.doAnswer(invocation -> {
      chunks.add(new DockerUploadChunkRecord(
          chunks.size() + 1L,
          invocation.getArgument(0),
          invocation.getArgument(1),
          invocation.getArgument(2),
          invocation.getArgument(3),
          invocation.getArgument(4),
          invocation.getArgument(5),
          invocation.getArgument(6),
          invocation.getArgument(7),
          Instant.now()));
      return null;
    }).when(uploadDao).appendChunk(
        eq("upload-2"), anyInt(), anyLong(), anyLong(),
        any(), any(), any(), anyLong(), anyLong());
    when(blobStore.putVerifiedBlob(
        eq(runtime),
        eq(digest),
        any(InputStream.class),
        eq(6L),
        eq("application/octet-stream"),
        eq("user"),
        eq("127.0.0.1")))
        .thenAnswer(invocation -> {
          try (InputStream in = invocation.getArgument(2)) {
            storedBody.set(in.readAllBytes());
          } catch (IOException e) {
            throw new UncheckedIOException(e);
          }
          return storedBlob(runtime, digest, 6);
        });

    service.append(runtime, "upload-2",
        new ByteArrayInputStream("abc".getBytes(java.nio.charset.StandardCharsets.UTF_8)), 3L, null);
    DockerUploadService.CompleteResult result = service.complete(
        runtime,
        "upload-2",
        new ByteArrayInputStream("def".getBytes(java.nio.charset.StandardCharsets.UTF_8)),
        3L,
        null,
        digest,
        "user",
        "127.0.0.1");

    assertEquals(digest, result.digest());
    assertEquals(6L, result.size());
    assertArrayEquals("abcdef".getBytes(java.nio.charset.StandardCharsets.UTF_8), storedBody.get());
    assertEquals(List.of(
        DockerUploadService.uploadChunkPath("upload-2", 0),
        DockerUploadService.uploadChunkPath("upload-2", 1)),
        storage.logicalPaths());
    verify(uploadDao).completeSession("upload-2", digest.value(), digest.algorithm());
  }

  @Test
  void statusRejectsUploadSessionFromAnotherRepository() {
    DockerUploadDao uploadDao = mock(DockerUploadDao.class);
    DockerUploadService service = new DockerUploadService(
        uploadDao, mock(DockerBlobStore.class), null, 3600);
    RepositoryRuntime runtime = hostedRuntime();
    RepositoryRuntime otherRuntime = dockerRuntime(99L, "other-docker-hosted");
    when(uploadDao.findSession("upload-1"))
        .thenReturn(Optional.of(session("upload-1", otherRuntime, 3)));

    DockerProtocolException thrown = assertThrows(DockerProtocolException.class,
        () -> service.status(runtime, "upload-1"));

    assertEquals(DockerErrorCode.BLOB_UPLOAD_UNKNOWN, thrown.code());
  }

  @Test
  void cancelRejectsUploadSessionFromAnotherRepository() {
    DockerUploadDao uploadDao = mock(DockerUploadDao.class);
    DockerUploadService service = new DockerUploadService(
        uploadDao, mock(DockerBlobStore.class), null, 3600);
    RepositoryRuntime runtime = hostedRuntime();
    RepositoryRuntime otherRuntime = dockerRuntime(99L, "other-docker-hosted");
    when(uploadDao.lockSession("upload-1"))
        .thenReturn(Optional.of(session("upload-1", otherRuntime, 3)));

    DockerProtocolException thrown = assertThrows(DockerProtocolException.class,
        () -> service.cancel(runtime, "upload-1"));

    assertEquals(DockerErrorCode.BLOB_UPLOAD_UNKNOWN, thrown.code());
    verify(uploadDao, never()).cancelSession("upload-1");
  }

  @Test
  void appendRejectsOutOfOrderContentRangeStart() {
    DockerUploadDao uploadDao = mock(DockerUploadDao.class);
    DockerUploadService service = new DockerUploadService(
        uploadDao, mock(DockerBlobStore.class), null, 3600);
    RepositoryRuntime runtime = hostedRuntime();
    when(uploadDao.lockSession("upload-1"))
        .thenReturn(Optional.of(session("upload-1", runtime, 3)));

    DockerProtocolException thrown = assertThrows(DockerProtocolException.class,
        () -> service.append(
            runtime,
            "upload-1",
            new ByteArrayInputStream("abc".getBytes(java.nio.charset.StandardCharsets.UTF_8)),
            3L,
            "0-2"));

    assertEquals(DockerErrorCode.BLOB_UPLOAD_INVALID, thrown.code());
    assertEquals(416, thrown.status());
    verify(uploadDao, never()).appendChunk(any(), eq(0), eq(0L), eq(2L), any(), any(), any(), eq(3L), eq(3L));
  }

  @Test
  void appendRejectsContentRangeThatDoesNotMatchLength() {
    DockerUploadDao uploadDao = mock(DockerUploadDao.class);
    DockerUploadService service = new DockerUploadService(
        uploadDao, mock(DockerBlobStore.class), null, 3600);
    RepositoryRuntime runtime = hostedRuntime();
    when(uploadDao.lockSession("upload-1"))
        .thenReturn(Optional.of(session("upload-1", runtime, 0)));

    DockerProtocolException thrown = assertThrows(DockerProtocolException.class,
        () -> service.append(
            runtime,
            "upload-1",
            new ByteArrayInputStream("abc".getBytes(java.nio.charset.StandardCharsets.UTF_8)),
            3L,
            "0-4"));

    assertEquals(DockerErrorCode.SIZE_INVALID, thrown.code());
    assertEquals(400, thrown.status());
  }

  @Test
  void uploadRangeHeaderMatchesDockerRegistryProgressFormat() {
    assertEquals("0-0",
        new DockerUploadService.UploadStatus("upload-1", 0, 0, false, null).rangeHeader());
    assertEquals("0-41",
        new DockerUploadService.UploadStatus("upload-1", 0, 42, false, null).rangeHeader());
  }

  private static RepositoryRuntime hostedRuntime() {
    return dockerRuntime(10L, "docker-hosted");
  }

  private static RepositoryRuntime dockerRuntime(long id, String name) {
    return new RepositoryRuntime(
        id,
        name,
        RepositoryFormat.DOCKER,
        RepositoryType.HOSTED,
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
        false,
        null,
        null,
        List.of());
  }

  private static DockerUploadSessionRecord session(String uuid, RepositoryRuntime runtime, long nextOffset) {
    return new DockerUploadSessionRecord(
        uuid,
        runtime.id(),
        "library/alpine",
        PersistenceHashes.sha256("library/alpine"),
        "STARTED",
        nextOffset,
        null,
        null,
        "user",
        "127.0.0.1",
        Instant.now().plusSeconds(3600),
        null,
        null,
        Map.of(),
        Instant.now(),
        Instant.now());
  }

  private static DockerBlobStore.StoredBlob storedBlob(RepositoryRuntime runtime, DockerDigest digest) {
    return storedBlob(runtime, digest, 3);
  }

  private static DockerBlobStore.StoredBlob storedBlob(
      RepositoryRuntime runtime, DockerDigest digest, long size) {
    Instant now = Instant.now();
    AssetRecord asset = new AssetRecord(
        200L,
        runtime.id(),
        null,
        300L,
        RepositoryFormat.DOCKER,
        "docker/blobs/sha256/aa/" + digest.hex(),
        new byte[32],
        digest.value(),
        "BLOB",
        "application/octet-stream",
        size,
        null,
        now,
        Map.of());
    AssetBlobRecord blob = new AssetBlobRecord(
        300L,
        runtime.blobStoreId(),
        "blob-ref",
        new byte[32],
        "object-key",
        new byte[32],
        null,
        digest.hex(),
        null,
        size,
        "application/octet-stream",
        "user",
        "127.0.0.1",
        now,
        now,
        Map.of());
    return new DockerBlobStore.StoredBlob(asset, blob);
  }

  private static final class InMemoryBlobStorage implements BlobStorage {
    private final Map<String, byte[]> objects = new HashMap<>();
    private final List<String> logicalPaths = new ArrayList<>();

    @Override
    public BlobReference put(String repository, String logicalPath, InputStream content, long size, String sha256) {
      try {
        byte[] bytes = content.readAllBytes();
        objects.put(logicalPath, bytes);
        logicalPaths.add(logicalPath);
        return new BlobReference("test", logicalPath, sha256, bytes.length);
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
    }

    @Override
    public Optional<InputStream> get(BlobReference reference) {
      byte[] bytes = objects.get(reference.objectKey());
      return bytes == null ? Optional.empty() : Optional.of(new ByteArrayInputStream(bytes));
    }

    @Override
    public boolean exists(BlobReference reference) {
      return objects.containsKey(reference.objectKey());
    }

    @Override
    public Optional<BlobObjectMetadata> stat(BlobReference reference) {
      return Optional.empty();
    }

    @Override
    public void delete(BlobReference reference) {
      objects.remove(reference.objectKey());
    }

    @Override
    public Optional<Path> stagingDirectory() {
      return Optional.empty();
    }

    List<String> logicalPaths() {
      return List.copyOf(logicalPaths);
    }
  }
}
