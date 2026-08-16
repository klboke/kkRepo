package com.github.klboke.kkrepo.server.alpine;

import com.github.klboke.kkrepo.core.security.EncryptionSecrets;
import com.github.klboke.kkrepo.core.security.SecretCipher;
import com.github.klboke.kkrepo.persistence.jdbc.api.AlpineRegistryDao;
import com.github.klboke.kkrepo.protocol.alpine.AlpineSignature;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntime;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

/** Encrypted repository-scoped PKCS#8 RSA key lifecycle and APK v2 signatures. */
@Service
final class AlpineSigningService {
  private final AlpineRegistryDao registry;
  private final AlpineLeaseManager leases;
  private final AlpineRepositorySettings settings;

  AlpineSigningService(
      AlpineRegistryDao registry,
      AlpineLeaseManager leases,
      AlpineRepositorySettings settings) {
    this.registry = registry;
    this.leases = leases;
    this.settings = settings;
  }

  SigningMaterial active(RepositoryRuntime runtime) {
    AlpineRegistryDao.SigningKey row = registry.findActiveSigningKey(runtime.id()).orElse(null);
    if (row == null) {
      try (AlpineLeaseManager.Lease lease = leases.acquire("alpine:key:" + runtime.id())) {
        lease.assertHeld();
        row = registry.findActiveSigningKey(runtime.id()).orElseGet(() -> create(runtime));
      }
    }
    return material(row);
  }

  AlpineRegistryDao.SigningKey rotate(
      RepositoryRuntime runtime,
      String privateKeyPem,
      String keyFilename,
      String signatureType) {
    if (privateKeyPem == null || privateKeyPem.isBlank()) {
      throw new IllegalArgumentException("Alpine PKCS#8 private key is required");
    }
    try (AlpineLeaseManager.Lease lease = leases.acquire("alpine:key:" + runtime.id())) {
      lease.assertHeld();
      PrivateKey privateKey = parsePrivate(privateKeyPem);
      PublicKey publicKey = derivePublic(privateKey);
      AlpineSignature.Type type = localSignatureType(signatureType);
      verifyPair(privateKey, publicKey, type);
      int revision = registry.findActiveSigningKey(runtime.id())
          .map(key -> key.revision() + 1).orElse(1);
      AlpineRegistryDao.SigningKey row = row(
          runtime.id(), revision, keyFilename, type, privateKeyPem, publicKey, Instant.now());
      registry.insertSigningKey(row);
      return row;
    }
  }

  AlpineRegistryDao.SigningKey rotateGenerated(
      RepositoryRuntime runtime, String keyFilename, String signatureType) {
    try (AlpineLeaseManager.Lease lease = leases.acquire("alpine:key:" + runtime.id())) {
      lease.assertHeld();
      KeyPair pair = generate();
      AlpineSignature.Type type = localSignatureType(signatureType);
      int revision = registry.findActiveSigningKey(runtime.id())
          .map(key -> key.revision() + 1).orElse(1);
      AlpineRegistryDao.SigningKey row = row(
          runtime.id(), revision, keyFilename, type,
          privatePem(pair.getPrivate()), pair.getPublic(), Instant.now());
      registry.insertSigningKey(row);
      return row;
    }
  }

  AlpineSignature sign(byte[] unsignedIndex, SigningMaterial material) {
    if (unsignedIndex == null || unsignedIndex.length == 0) {
      throw new IllegalArgumentException("Unsigned APKINDEX bytes are required");
    }
    try {
      Signature signer = Signature.getInstance(material.type().jcaAlgorithm());
      signer.initSign(material.privateKey());
      signer.update(unsignedIndex);
      return new AlpineSignature(material.type(), material.keyFilename(), signer.sign());
    } catch (GeneralSecurityException error) {
      throw new IllegalStateException("Failed to sign Alpine APKINDEX", error);
    }
  }

  AlpineSignature sign(Path unsignedIndex, SigningMaterial material) {
    if (unsignedIndex == null) throw new IllegalArgumentException("Unsigned APKINDEX is required");
    try {
      Signature signer = Signature.getInstance(material.type().jcaAlgorithm());
      signer.initSign(material.privateKey());
      try (var input = Files.newInputStream(unsignedIndex)) {
        byte[] buffer = new byte[64 * 1024];
        for (int read; (read = input.read(buffer)) >= 0;) {
          if (read > 0) signer.update(buffer, 0, read);
        }
      }
      return new AlpineSignature(material.type(), material.keyFilename(), signer.sign());
    } catch (java.io.IOException | GeneralSecurityException error) {
      throw new IllegalStateException("Failed to sign Alpine APKINDEX", error);
    }
  }

  static boolean verify(byte[] payload, byte[] signature, PublicKey key, AlpineSignature.Type type) {
    try {
      Signature verifier = Signature.getInstance(type.jcaAlgorithm());
      verifier.initVerify(key);
      verifier.update(payload);
      return verifier.verify(signature);
    } catch (GeneralSecurityException error) {
      throw new IllegalArgumentException("Unable to verify APK signature", error);
    }
  }

  static PublicKey parsePublic(String pem) {
    try {
      return KeyFactory.getInstance("RSA").generatePublic(
          new X509EncodedKeySpec(decodePem(pem, "PUBLIC KEY")));
    } catch (GeneralSecurityException error) {
      throw new IllegalArgumentException("Invalid Alpine RSA public key", error);
    }
  }

