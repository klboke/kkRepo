package com.github.klboke.kkrepo.server.docker;

import com.github.klboke.kkrepo.auth.AccessDecisionService;
import com.github.klboke.kkrepo.auth.PermissionAction;
import com.github.klboke.kkrepo.auth.RepositoryPermission;
import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.protocol.docker.DockerConstants;
import com.github.klboke.kkrepo.protocol.docker.DockerDigest;
import com.github.klboke.kkrepo.protocol.docker.DockerErrorCode;
import com.github.klboke.kkrepo.protocol.docker.DockerPath;
import com.github.klboke.kkrepo.protocol.docker.DockerPathParser;
import com.github.klboke.kkrepo.protocol.docker.DockerProtocolException;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntime;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntimeRegistry;
import com.github.klboke.kkrepo.server.security.AuthenticatedSubject;
import com.github.klboke.kkrepo.server.security.ForwardedHeaderPolicy;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v2")
public class DockerRegistryController {
  private final RepositoryRuntimeRegistry registry;
  private final DockerHostedService hosted;
  private final DockerProxyService proxy;
  private final DockerGroupService group;
  private final DockerRangeSupport rangeSupport;
  private final AccessDecisionService accessDecisionService;
  private final ForwardedHeaderPolicy forwardedHeaderPolicy;
  private final DockerPathParser parser = new DockerPathParser();

  public DockerRegistryController(
      RepositoryRuntimeRegistry registry,
      DockerHostedService hosted,
      DockerProxyService proxy,
      DockerGroupService group,
      DockerRangeSupport rangeSupport,
      AccessDecisionService accessDecisionService,
      ForwardedHeaderPolicy forwardedHeaderPolicy) {
    this.registry = registry;
    this.hosted = hosted;
    this.proxy = proxy;
    this.group = group;
    this.rangeSupport = rangeSupport;
    this.accessDecisionService = accessDecisionService;
    this.forwardedHeaderPolicy = forwardedHeaderPolicy;
  }

  public ResponseEntity<?> get(
      HttpServletRequest request,
      int limit,
      String last,
      String artifactType) {
    return get(request, limit, last, artifactType, List.of());
  }

  @GetMapping({"", "/", "/**"})
  public ResponseEntity<?> get(
      HttpServletRequest request,
      @RequestParam(name = "n", required = false, defaultValue = "100") int limit,
      @RequestParam(name = "last", required = false) String last,
      @RequestParam(name = "artifactType", required = false) String artifactType,
      @RequestHeader(name = HttpHeaders.ACCEPT, required = false) List<String> accept) {
    DockerTarget target = target(request);
    if (target.path().kind() == DockerPath.Kind.BASE) {
      return registryBase();
    }
    RepositoryRuntime runtime = runtime(target);
    return switch (target.path().kind()) {
      case MANIFEST -> stream(dispatchManifest(runtime, target.path(), false, accept), request, false);
      case BLOB -> stream(dispatchBlob(runtime, target.path(), false), request, true);
      case UPLOAD_SESSION -> uploadStatus(request, runtime, target, target.path());
      case TAGS -> tagsJson(request, dispatchTags(runtime, target.path(), last, limit), limit);
      case CATALOG -> catalogJson(request, dispatchCatalog(runtime, last, limit), limit);
      case REFERRERS -> referrersJson(dispatchReferrers(runtime, target.path(), artifactType), artifactType);
      default -> throw new DockerProtocolException(
          DockerErrorCode.UNSUPPORTED, "unsupported Docker GET path", 405);
    };
  }

  public ResponseEntity<?> head(HttpServletRequest request) {
    return head(request, List.of());
  }

