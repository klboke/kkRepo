package com.github.klboke.kkrepo.server.apt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.core.RepositoryType;
import com.github.klboke.kkrepo.core.security.OpenPgpKeyIds;
import com.github.klboke.kkrepo.persistence.jdbc.api.AptRegistryDao;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntime;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyPairGenerator;
import java.security.Provider;
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
import org.bouncycastle.openpgp.PGPPublicKey;
import org.bouncycastle.openpgp.PGPPublicKeyRingCollection;
import org.bouncycastle.openpgp.PGPSignature;
import org.bouncycastle.openpgp.PGPSignatureList;
import org.bouncycastle.openpgp.PGPSignatureSubpacketGenerator;
import org.bouncycastle.openpgp.PGPUtil;
import org.bouncycastle.openpgp.operator.PGPDigestCalculator;
import org.bouncycastle.openpgp.operator.jcajce.JcaKeyFingerprintCalculator;
import org.bouncycastle.openpgp.operator.jcajce.JcaPGPContentSignerBuilder;
import org.bouncycastle.openpgp.operator.jcajce.JcaPGPDigestCalculatorProviderBuilder;
import org.bouncycastle.openpgp.operator.jcajce.JcaPGPKeyPair;
import org.bouncycastle.openpgp.operator.jcajce.JcaPGPContentVerifierBuilderProvider;
import org.bouncycastle.openpgp.operator.jcajce.JcePBESecretKeyEncryptorBuilder;
import org.junit.jupiter.api.Test;

class AptSigningServiceTest {

  @Test
  void lazilyGeneratesRotatesAndSignsReleaseMetadata() throws Exception {
    AtomicReference<AptRegistryDao.SigningKey> stored = new AtomicReference<>();
    AptRegistryDao registry = registry(stored);
    AptSigningService service = new AptSigningService(registry, new AptLeaseManager(registry));
    RepositoryRuntime runtime = runtime();

    AptSigningService.SigningMaterial first = service.active(runtime);
    assertEquals(1, first.revision());
    assertTrue(first.publicArmor().contains("BEGIN PGP PUBLIC KEY BLOCK"));
    assertTrue(first.privateArmor().contains("BEGIN PGP PRIVATE KEY BLOCK"));
    assertEquals(first.keyId(), OpenPgpKeyIds.format(
        publicKeys(first.publicArmor()).getKeyRings().next().getPublicKey().getKeyID()));

    byte[] release = "Suite: stable  \r\nCodename: stable\nDescription: demo\t\n"
        .getBytes(StandardCharsets.UTF_8);
    AptSigningService.SignedRelease signed = service.sign(release, first, Instant.EPOCH);
    assertTrue(new String(signed.inRelease(), StandardCharsets.UTF_8)
        .contains("BEGIN PGP SIGNED MESSAGE"));
    assertTrue(new String(signed.detachedSignature(), StandardCharsets.UTF_8)
        .contains("BEGIN PGP SIGNATURE"));
    verifyDetached(release, signed.detachedSignature(), first.publicArmor());

    AptRegistryDao.SigningKey generated = service.rotateGenerated(runtime);
    assertEquals(2, generated.revision());
    assertNotEquals(first.fingerprint(), generated.fingerprint());
    AptSigningService.SigningMaterial second = service.active(runtime);
    assertEquals(generated.fingerprint(), second.fingerprint());
    verifyDetached(release, service.sign(release, second, null).detachedSignature(),
        second.publicArmor());
    assertThrows(IllegalArgumentException.class,
        () -> service.sign(null, second, Instant.now()));
  }

  @Test
  void importsPassphraseProtectedKeyAndRejectsMalformedMaterial() throws Exception {
    AtomicReference<AptRegistryDao.SigningKey> stored = new AtomicReference<>();
    AptRegistryDao registry = registry(stored);
    AptSigningService service = new AptSigningService(registry, new AptLeaseManager(registry));
    LegacyKey key = legacyKey("secret");

    assertThrows(IllegalArgumentException.class,
        () -> service.rotate(runtime(), " ", null));
    assertThrows(IllegalArgumentException.class,
        () -> service.rotate(runtime(), "not a key", ""));
    assertThrows(IllegalArgumentException.class,
        () -> service.rotate(runtime(), key.privateArmor(), "wrong"));

    AptRegistryDao.SigningKey imported = service.rotate(runtime(), key.privateArmor(), "secret");
    assertEquals(OpenPgpKeyIds.format(key.pair().getKeyID()), imported.keyId());
    assertEquals(1, imported.revision());
    AptSigningService.SigningMaterial material = service.active(runtime());
    byte[] release = "Suite: stable\n".getBytes(StandardCharsets.UTF_8);
    verifyDetached(release, service.sign(release, material, Instant.now()).detachedSignature(),
        material.publicArmor());

    AptSigningService.SigningMaterial malformed = new AptSigningService.SigningMaterial(
        1, "key", "fingerprint", "public", "not a key", "");
    IllegalStateException failure = assertThrows(IllegalStateException.class,
        () -> service.sign(release, malformed, Instant.now()));
    assertTrue(failure.getMessage().contains("Failed to sign APT Release metadata"));
  }

