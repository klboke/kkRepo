package com.github.klboke.kkrepo.server.routing.ohpm;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.core.RepositoryType;
import com.github.klboke.kkrepo.protocol.ohpm.OhpmException;
import com.github.klboke.kkrepo.protocol.ohpm.OhpmGroupMetadata;
import com.github.klboke.kkrepo.protocol.ohpm.OhpmPath;
import com.github.klboke.kkrepo.protocol.ohpm.OhpmLoginRequest;
import com.github.klboke.kkrepo.protocol.ohpm.OhpmPathParser;
import com.github.klboke.kkrepo.protocol.ohpm.OhpmUnpublishRequest;
import com.github.klboke.kkrepo.server.config.RepositoryUrlPrefix;
import com.github.klboke.kkrepo.server.http.ConditionalResponses;
import com.github.klboke.kkrepo.server.maven.MavenResponse;
import com.github.klboke.kkrepo.server.ohpm.OhpmAccess;
import com.github.klboke.kkrepo.server.ohpm.OhpmGroupService;
import com.github.klboke.kkrepo.server.ohpm.OhpmHostedService;
import com.github.klboke.kkrepo.server.ohpm.OhpmLoginService;
import com.github.klboke.kkrepo.server.ohpm.OhpmProxyService;
import com.github.klboke.kkrepo.server.routing.LegacyRepositoryProtocolHandler;
import com.github.klboke.kkrepo.server.routing.RepositoryProtocolRequest;
import com.github.klboke.kkrepo.server.routing.RepositoryProtocolRoute;
import com.github.klboke.kkrepo.server.security.AuthenticatedSubject;
import com.github.klboke.kkrepo.server.security.ForwardedHeaderPolicy;
import com.github.klboke.kkrepo.server.security.ManagementAuditFilter;
import com.github.klboke.kkrepo.server.securityscan.ArtifactPolicyException;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

@Component
public class OhpmProtocolHandler implements LegacyRepositoryProtocolHandler {
  private static final Logger log = LoggerFactory.getLogger(OhpmProtocolHandler.class);
  private final OhpmHostedService hosted;
  private final OhpmProxyService proxy;
  private final OhpmGroupService group;
  private final OhpmAccess access;
  private final ObjectMapper json;
  private final ForwardedHeaderPolicy forwarded;
  private final OhpmPathParser paths = new OhpmPathParser();
  private OhpmLoginService login;

  @org.springframework.beans.factory.annotation.Autowired
  void configureLogin(OhpmLoginService login) {
    this.login = login;
  }

  public OhpmProtocolHandler(OhpmHostedService hosted, OhpmProxyService proxy, OhpmGroupService group, OhpmAccess access,
      ObjectMapper json, ForwardedHeaderPolicy forwarded) {
    this.hosted = hosted;
    this.proxy = proxy;
    this.group = group;
    this.access = access;
    this.json = json;
    this.forwarded = forwarded;
  }

  @Override
  public Collection<RepositoryProtocolRoute> routes() {
    return List.of(HttpMethod.GET, HttpMethod.HEAD, HttpMethod.PUT, HttpMethod.POST, HttpMethod.DELETE)
        .stream().flatMap(method -> List.of(RepositoryType.HOSTED, RepositoryType.PROXY, RepositoryType.GROUP).stream()
            .map(type -> RepositoryProtocolRoute.anyPath(RepositoryFormat.OHPM, type, method, 0))).toList();
  }