  @RequestMapping(value = {"", "/", "/**"}, method = RequestMethod.HEAD)
  public ResponseEntity<?> head(
      HttpServletRequest request,
      @RequestHeader(name = HttpHeaders.ACCEPT, required = false) List<String> accept) {
    DockerTarget target = target(request);
    if (target.path().kind() == DockerPath.Kind.BASE) {
      return registryBase();
    }
    RepositoryRuntime runtime = runtime(target);
    return switch (target.path().kind()) {
      case MANIFEST -> stream(dispatchManifest(runtime, target.path(), true, accept), request, false);
      case BLOB -> stream(dispatchBlob(runtime, target.path(), true), request, false);
      case UPLOAD_SESSION -> uploadStatus(request, runtime, target, target.path());
      default -> throw new DockerProtocolException(
          DockerErrorCode.UNSUPPORTED, "unsupported Docker HEAD path", 405);
    };
  }

  @PostMapping("/**")
  public ResponseEntity<?> post(
      HttpServletRequest request,
      @RequestParam(name = "mount", required = false) String mount,
      @RequestParam(name = "from", required = false) String from) {
    DockerTarget target = target(request);
    RepositoryRuntime runtime = runtime(target);
    DockerPath path = target.path();
    if (path.kind() != DockerPath.Kind.UPLOAD_START) {
      throw new DockerProtocolException(DockerErrorCode.UNSUPPORTED, "unsupported Docker POST path", 405);
    }
    MountSource source = mount == null || mount.isBlank() ? null : mountSource(runtime, target, path.imageName(), from);
    if (source != null) {
      ensureMountSourcePullAllowed(request, source.runtime(), source.imageName());
    }
    var status = hosted.startUpload(
        runtime, path.imageName(), mount, source == null ? from : source.imageName(), source == null ? null : source.runtime(),
        userId(request), request.getRemoteAddr());
    if (status.mounted()) {
      return ResponseEntity.status(HttpStatus.CREATED)
          .header(DockerConstants.API_VERSION_HEADER, DockerConstants.API_VERSION)
          .header(DockerConstants.CONTENT_DIGEST_HEADER, status.mountedDigest().value())
          .header(HttpHeaders.LOCATION, blobLocation(request, runtime, target, path.imageName(), status.mountedDigest()))
          .build();
    }
    return uploadAccepted(request, runtime, target, path.imageName(), status);
  }

  @PatchMapping("/**")
  public ResponseEntity<?> patch(
      HttpServletRequest request,
      @RequestHeader(name = "Content-Range", required = false) String contentRange) throws IOException {
    DockerTarget target = target(request);
    RepositoryRuntime runtime = runtime(target);
    DockerPath path = target.path();
    if (path.kind() != DockerPath.Kind.UPLOAD_SESSION) {
      throw new DockerProtocolException(DockerErrorCode.UNSUPPORTED, "unsupported Docker PATCH path", 405);
    }
    var status = hosted.appendUpload(
        runtime, path.uploadUuid(), request.getInputStream(), request.getContentLengthLong(), contentRange);
    return uploadAccepted(request, runtime, target, path.imageName(), status);
  }

  @PutMapping("/**")
  public ResponseEntity<?> put(
      HttpServletRequest request,
      @RequestParam(name = "digest", required = false) String digest,
      @RequestParam(name = "tag", required = false) List<String> tags,
      @RequestHeader(name = HttpHeaders.CONTENT_TYPE, required = false) String contentType,
      @RequestHeader(name = "Content-Range", required = false) String contentRange) throws IOException {
    DockerTarget target = target(request);
    RepositoryRuntime runtime = runtime(target);
    DockerPath path = target.path();
    if (path.kind() == DockerPath.Kind.MANIFEST) {
      DockerResponse response = hosted.putManifest(
          runtime, path.imageName(), path.reference(), request.getInputStream(), contentType, tags,
          userId(request), request.getRemoteAddr())
          .withHeader(HttpHeaders.LOCATION, manifestLocation(request, runtime, target, path.imageName(), path.reference()));
      return stream(response, request, false);
    }
    if (path.kind() == DockerPath.Kind.UPLOAD_SESSION) {
      var result = hosted.completeUpload(
          runtime,
          path.uploadUuid(),
          request.getInputStream(),
          request.getContentLengthLong(),
          contentRange,
          parseUploadDigest(digest),
          userId(request),
          request.getRemoteAddr());
      return ResponseEntity.status(HttpStatus.CREATED)
          .header(DockerConstants.API_VERSION_HEADER, DockerConstants.API_VERSION)
          .header(DockerConstants.CONTENT_DIGEST_HEADER, result.digest().value())
          .header(HttpHeaders.LOCATION, blobLocation(request, runtime, target, path.imageName(), result.digest()))
          .build();
    }
    throw new DockerProtocolException(DockerErrorCode.UNSUPPORTED, "unsupported Docker PUT path", 405);
  }

