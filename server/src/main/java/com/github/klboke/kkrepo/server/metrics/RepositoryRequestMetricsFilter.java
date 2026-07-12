package com.github.klboke.kkrepo.server.metrics;

import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.persistence.mysql.model.RepositoryRecord;
import com.github.klboke.kkrepo.protocol.composer.ComposerPath;
import com.github.klboke.kkrepo.protocol.composer.ComposerPathParser;
import com.github.klboke.kkrepo.server.docker.DockerConnectorConfiguration;
import com.github.klboke.kkrepo.server.security.AuthenticatedSubject;
import com.github.klboke.kkrepo.server.security.RepositorySecurityFilter;
import io.micrometer.core.instrument.Timer;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.session.web.http.SessionRepositoryFilter;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.server.ResponseStatusException;

@Component
@Order(RepositoryRequestMetricsFilter.FILTER_ORDER)
public class RepositoryRequestMetricsFilter extends OncePerRequestFilter {
  static final int FILTER_ORDER = SessionRepositoryFilter.DEFAULT_ORDER + 18;
  private static final Logger log = LoggerFactory.getLogger(RepositoryRequestMetricsFilter.class);
  private static final int MAX_LOGGED_PARAM_VALUE_LENGTH = 512;
  private static final ComposerPathParser COMPOSER_PATHS = new ComposerPathParser();

  private final KkRepoMetrics metrics;
  private final boolean logNonSuccessRequests;
  private final Set<Integer> nonSuccessRequestLogExcludedStatuses;

  public RepositoryRequestMetricsFilter(
      KkRepoMetrics metrics,
      @Value("${kkrepo.repository.log-non-success-requests:true}") boolean logNonSuccessRequests,
      @Value("${kkrepo.repository.log-non-success-request-excluded-statuses:477,488}") String excludedStatuses) {
    this.metrics = metrics;
    this.logNonSuccessRequests = logNonSuccessRequests;
    this.nonSuccessRequestLogExcludedStatuses = parseExcludedStatuses(excludedStatuses);
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request,
      HttpServletResponse response,
      FilterChain filterChain) throws ServletException, IOException {
    Target target = target(request);
    if (target == null) {
      filterChain.doFilter(request, response);
      return;
    }

    Timer.Sample sample = metrics.startTimer();
    Throwable failure = null;
    try {
      filterChain.doFilter(request, response);
    } catch (IOException | ServletException | RuntimeException | Error e) {
      failure = e;
      throw e;
    } finally {
      RepositoryRecord record = repositoryRecord(request);
      String repository = record == null ? "unknown" : record.name();
      String format = record == null ? "unknown" : KkRepoMetrics.format(record.format());
      String type = record == null ? "unknown" : KkRepoMetrics.type(record.type());
      String operation = operation(target, record == null ? null : record.format(), request.getMethod());
      int status = failure == null ? response.getStatus() : statusFor(failure, response.getStatus());
      metrics.recordRepositoryRequest(
          repository,
          format,
          type,
          request.getMethod(),
          operation,
          status,
          request.getContentLengthLong(),
          contentLength(response),
          failure,
          sample);
      logNonSuccessRequest(request, target, repository, format, type, operation, status, failure);
    }
  }

  private void logNonSuccessRequest(
      HttpServletRequest request,
      Target target,
      String repository,
      String format,
      String type,
      String operation,
      int status,
      Throwable failure) {
    if (!logNonSuccessRequests
        || (status >= 200 && status < 300)
        || nonSuccessRequestLogExcludedStatuses.contains(status)) {
      return;
    }
    log.warn(
        "Repository request completed with non-success status: status={} method={} uri={} path={} params={} "
            + "repo={} format={} type={} operation={} route={} user={} userSource={} remoteAddr={} "
            + "userAgent={} failure={}",
        status,
        request.getMethod(),
        stripContextPath(request),
        target.path(),
        requestParameters(request),
        repository,
        format,
        type,
        operation,
        target.route(),
        authenticatedUserId(request),
        authenticatedSource(request),
        request.getRemoteAddr(),
        safeHeader(request, "User-Agent"),
        failure == null ? "none" : failure.getClass().getName());
  }

  private static String authenticatedUserId(HttpServletRequest request) {
    Object value = request.getAttribute(AuthenticatedSubject.REQUEST_ATTRIBUTE);
    if (value instanceof AuthenticatedSubject subject
        && subject.userId() != null
        && !subject.userId().isBlank()) {
      return subject.userId();
    }
    return "unknown";
  }

