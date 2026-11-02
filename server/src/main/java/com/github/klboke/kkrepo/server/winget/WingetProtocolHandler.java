package com.github.klboke.kkrepo.server.winget;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.core.RepositoryType;
import com.github.klboke.kkrepo.protocol.winget.WingetException;
import com.github.klboke.kkrepo.protocol.winget.WingetManifest;
import com.github.klboke.kkrepo.protocol.winget.WingetManifestParser;
import com.github.klboke.kkrepo.protocol.winget.WingetPackage;
import com.github.klboke.kkrepo.protocol.winget.WingetPath;
import com.github.klboke.kkrepo.protocol.winget.WingetProtocol;
import com.github.klboke.kkrepo.protocol.winget.WingetSearchRequest;
import com.github.klboke.kkrepo.protocol.winget.WingetVersion;
import com.github.klboke.kkrepo.protocol.winget.WingetVersionSummary;
import com.github.klboke.kkrepo.server.config.RepositoryUrlPrefix;
import com.github.klboke.kkrepo.server.routing.RepositoryProtocolHandler;
import com.github.klboke.kkrepo.server.routing.RepositoryProtocolRequest;
import com.github.klboke.kkrepo.server.routing.RepositoryProtocolRoute;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** WinGet REST source API ({@code /api/...}) handler. */
@Component
public class WingetProtocolHandler implements RepositoryProtocolHandler {
  private static final Logger log = LoggerFactory.getLogger(WingetProtocolHandler.class);
  private static final List<HttpMethod> ALL_METHODS = List.of(
      HttpMethod.GET, HttpMethod.HEAD, HttpMethod.POST, HttpMethod.PUT, HttpMethod.DELETE);

  private final WingetService service;
  private final ObjectMapper mapper;
  private final WingetPath.WingetPathParser paths = WingetPath.parser();
  private final WingetManifestParser parser = new WingetManifestParser();

  public WingetProtocolHandler(WingetService service, ObjectMapper mapper) {
    this.service = service;
    this.mapper = mapper;
  }

  @Override
  public Collection<RepositoryProtocolRoute> routes() {
    return List.of(RepositoryType.HOSTED, RepositoryType.PROXY, RepositoryType.GROUP).stream()
        .flatMap(type -> ALL_METHODS.stream()
            .map(method -> RepositoryProtocolRoute.anyPath(
                RepositoryFormat.WINGET, type, method, 0)))
        .toList();
  }

  @Override
  public ResponseEntity<?> handle(RepositoryProtocolRequest context) {
    WingetPath path = paths.parse(context.path());
    try {
      if (!context.runtime().online()) {
        throw WingetException.unavailable("Repository is offline");
      }
      return switch (path.kind()) {
        case INFORMATION -> information(context);
        case MANIFEST_SEARCH -> search(context);
        case PACKAGE_MANIFESTS -> publish(context);
        case MANIFEST -> manifest(context, path.packageIdentifier());
        case PACKAGES -> throw WingetException.notFound(
            "The granular /api/packages API is not supported; use /api/packageManifests");
        case UNKNOWN -> throw WingetException.notFound("Unknown WinGet route");
      };
    } catch (WingetException error) {
      return error(context, path, error);
    } catch (RuntimeException error) {
      log.error("Unexpected WinGet request failure for path {}", context.path(), error);
      return error(context, path,
          new WingetException(500, "InternalError", "Unexpected WinGet server failure"));
    }
  }

  private ResponseEntity<?> information(RepositoryProtocolRequest context) {
    requireMethods(context, HttpMethod.GET, HttpMethod.HEAD);
    String source = sourceIdentifier(context.runtime().name());
    ObjectNode data = mapper.createObjectNode();
    data.put("SourceIdentifier", source);
    data.set("ServerSupportedVersions", readArray(WingetProtocol.SUPPORTED_VERSIONS));
    data.putArray("UnsupportedPackageMatchFields");
    data.putArray("RequiredPackageMatchFields");
    data.putArray("UnsupportedQueryParameters").add("Market");
    data.putArray("RequiredQueryParameters");
    data.putObject("Authentication").put("AuthenticationType", "none");
    return respond(context, HttpStatus.OK, wrapper(data), new HttpHeaders());
  }

