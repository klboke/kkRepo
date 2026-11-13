package com.github.klboke.kkrepo.server.routing.gitlfs;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.klboke.kkrepo.plugin.api.RepositoryRequest;
import com.github.klboke.kkrepo.plugin.api.RepositoryResponse;
import com.github.klboke.kkrepo.protocol.gitlfs.GitLfsWriteOperations;
import com.github.klboke.kkrepo.server.maven.MavenExceptions;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntime;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntimeRegistry;
import com.github.klboke.kkrepo.server.routing.RepositoryProtocolRequest;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;

/**
 * Host adapter for the extracted Git LFS write plugin. It reuses the existing handler and its
 * transactional/service layer, and only converts the neutral request/response.
 */
@Component
public class ServerGitLfsWriteOperations implements GitLfsWriteOperations {
  private final GitLfsProtocolHandler handler;
  private final RepositoryRuntimeRegistry runtimes;
  private final ObjectMapper json;

  public ServerGitLfsWriteOperations(
      GitLfsProtocolHandler handler,
      RepositoryRuntimeRegistry runtimes,
      ObjectMapper json) {
    this.handler = handler;
    this.runtimes = runtimes;
    this.json = json;
  }

  @Override
  public RepositoryResponse handle(RepositoryRequest request) throws IOException {
    if (!(request.context().hostContext() instanceof HttpServletRequest servlet)) {
      throw new IllegalStateException("Git LFS host request is not available");
    }
    RepositoryRuntime runtime = runtimes.resolve(request.repository().name())
        .orElseThrow(() -> new MavenExceptions.MavenNotFoundException(
            "Repository not found: " + request.repository().name()));
    ResponseEntity<?> response = handler.handle(new RepositoryProtocolRequest(
        runtime,
        request.repositoryName(),
        request.path(),
        HttpMethod.valueOf(request.method()),
        servlet,
        request.headers().getOrDefault("Content-Type", List.of()).stream().findFirst().orElse(null),
        null));
    HttpHeaders headers = response.getHeaders();
    Map<String, List<String>> headerMap = new LinkedHashMap<>();
    headers.forEach((name, values) -> headerMap.put(name, new ArrayList<>(values)));
    return new RepositoryResponse(response.getStatusCode().value(), headerMap,
        body(response.getBody()));
  }

  private byte[] body(Object body) throws IOException {
    if (body == null) return new byte[0];
    if (body instanceof byte[] bytes) return bytes;
    if (body instanceof String text) return text.getBytes(StandardCharsets.UTF_8);
    if (body instanceof Map<?, ?>) return json.writeValueAsBytes(body);
    throw new IllegalStateException(
        "Git LFS write operation returned an unsupported body: " + body.getClass().getName());
  }
}