  @DeleteMapping("/**")
  public ResponseEntity<?> delete(HttpServletRequest request) {
    DockerTarget target = target(request);
    RepositoryRuntime runtime = runtime(target);
    DockerPath path = target.path();
    if (path.kind() == DockerPath.Kind.MANIFEST) {
      return stream(hosted.deleteManifest(runtime, path.imageName(), path.reference()), request, false);
    }
    if (path.kind() == DockerPath.Kind.UPLOAD_SESSION) {
      return stream(hosted.cancelUpload(runtime, path.uploadUuid()), request, false);
    }
    if (path.kind() == DockerPath.Kind.BLOB) {
      return stream(hosted.deleteBlob(runtime, path.digest()), request, false);
    }
    throw new DockerProtocolException(DockerErrorCode.UNSUPPORTED, "unsupported Docker DELETE path", 405);
  }

  private ResponseEntity<Void> registryBase() {
    return ResponseEntity.ok()
        .header(DockerConstants.API_VERSION_HEADER, DockerConstants.API_VERSION)
        .build();
  }

  private DockerResponse dispatchManifest(
      RepositoryRuntime runtime, DockerPath path, boolean headOnly, List<String> accept) {
    if (accept == null || accept.isEmpty()) {
      return switch (runtime.type()) {
        case HOSTED -> hosted.getManifest(runtime, path.imageName(), path.reference(), headOnly);
        case PROXY -> proxy.getManifest(runtime, path.imageName(), path.reference(), headOnly);
        case GROUP -> group.getManifest(runtime, path.imageName(), path.reference(), headOnly);
      };
    }
    return switch (runtime.type()) {
      case HOSTED -> hosted.getManifest(runtime, path.imageName(), path.reference(), headOnly, accept);
      case PROXY -> proxy.getManifest(runtime, path.imageName(), path.reference(), headOnly, accept);
      case GROUP -> group.getManifest(runtime, path.imageName(), path.reference(), headOnly, accept);
    };
  }

  private DockerResponse dispatchBlob(RepositoryRuntime runtime, DockerPath path, boolean headOnly) {
    return switch (runtime.type()) {
      case HOSTED -> hosted.getBlob(runtime, path.digest(), headOnly);
      case PROXY -> proxy.getBlob(runtime, path.imageName(), path.digest(), headOnly);
      case GROUP -> group.getBlob(runtime, path.imageName(), path.digest(), headOnly);
    };
  }

  private DockerTagList dispatchTags(RepositoryRuntime runtime, DockerPath path, String last, int limit) {
    return switch (runtime.type()) {
      case HOSTED -> hosted.tags(runtime, path.imageName(), last, limit);
      case PROXY -> proxy.tags(runtime, path.imageName(), last, limit);
      case GROUP -> group.tags(runtime, path.imageName(), last, limit);
    };
  }

  private DockerCatalogList dispatchCatalog(RepositoryRuntime runtime, String last, int limit) {
    return switch (runtime.type()) {
      case HOSTED -> hosted.catalog(runtime, last, limit);
      case PROXY -> proxy.catalog(runtime, last, limit);
      case GROUP -> group.catalog(runtime, last, limit);
    };
  }

