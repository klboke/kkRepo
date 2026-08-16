package com.github.klboke.kkrepo.server.alpine;

import com.github.klboke.kkrepo.protocol.alpine.AlpineIndex;
import com.github.klboke.kkrepo.protocol.alpine.AlpineIndexRecord;
import com.github.klboke.kkrepo.protocol.alpine.AlpineSignature;
import com.github.klboke.kkrepo.server.maven.MavenExceptions;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.GeneralSecurityException;
import java.security.PublicKey;
import java.security.Signature;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;

/** Strict reader and trust verifier for signed APK v2 index archives. */
final class AlpineIndexArchive {
  private static final long MAX_COMPRESSED_BYTES = 512L * 1024 * 1024;
  private static final long MAX_EXPANDED_BYTES = 1024L * 1024 * 1024;
  private static final int MAX_INDEX_BYTES = 512 * 1024 * 1024;
  private static final int MAX_DESCRIPTION_BYTES = 1024 * 1024;

  private AlpineIndexArchive() {
  }

  static Parsed read(
      InputStream input,
      List<String> configuredKeys,
      boolean verificationRequired) {
    Path file = null;
    try {
      file = Files.createTempFile("kkrepo-alpine-index-", ".tar.gz");
      String sha256 = spool(input, file);
      List<AlpineGzipMembers.Member> members = AlpineGzipMembers.scan(
          file, 2, MAX_EXPANDED_BYTES, MAX_EXPANDED_BYTES, Duration.ofMinutes(2));
      if (members.size() < 1 || members.size() > 2) {
        throw bad("Upstream APKINDEX must contain an optional signature and one index member");
      }
      SignatureEntry signature = members.size() == 2
          ? readSignature(file, members.getFirst()) : null;
      AlpineGzipMembers.Member unsigned = members.getLast();
      byte[] index = readIndex(file, unsigned);
      List<AlpineIndexRecord> records = AlpineIndex.parse(index);
      boolean verified = signature != null
          && verify(file, unsigned, signature, parseKeys(configuredKeys));
      if (verificationRequired && !verified) {
        throw bad("Upstream APKINDEX signature is missing, untrusted, or invalid");
      }
      return new Parsed(records, sha256, signature, verified);
    } catch (MavenExceptions.BadUpstreamException error) {
      throw error;
    } catch (IOException | RuntimeException error) {
      throw bad("Invalid upstream APKINDEX.tar.gz", error);
    } finally {
      if (file != null) {
        try {
          Files.deleteIfExists(file);
        } catch (IOException ignored) {
        }
      }
    }
  }

  private static String spool(InputStream input, Path file) throws IOException {
    if (input == null) throw bad("Upstream APKINDEX body is missing");
    java.security.MessageDigest digest;
    try {
      digest = java.security.MessageDigest.getInstance("SHA-256");
    } catch (GeneralSecurityException impossible) {
      throw new IllegalStateException(impossible);
    }
    long total = 0;
    try (input; var output = Files.newOutputStream(file)) {
      byte[] buffer = new byte[64 * 1024];
      for (int read; (read = input.read(buffer)) >= 0;) {
        if (read == 0) continue;
        total += read;
        if (total > MAX_COMPRESSED_BYTES) throw bad("Upstream APKINDEX exceeds size limit");
        digest.update(buffer, 0, read);
        output.write(buffer, 0, read);
      }
    }
    if (total == 0) throw bad("Upstream APKINDEX is empty");
    return HexFormat.of().formatHex(digest.digest());
  }

  private static SignatureEntry readSignature(
      Path file, AlpineGzipMembers.Member member) throws IOException {
    SignatureEntry found = null;
    try (InputStream expanded = AlpineGzipMembers.openExpanded(file, member);
        TarArchiveInputStream tar = new TarArchiveInputStream(expanded)) {
      for (TarArchiveEntry entry; (entry = tar.getNextEntry()) != null;) {
        requireRegular(entry, "signature");
        if (found != null) throw bad("APKINDEX signature member has multiple entries");
        AlpineSignature.ParsedEntry parsed;
        try {
          parsed = AlpineSignature.parseEntryName(entry.getName());
        } catch (IllegalArgumentException invalid) {
          throw bad("APKINDEX signature entry name is invalid", invalid);
        }
        if (entry.getSize() <= 0 || entry.getSize() > 65_536) {
          throw bad("APKINDEX signature entry exceeds size limit");
        }
        found = new SignatureEntry(
            parsed.type(), parsed.keyFilename(), readBounded(tar, 65_536));
      }
    }
    if (found == null) throw bad("APKINDEX signature member is empty");
    return found;
  }

