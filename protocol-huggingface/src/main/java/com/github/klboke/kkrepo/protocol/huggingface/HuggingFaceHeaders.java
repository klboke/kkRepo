package com.github.klboke.kkrepo.protocol.huggingface;

import java.util.Locale;
import java.util.Set;

/** Hugging Face response headers that are safe to expose from the local repository. */
public final class HuggingFaceHeaders {
  public static final String REPO_COMMIT = "X-Repo-Commit";
  public static final String LINKED_ETAG = "X-Linked-Etag";
  public static final String LINKED_SIZE = "X-Linked-Size";
  public static final String XET_HASH = "X-Xet-Hash";
  private static final Set<String> PASSTHROUGH = Set.of(
      "ratelimit", "ratelimit-policy", "retry-after", "x-request-id");

  private HuggingFaceHeaders() {
  }

  public static boolean passthrough(String name) {
    return name != null && PASSTHROUGH.contains(name.toLowerCase(Locale.ROOT));
  }

  public static String unquote(String value) {
    if (value == null) return null;
    String trimmed = value.trim();
    if (trimmed.length() >= 2 && trimmed.startsWith("\"") && trimmed.endsWith("\"")) {
      return trimmed.substring(1, trimmed.length() - 1);
    }
    return trimmed;
  }
}
