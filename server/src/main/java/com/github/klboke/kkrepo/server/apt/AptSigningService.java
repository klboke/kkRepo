package com.github.klboke.kkrepo.server.apt;

import com.github.klboke.kkrepo.core.security.EncryptionSecrets;
import com.github.klboke.kkrepo.core.security.OpenPgpKeyIds;
import com.github.klboke.kkrepo.core.security.SecretCipher;
import com.github.klboke.kkrepo.core.security.TerraformSigningKeyMaterial;
import com.github.klboke.kkrepo.persistence.jdbc.api.AptRegistryDao;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntime;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.security.KeyPairGenerator;
import java.security.Provider;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.HexFormat;
import java.util.Iterator;
import org.bouncycastle.bcpg.ArmoredOutputStream;
import org.bouncycastle.bcpg.BCPGOutputStream;
import org.bouncycastle.bcpg.HashAlgorithmTags;
import org.bouncycastle.bcpg.SymmetricKeyAlgorithmTags;
import org.bouncycastle.bcpg.sig.KeyFlags;
import org.bouncycastle.openpgp.PGPKeyPair;
import org.bouncycastle.openpgp.PGPKeyRingGenerator;
import org.bouncycastle.openpgp.PGPPrivateKey;
import org.bouncycastle.openpgp.PGPPublicKey;
import org.bouncycastle.openpgp.PGPSecretKey;
import org.bouncycastle.openpgp.PGPSecretKeyRing;
import org.bouncycastle.openpgp.PGPSecretKeyRingCollection;
import org.bouncycastle.openpgp.PGPSignature;
import org.bouncycastle.openpgp.PGPSignatureGenerator;
import org.bouncycastle.openpgp.PGPSignatureSubpacketGenerator;
import org.bouncycastle.openpgp.PGPUtil;
import org.bouncycastle.openpgp.operator.PGPDigestCalculator;
import org.bouncycastle.openpgp.operator.jcajce.JcaKeyFingerprintCalculator;
import org.bouncycastle.openpgp.operator.jcajce.JcaPGPContentSignerBuilder;
import org.bouncycastle.openpgp.operator.jcajce.JcaPGPDigestCalculatorProviderBuilder;
import org.bouncycastle.openpgp.operator.jcajce.JcaPGPKeyPair;
import org.bouncycastle.openpgp.operator.jcajce.JcePBESecretKeyDecryptorBuilder;
import org.bouncycastle.openpgp.operator.jcajce.JcePBESecretKeyEncryptorBuilder;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

/** Repository-scoped OpenPGP key lifecycle and APT clear/detached signatures. */
@Service
final class AptSigningService {
  static {
    AptCryptoProvider.current();
  }

  private final AptRegistryDao registry;
  private final AptLeaseManager leases;

  AptSigningService(AptRegistryDao registry, AptLeaseManager leases) {
    this.registry = registry;
    this.leases = leases;
  }

  SigningMaterial active(RepositoryRuntime runtime) {
    AptRegistryDao.SigningKey row = registry.findActiveSigningKey(runtime.id()).orElse(null);
    if (row == null) {
      try (AptLeaseManager.Lease lease = leases.acquire("apt:key:" + runtime.id())) {
        lease.assertHeld();
        row = registry.findActiveSigningKey(runtime.id()).orElseGet(() -> create(runtime));
      }
    }
    return material(row);
  }

  AptRegistryDao.SigningKey rotate(
      RepositoryRuntime runtime, String privateKeyArmor, String passphrase) {
    if (privateKeyArmor == null || privateKeyArmor.isBlank()) {
      throw new IllegalArgumentException("APT private signing key is required");
    }
    try (AptLeaseManager.Lease lease = leases.acquire("apt:key:" + runtime.id())) {
      lease.assertHeld();
      ParsedKey parsed = parse(privateKeyArmor, passphrase == null ? "" : passphrase);
      int revision = registry.findActiveSigningKey(runtime.id())
          .map(key -> key.revision() + 1).orElse(1);
      String encrypted = cipher().encrypt(TerraformSigningKeyMaterial.encode(
          privateKeyArmor, passphrase == null ? "" : passphrase));
      AptRegistryDao.SigningKey row = new AptRegistryDao.SigningKey(
          runtime.id(), revision, parsed.keyId(), parsed.fingerprint(), encrypted,
          parsed.publicArmor(), true, Instant.now());
      registry.insertSigningKey(row);
      return row;
    }
  }