  private static byte[] readIndex(
      Path file, AlpineGzipMembers.Member member) throws IOException {
    byte[] index = null;
    boolean description = false;
    int entries = 0;
    try (InputStream expanded = AlpineGzipMembers.openExpanded(file, member);
        TarArchiveInputStream tar = new TarArchiveInputStream(expanded)) {
      for (TarArchiveEntry entry; (entry = tar.getNextEntry()) != null;) {
        if (++entries > 2) throw bad("APKINDEX archive contains unexpected entries");
        requireRegular(entry, "index");
        if ("DESCRIPTION".equals(entry.getName())) {
          if (description) throw bad("APKINDEX archive contains duplicate DESCRIPTION");
          readBounded(tar, MAX_DESCRIPTION_BYTES);
          description = true;
        } else if ("APKINDEX".equals(entry.getName())) {
          if (index != null) throw bad("APKINDEX archive contains duplicate APKINDEX");
          index = readBounded(tar, MAX_INDEX_BYTES);
        } else {
          throw bad("APKINDEX archive contains an unexpected entry: " + entry.getName());
        }
      }
    }
    if (index == null) throw bad("APKINDEX archive is missing APKINDEX");
    return index;
  }

  private static boolean verify(
      Path file,
      AlpineGzipMembers.Member unsigned,
      SignatureEntry signature,
      List<TrustedKey> keys) throws IOException {
    for (TrustedKey trusted : keys) {
      if (!trusted.filename().equals(signature.keyFilename())) continue;
      try {
        Signature verifier = Signature.getInstance(signature.type().jcaAlgorithm());
        verifier.initVerify(trusted.key());
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ)) {
          long position = unsigned.start();
          ByteBuffer buffer = ByteBuffer.allocate(64 * 1024);
          while (position < unsigned.end()) {
            buffer.clear().limit((int) Math.min(buffer.capacity(), unsigned.end() - position));
            int read = channel.read(buffer, position);
            if (read < 0) throw bad("Truncated unsigned APKINDEX member");
            if (read == 0) continue;
            verifier.update(buffer.array(), 0, read);
            position += read;
          }
        }
        if (verifier.verify(signature.bytes())) return true;
      } catch (GeneralSecurityException invalid) {
        throw bad("Unable to verify upstream APKINDEX signature", invalid);
      }
    }
    return false;
  }

  private static List<TrustedKey> parseKeys(List<String> configured) {
    ArrayList<TrustedKey> keys = new ArrayList<>();
    for (String value : configured == null ? List.<String>of() : configured) {
      if (value == null || value.isBlank()) continue;
      String normalized = value.replace("\r\n", "\n").trim();
      int newline = normalized.indexOf('\n');
      if (newline <= "filename=".length()
          || !normalized.substring(0, newline).toLowerCase(Locale.ROOT)
              .startsWith("filename=")) {
        throw bad(
            "Each Alpine upstream public key must start with filename=<name>.rsa.pub");
      }
      String filename = AlpineSignature.requireKeyFilename(
          normalized.substring("filename=".length(), newline).trim());
      String pem = normalized.substring(newline + 1).trim();
      keys.add(new TrustedKey(filename, AlpineSigningService.parsePublic(pem)));
    }
    return List.copyOf(keys);
  }

  private static void requireRegular(TarArchiveEntry entry, String section) {
    if (!entry.isFile() || entry.isSymbolicLink() || entry.isLink()
        || entry.isCharacterDevice() || entry.isBlockDevice() || entry.isFIFO()
        || entry.isSparse() || entry.getName() == null || entry.getName().contains("/")
        || entry.getName().contains("\\") || entry.getName().contains("..")) {
      throw bad("APKINDEX " + section + " member contains an unsafe entry");
    }
  }

  private static byte[] readBounded(InputStream input, int limit) throws IOException {
    ByteArrayOutputStream output = new ByteArrayOutputStream(Math.min(limit, 64 * 1024));
    byte[] buffer = new byte[64 * 1024];
    int total = 0;
    for (int read; (read = input.read(buffer)) >= 0;) {
      if (read == 0) continue;
      total = Math.addExact(total, read);
      if (total > limit) throw bad("APKINDEX entry exceeds size limit");
      output.write(buffer, 0, read);
    }
    return output.toByteArray();
  }

  private static MavenExceptions.BadUpstreamException bad(String message) {
    return new MavenExceptions.BadUpstreamException(message);
  }

  private static MavenExceptions.BadUpstreamException bad(String message, Throwable cause) {
    return new MavenExceptions.BadUpstreamException(message, cause);
  }

  record Parsed(
      List<AlpineIndexRecord> records,
      String sha256,
      SignatureEntry signature,
      boolean signatureVerified) {
  }

  record SignatureEntry(AlpineSignature.Type type, String keyFilename, byte[] bytes) {
    SignatureEntry {
      bytes = bytes.clone();
    }

    @Override
    public byte[] bytes() {
      return bytes.clone();
    }
  }

  private record TrustedKey(String filename, PublicKey key) {
  }
}
