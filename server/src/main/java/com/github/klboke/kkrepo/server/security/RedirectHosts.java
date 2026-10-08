package com.github.klboke.kkrepo.server.security;

import java.net.IDN;
import java.util.Collection;
import java.util.Locale;
import java.util.regex.Pattern;

/** Stateless matching of database-backed redirect rules; never grants address or credential trust. */
public final class RedirectHosts {
  private static final Pattern LABEL = Pattern.compile("^[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?$");

  private RedirectHosts() {}

  public static String normalizeRule(String rule) {
    if (rule == null || rule.isBlank()) throw new IllegalArgumentException("Empty redirect host");
    if ("*".equals(rule.trim())) return "*"; // Content transport opt-in, not an unrestricted host match.
    String value = normalizeHost(rule);
    boolean wildcard = value.startsWith("*.");
    String domain = wildcard ? value.substring(2) : value;
    domain = IDN.toASCII(domain, IDN.USE_STD3_ASCII_RULES).toLowerCase(Locale.ROOT);
    if (domain.isBlank() || domain.length() > 253
        || (wildcard && (!domain.contains(".") || domain.matches("[0-9.]+")))) {
      throw new IllegalArgumentException("Invalid redirect host");
    }
    for (String label : domain.split("\\.", -1)) {
      if (!LABEL.matcher(label).matches()) throw new IllegalArgumentException("Invalid redirect host");
    }
    return (wildcard ? "*." : "") + domain;
  }

  /** A wildcard matches one or more labels and never the apex or a partial label. */
  public static boolean matches(Collection<String> rules, String host) {
    String target = normalizeHost(host);
    if (target.isBlank() || rules == null) return false;
    for (String rule : rules) {
      if (rule == null) continue;
      if (rule.startsWith("*.")) {
        String suffix = rule.substring(1);
        if (target.length() > suffix.length() && target.endsWith(suffix)) return true;
      } else if (rule.equals(target)) {
        return true;
      }
    }
    return false;
  }

  private static String normalizeHost(String host) {
    String value = host == null ? "" : host.trim().toLowerCase(Locale.ROOT);
    while (value.endsWith(".")) value = value.substring(0, value.length() - 1);
    return value;
  }
}
