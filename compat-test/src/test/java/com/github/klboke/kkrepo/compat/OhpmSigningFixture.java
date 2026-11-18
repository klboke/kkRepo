package com.github.klboke.kkrepo.compat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.EncryptedPrivateKeyInfo;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.PBEParameterSpec;

final class OhpmSigningFixture {
  private OhpmSigningFixture() {}

  static Path writePrivateKey(Path directory, KeyPair key, String passphrase)
      throws GeneralSecurityException, IOException {
    byte[] salt = new byte[8];
    new SecureRandom().nextBytes(salt);
    String algorithm = "PBEWithSHA1AndDESede";
    var encryptionKey = SecretKeyFactory.getInstance(algorithm).generateSecret(new PBEKeySpec(passphrase.toCharArray()));
    Cipher cipher = Cipher.getInstance(algorithm);
    cipher.init(Cipher.ENCRYPT_MODE, encryptionKey, new PBEParameterSpec(salt, 2048));
    byte[] encrypted = new EncryptedPrivateKeyInfo(cipher.getParameters(),
        cipher.doFinal(key.getPrivate().getEncoded())).getEncoded();
    Path privateKey = directory.resolve("signing-key.pem");
    String label = "ENCRYPTED PRIVATE KEY";
    Files.writeString(privateKey, "-----BEGIN " + label + "-----\n"
        + Base64.getMimeEncoder(64, new byte[] {'\n'}).encodeToString(encrypted)
        + "\n-----END " + label + "-----\n");
    return privateKey;
  }
}
