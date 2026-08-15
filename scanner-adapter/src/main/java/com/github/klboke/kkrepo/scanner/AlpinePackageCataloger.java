package com.github.klboke.kkrepo.scanner;

import com.github.klboke.kkrepo.protocol.alpine.AlpineChecksums;
import com.github.klboke.kkrepo.protocol.alpine.AlpinePackageInfo;
import com.github.klboke.kkrepo.protocol.alpine.AlpineSignature;
import com.github.klboke.kkrepo.security.scan.ScannerContract.ResourceLimits;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.zip.CRC32;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;
import org.springframework.stereotype.Component;

/** Safely reconstructs an APK v2 payload and the identity sidecars understood by Syft. */
@Component
final class AlpinePackageCataloger {
  private static final int MAX_MEMBERS = 3;
  private static final int MAX_PKGINFO_BYTES = 1024 * 1024;
  private static final int MAX_GZIP_HEADER_BYTES = 64 * 1024;

  Prepared prepare(
      Path artifact,
      ResourceLimits limits,
      Path workspace,
      ScanDeadline deadline) {
    deadline.check();
    try {
      List<Member> members = scanMembers(artifact, limits, deadline);
      if (members.size() != 2 && members.size() != 3) {
        throw invalid("APK v2 requires control/data and an optional signature member");
      }
      if (members.size() == 3) inspectSignature(artifact, members.getFirst(), limits, deadline);
      AlpinePackageInfo info = readControl(
          artifact, members.get(members.size() - 2), limits, deadline);
      Member data = members.getLast();
      String dataSha256 = digestRange(artifact, data, "SHA-256", deadline);
      if (!dataSha256.equalsIgnoreCase(info.dataSha256())) {
        throw invalid("APK datahash does not match the compressed data member");
      }
      String identity = "Q1" + java.util.Base64.getEncoder().encodeToString(
          digestRangeBytes(artifact, members.get(members.size() - 2), "SHA-1", deadline));
      AlpineChecksums.requireV2Identity(identity);

      Path root = workspace.resolve("alpine-root");
      Files.createDirectories(root);
      Extraction extracted = extractData(artifact, data, root, limits, deadline);
      writeIdentitySidecars(root, info, identity, dataSha256, Files.size(artifact), deadline);
      return new Prepared(
          root,
          info.name(),
          info.version(),
          info.architecture(),
          identity,
          dataSha256,
          extracted.entries() + controlEntryCount(artifact, members.get(members.size() - 2),
              limits, deadline),
          members.stream().mapToLong(Member::expandedSize).sum());
    } catch (ScannerRequestException failure) {
      throw failure;
    } catch (IOException | RuntimeException failure) {
      throw new ScannerRequestException(
          "ALPINE_PACKAGE_INVALID", "Unable to prepare APK v2 package", 422, false, failure);
    }
  }

  private static AlpinePackageInfo readControl(
      Path artifact, Member member, ResourceLimits limits, ScanDeadline deadline)
      throws IOException {
    byte[] pkgInfo = null;
    Set<String> paths = new HashSet<>();
    int entries = 0;
    try (InputStream expanded = expanded(artifact, member);
        TarArchiveInputStream tar = new TarArchiveInputStream(expanded)) {
      for (TarArchiveEntry entry; (entry = tar.getNextEntry()) != null;) {
        deadline.check();
        if (++entries > limits.maxArchiveEntries()) throw limit("APK control entry limit exceeded");
        ArchiveGuard.validatePath(entry.getName());
        if (!paths.add(entry.getName())) throw invalid("APK control contains duplicate paths");
        if (entry.isDirectory()) continue;
        requireRegular(entry, "control");
        if (".PKGINFO".equals(entry.getName())) {
          if (pkgInfo != null) throw invalid("APK control contains duplicate .PKGINFO");
          pkgInfo = readBounded(
              tar, Math.min(MAX_PKGINFO_BYTES, limits.maxSingleFileBytes()), deadline);
        } else {
          drainEntry(tar, entry.getSize(), limits.maxSingleFileBytes(), deadline, null);
        }
      }
    }
    if (pkgInfo == null) throw invalid("APK control does not contain .PKGINFO");
    try {
      String text = StandardCharsets.UTF_8.newDecoder()
          .onMalformedInput(CodingErrorAction.REPORT)
          .onUnmappableCharacter(CodingErrorAction.REPORT)
          .decode(ByteBuffer.wrap(pkgInfo)).toString();
      return AlpinePackageInfo.parse(text);
    } catch (CharacterCodingException | IllegalArgumentException failure) {
      throw invalid("APK .PKGINFO is invalid", failure);
    }
  }