  private static AptRegistryDao registry(AtomicReference<AptRegistryDao.SigningKey> stored) {
    AptRegistryDao registry = mock(AptRegistryDao.class);
    when(registry.findActiveSigningKey(anyLong()))
        .thenAnswer(ignored -> Optional.ofNullable(stored.get()));
    when(registry.tryAcquireLease(anyString(), anyString(), any(), any())).thenAnswer(invocation ->
        Optional.of(new AptRegistryDao.Lease(
            invocation.getArgument(0), invocation.getArgument(1), 1, 1,
            invocation.getArgument(3), invocation.getArgument(2))));
    when(registry.renewLease(anyString(), anyString(), anyLong(), any(), any())).thenReturn(true);
    doAnswer(invocation -> {
      stored.set(invocation.getArgument(0));
      return null;
    }).when(registry).insertSigningKey(any());
    return registry;
  }

  private static RepositoryRuntime runtime() {
    return new RepositoryRuntime(
        7, "apt-hosted", RepositoryFormat.APT, RepositoryType.HOSTED, "apt-hosted", true, 1L,
        "ALLOW", null, null, true, null, null, null, null, null, List.of());
  }

  private static PGPPublicKeyRingCollection publicKeys(String armor) throws Exception {
    return new PGPPublicKeyRingCollection(
        PGPUtil.getDecoderStream(new ByteArrayInputStream(armor.getBytes(StandardCharsets.UTF_8))),
        new JcaKeyFingerprintCalculator());
  }

  private static void verifyDetached(byte[] contents, byte[] detached, String publicArmor)
      throws Exception {
    PGPObjectFactory objects = new PGPObjectFactory(
        PGPUtil.getDecoderStream(new ByteArrayInputStream(detached)),
        new JcaKeyFingerprintCalculator());
    PGPSignature signature = ((PGPSignatureList) objects.nextObject()).get(0);
    PGPPublicKey publicKey = publicKeys(publicArmor).getPublicKey(signature.getKeyID());
    signature.init(new JcaPGPContentVerifierBuilderProvider()
        .setProvider(AptCryptoProvider.current()), publicKey);
    signature.update(contents);
    assertTrue(signature.verify());
  }

  private static LegacyKey legacyKey(String passphrase) throws Exception {
    Provider provider = new BouncyCastleProvider();
    KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA", provider);
    generator.initialize(2048);
    PGPKeyPair pair = new JcaPGPKeyPair(PGPPublicKey.RSA_SIGN,
        generator.generateKeyPair(), new Date());
    PGPDigestCalculator sha1 = new JcaPGPDigestCalculatorProviderBuilder()
        .setProvider(provider).build().get(HashAlgorithmTags.SHA1);
    PGPSignatureSubpacketGenerator certification = new PGPSignatureSubpacketGenerator();
    certification.setKeyFlags(false, KeyFlags.CERTIFY_OTHER | KeyFlags.SIGN_DATA);
    PGPKeyRingGenerator rings = new PGPKeyRingGenerator(
        PGPSignature.POSITIVE_CERTIFICATION,
        pair,
        "APT test key <apt@kkrepo.test>",
        sha1,
        certification.generate(),
        null,
        new JcaPGPContentSignerBuilder(pair.getPublicKey().getAlgorithm(), HashAlgorithmTags.SHA256)
            .setProvider(provider),
        new JcePBESecretKeyEncryptorBuilder(SymmetricKeyAlgorithmTags.CAST5, sha1)
            .setProvider(provider).build(passphrase.toCharArray()));
    ByteArrayOutputStream privateBytes = new ByteArrayOutputStream();
    try (ArmoredOutputStream armor = new ArmoredOutputStream(privateBytes)) {
      rings.generateSecretKeyRing().encode(armor);
    }
    return new LegacyKey(pair, privateBytes.toString(StandardCharsets.UTF_8));
  }

  private record LegacyKey(PGPKeyPair pair, String privateArmor) { }
}
