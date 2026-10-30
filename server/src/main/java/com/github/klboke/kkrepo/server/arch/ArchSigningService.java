package com.github.klboke.kkrepo.server.arch;

import com.github.klboke.kkrepo.cache.LocalCache;
import com.github.klboke.kkrepo.cache.LocalCacheFactory;
import com.github.klboke.kkrepo.core.security.EncryptionSecrets;
import com.github.klboke.kkrepo.core.security.OpenPgpKeyIds;
import com.github.klboke.kkrepo.core.security.SecretCipher;
import com.github.klboke.kkrepo.core.security.TerraformSigningKeyMaterial;
import com.github.klboke.kkrepo.persistence.jdbc.api.ArchSigningKeyDao;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntime;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyPairGenerator;
import java.security.Provider;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import org.bouncycastle.bcpg.ArmoredOutputStream;
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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Repository-scoped OpenPGP identities and detached pacman database signatures.
 *
 * <p>The private key is encrypted with the shared credential secret before it reaches the
 * database. Every replica reads the same active revision, so a signature served by one replica is
 * verifiable against the key served by any other replica.
 */
@Service
public class ArchSigningService {
  static {
    ArchCryptoProvider.current();
  }

  private final ArchSigningKeyDao keys;
  // The parsed private key is node-local, rebuildable state keyed by the durable key revision.
  private final LocalCache<String, SigningMaterial> materials = LocalCacheFactory.standard()
      .<String, SigningMaterial>builder("arch-signing-material")
      .maximumSize(256)
      .expireAfterAccess(java.time.Duration.ofMinutes(5))
      .build();

  public ArchSigningService(ArchSigningKeyDao keys) {
    this.keys = keys;
  }

  public Optional<SigningMaterial> active(RepositoryRuntime runtime) {
    return keys.findActiveSigningKey(runtime.id())
        .map(row -> materials.get(
            runtime.id() + ":" + row.revision(), ignored -> material(row)));
  }

  @Transactional
  public ArchSigningKeyDao.SigningKey rotate(
      RepositoryRuntime runtime, String privateKeyArmor, String passphrase) {
    if (privateKeyArmor == null || privateKeyArmor.isBlank()) {
      throw new IllegalArgumentException("Arch Linux private signing key is required");
    }
    ParsedKey parsed = parse(privateKeyArmor, passphrase == null ? "" : passphrase);
    int revision = keys.nextRevisionLocked(runtime.id());
    String encrypted = cipher().encrypt(TerraformSigningKeyMaterial.encode(
        privateKeyArmor, passphrase == null ? "" : passphrase));
    ArchSigningKeyDao.SigningKey row = new ArchSigningKeyDao.SigningKey(
        runtime.id(), revision, parsed.keyId(), parsed.fingerprint(), encrypted,
        parsed.publicArmor(), true, Instant.now());
    keys.insertSigningKey(row);
    return row;
  }

  @Transactional
  public ArchSigningKeyDao.SigningKey rotateGenerated(RepositoryRuntime runtime) {
    Generated generated = generate(runtime.name());
    int revision = keys.nextRevisionLocked(runtime.id());
    String encrypted = cipher().encrypt(TerraformSigningKeyMaterial.encode(
        generated.privateArmor(), ""));
    ArchSigningKeyDao.SigningKey row = new ArchSigningKeyDao.SigningKey(
        runtime.id(), revision, generated.keyId(), generated.fingerprint(), encrypted,
        generated.publicArmor(), true, Instant.now());
    keys.insertSigningKey(row);
    return row;
  }

  public List<ArchSigningKeyDao.SigningKey> retained(RepositoryRuntime runtime) {
    return keys.listSigningKeys(runtime.id(), 2);
  }

  public Optional<ArchSigningKeyDao.SigningKey> activeRow(RepositoryRuntime runtime) {
    return keys.findActiveSigningKey(runtime.id());
  }

