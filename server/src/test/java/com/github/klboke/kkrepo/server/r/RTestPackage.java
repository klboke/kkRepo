package com.github.klboke.kkrepo.server.r;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream;

final class RTestPackage {
  private RTestPackage() {
  }

  static byte[] source(String name, String version) throws IOException {
    return source(name, version, List.of());
  }

  static byte[] source(String name, String version, List<Item> extra) throws IOException {
    ArrayList<Item> entries = new ArrayList<>();
    entries.add(Item.directory(name + "/"));
    entries.add(Item.file(name + "/DESCRIPTION", description(name, version)));
    entries.add(Item.file(name + "/R/example.R", "example <- function() 1\n"));
    entries.addAll(extra);
    return archive(entries);
  }

  static byte[] archive(List<Item> entries) throws IOException {
    ByteArrayOutputStream compressed = new ByteArrayOutputStream();
    try (GzipCompressorOutputStream gzip = new GzipCompressorOutputStream(compressed);
        TarArchiveOutputStream tar = new TarArchiveOutputStream(gzip)) {
      tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_ERROR);
      for (Item item : entries) {
        TarArchiveEntry entry = item.link() == null
            ? new TarArchiveEntry(item.path())
            : new TarArchiveEntry(
                item.path(), org.apache.commons.compress.archivers.tar.TarConstants.LF_SYMLINK);
        if (item.link() != null) {
          entry.setLinkName(item.link());
          entry.setSize(0);
        } else if (item.directory()) {
          entry.setSize(0);
          entry.setMode(0755);
        } else {
          entry.setSize(item.bytes().length);
          entry.setMode(0644);
        }
        entry.setModTime(0);
        tar.putArchiveEntry(entry);
        if (!item.directory() && item.link() == null) tar.write(item.bytes());
        tar.closeArchiveEntry();
      }
      tar.finish();
    }
    return compressed.toByteArray();
  }

  private static byte[] description(String name, String version) {
    return ("Package: " + name + "\n"
        + "Version: " + version + "\n"
        + "Title: Fixture package\n"
        + "Description: A bounded R package fixture.\n"
        + "License: MIT\n"
        + "Author: kkRepo\n"
        + "Maintainer: kkRepo <noreply@example.invalid>\n"
        + "Imports: methods, utils (>= 4.0.0)\n"
        + "NeedsCompilation: no\n").getBytes(StandardCharsets.UTF_8);
  }

  record Item(String path, byte[] bytes, boolean directory, String link) {
    static Item file(String path, String text) {
      return file(path, text.getBytes(StandardCharsets.UTF_8));
    }

    static Item file(String path, byte[] bytes) {
      return new Item(path, bytes.clone(), false, null);
    }

    static Item directory(String path) {
      return new Item(path, new byte[0], true, null);
    }

    static Item link(String path, String target) {
      return new Item(path, new byte[0], false, target);
    }
  }
}
