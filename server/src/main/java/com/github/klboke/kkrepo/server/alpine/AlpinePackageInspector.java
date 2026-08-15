package com.github.klboke.kkrepo.server.alpine;

import com.github.klboke.kkrepo.protocol.alpine.AlpineChecksums;
import com.github.klboke.kkrepo.protocol.alpine.AlpinePackageInfo;
import com.github.klboke.kkrepo.protocol.alpine.AlpinePathParser;
import com.github.klboke.kkrepo.protocol.alpine.AlpineSignature;
import com.github.klboke.kkrepo.server.maven.MavenExceptions;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Bounded, non-executing APK v2 multipart inspector. */
@Component
final class AlpinePackageInspector {
  private static final int MAX_PKGINFO_BYTES = 1024 * 1024;
  private static final int MAX_SIGNATURE_ENTRIES = 16;
  private static final long MAX_SIGNATURE_BYTES = 65_536;

  private final long maxCompressedBytes;
  private final long maxExpandedMemberBytes;
  private final long maxExpandedTotalBytes;
  private final int maxControlEntries;
  private final int maxDataEntries;
  private final long maxEntryBytes;
  private final Duration timeout;
  private final Semaphore permits;
  private final long permitWaitMillis;

  @Autowired
  AlpinePackageInspector(
      @Value("${kkrepo.alpine.archive.max-compressed-bytes:5368709120}") long maxCompressedBytes,
      @Value("${kkrepo.alpine.archive.max-expanded-member-bytes:21474836480}")
          long maxExpandedMemberBytes,
      @Value("${kkrepo.alpine.archive.max-expanded-total-bytes:21474836480}")
          long maxExpandedTotalBytes,
      @Value("${kkrepo.alpine.archive.max-control-entries:4096}") int maxControlEntries,
      @Value("${kkrepo.alpine.archive.max-data-entries:1000000}") int maxDataEntries,
      @Value("${kkrepo.alpine.archive.max-entry-bytes:4294967296}") long maxEntryBytes,
      @Value("${kkrepo.alpine.archive.max-inspection-seconds:120}") long maxInspectionSeconds,
      @Value("${kkrepo.alpine.archive.max-concurrent-inspections:4}") int maxConcurrentInspections,
      @Value("${kkrepo.alpine.archive.inspection-permit-wait-ms:5000}") long permitWaitMillis) {
    this.maxCompressedBytes = Math.max(1, maxCompressedBytes);
    this.maxExpandedMemberBytes = Math.max(1, maxExpandedMemberBytes);
    this.maxExpandedTotalBytes = Math.max(1, maxExpandedTotalBytes);
    this.maxControlEntries = Math.max(1, maxControlEntries);
    this.maxDataEntries = Math.max(1, maxDataEntries);
    this.maxEntryBytes = Math.max(1, maxEntryBytes);
    this.timeout = Duration.ofSeconds(Math.max(1, maxInspectionSeconds));
    this.permits = new Semaphore(Math.max(1, maxConcurrentInspections), true);
    this.permitWaitMillis = Math.max(1, permitWaitMillis);
  }

  AlpinePackageInspector() {
    this(5L * 1024 * 1024 * 1024, 20L * 1024 * 1024 * 1024,
        20L * 1024 * 1024 * 1024, 4096, 1_000_000, 4L * 1024 * 1024 * 1024,
        120, 4, 5000);
  }