  /** Signs bytes with the active key when one is configured. */
  public Optional<byte[]> signIfConfigured(
      RepositoryRuntime runtime, byte[] content, Instant createdAt) {
    return active(runtime).map(material -> sign(content, material, createdAt));
  }

  public byte[] sign(byte[] content, SigningMaterial material, Instant createdAt) {
    if (content == null) throw new IllegalArgumentException("Arch signing content is required");
    try {
      Key key = signingKey(material);
      Instant signatureTime = createdAt == null ? Instant.now() : createdAt;
      Instant keyCreatedAt = key.publicKey().getCreationTime().toInstant();
      if (signatureTime.isBefore(keyCreatedAt)) signatureTime = keyCreatedAt;
      requireUsableAt(key.publicKey(), signatureTime);
      PGPSignatureGenerator generator = generator(
          key, PGPSignature.BINARY_DOCUMENT, signatureTime);
      generator.update(content);
      ByteArrayOutputStream output = new ByteArrayOutputStream();
      try (ArmoredOutputStream armor = new ArmoredOutputStream(output)) {
        generator.generate().encode(armor);
      }
      return output.toByteArray();
    } catch (Exception error) {
      throw new IllegalStateException("Failed to sign pacman repository metadata", error);
    }
  }

  public String publicKeyArmor(RepositoryRuntime runtime) {
    if (keys.findActiveSigningKey(runtime.id()).isEmpty()) {
      throw new com.github.klboke.kkrepo.server.maven.MavenExceptions.MavenNotFoundException(
          "No Arch Linux signing key is configured");
    }
    List<ArchSigningKeyDao.SigningKey> retained = retained(runtime);
    if (retained.isEmpty()) {
      throw new com.github.klboke.kkrepo.server.maven.MavenExceptions.MavenNotFoundException(
          "No Arch Linux signing key is configured");
    }
    StringBuilder armor = new StringBuilder();
    for (ArchSigningKeyDao.SigningKey row : retained) {
      if (row.publicKey() == null || row.publicKey().isBlank()) continue;
      if (!armor.isEmpty() && armor.charAt(armor.length() - 1) != '\n') armor.append('\n');
      armor.append(row.publicKey());
    }
    return armor.toString();
  }

  public KeyStatus status(RepositoryRuntime runtime) {
    return activeRow(runtime)
        .map(row -> new KeyStatus(row.revision(), row.keyId(), row.fingerprint(),
            row.createdAt()))
        .orElse(null);
  }

  private SigningMaterial material(ArchSigningKeyDao.SigningKey row) {
    String decrypted = cipher().decrypt(row.encryptedPrivateKey());
    TerraformSigningKeyMaterial.Material material = TerraformSigningKeyMaterial.decode(decrypted);
    return new SigningMaterial(
        row.revision(), row.keyId(), row.fingerprint(), row.publicKey(),
        material.privateKeyArmor(), material.passphrase());
  }

  private static Key signingKey(SigningMaterial material) throws Exception {
    PGPSecretKeyRingCollection rings = new PGPSecretKeyRingCollection(
        PGPUtil.getDecoderStream(new ByteArrayInputStream(
            material.privateArmor().getBytes(StandardCharsets.UTF_8))),
        new JcaKeyFingerprintCalculator());
    Iterator<PGPSecretKeyRing> ringIterator = rings.getKeyRings();
    while (ringIterator.hasNext()) {
      Iterator<PGPSecretKey> candidates = ringIterator.next().getSecretKeys();
      while (candidates.hasNext()) {
        PGPSecretKey candidate = candidates.next();
        if (!candidate.isSigningKey() || candidate.getPublicKey().isRevoked()) continue;
        requireUsableAt(candidate.getPublicKey(), Instant.now());
        Provider provider = ArchCryptoProvider.current();
        JcePBESecretKeyDecryptorBuilder decryptor = new JcePBESecretKeyDecryptorBuilder();
        if (provider != null) decryptor.setProvider(provider);
        PGPPrivateKey privateKey = candidate.extractPrivateKey(
            decryptor.build(material.passphrase().toCharArray()));
        return new Key(candidate.getPublicKey(), privateKey);
      }
    }
    throw new IllegalArgumentException("Arch Linux private key has no usable signing key");
  }

