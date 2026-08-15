package com.github.klboke.kkrepo.server.alpine;

import com.github.klboke.kkrepo.server.maven.MavenExceptions;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.CRC32;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;

/** Locates and validates every raw gzip member without losing concatenation boundaries. */
final class AlpineGzipMembers {
  private static final int INPUT_BUFFER = 64 * 1024;
  private static final int OUTPUT_BUFFER = 64 * 1024;
  private static final int MAX_HEADER_BYTES = 64 * 1024;

  private AlpineGzipMembers() {
  }

  static List<Member> scan(
      Path file,
      int maxMembers,
      long maxExpandedMemberBytes,
      long maxExpandedTotalBytes,
      Duration timeout) throws IOException {
    long deadline = System.nanoTime() + timeout.toNanos();
    try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ)) {
      long size = channel.size();
      long cursor = 0;
      long totalExpanded = 0;
      ArrayList<Member> members = new ArrayList<>();
      while (cursor < size) {
        if (members.size() >= maxMembers) throw bad("APK contains too many gzip members");
        Member member = scanMember(
            channel, cursor, size, maxExpandedMemberBytes,
            maxExpandedTotalBytes - totalExpanded, deadline);
        members.add(member);
        totalExpanded += member.expandedSize();
        cursor = member.end();
      }
      if (cursor != size || members.isEmpty()) throw bad("APK gzip member boundaries are invalid");
      return List.copyOf(members);
    }
  }

  static InputStream openExpanded(Path file, Member member) throws IOException {
    return new GzipCompressorInputStream(
        new FileRangeInputStream(file, member.start(), member.end()), false);
  }

  private static Member scanMember(
      FileChannel channel,
      long start,
      long fileSize,
      long memberLimit,
      long remainingTotalLimit,
      long deadline) throws IOException {
    long dataStart = headerEnd(channel, start, fileSize);
    Inflater inflater = new Inflater(true);
    CRC32 crc = new CRC32();
    byte[] input = new byte[INPUT_BUFFER];
    byte[] output = new byte[OUTPUT_BUFFER];
    long readPosition = dataStart;
    long expanded = 0;
    try {
      while (!inflater.finished()) {
        checkDeadline(deadline);
        if (inflater.needsInput()) {
          int read = positionalRead(channel, readPosition, input, fileSize);
          if (read <= 0) throw bad("Truncated APK gzip member");
          inflater.setInput(input, 0, read);
          readPosition += read;
        }
        int written;
        try {
          written = inflater.inflate(output);
        } catch (DataFormatException error) {
          throw bad("Invalid APK gzip deflate stream", error);
        }
        if (written > 0) {
          crc.update(output, 0, written);
          expanded += written;
          if (expanded > memberLimit || expanded > remainingTotalLimit) {
            throw bad("APK expanded content exceeds configured limits");
          }
          continue;
        }
        if (inflater.needsDictionary()) throw bad("APK gzip member requires a dictionary");
        if (!inflater.needsInput() && !inflater.finished()) {
          throw bad("APK gzip member made no decompression progress");
        }
      }
      long deflateEnd = readPosition - inflater.getRemaining();
      long end = Math.addExact(deflateEnd, 8);
      if (end > fileSize) throw bad("Truncated APK gzip trailer");
      byte[] trailer = readExact(channel, deflateEnd, 8);
      long expectedCrc = littleEndian32(trailer, 0);
      long expectedSize = littleEndian32(trailer, 4);
      if (expectedCrc != crc.getValue() || expectedSize != (expanded & 0xffff_ffffL)) {
        throw bad("APK gzip CRC or size trailer is invalid");
      }
      return new Member(start, end, dataStart, deflateEnd, expanded);
    } catch (ArithmeticException error) {
      throw bad("APK gzip member length overflow", error);
    } finally {
      inflater.end();
    }
  }

  private static long headerEnd(FileChannel channel, long start, long size) throws IOException {
    if (start + 10 > size) throw bad("Truncated APK gzip header");
    byte[] fixed = readExact(channel, start, 10);
    if ((fixed[0] & 0xff) != 0x1f || (fixed[1] & 0xff) != 0x8b || (fixed[2] & 0xff) != 8) {
      throw bad("APK v2 requires gzip members");
    }
    int flags = fixed[3] & 0xff;
    if ((flags & 0xe0) != 0) throw bad("APK gzip header uses reserved flags");
    long cursor = start + 10;
    if ((flags & 0x04) != 0) {
      byte[] length = readExact(channel, cursor, 2);
      cursor += 2 + ((length[0] & 0xff) | ((length[1] & 0xff) << 8));
      requireHeaderBounds(start, cursor, size);
    }
    if ((flags & 0x08) != 0) cursor = skipZeroTerminated(channel, start, cursor, size);
    if ((flags & 0x10) != 0) cursor = skipZeroTerminated(channel, start, cursor, size);
    if ((flags & 0x02) != 0) {
      cursor += 2;
      requireHeaderBounds(start, cursor, size);
    }
    return cursor;
  }

  private static long skipZeroTerminated(
      FileChannel channel, long start, long cursor, long size) throws IOException {
    while (cursor < size && cursor - start <= MAX_HEADER_BYTES) {
      if (readExact(channel, cursor++, 1)[0] == 0) return cursor;
    }
    throw bad("APK gzip optional header exceeds safety limits");
  }

  private static void requireHeaderBounds(long start, long cursor, long size) {
    if (cursor > size || cursor - start > MAX_HEADER_BYTES) {
      throw bad("APK gzip header exceeds safety limits");
    }
  }

  private static int positionalRead(
      FileChannel channel, long position, byte[] target, long fileSize) throws IOException {
    if (position >= fileSize) return -1;
    int length = (int) Math.min(target.length, fileSize - position);
    ByteBuffer buffer = ByteBuffer.wrap(target, 0, length);
    int total = 0;
    while (buffer.hasRemaining()) {
      int read = channel.read(buffer, position + total);
      if (read < 0) break;
      if (read == 0) continue;
      total += read;
    }
    return total == 0 ? -1 : total;
  }

  private static byte[] readExact(FileChannel channel, long position, int length) throws IOException {
    ByteBuffer buffer = ByteBuffer.allocate(length);
    int total = 0;
    while (buffer.hasRemaining()) {
      int read = channel.read(buffer, position + total);
      if (read < 0) throw bad("Truncated APK gzip structure");
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

  private static void checkDeadline(long deadline) {
    if (System.nanoTime() > deadline) throw bad("APK gzip inspection timed out");
  }

  private static MavenExceptions.BadRequestException bad(String message) {
    return new MavenExceptions.BadRequestException(message);
  }

  private static MavenExceptions.BadRequestException bad(String message, Throwable cause) {
    return new MavenExceptions.BadRequestException(message, cause);
  }

  record Member(long start, long end, long deflateStart, long deflateEnd, long expandedSize) {
    long compressedSize() {
      return end - start;
    }
  }

  private static final class FileRangeInputStream extends InputStream {
    private final FileChannel channel;
    private final long end;
    private long position;

    private FileRangeInputStream(Path file, long start, long end) throws IOException {
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
      int bounded = (int) Math.min(length, end - position);
      ByteBuffer buffer = ByteBuffer.wrap(bytes, offset, bounded);
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