  private static int controlEntryCount(
      Path artifact, Member member, ResourceLimits limits, ScanDeadline deadline)
      throws IOException {
    int entries = 0;
    try (InputStream expanded = expanded(artifact, member);
        TarArchiveInputStream tar = new TarArchiveInputStream(expanded)) {
      for (TarArchiveEntry entry; (entry = tar.getNextEntry()) != null;) {
        deadline.check();
        if (++entries > limits.maxArchiveEntries()) throw limit("APK control entry limit exceeded");
      }
    }
    return entries;
  }

  private static void inspectSignature(
      Path artifact, Member member, ResourceLimits limits, ScanDeadline deadline)
      throws IOException {
    int entries = 0;
    try (InputStream expanded = expanded(artifact, member);
        TarArchiveInputStream tar = new TarArchiveInputStream(expanded)) {
      for (TarArchiveEntry entry; (entry = tar.getNextEntry()) != null;) {
        deadline.check();
        if (++entries > Math.min(16, limits.maxArchiveEntries())) {
          throw limit("APK signature entry limit exceeded");
        }
        requireRegular(entry, "signature");
        try {
          AlpineSignature.parseEntryName(entry.getName());
        } catch (IllegalArgumentException failure) {
          throw invalid("APK signature entry is invalid", failure);
        }
        drainEntry(tar, entry.getSize(), Math.min(65_536, limits.maxSingleFileBytes()),
            deadline, null);
      }
    }
    if (entries == 0) throw invalid("APK signature member is empty");
  }

  private static Extraction extractData(
      Path artifact,
      Member member,
      Path root,
      ResourceLimits limits,
      ScanDeadline deadline) throws IOException {
    Set<String> paths = new HashSet<>();
    int entries = 0;
    long bytes = 0;
    try (InputStream expanded = expanded(artifact, member);
        TarArchiveInputStream tar = new TarArchiveInputStream(expanded)) {
      for (TarArchiveEntry entry; (entry = tar.getNextEntry()) != null;) {
        deadline.check();
        if (++entries > limits.maxArchiveEntries()) throw limit("APK data entry limit exceeded");
        ArchiveGuard.validatePath(entry.getName());
        String normalized = normalize(entry.getName());
        if (!paths.add(normalized)) throw invalid("APK data contains duplicate paths");
        if (entry.isCharacterDevice() || entry.isBlockDevice() || entry.isFIFO()
            || entry.isSparse()) {
          throw invalid("APK data contains an unsafe special file");
        }
        if (entry.isSymbolicLink() || entry.isLink()) {
          ArchiveGuard.validateLinkTarget(
              normalized, entry.getLinkName(), entry.isSymbolicLink());
          continue;
        }
        Path target = root.resolve(normalized).normalize();
        if (!target.startsWith(root)) throw invalid("APK data path escapes the scan root");
        if (entry.isDirectory()) {
          Files.createDirectories(target);
          continue;
        }
        if (!entry.isFile()) throw invalid("APK data contains an unsupported entry type");
        Files.createDirectories(target.getParent());
        long copied = drainEntry(
            tar, entry.getSize(), limits.maxSingleFileBytes(), deadline, target);
        bytes = Math.addExact(bytes, copied);
        if (bytes > limits.maxUncompressedBytes()) throw limit("APK payload expansion limit exceeded");
      }
    }
    if (entries == 0) throw invalid("APK data member is empty");
    return new Extraction(entries, bytes);
  }

