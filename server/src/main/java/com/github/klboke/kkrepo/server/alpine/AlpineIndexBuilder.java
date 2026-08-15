package com.github.klboke.kkrepo.server.alpine;

import com.github.klboke.kkrepo.persistence.jdbc.api.AlpineRegistryDao;
import com.github.klboke.kkrepo.protocol.alpine.AlpineIndexRecord;
import com.github.klboke.kkrepo.protocol.alpine.AlpineMediaTypes;
import com.github.klboke.kkrepo.protocol.alpine.AlpineSignature;
import com.github.klboke.kkrepo.protocol.alpine.AlpineVersions;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntime;
import java.io.BufferedOutputStream;
import java.io.BufferedWriter;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.zip.Deflater;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream;
import org.apache.commons.compress.compressors.gzip.GzipParameters;
import org.springframework.stereotype.Component;

/** Streams one deterministic signed APKINDEX generation into immutable blob assets. */
@Component
final class AlpineIndexBuilder {
  private static final List<Character> FIELD_ORDER = List.of(
      'C', 'P', 'V', 'A', 'S', 'I', 'T', 'U', 'L', 'o', 'm', 't', 'c', 'k', 'D', 'p', 'i');
  private static final Set<Character> KNOWN_FIELDS = Set.copyOf(FIELD_ORDER);
  private static final Comparator<AlpineRegistryDao.PackageRecord> PACKAGE_ORDER =
      Comparator.comparing(AlpineRegistryDao.PackageRecord::packageName)
          .thenComparing(AlpineRegistryDao.PackageRecord::version, AlpineVersions.COMPARATOR)
          .thenComparing(AlpineRegistryDao.PackageRecord::packageArchitecture)
          .thenComparing(AlpineRegistryDao.PackageRecord::path);

  private final AlpineRegistryDao registry;
  private final AlpineAssetSupport assets;
  private final AlpineSigningService signing;

  AlpineIndexBuilder(
      AlpineRegistryDao registry,
      AlpineAssetSupport assets,
      AlpineSigningService signing) {
    this.registry = registry;
    this.assets = assets;
    this.signing = signing;
  }

  BuiltSnapshot build(
      RepositoryRuntime runtime,
      AlpineRepositorySettings.Settings settings,
      AlpineRegistryDao.SuiteState state,
      AlpineSigningService.SigningMaterial key) {
    String[] namespace = namespace(state.distribution());
    return build(
        runtime,
        settings,
        state,
        key,
        visitor -> registry.visitPackages(
            runtime.id(), state.distribution(), namespace[1], namespace[2], visitor));
  }

  BuiltSnapshot build(
      RepositoryRuntime runtime,
      AlpineRepositorySettings.Settings settings,
      AlpineRegistryDao.SuiteState state,
      AlpineSigningService.SigningMaterial key,
      PackageSource source) {
    long revision = state.desiredRevision();
    Instant createdAt = state.desiredAt() == null ? Instant.now() : state.desiredAt();
    String canonical = state.distribution() + "/APKINDEX.tar.gz";
    String namespaceHash = HexFormat.of().formatHex(
        com.github.klboke.kkrepo.persistence.jdbc.api.PersistenceHashes.sha256(
            state.distribution()));
    String hidden = ".alpine/snapshots/" + namespaceHash + "/" + revision
        + "/APKINDEX.tar.gz";
    Path indexText = temporary("APKINDEX");
    Path unsigned = temporary("APKINDEX-unsigned.tar.gz");
    Path signatureMember = temporary("APKINDEX-signature.tar.gz");
    Path signed = temporary("APKINDEX-signed.tar.gz");
    try {
      writeIndex(indexText, source);
      writeUnsignedArchive(unsigned, indexText, settings.description(), createdAt);
      AlpineSignature signature = signing.sign(unsigned, key);
      writeSignatureArchive(signatureMember, signature, createdAt);
      concatenate(signatureMember, unsigned, signed);
      String sha256 = digest(signed, "SHA-256");
      long size = Files.size(signed);
      assets.storeGeneratedFile(
          runtime,
          hidden,
          signed,
          AlpineMediaTypes.APK_INDEX,
          Map.of(
              "alpineGenerated", true,
              "alpineRevision", revision,
              "alpineNamespace", state.distribution(),
              "alpineCanonicalPath", canonical,
              "alpineSigningKeyRevision", key.revision(),
              "sha256", sha256));
      return new BuiltSnapshot(
          Map.of(canonical, hidden), sha256, size, key.revision(), createdAt);
    } catch (IOException error) {
      throw new IllegalStateException("Failed to build Alpine APKINDEX snapshot", error);
    } finally {
      delete(indexText);
      delete(unsigned);
      delete(signatureMember);
      delete(signed);
    }
  }

