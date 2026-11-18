package com.github.klboke.kkrepo.server.maven;

import com.github.klboke.kkrepo.server.http.SingleRangePartialFetchSupport;
import com.github.klboke.kkrepo.server.http.SingleRangePartialFetchSupport.ResponseAdapter;
import jakarta.servlet.http.HttpServletRequest;
import java.io.InputStream;
import java.time.Instant;
import java.util.Map;
import org.springframework.http.HttpHeaders;

/**
 * Single-range partial fetch support for Maven content routes. Metadata routes do not call this
 * helper because Nexus intentionally does not attach PartialFetchHandler there.
 */
public final class MavenPartialFetchSupport {
  private static final SingleRangePartialFetchSupport<MavenResponse> SUPPORT =
      new SingleRangePartialFetchSupport<>();
  private static final ResponseAdapter<MavenResponse> ADAPTER = new ResponseAdapter<>() {
    @Override
    public int status(MavenResponse response) {
      return response.status();
    }

    @Override
    public boolean hasBody(MavenResponse response) {
      return response.hasBody();
    }

    @Override
    public InputStream body(MavenResponse response) {
      return response.body();
    }

    @Override
    public long contentLength(MavenResponse response) {
      return response.contentLength();
    }

    @Override
    public String contentType(MavenResponse response) {
      return response.contentType();
    }

    @Override
    public String etag(MavenResponse response) {
      return response.etag();
    }

    @Override
    public Instant lastModified(MavenResponse response) {
      return response.lastModified();
    }

    @Override
    public Map<String, String> headers(MavenResponse response) {
      return response.headers();
    }

    @Override
    public MavenResponse ok(
        InputStream body, long contentLength, String contentType, String etag, Instant lastModified) {
      return MavenResponse.ok(body, contentLength, contentType, etag, lastModified);
    }

    @Override
    public MavenResponse noBody(int status) {
      return MavenResponse.noBody(status);
    }

    @Override
    public MavenResponse withStatus(MavenResponse response, int status) {
      return response.withStatus(status);
    }

    @Override
    public MavenResponse withHeader(MavenResponse response, String name, String value) {
      return response.withHeader(name, value);
    }

    @Override
    public void closeBodyIfOpen(MavenResponse response) {
      response.closeBodyIfOpen();
    }
  };

  public MavenResponse apply(HttpServletRequest request, MavenResponse response) {
    if (com.github.klboke.kkrepo.server.proxy.ProgressiveProxyService.rangeHandled(request)) return response;
    return apply(
        request.getMethod(),
        request.getHeader(HttpHeaders.RANGE),
        request.getHeader(HttpHeaders.IF_RANGE),
        response);
  }

  MavenResponse apply(String method, String rangeHeader, String ifRangeHeader, MavenResponse response) {
    return SUPPORT.apply(method, rangeHeader, ifRangeHeader, response, ADAPTER);
  }
}
