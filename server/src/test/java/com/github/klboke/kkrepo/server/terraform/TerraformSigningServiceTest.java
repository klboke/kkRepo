package com.github.klboke.kkrepo.server.terraform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.core.RepositoryType;
import com.github.klboke.kkrepo.core.security.OpenPgpKeyIds;
import com.github.klboke.kkrepo.persistence.jdbc.api.TerraformRegistryDao;
import com.github.klboke.kkrepo.server.maven.MavenExceptions;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntime;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyPairGenerator;
import java.security.Provider;
import java.security.Security;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.bouncycastle.bcpg.ArmoredOutputStream;
import org.bouncycastle.bcpg.HashAlgorithmTags;
import org.bouncycastle.bcpg.SymmetricKeyAlgorithmTags;
import org.bouncycastle.bcpg.sig.KeyFlags;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.openpgp.PGPKeyPair;
import org.bouncycastle.openpgp.PGPKeyRingGenerator;
import org.bouncycastle.openpgp.PGPObjectFactory;
import org.bouncycastle.openpgp.PGPSignature;
import org.bouncycastle.openpgp.PGPSignatureGenerator;
import org.bouncycastle.openpgp.PGPSignatureSubpacketGenerator;
import org.bouncycastle.openpgp.PGPPublicKeyRingCollection;
import org.bouncycastle.openpgp.PGPSignatureList;
import org.bouncycastle.openpgp.PGPUtil;
import org.bouncycastle.openpgp.operator.PGPDigestCalculator;
import org.bouncycastle.openpgp.operator.jcajce.JcaPGPContentSignerBuilder;
import org.bouncycastle.openpgp.operator.jcajce.JcaPGPDigestCalculatorProviderBuilder;
import org.bouncycastle.openpgp.operator.jcajce.JcaKeyFingerprintCalculator;
import org.bouncycastle.openpgp.operator.jcajce.JcaPGPKeyPair;
import org.bouncycastle.openpgp.operator.jcajce.JcaPGPContentVerifierBuilderProvider;
import org.bouncycastle.openpgp.operator.jcajce.JcePBESecretKeyEncryptorBuilder;
import org.junit.jupiter.api.Test;

class TerraformSigningServiceTest {
  @Test
  void generatedDetachedSignatureVerifiesWithPublishedPublicKey() throws Exception {
    TerraformRegistryDao dao = mock(TerraformRegistryDao.class);
    AtomicReference<TerraformRegistryDao.SigningKey> stored = new AtomicReference<>();
    when(dao.findActiveSigningKey(7L)).thenAnswer(ignored -> Optional.ofNullable(stored.get()));
    when(dao.tryAcquirePublishLease(anyString(), anyString(), any(Instant.class))).thenReturn(true);
    doAnswer(invocation -> { stored.set(invocation.getArgument(0)); return null; })
        .when(dao).insertSigningKey(any());
    TerraformSigningService service = new TerraformSigningService(dao);
    RepositoryRuntime runtime = new RepositoryRuntime(
        7L, "terraform-hosted", RepositoryFormat.TERRAFORM, RepositoryType.HOSTED,
        "terraform-hosted", true, 1L, "ALLOW_ONCE", null, null, true,
        null, null, null, List.of());
    byte[] contents = "abcd  terraform-provider-demo_1.0.0_linux_amd64.zip\n"
        .getBytes(StandardCharsets.UTF_8);
    TerraformSigningService.SigningMaterial material = service.active(runtime);
    byte[] detached = service.sign(contents, material);

    new TerraformSignatureVerifier().verify(contents, detached, List.of(material.publicArmor()));

    PGPPublicKeyRingCollection publicKeys = new PGPPublicKeyRingCollection(
        PGPUtil.getDecoderStream(new ByteArrayInputStream(material.publicArmor().getBytes(StandardCharsets.UTF_8))),
        new JcaKeyFingerprintCalculator());
    PGPObjectFactory objects = new PGPObjectFactory(
        PGPUtil.getDecoderStream(new ByteArrayInputStream(detached)), new JcaKeyFingerprintCalculator());
    var signature = ((PGPSignatureList) objects.nextObject()).get(0);
    assertEquals(material.keyId(), OpenPgpKeyIds.format(signature.getKeyID()));
    var primary = publicKeys.getPublicKey(signature.getKeyID());
    var identities = primary.getSignaturesForID(primary.getUserIDs().next());
    var certification = identities.next();
    assertTrue(certification.getHashedSubPackets().getKeyFlags() != 0);
    signature.init(
        new JcaPGPContentVerifierBuilderProvider().setProvider(new BouncyCastleProvider()), primary);
    signature.update(contents);
    assertTrue(signature.verify());

    TerraformSignatureVerifier verifier = new TerraformSignatureVerifier();
    assertThrows(MavenExceptions.BadUpstreamException.class,
        () -> verifier.verify("tampered".getBytes(StandardCharsets.UTF_8), detached,
            List.of(material.publicArmor())));
    assertThrows(MavenExceptions.BadUpstreamException.class,
        () -> verifier.verify(contents, detached, List.of()));
    assertThrows(MavenExceptions.BadUpstreamException.class,
        () -> verifier.verify(contents, "not a signature".getBytes(StandardCharsets.UTF_8),
            List.of(material.publicArmor())));
  }

