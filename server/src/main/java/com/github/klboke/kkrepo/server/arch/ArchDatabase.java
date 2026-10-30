package com.github.klboke.kkrepo.server.arch;

import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.ComponentRecord;
import com.github.klboke.kkrepo.protocol.arch.ArchDesc;
import com.github.klboke.kkrepo.protocol.arch.ArchVersions;
import com.github.klboke.kkrepo.server.maven.MavenExceptions;
import com.github.luben.zstd.ZstdInputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream;
import org.apache.commons.compress.compressors.gzip.GzipParameters;
import org.tukaani.xz.XZInputStream;
import org.springframework.stereotype.Component;

/**
 * Deterministic pacman database serialization.
 *
 * <p>The generated archive is a pure function of the durable package projection: entries are
 * ordered by package name, version and architecture, mtimes are epoch, and ownership is fixed.
 * Replicas therefore produce identical bytes and identical ETags for the same package set.
 */
@Component
final class ArchDatabase {
  private static final int MAX_RECORDS = 200_000;
  private static final int MAX_DESC_BYTES = 4 * 1024 * 1024;

  byte[] build(List<ComponentRecord> components, boolean files) {
    List<ArchDesc> descs = components.stream()
        .filter(component -> component.format() == RepositoryFormat.ARCH)
        .map(ArchDatabase::desc)
        .filter(java.util.Objects::nonNull)
        .toList();
    return buildDescs(descs, files);
  }