  private Map<String, Object> dispatchReferrers(
      RepositoryRuntime runtime, DockerPath path, String artifactType) {
    return switch (runtime.type()) {
      case HOSTED -> hosted.referrers(runtime, path.digest(), artifactType);
      case PROXY -> proxy.referrers(runtime, path.imageName(), path.digest(), artifactType);
      case GROUP -> group.referrers(runtime, path.imageName(), path.digest(), artifactType);
    };
  }

  private ResponseEntity<?> uploadStatus(
      HttpServletRequest request, RepositoryRuntime runtime, DockerTarget target, DockerPath path) {
    var status = hosted.uploadStatus(runtime, path.uploadUuid());
    return ResponseEntity.status(HttpStatus.NO_CONTENT)
        .header(DockerConstants.API_VERSION_HEADER, DockerConstants.API_VERSION)
        .header(HttpHeaders.LOCATION, uploadLocation(request, runtime, target, path.imageName(), path.uploadUuid()))
        .header("Range", status.rangeHeader())
        .header(DockerConstants.UPLOAD_UUID_HEADER, path.uploadUuid())
        .build();
  }

  private ResponseEntity<InputStreamResource> stream(
      DockerResponse response,
      HttpServletRequest request,
      boolean ranges) {
    if (ranges) {
      response = rangeSupport.apply(request, response);
    }
    HttpHeaders headers = headers(response);
    if (!response.hasBody()) {
      return ResponseEntity.status(response.status()).headers(headers).body(null);
    }
    return ResponseEntity.status(response.status()).headers(headers)
        .body(new InputStreamResource(response.body()));
  }

  private ResponseEntity<Map<String, Object>> json(Map<String, Object> body, String linkHeader) {
    ResponseEntity.BodyBuilder builder = ResponseEntity.ok()
        .header(DockerConstants.API_VERSION_HEADER, DockerConstants.API_VERSION)
        .contentType(MediaType.APPLICATION_JSON);
    if (linkHeader != null) {
      builder.header(HttpHeaders.LINK, linkHeader);
    }
    return builder.body(body);
  }

  private ResponseEntity<Map<String, Object>> tagsJson(
      HttpServletRequest request, DockerTagList tags, int limit) {
    String linkHeader = tags.hasNext() && tags.tags() != null && !tags.tags().isEmpty()
        ? linkHeader(request, limit, tags.tags().get(tags.tags().size() - 1))
        : null;
    return json(tags.body(), linkHeader);
  }

  private ResponseEntity<Map<String, Object>> catalogJson(
      HttpServletRequest request, DockerCatalogList catalog, int limit) {
    String linkHeader = catalog.hasNext() && catalog.repositories() != null && !catalog.repositories().isEmpty()
        ? linkHeader(request, limit, catalog.repositories().get(catalog.repositories().size() - 1))
        : null;
    return json(catalog.body(), linkHeader);
  }

  private ResponseEntity<Map<String, Object>> referrersJson(
      Map<String, Object> body, String artifactType) {
    ResponseEntity.BodyBuilder builder = ResponseEntity.ok()
        .header(DockerConstants.API_VERSION_HEADER, DockerConstants.API_VERSION)
        .contentType(MediaType.parseMediaType(DockerConstants.MEDIA_TYPE_OCI_INDEX));
    if (artifactType != null && !artifactType.isBlank()) {
      builder.header(DockerConstants.OCI_FILTERS_APPLIED_HEADER, "artifactType");
    }
    return builder.body(body);
  }

  private ResponseEntity<?> uploadAccepted(
      HttpServletRequest request,
      RepositoryRuntime runtime,
      DockerTarget target,
      String imageName,
      DockerUploadService.UploadStatus status) {
    return ResponseEntity.status(HttpStatus.ACCEPTED)
        .header(DockerConstants.API_VERSION_HEADER, DockerConstants.API_VERSION)
        .header(HttpHeaders.LOCATION, uploadLocation(request, runtime, target, imageName, status.uuid()))
        .header("Range", status.rangeHeader())
        .header(DockerConstants.UPLOAD_UUID_HEADER, status.uuid())
        .build();
  }

