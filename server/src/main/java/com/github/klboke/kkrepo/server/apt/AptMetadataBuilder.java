package com.github.klboke.kkrepo.server.apt;

import com.github.klboke.kkrepo.persistence.jdbc.api.AptRegistryDao;
import com.github.klboke.kkrepo.protocol.apt.AptDeb822;
import com.github.klboke.kkrepo.protocol.apt.AptMediaTypes;
import com.github.klboke.kkrepo.protocol.apt.AptPackageControl;
import com.github.klboke.kkrepo.protocol.apt.AptRelease;
import com.github.klboke.kkrepo.protocol.apt.DebianVersions;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntime;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
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
      List<AptRegistryDao.PackageRecord> records = new ArrayList<>(
          registry.listPackages(runtime.id(), distribution, settings.component(), architecture));
      records.sort(PACKAGE_ORDER);
      byte[] packages = packages(records);
      LinkedHashMap<String, Representation> representations = new LinkedHashMap<>();
      representations.put("Packages", new Representation(packages, AptMediaTypes.TEXT));
      representations.put("Packages.gz", new Representation(gzip(packages), AptMediaTypes.GZIP));
      representations.put("Packages.bz2", new Representation(bzip2(packages), AptMediaTypes.BZIP2));
      representations.put("Packages.xz", new Representation(xz(packages), AptMediaTypes.XZ));
      String directory = "dists/" + distribution + "/" + settings.component()
          + "/binary-" + architecture + "/";
      String releaseDirectory = settings.component() + "/binary-" + architecture + "/";
      for (Map.Entry<String, Representation> entry : representations.entrySet()) {
        String canonical = directory + entry.getKey();
        String hidden = hiddenPrefix + canonical;
        byte[] bytes = entry.getValue().bytes();
        Digests digests = digests(bytes);
        assets.storeGenerated(
            runtime,
            hidden,
            bytes,
            entry.getValue().contentType(),
            Map.of(
                "aptGenerated", true,
                "aptRevision", revision,
                "aptDistribution", distribution,
                "aptCanonicalPath", canonical,
                "sha256", digests.sha256()));
        manifest.put(canonical, hidden);
        manifest.put(directory + "by-hash/SHA256/" + digests.sha256(), hidden);
        String releasePath = releaseDirectory + entry.getKey();
        releaseChecksums.add(new AptRelease.Checksum(
            "MD5Sum", digests.md5(), bytes.length, releasePath));
        releaseChecksums.add(new AptRelease.Checksum(
            "SHA1", digests.sha1(), bytes.length, releasePath));
        releaseChecksums.add(new AptRelease.Checksum(
            "SHA256", digests.sha256(), bytes.length, releasePath));
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
        .getBytes(java.nio.charset.StandardCharsets.UTF_8);
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

  private static byte[] packages(List<AptRegistryDao.PackageRecord> records) {
    ArrayList<AptDeb822.Stanza> stanzas = new ArrayList<>(records.size());
    for (AptRegistryDao.PackageRecord record : records) {
      LinkedHashMap<String, String> fields = new LinkedHashMap<>();
      record.controlFields().forEach((name, value) -> {
        if (value != null) fields.put(name, value.toString());
      });
      AptPackageControl control = AptPackageControl.from(new AptDeb822.Stanza(fields));
      stanzas.add(control.packagesStanza(
          record.path(), record.size(), record.md5(), record.sha1(), record.sha256()));
    }
    return AptDeb822.render(stanzas).getBytes(java.nio.charset.StandardCharsets.UTF_8);
  }

  private static byte[] gzip(byte[] bytes) {
    try {
      ByteArrayOutputStream output = new ByteArrayOutputStream();
      GzipParameters parameters = new GzipParameters();
      parameters.setModificationInstant(Instant.EPOCH);
      parameters.setCompressionLevel(Deflater.BEST_COMPRESSION);
      parameters.setOperatingSystem(3);
      try (GzipCompressorOutputStream gzip = new GzipCompressorOutputStream(output, parameters)) {
        gzip.write(bytes);
      }
      return output.toByteArray();
    } catch (IOException error) {
      throw new IllegalStateException("Failed to generate APT Packages.gz", error);
    }
  }

  private static byte[] bzip2(byte[] bytes) {
    try {
      ByteArrayOutputStream output = new ByteArrayOutputStream();
      try (BZip2CompressorOutputStream bzip2 = new BZip2CompressorOutputStream(output, 9)) {
        bzip2.write(bytes);
      }
      return output.toByteArray();
    } catch (IOException error) {
      throw new IllegalStateException("Failed to generate APT Packages.bz2", error);
    }
  }

  private static byte[] xz(byte[] bytes) {
    try {
      ByteArrayOutputStream output = new ByteArrayOutputStream();
      try (XZCompressorOutputStream xz = new XZCompressorOutputStream(output, 6)) {
        xz.write(bytes);
      }
      return output.toByteArray();
    } catch (IOException error) {
      throw new IllegalStateException("Failed to generate APT Packages.xz", error);
    }
  }

  private static Digests digests(byte[] bytes) {
    return new Digests(hex("MD5", bytes), hex("SHA-1", bytes), hex("SHA-256", bytes));
  }

  private static String hex(String algorithm, byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance(algorithm).digest(bytes));
    } catch (NoSuchAlgorithmException error) {
      throw new IllegalStateException("Missing digest: " + algorithm, error);
    }
  }

  record BuiltSnapshot(
      Map<String, String> manifest,
      String releaseSha256,
      int signingKeyRevision,
      Instant createdAt) { }

  private record Representation(byte[] bytes, String contentType) { }

  private record Digests(String md5, String sha1, String sha256) { }
}
