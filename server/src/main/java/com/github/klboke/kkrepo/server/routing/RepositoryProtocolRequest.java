package com.github.klboke.kkrepo.server.routing;

import com.github.klboke.kkrepo.server.maven.RepositoryRuntime;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Objects;
import org.springframework.http.HttpMethod;
import org.springframework.web.multipart.MultipartFile;

/** Immutable request context passed from the HTTP entry point to a protocol handler. */
public record RepositoryProtocolRequest(
    RepositoryRuntime runtime,
    String repositoryName,
    String path,
    HttpMethod method,
    HttpServletRequest servletRequest,
    String contentType,
    MultipartFile multipartFile) {

  public RepositoryProtocolRequest {
    Objects.requireNonNull(runtime, "runtime");
    Objects.requireNonNull(repositoryName, "repositoryName");
    path = RepositoryProtocolRoute.normalizePath(path);
    Objects.requireNonNull(method, "method");
    Objects.requireNonNull(servletRequest, "servletRequest");
  }
}
