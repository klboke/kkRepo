package com.github.klboke.kkrepo.server.nuget;

import com.github.klboke.kkrepo.core.security.EncryptionSecrets;
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
