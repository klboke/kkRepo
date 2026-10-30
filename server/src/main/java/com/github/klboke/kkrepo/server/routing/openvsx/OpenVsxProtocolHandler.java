package com.github.klboke.kkrepo.server.routing.openvsx;

import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.protocol.openvsx.OpenVsxCoordinates;
import com.github.klboke.kkrepo.protocol.openvsx.OpenVsxPath;
import com.github.klboke.kkrepo.protocol.openvsx.OpenVsxPathParser;
import com.github.klboke.kkrepo.server.config.RepositoryUrlPrefix;
import com.github.klboke.kkrepo.server.http.ConditionalResponses;
import com.github.klboke.kkrepo.server.maven.MavenExceptions;
import com.github.klboke.kkrepo.server.maven.MavenResponse;
import com.github.klboke.kkrepo.server.openvsx.OpenVsxGroupService;
import com.github.klboke.kkrepo.server.openvsx.OpenVsxHostedService;
import com.github.klboke.kkrepo.server.openvsx.OpenVsxProxyService;
import com.github.klboke.kkrepo.server.openvsx.OpenVsxQuery;
import com.github.klboke.kkrepo.server.routing.RepositoryProtocolHandler;
import com.github.klboke.kkrepo.server.routing.RepositoryProtocolRequest;
import com.github.klboke.kkrepo.server.routing.RepositoryProtocolRoute;
import com.github.klboke.kkrepo.server.security.AuthenticatedSubject;
import com.github.klboke.kkrepo.server.security.ClientAddress;
import com.github.klboke.kkrepo.server.security.ForwardedHeaderPolicy;
import com.github.klboke.kkrepo.server.staging.StagingHttpSupport;
import com.github.klboke.kkrepo.server.blob.TempBlobFiles;
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

/** Focused handler for the Open VSX registry and VS Code gallery compatibility routes. */
@Component
public class OpenVsxProtocolHandler implements RepositoryProtocolHandler {
  private static final List<RepositoryProtocolRoute> ROUTES =
      java.util.Arrays.stream(com.github.klboke.kkrepo.core.RepositoryType.values())
          .flatMap(type -> List.of(HttpMethod.GET, HttpMethod.HEAD, HttpMethod.POST,
              HttpMethod.PUT, HttpMethod.DELETE).stream()
              .map(method -> RepositoryProtocolRoute.anyPath(
                  RepositoryFormat.OPENVSX, type, method, 0)))
          .toList();

  private final OpenVsxPathParser parser = new OpenVsxPathParser();
  private final OpenVsxHostedService hosted;
  private final OpenVsxProxyService proxy;
  private final OpenVsxGroupService group;
  private final ForwardedHeaderPolicy forwardedHeaderPolicy;
  private final com.fasterxml.jackson.databind.ObjectMapper objectMapper;
  private static final int MAX_GALLERY_BODY_BYTES = 1024 * 1024;
  private StagingHttpSupport stagingHttp;
  private com.github.klboke.kkrepo.server.repositories.GroupWriteTargetService groupWriteTarget;

  public OpenVsxProtocolHandler(
      OpenVsxHostedService hosted,
      OpenVsxProxyService proxy,
      OpenVsxGroupService group,
      ForwardedHeaderPolicy forwardedHeaderPolicy,
      com.fasterxml.jackson.databind.ObjectMapper objectMapper) {
    this.hosted = hosted;
    this.proxy = proxy;
    this.group = group;
    this.forwardedHeaderPolicy = forwardedHeaderPolicy;
    this.objectMapper = objectMapper;
  }

  @Autowired(required = false)
  public void setStagingHttpSupport(StagingHttpSupport stagingHttp) {
    this.stagingHttp = stagingHttp;
  }

