package com.github.klboke.kkrepo.scanner;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.zip.GZIPOutputStream;
import org.apache.commons.compress.archivers.ar.ArArchiveEntry;
import org.apache.commons.compress.archivers.ar.ArArchiveOutputStream;
import org.apache.commons.compress.archivers.cpio.CpioArchiveEntry;
import org.apache.commons.compress.archivers.cpio.CpioArchiveOutputStream;
import org.apache.commons.compress.archivers.cpio.CpioConstants;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.compress.archivers.tar.TarConstants;

/** Small deterministic fixtures following the Galaxy, Debian ar/tar and RPM/CPIO layouts. */
final class ArchiveLinkFixtures {
  private ArchiveLinkFixtures() {}

  static byte[] galaxy() throws IOException {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (GZIPOutputStream gzip = new GZIPOutputStream(bytes);
        TarArchiveOutputStream tar = new TarArchiveOutputStream(gzip)) {
      file(tar, "COPYING", "license");
      link(tar, "LICENSES/GPL-3.0-or-later.txt", "../COPYING", TarConstants.LF_SYMLINK);
      file(tar, "plugins/action/k8s_info.py", "# module");
      link(tar, "plugins/action/helm.py", "k8s_info.py", TarConstants.LF_SYMLINK);
    }
    return bytes.toByteArray();
  }

  static byte[] deb() throws IOException {
    ByteArrayOutputStream control = new ByteArrayOutputStream();
    try (GZIPOutputStream gzip = new GZIPOutputStream(control);
        TarArchiveOutputStream tar = new TarArchiveOutputStream(gzip)) {
      file(tar, "./control", "Package: demo\nVersion: 1.0\nArchitecture: all\n");
    }
    ByteArrayOutputStream data = new ByteArrayOutputStream();
    try (GZIPOutputStream gzip = new GZIPOutputStream(data);
        TarArchiveOutputStream tar = new TarArchiveOutputStream(gzip)) {
      file(tar, "./usr/bin/dash", "executable");
      link(tar, "./usr/bin/sh", "dash", TarConstants.LF_SYMLINK);
      link(tar, "./usr/bin/dash-hard", "./usr/bin/dash", TarConstants.LF_LINK);
    }
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (ArArchiveOutputStream ar = new ArArchiveOutputStream(bytes)) {
      arEntry(ar, "debian-binary", "2.0\n".getBytes(StandardCharsets.UTF_8));
      arEntry(ar, "control.tar.gz", control.toByteArray());
      arEntry(ar, "data.tar.gz", data.toByteArray());
    }
    return bytes.toByteArray();
  }

  static byte[] rpm() throws IOException {
    return rpm("./usr/lib/.build-id/82/id", "../../../../usr/bin/gzip"
        .getBytes(StandardCharsets.UTF_8), CpioConstants.C_ISLNK);
  }

  static byte[] rpm(String name, byte[] content, long mode) throws IOException {
    ByteArrayOutputStream payload = new ByteArrayOutputStream();
    try (GZIPOutputStream gzip = new GZIPOutputStream(payload);
        CpioArchiveOutputStream cpio = new CpioArchiveOutputStream(gzip)) {
      CpioArchiveEntry entry = new CpioArchiveEntry(name, content.length);
      entry.setMode(mode | 0644);
      cpio.putArchiveEntry(entry);
      cpio.write(content);
      cpio.closeArchiveEntry();
    }
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    byte[] lead = new byte[96];
    ByteBuffer.wrap(lead).putInt(0xedabeedb);
    bytes.write(lead);
    byte[] header = ByteBuffer.allocate(16).order(ByteOrder.BIG_ENDIAN)
        .putInt(0x8eade801).putInt(0).putInt(0).putInt(0).array();
    bytes.write(header);
    bytes.write(header);
    bytes.write(payload.toByteArray());
    return bytes.toByteArray();
  }

  static void file(TarArchiveOutputStream tar, String name, String value) throws IOException {
    byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
    TarArchiveEntry entry = new TarArchiveEntry(name);
    entry.setSize(bytes.length);
    tar.putArchiveEntry(entry);
    tar.write(bytes);
    tar.closeArchiveEntry();
  }

  static void link(TarArchiveOutputStream tar, String name, String target, byte type)
      throws IOException {
    TarArchiveEntry entry = new TarArchiveEntry(name, type);
    entry.setLinkName(target);
    tar.putArchiveEntry(entry);
    tar.closeArchiveEntry();
  }

  private static void arEntry(ArArchiveOutputStream ar, String name, byte[] bytes)
      throws IOException {
    ar.putArchiveEntry(new ArArchiveEntry(name, bytes.length));
    ar.write(bytes);
    ar.closeArchiveEntry();
  }
}
