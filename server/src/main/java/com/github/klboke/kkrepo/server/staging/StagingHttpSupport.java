package com.github.klboke.kkrepo.server.staging;

import com.github.klboke.kkrepo.server.blob.TempBlobFiles;
import com.github.klboke.kkrepo.server.config.RepositoryUrlPrefix;
import com.github.klboke.kkrepo.server.http.ConditionalResponses;
import com.github.klboke.kkrepo.server.maven.MavenExceptions;
import com.github.klboke.kkrepo.server.maven.MavenResponse;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntime;
import com.github.klboke.kkrepo.server.security.AuthenticatedSubject;
import com.github.klboke.kkrepo.server.security.ClientAddress;
import com.github.klboke.kkrepo.server.security.ForwardedHeaderPolicy;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.io.InputStream;
import java.util.Optional;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

/**
 * HTTP boundary shared by every protocol handler for staging selectors.
 *
 * <p>Handlers call this before their normal routing. Reads that have no staged item return empty so
 * the request falls back to live content; staged deletes return 404 instead of falling back. The
 * session comes from {@link StagingSelector} so header, query parameter and credential binding are
 * honoured consistently, and a contradictory selector is rejected before any live effect.
 */
@Component
public class StagingHttpSupport {
  private final StagingService staging;
  private final ForwardedHeaderPolicy forwardedHeaderPolicy;

  public StagingHttpSupport(StagingService staging) {
    this(staging, null);
  }

  @org.springframework.beans.factory.annotation.Autowired
  public StagingHttpSupport(
      StagingService staging, ForwardedHeaderPolicy forwardedHeaderPolicy) {
    this.staging = staging;
    this.forwardedHeaderPolicy = forwardedHeaderPolicy;
  }

  public Optional<ResponseEntity<?>> dispatch(
      RepositoryRuntime runtime, String path, HttpServletRequest request) throws IOException {
    String sessionKey = StagingSelector.resolve(request);
    if (sessionKey == null) {
      return Optional.empty();
    }
    return switch (request.getMethod().toUpperCase(java.util.Locale.ROOT)) {
      case "GET", "HEAD" -> stagedRead(runtime, sessionKey, path, request);
      case "PUT", "DELETE" -> Optional.of(stagedWrite(runtime, sessionKey, path, request));
      case "POST" -> {
        if (staging.supportsPublication(runtime.format(), path)) {
          yield Optional.of(stagedWrite(runtime, sessionKey, path, request));
        }
        throw new MavenExceptions.MethodNotAllowed(
            "Staging does not support POST for " + runtime.format().id()
                + " repositories at " + path);
      }
      default -> throw new MavenExceptions.MethodNotAllowed(
          "Staging does not support " + request.getMethod() + " on repository content");
    };
  }

  private Optional<ResponseEntity<?>> stagedRead(
      RepositoryRuntime runtime,
      String sessionKey,
      String path,
      HttpServletRequest request) {
    boolean headOnly = "HEAD".equalsIgnoreCase(request.getMethod());
    Optional<MavenResponse> staged = staging.getStagedContent(
        runtime, sessionKey, path, headOnly, subject(request),
        repositoryBaseUrl(runtime, request));
    if (staged.isEmpty()) {
      return Optional.empty();
    }
    return Optional.of(headOnly
        ? headResponse(staged.get(), request)
        : streamingResponse(staged.get(), request));
  }

  private ResponseEntity<?> stagedWrite(
      RepositoryRuntime runtime,
      String sessionKey,
      String path,
      HttpServletRequest request) throws IOException {
    if ("DELETE".equalsIgnoreCase(request.getMethod())) {
      int deleted = staging.deleteRaw(
          runtime, sessionKey, path, subject(request), ClientAddress.from(request));
      return ResponseEntity.status(
          deleted == 0 ? HttpStatus.NOT_FOUND : HttpStatus.NO_CONTENT).build();
    }
    StagingService.StagingWriteResult result = staging.putRequest(
        runtime, sessionKey, path, request,
        subject(request), ClientAddress.from(request));
    return ResponseEntity.status(HttpStatus.CREATED)
        .header(StagingService.HEADER, String.valueOf(result.sessionId()))
        .header("X-KkRepo-Staging-Action", result.action())
        .build();
  }

  private String repositoryBaseUrl(
      RepositoryRuntime runtime, HttpServletRequest request) {
    String prefix = forwardedHeaderPolicy != null
        ? forwardedHeaderPolicy.serverBaseUrl(request)
        : request.getScheme() + "://" + request.getServerName()
            + (request.getServerPort() > 0 ? ":" + request.getServerPort() : "");
    return prefix + request.getContextPath() + RepositoryUrlPrefix.path(request) + runtime.name();
  }

  private static ResponseEntity<?> headResponse(
      MavenResponse response, HttpServletRequest request) {
    if (ConditionalResponses.shouldReturnNotModified(
        request, response.status(), response.etag(), response.lastModified())) {
      response.closeBodyIfOpen();
      return ResponseEntity.status(HttpStatus.NOT_MODIFIED)
          .headers(notModifiedHeaders(response)).build();
    }
    return ResponseEntity.status(response.status())
        .headers(responseHeaders(response, true)).build();
  }

  private static ResponseEntity<StreamingResponseBody> streamingResponse(
      MavenResponse response, HttpServletRequest request) {
    if (ConditionalResponses.shouldReturnNotModified(
        request, response.status(), response.etag(), response.lastModified())) {
      response.closeBodyIfOpen();
      return ResponseEntity.status(HttpStatus.NOT_MODIFIED)
          .headers(notModifiedHeaders(response)).build();
    }
    HttpHeaders headers = responseHeaders(response, true);
    if (!response.hasBody()) {
      return ResponseEntity.status(response.status()).headers(headers).body(null);
    }
    InputStream body = response.body();
    long contentLength = response.contentLength();
    StreamingResponseBody streaming = output ->
        TempBlobFiles.copyResponse(body, output, request, contentLength);
    return ResponseEntity.status(response.status()).headers(headers).body(streaming);
  }

  private static HttpHeaders notModifiedHeaders(MavenResponse response) {
    HttpHeaders headers = new HttpHeaders();
    ConditionalResponses.addValidators(headers, response.etag(), response.lastModified());
    return headers;
  }

  private static HttpHeaders responseHeaders(MavenResponse response, boolean entityHeaders) {
    HttpHeaders headers = new HttpHeaders();
    if (entityHeaders && response.contentType() != null) {
      try {
        headers.setContentType(MediaType.parseMediaType(response.contentType()));
      } catch (RuntimeException ignored) {
        headers.add(HttpHeaders.CONTENT_TYPE, response.contentType());
      }
    }
    if (entityHeaders && response.contentLength() > 0) {
      headers.setContentLength(response.contentLength());
    }
    ConditionalResponses.addValidators(headers, response.etag(), response.lastModified());
    response.headers().forEach(headers::add);
    return headers;
  }

  private static com.github.klboke.kkrepo.auth.PermissionSubject subject(
      HttpServletRequest request) {
    Object value = request.getAttribute(AuthenticatedSubject.REQUEST_ATTRIBUTE);
    if (value instanceof AuthenticatedSubject authenticated) {
      return authenticated.permissionSubject();
    }
    return null;
  }
}
