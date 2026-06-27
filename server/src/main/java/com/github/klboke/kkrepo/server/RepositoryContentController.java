package com.github.klboke.kkrepo.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.protocol.maven.path.MavenPath;
import com.github.klboke.kkrepo.protocol.maven.path.MavenPathParser;
import com.github.klboke.kkrepo.protocol.cargo.CargoPath;
import com.github.klboke.kkrepo.protocol.cargo.CargoPathParser;
import com.github.klboke.kkrepo.protocol.npm.NpmPath;
import com.github.klboke.kkrepo.protocol.npm.NpmPathParser;
import com.github.klboke.kkrepo.protocol.nuget.NugetPaths;
import com.github.klboke.kkrepo.server.blob.TempBlobFiles;
import com.github.klboke.kkrepo.server.cargo.CargoExceptions;
import com.github.klboke.kkrepo.server.cargo.CargoGroupService;
import com.github.klboke.kkrepo.server.cargo.CargoHostedService;
import com.github.klboke.kkrepo.server.cargo.CargoProxyService;
import com.github.klboke.kkrepo.server.goartifact.GoGroupService;
import com.github.klboke.kkrepo.server.goartifact.GoProxyService;
import com.github.klboke.kkrepo.server.helm.HelmHostedService;
import com.github.klboke.kkrepo.server.helm.HelmProxyService;
import com.github.klboke.kkrepo.server.http.ConditionalResponses;
import com.github.klboke.kkrepo.server.maven.MavenExceptions;
import com.github.klboke.kkrepo.server.maven.MavenGroupService;
import com.github.klboke.kkrepo.server.maven.MavenHostedService;
import com.github.klboke.kkrepo.server.maven.MavenHtmlListingService;
import com.github.klboke.kkrepo.server.maven.MavenPartialFetchSupport;
import com.github.klboke.kkrepo.server.maven.MavenProxyService;
import com.github.klboke.kkrepo.server.maven.MavenResponse;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntime;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntimeRegistry;
import com.github.klboke.kkrepo.server.npm.NpmExceptions;
import com.github.klboke.kkrepo.server.npm.NpmGroupService;
import com.github.klboke.kkrepo.server.npm.NpmHostedService;
import com.github.klboke.kkrepo.server.npm.NpmPackumentVariant;
import com.github.klboke.kkrepo.server.npm.NpmProxyService;
import com.github.klboke.kkrepo.server.npm.NpmSearchService;
import com.github.klboke.kkrepo.server.npm.NpmTokenService;
import com.github.klboke.kkrepo.server.nuget.NugetService;
import com.github.klboke.kkrepo.server.raw.RawGroupService;
import com.github.klboke.kkrepo.server.raw.RawHostedService;
import com.github.klboke.kkrepo.server.raw.RawProxyService;
import com.github.klboke.kkrepo.server.rubygems.RubygemsService;
import com.github.klboke.kkrepo.server.security.AuthenticatedSubject;
import com.github.klboke.kkrepo.server.security.ForwardedHeaderPolicy;
import com.github.klboke.kkrepo.server.yum.YumService;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.Part;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

/**
 * Unified content entry point for Nexus-compatible repository URLs. URL shape
 * {@code /repository/{name}/{path:.*}} mirrors Nexus and dispatches by repository format to the
 * protocol-specific hosted/proxy/group service.
 */
@RestController
@RequestMapping("/repository/{name}")
public class RepositoryContentController {
  private final RepositoryRuntimeRegistry registry;
  private final MavenHostedService hosted;
  private final MavenProxyService proxy;
  private final MavenGroupService group;
  private final GoProxyService goProxy;
  private final GoGroupService goGroup;
  private final HelmHostedService helmHosted;
  private final HelmProxyService helmProxy;
  private final MavenHtmlListingService htmlListing;
  private final NpmHostedService npmHosted;
  private final NpmProxyService npmProxy;
  private final NpmGroupService npmGroup;
  private final NpmSearchService npmSearch;
  private final NpmTokenService npmToken;
  private final CargoHostedService cargoHosted;
  private final CargoProxyService cargoProxy;
  private final CargoGroupService cargoGroup;
  private final NugetService nuget;
  private final RubygemsService rubygems;
  private final YumService yum;
  private final RawHostedService rawHosted;
  private final RawProxyService rawProxy;
  private final RawGroupService rawGroup;
  private final ObjectMapper objectMapper;
  private final ForwardedHeaderPolicy forwardedHeaderPolicy;
  private final MavenPathParser parser = new MavenPathParser();
  private final NpmPathParser npmParser = new NpmPathParser();
  private final CargoPathParser cargoParser = new CargoPathParser();
  private final MavenPartialFetchSupport partialFetch = new MavenPartialFetchSupport();

