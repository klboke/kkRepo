package com.github.klboke.kkrepo.server.apt;

import com.github.luben.zstd.ZstdOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.apache.commons.compress.archivers.ar.ArArchiveEntry;
import org.apache.commons.compress.archivers.ar.ArArchiveOutputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream;
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream;
import org.apache.commons.compress.compressors.xz.XZCompressorOutputStream;

final class AptTestPackage {
  private AptTestPackage() {
  }

  static byte[] deb(String compression, String control) throws IOException {
    return deb(compression, List.of(new Item("./control", control.getBytes(StandardCharsets.UTF_8), false)));
  }

  static byte[] deb(String compression, List<Item> controlEntries) throws IOException {
    byte[] control = tar(compression, controlEntries);
    byte[] data = tar("gz", List.of(new Item("./usr/share/demo/data", new byte[] {1, 2, 3}, false)));
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (ArArchiveOutputStream ar = new ArArchiveOutputStream(bytes)) {
      put(ar, "debian-binary", "2.0\n".getBytes(StandardCharsets.US_ASCII));
      put(ar, "control.tar" + suffix(compression), control);
      put(ar, "data.tar.gz", data);
    }
    return bytes.toByteArray();
  }

  static byte[] withMembers(List<Item> members) throws IOException {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (ArArchiveOutputStream ar = new ArArchiveOutputStream(bytes)) {
      for (Item member : members) put(ar, member.name(), member.bytes());
    }
    return bytes.toByteArray();
  }

  static String control(String name, String version, String architecture) {
    return """
        Package: %s
        Version: %s
        Architecture: %s
        Maintainer: kkRepo Test <test@example.invalid>
        Description: test package
         with a continued description
        Section: utils
        Priority: optional
        """.formatted(name, version, architecture);
  }

  static byte[] tar(String compression, List<Item> items) throws IOException {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (OutputStream compressed = compressor(compression, bytes);
         TarArchiveOutputStream tar = new TarArchiveOutputStream(compressed)) {
      tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_ERROR);
      for (Item item : items) {
        TarArchiveEntry entry = item.symlink()
            ? new TarArchiveEntry(
                item.name(), org.apache.commons.compress.archivers.tar.TarConstants.LF_SYMLINK)
            : new TarArchiveEntry(item.name());
        if (item.symlink()) {
          entry.setLinkName("../../outside");
          entry.setSize(0);
        } else {
          entry.setSize(item.bytes().length);
        }
        tar.putArchiveEntry(entry);
        if (!item.symlink()) tar.write(item.bytes());
        tar.closeArchiveEntry();
      }
    }
    return bytes.toByteArray();
  }

  private static OutputStream compressor(String compression, OutputStream output) throws IOException {
    return switch (compression) {
      case "none" -> output;
      case "gz" -> new GzipCompressorOutputStream(output);
      case "bz2" -> new BZip2CompressorOutputStream(output);
      case "xz" -> new XZCompressorOutputStream(output);
      case "zst" -> new ZstdOutputStream(output);
      default -> throw new IllegalArgumentException("unknown compression: " + compression);
    };
  }

  private static String suffix(String compression) {
    return "none".equals(compression) ? "" : "." + compression;
  }

  private static void put(ArArchiveOutputStream ar, String name, byte[] bytes) throws IOException {
    ar.putArchiveEntry(new ArArchiveEntry(name, bytes.length));
    ar.write(bytes);
    ar.closeArchiveEntry();
  }

  record Item(String name, byte[] bytes, boolean symlink) {
    Item(String name, byte[] bytes) {
      this(name, bytes, false);
    }
  }
}
