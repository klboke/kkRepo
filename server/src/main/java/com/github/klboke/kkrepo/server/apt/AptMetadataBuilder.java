package com.github.klboke.kkrepo.server.apt;

import com.github.klboke.kkrepo.persistence.jdbc.api.AptRegistryDao;
import com.github.klboke.kkrepo.protocol.apt.AptDeb822;
import com.github.klboke.kkrepo.protocol.apt.AptMediaTypes;
import com.github.klboke.kkrepo.protocol.apt.AptPackageControl;
import com.github.klboke.kkrepo.protocol.apt.AptRelease;
import com.github.klboke.kkrepo.protocol.apt.DebianVersions;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntime;
import java.io.BufferedOutputStream;
import java.io.BufferedWriter;
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
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.zip.Deflater;
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream;
import org.apache.commons.compress.compressors.gzip.GzipParameters;
import org.apache.commons.compress.compressors.xz.XZCompressorOutputStream;
import org.springframework.stereotype.Component;

/** Builds a complete immutable APT metadata snapshot before publishing its database pointer. */
@Component
final class AptMetadataBuilder {
  private static final Comparator<AptRegistryDao.PackageRecord> PACKAGE_ORDER =
      Comparator.comparing(AptRegistryDao.PackageRecord::packageName)
          .thenComparing(AptRegistryDao.PackageRecord::version, DebianVersions.COMPARATOR)
          .thenComparing(AptRegistryDao.PackageRecord::architecture)
          .thenComparing(AptRegistryDao.PackageRecord::path);

  private final AptRegistryDao registry;
  private final AptAssetSupport assets;
  private final AptSigningService signing;

  AptMetadataBuilder(
      AptRegistryDao registry, AptAssetSupport assets, AptSigningService signing) {
    this.registry = registry;
    this.assets = assets;
    this.signing = signing;
  }

  BuiltSnapshot build(
      RepositoryRuntime runtime,
      AptRepositorySettings.Settings settings,
      AptRegistryDao.SuiteState state,
      AptSigningService.SigningMaterial key) {
    String distribution = state.distribution();
    long revision = state.desiredRevision();
    Instant createdAt = state.desiredAt() == null ? Instant.now() : state.desiredAt();
    String hiddenPrefix = ".apt/snapshots/" + distribution + "/" + revision + "/";
    LinkedHashMap<String, String> manifest = new LinkedHashMap<>();
    ArrayList<AptRelease.Checksum> releaseChecksums = new ArrayList<>();
    LinkedHashSet<String> indexArchitectures = new LinkedHashSet<>();
    if (registry.listArchitectures(runtime.id(), distribution, settings.component())
        .contains("all")) {
      indexArchitectures.add("all");
    }
    indexArchitectures.addAll(settings.architectures());

    for (String architecture : indexArchitectures) {
      String directory = "dists/" + distribution + "/" + settings.component()
          + "/binary-" + architecture + "/";
      String releaseDirectory = settings.component() + "/binary-" + architecture + "/";
      Path packages = temporary("Packages");
      try {
        writePackages(
            packages, runtime.id(), distribution, settings.component(), architecture);
        storeIndex(
            runtime, packages, "Packages", AptMediaTypes.TEXT, directory, releaseDirectory,
            hiddenPrefix, distribution, revision, manifest, releaseChecksums);
        for (Compression compression : Compression.values()) {
          Path compressed = temporary(compression.filename());
          try {
            compress(packages, compressed, compression);
            storeIndex(
                runtime,
                compressed,
                compression.filename(),
                compression.contentType(),
                directory,
                releaseDirectory,
                hiddenPrefix,
                distribution,
                revision,
                manifest,
                releaseChecksums);
          } finally {
            deleteTemporary(compressed);
          }
        }
      } finally {
        deleteTemporary(packages);
      }
    }

    AptRelease.Builder releaseBuilder = AptRelease.builder(distribution, createdAt)
        .field("Origin", settings.origin())
        .field("Label", settings.label())
        .architectures(List.copyOf(indexArchitectures))
        .components(List.of(settings.component()));
    if (settings.validUntilDays() != null && settings.validUntilDays() > 0) {
      releaseBuilder.validUntil(createdAt.plus(settings.validUntilDays(), ChronoUnit.DAYS));
    }
    for (AptRelease.Checksum checksum : releaseChecksums) {
      releaseBuilder.checksum(
          checksum.algorithm(), checksum.digest(), checksum.size(), checksum.path());
    }
    byte[] release = releaseBuilder.build().render()
        .getBytes(StandardCharsets.UTF_8);
    AptSigningService.SignedRelease signed = signing.sign(release, key, createdAt);
    storeTopLevel(runtime, hiddenPrefix, distribution, revision, "Release", release, manifest);
    storeTopLevel(
        runtime, hiddenPrefix, distribution, revision, "InRelease", signed.inRelease(), manifest);
    storeTopLevel(
        runtime, hiddenPrefix, distribution, revision, "Release.gpg",
        signed.detachedSignature(), manifest);
    return new BuiltSnapshot(
        Map.copyOf(manifest), digests(release).sha256(), key.revision(), createdAt);
  }

