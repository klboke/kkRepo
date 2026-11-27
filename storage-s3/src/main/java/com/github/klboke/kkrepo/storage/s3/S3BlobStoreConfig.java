package com.github.klboke.kkrepo.storage.s3;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public record S3BlobStoreConfig(
    long id,
    String name,
    String engine,
    String endpoint,
    String region,
    String bucket,
    String prefix,
    String accessKey,
    String secretKey,
    boolean pathStyleAccess,
    int maxConnections,
    int connectionTimeoutMs,
    int socketTimeoutMs,
    int connectionAcquisitionTimeoutMs,
    boolean tcpKeepAlive,
    long multipartThresholdBytes,
    long multipartPartSizeBytes,
    int multipartConcurrency) {
  public static final String ENGINE_AWS_S3 = "aws-s3";
  public static final String ENGINE_OSS_NATIVE = "oss-native";

  public S3BlobStoreConfig {
    engine = normalizeEngine(engine);
    accessKey = normalizeCredential(accessKey);
    secretKey = normalizeCredential(secretKey);
    validateCredentials(engine, accessKey, secretKey);
    prefix = prefix == null ? "" : prefix;
    maxConnections = Math.max(1, maxConnections);
    connectionTimeoutMs = Math.max(1, connectionTimeoutMs);
    socketTimeoutMs = Math.max(1, socketTimeoutMs);
    connectionAcquisitionTimeoutMs = Math.max(1, connectionAcquisitionTimeoutMs);
    multipartThresholdBytes = Math.max(0, multipartThresholdBytes);
    multipartPartSizeBytes = Math.max(5L * 1024 * 1024, multipartPartSizeBytes);
    multipartConcurrency = Math.max(1, multipartConcurrency);
  }

  public static S3BlobStoreConfig of(
      long id,
      String name,
      String endpoint,
      String region,
      String bucket,
      String prefix,
      Map<String, Object> attributes) {
    Map<String, Object> attrs = attributes == null ? Map.of() : attributes;
    return new S3BlobStoreConfig(
        id,
        name,
        stringAttr(attrs, "engine", ENGINE_AWS_S3),
        endpoint,
        region,
        bucket,
        prefix == null ? "" : prefix,
        stringAttr(attrs, "accessKey"),
        stringAttr(attrs, "secretKey"),
        boolAttr(attrs, "pathStyleAccess", true),
        intAttr(attrs, "maxConnections", 200),
        intAttr(attrs, "connectionTimeoutMs", 5000),
        intAttr(attrs, "socketTimeoutMs", 120000),
        intAttr(attrs, "connectionAcquisitionTimeoutMs", 10000),
        boolAttr(attrs, "tcpKeepAlive", true),
        longAttr(attrs, "multipartThresholdBytes", 64L * 1024 * 1024),
        longAttr(attrs, "multipartPartSizeBytes", 16L * 1024 * 1024),
        intAttr(attrs, "multipartConcurrency", 4));
  }

  private static String stringAttr(Map<String, Object> attrs, String key) {
    return stringAttr(attrs, key, "");
  }

  /** Explicit empty credentials select the SDK chain; only absent legacy attributes inherit defaults. */
  public static String credentialAttribute(Map<String, Object> attrs, String key, String fallback) {
    Object value = attrs != null && attrs.containsKey(key) ? attrs.get(key) : fallback;
    return normalizeCredential(value == null ? null : value.toString());
  }

  private static String normalizeCredential(String value) {
    return value == null || value.isBlank() ? "" : value;
  }

  public static void validateCredentials(String engine, String accessKey, String secretKey) {
    boolean accessMissing = accessKey == null || accessKey.isBlank();
    boolean secretMissing = secretKey == null || secretKey.isBlank();
    if (accessMissing != secretMissing) {
      throw new IllegalArgumentException("accessKey and secretKey must both be provided or both be empty");
    }
    if (Engine.fromValue(engine) == Engine.OSS_NATIVE && accessMissing) {
      throw new IllegalArgumentException("accessKey and secretKey are required for oss-native");
    }
  }

  public boolean usesDefaultCredentials() {
    return accessKey.isEmpty() && secretKey.isEmpty();
  }

  private static String stringAttr(Map<String, Object> attrs, String key, String fallback) {
    Object value = attrs.get(key);
    return value == null || value.toString().isBlank() ? fallback : value.toString();
  }

  private static boolean boolAttr(Map<String, Object> attrs, String key, boolean fallback) {
    Object value = attrs.get(key);
    if (value == null) return fallback;
    if (value instanceof Boolean b) return b;
    return Boolean.parseBoolean(value.toString());
  }

  private static int intAttr(Map<String, Object> attrs, String key, int fallback) {
    Object value = attrs.get(key);
    if (value == null) return fallback;
    if (value instanceof Number number) return number.intValue();
    try {
      return Integer.parseInt(value.toString());
    } catch (NumberFormatException e) {
      return fallback;
    }
  }

  private static long longAttr(Map<String, Object> attrs, String key, long fallback) {
    Object value = attrs.get(key);
    if (value == null) return fallback;
    if (value instanceof Number number) return number.longValue();
    try {
      return Long.parseLong(value.toString());
    } catch (NumberFormatException e) {
      return fallback;
    }
  }

  public String signature() {
    return engine + "|" + endpoint + "|" + region + "|" + bucket + "|" + prefix + "|" + accessKey + "|"
        + secretKey + "|" + pathStyleAccess + "|" + maxConnections + "|" + connectionTimeoutMs
        + "|" + socketTimeoutMs + "|" + connectionAcquisitionTimeoutMs + "|" + tcpKeepAlive
        + "|" + multipartThresholdBytes + "|" + multipartPartSizeBytes + "|" + multipartConcurrency;
  }

  /**
   * Names of the settings that differ from {@code other}, for diagnostics. Credentials are reported
   * as {@code credentials} and never by value.
   */
  public List<String> differingFields(S3BlobStoreConfig other) {
    List<String> fields = new ArrayList<>();
    if (!Objects.equals(engine, other.engine)) fields.add("engine");
    if (!Objects.equals(endpoint, other.endpoint)) fields.add("endpoint");
    if (!Objects.equals(region, other.region)) fields.add("region");
    if (!Objects.equals(bucket, other.bucket)) fields.add("bucket");
    if (!Objects.equals(prefix, other.prefix)) fields.add("prefix");
    if (!Objects.equals(accessKey, other.accessKey) || !Objects.equals(secretKey, other.secretKey)) {
      fields.add("credentials");
    }
    if (pathStyleAccess != other.pathStyleAccess) fields.add("pathStyleAccess");
    if (maxConnections != other.maxConnections) fields.add("maxConnections");
    if (connectionTimeoutMs != other.connectionTimeoutMs) fields.add("connectionTimeoutMs");
    if (socketTimeoutMs != other.socketTimeoutMs) fields.add("socketTimeoutMs");
    if (connectionAcquisitionTimeoutMs != other.connectionAcquisitionTimeoutMs) {
      fields.add("connectionAcquisitionTimeoutMs");
    }
    if (tcpKeepAlive != other.tcpKeepAlive) fields.add("tcpKeepAlive");
    if (multipartThresholdBytes != other.multipartThresholdBytes) fields.add("multipartThresholdBytes");
    if (multipartPartSizeBytes != other.multipartPartSizeBytes) fields.add("multipartPartSizeBytes");
    if (multipartConcurrency != other.multipartConcurrency) fields.add("multipartConcurrency");
    return List.copyOf(fields);
  }

  public Engine engineType() {
    return Engine.fromValue(engine);
  }

  public boolean usesOssNative() {
    return engineType() == Engine.OSS_NATIVE;
  }

  @Override
  public boolean equals(Object other) {
    if (this == other) return true;
    if (!(other instanceof S3BlobStoreConfig that)) return false;
    return id == that.id && pathStyleAccess == that.pathStyleAccess
        && maxConnections == that.maxConnections
        && connectionTimeoutMs == that.connectionTimeoutMs
        && socketTimeoutMs == that.socketTimeoutMs
        && connectionAcquisitionTimeoutMs == that.connectionAcquisitionTimeoutMs
        && tcpKeepAlive == that.tcpKeepAlive
        && multipartThresholdBytes == that.multipartThresholdBytes
        && multipartPartSizeBytes == that.multipartPartSizeBytes
        && multipartConcurrency == that.multipartConcurrency
        && Objects.equals(name, that.name)
        && Objects.equals(engine, that.engine)
        && Objects.equals(endpoint, that.endpoint)
        && Objects.equals(region, that.region)
        && Objects.equals(bucket, that.bucket)
        && Objects.equals(prefix, that.prefix)
        && Objects.equals(accessKey, that.accessKey)
        && Objects.equals(secretKey, that.secretKey);
  }

  @Override
  public int hashCode() {
    return Objects.hash(id, name, engine, endpoint, region, bucket, prefix, accessKey, secretKey,
        pathStyleAccess, maxConnections, connectionTimeoutMs, socketTimeoutMs,
        connectionAcquisitionTimeoutMs, tcpKeepAlive, multipartThresholdBytes,
        multipartPartSizeBytes, multipartConcurrency);
  }

  private static String normalizeEngine(String value) {
    // Single source of truth for engine aliases/normalization lives in Engine.
    return Engine.fromValue(value).id();
  }
}
