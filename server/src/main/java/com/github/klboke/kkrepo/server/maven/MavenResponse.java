package com.github.klboke.kkrepo.server.maven;

import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * Lightweight value object used by the facet services to describe the response the controller
 * should emit. Keeps Spring/HTTP concerns out of the service layer so the same code paths can be
 * reused for HEAD vs GET and (later) for internal calls between group members.
 */
public final class MavenResponse {
  private final int status;
  private final InputStream body;
  private final BodySupplier bodySupplier;
  private final long contentLength;
  private final String contentType;
  private final String etag;
  private final Instant lastModified;
  private final Map<String, String> headers;
  private final Map<String, Object> internalAttributes;

  private MavenResponse(int status, InputStream body, long contentLength, String contentType,
      String etag, Instant lastModified, Map<String, String> headers) {
    this(status, body, null, contentLength, contentType, etag, lastModified, headers,
        new HashMap<>());
  }

  private MavenResponse(int status, InputStream body, BodySupplier bodySupplier,
      long contentLength, String contentType, String etag, Instant lastModified,
      Map<String, String> headers) {
    this(status, body, bodySupplier, contentLength, contentType, etag, lastModified, headers,
        new HashMap<>());
  }

  private MavenResponse(int status, InputStream body, BodySupplier bodySupplier,
      long contentLength, String contentType, String etag, Instant lastModified,
      Map<String, String> headers, Map<String, Object> internalAttributes) {
    this.status = status;
    this.body = body;
    this.bodySupplier = bodySupplier;
    this.contentLength = contentLength;
    this.contentType = contentType;
    this.etag = etag;
    this.lastModified = lastModified;
    this.headers = headers;
    this.internalAttributes = internalAttributes;
  }

  public static MavenResponse ok(InputStream body, long contentLength, String contentType,
      String etag, Instant lastModified) {
    return new MavenResponse(200, body, contentLength, contentType, etag, lastModified, new HashMap<>());
  }

  public static MavenResponse ok(BodySupplier bodySupplier, long contentLength, String contentType,
      String etag, Instant lastModified) {
    return new MavenResponse(200, null, bodySupplier, contentLength, contentType, etag, lastModified, new HashMap<>());
  }

  public static MavenResponse created() {
    return new MavenResponse(201, null, 0, null, null, null, new HashMap<>());
  }

  public static MavenResponse noBody(int status) {
    return new MavenResponse(status, null, 0, null, null, null, new HashMap<>());
  }

  public static MavenResponse noBody(int status, long contentLength, String contentType,
      String etag, Instant lastModified) {
    return new MavenResponse(status, null, contentLength, contentType, etag, lastModified, new HashMap<>());
  }

  public static MavenResponse notModified(String etag, Instant lastModified) {
    return new MavenResponse(304, null, 0, null, etag, lastModified, new HashMap<>());
  }

  public MavenResponse withStatus(int status) {
    return new MavenResponse(
        status, body, bodySupplier, contentLength, contentType, etag, lastModified, headers,
        internalAttributes);
  }

  public MavenResponse withHeader(String name, String value) {
    headers.put(name, value);
    return this;
  }

  /** Attaches service-internal context that controllers never expose as HTTP headers. */
  public MavenResponse withInternalAttribute(String name, Object value) {
    if (value == null) {
      internalAttributes.remove(name);
    } else {
      internalAttributes.put(name, value);
    }
    return this;
  }

  public Object internalAttribute(String name) {
    return internalAttributes.get(name);
  }

  void closeBodyIfOpen() {
    if (body == null) {
      return;
    }
    try {
      body.close();
    } catch (IOException ignored) {
    }
  }

  public int status() { return status; }
  public boolean hasBody() { return body != null || bodySupplier != null; }
  public InputStream body() { return body != null || bodySupplier == null ? body : bodySupplier.open(); }
  public long contentLength() { return contentLength; }
  public String contentType() { return contentType; }
  public String etag() { return etag; }
  public Instant lastModified() { return lastModified; }
  public Map<String, String> headers() { return headers; }

  @FunctionalInterface
  public interface BodySupplier {
    InputStream open();
  }
}