  @Override
  public ResponseEntity<?> handle(RepositoryProtocolRequest context) {
    HttpServletRequest request = context.servletRequest();
    OhpmPath path = request.getAttribute(OhpmAccess.PATH_ATTRIBUTE) instanceof OhpmPath parsed
        ? parsed : paths.parse(context.method().name(), context.path());
    try {
      if (path.kind() == OhpmPath.Kind.UNKNOWN) {
        throw new OhpmException(404, "OHPM endpoint is not available");
      }
      if (path.kind() == OhpmPath.Kind.LOGIN) {
        request.setAttribute(ManagementAuditFilter.OPERATION_DETAILS_ATTRIBUTE,
            Map.of("format", "ohpm", "repository", context.runtime().name(), "operation", "login"));
        if (login == null || !login.enabled()) throw new OhpmException(404, "OHPM Login is disabled");
        if (!context.runtime().online()) throw new OhpmException(503, "Repository is offline");
        if (!context.runtime().isHosted()) throw new OhpmException(405, "OHPM Login requires a hosted repository");
        requireJson(request);
        var result = login.login(OhpmLoginRequest.read(request.getInputStream()));
        request.setAttribute(AuthenticatedSubject.REQUEST_ATTRIBUTE, result.subject());
        return json(request, 200, Map.of("success", true, "token", result.token()));
      }
      if (!context.runtime().online()) throw new OhpmException(503, "Repository is offline");
      boolean head = context.method() == HttpMethod.HEAD;
      String base = forwarded.serverBaseUrl(request) + request.getContextPath()
          + RepositoryUrlPrefix.path(request) + context.runtime().name();
      if (path.kind() == OhpmPath.Kind.METADATA) {
        return read(request, context.runtime().isGroup()
            ? group.metadata(context.runtime(), path.packageId(), base, head) : context.runtime().isProxy()
            ? proxy.metadata(context.runtime(), path.packageId(), base, head)
            : hosted.metadata(context.runtime(), path.packageId(), base, head), head);
      }
      if (path.kind() == OhpmPath.Kind.DOWNLOAD) {
        String[] sources = request.getParameterValues(OhpmGroupMetadata.SOURCE_PARAMETER);
        if (sources != null && sources.length != 1) throw new OhpmException(400, "Ambiguous OHPM group source");
        return read(request, context.runtime().isGroup()
            ? group.download(context.runtime(), path.packageId(), path.assetPath(),
                sources == null ? null : sources[0], base, head) : context.runtime().isProxy()
            ? proxy.download(context.runtime(), path.packageId(), path.assetPath(), head)
            : hosted.download(context.runtime(), path.assetPath(), head), head);
      }
      if ((context.runtime().isProxy() || context.runtime().isGroup()) && path.kind() != OhpmPath.Kind.PING) {
        throw new OhpmException(405, "OHPM proxy and group repositories are read-only");
      }
      if (path.kind() != OhpmPath.Kind.PING) {
        request.setAttribute(ManagementAuditFilter.OPERATION_DETAILS_ATTRIBUTE,
            Map.of("format", "ohpm", "repository", context.runtime().name(),
                "operation", path.kind().name().toLowerCase(java.util.Locale.ROOT),
                "package", path.packageId().id()));
      }
      switch (path.kind()) {
        case PING -> { }
        case PUBLISH, STREAM_PUBLISH -> {
          Object subject = request.getAttribute(AuthenticatedSubject.REQUEST_ATTRIBUTE);
          if (!(subject instanceof AuthenticatedSubject actor)) throw new OhpmException(401, "Authentication required");
          if (path.kind() == OhpmPath.Kind.STREAM_PUBLISH) {
            if (request.getContentType() == null || !MediaType.MULTIPART_FORM_DATA
                .isCompatibleWith(MediaType.parseMediaType(request.getContentType()))) {
              throw new OhpmException(400, "Expected multipart/form-data");
            }
            hosted.publishStream(context.runtime(), path.packageId(), parts(request),
                actor.userId(), forwarded.clientAddress(request),
                existing -> access.requirePublish(request, context.runtime().name(), path.packageId().id(), existing));
          } else {
            requireJson(request);
            hosted.publish(context.runtime(), path.packageId(), request.getInputStream(),
                actor.userId(), forwarded.clientAddress(request),
                existing -> access.requirePublish(request, context.runtime().name(), path.packageId().id(), existing));
          }
        }
        case UNPUBLISH -> hosted.unpublish(context.runtime(), OhpmUnpublishRequest.parse(path, smallBody(request)));
        case DIST_TAG -> {
          boolean delete = context.method() == HttpMethod.DELETE;
          String version = null;
          if (!delete) {
            JsonNode body = smallBody(request);
            if (body == null || !body.isObject() || body.size() != 1 || !body.path("version").isTextual()) {
              throw new OhpmException(400, "OHPM tag body must contain a version string");
            }
            version = body.path("version").textValue();
          }
          hosted.tag(context.runtime(), path.packageId(), path.tag(), version, delete);
        }
        default -> throw new OhpmException(404, "OHPM endpoint is not available");
      }
      return json(request, 200, Map.of("code", 200, "message", "success"));
    } catch (ArtifactPolicyException error) {
      throw error;
    } catch (OhpmException error) {
      return json(request, error.status(), path.kind() == OhpmPath.Kind.LOGIN
          ? Map.of("success", false, "error", error.getMessage()) : error.response());
    } catch (TransientDataAccessException error) {
      int status = path.kind() == OhpmPath.Kind.PUBLISH || path.kind() == OhpmPath.Kind.STREAM_PUBLISH ? 598 : 500;
      log.warn("OHPM transaction retries exhausted: repository={}", context.runtime().name(), error);
      return json(request, status, path.kind() == OhpmPath.Kind.LOGIN
          ? Map.of("success", false, "error", "OHPM login temporarily unavailable")
          : new OhpmException(status, "OHPM transaction temporarily unavailable").response());
    } catch (IllegalArgumentException | JsonProcessingException | ServletException error) {
      return json(request, 400, path.kind() == OhpmPath.Kind.LOGIN
          ? Map.of("success", false, "error", "Invalid OHPM login request")
          : new OhpmException(400, "Invalid OHPM request").response());
    } catch (IOException | RuntimeException error) {
      log.warn("OHPM repository request failed: repository={}", context.runtime().name(), error);
      return json(request, 500, path.kind() == OhpmPath.Kind.LOGIN
          ? Map.of("success", false, "error", "OHPM login failed")
          : new OhpmException(500, "OHPM repository operation failed").response());
    }
  }

