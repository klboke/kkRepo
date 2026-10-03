package com.github.klboke.kkrepo.core;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/** Bounded one-pass verifier shared by S3, OSS and the development file backend. */
public final class VerifiedBlobReader {
  public static final int PART_BYTES = 8 * 1024 * 1024;
  public static final long MAX_BYTES = (long) PART_BYTES * 10_000;
  private final InputStream input;
  private final BlobReference target;
  private final MessageDigest sha256 = digest("SHA-256");
  private final MessageDigest sha1 = digest("SHA-1");
  private final MessageDigest md5 = digest("MD5");
  private long read;
  private boolean finished;

  public VerifiedBlobReader(BlobReference target, InputStream input) {
    this.target = Objects.requireNonNull(target);
    this.input = Objects.requireNonNull(input);
    if (target.size() < 0 || target.size() > MAX_BYTES
        || target.sha256() == null || !target.sha256().matches("[a-f0-9]{64}")) {
      throw new BlobIntegrityException("Invalid expected blob identity or size");
    }
  }

  public byte[] nextPart() {
    if (finished) throw new IllegalStateException("Upload already verified");
    int length = (int) Math.min(PART_BYTES, target.size() - read);
    try {
      byte[] part = input.readNBytes(length);
      if (part.length != length) throw new BlobIntegrityException("Object is shorter than declared size");
      sha256.update(part);
      sha1.update(part);
      md5.update(part);
      read += part.length;
      return part;
    } catch (IOException error) {
      throw new UncheckedIOException("Unable to read blob upload", error);
    }
  }

  public VerifiedBlobDigests finish() {
    if (finished) throw new IllegalStateException("Upload already verified");
    try {
      if (read != target.size() || input.read() != -1) {
        throw new BlobIntegrityException("Object length does not match declared size");
      }
    } catch (IOException error) {
      throw new UncheckedIOException("Unable to finish blob upload", error);
    }
    finished = true;
    String actual = HexFormat.of().formatHex(sha256.digest());
    if (!target.sha256().equals(actual)) throw new BlobIntegrityException("Object SHA-256 does not match OID");
    return new VerifiedBlobDigests(actual, HexFormat.of().formatHex(sha1.digest()),
        HexFormat.of().formatHex(md5.digest()), read);
  }

  private static MessageDigest digest(String name) {
    try { return MessageDigest.getInstance(name); }
    catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
  }
}
