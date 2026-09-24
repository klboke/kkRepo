package com.github.klboke.kkrepo.server.nuget;

import com.github.klboke.kkrepo.core.security.EncryptionSecrets;
import com.github.klboke.kkrepo.server.maven.MavenExceptions;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Stateless routing proofs use the deployment secret shared by all replicas. */
final class NugetResourceLinkToken {
  private NugetResourceLinkToken() {}

  static String issue(long groupId, long memberId, String pathAndQuery) {
    return memberId + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(mac(groupId, memberId, pathAndQuery));
  }

  static long verify(long groupId, String pathAndQuery, String token) {
    try {
      String[] parts = token.split("\\.", -1);
      if (parts.length != 2) throw new IllegalArgumentException();
      long memberId = Long.parseLong(parts[0]);
      if (memberId <= 0 || !MessageDigest.isEqual(mac(groupId, memberId, pathAndQuery),
          Base64.getUrlDecoder().decode(parts[1]))) throw new IllegalArgumentException();
      return memberId;
    } catch (IllegalArgumentException e) {
      throw new MavenExceptions.MavenNotFoundException("Invalid NuGet resource source");
    }
  }

  private static byte[] mac(long groupId, long memberId, String pathAndQuery) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(EncryptionSecrets.credentialSecret().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
      String message = "kkrepo-nuget-group-link-v1\0" + groupId + "\0" + memberId + "\0" + pathAndQuery;
      return mac.doFinal(message.getBytes(StandardCharsets.UTF_8));
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("HmacSHA256 unavailable", e);
    }
  }
}
