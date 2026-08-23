package com.github.klboke.kkrepo.compat;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Date;
import java.util.HexFormat;
import java.util.List;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream;
import org.apache.commons.compress.compressors.gzip.GzipParameters;

/** Deterministic, installable source-package fixtures for live R compatibility checks. */
final class RCompatibilityFixtures {
  private RCompatibilityFixtures() {
  }

  static SourcePackage sourcePackage(String name, String version, String marker) throws Exception {
    String description = "Package: " + name + "\n"
        + "Version: " + version + "\n"
        + "Title: kkRepo R compatibility fixture\n"
        + "Description: A deterministic source package used for repository protocol checks.\n"
        + "License: MIT\n"
        + "Author: kkRepo Compatibility\n"
        + "Maintainer: kkRepo Compatibility <compat@kkrepo.invalid>\n"
        + "Imports: methods\n"
        + "NeedsCompilation: no\n";
    String function = "kkrepo_marker <- function() \""
        + marker.replace("\\", "\\\\").replace("\"", "\\\"") + "\"\n";
    byte[] bytes = tarGzip(List.of(
        Entry.directory(name + "/"),
        Entry.file(name + "/DESCRIPTION", description),
        Entry.file(name + "/NAMESPACE", "export(kkrepo_marker)\n"),
        Entry.directory(name + "/R/"),
        Entry.file(name + "/R/marker.R", function)));
    return new SourcePackage(
        name + "_" + version + ".tar.gz", name, version, bytes,
        digest("MD5", bytes), digest("SHA-256", bytes));
  }

  private static byte[] tarGzip(List<Entry> entries) throws Exception {
    ByteArrayOutputStream compressed = new ByteArrayOutputStream();
    GzipParameters parameters = new GzipParameters();
    parameters.setModificationInstant(Instant.EPOCH);
    parameters.setFilename(null);
    try (GzipCompressorOutputStream gzip =
            new GzipCompressorOutputStream(compressed, parameters);
        TarArchiveOutputStream tar = new TarArchiveOutputStream(gzip)) {
      tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_ERROR);
      tar.setBigNumberMode(TarArchiveOutputStream.BIGNUMBER_ERROR);
      for (Entry value : entries) {
        TarArchiveEntry entry = new TarArchiveEntry(value.name());
        entry.setMode(value.directory() ? 0755 : 0644);
        entry.setUserId(0);
        entry.setGroupId(0);
        entry.setUserName("root");
        entry.setGroupName("root");
        entry.setModTime(Date.from(Instant.EPOCH));
        entry.setSize(value.directory() ? 0 : value.bytes().length);
        tar.putArchiveEntry(entry);
        if (!value.directory()) tar.write(value.bytes());
        tar.closeArchiveEntry();
      }
      tar.finish();
    }
    return compressed.toByteArray();
  }

  private static String digest(String algorithm, byte[] bytes) throws Exception {
    return HexFormat.of().formatHex(MessageDigest.getInstance(algorithm).digest(bytes));
  }

  private record Entry(String name, byte[] bytes, boolean directory) {
    private static Entry directory(String name) {
      return new Entry(name, new byte[0], true);
    }

    private static Entry file(String name, String value) {
      return new Entry(name, value.getBytes(StandardCharsets.UTF_8), false);
    }
  }

  record SourcePackage(
      String filename,
      String name,
      String version,
      byte[] bytes,
      String md5,
      String sha256) {
    @Override
    public byte[] bytes() {
      return bytes.clone();
    }
  }
}
