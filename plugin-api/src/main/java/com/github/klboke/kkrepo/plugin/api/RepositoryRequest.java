package com.github.klboke.kkrepo.plugin.api;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Framework-agnostic repository request passed to a plugin handler.
 *
 * <p>The body is either buffered in {@link #body()} or, when the handler declares
 * {@link RepositoryProtocolHandler#streamsRequestBody()}, exposed unbuffered as {@link #bodyStream()}.
 * {@link #openBody()} reads whichever is present.
 */
public record RepositoryRequest(
    RepositoryDescriptor repository,
    String repositoryName,
    String path,
    String method,
    Map<String, List<String>> headers,
    Map<String, String> queryParameters,
    byte[] body,
    MultipartPart multipart,
    RepositoryContext context,
    Map<String, Object> attributes,
    InputStream bodyStream) {

  public RepositoryRequest {
    if (repository == null) {
      throw new IllegalArgumentException("Repository descriptor is required");
    }
    repositoryName = repositoryName == null || repositoryName.isBlank()
        ? repository.name()
        : repositoryName.trim();
    path = normalizePath(path);
    method = method == null || method.isBlank() ? "GET" : method.trim().toUpperCase(Locale.ROOT);
    headers = copyHeaders(headers);
    queryParameters = queryParameters == null ? Map.of() : Map.copyOf(queryParameters);
    body = body == null ? new byte[0] : body.clone();
    context = context == null ? RepositoryContext.empty(repository) : context;
    if (!Objects.equals(repository, context.repository())) {
      throw new IllegalArgumentException("Repository context does not match the request repository");
    }
    attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
    if (bodyStream != null && body.length > 0) {
      throw new IllegalArgumentException("A streamed request cannot also carry a buffered body");
    }
  }

  /** Request with a buffered body. */
  public RepositoryRequest(
      RepositoryDescriptor repository,
      String repositoryName,
      String path,
      String method,
      Map<String, List<String>> headers,
      Map<String, String> queryParameters,
      byte[] body,
      MultipartPart multipart,
      RepositoryContext context,
      Map<String, Object> attributes) {
    this(repository, repositoryName, path, method, headers, queryParameters, body, multipart, context,
        attributes, null);
  }

  /** Compatibility constructor for callers that predate the host context. */
  public RepositoryRequest(
      RepositoryDescriptor repository,
      String repositoryName,
      String path,
      String method,
      Map<String, List<String>> headers,
      Map<String, String> queryParameters,
      byte[] body,
      MultipartPart multipart,
      Map<String, Object> attributes) {
    this(
        repository,
        repositoryName,
        path,
        method,
        headers,
        queryParameters,
        body,
        multipart,
        RepositoryContext.empty(repository),
        attributes);
  }

  /** First value of a request header, matched case-insensitively; {@code null} when absent. */
  public String header(String name) {
    if (name == null) return null;
    for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
      if (entry.getKey().equalsIgnoreCase(name) && !entry.getValue().isEmpty()) {
        return entry.getValue().getFirst();
      }
    }
    return null;
  }

  /** Whether the body is the unbuffered client stream rather than {@link #body()}. */
  public boolean isBodyStreamed() {
    return bodyStream != null;
  }

  /**
   * The request body as a stream: the unbuffered client stream when the handler streams request
   * bodies, otherwise the buffered bytes. A streamed body can be read only once.
   */
  public InputStream openBody() {
    return bodyStream != null ? bodyStream : new ByteArrayInputStream(body);
  }

  public String bodyText() {
    return new String(body, StandardCharsets.UTF_8);
  }

  @Override
  public byte[] body() {
    return body.clone();
  }

  public static String normalizePath(String path) {
    if (path == null) return "";
    String normalized = path.trim();
    while (normalized.startsWith("/")) {
      normalized = normalized.substring(1);
    }
    return normalized;
  }

  private static Map<String, List<String>> copyHeaders(Map<String, List<String>> headers) {
    if (headers == null) return Map.of();
    Map<String, List<String>> copy = new LinkedHashMap<>();
    headers.forEach((name, values) -> {
      if (name != null && values != null) {
        copy.put(name, List.copyOf(values));
      }
    });
    return Map.copyOf(copy);
  }
}