  private ResponseEntity<?> search(RepositoryProtocolRequest context) {
    requireMethods(context, HttpMethod.POST);
    byte[] body = jsonBody(context);
    WingetSearchRequest request = parseSearchRequest(body);
    String token = header(context.servletRequest(), "ContinuationToken");
    int offset = continuationOffset(context, token);
    WingetSearchPage page = service.search(
        context.runtime(), request, body, token, offset, request.maximumResults());
    ObjectNode root = mapper.createObjectNode();
    ArrayNode data = root.putArray("Data");
    for (WingetPackage pkg : page.packages()) {
      data.add(packageJson(pkg));
    }
    root.putArray("UnsupportedPackageMatchFields");
    root.putArray("RequiredPackageMatchFields");
    HttpHeaders headers = new HttpHeaders();
    if (page.upstreamContinuationToken() != null) {
      headers.set("ContinuationToken", page.upstreamContinuationToken());
    } else if (page.nextOffset() != null) {
      headers.set("ContinuationToken", encodeOffset(page.nextOffset()));
    }
    return respond(context, HttpStatus.OK, root, headers);
  }

  private ResponseEntity<?> publish(RepositoryProtocolRequest context) {
    requireMethods(context, HttpMethod.POST);
    WingetManifest manifest = parser.parse(jsonBody(context), mapper);
    WingetManifest published = service.publish(context.runtime(), manifest);
    HttpHeaders headers = new HttpHeaders();
    headers.set(HttpHeaders.LOCATION, location(context, published.packageIdentifier()));
    return respond(context, HttpStatus.CREATED, wrapper(manifestJson(published)), headers);
  }

  private ResponseEntity<?> manifest(
      RepositoryProtocolRequest context, String packageIdentifier) {
    HttpMethod method = context.method();
    if (method == HttpMethod.GET || method == HttpMethod.HEAD) {
      WingetManifest manifest = service.manifest(context.runtime(), packageIdentifier,
          query(context.servletRequest(), "PackageVersion"),
          query(context.servletRequest(), "Channel"));
      return respond(context, HttpStatus.OK, wrapper(manifestJson(manifest)), new HttpHeaders());
    }
    if (method == HttpMethod.PUT) {
      WingetManifest manifest = parser.parse(jsonBody(context), mapper);
      if (!manifest.packageIdentifier().equals(packageIdentifier)) {
        throw WingetException.badRequest(
            "PackageIdentifier in the body does not match the URL");
      }
      WingetManifest replaced = service.replace(context.runtime(), manifest);
      return respond(context, HttpStatus.OK, wrapper(manifestJson(replaced)), new HttpHeaders());
    }
    if (method == HttpMethod.DELETE) {
      service.delete(context.runtime(), packageIdentifier,
          query(context.servletRequest(), "PackageVersion"),
          query(context.servletRequest(), "Channel"));
      return ResponseEntity.noContent()
          .header(HttpHeaders.CACHE_CONTROL, "no-store").build();
    }
    throw WingetException.methodNotAllowed("Method not allowed for a package manifest");
  }

  private ObjectNode manifestJson(WingetManifest manifest) {
    ObjectNode data = mapper.createObjectNode();
    data.put("PackageIdentifier", manifest.packageIdentifier());
    ArrayNode versions = data.putArray("Versions");
    for (WingetVersion version : manifest.versions()) {
      versions.add(version.manifest());
    }
    return data;
  }

  private ObjectNode packageJson(WingetPackage pkg) {
    ObjectNode item = mapper.createObjectNode();
    item.put("PackageIdentifier", pkg.packageIdentifier());
    item.put("PackageName", pkg.packageName());
    item.put("Publisher", pkg.publisher());
    ArrayNode versions = item.putArray("Versions");
    for (WingetVersionSummary version : pkg.versions()) {
      ObjectNode node = versions.addObject();
      node.put("PackageVersion", version.packageVersion());
      node.put("Channel", version.channel() == null ? "" : version.channel());
      addStrings(node, "PackageFamilyNames", version.packageFamilyNames());
      addStrings(node, "ProductCodes", version.productCodes());
      addStrings(node, "AppsAndFeaturesEntryVersions", version.appsAndFeaturesEntryVersions());
      addStrings(node, "UpgradeCodes", version.upgradeCodes());
    }
    return item;
  }

  private static void addStrings(ObjectNode node, String field, List<String> values) {
    ArrayNode array = node.putArray(field);
    if (values != null) values.forEach(array::add);
  }

  private ObjectNode wrapper(ObjectNode data) {
    ObjectNode root = mapper.createObjectNode();
    root.set("Data", data);
    return root;
  }

  private WingetSearchRequest parseSearchRequest(byte[] body) {
    try {
      return WingetSearchRequest.parse(mapper.readTree(body));
    } catch (IOException invalid) {
      throw WingetException.badRequest("The search request is not valid JSON");
    }
  }

  private byte[] jsonBody(RepositoryProtocolRequest context) {
    String contentType = context.contentType();
    if (contentType == null || !isJson(contentType)) {
      throw WingetException.unsupportedMediaType("Content-Type must be application/json");
    }
    try {
      return WingetJson.readBody(context.servletRequest().getInputStream());
    } catch (IOException failure) {
      throw WingetException.badRequest("Unable to read the request body");
    }
  }