  private AlpineRegistryDao.SigningKey create(RepositoryRuntime runtime) {
    AlpineRegistryDao.SigningKey existing = registry.findActiveSigningKey(runtime.id()).orElse(null);
    if (existing != null) return existing;
    AlpineRepositorySettings.Settings configured = settings.get(runtime);
    KeyPair pair = generate();
    AlpineSignature.Type type = localSignatureType(configured.signatureType());
    AlpineRegistryDao.SigningKey row = row(
        runtime.id(), 1, configured.keyFilename(), type,
        privatePem(pair.getPrivate()), pair.getPublic(), Instant.now());
    try {
      registry.insertSigningKey(row);
      return row;
    } catch (DuplicateKeyException race) {
      return registry.findActiveSigningKey(runtime.id()).orElseThrow(() -> race);
    }
  }

  private SigningMaterial material(AlpineRegistryDao.SigningKey row) {
    PrivateKey privateKey = parsePrivate(cipher().decrypt(row.encryptedPrivateKey()));
    PublicKey publicKey = parsePublic(row.publicKey());
    AlpineSignature.Type type = AlpineSignature.Type.fromLabel(row.signatureType());
    verifyPair(privateKey, publicKey, type);
    return new SigningMaterial(
        row.revision(), row.keyFilename(), row.fingerprint(), row.publicKey(),
        privateKey, publicKey, type);
  }

  private static AlpineRegistryDao.SigningKey row(
      long repositoryId,
      int revision,
      String keyFilename,
      AlpineSignature.Type type,
      String privateKeyPem,
      PublicKey publicKey,
      Instant createdAt) {
    String filename = AlpineSignature.requireKeyFilename(keyFilename);
    String publicPem = publicPem(publicKey);
    String fingerprint = HexFormat.of().withUpperCase().formatHex(sha256(publicKey.getEncoded()));
    return new AlpineRegistryDao.SigningKey(
        repositoryId,
        revision,
        filename,
        fingerprint,
        cipher().encrypt(privateKeyPem),
        publicPem,
        type.label(),
        true,
        createdAt);
  }

  private static KeyPair generate() {
    try {
      KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
      generator.initialize(3072);
      return generator.generateKeyPair();
    } catch (GeneralSecurityException error) {
      throw new IllegalStateException("Failed to generate Alpine RSA signing key", error);
    }
  }

  private static AlpineSignature.Type localSignatureType(String label) {
    AlpineSignature.Type type = AlpineSignature.Type.fromLabel(label);
    if (type == AlpineSignature.Type.DSA) {
      throw new IllegalArgumentException("Alpine repository signing requires an RSA key type");
    }
    return type;
  }

  private static PrivateKey parsePrivate(String pem) {
    try {
      return KeyFactory.getInstance("RSA").generatePrivate(
          new PKCS8EncodedKeySpec(decodePem(pem, "PRIVATE KEY")));
    } catch (GeneralSecurityException error) {
      throw new IllegalArgumentException("Invalid Alpine PKCS#8 RSA private key", error);
    }
  }

  private static PublicKey derivePublic(PrivateKey key) {
    if (!(key instanceof RSAPrivateCrtKey rsa)) {
      throw new IllegalArgumentException("Alpine private key must be an RSA CRT PKCS#8 key");
    }
    try {
      return KeyFactory.getInstance("RSA").generatePublic(
          new RSAPublicKeySpec(rsa.getModulus(), rsa.getPublicExponent()));
    } catch (GeneralSecurityException error) {
      throw new IllegalArgumentException("Unable to derive Alpine RSA public key", error);
    }
  }

  private static void verifyPair(
      PrivateKey privateKey, PublicKey publicKey, AlpineSignature.Type type) {
    byte[] probe = "kkrepo-alpine-key-check".getBytes(StandardCharsets.US_ASCII);
    try {
      Signature signer = Signature.getInstance(type.jcaAlgorithm());
      signer.initSign(privateKey);
      signer.update(probe);
      if (!verify(probe, signer.sign(), publicKey, type)) {
        throw new IllegalArgumentException("Alpine private/public key pair does not match");
      }
    } catch (GeneralSecurityException error) {
      throw new IllegalArgumentException("Alpine signing key cannot sign", error);
    }
  }

  private static byte[] decodePem(String pem, String type) {
    if (pem == null) throw new IllegalArgumentException("Missing PEM key");
    String begin = "-----BEGIN " + type + "-----";
    String end = "-----END " + type + "-----";
    int start = pem.indexOf(begin);
    int finish = pem.indexOf(end);
    if (start < 0 || finish <= start || pem.indexOf('\0') >= 0) {
      throw new IllegalArgumentException("Expected PKCS#8 " + type + " PEM");
    }
    String encoded = pem.substring(start + begin.length(), finish).replaceAll("\\s", "");
    try {
      return Base64.getDecoder().decode(encoded);
    } catch (IllegalArgumentException error) {
      throw new IllegalArgumentException("Invalid PEM encoding", error);
    }
  }

  private static String privatePem(PrivateKey key) {
    return pem("PRIVATE KEY", key.getEncoded());
  }

  private static String publicPem(PublicKey key) {
    return pem("PUBLIC KEY", key.getEncoded());
  }

  private static String pem(String type, byte[] encoded) {
    String body = Base64.getMimeEncoder(64, new byte[]{'\n'}).encodeToString(encoded);
    return "-----BEGIN " + type + "-----\n" + body + "\n-----END " + type + "-----\n";
  }

  private static byte[] sha256(byte[] bytes) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(bytes);
    } catch (GeneralSecurityException error) {
      throw new IllegalStateException(error);
    }
  }

  private static SecretCipher cipher() {
    return new SecretCipher(EncryptionSecrets.credentialSecret());
  }

  record SigningMaterial(
      int revision,
      String keyFilename,
      String fingerprint,
      String publicPem,
      PrivateKey privateKey,
      PublicKey publicKey,
      AlpineSignature.Type type) {
  }
}
