package com.github.klboke.kkrepo.server.routing.builtin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.klboke.kkrepo.plugin.api.BuiltinProtocolOperations;
import com.github.klboke.kkrepo.plugin.api.MultipartPart;
import com.github.klboke.kkrepo.plugin.api.RepositoryRequest;
import com.github.klboke.kkrepo.plugin.api.RepositoryResponse;
import com.github.klboke.kkrepo.server.maven.MavenExceptions;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntime;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntimeRegistry;
import com.github.klboke.kkrepo.server.routing.RepositoryProtocolRequest;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

/**
 * Host adapter for the extracted catch-all plugin. The plugin owns route precedence; this adapter
 * resolves the live runtime and reuses the existing transactional handler. Streaming bodies stay
 * streamed in both directions.
 */
@Component
public class ServerBuiltinProtocolOperations implements BuiltinProtocolOperations {
  private final BuiltinRepositoryProtocolHandler handler;
  private final RepositoryRuntimeRegistry runtimes;
  private final ObjectMapper json;

  public ServerBuiltinProtocolOperations(
      BuiltinRepositoryProtocolHandler handler,
      RepositoryRuntimeRegistry runtimes,
      ObjectMapper json) {
    this.handler = handler;
    this.runtimes = runtimes;
    this.json = json;
  }

  @Override
  public RepositoryResponse handle(RepositoryRequest request) throws IOException {
    if (!(request.context().hostContext() instanceof HttpServletRequest servlet)) {
      throw new IllegalStateException("Built-in protocol host request is not available");
    }
    RepositoryRuntime runtime = runtimes.resolve(request.repository().name())
        .orElseThrow(() -> new MavenExceptions.MavenNotFoundException(
            "Repository not found: " + request.repository().name()));
    MultipartFile multipartFile = request.multipart() == null
        ? null
        : new PartMultipartFile(request.multipart());
    ResponseEntity<?> response;
    try {
      response = handler.handle(new RepositoryProtocolRequest(
          runtime,
          request.repositoryName(),
          request.path(),
          HttpMethod.valueOf(request.method()),
          servlet,
          header(request.headers(), "Content-Type"),
          multipartFile));
    } catch (ServletException error) {
      throw new IOException("Built-in protocol request failed", error);
    }
    return toNeutral(response);
  }

  private RepositoryResponse toNeutral(ResponseEntity<?> response) throws IOException {
    Map<String, List<String>> headers = new LinkedHashMap<>();
    HttpHeaders source = response.getHeaders();
    source.forEach((name, values) -> headers.put(name, new ArrayList<>(values)));
    int status = response.getStatusCode().value();
    Object body = response.getBody();
    if (body == null) {
      return new RepositoryResponse(status, headers, new byte[0]);
    }
    if (body instanceof StreamingResponseBody streaming) {
      return RepositoryResponse.streaming(status, headers, streaming::writeTo);
    }
    if (body instanceof byte[] bytes) {
      return new RepositoryResponse(status, headers, bytes);
    }
    if (body instanceof String text) {
      return new RepositoryResponse(
          status, headers, text.getBytes(StandardCharsets.UTF_8));
    }
    return new RepositoryResponse(status, headers, json.writeValueAsBytes(body));
  }

  private static String header(Map<String, List<String>> headers, String name) {
    for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
      if (entry.getKey() != null && entry.getKey().equalsIgnoreCase(name)
          && entry.getValue() != null && !entry.getValue().isEmpty()) {
        return entry.getValue().getFirst();
      }
    }
    return null;
  }

  private record PartMultipartFile(MultipartPart part) implements MultipartFile {
    @Override
    public String getName() {
      return part.name() == null ? "" : part.name();
    }

    @Override
    public String getOriginalFilename() {
      return part.filename();
    }

    @Override
    public String getContentType() {
      return part.contentType();
    }

    @Override
    public boolean isEmpty() {
      return part.content().length == 0;
    }

    @Override
    public long getSize() {
      return part.content().length;
    }

    @Override
    public byte[] getBytes() {
      return part.content();
    }

    @Override
    public InputStream getInputStream() {
      return new ByteArrayInputStream(part.content());
    }

    @Override
    public void transferTo(File destination) throws IOException {
      Files.write(destination.toPath(), part.content());
    }
  }
}