  private HttpHeaders headers(DockerResponse response) {
    HttpHeaders headers = new HttpHeaders();
    headers.add(DockerConstants.API_VERSION_HEADER, DockerConstants.API_VERSION);
    if (response.contentType() != null) {
      try {
        headers.setContentType(MediaType.parseMediaType(response.contentType()));
      } catch (RuntimeException e) {
        headers.add(HttpHeaders.CONTENT_TYPE, response.contentType());
      }
    }
    if (response.contentLength() >= 0) {
      headers.setContentLength(response.contentLength());
    }
    response.headers().forEach(headers::add);
    return headers;
  }

  private DockerTarget target(HttpServletRequest request) {
    String uri = stripContextPath(request);
    if (uri.equals("/v2") || uri.equals("/v2/")) {
      return new DockerTarget(connectorRepositoryOrNull(request), parser.parse(""), connectorRepositoryOrNull(request) != null);
    }
    if (!uri.startsWith("/v2/")) {
      throw new DockerProtocolException(DockerErrorCode.NAME_UNKNOWN, "not a Docker Registry V2 path");
    }
    String raw = uri.substring("/v2/".length());
    String connectorRepository = connectorRepositoryOrNull(request);
    if (connectorRepository != null) {
      return new DockerTarget(connectorRepository, parser.parse(raw), true);
    }
    int slash = raw.indexOf('/');
    if (slash <= 0) {
      throw new DockerProtocolException(DockerErrorCode.NAME_UNKNOWN, "Docker repository route is missing");
    }
    String repository = decode(raw.substring(0, slash));
    return new DockerTarget(repository, parser.parse(raw.substring(slash + 1)), false);
  }

  private RepositoryRuntime runtime(DockerTarget target) {
    if (target.repository() == null || target.repository().isBlank()) {
      throw new DockerProtocolException(DockerErrorCode.NAME_UNKNOWN, "Docker repository route is missing");
    }
    RepositoryRuntime runtime = registry.resolve(target.repository())
        .orElseThrow(() -> new DockerProtocolException(DockerErrorCode.NAME_UNKNOWN, target.repository()));
    if (runtime.format() != RepositoryFormat.DOCKER) {
      throw new DockerProtocolException(DockerErrorCode.NAME_UNKNOWN, target.repository());
    }
    return runtime;
  }

  private static String userId(HttpServletRequest request) {
    Object subject = request.getAttribute(AuthenticatedSubject.REQUEST_ATTRIBUTE);
    return subject instanceof AuthenticatedSubject authenticated ? authenticated.userId() : "anonymous";
  }

  private void ensureMountSourcePullAllowed(
      HttpServletRequest request, RepositoryRuntime runtime, String sourceImage) {
    if (accessDecisionService == null) {
      return;
    }
    Object subject = request.getAttribute(AuthenticatedSubject.REQUEST_ATTRIBUTE);
    if (!(subject instanceof AuthenticatedSubject authenticated)) {
      throw new DockerProtocolException(DockerErrorCode.DENIED, "source pull permission is required for blob mount", 403);
    }
    if (!accessDecisionService.decide(
        authenticated.permissionSubject(),
        new RepositoryPermission(runtime.name(), RepositoryFormat.DOCKER, sourceImage, PermissionAction.READ))
        .allowed()) {
      throw new DockerProtocolException(DockerErrorCode.DENIED, "source pull permission is required for blob mount", 403);
    }
  }

  private MountSource mountSource(
      RepositoryRuntime targetRuntime,
      DockerTarget target,
      String targetImage,
      String from) {
    String sourceImage = from == null || from.isBlank() ? targetImage : from;
    RepositoryRuntime sourceRuntime = targetRuntime;
    if (!target.connectorRoute() && from != null && !from.isBlank()) {
      int slash = from.indexOf('/');
      if (slash > 0) {
        String candidateRepository = from.substring(0, slash);
        var resolved = registry.resolve(candidateRepository);
        if (resolved.isPresent() && resolved.get().format() == RepositoryFormat.DOCKER) {
          sourceRuntime = resolved.get();
          sourceImage = from.substring(slash + 1);
          if (sourceImage.isBlank()) {
            sourceImage = targetImage;
          }
        }
      }
    }
    return new MountSource(sourceRuntime, sourceImage);
  }

