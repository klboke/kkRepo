package com.github.klboke.kkrepo.server.alpine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.klboke.kkrepo.server.maven.MavenExceptions;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.util.Base64;
import java.util.List;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream;
import org.junit.jupiter.api.Test;

class AlpineIndexArchiveTest {

  @Test
  void verifiesSignedIndexWithoutRewritingUpstreamBytes() throws Exception {
    KeyPair key = rsa();
    byte[] unsigned = indexMember(indexRecord("demo", "1.2.3-r0"));
    byte[] signature = sign(unsigned, key, "SHA256withRSA");
    byte[] archive = AlpineTestPackage.concatenate(
        signatureMember(".SIGN.RSA256.fixture.rsa.pub", signature), unsigned);

    AlpineIndexArchive.Parsed parsed = AlpineIndexArchive.read(
        new ByteArrayInputStream(archive),
        List.of("filename=fixture.rsa.pub\n" + publicPem(key)),
        true);

    assertTrue(parsed.signatureVerified());
    assertEquals("demo", parsed.records().getFirst().packageName());
    assertEquals("1.2.3-r0", parsed.records().getFirst().version());
    assertEquals(64, parsed.sha256().length());
    assertEquals("fixture.rsa.pub", parsed.signature().keyFilename());
  }

  @Test
  void distinguishesUnverifiedPassthroughFromRequiredTrust() throws Exception {
    KeyPair key = rsa();
    byte[] unsigned = indexMember(indexRecord("demo", "1.0-r0"));
    byte[] archive = AlpineTestPackage.concatenate(
        signatureMember(".SIGN.RSA.fixture.rsa.pub", sign(unsigned, key, "SHA1withRSA")),
        unsigned);

    AlpineIndexArchive.Parsed passthrough = AlpineIndexArchive.read(
        new ByteArrayInputStream(archive), List.of(), false);
    assertFalse(passthrough.signatureVerified());
    assertThrows(MavenExceptions.BadUpstreamException.class, () -> AlpineIndexArchive.read(
        new ByteArrayInputStream(archive), List.of(), true));

    archive[archive.length - 9] ^= 1;
    assertThrows(MavenExceptions.BadUpstreamException.class, () -> AlpineIndexArchive.read(
        new ByteArrayInputStream(archive),
        List.of("filename=fixture.rsa.pub\n" + publicPem(key)), false));
  }

  @Test
  void rejectsUnsignedMalformedAndV3LikeInputs() throws Exception {
    byte[] unsigned = indexMember(indexRecord("demo", "1.0-r0"));
    AlpineIndexArchive.Parsed parsed = AlpineIndexArchive.read(
        new ByteArrayInputStream(unsigned), List.of(), false);
    assertFalse(parsed.signatureVerified());
    assertThrows(MavenExceptions.BadUpstreamException.class, () -> AlpineIndexArchive.read(
        new ByteArrayInputStream(unsigned), List.of(), true));
    assertThrows(MavenExceptions.BadUpstreamException.class, () -> AlpineIndexArchive.read(
        new ByteArrayInputStream("Packages.adb".getBytes(StandardCharsets.UTF_8)),
        List.of(), false));
  }

  private static String indexRecord(String name, String version) {
    return """
        C:Q1AAAAAAAAAAAAAAAAAAAAAAAAAAA=
        P:%s
        V:%s
        A:x86_64
        S:123
        I:456
        T:fixture
        U:https://example.invalid
        L:MIT

        """.formatted(name, version);
  }

  private static byte[] indexMember(String record) throws Exception {
    ByteArrayOutputStream result = new ByteArrayOutputStream();
    try (GzipCompressorOutputStream gzip = new GzipCompressorOutputStream(result);
        TarArchiveOutputStream tar = new TarArchiveOutputStream(gzip)) {
      write(tar, "DESCRIPTION", "kkRepo Alpine fixture".getBytes(StandardCharsets.UTF_8));
      write(tar, "APKINDEX", record.getBytes(StandardCharsets.UTF_8));
      tar.finish();
    }
    return result.toByteArray();
  }

  private static byte[] signatureMember(String name, byte[] signature) throws Exception {
    ByteArrayOutputStream result = new ByteArrayOutputStream();
    try (GzipCompressorOutputStream gzip = new GzipCompressorOutputStream(result);
        TarArchiveOutputStream tar = new TarArchiveOutputStream(gzip)) {
      write(tar, name, signature);
      tar.finish();
    }
    return result.toByteArray();
  }

  private static void write(TarArchiveOutputStream tar, String name, byte[] bytes)
      throws Exception {
    TarArchiveEntry entry = new TarArchiveEntry(name);
    entry.setSize(bytes.length);
    tar.putArchiveEntry(entry);
    tar.write(bytes);
    tar.closeArchiveEntry();
  }

  private static KeyPair rsa() throws Exception {
    KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
    generator.initialize(2048);
    return generator.generateKeyPair();
  }

  private static byte[] sign(byte[] payload, KeyPair key, String algorithm) throws Exception {
    Signature signer = Signature.getInstance(algorithm);
    signer.initSign(key.getPrivate());
    signer.update(payload);
    return signer.sign();
  }

  private static String publicPem(KeyPair key) {
    String body = Base64.getMimeEncoder(64, new byte[] {'\n'})
        .encodeToString(key.getPublic().getEncoded());
    return "-----BEGIN PUBLIC KEY-----\n" + body + "\n-----END PUBLIC KEY-----\n";
  }
}
