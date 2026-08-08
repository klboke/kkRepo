package com.github.klboke.kkrepo.protocol.apt;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Deterministic Debian Release-file model. */
public record AptRelease(Map<String, String> fields, List<Checksum> checksums) {
  private static final DateTimeFormatter RFC_1123 = DateTimeFormatter.RFC_1123_DATE_TIME;
  private static final List<String> HASH_ORDER = List.of("MD5Sum", "SHA1", "SHA256", "SHA512");

  public AptRelease {
    fields = Collections.unmodifiableMap(new LinkedHashMap<>(fields));
    checksums = List.copyOf(checksums);
  }

  public static Builder builder(String distribution, Instant date) {
    return new Builder(distribution, date);
  }

  public static AptRelease parse(String value) {
    AptDeb822.Stanza stanza = AptDeb822.parseSingle(value);
    LinkedHashMap<String, String> fields = new LinkedHashMap<>();
    ArrayList<Checksum> checksums = new ArrayList<>();
    for (Map.Entry<String, String> entry : stanza.fields().entrySet()) {
      if (HASH_ORDER.contains(entry.getKey())) {
        for (String line : entry.getValue().split("\n")) {
          if (line.isBlank()) continue;
          String[] parts = line.strip().split("\\s+", 3);
          if (parts.length != 3) throw new IllegalArgumentException("Invalid Release checksum line");
          checksums.add(new Checksum(entry.getKey(), parts[0], Long.parseLong(parts[1]), parts[2]));
        }
      } else {
        fields.put(entry.getKey(), entry.getValue());
      }
    }
    return new AptRelease(fields, checksums);
  }

  public String render() {
    LinkedHashMap<String, String> output = new LinkedHashMap<>(fields);
    for (String algorithm : HASH_ORDER) {
      StringBuilder lines = new StringBuilder();
      checksums.stream()
          .filter(checksum -> checksum.algorithm().equals(algorithm))
          .sorted(Comparator.comparing(Checksum::path))
          .forEach(checksum -> {
            lines.append('\n').append(checksum.digest()).append(' ')
                .append(checksum.size()).append(' ').append(checksum.path());
          });
      if (!lines.isEmpty()) output.put(algorithm, lines.toString());
    }
    return new AptDeb822.Stanza(output).render();
  }

  public record Checksum(String algorithm, String digest, long size, String path) {
    public Checksum {
      if (!HASH_ORDER.contains(algorithm)) throw new IllegalArgumentException("Unsupported hash: " + algorithm);
      int length = switch (algorithm) {
        case "MD5Sum" -> 32;
        case "SHA1" -> 40;
        case "SHA256" -> 64;
        case "SHA512" -> 128;
        default -> throw new IllegalArgumentException("Unsupported hash: " + algorithm);
      };
      if (digest == null || digest.length() != length
          || !digest.chars().allMatch(character -> Character.digit(character, 16) >= 0)) {
        throw new IllegalArgumentException("Invalid " + algorithm + " digest");
      }
      if (size < 0 || path == null || path.isBlank() || path.startsWith("/")
          || path.contains("..") || path.indexOf('\\') >= 0) {
        throw new IllegalArgumentException("Invalid Release checksum target");
      }
      digest = digest.toLowerCase(Locale.ROOT);
    }
  }

  public static final class Builder {
    private final LinkedHashMap<String, String> fields = new LinkedHashMap<>();
    private final ArrayList<Checksum> checksums = new ArrayList<>();

    private Builder(String distribution, Instant date) {
      if (!AptPathParser.isSafeName(distribution)) {
        throw new IllegalArgumentException("Invalid distribution: " + distribution);
      }
      fields.put("Origin", "kkrepo");
      fields.put("Label", "kkrepo");
      fields.put("Suite", distribution);
      fields.put("Codename", distribution);
      fields.put("Date", RFC_1123.format(date.atOffset(ZoneOffset.UTC)));
      fields.put("Acquire-By-Hash", "yes");
    }

    public Builder architectures(List<String> values) {
      values.forEach(value -> {
        if (!AptPathParser.isArchitecture(value)) throw new IllegalArgumentException("Invalid architecture: " + value);
      });
      fields.put("Architectures", String.join(" ", values));
      return this;
    }

    public Builder components(List<String> values) {
      values.forEach(value -> {
        if (!AptPathParser.isSafeName(value)) throw new IllegalArgumentException("Invalid component: " + value);
      });
      fields.put("Components", String.join(" ", values));
      return this;
    }

    public Builder validUntil(Instant value) {
      if (value == null) fields.remove("Valid-Until");
      else fields.put("Valid-Until", RFC_1123.format(value.atOffset(ZoneOffset.UTC)));
      return this;
    }

    public Builder field(String name, String value) {
      if (HASH_ORDER.contains(name)) throw new IllegalArgumentException("Use checksum() for hash fields");
      fields.put(name, value);
      return this;
    }

    public Builder checksum(String algorithm, String digest, long size, String path) {
      checksums.add(new Checksum(algorithm, digest, size, path));
      return this;
    }

    public AptRelease build() {
      return new AptRelease(fields, checksums);
    }
  }
}
