package com.github.klboke.kkrepo.protocol.huggingface;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/** Bounded Hub JSON projection that keeps clients inside the repository trust boundary. */
public final class HuggingFaceJsonTransformer {
  public static final int SCHEMA_VERSION = 1;
  public static final int MAX_BYTES = 32 * 1024 * 1024;
  private static final int MAX_ENTRIES = 250_000;
  private final ObjectMapper mapper;

  public HuggingFaceJsonTransformer() {
    JsonFactory factory = JsonFactory.builder()
        .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
        .streamReadConstraints(StreamReadConstraints.builder()
            .maxNestingDepth(100)
            .maxStringLength(2 * 1024 * 1024)
            .maxDocumentLength(MAX_BYTES)
            .build())
        .build();
    this.mapper = new ObjectMapper(factory);
  }

  public Result transform(byte[] raw, String upstreamBase, String repositoryBase) {
    if (raw == null || raw.length > MAX_BYTES) {
      throw new IllegalArgumentException("Hugging Face metadata exceeds the size limit");
    }
    try {
      JsonNode root = mapper.readTree(raw);
      if (root == null) throw new IllegalArgumentException("Hugging Face metadata is empty");
      JsonNode source = root.deepCopy();
      Counter counter = new Counter();
      rewrite(root, normalizeBase(upstreamBase), normalizeBase(repositoryBase), counter);
      return new Result(mapper.writeValueAsBytes(root), root, source, counter.removedXetHints);
    } catch (IOException error) {
      throw new IllegalArgumentException("Invalid Hugging Face metadata JSON", error);
    }
  }

  public String rewriteLink(String link, String upstreamBase, String repositoryBase) {
    if (link == null || link.isBlank()) return null;
    String upstream = normalizeBase(upstreamBase);
    String local = normalizeBase(repositoryBase);
    String rewritten = link.replace(upstream + "/api/models/", local + "/api/models/");
    if (rewritten.toLowerCase(java.util.Locale.ROOT).contains("rel=\"xet-")
        || rewritten.toLowerCase(java.util.Locale.ROOT).contains("rel=xet-")) {
      return null;
    }
    return rewritten;
  }

  private void rewrite(JsonNode node, String upstream, String local, Counter counter) {
    if (++counter.entries > MAX_ENTRIES) {
      throw new IllegalArgumentException("Hugging Face metadata has too many entries");
    }
    if (node instanceof ObjectNode object) {
      List<String> remove = new ArrayList<>();
      Iterator<Map.Entry<String, JsonNode>> fields = object.fields();
      while (fields.hasNext()) {
        Map.Entry<String, JsonNode> field = fields.next();
        String name = field.getKey();
        if (name.equalsIgnoreCase("xetHash") || name.equalsIgnoreCase("xet_hash")
            || name.equalsIgnoreCase("xetConnectionInfo")) {
          remove.add(name);
          counter.removedXetHints++;
          continue;
        }
        JsonNode value = field.getValue();
        if (value.isTextual() && isUrlField(name)) {
          String rewritten = rewriteUrl(value.textValue(), upstream, local);
          if (rewritten == null) {
            remove.add(name);
            counter.removedXetHints++;
          } else {
            object.set(name, TextNode.valueOf(rewritten));
          }
        } else {
          rewrite(value, upstream, local, counter);
        }
      }
      remove.forEach(object::remove);
    } else if (node instanceof ArrayNode array) {
      array.forEach(child -> rewrite(child, upstream, local, counter));
    }
  }

  private static boolean isUrlField(String name) {
    return "url".equalsIgnoreCase(name) || "downloadUrl".equalsIgnoreCase(name)
        || "siblingsUrl".equalsIgnoreCase(name);
  }

  private static String rewriteUrl(String value, String upstream, String local) {
    if (value == null || !value.startsWith(upstream + "/")) return value;
    URI uri;
    try {
      uri = URI.create(value);
    } catch (RuntimeException error) {
      return value;
    }
    String path = uri.getRawPath();
    if (path == null || path.contains("/xet-read-token/")) return null;
    String suffix = path + (uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery());
    return local + suffix;
  }

  private static String normalizeBase(String value) {
    if (value == null || value.isBlank()) throw new IllegalArgumentException("URL base is required");
    String normalized = value.trim();
    while (normalized.endsWith("/")) normalized = normalized.substring(0, normalized.length() - 1);
    URI uri = URI.create(normalized);
    if (uri.getScheme() == null || uri.getHost() == null) {
      throw new IllegalArgumentException("Absolute URL base is required");
    }
    return normalized;
  }

  /**
   * The source projection is retained only for durable identity extraction. Client responses use
   * {@link #json()}, where Xet connection hints have already been removed.
   */
  public record Result(byte[] bytes, JsonNode json, JsonNode sourceJson, int removedXetHints) {
  }

  private static final class Counter {
    private int entries;
    private int removedXetHints;
  }
}
