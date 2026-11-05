package com.github.klboke.kkrepo.server.docker;

import com.github.klboke.kkrepo.core.BlobReference;
import com.github.klboke.kkrepo.core.BlobStorage;
import com.github.klboke.kkrepo.persistence.jdbc.api.DockerUploadDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.PersistenceHashes;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.docker.DockerUploadChunkRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.docker.DockerUploadSessionRecord;
import com.github.klboke.kkrepo.protocol.docker.DockerConstants;
import com.github.klboke.kkrepo.protocol.docker.DockerDigest;
import com.github.klboke.kkrepo.protocol.docker.DockerErrorCode;
import com.github.klboke.kkrepo.protocol.docker.DockerProtocolException;
import com.github.klboke.kkrepo.server.blob.BlobReferenceCodec;
import com.github.klboke.kkrepo.server.blob.TempBlobFiles;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntime;
import com.github.klboke.kkrepo.server.transaction.TransientTransactionRetry;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
public class DockerUploadService {
  private static final Logger log = LoggerFactory.getLogger(DockerUploadService.class);
  private final TransientTransactionRetry transactions;
  private final DockerUploadDao uploadDao;
  private final DockerBlobStore blobStore;
  private final DockerManifestStore manifestStore;
  private final long uploadTtlSeconds;
  private final DockerMetrics metrics;

  @Autowired
  public DockerUploadService(
      DockerUploadDao uploadDao,
      DockerBlobStore blobStore,
      DockerManifestStore manifestStore,
      @Value("${kkrepo.docker.upload-session-ttl-seconds:86400}") long uploadTtlSeconds,
      @Autowired(required = false) DockerMetrics metrics,
      TransientTransactionRetry transactions) {
    this.transactions = Objects.requireNonNull(transactions, "transactions");
    this.uploadDao = uploadDao;
    this.blobStore = blobStore;
    this.manifestStore = manifestStore;
    this.uploadTtlSeconds = Math.max(300, uploadTtlSeconds);
    this.metrics = metrics;
  }

  public UploadStatus start(
      RepositoryRuntime runtime,
      String imageName,
      String mountDigest,
      String fromImage,
      RepositoryRuntime sourceRuntime,
      String createdBy,
      String createdByIp) {
    if (mountDigest != null && sourceRuntime != null) {
      DockerDigest digest = DockerDigest.parse(mountDigest);
      var mounted = mountExistingBlob(runtime, sourceRuntime, digest, imageName, fromImage, createdBy, createdByIp);
      if (mounted.isPresent()) {
        recordMount(runtime, sourceRuntime, "mounted");
        return new UploadStatus(null, 0, 0, true, digest);
      }
      recordMount(runtime, sourceRuntime, "fallback_upload");
    }
    String uuid = UUID.randomUUID().toString();
    uploadDao.insertSession(new DockerUploadSessionRecord(
        uuid,
        runtime.id(),
        imageName,
        PersistenceHashes.sha256(imageName),
        "STARTED",
        0,
        null,
        null,
        createdBy,
        createdByIp,
        Instant.now().plusSeconds(uploadTtlSeconds),
        null,
        null,
        Map.of("multiReplicaSemantics", "session and chunk offsets are shared-database truth; chunk bytes are blob-store objects"),
        null,
        null));
    recordUpload(runtime, "start", "created");
    return new UploadStatus(uuid, 0, 0, false, null);
  }

  private java.util.Optional<DockerBlobStore.StoredBlob> mountExistingBlob(
      RepositoryRuntime runtime,
      RepositoryRuntime sourceRuntime,
      DockerDigest digest,
      String targetImage,
      String fromImage,
      String createdBy,
      String createdByIp) {
    if (manifestStore == null) {
      return blobStore.mountBlob(runtime, sourceRuntime, digest, targetImage, createdBy, createdByIp);
    }
    String sourceImage = fromImage == null || fromImage.isBlank() ? targetImage : fromImage;
    if (!manifestStore.referencesBlob(sourceRuntime, sourceImage, digest)) {
      return java.util.Optional.empty();
    }
    return blobStore.findBlob(sourceRuntime, digest)
        .flatMap(sourceBlob -> blobStore.mountBlob(
            runtime, sourceRuntime, sourceBlob, digest, targetImage, createdBy, createdByIp));
  }

  @Transactional
  public UploadStatus status(RepositoryRuntime runtime, String uuid) {
    DockerUploadSessionRecord session = session(uuid);
    if (session.repositoryId() != runtime.id()) {
      throw new DockerProtocolException(DockerErrorCode.BLOB_UPLOAD_UNKNOWN, uuid);
    }
    ensureActive(session);
    return new UploadStatus(uuid, startOffset(session), session.nextOffset(), false, null);
  }