  AptRegistryDao.SigningKey rotateGenerated(RepositoryRuntime runtime) {
    try (AptLeaseManager.Lease lease = leases.acquire("apt:key:" + runtime.id())) {
      lease.assertHeld();
      Generated generated = generate(runtime.name());
      int revision = registry.findActiveSigningKey(runtime.id())
          .map(key -> key.revision() + 1).orElse(1);
      String encrypted = cipher().encrypt(TerraformSigningKeyMaterial.encode(
          generated.privateArmor(), ""));
      AptRegistryDao.SigningKey row = new AptRegistryDao.SigningKey(
          runtime.id(), revision, generated.keyId(), generated.fingerprint(), encrypted,
          generated.publicArmor(), true, Instant.now());
      registry.insertSigningKey(row);
      return row;
    }
  }

  SignedRelease sign(byte[] release, SigningMaterial material, Instant createdAt) {
    if (release == null) throw new IllegalArgumentException("APT Release bytes are required");
    try {
      Key key = signingKey(material);
      Instant signatureTime = createdAt == null ? Instant.now() : createdAt;
      Instant keyCreatedAt = key.publicKey().getCreationTime().toInstant();
      if (signatureTime.isBefore(keyCreatedAt)) signatureTime = keyCreatedAt;
      requireUsableAt(key.publicKey(), signatureTime);
      byte[] detached = detached(release, key, signatureTime);
      byte[] inRelease = clearSign(release, key, signatureTime);
      return new SignedRelease(inRelease, detached);
    } catch (Exception error) {
      throw new IllegalStateException("Failed to sign APT Release metadata", error);
    }
  }

  private AptRegistryDao.SigningKey create(RepositoryRuntime runtime) {
    AptRegistryDao.SigningKey current = registry.findActiveSigningKey(runtime.id()).orElse(null);
    if (current != null) return current;
    Generated generated = generate(runtime.name());
    String encrypted = cipher().encrypt(TerraformSigningKeyMaterial.encode(
        generated.privateArmor(), ""));
    AptRegistryDao.SigningKey row = new AptRegistryDao.SigningKey(
        runtime.id(), 1, generated.keyId(), generated.fingerprint(), encrypted,
        generated.publicArmor(), true, Instant.now());
    try {
      registry.insertSigningKey(row);
      return row;
    } catch (DuplicateKeyException race) {
      return registry.findActiveSigningKey(runtime.id()).orElseThrow(() -> race);
    }
  }

  private SigningMaterial material(AptRegistryDao.SigningKey row) {
    String decrypted = cipher().decrypt(row.encryptedPrivateKey());
    TerraformSigningKeyMaterial.Material material = TerraformSigningKeyMaterial.decode(decrypted);
    return new SigningMaterial(
        row.revision(), row.keyId(), row.fingerprint(), row.publicKey(),
        material.privateKeyArmor(), material.passphrase());
  }

  private static Key signingKey(SigningMaterial material) throws Exception {
    PGPSecretKeyRingCollection rings = new PGPSecretKeyRingCollection(
        PGPUtil.getDecoderStream(new ByteArrayInputStream(
            material.privateArmor().getBytes(java.nio.charset.StandardCharsets.UTF_8))),
        new JcaKeyFingerprintCalculator());
    Iterator<PGPSecretKeyRing> ringIterator = rings.getKeyRings();
    while (ringIterator.hasNext()) {
      Iterator<PGPSecretKey> keys = ringIterator.next().getSecretKeys();
      while (keys.hasNext()) {
        PGPSecretKey candidate = keys.next();
        if (!candidate.isSigningKey() || candidate.getPublicKey().isRevoked()) continue;
        requireUsableAt(candidate.getPublicKey(), Instant.now());
        Provider provider = AptCryptoProvider.current();
        JcePBESecretKeyDecryptorBuilder decryptor = new JcePBESecretKeyDecryptorBuilder();
        if (provider != null) decryptor.setProvider(provider);
        PGPPrivateKey privateKey = candidate.extractPrivateKey(
            decryptor.build(material.passphrase().toCharArray()));
        return new Key(candidate.getPublicKey(), privateKey);
      }
    }
    throw new IllegalArgumentException("APT private key does not contain a usable signing key");
  }

  private static byte[] detached(byte[] release, Key key, Instant createdAt) throws Exception {
    PGPSignatureGenerator generator = generator(key, PGPSignature.BINARY_DOCUMENT, createdAt);
    generator.update(release);
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    try (ArmoredOutputStream armor = new ArmoredOutputStream(output)) {
      generator.generate().encode(armor);
    }
    return output.toByteArray();
  }

