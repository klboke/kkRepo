package com.github.klboke.kkrepo.server.security;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.regex.Pattern;

/**
 * Nexus-style CLI user token: a generated user code and passcode that clients send as the HTTP
 * Basic username and password. It is stored like an API key in the {@link #DOMAIN} domain (hash of
 * {@code userCode:passCode}, one per owner), but it is accepted only as Basic credentials and never
 * opens a UI session.
 */
final class UserTokens {
  static final String DOMAIN = "UserToken";

  private static final SecureRandom RANDOM = new SecureRandom();
  private static final String USER_CODE_ALPHABET =
      "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789";
  private static final int USER_CODE_LENGTH = 10;
  private static final Pattern USER_CODE = Pattern.compile("[A-Za-z0-9]{" + USER_CODE_LENGTH + "}");
  // 32 random bytes, unpadded base64url.
  private static final Pattern PASS_CODE = Pattern.compile("[A-Za-z0-9_-]{43}");

  private UserTokens() {
  }

  /** Returns {@code userCode:passCode}. */
  static String generate() {
    StringBuilder userCode = new StringBuilder(USER_CODE_LENGTH);
    for (int i = 0; i < USER_CODE_LENGTH; i++) {
      userCode.append(USER_CODE_ALPHABET.charAt(RANDOM.nextInt(USER_CODE_ALPHABET.length())));
    }
    byte[] passCode = new byte[32];
    RANDOM.nextBytes(passCode);
    return userCode + ":" + Base64.getUrlEncoder().withoutPadding().encodeToString(passCode);
  }

  static String userCode(String token) {
    return token.substring(0, token.indexOf(':'));
  }

  /** Cheap shape check so ordinary passwords skip the token lookup and token lookups skip LDAP. */
  static boolean matches(String userCode, String passCode) {
    return userCode != null && passCode != null
        && USER_CODE.matcher(userCode).matches() && PASS_CODE.matcher(passCode).matches();
  }
}