  /** Network I/O runs without a transaction; the shared session is revalidated at publication. */
  public UploadStatus append(
      RepositoryRuntime runtime, String uuid, InputStream body, Long contentLength, String contentRange) {
    requireNoTransaction();
    UploadSnapshot snapshot = snapshot(runtime, uuid, false);
    validateRange(snapshot, contentLength, contentRange);
    ChunkUpload chunk = uploadChunk(runtime, uuid, snapshot.chunkIndex(), body,
        contentLength == null ? -1 : contentLength);
    try {
      transactions.execute("Append Docker upload", () -> {
        ensureUnchanged(runtime, uuid, snapshot);
        appendChunk(uuid, snapshot, chunk);
        return null;
      });
    } catch (RuntimeException failure) {
      discardUnpublishedChunk(runtime, uuid, chunk);
      throw failure;
    }
    recordUpload(runtime, "append", "stored");
    return new UploadStatus(uuid, 0, snapshot.offset() + chunk.size(), false, null);
  }

  public CompleteResult complete(
      RepositoryRuntime runtime, String uuid, InputStream finalChunk, Long finalChunkLength,
      String contentRange, DockerDigest expectedDigest, String createdBy, String createdByIp) {
    requireNoTransaction();
    if (expectedDigest == null) {
      throw new DockerProtocolException(DockerErrorCode.DIGEST_INVALID, "digest query parameter is required", 400);
    }
    UploadSnapshot snapshot = snapshot(runtime, uuid, true);
    ChunkUpload extra = null;
    if (finalChunkLength == null || finalChunkLength != 0) {
      validateRange(snapshot, finalChunkLength, contentRange);
      extra = uploadChunk(runtime, uuid, snapshot.chunkIndex(), finalChunk,
          finalChunkLength == null ? -1 : finalChunkLength);
    }
    ChunkUpload finalUpload = extra;
    List<DockerUploadChunkRecord> chunks = new ArrayList<>(snapshot.chunks());
    if (extra != null) {
      chunks.add(new DockerUploadChunkRecord(0L, uuid, snapshot.chunkIndex(), snapshot.offset(),
          snapshot.offset() + Math.max(0, extra.size() - 1), extra.blobRef(), extra.objectKey(),
          extra.sha256(), extra.size(), Instant.now()));
    }
    try (InputStream in = chunkStream(runtime, chunks)) {
      var stored = blobStore.putVerifiedBlob(runtime, expectedDigest, in, totalChunkSize(chunks),
          "application/octet-stream", createdBy, createdByIp,
          persist -> transactions.execute("Complete Docker upload", () -> {
            ensureUnchanged(runtime, uuid, snapshot);
            if (finalUpload != null) appendChunk(uuid, snapshot, finalUpload);
            var result = persist.get();
            uploadDao.completeSession(uuid, expectedDigest.value(), expectedDigest.algorithm());
            return result;
          }));
      recordUpload(runtime, "complete", "stored");
      recordDigest(runtime, "upload_blob", "success");
      return new CompleteResult(expectedDigest, stored.blob().size(), stored.blob().id());
    } catch (RuntimeException | IOException failure) {
      if (extra != null) discardUnpublishedChunk(runtime, uuid, extra);
      recordUpload(runtime, "complete", "error");
      if (failure instanceof DockerProtocolException protocol && protocol.code() == DockerErrorCode.DIGEST_INVALID) {
        recordDigest(runtime, "upload_blob", "failure");
      }
      if (failure instanceof IOException io) throw new UncheckedIOException(io);
      throw (RuntimeException) failure;
    }
  }

  private UploadSnapshot snapshot(RepositoryRuntime runtime, String uuid, boolean includeChunks) {
    return transactions.execute("Read Docker upload", () -> {
      var session = lockedSession(uuid, runtime);
      ensureActive(session);
      return new UploadSnapshot(session.nextOffset(), uploadDao.nextChunkIndex(uuid),
          includeChunks ? List.copyOf(uploadDao.listChunks(uuid)) : List.of());
    });
  }

  private void ensureUnchanged(RepositoryRuntime runtime, String uuid, UploadSnapshot snapshot) {
    var session = lockedSession(uuid, runtime);
    ensureActive(session);
    // Checking the chunk sequence also fences zero-byte appends, which leave the offset unchanged.
    if (session.nextOffset() != snapshot.offset() || uploadDao.nextChunkIndex(uuid) != snapshot.chunkIndex()) {
      throw new DockerProtocolException(DockerErrorCode.BLOB_UPLOAD_INVALID,
          "upload changed while content was being stored; resume from the current upload offset", 416);
    }
  }