  private static byte[] clearSign(byte[] release, Key key, Instant createdAt) throws Exception {
    PGPSignatureGenerator generator = generator(
        key, PGPSignature.CANONICAL_TEXT_DOCUMENT, createdAt);
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    try (ArmoredOutputStream armor = new ArmoredOutputStream(output)) {
      armor.beginClearText(HashAlgorithmTags.SHA256);
      armor.write(release);
      updateCleartextSignature(generator, release);
      armor.endClearText();
      try (BCPGOutputStream packets = new BCPGOutputStream(armor)) {
        generator.generate().encode(packets);
      }
    }
    return output.toByteArray();
  }

  /** RFC 4880 cleartext signatures omit trailing whitespace and the final line ending. */
  private static void updateCleartextSignature(
      PGPSignatureGenerator generator, byte[] cleartext) {
    int offset = 0;
    boolean firstLine = true;
    while (offset < cleartext.length) {
      int end = offset;
      while (end < cleartext.length && cleartext[end] != '\r' && cleartext[end] != '\n') end++;
      if (!firstLine) {
        generator.update((byte) '\r');
        generator.update((byte) '\n');
      }
      int contentEnd = end;
      while (contentEnd > offset
          && (cleartext[contentEnd - 1] == ' ' || cleartext[contentEnd - 1] == '\t')) {
        contentEnd--;
      }
      if (contentEnd > offset) generator.update(cleartext, offset, contentEnd - offset);
      firstLine = false;
      if (end == cleartext.length) break;
      if (cleartext[end] == '\r' && end + 1 < cleartext.length && cleartext[end + 1] == '\n') {
        offset = end + 2;
      } else {
        offset = end + 1;
      }
    }
  }

  private static PGPSignatureGenerator generator(
      Key key, int signatureType, Instant createdAt) throws Exception {
    Provider provider = AptCryptoProvider.current();
    JcaPGPContentSignerBuilder builder = new JcaPGPContentSignerBuilder(
        key.publicKey().getAlgorithm(), HashAlgorithmTags.SHA256);
    if (provider != null) builder.setProvider(provider);
    PGPSignatureGenerator generator = new PGPSignatureGenerator(builder);
    generator.init(signatureType, key.privateKey());
    PGPSignatureSubpacketGenerator packets = new PGPSignatureSubpacketGenerator();
    packets.setSignatureCreationTime(false, Date.from(createdAt));
    generator.setHashedSubpackets(packets.generate());
    return generator;
  }

  private static ParsedKey parse(String privateArmor, String passphrase) {
    try {
      PGPSecretKeyRingCollection rings = new PGPSecretKeyRingCollection(
          PGPUtil.getDecoderStream(new ByteArrayInputStream(
              privateArmor.getBytes(java.nio.charset.StandardCharsets.UTF_8))),
          new JcaKeyFingerprintCalculator());
      ArrayList<PGPSecretKeyRing> ringList = new ArrayList<>();
      rings.getKeyRings().forEachRemaining(ringList::add);
      if (ringList.isEmpty()) throw new IllegalArgumentException("APT private key ring is empty");
      PGPSecretKey signing = null;
      for (PGPSecretKeyRing ring : ringList) {
        Iterator<PGPSecretKey> keys = ring.getSecretKeys();
        while (keys.hasNext()) {
          PGPSecretKey candidate = keys.next();
          if (candidate.isSigningKey() && !candidate.getPublicKey().isRevoked()) {
            signing = candidate;
            break;
          }
        }
        if (signing != null) break;
      }
      if (signing == null) throw new IllegalArgumentException("APT key cannot sign");
      requireUsableAt(signing.getPublicKey(), Instant.now());
      SigningMaterial probe = new SigningMaterial(
          0, OpenPgpKeyIds.format(signing.getKeyID()),
          HexFormat.of().withUpperCase().formatHex(signing.getPublicKey().getFingerprint()),
          "", privateArmor, passphrase);
      signingKey(probe);
      ByteArrayOutputStream publicBytes = new ByteArrayOutputStream();
      try (ArmoredOutputStream armor = new ArmoredOutputStream(publicBytes)) {
        for (PGPSecretKeyRing ring : ringList) ring.toCertificate().encode(armor);
      }
      return new ParsedKey(
          OpenPgpKeyIds.format(signing.getKeyID()),
          HexFormat.of().withUpperCase().formatHex(signing.getPublicKey().getFingerprint()),
          publicBytes.toString(java.nio.charset.StandardCharsets.UTF_8));
    } catch (IllegalArgumentException error) {
      throw error;
    } catch (Exception error) {
      throw new IllegalArgumentException("Invalid APT OpenPGP private key", error);
    }
  }