  public RepositoryContentController(RepositoryRuntimeRegistry registry,
      MavenHostedService hosted, MavenProxyService proxy, MavenGroupService group,
      GoProxyService goProxy, GoGroupService goGroup,
      HelmHostedService helmHosted, HelmProxyService helmProxy,
      MavenHtmlListingService htmlListing,
      NpmHostedService npmHosted, NpmProxyService npmProxy, NpmGroupService npmGroup,
      NpmSearchService npmSearch, NpmTokenService npmToken,
      CargoHostedService cargoHosted, CargoProxyService cargoProxy, CargoGroupService cargoGroup,
      NugetService nuget,
      RubygemsService rubygems,
      YumService yum,
      RawHostedService rawHosted, RawProxyService rawProxy, RawGroupService rawGroup,
      ObjectMapper objectMapper,
      ForwardedHeaderPolicy forwardedHeaderPolicy) {
    this.registry = registry;
    this.hosted = hosted;
    this.proxy = proxy;
    this.group = group;
    this.goProxy = goProxy;
    this.goGroup = goGroup;
    this.helmHosted = helmHosted;
    this.helmProxy = helmProxy;
    this.htmlListing = htmlListing;
    this.npmHosted = npmHosted;
    this.npmProxy = npmProxy;
    this.npmGroup = npmGroup;
    this.npmSearch = npmSearch;
    this.npmToken = npmToken;
    this.cargoHosted = cargoHosted;
    this.cargoProxy = cargoProxy;
    this.cargoGroup = cargoGroup;
    this.nuget = nuget;
    this.rubygems = rubygems;
    this.yum = yum;
    this.rawHosted = rawHosted;
    this.rawProxy = rawProxy;
    this.rawGroup = rawGroup;
    this.objectMapper = objectMapper;
    this.forwardedHeaderPolicy = forwardedHeaderPolicy;
  }

  @GetMapping("/**")
  public ResponseEntity<StreamingResponseBody> get(
      @PathVariable("name") String name, HttpServletRequest request) {
    return serveBody(name, request, false);
  }

  @RequestMapping(value = "/**", method = RequestMethod.HEAD)
  public ResponseEntity<Void> head(@PathVariable("name") String name, HttpServletRequest request) {
    RepositoryRuntime runtime = resolveRuntime(name);
    if (runtime.format() == RepositoryFormat.NPM) {
      NpmPath path = npmParser.parse(extractRepositoryPath(name, request));
      return toHeadResponse(dispatchNpmGet(runtime, path, request, true), request);
    }
    if (runtime.format() == RepositoryFormat.GO) {
      String raw = extractRepositoryPath(name, request, true);
      MavenResponse resp = raw.isBlank()
          ? repositoryInfo(runtime, request, "go", true)
          : dispatchGoGet(runtime, raw, true);
      return toHeadResponse(resp, request);
    }
    if (runtime.format() == RepositoryFormat.HELM) {
      String raw = extractRepositoryPath(name, request);
      MavenResponse resp = raw.isBlank()
          ? helmRepositoryInfo(runtime, request, true)
          : dispatchHelmGet(runtime, raw, true);
      return toHeadResponse(resp, request);
    }
    if (runtime.format() == RepositoryFormat.CARGO) {
      CargoPath path = cargoParser.parse(extractRepositoryPath(name, request, true));
      MavenResponse resp = dispatchCargoGet(runtime, path, repositoryBaseUrl(request, runtime.name()), true);
      return toHeadResponse(resp, request);
    }
    if (runtime.format() == RepositoryFormat.NUGET) {
      String raw = extractRepositoryPath(name, request, true);
      MavenResponse resp = nuget.get(runtime, raw, repositoryBaseUrl(request, runtime.name()), request, true);
      return toHeadResponse(resp, request);
    }
    if (runtime.format() == RepositoryFormat.RUBYGEMS) {
      String raw = withQuery(extractRepositoryPath(name, request, true), request);
      MavenResponse resp = rubygems.get(runtime, raw, true);
      return toHeadResponse(resp, request);
    }
    if (runtime.format() == RepositoryFormat.YUM) {
      String raw = extractRepositoryPath(name, request, true);
      MavenResponse resp = yum.get(runtime, raw, true);
      return toHeadResponse(resp, request);
    }
    if (isPathRepository(runtime.format())) {
      String raw = extractRepositoryPath(name, request, true);
      MavenResponse resp = raw.isBlank()
          ? repositoryInfo(runtime, request, runtime.format().id(), true)
          : dispatchRawGet(runtime, raw, true);
      return toHeadResponse(resp, request);
    }
    DirectoryRequest dir = detectDirectory(name, request);
    if (dir != null) {
      MavenResponse resp = dir.needsRedirect()
          ? badRepositoryPath(true)
          : mavenRepositoryInfo(runtime, request, true);
      return toHeadResponse(resp, request);
    }
    RuntimeAndPath rp = resolve(runtime, name, request);
    MavenResponse resp = dispatchGet(rp, true);
    return toHeadResponse(resp, request);
  }