  private static String authenticatedSource(HttpServletRequest request) {
    Object value = request.getAttribute(AuthenticatedSubject.REQUEST_ATTRIBUTE);
    if (value instanceof AuthenticatedSubject subject
        && subject.source() != null
        && !subject.source().isBlank()) {
      return subject.source();
    }
    return "unknown";
  }

  private static Set<Integer> parseExcludedStatuses(String configuredStatuses) {
    if (configuredStatuses == null || configuredStatuses.isBlank()) {
      return Set.of();
    }
    Set<Integer> statuses = new HashSet<>();
    for (String rawStatus : configuredStatuses.split(",")) {
      String status = rawStatus.trim();
      if (status.isEmpty()) {
        continue;
      }
      try {
        int parsed = Integer.parseInt(status);
        if (parsed < 100 || parsed > 599) {
          log.warn("Ignoring invalid non-success repository request log status code: {}", status);
          continue;
        }
        statuses.add(parsed);
      } catch (NumberFormatException e) {
        log.warn("Ignoring invalid non-success repository request log status code: {}", status);
      }
    }
    return Set.copyOf(statuses);
  }

  private static RepositoryRecord repositoryRecord(HttpServletRequest request) {
    Object value = request.getAttribute(RepositorySecurityFilter.REPOSITORY_RECORD_ATTRIBUTE);
    return value instanceof RepositoryRecord record ? record : null;
  }

  private static Target target(HttpServletRequest request) {
    String method = request.getMethod() == null ? "" : request.getMethod().toUpperCase(Locale.ROOT);
    String uri = stripContextPath(request);
    if (uri.startsWith("/repository/")) {
      String remaining = uri.substring("/repository/".length());
      if (remaining.isBlank()) return null;
      int slash = remaining.indexOf('/');
      String repository = slash < 0 ? remaining : remaining.substring(0, slash);
      String path = slash < 0 ? "" : remaining.substring(slash + 1);
      return repository.isBlank() ? null : new Target(decode(repository), path, "repository");
    }
    if (uri.startsWith("/v2/")) {
      Object connectorRepository =
          request.getAttribute(DockerConnectorConfiguration.CONNECTOR_REPOSITORY_ATTRIBUTE);
      if (connectorRepository instanceof String repository && !repository.isBlank()) {
        return new Target(repository, uri.substring("/v2/".length()), "repository");
      }
      String remaining = uri.substring("/v2/".length());
      if (remaining.isBlank()) return null;
      int slash = remaining.indexOf('/');
      if (slash < 0) return null;
      String repository = remaining.substring(0, slash);
      String path = remaining.substring(slash + 1);
      return repository.isBlank() ? null : new Target(decode(repository), path, "repository");
    }
    if (uri.startsWith("/service/rest/repository/browse/")) {
      String remaining = uri.substring("/service/rest/repository/browse/".length());
      if (remaining.isBlank()) return null;
      int slash = remaining.indexOf('/');
      String repository = slash < 0 ? remaining : remaining.substring(0, slash);
      return repository.isBlank() ? null : new Target(decode(repository), "", "browse");
    }
    if ("POST".equals(method) && uri.equals("/service/rest/v1/components")) {
      String repository = request.getParameter("repository");
      return repository == null || repository.isBlank()
          ? null
          : new Target(repository.trim(), "", "component_upload");
    }
    if (uri.startsWith("/service/rest/internal/ui/upload/")) {
      String repository = uri.substring("/service/rest/internal/ui/upload/".length());
      int slash = repository.indexOf('/');
      if (slash >= 0) {
        repository = repository.substring(0, slash);
      }
      return repository.isBlank() ? null : new Target(decode(repository), "", "component_upload");
    }
    return null;
  }

  private static String operation(Target target, RepositoryFormat format, String method) {
    if (!"repository".equals(target.route())) {
      return target.route();
    }
    String normalizedMethod = method == null ? "" : method.toUpperCase(Locale.ROOT);
    String path = target.path() == null ? "" : target.path();
    if (format == null) {
      return "repository";
    }
    return switch (format) {
      case MAVEN2 -> mavenOperation(path, normalizedMethod);
      case NPM -> npmOperation(path, normalizedMethod);
      case PYPI -> pypiOperation(path, normalizedMethod);
      case CARGO -> cargoOperation(path, normalizedMethod);
      case PUB -> pubOperation(path, normalizedMethod);
      case COMPOSER -> composerOperation(path, normalizedMethod);
      case HELM -> helmOperation(path, normalizedMethod);
      case GO -> goOperation(path);
      case NUGET -> nugetOperation(path, normalizedMethod);
      case RUBYGEMS -> rubygemsOperation(path, normalizedMethod);
      case YUM -> yumOperation(path, normalizedMethod);
      case DOCKER -> dockerOperation(path, normalizedMethod);
      case RAW -> rawOperation(normalizedMethod);
    };
  }

