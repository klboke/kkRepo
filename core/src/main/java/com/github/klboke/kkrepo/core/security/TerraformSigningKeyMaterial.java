package com.github.klboke.kkrepo.core.security;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/** Versioned plaintext envelope stored only inside {@link SecretCipher} ciphertext. */
public final class TerraformSigningKeyMaterial {
  private static final String HEADER = "KKREPO_TERRAFORM_SIGNING_KEY_V1";

  private TerraformSigningKeyMaterial() {
  }

  public static String encode(String privateKeyArmor, String passphrase) {
    if (privateKeyArmor == null || privateKeyArmor.isBlank()) {
      throw new IllegalArgumentException("Terraform signing private key is required");
    }
    String encodedPassphrase = Base64.getEncoder().encodeToString(
        (passphrase == null ? "" : passphrase).getBytes(StandardCharsets.UTF_8));
    return HEADER + "\n" + encodedPassphrase + "\n" + privateKeyArmor;
  }

  public static Material decode(String plaintext) {
    if (plaintext == null || plaintext.isBlank()) {
      throw new IllegalArgumentException("Terraform signing private key is required");
    }
    String prefix = HEADER + "\n";
    if (!plaintext.startsWith(prefix)) {
      // Backward-compatible with keys created before passphrase migration support.
      return new Material(plaintext, "");
    }
    int separator = plaintext.indexOf('\n', prefix.length());
    if (separator < 0) {
      throw new IllegalArgumentException("Invalid Terraform signing key envelope");
    }
    String passphrase = new String(Base64.getDecoder().decode(
        plaintext.substring(prefix.length(), separator)), StandardCharsets.UTF_8);
    String privateKey = plaintext.substring(separator + 1);
    if (privateKey.isBlank()) {
      throw new IllegalArgumentException("Invalid Terraform signing key envelope");
    }
    return new Material(privateKey, passphrase);
  }

  public record Material(String privateKeyArmor, String passphrase) {
  }
}
