package com.github.klboke.kkrepo.scanner;

import jakarta.annotation.PostConstruct;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Authenticates scanner API requests before Spring MVC resolves controller arguments.
 *
 * <p>The OCI endpoint accepts a small JSON control envelope rather than artifact bytes. It is
 * buffered under a dedicated hard limit before Jackson sees it, which also bounds collection
 * allocation from attacker-controlled JSON. Catalog and match bodies remain streaming because
 * they carry the bounded artifact/SBOM payload itself.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
@ConditionalOnProperty(
    prefix = "kkrepo.scanner",
    name = "database-update-only",
    havingValue = "false",
    matchIfMissing = true)
final class ScannerRequestSecurityFilter extends OncePerRequestFilter {
  static final String CREDENTIAL_HEADER = "X-KKRepo-Scanner-Credential";
  private static final String API_PREFIX = "/v1/";
  private static final String OCI_SCAN_PATH = "/v1/oci/scan";

  private final ScannerAdapterProperties properties;

  ScannerRequestSecurityFilter(ScannerAdapterProperties properties) {
    this.properties = properties;
  }

  @PostConstruct
  void requireConfiguredCredential() {
    if (properties.getServiceCredential() == null
        || properties.getServiceCredential().isBlank()) {
      throw new IllegalStateException(
          "kkrepo.scanner.service-credential must be configured");
    }
  }

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    return !requestPath(request).startsWith(API_PREFIX);
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request,
      HttpServletResponse response,
      FilterChain filterChain)
      throws ServletException, IOException {
    if (!authorized(request.getHeader(CREDENTIAL_HEADER))) {
      writeError(
          response,
          HttpServletResponse.SC_UNAUTHORIZED,
          "SCANNER_UNAUTHORIZED",
          "Scanner service credential is invalid");
      return;
    }

    HttpServletRequest secured = request;
    if ("POST".equalsIgnoreCase(request.getMethod())
        && OCI_SCAN_PATH.equals(requestPath(request))) {
      int maximum = properties.getMaxOciRequestBytes();
      if (request.getContentLengthLong() > maximum) {
        writeTooLarge(response);
        return;
      }
      byte[] body = request.getInputStream().readNBytes(maximum + 1);
      if (body.length > maximum) {
        writeTooLarge(response);
        return;
      }
      secured = new BufferedRequest(request, body);
    }
    filterChain.doFilter(secured, response);
  }

  private boolean authorized(String actual) {
    String expected = properties.getServiceCredential();
    if (expected == null || expected.isBlank()) {
      return false;
    }
    byte[] left = expected.getBytes(StandardCharsets.UTF_8);
    byte[] right = actual == null ? new byte[0] : actual.getBytes(StandardCharsets.UTF_8);
    return MessageDigest.isEqual(left, right);
  }

  private static String requestPath(HttpServletRequest request) {
    String uri = request.getRequestURI();
    String context = request.getContextPath();
    if (context != null && !context.isEmpty() && uri.startsWith(context)) {
      return uri.substring(context.length());
    }
    return uri;
  }

  private static void writeTooLarge(HttpServletResponse response) throws IOException {
    writeError(
        response,
        HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE,
        "SCANNER_OCI_REQUEST_TOO_LARGE",
        "Scanner OCI request body exceeds the configured limit");
  }

  private static void writeError(
      HttpServletResponse response, int status, String code, String message)
      throws IOException {
    response.setStatus(status);
    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
    response.setCharacterEncoding(StandardCharsets.UTF_8.name());
    response.setHeader("Cache-Control", "no-store");
    response.getWriter().write(
        "{\"code\":\"" + code + "\",\"message\":\"" + message
            + "\",\"retryable\":false}");
  }

  private static final class BufferedRequest extends HttpServletRequestWrapper {
    private final byte[] body;

    private BufferedRequest(HttpServletRequest request, byte[] body) {
      super(request);
      this.body = body;
    }

    @Override
    public ServletInputStream getInputStream() {
      return new ByteArrayServletInputStream(body);
    }

    @Override
    public BufferedReader getReader() {
      return new BufferedReader(
          new InputStreamReader(getInputStream(), StandardCharsets.UTF_8));
    }

    @Override
    public int getContentLength() {
      return body.length;
    }

    @Override
    public long getContentLengthLong() {
      return body.length;
    }
  }

  private static final class ByteArrayServletInputStream extends ServletInputStream {
    private final ByteArrayInputStream delegate;

    private ByteArrayServletInputStream(byte[] body) {
      this.delegate = new ByteArrayInputStream(body);
    }

    @Override
    public int read() {
      return delegate.read();
    }

    @Override
    public int read(byte[] bytes, int offset, int length) {
      return delegate.read(bytes, offset, length);
    }

    @Override
    public int available() {
      return delegate.available();
    }

    @Override
    public boolean isFinished() {
      return delegate.available() == 0;
    }

    @Override
    public boolean isReady() {
      return true;
    }

    @Override
    public void setReadListener(ReadListener readListener) {
      // Spring MVC consumes this bounded request synchronously.
    }
  }
}