  @PutMapping("/**")
  public ResponseEntity<?> put(
      @PathVariable("name") String name,
      HttpServletRequest request,
      @RequestHeader(value = HttpHeaders.CONTENT_TYPE, required = false) String contentType) throws IOException {
    RepositoryRuntime runtime = resolveRuntime(name);
    String userId = requestUserId(request);
    if (runtime.format() == RepositoryFormat.NPM) {
      String rawPath = extractRepositoryPath(name, request);
      if (NpmTokenService.isLoginPath(rawPath)) {
        try (InputStream body = request.getInputStream()) {
          return toStreamingResponse(npmToken.login(body));
        }
      }
      NpmPath path = npmParser.parse(rawPath);
      MavenResponse resp;
      try (InputStream body = request.getInputStream()) {
        resp = switch (path.kind()) {
          case PACKAGE_ROOT -> npmHosted.putPackage(runtime, path.packageId(), path.revision(),
              body, userId, request.getRemoteAddr());
          case DIST_TAG -> npmHosted.putDistTag(runtime, path.packageId(), path.tag(),
              body, userId, request.getRemoteAddr());
          default -> throw new NpmExceptions.MethodNotAllowed("Unsupported npm PUT path: " + path.rawPath());
        };
      }
      return toStreamingResponse(resp);
    }
    if (runtime.format() == RepositoryFormat.HELM) {
      String raw = extractRepositoryPath(name, request);
      MavenResponse resp;
      try (InputStream body = request.getInputStream()) {
        resp = helmHosted.put(runtime, raw, body, contentType,
            userId, request.getRemoteAddr());
      }
      return ResponseEntity.status(resp.status()).build();
    }
    if (runtime.format() == RepositoryFormat.CARGO) {
      CargoPath path = cargoParser.parse(extractRepositoryPath(name, request, true));
      MavenResponse resp;
      if (path.kind() == CargoPath.Kind.PUBLISH) {
        try (InputStream body = request.getInputStream()) {
          resp = cargoHosted.publish(runtime, body, userId, request.getRemoteAddr());
        }
      } else if (path.kind() == CargoPath.Kind.UNYANK) {
        resp = cargoHosted.yank(runtime, path.crateName(), path.version(), false);
      } else {
        throw new CargoExceptions.MethodNotAllowed("Unsupported Cargo PUT path: " + path.rawPath());
      }
      return toByteArrayResponse(resp);
    }
    if (runtime.format() == RepositoryFormat.NUGET) {
      String raw = extractRepositoryPath(name, request, true);
      MavenResponse resp;
      if (raw.isBlank() && isMultipart(contentType)) {
        Part part = nugetPackagePart(request);
        try (InputStream body = part.getInputStream()) {
          resp = nuget.putPackage(runtime, NugetPaths.PACKAGE_PUBLISH, body,
              part.getContentType(), userId, request.getRemoteAddr());
        }
      } else {
        try (InputStream body = request.getInputStream()) {
          resp = nuget.putPackage(runtime, raw, body, contentType, userId, request.getRemoteAddr());
        }
      }
      return ResponseEntity.status(resp.status()).build();
    }
    if (runtime.format() == RepositoryFormat.RUBYGEMS) {
      String raw = extractRepositoryPath(name, request, true);
      MavenResponse resp;
      try (InputStream body = request.getInputStream()) {
        resp = rubygems.put(runtime, raw, body, contentType, userId, request.getRemoteAddr());
      }
      return ResponseEntity.status(resp.status()).build();
    }
    if (runtime.format() == RepositoryFormat.YUM) {
      String raw = extractRepositoryPath(name, request, true);
      MavenResponse resp;
      try (InputStream body = request.getInputStream()) {
        resp = yum.put(runtime, raw, body, contentType, userId, request.getRemoteAddr());
      }
      return ResponseEntity.status(resp.status()).build();
    }
    if (isPathRepository(runtime.format())) {
      String raw = extractRepositoryPath(name, request);
      MavenResponse resp;
      try (InputStream body = request.getInputStream()) {
        resp = rawHosted.put(runtime, raw, body, contentType,
            userId, request.getRemoteAddr());
      }
      return ResponseEntity.status(resp.status()).build();
    }
    if (runtime.format() != RepositoryFormat.MAVEN2) {
      throw new MavenExceptions.MethodNotAllowed("PUT is not supported for " + runtime.format() + " repositories");
    }
    RuntimeAndPath rp = resolve(runtime, name, request);
    MavenResponse resp;
    try (InputStream body = request.getInputStream()) {
      resp = hosted.put(rp.runtime(), rp.path(), body, contentType,
          userId, request.getRemoteAddr());
    }
    return ResponseEntity.status(resp.status()).build();
  }