  private static ParsedKey parse(String privateArmor, String passphrase) {
    try {
      PGPSecretKeyRingCollection rings = new PGPSecretKeyRingCollection(
          PGPUtil.getDecoderStream(new ByteArrayInputStream(
              privateArmor.getBytes(StandardCharsets.UTF_8))),
          new JcaKeyFingerprintCalculator());
      ArrayList<PGPSecretKeyRing> ringList = new ArrayList<>();
      rings.getKeyRings().forEachRemaining(ringList::add);
      if (ringList.isEmpty()) {
        throw new IllegalArgumentException("Arch Linux private key ring is empty");
      }
      PGPSecretKey signing = null;
      for (PGPSecretKeyRing ring : ringList) {
        Iterator<PGPSecretKey> candidates = ring.getSecretKeys();
        while (candidates.hasNext()) {
          PGPSecretKey candidate = candidates.next();
          if (candidate.isSigningKey() && !candidate.getPublicKey().isRevoked()) {
            signing = candidate;
            break;
          }
        }
        if (signing != null) break;
      }
      if (signing == null) throw new IllegalArgumentException("Arch Linux key cannot sign");
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
          publicBytes.toString(StandardCharsets.UTF_8));
    } catch (IllegalArgumentException error) {
      throw error;
    } catch (Exception error) {
      throw new IllegalArgumentException("Invalid Arch Linux OpenPGP private key", error);
    }
  }

  private static Generated generate(String repositoryName) {
    try {
      Provider provider = ArchCryptoProvider.current();
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
          "kkRepo Arch <arch@" + repositoryName + ">",
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
      return new Generated(
          OpenPgpKeyIds.format(pair.getKeyID()),
          HexFormat.of().withUpperCase().formatHex(pair.getPublicKey().getFingerprint()),
          publicBytes.toString(StandardCharsets.UTF_8),
          privateBytes.toString(StandardCharsets.UTF_8));
    } catch (Exception error) {
      throw new IllegalStateException("Failed to generate Arch Linux signing key", error);
    }
  }

  private static PGPSignatureGenerator generator(
      Key key, int signatureType, Instant createdAt) throws Exception {
    Provider provider = ArchCryptoProvider.current();
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

  private static void requireUsableAt(PGPPublicKey key, Instant instant) {
    Instant createdAt = key.getCreationTime().toInstant();
    if (createdAt.isAfter(instant.plusSeconds(300))) {
      throw new IllegalArgumentException("Arch Linux signing key creation time is in the future");
    }
    long validSeconds = key.getValidSeconds();
    if (validSeconds > 0 && !instant.isBefore(createdAt.plusSeconds(validSeconds))) {
      throw new IllegalArgumentException("Arch Linux signing key is expired");
    }
    if (key.isRevoked()) throw new IllegalArgumentException("Arch Linux signing key is revoked");
  }

  private static SecretCipher cipher() {
    return new SecretCipher(EncryptionSecrets.credentialSecret());
  }

  public record KeyStatus(int revision, String keyId, String fingerprint, Instant createdAt) {
  }

  record SigningMaterial(
      int revision,
      String keyId,
      String fingerprint,
      String publicArmor,
      String privateArmor,
      String passphrase) {
  }

  private record Key(PGPPublicKey publicKey, PGPPrivateKey privateKey) {
  }

  private record ParsedKey(String keyId, String fingerprint, String publicArmor) {
  }

  private record Generated(
      String keyId, String fingerprint, String publicArmor, String privateArmor) {
  }
}