  private static void writeIdentitySidecars(
      Path root,
      AlpinePackageInfo info,
      String identity,
      String dataSha256,
      long packageSize,
      ScanDeadline deadline) throws IOException {
    deadline.check();
    Path installed = root.resolve("lib/apk/db/installed");
    Files.createDirectories(installed.getParent());
    Files.write(installed, info.indexRecord(identity, packageSize).render()
        .getBytes(StandardCharsets.UTF_8));
    Path identityFile = root.resolve(".kkrepo/alpine-apk-v2.identity");
    Files.createDirectories(identityFile.getParent());
    Files.writeString(identityFile, """
        schema=alpine-apk-v2
        name=%s
        version=%s
        architecture=%s
        identity=%s
        dataSha256=%s
        """.formatted(
            info.name(), info.version(), info.architecture(), identity, dataSha256),
        StandardCharsets.UTF_8);
    deadline.check();
  }

  private static List<Member> scanMembers(
      Path artifact, ResourceLimits limits, ScanDeadline deadline) throws IOException {
    try (FileChannel channel = FileChannel.open(artifact, StandardOpenOption.READ)) {
      long fileSize = channel.size();
      long cursor = 0;
      long totalExpanded = 0;
      ArrayList<Member> result = new ArrayList<>();
      while (cursor < fileSize) {
        deadline.check();
        if (result.size() >= MAX_MEMBERS) throw invalid("APK has too many gzip members");
        Member member = scanMember(
            channel, cursor, fileSize, limits.maxUncompressedBytes() - totalExpanded, deadline);
        result.add(member);
        totalExpanded = Math.addExact(totalExpanded, member.expandedSize());
        if (totalExpanded > limits.maxUncompressedBytes()) {
          throw limit("APK expanded size exceeds the scanner limit");
        }
        cursor = member.end();
      }
      if (result.isEmpty() || cursor != fileSize) throw invalid("APK gzip boundaries are invalid");
      return List.copyOf(result);
    } catch (ArithmeticException overflow) {
      throw limit("APK expanded size overflow");
    }
  }

  private static Member scanMember(
      FileChannel channel,
      long start,
      long fileSize,
      long remainingLimit,
      ScanDeadline deadline) throws IOException {
    long dataStart = headerEnd(channel, start, fileSize);
    Inflater inflater = new Inflater(true);
    CRC32 crc = new CRC32();
    byte[] input = new byte[64 * 1024];
    byte[] output = new byte[64 * 1024];
    long readPosition = dataStart;
    long expanded = 0;
    try {
      while (!inflater.finished()) {
        deadline.check();
        if (inflater.needsInput()) {
          int read = positionalRead(channel, readPosition, input, fileSize);
          if (read <= 0) throw invalid("APK gzip member is truncated");
          inflater.setInput(input, 0, read);
          readPosition += read;
        }
        int written;
        try {
          written = inflater.inflate(output);
        } catch (DataFormatException failure) {
          throw invalid("APK gzip deflate stream is invalid", failure);
        }
        if (written > 0) {
          crc.update(output, 0, written);
          expanded = Math.addExact(expanded, written);
          if (expanded > remainingLimit) throw limit("APK expanded size exceeds the scanner limit");
        } else if (inflater.needsDictionary()
            || (!inflater.needsInput() && !inflater.finished())) {
          throw invalid("APK gzip decompression made no progress");
        }
      }
      long deflateEnd = readPosition - inflater.getRemaining();
      long end = Math.addExact(deflateEnd, 8);
      if (end > fileSize) throw invalid("APK gzip trailer is truncated");
      byte[] trailer = readExact(channel, deflateEnd, 8);
      if (littleEndian32(trailer, 0) != crc.getValue()
          || littleEndian32(trailer, 4) != (expanded & 0xffff_ffffL)) {
        throw invalid("APK gzip CRC or size is invalid");
      }
      return new Member(start, end, expanded);
    } catch (ArithmeticException overflow) {
      throw limit("APK gzip size overflow");
    } finally {
      inflater.end();
    }
  }