  private static Generated generate(String repositoryName) {
    try {
      Provider provider = AptCryptoProvider.current();
      KeyPairGenerator rsa = provider == null
          ? KeyPairGenerator.getInstance("RSA")
          : KeyPairGenerator.getInstance("RSA", provider);
      rsa.initialize(3072, new SecureRandom());
      PGPKeyPair pair = new JcaPGPKeyPair(
          PGPPublicKey.RSA_SIGN, rsa.generateKeyPair(), new Date());
      JcaPGPDigestCalculatorProviderBuilder digestBuilder =
          new JcaPGPDigestCalculatorProviderBuilder();
      if (provider != null) digestBuilder.setProvider(provider);
      PGPDigestCalculator sha1 = digestBuilder.build().get(HashAlgorithmTags.SHA1);
      PGPSignatureSubpacketGenerator certification = new PGPSignatureSubpacketGenerator();
      certification.setKeyFlags(false, KeyFlags.CERTIFY_OTHER | KeyFlags.SIGN_DATA);
      JcaPGPContentSignerBuilder signer = new JcaPGPContentSignerBuilder(
          pair.getPublicKey().getAlgorithm(), HashAlgorithmTags.SHA256);
      JcePBESecretKeyEncryptorBuilder encryptor = new JcePBESecretKeyEncryptorBuilder(
          SymmetricKeyAlgorithmTags.AES_256, sha1);
      if (provider != null) {
        signer.setProvider(provider);
        encryptor.setProvider(provider);
      }
      PGPKeyRingGenerator rings = new PGPKeyRingGenerator(
          PGPSignature.POSITIVE_CERTIFICATION,
          pair,
          "kkRepo APT <apt@" + repositoryName + ">",
          sha1,
          certification.generate(),
          null,
          signer,
          encryptor.build(new char[0]));
      ByteArrayOutputStream publicBytes = new ByteArrayOutputStream();
      try (ArmoredOutputStream armor = new ArmoredOutputStream(publicBytes)) {
        rings.generatePublicKeyRing().encode(armor);
      }
      ByteArrayOutputStream privateBytes = new ByteArrayOutputStream();
      try (ArmoredOutputStream armor = new ArmoredOutputStream(privateBytes)) {
        rings.generateSecretKeyRing().encode(armor);
      }
      String keyId = OpenPgpKeyIds.format(pair.getKeyID());
      String fingerprint = HexFormat.of().withUpperCase().formatHex(
          pair.getPublicKey().getFingerprint());
      return new Generated(
          keyId, fingerprint,
          publicBytes.toString(java.nio.charset.StandardCharsets.UTF_8),
          privateBytes.toString(java.nio.charset.StandardCharsets.UTF_8));
    } catch (Exception error) {
      throw new IllegalStateException("Failed to generate APT repository signing key", error);
    }
  }

  private static void requireUsableAt(PGPPublicKey key, Instant instant) {
    Instant createdAt = key.getCreationTime().toInstant();
    if (createdAt.isAfter(instant.plusSeconds(300))) {
      throw new IllegalArgumentException("APT signing key creation time is in the future");
    }
    long validSeconds = key.getValidSeconds();
    if (validSeconds > 0 && !instant.isBefore(createdAt.plusSeconds(validSeconds))) {
      throw new IllegalArgumentException("APT signing key is expired");
    }
    if (key.isRevoked()) throw new IllegalArgumentException("APT signing key is revoked");
  }

  private static SecretCipher cipher() {
    return new SecretCipher(EncryptionSecrets.credentialSecret());
  }

  record SigningMaterial(
      int revision,
      String keyId,
      String fingerprint,
      String publicArmor,
      String privateArmor,
      String passphrase) { }

  record SignedRelease(byte[] inRelease, byte[] detachedSignature) { }

  private record Key(PGPPublicKey publicKey, PGPPrivateKey privateKey) { }

  private record ParsedKey(String keyId, String fingerprint, String publicArmor) { }

  private record Generated(
      String keyId, String fingerprint, String publicArmor, String privateArmor) { }
}
