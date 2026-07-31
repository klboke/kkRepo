package com.github.klboke.kkrepo.server.metrics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.core.RepositoryType;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.RepositoryRecord;
import com.github.klboke.kkrepo.server.security.AuthenticatedSubject;
import com.github.klboke.kkrepo.server.security.RepositorySecurityFilter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.servlet.FilterChain;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class RepositoryRequestMetricsFilterTest {

  @Test
  void recordsRepositoryRequestWithResolvedRepositoryTags() throws Exception {
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    RepositoryRequestMetricsFilter filter = new RepositoryRequestMetricsFilter(new KkRepoMetrics(registry), true, "");
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/repository/npm-example/@example/client-uri");
    MockHttpServletResponse response = new MockHttpServletResponse();
    response.setContentLength(123);
    FilterChain chain = (req, resp) -> req.setAttribute(
        RepositorySecurityFilter.REPOSITORY_RECORD_ATTRIBUTE,
        repository("npm-example", RepositoryFormat.NPM, RepositoryType.GROUP));

    filter.doFilter(request, response, chain);

    var counter = registry.find("kkrepo_repository_requests_total")
        .tags(
            "repo", "npm-example",
            "format", "npm",
            "type", "group",
            "method", "get",
            "operation", "npm_packument",
            "status", "200",
            "outcome", "success")
        .counter();
    assertNotNull(counter);
    assertEquals(1.0, counter.count());
  }

  @Test
  void recordsNpmAuditRequestsSeparatelyFromPublish() throws Exception {
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    RepositoryRequestMetricsFilter filter = new RepositoryRequestMetricsFilter(new KkRepoMetrics(registry), true, "");
    MockHttpServletRequest request = new MockHttpServletRequest(
        "POST", "/repository/npm-example/-/npm/v1/security/advisories/bulk");
    MockHttpServletResponse response = new MockHttpServletResponse();
    FilterChain chain = (req, resp) -> req.setAttribute(
        RepositorySecurityFilter.REPOSITORY_RECORD_ATTRIBUTE,
        repository("npm-example", RepositoryFormat.NPM, RepositoryType.GROUP));

    filter.doFilter(request, response, chain);

    var counter = registry.find("kkrepo_repository_requests_total")
        .tags(
            "repo", "npm-example",
            "format", "npm",
            "type", "group",
            "method", "post",
            "operation", "npm_audit",
            "status", "200",
            "outcome", "success")
        .counter();
    assertNotNull(counter);
    assertEquals(1.0, counter.count());
    assertTrue(registry.find("kkrepo_repository_requests_total")
        .tag("operation", "npm_publish")
        .meters()
        .isEmpty());
  }

  @Test
  void recordsLowCardinalitySwiftRegistryOperations() throws Exception {
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    RepositoryRequestMetricsFilter filter = new RepositoryRequestMetricsFilter(
        new KkRepoMetrics(registry), true, "");
    String[][] requests = {
        {"GET", "/repository/swift-group/acme/library", "swift_release_list"},
        {"GET", "/repository/swift-group/acme/library/1.2.3", "swift_release_metadata"},
        {"GET", "/repository/swift-group/acme/library/1.2.3/Package.swift", "swift_manifest"},
        {"GET", "/repository/swift-group/acme/library/1.2.3.zip", "swift_source_archive"},
        {"GET", "/repository/swift-group/identifiers", "swift_identifiers"},
        {"POST", "/repository/swift-group/login", "swift_login"},
        {"PUT", "/repository/swift-hosted/acme/library/1.2.3", "swift_publish"},
        {"PUT", "/repository/swift-hosted/acme/library/1.2.3+linux.zip", "swift_publish"},
    };

    for (String[] item : requests) {
      MockHttpServletRequest request = new MockHttpServletRequest(item[0], item[1]);
      MockHttpServletResponse response = new MockHttpServletResponse();
      RepositoryType type = item[1].contains("swift-hosted")
          ? RepositoryType.HOSTED
          : RepositoryType.GROUP;
      FilterChain chain = (req, resp) -> req.setAttribute(
          RepositorySecurityFilter.REPOSITORY_RECORD_ATTRIBUTE,
          repository(type == RepositoryType.HOSTED ? "swift-hosted" : "swift-group",
              RepositoryFormat.SWIFT, type));
      filter.doFilter(request, response, chain);

      var counter = registry.find("kkrepo_repository_requests_total")
          .tags("operation", item[2], "status", "200")
          .counter();
      assertNotNull(counter, item[2]);
      double expectedCount = item[1].endsWith("+linux.zip") ? 2.0 : 1.0;
      assertEquals(expectedCount, counter.count(), item[2]);
    }
  }

  @Test
  void recordsLowCardinalityAnsibleGalaxyOperations() throws Exception {
    String[][] requests = {
        {"GET", "/repository/ansible-group/api/", "ansible_discovery"},
        {"POST", "/repository/ansible-hosted/api/v3/artifacts/collections/", "ansible_publish"},
        {"GET", "/repository/ansible-hosted/api/v3/imports/collections/task-1/", "ansible_import_task"},
        {"GET", "/repository/ansible-group/api/v3/plugin/ansible/content/published/collections/artifacts/"
            + "acme-tools-1.2.3.tar.gz", "ansible_artifact_download"},
        {"PUT", "/repository/ansible-hosted/api/v3/plugin/ansible/content/published/collections/artifacts/"
            + "acme-tools-1.2.3.tar.gz", "ansible_artifact_upload"},
        {"GET", "/repository/ansible-group/api/v3/collections/acme/tools/versions/", "ansible_version_list"},
        {"GET", "/repository/ansible-group/api/v3/collections/acme/tools/versions/1.2.3/",
            "ansible_version_detail"},
        {"GET", "/repository/ansible-group/api/v3/collections/acme/tools/", "ansible_collection"},
        {"GET", "/repository/ansible-group/api/v3/unknown/", "ansible_repository"},
    };

    for (String[] item : requests) {
      SimpleMeterRegistry registry = new SimpleMeterRegistry();
      RepositoryRequestMetricsFilter filter = new RepositoryRequestMetricsFilter(
          new KkRepoMetrics(registry), true, "");
      MockHttpServletRequest request = new MockHttpServletRequest(item[0], item[1]);
      MockHttpServletResponse response = new MockHttpServletResponse();
      RepositoryType type = item[1].contains("ansible-hosted")
          ? RepositoryType.HOSTED
          : RepositoryType.GROUP;
      FilterChain chain = (req, resp) -> req.setAttribute(
          RepositorySecurityFilter.REPOSITORY_RECORD_ATTRIBUTE,
          repository(type == RepositoryType.HOSTED ? "ansible-hosted" : "ansible-group",
              RepositoryFormat.ANSIBLEGALAXY, type));

      filter.doFilter(request, response, chain);

      var counter = registry.find("kkrepo_repository_requests_total")
          .tags("operation", item[2], "status", "200")
          .counter();
      assertNotNull(counter, item[2]);
      assertEquals(1.0, counter.count(), item[2]);
    }
  }

  @Test
  void recordsSecurityFailuresWhenFilterChainStopsEarly() throws Exception {
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    RepositoryRequestMetricsFilter filter = new RepositoryRequestMetricsFilter(new KkRepoMetrics(registry), true, "");
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/repository/maven-public/com/acme/app/1.0/app.jar");
    MockHttpServletResponse response = new MockHttpServletResponse();
    FilterChain chain = (req, resp) -> {
      req.setAttribute(
          RepositorySecurityFilter.REPOSITORY_RECORD_ATTRIBUTE,
          repository("maven-public", RepositoryFormat.MAVEN2, RepositoryType.PROXY));
      ((MockHttpServletResponse) resp).sendError(401);
    };

    filter.doFilter(request, response, chain);

    var counter = registry.find("kkrepo_repository_requests_total")
        .tags(
            "repo", "maven-public",
            "format", "maven2",
            "type", "proxy",
            "method", "get",
            "operation", "maven_artifact",
            "status", "401",
            "outcome", "client_error")
        .counter();
    assertNotNull(counter);
    assertEquals(1.0, counter.count());
  }

  @Test
  void recordsUnknownRepositoryWhenNoRepositoryRecordWasResolved() throws Exception {
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    RepositoryRequestMetricsFilter filter = new RepositoryRequestMetricsFilter(new KkRepoMetrics(registry), true, "404");
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/repository/zwitch");
    MockHttpServletResponse response = new MockHttpServletResponse();
    FilterChain chain = (req, resp) -> ((MockHttpServletResponse) resp).sendError(404);

    filter.doFilter(request, response, chain);

    var counter = registry.find("kkrepo_repository_requests_total")
        .tags(
            "repo", "unknown",
            "format", "unknown",
            "type", "unknown",
            "method", "get",
            "operation", "repository",
            "status", "404",
            "outcome", "client_error")
        .counter();
    assertNotNull(counter);
    assertEquals(1.0, counter.count());
    assertFalse(registry.getMeters().stream()
        .anyMatch(meter -> "zwitch".equals(meter.getId().getTag("repo"))));
  }

  @Test
  void logsNonSuccessRepositoryRequestDetailsWhenEnabled() throws Exception {
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    RepositoryRequestMetricsFilter filter = new RepositoryRequestMetricsFilter(new KkRepoMetrics(registry), true, "");
    MockHttpServletRequest request = new MockHttpServletRequest(
        "GET", "/repository/maven-releases/com/acme/app/1.0/app.jar");
    request.addParameter("classifier", "sources");
    request.addParameter("token", "secret-token");
    request.addHeader("User-Agent", "maven-test");
    MockHttpServletResponse response = new MockHttpServletResponse();
    FilterChain chain = (req, resp) -> {
      req.setAttribute(
          RepositorySecurityFilter.REPOSITORY_RECORD_ATTRIBUTE,
          repository("maven-releases", RepositoryFormat.MAVEN2, RepositoryType.HOSTED));
      req.setAttribute(
          AuthenticatedSubject.REQUEST_ATTRIBUTE,
          new AuthenticatedSubject("default", "build-user", "local", null, null));
      ((MockHttpServletResponse) resp).sendError(404);
    };

    ListAppender<ILoggingEvent> appender = attachAppender();
    try {
      filter.doFilter(request, response, chain);
    } finally {
      detachAppender(appender);
    }

    assertEquals(1, appender.list.size());
    ILoggingEvent event = appender.list.get(0);
    assertEquals(Level.WARN, event.getLevel());
    String message = event.getFormattedMessage();
    assertTrue(message.contains("status=404"));
    assertTrue(message.contains("method=GET"));
    assertTrue(message.contains("uri=/repository/maven-releases/com/acme/app/1.0/app.jar"));
    assertTrue(message.contains("path=com/acme/app/1.0/app.jar"));
    assertTrue(message.contains("params={classifier=[sources], token=[<redacted>]}"));
    assertTrue(message.contains("repo=maven-releases"));
    assertTrue(message.contains("user=build-user"));
    assertTrue(message.contains("userSource=default"));
    assertTrue(message.contains("userAgent=maven-test"));
    assertFalse(message.contains("secret-token"));
  }

  @Test
  void doesNotParseOrLogMultipartBodiesForNonSuccessRequests() throws Exception {
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    RepositoryRequestMetricsFilter filter = new RepositoryRequestMetricsFilter(
        new KkRepoMetrics(registry), true, "");
    MockHttpServletRequest request = new MockHttpServletRequest(
        "PUT", "/repository/swift-hosted/kkrepo/example/1.0.0") {
      @Override
      public Map<String, String[]> getParameterMap() {
        throw new AssertionError("multipart body must not be parsed for request logging");
      }
    };
    request.setContentType("multipart/form-data; boundary=swift-package-registry");
    MockHttpServletResponse response = new MockHttpServletResponse();
    FilterChain chain = (req, resp) -> {
      req.setAttribute(
          RepositorySecurityFilter.REPOSITORY_RECORD_ATTRIBUTE,
          repository("swift-hosted", RepositoryFormat.SWIFT, RepositoryType.HOSTED));
      ((MockHttpServletResponse) resp).sendError(401);
    };

    ListAppender<ILoggingEvent> appender = attachAppender();
    try {
      filter.doFilter(request, response, chain);
    } finally {
      detachAppender(appender);
    }

    assertEquals(1, appender.list.size());
    String message = appender.list.getFirst().getFormattedMessage();
    assertTrue(message.contains("operation=swift_publish"));
    assertTrue(message.contains("params={_multipart=[<omitted>]}"));
  }

  @Test
  void redactsSwiftIdentifiersRepositoryUrlFromFailureLogs() throws Exception {
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    RepositoryRequestMetricsFilter filter = new RepositoryRequestMetricsFilter(
        new KkRepoMetrics(registry), true, "");
    String repositoryUrl =
        "https://github.com/private-owner/private-package?access_token=super-secret";
    MockHttpServletRequest request = new MockHttpServletRequest(
        "GET", "/repository/swift-group/identifiers");
    request.setQueryString("url=" + java.net.URLEncoder.encode(
        repositoryUrl, java.nio.charset.StandardCharsets.UTF_8));
    request.addParameter("url", repositoryUrl);
    MockHttpServletResponse response = new MockHttpServletResponse();
    FilterChain chain = (req, resp) -> {
      req.setAttribute(
          RepositorySecurityFilter.REPOSITORY_RECORD_ATTRIBUTE,
          repository("swift-group", RepositoryFormat.SWIFT, RepositoryType.GROUP));
      ((MockHttpServletResponse) resp).sendError(404);
    };

    ListAppender<ILoggingEvent> appender = attachAppender();
    try {
      filter.doFilter(request, response, chain);
    } finally {
      detachAppender(appender);
    }

    assertEquals(1, appender.list.size());
    String message = appender.list.getFirst().getFormattedMessage();
    assertTrue(message.contains("operation=swift_identifiers"));
    assertTrue(message.contains("uri=/repository/swift-group/identifiers"));
    assertTrue(message.contains("params={url=[<redacted>]}"));
    assertFalse(message.contains("private-owner"));
    assertFalse(message.contains("private-package"));
    assertFalse(message.contains("super-secret"));
  }

  @Test
  void redactsSwiftIdentifiersRepositoryUrlFromEncodedFailureLogs() throws Exception {
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    RepositoryRequestMetricsFilter filter = new RepositoryRequestMetricsFilter(
        new KkRepoMetrics(registry), true, "");
    String repositoryUrl = "https://token@github.com/private-owner/private-package";
    MockHttpServletRequest request = new MockHttpServletRequest(
        "GET", "/repository/swift-group/%69dentifiers");
    request.setQueryString("url=" + java.net.URLEncoder.encode(
        repositoryUrl, java.nio.charset.StandardCharsets.UTF_8));
    request.addParameter("url", repositoryUrl);
    MockHttpServletResponse response = new MockHttpServletResponse();
    FilterChain chain = (req, resp) -> {
      req.setAttribute(
          RepositorySecurityFilter.REPOSITORY_RECORD_ATTRIBUTE,
          repository("swift-group", RepositoryFormat.SWIFT, RepositoryType.GROUP));
      ((MockHttpServletResponse) resp).sendError(404);
    };

    ListAppender<ILoggingEvent> appender = attachAppender();
    try {
      filter.doFilter(request, response, chain);
    } finally {
      detachAppender(appender);
    }

    assertEquals(1, appender.list.size());
    String message = appender.list.getFirst().getFormattedMessage();
    assertTrue(message.contains("operation=swift_identifiers"));
    assertTrue(message.contains("params={url=[<redacted>]}"));
    assertFalse(message.contains("private-owner"));
    assertFalse(message.contains("private-package"));
    assertFalse(message.contains("token@"));
  }

  @Test
  void stripsTerraformUrlTokenBeforeOperationMetricsAndFailureLogs() throws Exception {
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    RepositoryRequestMetricsFilter filter = new RepositoryRequestMetricsFilter(new KkRepoMetrics(registry), true, "");
    String token = "GenericToken.super-secret-value";
    String canonical = "v1/providers/kkrepo/fixture/versions";
    MockHttpServletRequest request = new MockHttpServletRequest(
        "GET", "/repository/terraform-group/v1/providers/" + token + "/kkrepo/fixture/versions");
    MockHttpServletResponse response = new MockHttpServletResponse();
    FilterChain chain = (req, resp) -> {
      req.setAttribute(
          RepositorySecurityFilter.REPOSITORY_RECORD_ATTRIBUTE,
          repository("terraform-group", RepositoryFormat.TERRAFORM, RepositoryType.GROUP));
      req.setAttribute(RepositorySecurityFilter.NORMALIZED_REPOSITORY_PATH_ATTRIBUTE, canonical);
      ((MockHttpServletResponse) resp).sendError(404);
    };

    ListAppender<ILoggingEvent> appender = attachAppender();
    try {
      filter.doFilter(request, response, chain);
    } finally {
      detachAppender(appender);
    }

    var counter = registry.find("kkrepo_repository_requests_total")
        .tags("operation", "terraform_provider_versions", "status", "404")
        .counter();
    assertNotNull(counter);
    String message = appender.list.get(0).getFormattedMessage();
    assertTrue(message.contains("uri=/repository/terraform-group/" + canonical));
    assertTrue(message.contains("path=" + canonical));
    assertFalse(message.contains(token));
  }

  @Test
  void redactsUncanonicalizedTerraformPathsFromFailureLogs() throws Exception {
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    RepositoryRequestMetricsFilter filter = new RepositoryRequestMetricsFilter(
        new KkRepoMetrics(registry), true, "");
    String token = "GenericToken.super-secret-value";
    MockHttpServletRequest request = new MockHttpServletRequest(
        "GET", "/repository/terraform-group/v1/modules/" + token + "/bad");
    MockHttpServletResponse response = new MockHttpServletResponse();
    FilterChain chain = (req, resp) -> {
      req.setAttribute(
          RepositorySecurityFilter.REPOSITORY_RECORD_ATTRIBUTE,
          repository("terraform-group", RepositoryFormat.TERRAFORM, RepositoryType.GROUP));
      ((MockHttpServletResponse) resp).sendError(404);
    };

    ListAppender<ILoggingEvent> appender = attachAppender();
    try {
      filter.doFilter(request, response, chain);
    } finally {
      detachAppender(appender);
    }

    String message = appender.list.get(0).getFormattedMessage();
    assertTrue(message.contains("path=<redacted-terraform-path>"));
    assertFalse(message.contains(token));
  }

  @Test
  void stripsTerraformUrlTokenFromMissingRepositoryFailureLogs() throws Exception {
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    RepositoryRequestMetricsFilter filter = new RepositoryRequestMetricsFilter(
        new KkRepoMetrics(registry), true, "");
    String token = "GenericToken.missing-repository-secret";
    String canonical = "v1/providers/kkrepo/fixture/versions";
    MockHttpServletRequest request = new MockHttpServletRequest(
        "GET", "/repository/deleted/v1/providers/" + token + "/kkrepo/fixture/versions");
    MockHttpServletResponse response = new MockHttpServletResponse();
    FilterChain chain = (req, resp) -> ((MockHttpServletResponse) resp).sendError(404);

    ListAppender<ILoggingEvent> appender = attachAppender();
    try {
      filter.doFilter(request, response, chain);
    } finally {
      detachAppender(appender);
    }

    String message = appender.list.get(0).getFormattedMessage();
    assertTrue(message.contains("uri=/repository/deleted/" + canonical));
    assertTrue(message.contains("path=" + canonical));
    assertFalse(message.contains(token));
  }

  @Test
  void stripsTerraformUrlTokenFromWrongFormatRepositoryFailureLogs() throws Exception {
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    RepositoryRequestMetricsFilter filter = new RepositoryRequestMetricsFilter(
        new KkRepoMetrics(registry), true, "");
    String token = "GenericToken.raw-repository-secret";
    String canonical = "v1/modules/acme/network/aws/versions";
    MockHttpServletRequest request = new MockHttpServletRequest(
        "GET", "/repository/raw/v1/modules/" + token + "/acme/network/aws/versions");
    MockHttpServletResponse response = new MockHttpServletResponse();
    FilterChain chain = (req, resp) -> {
      req.setAttribute(
          RepositorySecurityFilter.REPOSITORY_RECORD_ATTRIBUTE,
          repository("raw", RepositoryFormat.RAW, RepositoryType.HOSTED));
      ((MockHttpServletResponse) resp).sendError(404);
    };

    ListAppender<ILoggingEvent> appender = attachAppender();
    try {
      filter.doFilter(request, response, chain);
    } finally {
      detachAppender(appender);
    }

    String message = appender.list.get(0).getFormattedMessage();
    assertTrue(message.contains("uri=/repository/raw/" + canonical));
    assertTrue(message.contains("path=" + canonical));
    assertFalse(message.contains(token));
  }

  @Test
  void doesNotLogNonSuccessRepositoryRequestDetailsWhenDisabled() throws Exception {
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    RepositoryRequestMetricsFilter filter = new RepositoryRequestMetricsFilter(new KkRepoMetrics(registry), false, "404");
    MockHttpServletRequest request = new MockHttpServletRequest(
        "GET", "/repository/maven-releases/com/acme/app/1.0/app.jar");
    MockHttpServletResponse response = new MockHttpServletResponse();
    FilterChain chain = (req, resp) -> ((MockHttpServletResponse) resp).sendError(404);

    ListAppender<ILoggingEvent> appender = attachAppender();
    try {
      filter.doFilter(request, response, chain);
    } finally {
      detachAppender(appender);
    }

    assertTrue(appender.list.isEmpty());
  }

  @Test
  void doesNotLogExcludedNonSuccessRepositoryStatusWhenEnabled() throws Exception {
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    RepositoryRequestMetricsFilter filter = new RepositoryRequestMetricsFilter(new KkRepoMetrics(registry), true, "401, 404");
    MockHttpServletRequest request = new MockHttpServletRequest(
        "GET", "/repository/maven-releases/com/acme/app/1.0/app.jar");
    MockHttpServletResponse response = new MockHttpServletResponse();
    FilterChain chain = (req, resp) -> ((MockHttpServletResponse) resp).sendError(404);

    ListAppender<ILoggingEvent> appender = attachAppender();
    try {
      filter.doFilter(request, response, chain);
    } finally {
      detachAppender(appender);
    }

    assertTrue(appender.list.isEmpty());
  }

  @Test
  void doesNotLogDefaultExcludedNonSuccessRepositoryStatusWhenEnabled() throws Exception {
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    RepositoryRequestMetricsFilter filter = new RepositoryRequestMetricsFilter(new KkRepoMetrics(registry), true, "477,488");
    MockHttpServletRequest request = new MockHttpServletRequest(
        "GET", "/repository/maven-releases/com/acme/app/1.0/app.jar");
    MockHttpServletResponse response = new MockHttpServletResponse();
    FilterChain chain = (req, resp) -> ((MockHttpServletResponse) resp).setStatus(477);

    ListAppender<ILoggingEvent> appender = attachAppender();
    try {
      filter.doFilter(request, response, chain);
    } finally {
      detachAppender(appender);
    }

    assertTrue(appender.list.isEmpty());
  }

  private static ListAppender<ILoggingEvent> attachAppender() {
    Logger logger = (Logger) LoggerFactory.getLogger(RepositoryRequestMetricsFilter.class);
    ListAppender<ILoggingEvent> appender = new ListAppender<>();
    appender.start();
    logger.addAppender(appender);
    return appender;
  }

  private static void detachAppender(ListAppender<ILoggingEvent> appender) {
    Logger logger = (Logger) LoggerFactory.getLogger(RepositoryRequestMetricsFilter.class);
    logger.detachAppender(appender);
  }

  private static RepositoryRecord repository(String name, RepositoryFormat format, RepositoryType type) {
    return new RepositoryRecord(
        1L,
        name,
        format,
        type,
        format.name().toLowerCase() + "-" + type.name().toLowerCase(),
        true,
        1L,
        null,
        null,
        null,
        null,
        null,
        true,
        Map.of());
  }
}