  byte[] buildDescs(List<ArchDesc> descs, boolean files) {
    List<ArchDesc> ordered = descs.stream()
        .filter(java.util.Objects::nonNull)
        .sorted(Comparator
            .comparing(ArchDesc::name)
            .thenComparing(ArchDesc::version, ArchVersions.COMPARATOR.reversed())
            .thenComparing(desc -> desc.architecture() == null ? "" : desc.architecture()))
        .toList();
    try {
      ByteArrayOutputStream bytes = new ByteArrayOutputStream();
      GzipParameters gzip = new GzipParameters();
      gzip.setModificationTime(0);
      gzip.setCompressionLevel(9);
      try (GzipCompressorOutputStream compressed = new GzipCompressorOutputStream(bytes, gzip);
           TarArchiveOutputStream tar = new TarArchiveOutputStream(compressed)) {
        tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX);
        tar.setBigNumberMode(TarArchiveOutputStream.BIGNUMBER_POSIX);
        for (ArchDesc desc : ordered) {
          String entryName = desc.directory() + (files ? "/files" : "/desc");
          byte[] content = (files ? files(desc) : desc.render()).getBytes(StandardCharsets.UTF_8);
          TarArchiveEntry entry = new TarArchiveEntry(entryName);
          entry.setSize(content.length);
          entry.setModTime(0);
          entry.setUserId(0);
          entry.setGroupId(0);
          entry.setUserName("");
          entry.setGroupName("");
          entry.setMode(0644);
          tar.putArchiveEntry(entry);
          tar.write(content);
          tar.closeArchiveEntry();
        }
        tar.finish();
      }
      return bytes.toByteArray();
    } catch (IOException error) {
      throw new IllegalStateException("Unable to build pacman database", error);
    }
  }

  private static String files(ArchDesc desc) {
    StringBuilder out = new StringBuilder("%FILES%\n");
    // File lists are intentionally not persisted as unbounded component JSON. The database
    // remains valid for pacman; pacman -F/extra file tooling sees an empty list.
    out.append('\n');
    return out.toString();
  }

  static ArchDesc desc(ComponentRecord component) {
    Object encoded = component.attributes() == null ? null : component.attributes().get("archDesc");
    if (encoded != null) {
      try {
        return ArchDesc.parse(encoded.toString());
      } catch (RuntimeException ignored) {
        // Fall through to the bounded projection below.
      }
    }
    if (component.name() == null || component.version() == null) return null;
    return new ArchDesc(
        string(component.attributes(), "archFileName"), component.name(),
        string(component.attributes(), "archBase"),
        string(component.attributes(), "archVersion"),
        string(component.attributes(), "archDescription"),
        longValue(component.attributes(), "archCsize"),
        longValue(component.attributes(), "archIsize"),
        string(component.attributes(), "archMd5"),
        string(component.attributes(), "archSha256"),
        null, string(component.attributes(), "archUrl"), List.of(),
        string(component.attributes(), "archArchitecture"),
        longValue(component.attributes(), "archBuildDate"),
        string(component.attributes(), "archPackager"), List.of(), List.of(), List.of(),
        List.of(), List.of(), List.of(), List.of(), List.of());
  }

  List<ArchDesc> parse(byte[] database, String fileName) {
    try (InputStream compressed = decompress(new ByteArrayInputStream(database), fileName);
         TarArchiveInputStream tar = new TarArchiveInputStream(compressed)) {
      List<ArchDesc> descs = new ArrayList<>();
      TarArchiveEntry entry;
      while ((entry = tar.getNextEntry()) != null) {
        if (descs.size() >= MAX_RECORDS) {
          throw new MavenExceptions.BadUpstreamException("Pacman database has too many records");
        }
        String name = entry.getName();
        if (!name.endsWith("/desc")) continue;
        byte[] content = tar.readNBytes(MAX_DESC_BYTES + 1);
        if (content.length > MAX_DESC_BYTES) {
          throw new MavenExceptions.BadUpstreamException("Pacman desc record is too large");
        }
        try {
          descs.add(ArchDesc.parse(new String(content, StandardCharsets.UTF_8)));
        } catch (RuntimeException ignored) {
          // A malformed upstream record is skipped; the rest of the database remains usable.
        }
      }
      return descs;
    } catch (IOException error) {
      throw new MavenExceptions.BadUpstreamException("Invalid pacman database", error);
    }
  }

  private static InputStream decompress(InputStream input, String fileName) throws IOException {
    java.io.PushbackInputStream probe = new java.io.PushbackInputStream(input, 6);
    byte[] header = probe.readNBytes(6);
    probe.unread(header);
    if (startsWith(header, new int[] {0x1f, 0x8b})) {
      return new GzipCompressorInputStream(probe);
    }
    if (startsWith(header, new int[] {0x28, 0xb5, 0x2f, 0xfd})) {
      return new ZstdInputStream(probe);
    }
    if (startsWith(header, new int[] {0xfd, 0x37, 0x7a, 0x58, 0x5a, 0x00})) {
      return new XZInputStream(probe);
    }
    if (startsWith(header, new int[] {0x42, 0x5a, 0x68})) {
      return new BZip2CompressorInputStream(probe);
    }
    String name = fileName == null ? "" : fileName;
    if (name.endsWith(".xz")) return new XZInputStream(probe);
    if (name.endsWith(".zst")) return new ZstdInputStream(probe);
    if (name.endsWith(".bz2")) return new BZip2CompressorInputStream(probe);
    if (name.endsWith(".db") || name.endsWith(".gz")) {
      return new GzipCompressorInputStream(probe);
    }
    // Unknown/raw tar: let the tar reader decide.
    return probe;
  }

  private static boolean startsWith(byte[] value, int[] signature) {
    if (value.length < signature.length) return false;
    for (int index = 0; index < signature.length; index++) {
      if ((value[index] & 0xff) != signature[index]) return false;
    }
    return true;
  }

  private static String string(Map<String, Object> attributes, String key) {
    Object value = attributes == null ? null : attributes.get(key);
    return value == null ? null : value.toString();
  }

  private static Long longValue(Map<String, Object> attributes, String key) {
    Object value = attributes == null ? null : attributes.get(key);
    if (value instanceof Number number) return number.longValue();
    if (value == null) return null;
    try {
      return Long.parseLong(value.toString());
    } catch (NumberFormatException ignored) {
      return null;
    }
  }

}