  @DeleteMapping("/**")
  public ResponseEntity<?> delete(@PathVariable("name") String name, HttpServletRequest request) throws IOException {
    RepositoryRuntime runtime = resolveRuntime(name);
    if (runtime.format() == RepositoryFormat.NPM) {
      String rawPath = extractRepositoryPath(name, request);
      if (NpmTokenService.isLogoutPath(rawPath)) {
        return toStreamingResponse(npmToken.logout(request));
      }
      NpmPath path = npmParser.parse(rawPath);
      String userId = requestUserId(request);
      MavenResponse resp = switch (path.kind()) {
        case PACKAGE_ROOT -> npmHosted.deletePackage(runtime, path.packageId(), path.revision());
        case TARBALL -> npmHosted.deleteTarball(runtime, path.packageId(), path.tarballName(), path.revision());
        case DIST_TAG -> npmHosted.deleteDistTag(runtime, path.packageId(), path.tag(), userId, request.getRemoteAddr());
        default -> throw new NpmExceptions.MethodNotAllowed("Unsupported npm DELETE path: " + path.rawPath());
      };
      return toStreamingResponse(resp);
    }
    if (runtime.format() == RepositoryFormat.HELM) {
      String raw = extractRepositoryPath(name, request);
      MavenResponse resp = helmHosted.delete(runtime, raw);
      return ResponseEntity.status(resp.status()).build();
    }
    if (runtime.format() == RepositoryFormat.CARGO) {
      CargoPath path = cargoParser.parse(extractRepositoryPath(name, request, true));
      if (path.kind() != CargoPath.Kind.YANK) {
        throw new CargoExceptions.MethodNotAllowed("Unsupported Cargo DELETE path: " + path.rawPath());
      }
      MavenResponse resp = cargoHosted.yank(runtime, path.crateName(), path.version(), true);
      return toByteArrayResponse(resp);
    }
    if (runtime.format() == RepositoryFormat.NUGET) {
      String raw = extractRepositoryPath(name, request, true);
      MavenResponse resp = nuget.deletePackage(runtime, raw);
      return ResponseEntity.status(resp.status()).build();
    }
    if (runtime.format() == RepositoryFormat.RUBYGEMS) {
      String raw = withQuery(extractRepositoryPath(name, request, true), request);
      MavenResponse resp = rubygems.delete(runtime, raw);
      return ResponseEntity.status(resp.status()).build();
    }
    if (runtime.format() == RepositoryFormat.YUM) {
      String raw = extractRepositoryPath(name, request, true);
      MavenResponse resp = yum.delete(runtime, raw);
      return ResponseEntity.status(resp.status()).build();
    }
    if (isPathRepository(runtime.format())) {
      String raw = extractRepositoryPath(name, request);
      MavenResponse resp = rawHosted.delete(runtime, raw);
      return ResponseEntity.status(resp.status()).build();
    }
    if (runtime.format() != RepositoryFormat.MAVEN2) {
      throw new MavenExceptions.MethodNotAllowed("DELETE is not supported for " + runtime.format() + " repositories");
    }
    RuntimeAndPath rp = resolve(runtime, name, request);
    MavenResponse resp = hosted.delete(rp.runtime(), rp.path());
    return ResponseEntity.status(resp.status()).build();
  }

  @PostMapping("/**")
  public ResponseEntity<?> post(@PathVariable("name") String name, HttpServletRequest request) {
    RepositoryRuntime runtime = resolveRuntime(name);
    if (runtime.format() == RepositoryFormat.NPM) {
      NpmPath path = npmParser.parse(extractRepositoryPath(name, request));
      if (path.kind() == NpmPath.Kind.ADVISORIES_BULK) {
        return ResponseEntity.ok()
            .contentType(MediaType.APPLICATION_JSON)
            .body(Map.of());
      }
      if (path.kind() == NpmPath.Kind.AUDIT || path.kind() == NpmPath.Kind.AUDIT_QUICK) {
        return ResponseEntity.ok()
            .contentType(MediaType.APPLICATION_JSON)
            .body(emptyNpmAuditReport());
      }
      throw new NpmExceptions.MethodNotAllowed("Unsupported npm POST path: " + path.rawPath());
    }
    if (runtime.format() == RepositoryFormat.RUBYGEMS) {
      String raw = withQuery(extractRepositoryPath(name, request, true), request);
      String userId = requestUserId(request);
      if (raw.startsWith("api/v1/gems/yank")) {
        MavenResponse resp = rubygems.delete(runtime, raw);
        return ResponseEntity.status(resp.status()).build();
      }
      try (InputStream body = request.getInputStream()) {
        MavenResponse resp = rubygems.put(runtime, raw, body,
            request.getContentType(), userId, request.getRemoteAddr());
        return ResponseEntity.status(resp.status()).build();
      } catch (IOException e) {
        throw new IllegalStateException("Failed reading RubyGems request body", e);
      }
    }
    if (runtime.format() != RepositoryFormat.HELM) {
      throw new MavenExceptions.MethodNotAllowed("POST is not supported for " + runtime.format() + " repositories");
    }
    throw new MavenExceptions.MethodNotAllowed("Unsupported Helm POST path: " + extractRepositoryPath(name, request));
  }

  private Map<String, Object> emptyNpmAuditReport() {
    Map<String, Integer> severities = Map.of(
        "info", 0,
        "low", 0,
        "moderate", 0,
        "high", 0,
        "critical", 0);
    return Map.of(
        "actions", List.of(),
        "advisories", Map.of(),
        "muted", List.of(),
        "metadata", Map.of(
            "vulnerabilities", severities,
            "dependencies", 0,
            "devDependencies", 0,
            "optionalDependencies", 0,
            "totalDependencies", 0));
  }

