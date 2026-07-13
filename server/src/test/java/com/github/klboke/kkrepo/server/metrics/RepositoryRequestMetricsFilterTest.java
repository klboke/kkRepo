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