  @Test
  void signsCast5ProtectedLegacyKeyWhenBcIsNotPreinstalled() throws Exception {
    LegacySigningKey key = legacySigningKey("legacy-passphrase");
    byte[] contents = "legacy checksums\n".getBytes(StandardCharsets.UTF_8);
    Provider previousProvider = removeBouncyCastleProvider();
    try {
      assertNull(Security.getProvider(BouncyCastleProvider.PROVIDER_NAME));
      TerraformSigningService service = new TerraformSigningService(mock(TerraformRegistryDao.class));
      byte[] detached = service.sign(contents, new TerraformSigningService.SigningMaterial(
          1,
          OpenPgpKeyIds.format(key.keyPair().getKeyID()),
          key.publicArmor(),
          key.privateArmor(),
          key.passphrase()));

      assertNotNull(Security.getProvider(BouncyCastleProvider.PROVIDER_NAME));
      new TerraformSignatureVerifier().verify(contents, detached, List.of(key.publicArmor()));
    } finally {
      restoreBouncyCastleProvider(previousProvider);
    }
  }

  @Test
  void verifiesRipemd160LegacySignatureWhenBcIsNotPreinstalled() throws Exception {
    LegacySigningKey key = legacySigningKey("legacy-passphrase");
    byte[] contents = "legacy upstream checksums\n".getBytes(StandardCharsets.UTF_8);
    byte[] detached = detachedSignature(contents, key.keyPair(), HashAlgorithmTags.RIPEMD160);
    Provider previousProvider = removeBouncyCastleProvider();
    try {
      assertNull(Security.getProvider(BouncyCastleProvider.PROVIDER_NAME));

      new TerraformSignatureVerifier().verify(contents, detached, List.of(key.publicArmor()));

      assertNotNull(Security.getProvider(BouncyCastleProvider.PROVIDER_NAME));
    } finally {
      restoreBouncyCastleProvider(previousProvider);
    }
  }

  @Test
  void nativeRuntimeDoesNotInstallBouncyCastleProvider() {
    Provider previousProvider = removeBouncyCastleProvider();
    try {
      assertNull(TerraformCryptoProvider.current(true));
      assertNull(Security.getProvider(BouncyCastleProvider.PROVIDER_NAME));
    } finally {
      restoreBouncyCastleProvider(previousProvider);
    }
  }

  private static LegacySigningKey legacySigningKey(String passphrase) throws Exception {
    Provider provider = new BouncyCastleProvider();
    KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA", provider);
    generator.initialize(2048);
    PGPKeyPair pair = new JcaPGPKeyPair(
        org.bouncycastle.openpgp.PGPPublicKey.RSA_SIGN, generator.generateKeyPair(), new Date());
    PGPDigestCalculator sha1 = new JcaPGPDigestCalculatorProviderBuilder()
        .setProvider(provider).build().get(HashAlgorithmTags.SHA1);
    PGPSignatureSubpacketGenerator certification = new PGPSignatureSubpacketGenerator();
    certification.setKeyFlags(false, KeyFlags.CERTIFY_OTHER | KeyFlags.SIGN_DATA);
    PGPKeyRingGenerator rings = new PGPKeyRingGenerator(
        PGPSignature.POSITIVE_CERTIFICATION,
        pair,
        "Legacy Terraform key <terraform@kkrepo.test>",
        sha1,
        certification.generate(),
        null,
        new JcaPGPContentSignerBuilder(pair.getPublicKey().getAlgorithm(), HashAlgorithmTags.SHA256)
            .setProvider(provider),
        new JcePBESecretKeyEncryptorBuilder(SymmetricKeyAlgorithmTags.CAST5, sha1)
            .setProvider(provider).build(passphrase.toCharArray()));
    ByteArrayOutputStream publicBytes = new ByteArrayOutputStream();
    try (ArmoredOutputStream armor = new ArmoredOutputStream(publicBytes)) {
      rings.generatePublicKeyRing().encode(armor);
    }
    ByteArrayOutputStream privateBytes = new ByteArrayOutputStream();
    try (ArmoredOutputStream armor = new ArmoredOutputStream(privateBytes)) {
      rings.generateSecretKeyRing().encode(armor);
    }
    return new LegacySigningKey(
        pair,
        publicBytes.toString(StandardCharsets.UTF_8),
        privateBytes.toString(StandardCharsets.UTF_8),
        passphrase);
  }

  private static byte[] detachedSignature(byte[] contents, PGPKeyPair keyPair, int hashAlgorithm)
      throws Exception {
    PGPSignatureGenerator generator = new PGPSignatureGenerator(
        new JcaPGPContentSignerBuilder(keyPair.getPublicKey().getAlgorithm(), hashAlgorithm)
            .setProvider(new BouncyCastleProvider()));
    generator.init(PGPSignature.BINARY_DOCUMENT, keyPair.getPrivateKey());
    generator.update(contents);
    return generator.generate().getEncoded();
  }

  private static Provider removeBouncyCastleProvider() {
    Provider previous = Security.getProvider(BouncyCastleProvider.PROVIDER_NAME);
    Security.removeProvider(BouncyCastleProvider.PROVIDER_NAME);
    return previous;
  }

  private static void restoreBouncyCastleProvider(Provider previous) {
    Security.removeProvider(BouncyCastleProvider.PROVIDER_NAME);
    if (previous != null) Security.addProvider(previous);
  }

  private record LegacySigningKey(
      PGPKeyPair keyPair, String publicArmor, String privateArmor, String passphrase) {}
}