  InspectedPackage inspect(InputStream body, String filename) {
    requireFilename(filename);
    boolean acquired = acquirePermit();
    Path file = null;
    try {
      file = Files.createTempFile("kkrepo-alpine-", ".apk");
      BlobDigest blob = spool(body, file);
      List<AlpineGzipMembers.Member> members = AlpineGzipMembers.scan(
          file, 3, maxExpandedMemberBytes, maxExpandedTotalBytes, timeout);
      if (members.size() != 2 && members.size() != 3) {
        throw bad("APK v2 must contain control/data and optional signature gzip members");
      }
      int controlIndex = members.size() - 2;
      List<AlpineSignature.ParsedEntry> signatures = members.size() == 3
          ? inspectSignatures(file, members.getFirst()) : List.of();
      AlpinePackageInfo info = inspectControl(file, members.get(controlIndex));
      inspectData(file, members.getLast());
      String dataSha256 = hashRange(file, members.getLast(), "SHA-256");
      if (!dataSha256.equalsIgnoreCase(info.dataSha256())) {
        throw bad("APK datahash does not match the raw compressed data member");
      }
      String identity = "Q1" + Base64.getEncoder().encodeToString(
          digestRange(file, members.get(controlIndex), "SHA-1"));
      AlpineChecksums.requireV2Identity(identity);
      String canonicalFilename = AlpinePathParser.packageFilename(info.name(), info.version());
      if (!canonicalFilename.equals(filename)) {
        throw bad("APK filename must match .PKGINFO identity: " + canonicalFilename);
      }
      return new InspectedPackage(
          file,
          filename,
          info,
          identity,
          dataSha256,
          blob.sha256(),
          blob.size(),
          List.copyOf(signatures),
          members.get(controlIndex),
          members.getLast());
    } catch (MavenExceptions.BadRequestException error) {
      delete(file);
      throw error;
    } catch (IOException | RuntimeException error) {
      delete(file);
      throw bad("Unable to inspect APK v2 package", error);
    } finally {
      if (acquired) permits.release();
    }
  }

  private List<AlpineSignature.ParsedEntry> inspectSignatures(
      Path file, AlpineGzipMembers.Member member) throws IOException {
    ArrayList<AlpineSignature.ParsedEntry> signatures = new ArrayList<>();
    HashSet<String> names = new HashSet<>();
    try (InputStream expanded = AlpineGzipMembers.openExpanded(file, member);
        TarArchiveInputStream tar = new TarArchiveInputStream(expanded)) {
      for (TarArchiveEntry entry; (entry = tar.getNextEntry()) != null;) {
        if (signatures.size() >= MAX_SIGNATURE_ENTRIES) {
          throw bad("APK contains too many signatures");
        }
        requireRegularEntry(entry, "signature");
        String name = safePath(entry.getName(), false);
        if (!names.add(name)) throw bad("APK contains duplicate signature entries");
        AlpineSignature.ParsedEntry parsed;
        try {
          parsed = AlpineSignature.parseEntryName(name);
        } catch (IllegalArgumentException error) {
          throw bad("APK contains an invalid signature entry", error);
        }
        if (entry.getSize() <= 0 || entry.getSize() > MAX_SIGNATURE_BYTES) {
          throw bad("APK signature exceeds safety limits");
        }
        drainBounded(tar, entry.getSize());
        signatures.add(parsed);
      }
    }
    if (signatures.isEmpty()) throw bad("APK signature member is empty");
    return signatures;
  }

  private AlpinePackageInfo inspectControl(
      Path file, AlpineGzipMembers.Member member) throws IOException {
    byte[] pkgInfo = null;
    HashSet<String> names = new HashSet<>();
    int count = 0;
    try (InputStream expanded = AlpineGzipMembers.openExpanded(file, member);
        TarArchiveInputStream tar = new TarArchiveInputStream(expanded)) {
      for (TarArchiveEntry entry; (entry = tar.getNextEntry()) != null;) {
        if (++count > maxControlEntries) throw bad("APK control member has too many entries");
        requireSafePax(entry);
        String name = safePath(entry.getName(), false);
        if (!names.add(name)) throw bad("APK control member contains duplicate entries");
        if (entry.isDirectory()) continue;
        requireRegularEntry(entry, "control");
        if (".PKGINFO".equals(name)) {
          if (pkgInfo != null) throw bad("APK contains duplicate .PKGINFO metadata");
          pkgInfo = readBounded(tar, MAX_PKGINFO_BYTES);
        } else {
          if (!name.startsWith(".")) throw bad("APK control member contains a data entry");
          if (entry.getSize() > maxEntryBytes) throw bad("APK control entry is too large");
          drainBounded(tar, entry.getSize());
        }
      }
    }
    if (pkgInfo == null) throw bad("APK control member does not contain .PKGINFO");
    try {
      return AlpinePackageInfo.parse(decodeUtf8(pkgInfo));
    } catch (IllegalArgumentException error) {
      throw bad("Invalid APK .PKGINFO metadata", error);
    }
  }

