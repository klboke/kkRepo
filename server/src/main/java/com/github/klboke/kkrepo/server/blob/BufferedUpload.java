package com.github.klboke.kkrepo.server.blob;

import com.github.klboke.kkrepo.core.BlobStorage;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Streams an upload through one bounded buffer while calculating its content identity.
 * Input streams belong to callers. A successfully created file transfers to the caller, which may
 * retain it for a response or delete it through {@link TempBlobFiles}; failed staging is cleaned up.
 * These files are request-scoped staging, never durable repository state.
 */
public record BufferedUpload(Path file, Digests digests) {
  public enum Checksums { STANDARD, WITH_SHA512 }

  public record Digests(String md5, String sha1, String sha256, String sha512, long size) { }

  public static BufferedUpload create(
      InputStream input, BlobStorage stagingStorage, String prefix, Checksums checksums)
      throws IOException {
    Path file = TempBlobFiles.createTempFile(stagingStorage, prefix, ".tmp");
    try {
      return new BufferedUpload(file, copy(input, file, checksums));
    } catch (IOException | RuntimeException | Error error) {
      TempBlobFiles.deleteQuietly(file);
      throw error;
    }
  }

  /** Writes into a caller-owned staging file without changing its lifecycle. */
  public static Digests copy(InputStream input, Path target, Checksums checksums) throws IOException {
    try (OutputStream out = Files.newOutputStream(target, StandardOpenOption.TRUNCATE_EXISTING)) {
      return transfer(input, out, checksums);
    }
  }

  /** Digests an existing file without copying, modifying, or deleting it. */
  public static Digests inspect(Path file, Checksums checksums) throws IOException {
    try (InputStream input = Files.newInputStream(file)) {
      return transfer(input, OutputStream.nullOutputStream(), checksums);
    }
  }

  private static Digests transfer(InputStream input, OutputStream output, Checksums checksums)
      throws IOException {
    MessageDigest md5 = digest("MD5");
    MessageDigest sha1 = digest("SHA-1");
    MessageDigest sha256 = digest("SHA-256");
    MessageDigest sha512 = checksums == Checksums.WITH_SHA512 ? digest("SHA-512") : null;
    byte[] buffer = new byte[TempBlobFiles.responseBufferSize()];
    long size = 0;
    for (int read; (read = input.read(buffer)) >= 0;) {
      if (read == 0) continue;
      md5.update(buffer, 0, read);
      sha1.update(buffer, 0, read);
      sha256.update(buffer, 0, read);
      if (sha512 != null) sha512.update(buffer, 0, read);
      output.write(buffer, 0, read);
      size += read;
    }
    return new Digests(hex(md5), hex(sha1), hex(sha256), sha512 == null ? null : hex(sha512), size);
  }

  private static MessageDigest digest(String algorithm) {
    try {
      return MessageDigest.getInstance(algorithm);
    } catch (NoSuchAlgorithmException error) {
      throw new IllegalStateException("Missing required digest " + algorithm, error);
    }
  }

  private static String hex(MessageDigest digest) {
    return HexFormat.of().formatHex(digest.digest());
  }
}