  private void storeTopLevel(
      RepositoryRuntime runtime,
      String hiddenPrefix,
      String distribution,
      long revision,
      String filename,
      byte[] bytes,
      Map<String, String> manifest) {
    String canonical = "dists/" + distribution + "/" + filename;
    String hidden = hiddenPrefix + canonical;
    assets.storeGenerated(
        runtime,
        hidden,
        bytes,
        AptMediaTypes.forPath(filename),
        Map.of(
            "aptGenerated", true,
            "aptRevision", revision,
            "aptDistribution", distribution,
            "aptCanonicalPath", canonical));
    manifest.put(canonical, hidden);
  }

  private void writePackages(
      Path output,
      long repositoryId,
      String distribution,
      String component,
      String architecture) {
    try (BufferedWriter writer = Files.newBufferedWriter(
        output,
        StandardCharsets.UTF_8,
        StandardOpenOption.TRUNCATE_EXISTING,
        StandardOpenOption.WRITE)) {
      PackageIndexWriter visitor = new PackageIndexWriter(writer);
      try {
        registry.visitPackages(
            repositoryId, distribution, component, architecture, visitor);
        visitor.finish();
      } catch (UncheckedIOException error) {
        throw error.getCause();
      }
    } catch (IOException error) {
      throw new IllegalStateException("Failed to stream APT Packages index", error);
    }
  }

  private void storeIndex(
      RepositoryRuntime runtime,
      Path file,
      String filename,
      String contentType,
      String directory,
      String releaseDirectory,
      String hiddenPrefix,
      String distribution,
      long revision,
      Map<String, String> manifest,
      List<AptRelease.Checksum> releaseChecksums) {
    try {
      long size = Files.size(file);
      Digests digests = digests(file);
      String canonical = directory + filename;
      String hidden = hiddenPrefix + canonical;
      assets.storeGeneratedFile(
          runtime,
          hidden,
          file,
          contentType,
          Map.of(
              "aptGenerated", true,
              "aptRevision", revision,
              "aptDistribution", distribution,
              "aptCanonicalPath", canonical,
              "sha256", digests.sha256()));
      manifest.put(canonical, hidden);
      manifest.put(directory + "by-hash/SHA256/" + digests.sha256(), hidden);
      String releasePath = releaseDirectory + filename;
      releaseChecksums.add(new AptRelease.Checksum(
          "MD5Sum", digests.md5(), size, releasePath));
      releaseChecksums.add(new AptRelease.Checksum(
          "SHA1", digests.sha1(), size, releasePath));
      releaseChecksums.add(new AptRelease.Checksum(
          "SHA256", digests.sha256(), size, releasePath));
    } catch (IOException error) {
      throw new IllegalStateException("Failed to store APT " + filename, error);
    }
  }

  private static void compress(Path input, Path output, Compression compression) {
    try (InputStream source = Files.newInputStream(input);
        OutputStream destination = new BufferedOutputStream(Files.newOutputStream(
            output, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE));
        OutputStream compressed = compression.wrap(destination)) {
      source.transferTo(compressed);
    } catch (IOException error) {
      throw new IllegalStateException(
          "Failed to generate APT " + compression.filename(), error);
    }
  }

  private static Path temporary(String suffix) {
    try {
      return Files.createTempFile("kkrepo-apt-metadata-", "-" + suffix);
    } catch (IOException error) {
      throw new IllegalStateException("Failed to create APT metadata spool", error);
    }
  }

  private static void deleteTemporary(Path file) {
    if (file == null) return;
    try {
      Files.deleteIfExists(file);
    } catch (IOException ignored) {
      file.toFile().deleteOnExit();
    }
  }

