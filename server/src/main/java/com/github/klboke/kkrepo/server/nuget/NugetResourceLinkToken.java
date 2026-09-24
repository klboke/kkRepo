package com.github.klboke.kkrepo.server.nuget;

import com.github.klboke.kkrepo.core.security.EncryptionSecrets;
import com.github.klboke.kkrepo.core.security.SecretCipher;
import com.github.klboke.kkrepo.server.maven.MavenExceptions;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Stateless routing proofs use the deployment secret shared by all replicas. */
final class NugetResourceLinkToken {
  private NugetResourceLinkToken() {}

  static String identity(String configuredIndex, String endpoint) {
    // URLs can contain low-entropy credentials. A public, unkeyed digest would allow
    // offline guessing even though the enclosing routing proof is authenticated.
    return HexFormat.of().formatHex(hmac("kkrepo-nuget-resource-id-v1\0" + configuredIndex + "\0" + endpoint));
  }

  static String issue(long groupId, long memberId, String pathAndQuery, String resourceIdentity) {
    return memberId + "." + resourceIdentity + "."
        + Base64.getUrlEncoder().withoutPadding().encodeToString(mac(groupId, memberId, pathAndQuery, resourceIdentity));
  }

  static long verify(long groupId, String pathAndQuery, String token) {
    try {
      String[] parts = token.split("\\.", -1);
      if (parts.length != 3 || !parts[1].matches("[0-9a-f]{64}")) throw new IllegalArgumentException();
      long memberId = Long.parseLong(parts[0]);
      if (memberId <= 0 || !MessageDigest.isEqual(mac(groupId, memberId, pathAndQuery, parts[1]),
          Base64.getUrlDecoder().decode(parts[2]))) throw new IllegalArgumentException();
      return memberId;
    } catch (IllegalArgumentException e) {
      throw new MavenExceptions.MavenNotFoundException("Invalid NuGet resource source");
    }
  }

  static void requireResource(String token, String resourceIdentity) {
    if (token != null && !token.split("\\.", -1)[1].equals(resourceIdentity)) {
      throw new MavenExceptions.MavenNotFoundException("NuGet resource source changed");
    }
  }

  // Preserve opaque queries containing resource credentials without exposing those
  // credentials to readers. The authenticated ciphertext is portable across replicas.
  static String sealQuery(long repositoryId, String resourceIdentity, String publicPath, String rawQuery) {
    String payload = queryBinding(repositoryId, resourceIdentity, publicPath) + "\n" + rawQuery;
    return "v1." + Base64.getUrlEncoder().withoutPadding().encodeToString(
        queryCipher().encrypt(payload).getBytes(StandardCharsets.UTF_8));
  }

  static String openQuery(long repositoryId, String resourceIdentity, String publicPath, String token) {
    if (!token.startsWith("v1.")) return null;
    String payload;
    try {
      String ciphertext = new String(Base64.getUrlDecoder().decode(token.substring(3)), StandardCharsets.UTF_8);
      if (!SecretCipher.isEncrypted(ciphertext)) return null;
      payload = queryCipher().decrypt(ciphertext);
    } catch (IllegalArgumentException | IllegalStateException invalid) {
      // A same-named upstream parameter remains opaque unless we authenticated it.
      return null;
    }
    String binding = queryBinding(repositoryId, resourceIdentity, publicPath) + "\n";
    if (!payload.startsWith(binding)) throw new MavenExceptions.MavenNotFoundException("NuGet resource query changed");
    return payload.substring(binding.length());
  }

  private static String queryBinding(long repositoryId, String resourceIdentity, String path) {
    return HexFormat.of().formatHex(hmac("kkrepo-nuget-query-context-v1\0" + repositoryId + "\0" + resourceIdentity + "\0" + path));
  }

  private static SecretCipher queryCipher() {
    return new SecretCipher(HexFormat.of().formatHex(hmac("kkrepo-nuget-query-cipher-v1")));
  }

  private static byte[] mac(long groupId, long memberId, String pathAndQuery, String resourceIdentity) {
    return hmac("kkrepo-nuget-group-link-v2\0" + groupId + "\0" + memberId + "\0" + resourceIdentity + "\0" + pathAndQuery);
  }

  private static byte[] hmac(String message) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(EncryptionSecrets.credentialSecret().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
      return mac.doFinal(message.getBytes(StandardCharsets.UTF_8));
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("HmacSHA256 unavailable", e);
    }
  }
}
