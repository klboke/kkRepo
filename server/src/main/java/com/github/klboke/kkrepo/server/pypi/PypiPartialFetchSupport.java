package com.github.klboke.kkrepo.server.pypi;

import com.github.klboke.kkrepo.server.http.SingleRangePartialFetchSupport;
import com.github.klboke.kkrepo.server.http.SingleRangePartialFetchSupport.ResponseAdapter;
import jakarta.servlet.http.HttpServletRequest;
import java.io.InputStream;
import java.time.Instant;
import java.util.Map;
import org.springframework.http.HttpHeaders;

/**
 * Single-range partial fetch support for PyPI package downloads.
 */
public final class PypiPartialFetchSupport {
  private static final SingleRangePartialFetchSupport<PypiResponse> SUPPORT =
      new SingleRangePartialFetchSupport<>();
  private static final ResponseAdapter<PypiResponse> ADAPTER = new ResponseAdapter<>() {
    @Override
    public int status(PypiResponse response) {
      return response.status();
    }

    @Override
    public boolean hasBody(PypiResponse response) {
      return response.hasBody();
    }

    @Override
    public InputStream body(PypiResponse response) {
      return response.body();
    }

    @Override
    public long contentLength(PypiResponse response) {
      return response.contentLength();
    }

    @Override
    public String contentType(PypiResponse response) {
      return response.contentType();
    }

    @Override
    public String etag(PypiResponse response) {
      return response.etag();
    }

    @Override
    public Instant lastModified(PypiResponse response) {
      return response.lastModified();
    }

    @Override
    public Map<String, String> headers(PypiResponse response) {
      return response.headers();
    }

    @Override
    public PypiResponse ok(
        InputStream body, long contentLength, String contentType, String etag, Instant lastModified) {
      return PypiResponse.ok(body, contentLength, contentType, etag, lastModified);
    }

    @Override
    public PypiResponse noBody(int status) {
      return PypiResponse.noBody(status);
    }

    @Override
    public PypiResponse withStatus(PypiResponse response, int status) {
      return response.withStatus(status);
    }

    @Override
    public PypiResponse withHeader(PypiResponse response, String name, String value) {
      return response.withHeader(name, value);
    }

    @Override
    public void closeBodyIfOpen(PypiResponse response) {
      response.closeBodyIfOpen();
    }
  };

  public PypiResponse apply(HttpServletRequest request, PypiResponse response) {
    return apply(
        request.getMethod(),
        request.getHeader(HttpHeaders.RANGE),
        request.getHeader(HttpHeaders.IF_RANGE),
        response);
  }

  public PypiResponse apply(String method, String rangeHeader, String ifRangeHeader, PypiResponse response) {
    return SUPPORT.apply(method, rangeHeader, ifRangeHeader, response, ADAPTER);
  }
}
