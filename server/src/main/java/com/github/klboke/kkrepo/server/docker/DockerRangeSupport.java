package com.github.klboke.kkrepo.server.docker;

import com.github.klboke.kkrepo.server.http.SingleRangePartialFetchSupport;
import jakarta.servlet.http.HttpServletRequest;
import java.io.InputStream;
import java.time.Instant;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class DockerRangeSupport {
  private final SingleRangePartialFetchSupport<DockerResponse> ranges = new SingleRangePartialFetchSupport<>();

  public DockerResponse apply(HttpServletRequest request, DockerResponse response) {
    if (com.github.klboke.kkrepo.server.proxy.ProgressiveProxyService.rangeHandled(request)) return response;
    return ranges.apply(
        request == null ? null : request.getMethod(),
        request == null ? null : request.getHeader("Range"),
        request == null ? null : request.getHeader("If-Range"),
        response,
        new Adapter());
  }

  private static final class Adapter implements SingleRangePartialFetchSupport.ResponseAdapter<DockerResponse> {
    @Override
    public int status(DockerResponse response) {
      return response.status();
    }

    @Override
    public boolean hasBody(DockerResponse response) {
      return response.hasBody();
    }

    @Override
    public InputStream body(DockerResponse response) {
      return response.body();
    }

    @Override
    public long contentLength(DockerResponse response) {
      return response.contentLength();
    }

    @Override
    public String contentType(DockerResponse response) {
      return response.contentType();
    }

    @Override
    public String etag(DockerResponse response) {
      return response.headers().get("Docker-Content-Digest");
    }

    @Override
    public Instant lastModified(DockerResponse response) {
      return response.lastModified();
    }

    @Override
    public Map<String, String> headers(DockerResponse response) {
      return response.headers();
    }

    @Override
    public DockerResponse ok(
        InputStream body,
        long contentLength,
        String contentType,
        String etag,
        Instant lastModified) {
      return DockerResponse.body(200, () -> body, contentLength, contentType, lastModified);
    }

    @Override
    public DockerResponse noBody(int status) {
      return DockerResponse.noBody(status);
    }

    @Override
    public DockerResponse withStatus(DockerResponse response, int status) {
      return response.withStatus(status);
    }

    @Override
    public DockerResponse withHeader(DockerResponse response, String name, String value) {
      return response.withHeader(name, value);
    }
  }
}
