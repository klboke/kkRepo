package com.github.klboke.kkrepo.plugin.api;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/** Framework-agnostic repository request passed to a plugin handler. */
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
    Map<String, Object> attributes) {

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