  @PostMapping(value = "/api/charts", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  public ResponseEntity<?> pushHelmChart(
      @PathVariable("name") String name,
      @RequestPart("chart") MultipartFile chart,
      HttpServletRequest request) throws IOException {
    RepositoryRuntime runtime = resolveRuntime(name);
    if (runtime.format() != RepositoryFormat.HELM) {
      throw new MavenExceptions.MethodNotAllowed("POST is not supported for " + runtime.format() + " repositories");
    }
    MavenResponse resp = helmHosted.push(runtime, chart, "anonymous", request.getRemoteAddr());
    return ResponseEntity.status(resp.status()).build();
  }

  private ResponseEntity<StreamingResponseBody> serveBody(
      String name, HttpServletRequest request, boolean headOnly) {
    RepositoryRuntime runtime = resolveRuntime(name);
    if (runtime.format() == RepositoryFormat.NPM) {
      NpmPath path = npmParser.parse(extractRepositoryPath(name, request));
      MavenResponse resp = dispatchNpmGet(runtime, path, request, headOnly);
      return toStreamingResponse(resp, request, false);
    }
    if (runtime.format() == RepositoryFormat.GO) {
      String raw = extractRepositoryPath(name, request, true);
      MavenResponse resp = raw.isBlank()
          ? repositoryInfo(runtime, request, "go", headOnly)
          : dispatchGoGet(runtime, raw, headOnly);
      return toStreamingResponse(resp, request, false);
    }
    if (runtime.format() == RepositoryFormat.HELM) {
      String raw = extractRepositoryPath(name, request);
      MavenResponse resp = raw.isBlank()
          ? helmRepositoryInfo(runtime, request, headOnly)
          : dispatchHelmGet(runtime, raw, headOnly);
      return toStreamingResponse(resp, request, false);
    }
    if (runtime.format() == RepositoryFormat.CARGO) {
      CargoPath path = cargoParser.parse(extractRepositoryPath(name, request, true));
      MavenResponse resp = dispatchCargoGet(runtime, path, repositoryBaseUrl(request, runtime.name()), headOnly);
      return toStreamingResponse(resp, request, false);
    }
    if (runtime.format() == RepositoryFormat.NUGET) {
      String raw = extractRepositoryPath(name, request, true);
      MavenResponse resp = nuget.get(runtime, raw, repositoryBaseUrl(request, runtime.name()), request, headOnly);
      return toStreamingResponse(resp, request, false);
    }
    if (runtime.format() == RepositoryFormat.RUBYGEMS) {
      String raw = withQuery(extractRepositoryPath(name, request, true), request);
      MavenResponse resp = rubygems.get(runtime, raw, headOnly);
      return toStreamingResponse(resp, request, false);
    }
    if (runtime.format() == RepositoryFormat.YUM) {
      String raw = extractRepositoryPath(name, request, true);
      MavenResponse resp = yum.get(runtime, raw, headOnly);
      return toStreamingResponse(resp, request, false);
    }
    if (isPathRepository(runtime.format())) {
      String raw = extractRepositoryPath(name, request, true);
      MavenResponse resp = raw.isBlank()
          ? repositoryInfo(runtime, request, runtime.format().id(), headOnly)
          : dispatchRawGet(runtime, raw, headOnly);
      return toStreamingResponse(resp, request, false);
    }
    DirectoryRequest dir = detectDirectory(name, request);
    if (dir != null) {
      if (dir.needsRedirect()) {
        return toStreamingResponse(badRepositoryPath(headOnly), request, false);
      }
      return toStreamingResponse(mavenRepositoryInfo(runtime, request, headOnly), request, false);
    }
    RuntimeAndPath rp = resolve(runtime, name, request);
    MavenResponse resp = dispatchGet(rp, headOnly);
    return toStreamingResponse(resp, request, !headOnly && !parser.isRepositoryMetadata(rp.path()));
  }

  private ResponseEntity<Void> toHeadResponse(MavenResponse resp, HttpServletRequest request) {
    if (ConditionalResponses.shouldReturnNotModified(
        request, resp.status(), resp.etag(), resp.lastModified())) {
      return ResponseEntity.status(HttpStatus.NOT_MODIFIED).headers(notModifiedHeaders(resp)).build();
    }
    HttpHeaders headers = responseHeaders(resp, true);
    return ResponseEntity.status(resp.status()).headers(headers).build();
  }

  private ResponseEntity<StreamingResponseBody> toStreamingResponse(MavenResponse resp) {
    return toStreamingResponse(resp, null, false);
  }

  private ResponseEntity<byte[]> toByteArrayResponse(MavenResponse resp) throws IOException {
    HttpHeaders headers = responseHeaders(resp, true);
    InputStream responseBody = resp.body();
    if (responseBody == null) {
      headers.setContentLength(0);
      return ResponseEntity.status(resp.status()).headers(headers).body(null);
    }
    byte[] bytes;
    try (responseBody) {
      bytes = responseBody.readAllBytes();
    }
    headers.setContentLength(bytes.length);
    return ResponseEntity.status(resp.status()).headers(headers).body(bytes);
  }

  private ResponseEntity<StreamingResponseBody> toStreamingResponse(MavenResponse resp, HttpServletRequest request) {
    return toStreamingResponse(resp, request, false);
  }

  private ResponseEntity<StreamingResponseBody> toStreamingResponse(
      MavenResponse resp, HttpServletRequest request, boolean partialFetchAllowed) {
    if (ConditionalResponses.shouldReturnNotModified(
        request, resp.status(), resp.etag(), resp.lastModified())) {
      return ResponseEntity.status(HttpStatus.NOT_MODIFIED)
          .headers(notModifiedHeaders(resp))
          .body(null);
    }
    if (partialFetchAllowed) {
      resp = partialFetch.apply(request, resp);
    }
    HttpHeaders headers = responseHeaders(resp, true);
    InputStream responseBody = resp.body();
    if (TempBlobFiles.tryUseTomcatSendfile(request, responseBody)) {
      return ResponseEntity.status(resp.status()).headers(headers).body(null);
    }
    StreamingResponseBody body = output -> TempBlobFiles.copyResponse(responseBody, output, request);
    return ResponseEntity.status(resp.status()).headers(headers).body(body);
  }

  private HttpHeaders responseHeaders(MavenResponse resp, boolean includeEntityHeaders) {
    HttpHeaders headers = new HttpHeaders();
    if (includeEntityHeaders && resp.contentType() != null) {
      try {
        headers.setContentType(MediaType.parseMediaType(resp.contentType()));
      } catch (RuntimeException ignored) {
        headers.add(HttpHeaders.CONTENT_TYPE, resp.contentType());
      }
    }
    if (includeEntityHeaders && resp.contentLength() > 0) {
      headers.setContentLength(resp.contentLength());
    }
    ConditionalResponses.addValidators(headers, resp.etag(), resp.lastModified());
    resp.headers().forEach(headers::add);
    return headers;
  }

  private HttpHeaders notModifiedHeaders(MavenResponse resp) {
    return responseHeaders(resp, false);
  }

  private MavenResponse dispatchGet(RuntimeAndPath rp, boolean headOnly) {
    return switch (rp.runtime().type()) {
      case HOSTED -> hosted.get(rp.runtime(), rp.path(), headOnly);
      case PROXY -> proxy.get(rp.runtime(), rp.path(), headOnly);
      case GROUP -> group.get(rp.runtime(), rp.path(), headOnly);
    };
  }

  private MavenResponse dispatchHelmGet(RepositoryRuntime runtime, String rawPath, boolean headOnly) {
    return switch (runtime.type()) {
      case HOSTED -> helmHosted.get(runtime, rawPath, headOnly);
      case PROXY -> helmProxy.get(runtime, rawPath, headOnly);
      case GROUP -> throw new MavenExceptions.MavenNotFoundException(
          "Helm group repositories are not supported: " + runtime.name());
    };
  }

  private MavenResponse dispatchGoGet(RepositoryRuntime runtime, String rawPath, boolean headOnly) {
    return switch (runtime.type()) {
      case PROXY -> goProxy.get(runtime, rawPath, headOnly);
      case GROUP -> goGroup.get(runtime, rawPath, headOnly);
      case HOSTED -> throw new MavenExceptions.MavenNotFoundException(
          "Go hosted repositories are not supported: " + runtime.name());
    };
  }

  private MavenResponse dispatchRawGet(RepositoryRuntime runtime, String rawPath, boolean headOnly) {
    return switch (runtime.type()) {
      case HOSTED -> rawHosted.get(runtime, rawPath, headOnly);
      case PROXY -> rawProxy.get(runtime, rawPath, headOnly);
      case GROUP -> rawGroup.get(runtime, rawPath, headOnly);
    };
  }

  private MavenResponse dispatchCargoGet(
      RepositoryRuntime runtime,
      CargoPath path,
      String baseUrl,
      boolean headOnly) {
    return switch (runtime.type()) {
      case HOSTED -> cargoHosted.get(runtime, path, baseUrl, headOnly);
      case PROXY -> cargoProxy.get(runtime, path, baseUrl, headOnly);
      case GROUP -> cargoGroup.get(runtime, path, baseUrl, headOnly);
    };
  }

  private RuntimeAndPath resolve(String name, HttpServletRequest request) {
    return resolve(resolveRuntime(name), name, request);
  }

  private RuntimeAndPath resolve(RepositoryRuntime runtime, String name, HttpServletRequest request) {
    if (runtime.format() != RepositoryFormat.MAVEN2) {
      throw new MavenExceptions.MavenNotFoundException("Repository is not Maven format: " + name);
    }
    String raw = extractMavenPath(name, request);
    MavenPath path = parser.parsePath(raw);
    return new RuntimeAndPath(runtime, path);
  }

  private RepositoryRuntime resolveRuntime(String name) {
    return registry.resolve(name)
        .orElseThrow(() -> new MavenExceptions.MavenNotFoundException("Repository not found: " + name));
  }

  private MavenResponse dispatchNpmGet(
      RepositoryRuntime runtime,
      NpmPath path,
      HttpServletRequest request,
      boolean headOnly) {
    return switch (path.kind()) {
      case REPOSITORY_ROOT -> npmRepositoryRoot(runtime, request, headOnly);
      case PING -> npmJson(Map.of(), headOnly);
      case WHOAMI -> npmWhoami(request, headOnly);
      case SEARCH_V1 -> npmJson(npmSearch.search(runtime, request.getParameter("text"),
          parsePositiveInt(request.getParameter("size"), 20),
          repositoryBaseUrl(request, runtime.name())), headOnly);
      case SEARCH_INDEX -> npmJson(npmSearch.legacyIndex(runtime), headOnly);
      case PACKAGE_ROOT, PACKAGE_VERSION, TARBALL, DIST_TAGS -> switch (runtime.type()) {
        case HOSTED -> npmHosted.get(runtime, path, repositoryBaseUrl(request, runtime.name()), headOnly,
            npmPackumentVariant(request));
        case PROXY -> npmProxy.get(runtime, path, repositoryBaseUrl(request, runtime.name()), headOnly,
            npmPackumentVariant(request));
        case GROUP -> npmGroup.get(runtime, path, repositoryBaseUrl(request, runtime.name()), headOnly,
            npmPackumentVariant(request));
      };
      default -> throw new NpmExceptions.NpmNotFoundException(path.rawPath());
    };
  }

  private NpmPackumentVariant npmPackumentVariant(HttpServletRequest request) {
    return NpmPackumentVariant.fromAccept(request.getHeader(HttpHeaders.ACCEPT));
  }

  private MavenResponse mavenRepositoryInfo(
      RepositoryRuntime runtime,
      HttpServletRequest request,
      boolean headOnly) {
    return repositoryInfo(runtime, request, "maven2", headOnly);
  }

  private MavenResponse npmWhoami(HttpServletRequest request, boolean headOnly) {
    return npmJson(Map.of("username", requestUserId(request)), headOnly);
  }

  private String requestUserId(HttpServletRequest request) {
    Object subject = request.getAttribute(AuthenticatedSubject.REQUEST_ATTRIBUTE);
    if (subject instanceof AuthenticatedSubject authenticated
        && authenticated.userId() != null
        && !authenticated.userId().isBlank()) {
      return authenticated.userId();
    }
    return "anonymous";
  }

  private MavenResponse npmRepositoryRoot(
      RepositoryRuntime runtime,
      HttpServletRequest request,
      boolean headOnly) {
    return repositoryInfo(runtime, request, "npm", headOnly);
  }

  private MavenResponse helmRepositoryInfo(
      RepositoryRuntime runtime,
      HttpServletRequest request,
      boolean headOnly) {
    return repositoryInfo(runtime, request, "helm", headOnly);
  }

  private MavenResponse rawRepositoryInfo(
      RepositoryRuntime runtime,
      HttpServletRequest request,
      boolean headOnly) {
    return repositoryInfo(runtime, request, "raw", headOnly);
  }

  private static boolean isPathRepository(RepositoryFormat format) {
    return format == RepositoryFormat.RAW;
  }

  private MavenResponse repositoryInfo(
      RepositoryRuntime runtime,
      HttpServletRequest request,
      String formatLabel,
      boolean headOnly) {
    String type = runtime.type().name().toLowerCase(Locale.ROOT);
    String body = """
        <!DOCTYPE html>
        <html lang="en">
        <head>
          <title>Repository - Nexus Repository Manager</title>
          <meta http-equiv="Content-Type" content="text/html; charset=UTF-8"/>
        </head>
        <body>
        <div class="nexus-body">
          <div class="content-header">
            <span class="title">Repository</span>
            <span class="description">%s</span>
          </div>
          <div class="content-body">
            <div class="content-section">
              <p>This %s %s repository is not directly browseable at this URL.</p>
              <p>Please use the <a href="%s/browse/">browse</a>
              or <a href="%s/service/rest/repository/browse/%s/">HTML index</a>
              views to inspect the contents of this repository.</p>
            </div>
          </div>
        </div>
        </body>
        </html>
        """.formatted(
        escapeHtml(runtime.name()),
        escapeHtml(formatLabel),
        escapeHtml(type),
        escapeHtml(request.getContextPath().isBlank() ? "" : request.getContextPath()),
        escapeHtml(serverBaseUrl(request)),
        escapeHtml(runtime.name()));
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    if (headOnly) {
      return MavenResponse.noBody(200, bytes.length, MediaType.TEXT_HTML_VALUE, null, null);
    }
    return MavenResponse.ok(new ByteArrayInputStream(bytes), bytes.length, MediaType.TEXT_HTML_VALUE, null, null);
  }

  private MavenResponse badRepositoryPath(boolean headOnly) {
    String body = """
        <!DOCTYPE html>
        <html lang="en">
        <head>
          <title>400 - Nexus Repository Manager</title>
          <meta http-equiv="Content-Type" content="text/html; charset=UTF-8"/>
        </head>
        <body>
        <div class="nexus-body">
          <div class="content-header">
            <span class="title">Error 400</span>
            <span class="description">Bad Request</span>
          </div>
          <div class="content-body">
            <div class="content-section">
              Repository path must have another '/' after initial '/'
            </div>
          </div>
        </div>
        </body>
        </html>
        """;
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    if (headOnly) {
      return MavenResponse.noBody(400, bytes.length, MediaType.TEXT_HTML_VALUE, null, null);
    }
    return MavenResponse.ok(new ByteArrayInputStream(bytes), bytes.length, MediaType.TEXT_HTML_VALUE, null, null)
        .withStatus(400);
  }

  private MavenResponse npmJson(Object value, boolean headOnly) {
    byte[] bytes;
    try {
      bytes = objectMapper.writeValueAsBytes(value);
    } catch (IOException e) {
      throw new IllegalStateException("Failed to serialize npm JSON", e);
    }
    if (headOnly) {
      return MavenResponse.noBody(200, bytes.length, "application/json", null, null);
    }
    return MavenResponse.ok(new ByteArrayInputStream(bytes), bytes.length, "application/json", null, null);
  }

  private String extractMavenPath(String name, HttpServletRequest request) {
    String uri = request.getRequestURI();
    String prefix = "/repository/" + name + "/";
    int idx = uri.indexOf(prefix);
    if (idx < 0) {
      throw new MavenExceptions.MavenNotFoundException("Bad maven URL: " + uri);
    }
    String rest = uri.substring(idx + prefix.length());
    if (rest.isEmpty()) {
      throw new MavenExceptions.MavenNotFoundException("Missing path");
    }
    return rest;
  }

  private String extractRepositoryPath(String name, HttpServletRequest request, boolean allowEmpty) {
    String uri = request.getRequestURI();
    String root = request.getContextPath() + "/repository/" + name;
    if (uri.equals(root)) {
      if (allowEmpty) return "";
      throw new MavenExceptions.MavenNotFoundException("Missing path");
    }
    String prefix = root + "/";
    if (!uri.startsWith(prefix)) {
      throw new MavenExceptions.MavenNotFoundException("Bad repository URL: " + uri);
    }
    String rest = uri.substring(prefix.length());
    if (rest.isEmpty() && !allowEmpty) {
      throw new MavenExceptions.MavenNotFoundException("Missing path");
    }
    return rest;
  }

  private String extractRepositoryPath(String name, HttpServletRequest request) {
    String uri = request.getRequestURI();
    String base = request.getContextPath() + "/repository/" + name;
    if (uri.equals(base)) {
      return "";
    }
    String prefix = base + "/";
    int idx = uri.indexOf(prefix);
    if (idx < 0) {
      throw new NpmExceptions.NpmNotFoundException("Bad npm URL: " + uri);
    }
    return uri.substring(idx + prefix.length());
  }

  private static String withQuery(String path, HttpServletRequest request) {
    String query = request.getQueryString();
    return query == null || query.isBlank() ? path : path + "?" + query;
  }

  private String repositoryBaseUrl(HttpServletRequest request, String name) {
    return serverBaseUrl(request) + request.getContextPath() + "/repository/" + name;
  }

  private String serverBaseUrl(HttpServletRequest request) {
    boolean trustedForwarded = forwardedHeaderPolicy.trusted(request);
    String scheme = trustedForwarded ? firstHeader(request, "X-Forwarded-Proto", request.getScheme()) : request.getScheme();
    String host = trustedForwarded ? firstHeader(request, "X-Forwarded-Host", request.getServerName()) : request.getServerName();
    String portHeader = trustedForwarded ? request.getHeader("X-Forwarded-Port") : null;
    String authority = host;
    if (portHeader == null && !host.contains(":")) {
      int port = request.getServerPort();
      boolean standard = ("http".equalsIgnoreCase(scheme) && port == 80)
          || ("https".equalsIgnoreCase(scheme) && port == 443);
      if (!standard) authority = host + ":" + port;
    }
    return scheme + "://" + authority;
  }

  private static String firstHeader(HttpServletRequest request, String name, String fallback) {
    String value = request.getHeader(name);
    if (value == null || value.isBlank()) return fallback;
    int comma = value.indexOf(',');
    return comma < 0 ? value.trim() : value.substring(0, comma).trim();
  }

  private static int parsePositiveInt(String raw, int fallback) {
    if (raw == null || raw.isBlank()) return fallback;
    try {
      return Math.max(1, Integer.parseInt(raw));
    } catch (NumberFormatException e) {
      return fallback;
    }
  }

  private static String escapeHtml(String s) {
    if (s == null) return "";
    return s.replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;");
  }

  private record RuntimeAndPath(RepositoryRuntime runtime, MavenPath path) {}

  private static boolean isMultipart(String contentType) {
    return contentType != null
        && contentType.toLowerCase(Locale.ROOT).startsWith(MediaType.MULTIPART_FORM_DATA_VALUE);
  }

  private static Part nugetPackagePart(HttpServletRequest request) throws IOException {
    try {
      Part firstFile = null;
      for (Part part : request.getParts()) {
        if (part == null || part.getSize() <= 0) {
          continue;
        }
        boolean namedPackage = "package".equals(part.getName());
        boolean hasFilename = part.getSubmittedFileName() != null
            && !part.getSubmittedFileName().isBlank();
        if (namedPackage) {
          return part;
        }
        if (firstFile == null && hasFilename) {
          firstFile = part;
        }
      }
      if (firstFile != null) {
        return firstFile;
      }
      throw new MavenExceptions.LayoutPolicyViolation("NuGet multipart upload requires a package file part");
    } catch (ServletException e) {
      throw new MavenExceptions.LayoutPolicyViolation("Invalid NuGet multipart upload");
    }
  }

  /**
   * Detects {@code /repository/{name}} (needsRedirect=true) and {@code /repository/{name}/[path/]}
   * (directory). Returns null for file requests.
   */
  private DirectoryRequest detectDirectory(String name, HttpServletRequest request) {
    String uri = request.getRequestURI();
    String prefix = "/repository/" + name;
    if (uri.equals(prefix)) {
      return new DirectoryRequest("", true);
    }
    String prefixSlash = prefix + "/";
    if (!uri.startsWith(prefixSlash)) {
      return null;
    }
    String rest = uri.substring(prefixSlash.length());
    if (rest.isEmpty()) {
      return new DirectoryRequest("", false);
    }
    if (rest.endsWith("/")) {
      return new DirectoryRequest(rest.substring(0, rest.length() - 1), false);
    }
    return null;
  }

  private record DirectoryRequest(String path, boolean needsRedirect) {}

}