  private void validateRange(UploadSnapshot snapshot, Long length, String range) {
    if (parseContentRange(range, snapshot.offset(), length).start() != snapshot.offset()) {
      throw new DockerProtocolException(DockerErrorCode.BLOB_UPLOAD_INVALID,
          "unexpected upload offset, expected " + snapshot.offset(), 416);
    }
  }

  private void appendChunk(String uuid, UploadSnapshot snapshot, ChunkUpload chunk) {
    long start = snapshot.offset();
    uploadDao.appendChunk(uuid, snapshot.chunkIndex(), start, start + Math.max(0, chunk.size() - 1),
        chunk.blobRef(), chunk.objectKey(), chunk.sha256(), chunk.size(), start + chunk.size());
  }

  private void discardUnpublishedChunk(RepositoryRuntime runtime, String uuid, ChunkUpload chunk) {
    try {
      // A commit acknowledgement may have been lost; never delete a durably registered chunk.
      if (uploadDao.listChunks(uuid).stream().anyMatch(row -> chunk.objectKey().equals(row.objectKey()))) return;
      blobStore.storage(runtime).delete(BlobReferenceCodec.reference(
          chunk.blobRef(), chunk.objectKey(), chunk.sha256(), chunk.size()));
    } catch (RuntimeException cleanup) {
      log.warn("Failed cleaning unpublished Docker chunk uuid={} objectKey={}", uuid, chunk.objectKey(), cleanup);
    }
  }

  private static void requireNoTransaction() {
    if (TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalStateException("Docker upload I/O must not run inside a database transaction");
    }
  }

  private record UploadSnapshot(long offset, int chunkIndex, List<DockerUploadChunkRecord> chunks) {}

  @Transactional
  public void cancel(RepositoryRuntime runtime, String uuid) {
    lockedSession(uuid, runtime);
    uploadDao.cancelSession(uuid);
    recordUpload(runtime, "cancel", "cancelled");
  }

  private DockerUploadSessionRecord lockedSession(String uuid, RepositoryRuntime runtime) {
    DockerUploadSessionRecord session = uploadDao.lockSession(uuid)
        .orElseThrow(() -> new DockerProtocolException(DockerErrorCode.BLOB_UPLOAD_UNKNOWN, uuid));
    if (session.repositoryId() != runtime.id()) {
      throw new DockerProtocolException(DockerErrorCode.BLOB_UPLOAD_UNKNOWN, uuid);
    }
    return session;
  }

  private DockerUploadSessionRecord session(String uuid) {
    return uploadDao.findSession(uuid)
        .orElseThrow(() -> new DockerProtocolException(DockerErrorCode.BLOB_UPLOAD_UNKNOWN, uuid));
  }

  private void ensureActive(DockerUploadSessionRecord session) {
    if (!"STARTED".equals(session.status()) || session.expiresAt().isBefore(Instant.now())) {
      throw new DockerProtocolException(DockerErrorCode.BLOB_UPLOAD_UNKNOWN, session.uuid());
    }
  }

  private ChunkUpload uploadChunk(
      RepositoryRuntime runtime, String uuid, int chunkIndex, InputStream body, long expectedSize) {
    BlobStorage storage = blobStore.storage(runtime);
    Path temp = null;
    try {
      temp = TempBlobFiles.createTempFile(storage, "docker-upload-", ".chunk");
      MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
      long written;
      try (var out = Files.newOutputStream(temp)) {
        written = copyDigesting(body, out, sha256);
      }
      if (expectedSize >= 0 && written != expectedSize) {
        throw new DockerProtocolException(DockerErrorCode.SIZE_INVALID, "chunk size mismatch", 400);
      }
      String digestHex = HexFormat.of().formatHex(sha256.digest());
      BlobReference ref = storage.putFile(runtime.name(), uploadChunkPath(uuid, chunkIndex), temp, digestHex);
      return new ChunkUpload(BlobReferenceCodec.format(ref), ref.objectKey(), digestHex, written);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    } finally {
      TempBlobFiles.deleteQuietly(temp);
    }
  }

  private InputStream chunkStream(RepositoryRuntime runtime, List<DockerUploadChunkRecord> chunks) {
    BlobStorage storage = blobStore.storage(runtime);
    return new ChunkSequenceInputStream(storage, chunks);
  }

  private static long copyDigesting(InputStream in, java.io.OutputStream out, MessageDigest digest) throws IOException {
    byte[] buffer = new byte[TempBlobFiles.responseBufferSize()];
    long total = 0;
    int read;
    while ((read = in.read(buffer)) >= 0) {
      digest.update(buffer, 0, read);
      out.write(buffer, 0, read);
      total += read;
    }
    return total;
  }

