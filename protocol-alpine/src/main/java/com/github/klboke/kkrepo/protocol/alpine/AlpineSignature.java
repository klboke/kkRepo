package com.github.klboke.kkrepo.protocol.alpine;

import java.util.Locale;
import java.util.regex.Pattern;

/** APK v2 signature tar-entry identity and supported digest algorithms. */
public record AlpineSignature(Type type, String keyFilename, byte[] bytes) {
  private static final Pattern KEY_FILENAME =
      Pattern.compile("[A-Za-z0-9][A-Za-z0-9@+._-]{0,191}\\.rsa\\.pub");

  public AlpineSignature {
    if (type == null) throw new IllegalArgumentException("APK signature type is required");
    keyFilename = requireKeyFilename(keyFilename);
    if (bytes == null || bytes.length == 0 || bytes.length > 65_536) {
      throw new IllegalArgumentException("Invalid APK signature length");
    }
    bytes = bytes.clone();
  }

  @Override
  public byte[] bytes() {
    return bytes.clone();
  }

  public String entryName() {
    return ".SIGN." + type.label() + "." + keyFilename;
  }

  public static ParsedEntry parseEntryName(String value) {
    if (value == null || !value.startsWith(".SIGN.") || value.indexOf('/') >= 0) {
      throw new IllegalArgumentException("Invalid APK signature entry");
    }
    int separator = value.indexOf('.', ".SIGN.".length());
    if (separator < 0) throw new IllegalArgumentException("Invalid APK signature entry");
    Type type = Type.fromLabel(value.substring(".SIGN.".length(), separator));
    String filename = requireKeyFilename(value.substring(separator + 1));
    return new ParsedEntry(type, filename);
  }

  public static String requireKeyFilename(String value) {
    if (value == null || !KEY_FILENAME.matcher(value).matches()
        || value.contains("..") || value.toLowerCase(Locale.ROOT).startsWith(".sign.")) {
      throw new IllegalArgumentException("Invalid Alpine public key filename: " + value);
    }
    return value;
  }

  public enum Type {
    RSA("RSA", "SHA1withRSA"),
    RSA256("RSA256", "SHA256withRSA"),
    RSA512("RSA512", "SHA512withRSA"),
    DSA("DSA", "SHA1withDSA");

    private final String label;
    private final String jcaAlgorithm;

    Type(String label, String jcaAlgorithm) {
      this.label = label;
      this.jcaAlgorithm = jcaAlgorithm;
    }

    public String label() {
      return label;
    }

    public String jcaAlgorithm() {
      return jcaAlgorithm;
    }

    public static Type fromLabel(String label) {
      for (Type value : values()) {
        if (value.label.equals(label)) return value;
      }
      throw new IllegalArgumentException("Unsupported APK signature type: " + label);
    }
  }

  public record ParsedEntry(Type type, String keyFilename) {
  }
}