  private static String connectorRepositoryOrNull(HttpServletRequest request) {
    Object repository = request.getAttribute(DockerConnectorConfiguration.CONNECTOR_REPOSITORY_ATTRIBUTE);
    if (repository instanceof String value && !value.isBlank()) {
      return value;
    }
    return null;
  }

  private static DockerDigest parseUploadDigest(String digest) {
    try {
      return DockerDigest.parse(digest);
    } catch (DockerProtocolException e) {
      if (e.code() == DockerErrorCode.DIGEST_INVALID) {
        throw new DockerProtocolException(e.code(), e.getMessage(), 400);
      }
      throw e;
    }
  }

  private String uploadLocation(
      HttpServletRequest request, RepositoryRuntime runtime, DockerTarget target, String imageName, String uuid) {
    return registryBaseUrl(request, runtime, target) + registryPath(target, imageName, "blobs/uploads/" + uuid);
  }

  private String blobLocation(
      HttpServletRequest request,
      RepositoryRuntime runtime,
      DockerTarget target,
      String imageName,
      DockerDigest digest) {
    return registryBaseUrl(request, runtime, target) + registryPath(target, imageName, "blobs/" + digest.value());
  }

  private String manifestLocation(
      HttpServletRequest request,
      RepositoryRuntime runtime,
      DockerTarget target,
      String imageName,
      String reference) {
    return registryBaseUrl(request, runtime, target) + registryPath(target, imageName, "manifests/" + reference);
  }

  private static String registryPath(DockerTarget target, String imageName, String suffix) {
    String prefix = target.connectorRoute() ? "/v2/" : "/v2/" + target.repository() + "/";
    return prefix + imageName + "/" + suffix;
  }

  private String registryBaseUrl(
      HttpServletRequest request, RepositoryRuntime runtime, DockerTarget target) {
    if (target.connectorRoute()
        && runtime.dockerConnectorPublicUrl() != null
        && !runtime.dockerConnectorPublicUrl().isBlank()) {
      return trimTrailingSlash(runtime.dockerConnectorPublicUrl());
    }
    return forwardedHeaderPolicy.serverBaseUrl(request);
  }

  private static String stripContextPath(HttpServletRequest request) {
    String uri = request.getRequestURI();
    String contextPath = request.getContextPath();
    if (contextPath != null && !contextPath.isBlank() && uri.startsWith(contextPath)) {
      return uri.substring(contextPath.length());
    }
    return uri;
  }

  private static String trimTrailingSlash(String value) {
    String trimmed = value.trim();
    while (trimmed.endsWith("/")) {
      trimmed = trimmed.substring(0, trimmed.length() - 1);
    }
    return trimmed;
  }

  private static String decode(String value) {
    return URLDecoder.decode(value, StandardCharsets.UTF_8);
  }

  private String linkHeader(HttpServletRequest request, int limit, String nextLast) {
    String uri = stripContextPath(request);
    String query = request.getQueryString();
    StringBuilder target = new StringBuilder(uri);
    String separator = "?";
    if (query != null && !query.isBlank()) {
      for (String part : query.split("&")) {
        if (part.isBlank() || part.startsWith("n=") || part.startsWith("last=")) {
          continue;
        }
        target.append(separator).append(part);
        separator = "&";
      }
    }
    target.append(separator).append("n=").append(Math.max(1, Math.min(limit, 1000)));
    target.append("&last=").append(URLEncoder.encode(nextLast, StandardCharsets.UTF_8));
    return "<" + target + ">; rel=\"next\"";
  }

  private record DockerTarget(String repository, DockerPath path, boolean connectorRoute) {
  }

  private record MountSource(RepositoryRuntime runtime, String imageName) {
  }
}