  private static String composerOperation(String path, String method) {
    if (COMPOSER_PATHS.parse(path).kind() == ComposerPath.Kind.DIST) return "composer_dist";
    if (path.equals("packages.json") || path.startsWith("p2/")
        || path.startsWith("providers/") || path.startsWith("packages/list.json")) {
      return "composer_metadata";
    }
    return "composer_repository";
  }

  private static String mavenOperation(String path, String method) {
    if ("PUT".equals(method) || "POST".equals(method)) return "maven_upload";
    if ("DELETE".equals(method)) return "maven_delete";
    if (path.endsWith("maven-metadata.xml")
        || path.endsWith("maven-metadata.xml.sha1")
        || path.endsWith("maven-metadata.xml.md5")
        || path.endsWith("maven-metadata.xml.sha256")
        || path.endsWith("maven-metadata.xml.sha512")) {
      return "maven_metadata";
    }
    return "maven_artifact";
  }

  private static String pubOperation(String path, String method) {
    if ("GET".equals(method) && path.equals("api/packages/versions/new")) return "pub_publish_init";
    if ("POST".equals(method) && path.startsWith("api/packages/versions/upload/")) return "pub_publish_upload";
    if ("GET".equals(method) && path.startsWith("api/packages/versions/finalize/")) return "pub_publish_finalize";
    if (path.startsWith("api/packages/") && path.contains("/versions/")) return "pub_version_metadata";
    if (path.startsWith("api/packages/")) return "pub_package_metadata";
    if (path.startsWith("packages/")) return "pub_archive";
    return "pub_request";
  }

  private static String npmOperation(String path, String method) {
    if ("POST".equals(method) && path.startsWith("-/npm/v1/security/")) return "npm_audit";
    if ("PUT".equals(method) || "POST".equals(method)) return "npm_publish";
    if ("DELETE".equals(method)) return "npm_delete";
    if (path.startsWith("-/v1/search") || path.startsWith("-/all")) return "npm_search";
    if (path.endsWith(".tgz") || path.contains("/-/")) return "npm_tarball";
    if (path.endsWith("/-/package") || path.contains("/-/package/")) return "npm_login";
    return "npm_packument";
  }

  private static String pypiOperation(String path, String method) {
    if ("POST".equals(method)) return "pypi_upload";
    if (path.equals("simple") || path.equals("simple/") || path.startsWith("simple/")) return "pypi_simple";
    if (path.startsWith("packages/")) return "pypi_package";
    return "pypi_repository";
  }

  private static String cargoOperation(String path, String method) {
    if ("PUT".equals(method) && "api/v1/crates/new".equals(path)) return "cargo_publish";
    if ("DELETE".equals(method) && path.endsWith("/yank")) return "cargo_yank";
    if ("PUT".equals(method) && path.endsWith("/unyank")) return "cargo_unyank";
    if (path.equals("config.json")) return "cargo_config";
    if (path.startsWith("api/v1/crates/") && path.endsWith("/download")) return "cargo_crate";
    if (path.startsWith("crates/") && (path.endsWith("/download") || path.endsWith(".crate"))) return "cargo_crate";
    if (path.startsWith("api/v1/crates")) return "cargo_api";
    return "cargo_index";
  }

  private static String helmOperation(String path, String method) {
    if ("PUT".equals(method) || "POST".equals(method)) return "helm_upload";
    if ("DELETE".equals(method)) return "helm_delete";
    if (path.equals("index.yaml")) return "helm_index";
    return "helm_chart";
  }

  private static String goOperation(String path) {
    if (path.endsWith("/@v/list")) return "go_list";
    if (path.endsWith(".mod")) return "go_mod";
    if (path.endsWith(".zip")) return "go_zip";
    if (path.endsWith(".info")) return "go_info";
    return "go_repository";
  }

  private static String nugetOperation(String path, String method) {
    if ("PUT".equals(method) || "POST".equals(method)) return "nuget_upload";
    if ("DELETE".equals(method)) return "nuget_delete";
    if (path.equals("index.json")) return "nuget_service_index";
    if (path.startsWith("v3-flatcontainer/")) return "nuget_flat_container";
    return "nuget_repository";
  }