  private static long headerEnd(FileChannel channel, long start, long size) throws IOException {
    if (start + 10 > size) throw invalid("APK gzip header is truncated");
    byte[] fixed = readExact(channel, start, 10);
    if ((fixed[0] & 0xff) != 0x1f || (fixed[1] & 0xff) != 0x8b
        || (fixed[2] & 0xff) != 8 || ((fixed[3] & 0xff) & 0xe0) != 0) {
      throw invalid("APK v2 requires valid gzip members");
    }
    int flags = fixed[3] & 0xff;
    long cursor = start + 10;
    if ((flags & 0x04) != 0) {
      byte[] length = readExact(channel, cursor, 2);
      cursor += 2 + ((length[0] & 0xff) | ((length[1] & 0xff) << 8));
      requireHeaderBounds(start, cursor, size);
    }
    if ((flags & 0x08) != 0) cursor = skipZero(channel, start, cursor, size);
    if ((flags & 0x10) != 0) cursor = skipZero(channel, start, cursor, size);
    if ((flags & 0x02) != 0) {
      cursor += 2;
      requireHeaderBounds(start, cursor, size);
    }
    return cursor;
  }

  private static long skipZero(
      FileChannel channel, long start, long cursor, long size) throws IOException {
    while (cursor < size && cursor - start <= MAX_GZIP_HEADER_BYTES) {
      if (readExact(channel, cursor++, 1)[0] == 0) return cursor;
    }
    throw invalid("APK gzip optional header exceeds the limit");
  }

  private static void requireHeaderBounds(long start, long cursor, long size) {
    if (cursor > size || cursor - start > MAX_GZIP_HEADER_BYTES) {
      throw invalid("APK gzip header exceeds the limit");
    }
  }

  private static InputStream expanded(Path artifact, Member member) throws IOException {
    return new GzipCompressorInputStream(
        new RangeInputStream(artifact, member.start(), member.end()), false);
  }

  private static void requireRegular(TarArchiveEntry entry, String section) {
    ArchiveGuard.validatePath(entry.getName());
    if (!entry.isFile() || entry.isSymbolicLink() || entry.isLink()
        || entry.isCharacterDevice() || entry.isBlockDevice() || entry.isFIFO()
        || entry.isSparse()) {
      throw invalid("APK " + section + " contains an unsafe entry");
    }
  }

  private static byte[] readBounded(
      InputStream input, long maximum, ScanDeadline deadline) throws IOException {
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    byte[] buffer = new byte[16 * 1024];
    long total = 0;
    for (int read; (read = input.read(buffer)) >= 0;) {
      deadline.check();
      if (read == 0) continue;
      total += read;
      if (total > maximum) throw limit("APK metadata exceeds the scanner limit");
      output.write(buffer, 0, read);
    }
    return output.toByteArray();
  }

  private static long drainEntry(
      InputStream input,
      long declared,
      long maximum,
      ScanDeadline deadline,
      Path output) throws IOException {
    if (declared < 0 || declared > maximum) throw limit("APK entry exceeds the scanner limit");
    long total = 0;
    byte[] buffer = new byte[32 * 1024];
    try (var target = output == null ? null : Files.newOutputStream(output)) {
      while (total < declared) {
        deadline.check();
        int read = input.read(buffer, 0, (int) Math.min(buffer.length, declared - total));
        if (read < 0) throw invalid("APK tar entry is truncated");
        if (read == 0) continue;
        total += read;
        if (target != null) target.write(buffer, 0, read);
      }
    }
    return total;
  }

  private static String normalize(String value) {
    String result = value;
    while (result.startsWith("./")) result = result.substring(2);
    while (result.endsWith("/") && result.length() > 1) {
      result = result.substring(0, result.length() - 1);
    }
    if (result.isBlank()) throw invalid("APK path is empty");
    return result;
  }