  private static Collection<jakarta.servlet.http.Part> parts(HttpServletRequest request)
      throws IOException, ServletException {
    try {
      return request.getParts();
    } catch (IllegalStateException error) {
      throw new OhpmException(413, "OHPM multipart body exceeds the configured limit");
    }
  }

  private JsonNode smallBody(HttpServletRequest request) throws IOException {
    byte[] bytes = request.getInputStream().readNBytes(4097);
    if (bytes.length == 0) return null;
    if (bytes.length > 4096) throw new OhpmException(413, "OHPM request body is too large");
    requireJson(request);
    return json.readerFor(JsonNode.class).with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
        .with(JsonParser.Feature.STRICT_DUPLICATE_DETECTION).readValue(bytes);
  }

  private static void requireJson(HttpServletRequest request) {
    if (request.getContentType() == null
        || !MediaType.APPLICATION_JSON.isCompatibleWith(MediaType.parseMediaType(request.getContentType()))) {
      throw new OhpmException(400, "Expected application/json");
    }
  }

  private ResponseEntity<?> json(HttpServletRequest request, int status, Map<String, Object> body) {
    var response = ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON)
        .header(HttpHeaders.CACHE_CONTROL, "no-store");
    if ("HEAD".equals(request.getMethod())) return response.build();
    if ("GET".equals(request.getMethod())) {
      return response.body((StreamingResponseBody) output -> json.writeValue(output, body));
    }
    return response.body(body);
  }

  private static ResponseEntity<?> read(HttpServletRequest request, MavenResponse response, boolean head) {
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.parseMediaType(response.contentType()));
    response.headers().forEach(headers::set);
    ConditionalResponses.addValidators(headers, response.etag(), response.lastModified());
    if (ConditionalResponses.shouldReturnNotModified(request, response.status(), response.etag(), response.lastModified())) {
      response.closeBodyIfOpen();
      return ResponseEntity.status(304).headers(headers).build();
    }
    headers.setContentLength(response.contentLength());
    if (head) return ResponseEntity.status(response.status()).headers(headers).build();
    MavenResponse materialized = response.materializeBody();
    return ResponseEntity.status(response.status()).headers(headers).body((StreamingResponseBody) output -> {
      try (var body = materialized.body()) {
        body.transferTo(output);
      }
    });
  }
}