  private static String rubygemsOperation(String path, String method) {
    if ("PUT".equals(method) || "POST".equals(method)) return "rubygems_upload";
    if ("DELETE".equals(method)) return "rubygems_delete";
    if (path.startsWith("gems/")) return "rubygems_gem";
    if (path.endsWith(".gz") || path.startsWith("quick/")) return "rubygems_index";
    return "rubygems_repository";
  }

  private static String yumOperation(String path, String method) {
    if ("PUT".equals(method) || "POST".equals(method)) return "yum_upload";
    if ("DELETE".equals(method)) return "yum_delete";
    if (path.startsWith("repodata/")) return "yum_metadata";
    if (path.endsWith(".rpm")) return "yum_package";
    return "yum_repository";
  }

  private static String rawOperation(String method) {
    if ("PUT".equals(method) || "POST".equals(method)) return "raw_upload";
    if ("DELETE".equals(method)) return "raw_delete";
    return "raw_download";
  }

  private static String dockerOperation(String path, String method) {
    if ("POST".equals(method) || "PATCH".equals(method) || "PUT".equals(method)) {
      if (path.contains("/blobs/uploads/") || path.endsWith("/blobs/uploads")) return "docker_blob_upload";
      if (path.contains("/manifests/")) return "docker_manifest_upload";
    }
    if ("DELETE".equals(method)) {
      if (path.contains("/blobs/uploads/")) return "docker_upload_cancel";
      if (path.contains("/manifests/")) return "docker_manifest_delete";
      return "docker_delete";
    }
    if (path.contains("/manifests/")) return "docker_manifest";
    if (path.contains("/blobs/")) return "docker_blob";
    if (path.endsWith("/tags/list")) return "docker_tags";
    if (path.contains("/referrers/")) return "docker_referrers";
    return "docker_repository";
  }

  private static int statusFor(Throwable failure, int fallback) {
    if (failure instanceof ResponseStatusException rse) {
      return rse.getStatusCode().value();
    }
    return fallback > 0 ? fallback : 500;
  }

  private static long contentLength(HttpServletResponse response) {
    String header = response.getHeader("Content-Length");
    if (header == null || header.isBlank()) return 0;
    try {
      return Long.parseLong(header);
    } catch (NumberFormatException ignored) {
      return 0;
    }
  }

  private static String requestParameters(HttpServletRequest request) {
    Map<String, String[]> parameters;
    try {
      parameters = request.getParameterMap();
    } catch (RuntimeException e) {
      return "{_error=[" + e.getClass().getSimpleName() + "]}";
    }
    if (parameters.isEmpty()) {
      return "{}";
    }
    return parameters.entrySet().stream()
        .sorted(Map.Entry.comparingByKey())
        .map(entry -> entry.getKey() + "=" + parameterValues(entry.getKey(), entry.getValue()))
        .collect(Collectors.joining(", ", "{", "}"));
  }

  private static String parameterValues(String name, String[] values) {
    if (values == null || values.length == 0) {
      return "[]";
    }
    return Arrays.stream(values)
        .map(value -> parameterValue(name, value))
        .collect(Collectors.joining(", ", "[", "]"));
  }

  private static String parameterValue(String name, String value) {
    if (isSensitiveParameter(name)) {
      return "<redacted>";
    }
    if (value == null) {
      return "null";
    }
    if (value.length() <= MAX_LOGGED_PARAM_VALUE_LENGTH) {
      return value;
    }
    return value.substring(0, MAX_LOGGED_PARAM_VALUE_LENGTH) + "...";
  }

  private static boolean isSensitiveParameter(String name) {
    String lower = name == null ? "" : name.toLowerCase(Locale.ROOT);
    return lower.contains("token")
        || lower.contains("password")
        || lower.contains("secret")
        || lower.contains("credential")
        || lower.contains("authorization")
        || lower.contains("access_key")
        || lower.contains("accesskey")
        || lower.contains("secret_key")
        || lower.contains("secretkey")
        || lower.contains("signature");
  }

  private static String safeHeader(HttpServletRequest request, String name) {
    String value = request.getHeader(name);
    return value == null || value.isBlank() ? "unknown" : value;
  }

  private static String stripContextPath(HttpServletRequest request) {
    String uri = request.getRequestURI();
    String contextPath = request.getContextPath();
    if (contextPath != null && !contextPath.isBlank() && uri.startsWith(contextPath)) {
      return uri.substring(contextPath.length());
    }
    return uri;
  }

  private static String decode(String value) {
    return URLDecoder.decode(value, StandardCharsets.UTF_8);
  }

  private record Target(String repository, String path, String route) {
  }
}
