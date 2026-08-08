package com.github.klboke.kkrepo.compat;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.Security;
import java.time.Instant;
import java.util.Date;
import java.util.HexFormat;
import org.apache.commons.compress.archivers.ar.ArArchiveEntry;
import org.apache.commons.compress.archivers.ar.ArArchiveOutputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream;
import org.apache.commons.compress.compressors.gzip.GzipParameters;
import org.bouncycastle.bcpg.ArmoredOutputStream;
import org.bouncycastle.bcpg.HashAlgorithmTags;
import org.bouncycastle.bcpg.SymmetricKeyAlgorithmTags;
import org.bouncycastle.bcpg.sig.KeyFlags;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.openpgp.PGPKeyPair;
import org.bouncycastle.openpgp.PGPKeyRingGenerator;
import org.bouncycastle.openpgp.PGPPublicKey;
import org.bouncycastle.openpgp.PGPSignature;
import org.bouncycastle.openpgp.PGPSignatureSubpacketGenerator;
import org.bouncycastle.openpgp.operator.PGPDigestCalculator;
import org.bouncycastle.openpgp.operator.jcajce.JcaPGPContentSignerBuilder;
import org.bouncycastle.openpgp.operator.jcajce.JcaPGPDigestCalculatorProviderBuilder;
import org.bouncycastle.openpgp.operator.jcajce.JcaPGPKeyPair;
import org.bouncycastle.openpgp.operator.jcajce.JcePBESecretKeyEncryptorBuilder;

/** Deterministic-enough binary package and ephemeral OpenPGP fixtures for live APT checks. */
final class AptCompatibilityFixtures {
  private AptCompatibilityFixtures() {
  }

  static DebianPackage deb(
      String name, String version, String architecture, String depends, String marker)
      throws Exception {
    StringBuilder control = new StringBuilder()
        .append("Package: ").append(name).append('\n')
        .append("Version: ").append(version).append('\n')
        .append("Architecture: ").append(architecture).append('\n')
        .append("Maintainer: kkRepo Compatibility <compat@kkrepo.invalid>\n")
        .append("Section: utils\n")
        .append("Priority: optional\n");
    if (depends != null && !depends.isBlank()) {
      control.append("Depends: ").append(depends).append('\n');
    }
    control.append("Description: kkRepo APT compatibility fixture\n")
        .append(" Generated package for repository protocol verification.\n");

    byte[] controlArchive = tarGzip(
        "control", control.toString().getBytes(StandardCharsets.UTF_8), 0644);
    byte[] dataArchive = tarGzip(
        "usr/share/kkrepo-apt/" + name + ".txt",
        (marker + "\n").getBytes(StandardCharsets.UTF_8),
        0644);
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (ArArchiveOutputStream ar = new ArArchiveOutputStream(bytes)) {
      ar.setLongFileMode(ArArchiveOutputStream.LONGFILE_ERROR);
      addAr(ar, "debian-binary", "2.0\n".getBytes(StandardCharsets.US_ASCII));
      addAr(ar, "control.tar.gz", controlArchive);
      addAr(ar, "data.tar.gz", dataArchive);
      ar.finish();
    }
    byte[] archive = bytes.toByteArray();
    String epochless = version.contains(":") ? version.substring(version.indexOf(':') + 1) : version;
    return new DebianPackage(
        name + "_" + epochless + "_" + architecture + ".deb",
        name,
        version,
        architecture,
        archive,
        sha256(archive));
  }

  static SigningKey signingKey(String identity) throws Exception {
    if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
      Security.addProvider(new BouncyCastleProvider());
    }
    KeyPairGenerator rsa = KeyPairGenerator.getInstance(
        "RSA", BouncyCastleProvider.PROVIDER_NAME);
    rsa.initialize(2048);
    Date createdAt = Date.from(Instant.now().minusSeconds(60));
    PGPKeyPair pair = new JcaPGPKeyPair(
        PGPPublicKey.RSA_SIGN, rsa.generateKeyPair(), createdAt);
    PGPDigestCalculator sha1 = new JcaPGPDigestCalculatorProviderBuilder()
        .setProvider(BouncyCastleProvider.PROVIDER_NAME)
        .build()
        .get(HashAlgorithmTags.SHA1);
    PGPSignatureSubpacketGenerator certification = new PGPSignatureSubpacketGenerator();
    certification.setKeyFlags(false, KeyFlags.CERTIFY_OTHER | KeyFlags.SIGN_DATA);
    PGPKeyRingGenerator generator = new PGPKeyRingGenerator(
        PGPSignature.POSITIVE_CERTIFICATION,
        pair,
        identity,
        sha1,
        certification.generate(),
        null,
        new JcaPGPContentSignerBuilder(
            pair.getPublicKey().getAlgorithm(), HashAlgorithmTags.SHA256)
            .setProvider(BouncyCastleProvider.PROVIDER_NAME),
        new JcePBESecretKeyEncryptorBuilder(SymmetricKeyAlgorithmTags.AES_256, sha1)
            .setProvider(BouncyCastleProvider.PROVIDER_NAME)
            .build(new char[0]));
    ByteArrayOutputStream publicBytes = new ByteArrayOutputStream();
    try (ArmoredOutputStream armor = new ArmoredOutputStream(publicBytes)) {
      generator.generatePublicKeyRing().encode(armor);
    }
    ByteArrayOutputStream privateBytes = new ByteArrayOutputStream();
    try (ArmoredOutputStream armor = new ArmoredOutputStream(privateBytes)) {
      generator.generateSecretKeyRing().encode(armor);
    }
    return new SigningKey(
        publicBytes.toString(StandardCharsets.UTF_8),
        privateBytes.toString(StandardCharsets.UTF_8));
  }

  private static byte[] tarGzip(String path, byte[] content, int mode) throws Exception {
    ByteArrayOutputStream compressed = new ByteArrayOutputStream();
    GzipParameters parameters = new GzipParameters();
    parameters.setModificationInstant(Instant.EPOCH);
    try (GzipCompressorOutputStream gzip = new GzipCompressorOutputStream(compressed, parameters);
         TarArchiveOutputStream tar = new TarArchiveOutputStream(gzip)) {
      String[] parts = path.split("/");
      StringBuilder directory = new StringBuilder();
      for (int index = 0; index < parts.length - 1; index++) {
        if (parts[index].isBlank()) {
          continue;
        }
        if (!directory.isEmpty()) {
          directory.append('/');
        }
        directory.append(parts[index]);
        TarArchiveEntry parent = new TarArchiveEntry(directory + "/");
        parent.setMode(0755);
        parent.setModTime(Date.from(Instant.EPOCH));
        tar.putArchiveEntry(parent);
        tar.closeArchiveEntry();
      }
      TarArchiveEntry entry = new TarArchiveEntry(path);
      entry.setMode(mode);
      entry.setModTime(Date.from(Instant.EPOCH));
      entry.setSize(content.length);
      tar.putArchiveEntry(entry);
      tar.write(content);
      tar.closeArchiveEntry();
      tar.finish();
    }
    return compressed.toByteArray();
  }

  private static void addAr(ArArchiveOutputStream ar, String name, byte[] content)
      throws Exception {
    ar.putArchiveEntry(new ArArchiveEntry(name, content.length));
    ar.write(content);
    ar.closeArchiveEntry();
  }

  private static String sha256(byte[] bytes) throws Exception {
    return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
  }

  record DebianPackage(
      String filename,
      String name,
      String version,
      String architecture,
      byte[] bytes,
      String sha256) {
  }

  record SigningKey(String publicArmor, String privateArmor) {
  }
}