  private static ContentRange parseContentRange(String contentRange, long expectedStart, Long contentLength) {
    if (contentRange == null || contentRange.isBlank()) {
      return new ContentRange(expectedStart, null);
    }
    String value = contentRange.trim();
    if (value.startsWith("bytes ")) {
      value = value.substring("bytes ".length());
    }
    int dash = value.indexOf('-');
    if (dash <= 0) {
      throw new DockerProtocolException(
          DockerErrorCode.BLOB_UPLOAD_INVALID, "invalid Content-Range header", 416);
    }
    try {
      long start = Long.parseLong(value.substring(0, dash));
      long end = Long.parseLong(value.substring(dash + 1));
      if (start < 0 || end < start) {
        throw new NumberFormatException();
      }
      if (contentLength != null && contentLength >= 0 && end - start + 1 != contentLength) {
        throw new DockerProtocolException(
            DockerErrorCode.SIZE_INVALID, "Content-Range does not match content length", 400);
      }
      return new ContentRange(start, end);
    } catch (NumberFormatException e) {
      throw new DockerProtocolException(
          DockerErrorCode.BLOB_UPLOAD_INVALID, "invalid Content-Range header", 416);
    }
  }

  private static long startOffset(DockerUploadSessionRecord session) {
    return session.nextOffset() <= 0 ? 0 : session.nextOffset() - 1;
  }

  static String uploadChunkPath(String uuid, int chunkIndex) {
    return "docker/uploads/" + uuid + "/" + chunkIndex;
  }

  private static long totalChunkSize(List<DockerUploadChunkRecord> chunks) {
    long total = 0;
    for (DockerUploadChunkRecord chunk : chunks) {
      total += Math.max(0, chunk.size());
    }
    return total;
  }

  private void recordUpload(RepositoryRuntime runtime, String action, String outcome) {
    if (metrics != null) {
      metrics.uploadSession(runtime, action, outcome);
    }
  }

  private void recordMount(RepositoryRuntime target, RepositoryRuntime source, String outcome) {
    if (metrics != null) {
      metrics.mount(target, source, outcome);
    }
  }

  private void recordDigest(RepositoryRuntime runtime, String target, String outcome) {
    if (metrics != null) {
      metrics.digestVerification(runtime, target, outcome);
    }
  }

  public record UploadStatus(
      String uuid,
      long rangeStart,
      long nextOffset,
      boolean mounted,
      DockerDigest mountedDigest) {
    public String rangeHeader() {
      return nextOffset <= 0 ? "0-0" : "0-" + (nextOffset - 1);
    }
  }

  public record CompleteResult(DockerDigest digest, long size, Long blobId) {
    public CompleteResult(DockerDigest digest, long size) {
      this(digest, size, null);
    }
  }

  private record ChunkUpload(String blobRef, String objectKey, String sha256, long size) {
  }

  private record ContentRange(long start, Long end) {
  }

  private static final class ChunkSequenceInputStream extends InputStream {
    private final BlobStorage storage;
    private final List<DockerUploadChunkRecord> chunks;
    private int index;
    private InputStream current;

    private ChunkSequenceInputStream(BlobStorage storage, List<DockerUploadChunkRecord> chunks) {
      this.storage = Objects.requireNonNull(storage, "storage");
      this.chunks = chunks == null ? List.of() : chunks;
    }

    @Override
    public int read() throws IOException {
      while (currentStream()) {
        int value = current.read();
        if (value >= 0) {
          return value;
        }
        closeCurrent();
      }
      return -1;
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
      while (currentStream()) {
        int read = current.read(b, off, len);
        if (read >= 0) {
          return read;
        }
        closeCurrent();
      }
      return -1;
    }

    @Override
    public void close() throws IOException {
      closeCurrent();
    }

    private boolean currentStream() {
      if (current != null) {
        return true;
      }
      if (index >= chunks.size()) {
        return false;
      }
      DockerUploadChunkRecord chunk = chunks.get(index++);
      BlobReference reference = BlobReferenceCodec.reference(
          chunk.blobRef(), chunk.objectKey(), chunk.sha256(), chunk.size());
      current = storage.get(reference)
          .orElseThrow(() -> new DockerProtocolException(DockerErrorCode.BLOB_UPLOAD_INVALID,
              "missing upload chunk " + chunk.id(), 400));
      return true;
    }

    private void closeCurrent() throws IOException {
      if (current != null) {
        try {
          current.close();
        } finally {
          current = null;
        }
      }
    }
  }
}
