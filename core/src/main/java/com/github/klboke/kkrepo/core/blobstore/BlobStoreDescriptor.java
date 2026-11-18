package com.github.klboke.kkrepo.core.blobstore;

import java.util.Locale;
import java.util.Map;

/**
 * Module-neutral view of a persisted blob-store row. Storage adapters ({@code storage-s3},
 * {@code storage-file}, {@code storage-azure}) map this into their own configuration record without
 * depending on the persistence layer, so {@code core} stays free of JDBC types.
 *
 * <p>{@code bucket} is the provider's top-level container: an S3/OSS bucket, an Azure Blob
 * container, or an unused value for filesystem stores.
 */
public record BlobStoreDescriptor(
    long id,
    String name,
    String type,
    String endpoint,
    String region,
    String bucket,
    String prefix,
    Map<String, Object> attributes) {

  public BlobStoreDescriptor {
    name = name == null ? "" : name;
    type = type == null ? "" : type;
    prefix = prefix == null ? "" : prefix;
    attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
  }

  /** Lower-cased, trimmed {@code type}, the primary provider selector. */
  public String normalizedType() {
    return normalize(type);
  }

  /**
   * Lower-cased {@code engine} attribute. Legacy rows stored the provider discriminator here
   * instead of in {@code type}, so provider matching must consider both.
   */
  public String normalizedEngine() {
    return normalize(stringAttr("engine", ""));
  }

  public String stringAttr(String key, String fallback) {
    Object value = attributes.get(key);
    return value == null || value.toString().isBlank() ? fallback : value.toString();
  }

  public boolean boolAttr(String key, boolean fallback) {
    Object value = attributes.get(key);
    if (value == null) {
      return fallback;
    }
    if (value instanceof Boolean bool) {
      return bool;
    }
    return Boolean.parseBoolean(value.toString());
  }

  public int intAttr(String key, int fallback) {
    Object value = attributes.get(key);
    if (value == null) {
      return fallback;
    }
    if (value instanceof Number number) {
      return number.intValue();
    }
    try {
      return Integer.parseInt(value.toString().trim());
    } catch (NumberFormatException e) {
      return fallback;
    }
  }

  public long longAttr(String key, long fallback) {
    Object value = attributes.get(key);
    if (value == null) {
      return fallback;
    }
    if (value instanceof Number number) {
      return number.longValue();
    }
    try {
      return Long.parseLong(value.toString().trim());
    } catch (NumberFormatException e) {
      return fallback;
    }
  }

  /**
   * Returns the attribute only when the key is present, so an explicitly blank value can mean
   * "use the ambient credential chain" rather than "inherit the configured default".
   */
  public String presentAttr(String key, String fallback) {
    Object value = attributes.containsKey(key) ? attributes.get(key) : fallback;
    return value == null ? "" : value.toString();
  }

  public static String normalize(String value) {
    return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
  }
}