  private static void writeIndex(Path output, PackageSource source) throws IOException {
    try (BufferedWriter writer = Files.newBufferedWriter(
        output, StandardCharsets.UTF_8, StandardOpenOption.TRUNCATE_EXISTING,
        StandardOpenOption.WRITE)) {
      PackageIndexWriter sink = new PackageIndexWriter(writer);
      try {
        source.visit(sink);
        sink.finish();
      } catch (UncheckedIOException error) {
        throw error.getCause();
      }
    }
  }

  private static void writeUnsignedArchive(
      Path output, Path index, String description, Instant createdAt) throws IOException {
    try (OutputStream raw = new BufferedOutputStream(Files.newOutputStream(
            output, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE));
        GzipCompressorOutputStream gzip = new GzipCompressorOutputStream(raw, gzipParameters());
        TarArchiveOutputStream tar = new TarArchiveOutputStream(gzip)) {
      tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_ERROR);
      tar.setBigNumberMode(TarArchiveOutputStream.BIGNUMBER_ERROR);
      if (description != null && !description.isBlank()) {
        byte[] bytes = description.getBytes(StandardCharsets.UTF_8);
        writeEntry(tar, "DESCRIPTION", bytes, createdAt);
      }
      TarArchiveEntry entry = regularEntry("APKINDEX", Files.size(index), createdAt);
      tar.putArchiveEntry(entry);
      try (InputStream input = Files.newInputStream(index)) {
        input.transferTo(tar);
      }
      tar.closeArchiveEntry();
      tar.finish();
    }
  }

  private static void writeSignatureArchive(
      Path output, AlpineSignature signature, Instant createdAt) throws IOException {
    ByteArrayOutputStream tarBytes = new ByteArrayOutputStream();
    try (TarArchiveOutputStream tar = new TarArchiveOutputStream(tarBytes)) {
      tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_ERROR);
      tar.setBigNumberMode(TarArchiveOutputStream.BIGNUMBER_ERROR);
      writeEntry(tar, signature.entryName(), signature.bytes(), createdAt);
      tar.finish();
    }
    int fragmentSize = 512 + ((signature.bytes().length + 511) / 512) * 512;
    byte[] archive = tarBytes.toByteArray();
    if (archive.length < fragmentSize) {
      throw new IOException("Incomplete Alpine signature tar fragment");
    }
    try (OutputStream raw = new BufferedOutputStream(Files.newOutputStream(
            output, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE));
        GzipCompressorOutputStream gzip = new GzipCompressorOutputStream(raw, gzipParameters())) {
      gzip.write(archive, 0, fragmentSize);
    }
  }

  private static void writeEntry(
      TarArchiveOutputStream tar, String name, byte[] bytes, Instant createdAt) throws IOException {
    TarArchiveEntry entry = regularEntry(name, bytes.length, createdAt);
    tar.putArchiveEntry(entry);
    tar.write(bytes);
    tar.closeArchiveEntry();
  }

  private static TarArchiveEntry regularEntry(String name, long size, Instant createdAt) {
    TarArchiveEntry entry = new TarArchiveEntry(name);
    entry.setSize(size);
    entry.setMode(0644);
    entry.setUserId(0);
    entry.setGroupId(0);
    entry.setUserName("");
    entry.setGroupName("");
    entry.setModTime(Date.from(createdAt));
    return entry;
  }

  private static GzipParameters gzipParameters() {
    GzipParameters parameters = new GzipParameters();
    parameters.setModificationInstant(Instant.EPOCH);
    parameters.setCompressionLevel(Deflater.BEST_COMPRESSION);
    parameters.setOperatingSystem(3);
    parameters.setFilename(null);
    return parameters;
  }

  private static void concatenate(Path first, Path second, Path output) throws IOException {
    try (OutputStream destination = new BufferedOutputStream(Files.newOutputStream(
        output, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE))) {
      Files.copy(first, destination);
      Files.copy(second, destination);
    }
  }

  static AlpineIndexRecord indexRecord(AlpineRegistryDao.PackageRecord row) {
    LinkedHashMap<Character, String> fields = new LinkedHashMap<>();
    row.controlFields().forEach((name, value) -> {
      if (name != null && name.length() == 1 && value != null) {
        fields.put(name.charAt(0), value.toString());
      }
    });
    fields.put('C', row.identity());
    fields.put('P', row.packageName());
    fields.put('V', row.version());
    // apk uses A as the download-directory segment relative to the configured repository URL.
    // Preserve the original .PKGINFO architecture separately, but publish the URL namespace.
    fields.put('A', row.architecture());
    fields.put('S', Long.toString(row.size()));
    AlpineIndexRecord.Builder builder = AlpineIndexRecord.builder();
    for (Character name : FIELD_ORDER) {
      String value = fields.get(name);
      if (value != null) builder.field(name, value);
    }
    fields.entrySet().stream()
        .filter(entry -> !KNOWN_FIELDS.contains(entry.getKey()))
        .sorted(Map.Entry.comparingByKey())
        .forEach(entry -> builder.field(entry.getKey(), entry.getValue()));
    return builder.build();
  }

  private static String[] namespace(String value) {
    String[] parts = value == null ? new String[0] : value.split("/", -1);
    if (parts.length != 3) throw new IllegalArgumentException("Invalid Alpine namespace: " + value);
    return parts;
  }

  private static String digest(Path file, String algorithm) throws IOException {
    MessageDigest digest;
    try {
      digest = MessageDigest.getInstance(algorithm);
    } catch (NoSuchAlgorithmException error) {
      throw new IllegalStateException(error);
    }
    try (InputStream input = Files.newInputStream(file)) {
      byte[] buffer = new byte[64 * 1024];
      for (int read; (read = input.read(buffer)) >= 0;) {
        if (read > 0) digest.update(buffer, 0, read);
      }
    }
    return HexFormat.of().formatHex(digest.digest());
  }

  private static Path temporary(String suffix) {
    try {
      return Files.createTempFile("kkrepo-alpine-index-", "-" + suffix);
    } catch (IOException error) {
      throw new IllegalStateException("Failed to create Alpine index spool", error);
    }
  }

  private static void delete(Path file) {
    if (file == null) return;
    try {
      Files.deleteIfExists(file);
    } catch (IOException ignored) {
      file.toFile().deleteOnExit();
    }
  }

  @FunctionalInterface
  interface PackageSource {
    void visit(Consumer<AlpineRegistryDao.PackageRecord> visitor);
  }

  record BuiltSnapshot(
      Map<String, String> manifest,
      String indexSha256,
      long size,
      int signingKeyRevision,
      Instant createdAt) {
  }

  private static final class PackageIndexWriter
      implements Consumer<AlpineRegistryDao.PackageRecord> {
    private final BufferedWriter writer;
    private final ArrayList<AlpineRegistryDao.PackageRecord> family = new ArrayList<>();
    private String packageName;
    private boolean first = true;

    private PackageIndexWriter(BufferedWriter writer) {
      this.writer = writer;
    }

    @Override
    public void accept(AlpineRegistryDao.PackageRecord record) {
      if (packageName != null && !packageName.equals(record.packageName())) flush();
      packageName = record.packageName();
      family.add(record);
    }

    private void finish() {
      flush();
    }

    private void flush() {
      if (family.isEmpty()) return;
      family.sort(PACKAGE_ORDER);
      try {
        for (AlpineRegistryDao.PackageRecord record : family) {
          if (!first) writer.write('\n');
          writer.write(indexRecord(record).render());
          first = false;
        }
      } catch (IOException error) {
        throw new UncheckedIOException(error);
      } finally {
        family.clear();
      }
    }
  }
}
