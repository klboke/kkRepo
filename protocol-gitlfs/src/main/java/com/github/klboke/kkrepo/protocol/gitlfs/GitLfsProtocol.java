package com.github.klboke.kkrepo.protocol.gitlfs;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/** Bounded parser for the official Batch/basic contract. OIDs are repository-relative identities. */
public final class GitLfsProtocol {
  public static final String MEDIA_TYPE = "application/vnd.git-lfs+json";
  public static final String BATCH_PATH = "info/lfs/objects/batch";
  public static final int MAX_BODY_BYTES = 1024 * 1024;
  public static final int MAX_OBJECTS = 1000;
  private static final Pattern OID = Pattern.compile("[a-f0-9]{64}");
  private static final ObjectMapper JSON = new ObjectMapper(JsonFactory.builder()
      .streamReadConstraints(StreamReadConstraints.builder()
          .maxNestingDepth(16).maxStringLength(4096).maxNumberLength(24).build()).build());

  private GitLfsProtocol() {}

  public static boolean validOid(String oid) { return oid != null && OID.matcher(oid).matches(); }

  public static String objectOid(String path) {
    String value = path != null && path.endsWith("/verify")
        ? path.substring(0, path.length() - "/verify".length()) : path;
    if (!validOid(value)) throw new GitLfsException(404, "Not Found");
    return value;
  }

  public static Batch parseBatch(InputStream input, long maxObjectSize) throws IOException {
    JsonNode root = read(input);
    String operation = root.path("operation").asText("");
    if (!operation.equals("upload") && !operation.equals("download")) {
      throw new GitLfsException(422, "Only 'upload' and 'download' operations are supported");
    }
    if (root.has("transfers")) {
      JsonNode transfers = root.get("transfers");
      boolean basic = false;
      if (!transfers.isArray()) throw new GitLfsException(422, "transfers must be an array");
      for (JsonNode transfer : transfers) {
        if (!transfer.isTextual()) throw new GitLfsException(422, "Invalid transfer adapter");
        basic |= "basic".equals(transfer.textValue());
      }
      if (!basic) throw new GitLfsException(422, "Only the 'basic' transfer adapter is supported");
    }
    if (root.has("hash_algo") && !"sha256".equals(root.get("hash_algo").asText())) {
      throw new GitLfsException(409, "Only sha256 object identities are supported");
    }
    JsonNode objects = root.get("objects");
    if (objects == null || !objects.isArray()) throw new GitLfsException(422, "objects must be an array");
    if (objects.size() > MAX_OBJECTS) throw new GitLfsException(413, "Too many objects");
    List<ObjectRequest> parsed = new ArrayList<>(objects.size());
    for (JsonNode object : objects) parsed.add(object(object, maxObjectSize));
    if (!parsed.isEmpty() && parsed.stream().noneMatch(ObjectRequest::valid)) {
      throw new GitLfsException(422, "No valid objects in request");
    }
    return new Batch(operation, List.copyOf(parsed));
  }

  public static ObjectRequest parseVerify(InputStream input, long maxObjectSize) throws IOException {
    ObjectRequest object = object(read(input), maxObjectSize);
    if (!object.valid()) throw new GitLfsException(422, object.error());
    return object;
  }

  private static ObjectRequest object(JsonNode node, long maxSize) {
    String oid = node.path("oid").isTextual() ? node.path("oid").textValue() : "";
    JsonNode size = node.path("size");
    if (!validOid(oid)) return new ObjectRequest(oid, 0, "Invalid SHA-256 OID");
    if (!size.isIntegralNumber() || !size.canConvertToLong() || size.longValue() < 0) {
      return new ObjectRequest(oid, 0, "size must be a non-negative 64-bit integer");
    }
    if (size.longValue() > maxSize) return new ObjectRequest(oid, size.longValue(), "Object exceeds size limit");
    return new ObjectRequest(oid, size.longValue(), null);
  }

  private static JsonNode read(InputStream input) throws IOException {
    byte[] bytes = input.readNBytes(MAX_BODY_BYTES + 1);
    if (bytes.length > MAX_BODY_BYTES) throw new GitLfsException(413, "LFS JSON body is too large");
    try (var parser = JSON.createParser(bytes)) {
      JsonNode value = JSON.readTree(parser);
      if (value == null || !value.isObject() || parser.nextToken() != null) {
        throw new GitLfsException(422, "Expected one JSON object");
      }
      return value;
    } catch (com.fasterxml.jackson.core.JacksonException invalid) {
      throw new GitLfsException(422, "Invalid LFS JSON");
    }
  }

  public record Batch(String operation, List<ObjectRequest> objects) {
    public boolean upload() { return "upload".equals(operation); }
  }
  public record ObjectRequest(String oid, long size, String error) {
    public boolean valid() { return error == null; }
  }
}
