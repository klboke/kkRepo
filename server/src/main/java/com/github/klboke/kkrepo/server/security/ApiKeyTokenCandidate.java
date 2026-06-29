package com.github.klboke.kkrepo.server.security;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

record ApiKeyTokenCandidate(String domain, String tokenMaterial) {
  private static final String NPM_TOKEN_DOMAIN = "NpmToken";
  private static final String CARGO_TOKEN_DOMAIN = "CargoToken";
  private static final String NUGET_API_KEY_DOMAIN = "NuGetApiKey";
  private static final String RUBYGEMS_API_KEY_DOMAIN = "RubyGemsApiKey";

  static List<ApiKeyTokenCandidate> fromPresentedToken(String token) {
    return fromPresentedToken(token, null);
  }

  static List<ApiKeyTokenCandidate> fromPresentedCargoToken(String token) {
    return fromPresentedToken(token, CARGO_TOKEN_DOMAIN);
  }

  static List<ApiKeyTokenCandidate> fromPresentedRubygemsToken(String token) {
    return fromPresentedToken(token, RUBYGEMS_API_KEY_DOMAIN);
  }

  private static List<ApiKeyTokenCandidate> fromPresentedToken(String token, String preferredDomain) {
    String value = normalize(token);
    if (value == null) {
      return List.of();
    }
    LinkedHashMap<String, ApiKeyTokenCandidate> candidates = new LinkedHashMap<>();
    int separator = value.indexOf('.');
    if (separator > 0 && separator < value.length() - 1) {
      String domain = normalize(value.substring(0, separator));
      String rawToken = normalize(value.substring(separator + 1));
      if (domain != null && rawToken != null) {
        add(candidates, domain, rawToken);
      }
    } else {
      // Bare tokens are checked in Nexus-compatible token domains before the full-token fallback.
      add(candidates, preferredDomain == null ? NPM_TOKEN_DOMAIN : preferredDomain, value);
      if (!NPM_TOKEN_DOMAIN.equals(preferredDomain)) {
        add(candidates, NPM_TOKEN_DOMAIN, value);
      }
      if (!CARGO_TOKEN_DOMAIN.equals(preferredDomain)) {
        add(candidates, CARGO_TOKEN_DOMAIN, value);
      }
      add(candidates, NUGET_API_KEY_DOMAIN, value);
      if (RUBYGEMS_API_KEY_DOMAIN.equals(preferredDomain)) {
        add(candidates, RUBYGEMS_API_KEY_DOMAIN, value);
      }
    }
    add(candidates, null, value);
    return new ArrayList<>(candidates.values());
  }

  boolean domainScoped() {
    return domain != null;
  }

  private static void add(
      LinkedHashMap<String, ApiKeyTokenCandidate> candidates,
      String domain,
      String tokenMaterial) {
    String value = normalize(tokenMaterial);
    if (value == null) {
      return;
    }
    String key = (domain == null ? "" : domain) + "::" + value;
    candidates.putIfAbsent(key, new ApiKeyTokenCandidate(domain, value));
  }

  private static String normalize(String value) {
    if (value == null) {
      return null;
    }
    String trimmed = value.trim();
    return trimmed.isEmpty() ? null : trimmed;
  }
}