  private void inspectData(Path file, AlpineGzipMembers.Member member) throws IOException {
    Set<String> paths = new HashSet<>();
    int count = 0;
    try (InputStream expanded = AlpineGzipMembers.openExpanded(file, member);
        TarArchiveInputStream tar = new TarArchiveInputStream(expanded)) {
      for (TarArchiveEntry entry; (entry = tar.getNextEntry()) != null;) {
        if (++count > maxDataEntries) throw bad("APK data member has too many entries");
        requireSafePax(entry);
        String path = safePath(entry.getName(), true);
        if (!paths.add(path)) throw bad("APK data member contains duplicate paths");
        if (entry.isCharacterDevice() || entry.isBlockDevice() || entry.isFIFO()
            || entry.isSparse()) {
          throw bad("APK data member contains an unsafe special entry");
        }
        if (entry.isSymbolicLink() || entry.isLink()) {
          safeLinkTarget(path, entry.getLinkName());
        } else if (!entry.isDirectory() && !entry.isFile()) {
          throw bad("APK data member contains an unsupported entry");
        }
        if (entry.getSize() < 0 || entry.getSize() > maxEntryBytes) {
          throw bad("APK data entry exceeds configured limits");
        }
        drainBounded(tar, entry.getSize());
      }
    }
    if (count == 0) throw bad("APK data member is empty");
  }

  private BlobDigest spool(InputStream input, Path file) throws IOException {
    if (input == null) throw bad("APK package body is required");
    MessageDigest sha256 = digest("SHA-256");
    long size = 0;
    try (var output = Files.newOutputStream(file)) {
      byte[] buffer = new byte[64 * 1024];
      for (int read; (read = input.read(buffer)) >= 0;) {
        if (read == 0) continue;
        size += read;
        if (size > maxCompressedBytes) throw bad("APK package exceeds the upload limit");
        sha256.update(buffer, 0, read);
        output.write(buffer, 0, read);
      }
    }
    if (size == 0) throw bad("APK package is empty");
    return new BlobDigest(HexFormat.of().formatHex(sha256.digest()), size);
  }

  private boolean acquirePermit() {
    try {
      if (permits.tryAcquire(permitWaitMillis, TimeUnit.MILLISECONDS)) return true;
      throw new MavenExceptions.WritePolicyDenied(
          "APK archive inspection capacity is busy; retry the request");
    } catch (InterruptedException error) {
      Thread.currentThread().interrupt();
      throw new MavenExceptions.WritePolicyDenied(
          "Interrupted while waiting for APK inspection capacity");
    }
  }

  private static void requireFilename(String filename) {
    if (filename == null || filename.isBlank() || filename.length() > 255
        || !filename.toLowerCase(Locale.ROOT).endsWith(".apk")
        || filename.indexOf('/') >= 0 || filename.indexOf('\\') >= 0
        || filename.startsWith(".")) {
      throw bad("Alpine packages must use a safe .apk filename");
    }
  }

  private static void requireRegularEntry(TarArchiveEntry entry, String section) {
    requireSafePax(entry);
    if (!entry.isFile() || entry.isSymbolicLink() || entry.isLink()
        || entry.isCharacterDevice() || entry.isBlockDevice() || entry.isFIFO()
        || entry.isSparse()) {
      throw bad("APK " + section + " member contains an unsafe entry");
    }
  }

  private static void requireSafePax(TarArchiveEntry entry) {
    Map<String, String> headers = entry.getExtraPaxHeaders();
    if (headers.size() > 128) throw bad("APK tar entry has too many PAX headers");
    int bytes = 0;
    for (Map.Entry<String, String> header : headers.entrySet()) {
      bytes += header.getKey().length() + header.getValue().length();
      if (bytes > 64 * 1024) throw bad("APK PAX headers exceed safety limits");
    }
  }

  private static String safePath(String raw, boolean allowDirectories) {
    if (raw == null || raw.isBlank() || raw.length() > 4096 || raw.startsWith("/")
        || raw.indexOf('\\') >= 0 || raw.indexOf('\0') >= 0) {
      throw bad("APK tar member contains an unsafe path");
    }
    String path = raw;
    while (path.startsWith("./")) path = path.substring(2);
    while (allowDirectories && path.endsWith("/") && path.length() > 1) {
      path = path.substring(0, path.length() - 1);
    }
    if (path.isBlank()) throw bad("APK tar member contains an empty path");
    for (String segment : path.split("/", -1)) {
      if (segment.isBlank() || segment.equals(".") || segment.equals("..")) {
        throw bad("APK tar member contains a dot path");
      }
    }
    return path;
  }