  @Autowired(required = false)
  public void setGroupWriteTargetService(
      com.github.klboke.kkrepo.server.repositories.GroupWriteTargetService groupWriteTarget) {
    this.groupWriteTarget = groupWriteTarget;
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
    String baseUrl = serverBaseUrl(request) + request.getContextPath()
        + RepositoryUrlPrefix.path(request);
    String repository = runtime.name();
    OpenVsxQuery query = OpenVsxQuery.from(request);
    try {
      OpenVsxPath path = parser.parse(context.path());
      if (context.method() == HttpMethod.POST
          && (path.kind() == OpenVsxPath.Kind.VSCODE_QUERY
              || path.kind() == OpenVsxPath.Kind.VSCODE_ITEM)) {
        byte[] body = request.getInputStream().readNBytes(MAX_GALLERY_BODY_BYTES + 1);
        if (body.length > MAX_GALLERY_BODY_BYTES) {
          throw new MavenExceptions.BadRequestException("VS Code gallery query is too large");
        }
        query = query.withVscodeBody(body, objectMapper);
      }
      if (context.method() == HttpMethod.POST && path.kind() == OpenVsxPath.Kind.PUBLISH) {
        if (runtime.type() != com.github.klboke.kkrepo.core.RepositoryType.HOSTED) {
          throw new MavenExceptions.MavenNotFoundException(path.rawPath());
        }
        String actor = actor(request);
        String ip = ClientAddress.from(request);
        try (InputStream body = request.getInputStream()) {
          return response(hosted.publish(runtime, body, actor, ip, baseUrl, repository)
              .response(), request);
        }
      }
      if (context.method() == HttpMethod.DELETE) {
        if (runtime.type() != com.github.klboke.kkrepo.core.RepositoryType.HOSTED) {
          throw new MavenExceptions.MethodNotAllowed(
              "Open VSX deletion is only available on hosted repositories");
        }
        MavenResponse response = switch (path.kind()) {
          case FILE, PLATFORM_FILE -> hosted.deleteExtension(runtime,
              path.namespace(), path.extension(), path.version(), path.targetPlatform(), true);
          default -> throw new MavenExceptions.MethodNotAllowed(
              "Delete a concrete extension version package");
        };
        return response(response, request);
      }
      if (context.method() == HttpMethod.PUT) {
        throw new MavenExceptions.MethodNotAllowed(
            "Use POST api/-/publish to publish an Open VSX extension");
      }
      if (path.kind() == OpenVsxPath.Kind.VSCODE_ITEM) {
        String itemName = request.getParameter("itemName");
        if (itemName == null || itemName.isBlank()) {
          throw new MavenExceptions.BadRequestException("itemName query parameter is required");
        }
        int separator = itemName.indexOf('.');
        if (separator <= 0 || separator >= itemName.length() - 1) {
          throw new MavenExceptions.BadRequestException("Invalid itemName");
        }
        String namespace = itemName.substring(0, separator);
        String extension = itemName.substring(separator + 1);
        if (!OpenVsxCoordinates.validName(namespace)
            || !OpenVsxCoordinates.validName(extension)) {
          throw new MavenExceptions.BadRequestException("Invalid itemName");
        }
        boolean itemHeadOnly = context.method() == HttpMethod.HEAD;
        MavenResponse item = switch (runtime.type()) {
          case HOSTED -> hosted.getItem(runtime, namespace, extension, query, baseUrl,
              repository, itemHeadOnly);
          case PROXY -> proxy.getItem(runtime, namespace, extension, query, baseUrl,
              repository, itemHeadOnly);
          case GROUP -> group.getItem(runtime, namespace, extension, query, baseUrl,
              repository, itemHeadOnly);
        };
        return response(item, request);
      }
      boolean headOnly = context.method() == HttpMethod.HEAD;
      MavenResponse response = switch (runtime.type()) {
        case HOSTED -> hosted.get(runtime, path, query, baseUrl, repository, headOnly);
        case PROXY -> proxy.get(runtime, path, query, baseUrl, repository, headOnly);
        case GROUP -> group.get(runtime, path, query, baseUrl, repository, headOnly);
      };
      return response(response, request);
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

  private ResponseEntity<?> response(
      MavenResponse response, HttpServletRequest request) {
    if (ConditionalResponses.shouldReturnNotModified(
        request, response.status(), response.etag(), response.lastModified())) {
      response.closeBodyIfOpen();
      HttpHeaders headers = headers(response, false);
      return ResponseEntity.status(HttpStatus.NOT_MODIFIED).headers(headers).build();
    }
    HttpHeaders headers = headers(response, true);
    if (response.status() == 201 && response.headers().containsKey("Location")) {
      headers.set(HttpHeaders.LOCATION, response.headers().get("Location"));
    }
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

  private String serverBaseUrl(HttpServletRequest request) {
    return forwardedHeaderPolicy.serverBaseUrl(request);
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