  private static String digestRange(
      Path artifact, Member member, String algorithm, ScanDeadline deadline) throws IOException {
    return HexFormat.of().formatHex(digestRangeBytes(artifact, member, algorithm, deadline));
  }

  private static byte[] digestRangeBytes(
      Path artifact, Member member, String algorithm, ScanDeadline deadline) throws IOException {
    MessageDigest digest;
    try {
      digest = MessageDigest.getInstance(algorithm);
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException(impossible);
    }
    try (FileChannel channel = FileChannel.open(artifact, StandardOpenOption.READ)) {
      long position = member.start();
      ByteBuffer buffer = ByteBuffer.allocate(64 * 1024);
      while (position < member.end()) {
        deadline.check();
        buffer.clear().limit((int) Math.min(buffer.capacity(), member.end() - position));
        int read = channel.read(buffer, position);
        if (read < 0) throw invalid("APK member is truncated");
        if (read == 0) continue;
        digest.update(buffer.array(), 0, read);
        position += read;
      }
    }
    return digest.digest();
  }

  private static int positionalRead(
      FileChannel channel, long position, byte[] target, long fileSize) throws IOException {
    if (position >= fileSize) return -1;
    ByteBuffer buffer = ByteBuffer.wrap(target, 0, (int) Math.min(target.length, fileSize - position));
    int total = 0;
    while (buffer.hasRemaining()) {
      int read = channel.read(buffer, position + total);
      if (read < 0) break;
      if (read == 0) continue;
      total += read;
    }
    return total == 0 ? -1 : total;
  }

  private static byte[] readExact(FileChannel channel, long position, int length)
      throws IOException {
    ByteBuffer buffer = ByteBuffer.allocate(length);
    int total = 0;
    while (buffer.hasRemaining()) {
      int read = channel.read(buffer, position + total);
      if (read < 0) throw invalid("APK structure is truncated");
      if (read == 0) continue;
      total += read;
    }
    return buffer.array();
  }

  private static long littleEndian32(byte[] bytes, int offset) {
    return (bytes[offset] & 0xffL)
        | ((bytes[offset + 1] & 0xffL) << 8)
        | ((bytes[offset + 2] & 0xffL) << 16)
        | ((bytes[offset + 3] & 0xffL) << 24);
  }

  private static ScannerRequestException invalid(String message) {
    return new ScannerRequestException("ALPINE_PACKAGE_INVALID", message, 422, false);
  }

  private static ScannerRequestException invalid(String message, Throwable cause) {
    return new ScannerRequestException(
        "ALPINE_PACKAGE_INVALID", message, 422, false, cause);
  }

  private static ScannerRequestException limit(String message) {
    return new ScannerRequestException("ALPINE_ARCHIVE_LIMIT", message, 422, false);
  }

  record Prepared(
      Path scanRoot,
      String name,
      String version,
      String architecture,
      String identity,
      String dataSha256,
      int entries,
      long expandedBytes) {
  }

  private record Member(long start, long end, long expandedSize) {
  }

  private record Extraction(int entries, long bytes) {
  }

  private static final class RangeInputStream extends InputStream {
    private final FileChannel channel;
    private final long end;
    private long position;

    private RangeInputStream(Path file, long start, long end) throws IOException {
      this.channel = FileChannel.open(file, StandardOpenOption.READ);
      this.position = start;
      this.end = end;
    }

    @Override
    public int read() throws IOException {
      byte[] one = new byte[1];
      int read = read(one, 0, 1);
      return read < 0 ? -1 : one[0] & 0xff;
    }

    @Override
    public int read(byte[] bytes, int offset, int length) throws IOException {
      if (position >= end) return -1;
      ByteBuffer buffer = ByteBuffer.wrap(bytes, offset, (int) Math.min(length, end - position));
      int read = channel.read(buffer, position);
      if (read > 0) position += read;
      return read;
    }

    @Override
    public void close() throws IOException {
      channel.close();
    }
  }
}
