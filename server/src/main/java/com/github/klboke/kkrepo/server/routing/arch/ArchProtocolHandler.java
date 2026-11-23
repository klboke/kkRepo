package com.github.klboke.kkrepo.server.routing.arch;

import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.protocol.arch.ArchPath;
import com.github.klboke.kkrepo.protocol.arch.ArchPathParser;
import com.github.klboke.kkrepo.server.arch.ArchService;
import com.github.klboke.kkrepo.server.blob.TempBlobFiles;
import com.github.klboke.kkrepo.server.http.ConditionalResponses;
import com.github.klboke.kkrepo.server.maven.MavenExceptions;
import com.github.klboke.kkrepo.server.maven.MavenResponse;
import com.github.klboke.kkrepo.server.routing.RepositoryProtocolHandler;
import com.github.klboke.kkrepo.server.routing.RepositoryProtocolRequest;
import com.github.klboke.kkrepo.server.routing.RepositoryProtocolRoute;
import com.github.klboke.kkrepo.server.security.AuthenticatedSubject;
import com.github.klboke.kkrepo.server.security.ClientAddress;
import com.github.klboke.kkrepo.server.staging.StagingHttpSupport;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.io.InputStream;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

/** Focused handler for the flat pacman repository protocol. */
@Component
public class ArchProtocolHandler implements RepositoryProtocolHandler {
  private static final List<RepositoryProtocolRoute> ROUTES =
      java.util.Arrays.stream(com.github.klboke.kkrepo.core.RepositoryType.values())
          .flatMap(type -> List.of(HttpMethod.GET, HttpMethod.HEAD, HttpMethod.POST,
              HttpMethod.PUT, HttpMethod.DELETE).stream()
              .map(method -> RepositoryProtocolRoute.anyPath(
                  RepositoryFormat.ARCH, type, method, 0)))
          .toList();

  private final ArchPathParser parser = new ArchPathParser();
  private final ArchService service;
  private com.github.klboke.kkrepo.server.repositories.GroupWriteTargetService groupWriteTarget;
  private StagingHttpSupport stagingHttp;

  public ArchProtocolHandler(ArchService service) {
    this.service = service;
  }

  @org.springframework.beans.factory.annotation.Autowired(required = false)
  public void setGroupWriteTargetService(
      com.github.klboke.kkrepo.server.repositories.GroupWriteTargetService groupWriteTarget) {
    this.groupWriteTarget = groupWriteTarget;
  }

  @Autowired(required = false)
  public void setStagingHttpSupport(StagingHttpSupport stagingHttp) {
    this.stagingHttp = stagingHttp;
  }

  @Override
  public Collection<RepositoryProtocolRoute> routes() {
    return ROUTES;
  }

  @Override
  public ResponseEntity<?> handle(RepositoryProtocolRequest context) throws IOException {
    HttpServletRequest request = context.servletRequest();
    com.github.klboke.kkrepo.server.maven.RepositoryRuntime runtime = context.runtime();
    if (context.method() != HttpMethod.GET && context.method() != HttpMethod.HEAD
        && runtime.isGroup() && groupWriteTarget != null) {
      runtime = groupWriteTarget.requireWriteTarget(runtime);
    }
    if (stagingHttp != null) {
      Optional<ResponseEntity<?>> staged = stagingHttp.dispatch(
          runtime, context.path(), request);
      if (staged.isPresent()) {
        return staged.get();
      }
    }
    try {
      ArchPath path = parser.parse(context.path());
      if (context.method() == HttpMethod.PUT) {
        if (runtime.type() != com.github.klboke.kkrepo.core.RepositoryType.HOSTED) {
          throw new MavenExceptions.MethodNotAllowed(
              "Pacman package publication is only available on hosted repositories");
        }
        if (path.kind() != ArchPath.Kind.PACKAGE && path.kind() != ArchPath.Kind.SIGNATURE) {
          throw new MavenExceptions.BadRequestException(
              "PUT requires a *.pkg.tar.* package or package signature path");
        }
        if (path.databaseSignature()) {
          throw new MavenExceptions.BadRequestException(
              "Database signatures are generated from the repository signing key");
        }
        try (InputStream body = request.getInputStream()) {
          ArchService.Published published = path.kind() == ArchPath.Kind.PACKAGE
              ? service.publish(runtime, path.fileName(), body, actor(request),
                  ClientAddress.from(request))
              : service.publishSignature(runtime, path.fileName(), body, actor(request),
                  ClientAddress.from(request));
          return ResponseEntity.status(HttpStatus.CREATED)
              .header(HttpHeaders.LOCATION, published.path()).build();
        }
      }
      if (context.method() == HttpMethod.DELETE) {
        return response(service.delete(runtime, path, true), request);
      }
      if (context.method() == HttpMethod.POST) {
        throw new MavenExceptions.MethodNotAllowed(
            "POST is not supported for pacman repositories; use PUT or the components API");
      }
      boolean headOnly = context.method() == HttpMethod.HEAD;
      return response(service.get(runtime, path, headOnly), request);
    } catch (MavenExceptions.MavenNotFoundException notFound) {
      throw notFound;
    } catch (MavenExceptions.MethodNotAllowed notAllowed) {
      throw notAllowed;
    } catch (MavenExceptions.WritePolicyDenied denied) {
      throw denied;
    } catch (IllegalArgumentException invalid) {
      throw new MavenExceptions.BadRequestException(invalid.getMessage(), invalid);
    }
  }

  private ResponseEntity<?> response(MavenResponse response, HttpServletRequest request) {
    if (ConditionalResponses.shouldReturnNotModified(
        request, response.status(), response.etag(), response.lastModified())) {
      response.closeBodyIfOpen();
      return ResponseEntity.status(HttpStatus.NOT_MODIFIED)
          .headers(headers(response, false)).build();
    }
    HttpHeaders headers = headers(response, true);
    if (request.getMethod().equalsIgnoreCase("HEAD") || !response.hasBody()) {
      response.closeBodyIfOpen();
      return ResponseEntity.status(response.status()).headers(headers).build();
    }
    InputStream body = response.body();
    long contentLength = response.contentLength();
    StreamingResponseBody streaming = output ->
        TempBlobFiles.copyResponse(body, output, request, contentLength);
    return ResponseEntity.status(response.status()).headers(headers).body(streaming);
  }

  private static HttpHeaders headers(MavenResponse response, boolean entityHeaders) {
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

  private static String actor(HttpServletRequest request) {
    Object subject = request.getAttribute(AuthenticatedSubject.REQUEST_ATTRIBUTE);
    if (subject instanceof AuthenticatedSubject authenticated
        && authenticated.userId() != null
        && !authenticated.userId().isBlank()) {
      return authenticated.userId();
    }
    return "anonymous";
  }
}