  private static Digests digests(byte[] bytes) {
    return new Digests(hex("MD5", bytes), hex("SHA-1", bytes), hex("SHA-256", bytes));
  }

  private static Digests digests(Path file) throws IOException {
    MessageDigest md5 = digest("MD5");
    MessageDigest sha1 = digest("SHA-1");
    MessageDigest sha256 = digest("SHA-256");
    try (InputStream input = Files.newInputStream(file)) {
      byte[] buffer = new byte[64 * 1024];
      int read;
      while ((read = input.read(buffer)) >= 0) {
        if (read == 0) continue;
        md5.update(buffer, 0, read);
        sha1.update(buffer, 0, read);
        sha256.update(buffer, 0, read);
      }
    }
    return new Digests(
        HexFormat.of().formatHex(md5.digest()),
        HexFormat.of().formatHex(sha1.digest()),
        HexFormat.of().formatHex(sha256.digest()));
  }

  private static String hex(String algorithm, byte[] bytes) {
    return HexFormat.of().formatHex(digest(algorithm).digest(bytes));
  }

  private static MessageDigest digest(String algorithm) {
    try {
      return MessageDigest.getInstance(algorithm);
    } catch (NoSuchAlgorithmException error) {
      throw new IllegalStateException("Missing digest: " + algorithm, error);
    }
  }

  record BuiltSnapshot(
      Map<String, String> manifest,
      String releaseSha256,
      int signingKeyRevision,
      Instant createdAt) { }

  private record Digests(String md5, String sha1, String sha256) { }

  private static final class PackageIndexWriter
      implements Consumer<AptRegistryDao.PackageRecord> {
    private final BufferedWriter writer;
    private final ArrayList<AptRegistryDao.PackageRecord> group = new ArrayList<>();
    private String packageName;
    private boolean first = true;

    private PackageIndexWriter(BufferedWriter writer) {
      this.writer = writer;
    }

    @Override
    public void accept(AptRegistryDao.PackageRecord record) {
      if (packageName != null && !packageName.equals(record.packageName())) {
        flush();
      }
      packageName = record.packageName();
      group.add(record);
    }

    private void finish() {
      flush();
    }

    private void flush() {
      if (group.isEmpty()) return;
      group.sort(PACKAGE_ORDER);
      try {
        for (AptRegistryDao.PackageRecord record : group) {
          if (!first) writer.write('\n');
          writer.write(packagesStanza(record).render());
          first = false;
        }
      } catch (IOException error) {
        throw new UncheckedIOException(error);
      } finally {
        group.clear();
      }
    }
  }

  private static AptDeb822.Stanza packagesStanza(AptRegistryDao.PackageRecord record) {
    LinkedHashMap<String, String> fields = new LinkedHashMap<>();
    record.controlFields().forEach((name, value) -> {
      if (value != null) fields.put(name, value.toString());
    });
    AptPackageControl control = AptPackageControl.from(new AptDeb822.Stanza(fields));
    return control.packagesStanza(
        record.path(), record.size(), record.md5(), record.sha1(), record.sha256());
  }

  private enum Compression {
    GZIP("Packages.gz", AptMediaTypes.GZIP) {
      @Override
      OutputStream wrap(OutputStream output) throws IOException {
        GzipParameters parameters = new GzipParameters();
        parameters.setModificationInstant(Instant.EPOCH);
        parameters.setCompressionLevel(Deflater.BEST_COMPRESSION);
        parameters.setOperatingSystem(3);
        return new GzipCompressorOutputStream(output, parameters);
      }
    },
    BZIP2("Packages.bz2", AptMediaTypes.BZIP2) {
      @Override
      OutputStream wrap(OutputStream output) throws IOException {
        return new BZip2CompressorOutputStream(output, 9);
      }
    },
    XZ("Packages.xz", AptMediaTypes.XZ) {
      @Override
      OutputStream wrap(OutputStream output) throws IOException {
        return new XZCompressorOutputStream(output, 6);
      }
    };

    private final String filename;
    private final String contentType;

    Compression(String filename, String contentType) {
      this.filename = filename;
      this.contentType = contentType;
    }

    String filename() {
      return filename;
    }

    String contentType() {
      return contentType;
    }

    abstract OutputStream wrap(OutputStream output) throws IOException;
  }
}