  private static void safeLinkTarget(String entryPath, String target) {
    if (target == null || target.isBlank() || target.startsWith("/")
        || target.indexOf('\\') >= 0 || target.indexOf('\0') >= 0) {
      throw bad("APK tar link has an unsafe target");
    }
    Path parent = Path.of(entryPath).getParent();
    Path resolved = (parent == null ? Path.of(target) : parent.resolve(target)).normalize();
    if (resolved.isAbsolute() || resolved.toString().equals("..")
        || resolved.startsWith("..")) {
      throw bad("APK tar link escapes the package root");
    }
  }

  private static byte[] readBounded(InputStream input, int limit) throws IOException {
    ByteArrayOutputStream output = new ByteArrayOutputStream(Math.min(limit, 64 * 1024));
    byte[] buffer = new byte[8192];
    int total = 0;
    for (int read; (read = input.read(buffer)) >= 0;) {
      if (read == 0) continue;
      total += read;
      if (total > limit) throw bad("APK metadata exceeds safety limits");
      output.write(buffer, 0, read);
    }
    return output.toByteArray();
  }

  private static void drainBounded(InputStream input, long expected) throws IOException {
    byte[] buffer = new byte[64 * 1024];
    long total = 0;
    while (total < expected) {
      int read = input.read(buffer, 0, (int) Math.min(buffer.length, expected - total));
      if (read < 0) throw bad("Truncated APK tar entry");
      if (read == 0) continue;
      total += read;
    }
  }

  private static String decodeUtf8(byte[] bytes) {
    try {
      return StandardCharsets.UTF_8.newDecoder()
          .onMalformedInput(CodingErrorAction.REPORT)
          .onUnmappableCharacter(CodingErrorAction.REPORT)
          .decode(ByteBuffer.wrap(bytes)).toString();
    } catch (CharacterCodingException error) {
      throw bad("APK .PKGINFO is not valid UTF-8", error);
    }
  }

  private static String hashRange(
      Path file, AlpineGzipMembers.Member member, String algorithm) throws IOException {
    return HexFormat.of().formatHex(digestRange(file, member, algorithm));
  }

  private static byte[] digestRange(
      Path file, AlpineGzipMembers.Member member, String algorithm) throws IOException {
    MessageDigest digest = digest(algorithm);
    try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ)) {
      long position = member.start();
      ByteBuffer buffer = ByteBuffer.allocate(64 * 1024);
      while (position < member.end()) {
        buffer.clear().limit((int) Math.min(buffer.capacity(), member.end() - position));
        int read = channel.read(buffer, position);
        if (read < 0) throw bad("Truncated APK gzip member");
        if (read == 0) continue;
        digest.update(buffer.array(), 0, read);
        position += read;
      }
    }
    return digest.digest();
  }

  private static MessageDigest digest(String algorithm) {
    try {
      return MessageDigest.getInstance(algorithm);
    } catch (NoSuchAlgorithmException error) {
      throw new IllegalStateException(error);
    }
  }

  private static void delete(Path path) {
    if (path == null) return;
    try {
      Files.deleteIfExists(path);
    } catch (IOException ignored) {
    }
  }

  private static MavenExceptions.BadRequestException bad(String message) {
    return new MavenExceptions.BadRequestException(message);
  }

  private static MavenExceptions.BadRequestException bad(String message, Throwable cause) {
    return new MavenExceptions.BadRequestException(message, cause);
  }

  record InspectedPackage(
      Path file,
      String filename,
      AlpinePackageInfo info,
      String identity,
      String dataSha256,
      String sha256,
      long size,
      List<AlpineSignature.ParsedEntry> signatures,
      AlpineGzipMembers.Member controlMember,
      AlpineGzipMembers.Member dataMember) implements AutoCloseable {
    @Override
    public void close() {
      delete(file);
    }
  }

  private record BlobDigest(String sha256, long size) {
  }
}
