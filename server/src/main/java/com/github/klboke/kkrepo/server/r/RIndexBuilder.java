package com.github.klboke.kkrepo.server.r;

import com.github.klboke.kkrepo.persistence.jdbc.api.PersistenceHashes;
import com.github.klboke.kkrepo.persistence.jdbc.api.RRegistryDao;
import com.github.klboke.kkrepo.protocol.r.RDcf;
import com.github.klboke.kkrepo.protocol.r.RPackageIndex;
import com.github.klboke.kkrepo.protocol.r.RVersions;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntime;
import java.io.BufferedOutputStream;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;
import java.util.zip.Deflater;
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream;
import org.apache.commons.compress.compressors.gzip.GzipParameters;
import org.springframework.stereotype.Component;

/** Streams a deterministic latest-only PACKAGES.gz into an immutable snapshot asset. */
@Component
final class RIndexBuilder {
  static final int CODEC_REVISION = 1;

  private final RRegistryDao registry;
  private final RAssetSupport assets;

  RIndexBuilder(RRegistryDao registry, RAssetSupport assets) {
    this.registry = registry;
    this.assets = assets;
  }

  BuiltSnapshot build(RepositoryRuntime runtime, RRegistryDao.SuiteState state) {
    return build(runtime, state, visitor -> registry.visitPackages(
        runtime.id(), state.distribution(), RService.COMPONENT,
        RService.ARCHITECTURE, visitor));
  }

  BuiltSnapshot build(
      RepositoryRuntime runtime,
      RRegistryDao.SuiteState state,
      PackageSource source) {
    long revision = state.desiredRevision();
    Instant createdAt = state.desiredAt() == null ? Instant.now() : state.desiredAt();
    String canonical = RService.SOURCE_NAMESPACE + "/PACKAGES.gz";
    String namespaceHash = HexFormat.of().formatHex(
        PersistenceHashes.sha256(state.distribution()));
    String hidden = ".r/snapshots/" + namespaceHash + "/" + revision + "/PACKAGES.gz";
    Path compressed = temporary();
    try {
      writeIndex(compressed, source);
      String sha256 = digest(compressed, "SHA-256");
      long size = Files.size(compressed);
      assets.storeGeneratedFile(
          runtime,
          hidden,
          compressed,
          Map.of(
              "rGenerated", true,
              "rRevision", revision,
              "rNamespace", state.distribution(),
              "rCanonicalPath", canonical,
              "rCodecRevision", CODEC_REVISION,
              "sha256", sha256));
      return new BuiltSnapshot(
          Map.of(canonical, hidden), sha256, size, CODEC_REVISION, createdAt);
    } catch (IOException error) {
      throw new IllegalStateException("Failed to build R PACKAGES.gz snapshot", error);
    } finally {
      delete(compressed);
    }
  }

  private static void writeIndex(Path output, PackageSource source) throws IOException {
    GzipParameters parameters = new GzipParameters();
    parameters.setModificationInstant(Instant.EPOCH);
    parameters.setCompressionLevel(Deflater.BEST_COMPRESSION);
    parameters.setOperatingSystem(3);
    parameters.setFilename(null);
    try (OutputStream raw = new BufferedOutputStream(Files.newOutputStream(
            output, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE));
        GzipCompressorOutputStream gzip = new GzipCompressorOutputStream(raw, parameters);
        BufferedWriter writer = new BufferedWriter(
            new OutputStreamWriter(gzip, StandardCharsets.UTF_8))) {
      LatestOnlyWriter sink = new LatestOnlyWriter(writer);
      try {
        source.visit(sink);
        sink.finish();
      } catch (UncheckedIOException error) {
        throw error.getCause();
      }
    }
  }

  private static Map<String, String> fields(RRegistryDao.PackageRecord row) {
    LinkedHashMap<String, String> fields = new LinkedHashMap<>();
    row.controlFields().forEach((name, value) -> {
      if (name != null && value != null) fields.put(name, value.toString());
    });
    fields.put("Package", row.packageName());
    fields.put("Version", row.version());
    if (!fields.containsKey("MD5sum") && row.identity() != null) {
      fields.put("MD5sum", row.identity());
    }
    return fields;
  }

  private static int prefer(
      RRegistryDao.PackageRecord left, RRegistryDao.PackageRecord right) {
    int version = RVersions.compare(left.version(), right.version());
    if (version != 0) return version;
    return right.path().compareTo(left.path());
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

  private static Path temporary() {
    try {
      return Files.createTempFile("kkrepo-r-index-", ".PACKAGES.gz");
    } catch (IOException error) {
      throw new IllegalStateException("Failed to create R index spool", error);
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
    void visit(Consumer<RRegistryDao.PackageRecord> visitor);
  }

  record BuiltSnapshot(
      Map<String, String> manifest,
      String indexSha256,
      long size,
      int codecRevision,
      Instant createdAt) { }

  private static final class LatestOnlyWriter
      implements Consumer<RRegistryDao.PackageRecord> {
    private final BufferedWriter writer;
    private RRegistryDao.PackageRecord selected;
    private String packageName;
    private boolean first = true;

    private LatestOnlyWriter(BufferedWriter writer) {
      this.writer = writer;
    }

    @Override
    public void accept(RRegistryDao.PackageRecord record) {
      if (packageName != null && !packageName.equals(record.packageName())) flush();
      if (packageName == null || !packageName.equals(record.packageName())) {
        packageName = record.packageName();
        selected = record;
      } else if (prefer(record, selected) > 0) {
        selected = record;
      }
    }

    private void finish() {
      flush();
    }

    private void flush() {
      if (selected == null) return;
      try {
        if (!first) writer.newLine();
        writer.write(RDcf.renderRecord(fields(selected), RPackageIndex.FIELD_ORDER));
        first = false;
      } catch (IOException error) {
        throw new UncheckedIOException(error);
      }
      selected = null;
    }
  }
}
