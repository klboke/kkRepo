package com.github.klboke.kkrepo.server.metrics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.springframework.core.annotation.Order;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.session.web.http.SessionRepositoryFilter;
import org.springframework.web.servlet.HandlerMapping;

class TetrisApiMetricsFilterTest {

  @Test
  void exportsTetrisHistogramWithExpectedMetricAndLabels() throws Exception {
    PrometheusMeterRegistry registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
    TetrisApiMetricsFilter filter = new TetrisApiMetricsFilter(registry, true);
    MockHttpServletRequest request = request("GET", "/internal/repositories/maven-public");
    MockHttpServletResponse response = new MockHttpServletResponse();

    filter.doFilter(request, response, matchingRoute("/internal/repositories/{name}"));

    var timer = registry.find(TetrisApiMetricsFilter.METRIC_NAME)
        .tags(
            "uriPattern", "/internal/repositories/{name}",
            "method", "GET",
            "statusCode", "2xx",
            "upServiceName", "",
            "bizCode", "")
        .timer();
    assertNotNull(timer);
    assertEquals(1L, timer.count());

    String scrape = registry.scrape();
    assertTrue(scrape.contains("hunter_api_response_duration_seconds_count"));
    assertTrue(scrape.contains("hunter_api_response_duration_seconds_sum"));
    assertTrue(scrape.contains("hunter_api_response_duration_seconds_bucket"));
    assertTrue(scrape.contains("uriPattern=\"/internal/repositories/{name}\""));
    assertTrue(scrape.contains("method=\"GET\""));
    assertTrue(scrape.contains("statusCode=\"2xx\""));
    assertTrue(scrape.contains("upServiceName=\"\""));
    assertTrue(scrape.contains("bizCode=\"\""));
    assertTrue(scrape.contains("le=\"0.01\""));
    assertTrue(scrape.contains("le=\"0.05\""));
    assertTrue(scrape.contains("le=\"0.1\""));
    assertTrue(scrape.contains("le=\"0.25\""));
    assertTrue(scrape.contains("le=\"0.5\""));
    assertTrue(scrape.contains("le=\"1.0\""));
    assertTrue(scrape.contains("le=\"2.5\""));
    assertTrue(scrape.contains("le=\"5.0\""));
  }

  @Test
  void groupsHttpStatusesByClass() throws Exception {
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    TetrisApiMetricsFilter filter = new TetrisApiMetricsFilter(registry, true);

    record(filter, "POST", 204, "/internal/repositories");
    record(filter, "GET", 404, "/internal/repositories/{name}");
    record(filter, "DELETE", 503, "/internal/repositories/{name}");

    assertEquals(1L, timer(registry, "2xx").count());
    assertEquals(1L, timer(registry, "4xx").count());
    assertEquals(1L, timer(registry, "5xx").count());
  }

  @Test
  void skipsActuatorAndFarosRequests() throws Exception {
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    TetrisApiMetricsFilter filter = new TetrisApiMetricsFilter(registry, true);

    filter.doFilter(
        request("GET", "/actuator/prometheus"),
        new MockHttpServletResponse(),
        matchingRoute("/actuator/prometheus"));
    filter.doFilter(
        request("GET", "/faros"),
        new MockHttpServletResponse(),
        matchingRoute("/faros"));

    assertNull(registry.find(TetrisApiMetricsFilter.METRIC_NAME).timer());
  }

  @Test
  void skipsRequestsWithoutStableMatchingPattern() throws Exception {
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    TetrisApiMetricsFilter filter = new TetrisApiMetricsFilter(registry, true);

    filter.doFilter(
        request("GET", "/repository/raw-hosted/arbitrary/path"),
        new MockHttpServletResponse(),
        (req, resp) -> { });

    assertNull(registry.find(TetrisApiMetricsFilter.METRIC_NAME).timer());
  }

  @Test
  void canDisableTetrisCompatibilityMetric() throws Exception {
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    TetrisApiMetricsFilter filter = new TetrisApiMetricsFilter(registry, false);

    filter.doFilter(
        request("GET", "/internal/repositories"),
        new MockHttpServletResponse(),
        matchingRoute("/internal/repositories"));

    assertNull(registry.find(TetrisApiMetricsFilter.METRIC_NAME).timer());
  }

  @Test
  void runsBeforeApplicationRateLimitAndSecurityFilters() {
    Order order = TetrisApiMetricsFilter.class.getAnnotation(Order.class);

    assertNotNull(order);
    assertEquals(SessionRepositoryFilter.DEFAULT_ORDER + 4, order.value());
  }

  private static void record(
      TetrisApiMetricsFilter filter, String method, int status, String pattern) throws Exception {
    MockHttpServletResponse response = new MockHttpServletResponse();
    response.setStatus(status);
    filter.doFilter(request(method, pattern), response, matchingRoute(pattern));
  }

  private static io.micrometer.core.instrument.Timer timer(
      SimpleMeterRegistry registry, String statusClass) {
    var timer = registry.find(TetrisApiMetricsFilter.METRIC_NAME)
        .tag("statusCode", statusClass)
        .timer();
    assertNotNull(timer);
    return timer;
  }

  private static MockHttpServletRequest request(String method, String path) {
    return new MockHttpServletRequest(method, path);
  }

  private static FilterChain matchingRoute(String pattern) {
    return (req, resp) -> req.setAttribute(
        HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, pattern);
  }
}
