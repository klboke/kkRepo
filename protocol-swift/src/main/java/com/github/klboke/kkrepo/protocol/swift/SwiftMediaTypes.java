package com.github.klboke.kkrepo.protocol.swift;

import java.math.BigInteger;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Swift Registry v1 media types and Accept negotiation. */
public final class SwiftMediaTypes {
  public static final int API_VERSION = 1;
  public static final String CONTENT_VERSION = "1";
  public static final String VENDOR_BASE = "application/vnd.swift.registry";
  public static final String VENDOR_JSON = VENDOR_BASE + ".v1+json";
  public static final String VENDOR_SWIFT = VENDOR_BASE + ".v1+swift";
  public static final String VENDOR_ZIP = VENDOR_BASE + ".v1+zip";
  public static final String JSON = "application/json";
  public static final String MANIFEST = "text/x-swift";
  public static final String ARCHIVE = "application/zip";
  public static final String PROBLEM_JSON = "application/problem+json";

  private static final Pattern VENDOR = Pattern.compile(
      "^application/vnd\\.swift\\.registry(?:\\.v([^+]+))?(?:\\+([a-z0-9-]+))?$");

  private SwiftMediaTypes() {
  }

  public enum Resource {
    JSON("json", SwiftMediaTypes.JSON),
    MANIFEST("swift", SwiftMediaTypes.MANIFEST),
    ARCHIVE("zip", SwiftMediaTypes.ARCHIVE);

    private final String suffix;
    private final String responseContentType;

    Resource(String suffix, String responseContentType) {
      this.suffix = suffix;
      this.responseContentType = responseContentType;
    }
  }

  public enum Outcome {
    ACCEPTED,
    INVALID_API_VERSION,
    UNSUPPORTED_API_VERSION,
    UNSUPPORTED_MEDIA_TYPE
  }

  public record Negotiation(Outcome outcome, int apiVersion, String responseContentType) {
    public boolean accepted() {
      return outcome == Outcome.ACCEPTED;
    }

    public int httpStatus() {
      return switch (outcome) {
        case ACCEPTED -> 200;
        case INVALID_API_VERSION -> 400;
        case UNSUPPORTED_API_VERSION, UNSUPPORTED_MEDIA_TYPE -> 415;
      };
    }
  }

  public static Negotiation negotiate(String accept, Resource resource) {
    if (resource == null) {
      throw new IllegalArgumentException("Swift registry resource must not be null");
    }
    if (accept == null || accept.isBlank()) {
      return accepted(resource);
    }

    boolean invalidVersion = false;
    boolean unsupportedVersion = false;
    for (String range : accept.split(",")) {
      String[] parts = range.trim().split(";");
      if (parts.length == 0 || parts[0].isBlank() || qualityIsZero(parts)) continue;
      String mediaType = parts[0].trim().toLowerCase(Locale.ROOT);
      if (mediaType.equals("*/*") || mediaType.equals(typeWildcard(resource))) {
        return accepted(resource);
      }
      if (mediaType.equals(resource.responseContentType)) {
        return accepted(resource);
      }
      Matcher matcher = VENDOR.matcher(mediaType);
      if (!matcher.matches()) continue;

      String versionText = matcher.group(1);
      if (versionText != null) {
        if (!versionText.matches("^(?:0|[1-9]\\d*)$")) {
          invalidVersion = true;
          continue;
        }
        if (!new BigInteger(versionText).equals(BigInteger.ONE)) {
          unsupportedVersion = true;
          continue;
        }
      }
      String suffix = matcher.group(2) == null ? "json" : matcher.group(2);
      if (suffix.equals(resource.suffix)) {
        return accepted(resource);
      }
    }

    if (invalidVersion) {
      return new Negotiation(Outcome.INVALID_API_VERSION, API_VERSION, PROBLEM_JSON);
    }
    if (unsupportedVersion) {
      return new Negotiation(Outcome.UNSUPPORTED_API_VERSION, API_VERSION, PROBLEM_JSON);
    }
    return new Negotiation(Outcome.UNSUPPORTED_MEDIA_TYPE, API_VERSION, PROBLEM_JSON);
  }

  private static Negotiation accepted(Resource resource) {
    return new Negotiation(Outcome.ACCEPTED, API_VERSION, resource.responseContentType);
  }

  private static String typeWildcard(Resource resource) {
    int separator = resource.responseContentType.indexOf('/');
    return resource.responseContentType.substring(0, separator) + "/*";
  }

  private static boolean qualityIsZero(String[] parameters) {
    for (int i = 1; i < parameters.length; i++) {
      String parameter = parameters[i].trim();
      int separator = parameter.indexOf('=');
      if (separator > 0 && parameter.substring(0, separator).trim().equalsIgnoreCase("q")) {
        try {
          return Double.parseDouble(parameter.substring(separator + 1).trim()) <= 0d;
        } catch (NumberFormatException e) {
          return true;
        }
      }
    }
    return false;
  }
}