  private static boolean isJson(String contentType) {
    String normalized = contentType.toLowerCase(java.util.Locale.ROOT);
    int separator = normalized.indexOf(';');
    if (separator >= 0) normalized = normalized.substring(0, separator);
    normalized = normalized.trim();
    return normalized.equals("application/json") || normalized.endsWith("+json");
  }

  private static void requireMethods(RepositoryProtocolRequest context, HttpMethod... methods) {
    for (HttpMethod method : methods) {
      if (method == context.method()) return;
    }
    throw WingetException.methodNotAllowed("Method not allowed for this WinGet route");
  }

  private ResponseEntity<?> error(
      RepositoryProtocolRequest context, WingetPath path, WingetException error) {
    HttpHeaders headers = new HttpHeaders();
    if (error.status() == 405) {
      headers.setAllow(allowed(path.kind()));
    }
    if (context.method() == HttpMethod.HEAD) {
      return ResponseEntity.status(error.status())
          .contentType(MediaType.APPLICATION_JSON)
          .header(HttpHeaders.CACHE_CONTROL, "no-store")
          .headers(headers).build();
    }
    ObjectNode body = mapper.createObjectNode();
    body.put("code", error.code());
    body.put("message", error.getMessage());
    return ResponseEntity.status(error.status())
        .contentType(MediaType.APPLICATION_JSON)
        .header(HttpHeaders.CACHE_CONTROL, "no-store")
        .headers(headers)
        .body(body);
  }

  private static Set<HttpMethod> allowed(WingetPath.Kind kind) {
    return switch (kind) {
      case INFORMATION -> java.util.Set.of(HttpMethod.GET, HttpMethod.HEAD);
      case MANIFEST_SEARCH, PACKAGE_MANIFESTS -> java.util.Set.of(HttpMethod.POST);
      case MANIFEST -> java.util.Set.of(
          HttpMethod.GET, HttpMethod.HEAD, HttpMethod.PUT, HttpMethod.DELETE);
      case PACKAGES, UNKNOWN -> java.util.Set.of(HttpMethod.GET, HttpMethod.HEAD);
    };
  }

  private ResponseEntity<?> respond(
      RepositoryProtocolRequest context, HttpStatus status, JsonNode body, HttpHeaders headers) {
    HttpHeaders all = new HttpHeaders();
    all.putAll(headers);
    all.setContentType(MediaType.APPLICATION_JSON);
    all.set(HttpHeaders.CACHE_CONTROL, "no-store");
    if (context.method() == HttpMethod.HEAD) {
      return ResponseEntity.status(status).headers(all).build();
    }
    return ResponseEntity.status(status).headers(all).body(body);
  }

  private static String header(HttpServletRequest request, String name) {
    String value = request.getHeader(name);
    return value == null || value.isBlank() ? null : value.trim();
  }

  private static String query(HttpServletRequest request, String name) {
    String value = request.getParameter(name);
    return value == null || value.isBlank() ? null : value.trim();
  }

  private int continuationOffset(RepositoryProtocolRequest context, String token) {
    if (token == null || context.runtime().type() != RepositoryType.HOSTED) {
      return 0;
    }
    try {
      String decoded = new String(Base64.getUrlDecoder().decode(token), StandardCharsets.UTF_8);
      if (!decoded.startsWith("o:")) throw new IllegalArgumentException("bad token");
      return Math.max(0, Math.min(1_000_000, Integer.parseInt(decoded.substring(2))));
    } catch (IllegalArgumentException invalid) {
      throw WingetException.badRequest("ContinuationToken is not valid");
    }
  }

  private static String encodeOffset(int offset) {
    return Base64.getUrlEncoder().withoutPadding()
        .encodeToString(("o:" + offset).getBytes(StandardCharsets.UTF_8));
  }

  private JsonNode readArray(String json) {
    try {
      return mapper.readTree(json);
    } catch (JsonProcessingException failure) {
      throw new IllegalStateException("Built-in WinGet protocol JSON is invalid", failure);
    }
  }

  private String sourceIdentifier(String repositoryName) {
    String source = "kkrepo-" + repositoryName;
    return source.length() <= 128 ? source : source.substring(0, 128);
  }

  private String location(RepositoryProtocolRequest context, String packageIdentifier) {
    return RepositoryUrlPrefix.path(context.servletRequest()) + context.repositoryName()
        + "/" + WingetProtocol.PACKAGE_MANIFESTS_PATH + "/"
        + UriUtils.encodePathSegment(packageIdentifier, StandardCharsets.UTF_8);
  }
}
