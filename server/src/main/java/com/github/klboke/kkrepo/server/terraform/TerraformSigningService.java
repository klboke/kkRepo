package com.github.klboke.kkrepo.server.terraform;

import com.github.klboke.kkrepo.core.security.EncryptionSecrets;
import com.github.klboke.kkrepo.core.security.OpenPgpKeyIds;
import com.github.klboke.kkrepo.core.security.SecretCipher;
import com.github.klboke.kkrepo.core.security.TerraformSigningKeyMaterial;
import com.github.klboke.kkrepo.persistence.jdbc.api.TerraformRegistryDao;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntime;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import java.security.Security;
import java.time.Instant;
import java.util.Date;
import java.util.Iterator;
import org.bouncycastle.bcpg.ArmoredOutputStream;
import org.bouncycastle.bcpg.HashAlgorithmTags;
import org.bouncycastle.bcpg.SymmetricKeyAlgorithmTags;
import org.bouncycastle.bcpg.sig.KeyFlags;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.openpgp.PGPKeyPair;
import org.bouncycastle.openpgp.PGPKeyRingGenerator;
import org.bouncycastle.openpgp.PGPPrivateKey;
import org.bouncycastle.openpgp.PGPPublicKey;
import org.bouncycastle.openpgp.PGPSecretKey;
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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/** Generates repository-scoped OpenPGP keys and creates detached SHA256SUMS signatures. */
@Service
final class TerraformSigningService {
  static {
    if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
      Security.addProvider(new BouncyCastleProvider());
    }
  }

  private final TerraformRegistryDao registry;
  private final TerraformPublishLeaseManager leases;

  TerraformSigningService(TerraformRegistryDao registry) {
    this(registry, new TerraformPublishLeaseManager(registry));
  }

  @Autowired
  TerraformSigningService(TerraformRegistryDao registry, TerraformPublishLeaseManager leases) {
    this.registry = registry;
    this.leases = leases;
  }

  SigningMaterial active(RepositoryRuntime runtime) {
    TerraformRegistryDao.SigningKey row = registry.findActiveSigningKey(runtime.id()).orElse(null);
    if (row == null) {
      String leaseKey = "signing-key:" + runtime.id();
      try (TerraformPublishLeaseManager.Lease ignored = leases.acquire(
          leaseKey, java.time.Duration.ofMinutes(2), java.time.Duration.ofSeconds(30))) {
        row = registry.findActiveSigningKey(runtime.id()).orElseGet(() -> create(runtime));
      }
    }
    String decrypted = new SecretCipher(EncryptionSecrets.credentialSecret())
        .decrypt(row.encryptedPrivateKey());
    TerraformSigningKeyMaterial.Material privateKey = TerraformSigningKeyMaterial.decode(decrypted);
    return new SigningMaterial(
        row.revision(), row.keyId(), row.publicKey(), privateKey.privateKeyArmor(), privateKey.passphrase());
  }

  TerraformRegistryDao.SigningKey create(RepositoryRuntime runtime) {
    TerraformRegistryDao.SigningKey current = registry.findActiveSigningKey(runtime.id()).orElse(null);
    if (current != null) return current;
    Generated generated = generate(runtime.name());
    String encrypted = new SecretCipher(EncryptionSecrets.credentialSecret()).encrypt(
        TerraformSigningKeyMaterial.encode(generated.privateArmor(), ""));
    TerraformRegistryDao.SigningKey row = new TerraformRegistryDao.SigningKey(
        runtime.id(), 1, generated.keyId(), encrypted, generated.publicArmor(), Instant.now());
    try {
      registry.insertSigningKey(row);
      return row;
    } catch (DuplicateKeyException race) {
      return registry.findActiveSigningKey(runtime.id()).orElseThrow(() -> race);
    }
  }

  byte[] sign(byte[] content, SigningMaterial material) {
    try {
      PGPSecretKeyRingCollection rings = new PGPSecretKeyRingCollection(
          PGPUtil.getDecoderStream(new ByteArrayInputStream(material.privateArmor().getBytes(java.nio.charset.StandardCharsets.UTF_8))),
          new JcaKeyFingerprintCalculator());
      PGPSecretKey signing = null;
      Iterator<org.bouncycastle.openpgp.PGPSecretKeyRing> ringIterator = rings.getKeyRings();
      while (ringIterator.hasNext() && signing == null) {
        Iterator<PGPSecretKey> keys = ringIterator.next().getSecretKeys();
        while (keys.hasNext()) {
          PGPSecretKey candidate = keys.next();
          if (candidate.isSigningKey()) { signing = candidate; break; }
        }
      }
      if (signing == null) throw new IllegalStateException("Terraform signing key cannot sign");
      PGPPrivateKey privateKey = signing.extractPrivateKey(
          new JcePBESecretKeyDecryptorBuilder().setProvider(BouncyCastleProvider.PROVIDER_NAME)
              .build(material.passphrase().toCharArray()));
      PGPSignatureGenerator generator = new PGPSignatureGenerator(
          new JcaPGPContentSignerBuilder(signing.getPublicKey().getAlgorithm(), HashAlgorithmTags.SHA256)
              .setProvider(BouncyCastleProvider.PROVIDER_NAME));
      generator.init(PGPSignature.BINARY_DOCUMENT, privateKey);
      generator.update(content);
      return generator.generate().getEncoded();
    } catch (Exception e) {
      throw new IllegalStateException("Failed to sign Terraform provider checksums", e);
    }
  }

  private static Generated generate(String repositoryName) {
    try {
      KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA", BouncyCastleProvider.PROVIDER_NAME);
      generator.initialize(3072, new SecureRandom());
      PGPKeyPair pair = new JcaPGPKeyPair(PGPPublicKey.RSA_SIGN, generator.generateKeyPair(), new Date());
      PGPDigestCalculator sha1 = new JcaPGPDigestCalculatorProviderBuilder()
          .setProvider(BouncyCastleProvider.PROVIDER_NAME).build().get(HashAlgorithmTags.SHA1);
      PGPSignatureSubpacketGenerator certification = new PGPSignatureSubpacketGenerator();
      certification.setKeyFlags(false, KeyFlags.CERTIFY_OTHER | KeyFlags.SIGN_DATA);
      PGPKeyRingGenerator rings = new PGPKeyRingGenerator(
          PGPSignature.POSITIVE_CERTIFICATION,
          pair,
          "kkrepo Terraform <terraform@" + repositoryName + ">",
          sha1,
          certification.generate(),
          null,
          new JcaPGPContentSignerBuilder(pair.getPublicKey().getAlgorithm(), HashAlgorithmTags.SHA256)
              .setProvider(BouncyCastleProvider.PROVIDER_NAME),
          new JcePBESecretKeyEncryptorBuilder(SymmetricKeyAlgorithmTags.AES_256, sha1)
              .setProvider(BouncyCastleProvider.PROVIDER_NAME).build(new char[0]));
      ByteArrayOutputStream publicBytes = new ByteArrayOutputStream();
      try (ArmoredOutputStream armor = new ArmoredOutputStream(publicBytes)) {
        rings.generatePublicKeyRing().encode(armor);
      }
      ByteArrayOutputStream privateBytes = new ByteArrayOutputStream();
      try (ArmoredOutputStream armor = new ArmoredOutputStream(privateBytes)) {
        rings.generateSecretKeyRing().encode(armor);
      }
      String keyId = OpenPgpKeyIds.format(pair.getKeyID());
      return new Generated(keyId, publicBytes.toString(java.nio.charset.StandardCharsets.UTF_8),
          privateBytes.toString(java.nio.charset.StandardCharsets.UTF_8));
    } catch (Exception e) {
      throw new IllegalStateException("Failed to generate Terraform repository signing key", e);
    }
  }

  record SigningMaterial(
      int revision, String keyId, String publicArmor, String privateArmor, String passphrase) {}
  private record Generated(String keyId, String publicArmor, String privateArmor) {}
}
