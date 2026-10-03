package com.github.klboke.kkrepo.core;

import java.io.InputStream;
import java.io.UncheckedIOException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

public interface BlobStorage extends AutoCloseable {
  /** Allocates an attempt-unique, unpublished key without performing remote I/O. */
  default BlobReference prepareVerifiedUpload(String repository, String sha256, long size) {
    throw new UnsupportedOperationException("Verified streaming uploads are not supported");
  }

  /**
   * Writes to a previously persisted attempt key using bounded memory. The observer must persist
   * the multipart handle before any part is sent. Returns only after length and SHA-256 pass;
   * callers still own transactional publication and recovery of an interrupted attempt.
   */
  default VerifiedBlobDigests uploadVerified(
      BlobReference target, InputStream content, java.util.function.Consumer<String> multipartStarted) {
    throw new UnsupportedOperationException("Verified streaming uploads are not supported");
  }

  /** Aborts incomplete multipart state and removes only this unpublished attempt's object. */
  default void discardVerifiedUpload(BlobReference target, String multipartId) {
    delete(target);
  }

  BlobReference put(String repository, String logicalPath, InputStream content, long size, String sha256);

  default BlobReference putFile(String repository, String logicalPath, Path file, String sha256) {
    try (InputStream input = Files.newInputStream(file)) {
      return put(repository, logicalPath, input, Files.size(file), sha256);
    } catch (java.io.IOException e) {
      throw new UncheckedIOException("Failed to read blob upload file: " + file, e);
    }
  }

  /**
   * Optional local staging directory for callers that need to materialize a blob before invoking
   * {@link #putFile(String, String, Path, String)}. Implementations may return a directory on the
   * same filesystem as their final object location to allow hard-link or move based installs.
   */
  default Optional<Path> stagingDirectory() {
    return Optional.empty();
  }

  Optional<InputStream> get(BlobReference reference);

  default Optional<InputStream> getRange(BlobReference reference, long start, long length) {
    if (start < 0 || length < 0) {
      throw new IllegalArgumentException("range start and length must be non-negative");
    }
    Optional<InputStream> stream = get(reference);
    if (stream.isEmpty()) {
      return Optional.empty();
    }
    return Optional.of(new BoundedInputStream(skipFully(stream.get(), start), length));
  }

  boolean exists(BlobReference reference);

  Optional<BlobObjectMetadata> stat(BlobReference reference);

  void delete(BlobReference reference);

  @Override
  default void close() {
  }

  private static InputStream skipFully(InputStream in, long start) {
    try {
      long remaining = start;
      while (remaining > 0) {
        long skipped = in.skip(remaining);
        if (skipped <= 0) {
          if (in.read() < 0) {
            break;
          }
          skipped = 1;
        }
        remaining -= skipped;
      }
      return in;
    } catch (IOException e) {
      try {
        in.close();
      } catch (IOException ignored) {
      }
      throw new UncheckedIOException(e);
    }
  }

  final class BoundedInputStream extends InputStream {
    private final InputStream delegate;
    private long remaining;

    private BoundedInputStream(InputStream delegate, long length) {
      this.delegate = delegate;
      this.remaining = length;
    }

    @Override
    public int read() throws IOException {
      if (remaining <= 0) {
        return -1;
      }
      int value = delegate.read();
      if (value >= 0) {
        remaining--;
      }
      return value;
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
      if (remaining <= 0) {
        return -1;
      }
      int read = delegate.read(b, off, (int) Math.min(len, remaining));
      if (read > 0) {
        remaining -= read;
      }
      return read;
    }

    @Override
    public void close() throws IOException {
      delegate.close();
    }
  }
}
