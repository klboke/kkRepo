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
  private static final Pattern API_KEY_DOMAIN = Pattern.compile("[A-Za-z0-9._-]{1,100}");

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

  /**
   * The one identity rule for API key domains in storage, management and authentication: an ASCII
   * name compared case-insensitively, with the reserved user token domain in its canonical spelling.
   * On this character set Java's comparison matches the database's (MySQL utf8mb4_unicode_ci folds
   * accents and full-width forms, which are rejected here), so no stored row can be a user token for
   * one layer and an ordinary key for another. Returns null for a name outside the rule.
   */
  static String canonicalApiKeyDomain(String domain) {
    if (domain == null || !API_KEY_DOMAIN.matcher(domain).matches()) {
      return null;
    }
    return isUserTokenDomain(domain) ? DOMAIN : domain;
  }

  /** Classifies a stored domain; rows written under {@link #canonicalApiKeyDomain} are canonical. */
  static boolean isUserTokenDomain(String domain) {
    return DOMAIN.equalsIgnoreCase(domain);
  }

  /** Cheap shape check so ordinary passwords skip the token lookup and token lookups skip LDAP. */
  static boolean matches(String userCode, String passCode) {
    return userCode != null && passCode != null
        && USER_CODE.matcher(userCode).matches() && PASS_CODE.matcher(passCode).matches();
  }
}
