package com.github.klboke.kkrepo.server.metrics;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.session.web.http.SessionRepositoryFilter;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.server.ResponseStatusException;

/** Exposes the low-cardinality HTTP histogram expected by the Tetris APM dashboard. */
@Component
@Order(TetrisApiMetricsFilter.FILTER_ORDER)
public class TetrisApiMetricsFilter extends OncePerRequestFilter {
  static final int FILTER_ORDER = SessionRepositoryFilter.DEFAULT_ORDER + 4;
  static final String METRIC_NAME = "hunter.api.response.duration";
  private static final Duration[] LATENCY_SLOS = {
      Duration.ofMillis(10),
      Duration.ofMillis(50),
      Duration.ofMillis(100),
      Duration.ofMillis(250),
      Duration.ofMillis(500),
      Duration.ofSeconds(1),
      Duration.ofMillis(2500),
      Duration.ofSeconds(5)
  };

  private final MeterRegistry registry;
  private final boolean enabled;

  public TetrisApiMetricsFilter(
      MeterRegistry registry,
      @Value("${kkrepo.metrics.tetris-api.enabled:true}") boolean enabled) {
    this.registry = registry;
    this.enabled = enabled;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request,
      HttpServletResponse response,
      FilterChain filterChain) throws ServletException, IOException {
    Timer.Sample sample = Timer.start(registry);
    Throwable failure = null;
    try {
      filterChain.doFilter(request, response);
    } catch (IOException | ServletException | RuntimeException | Error e) {
      failure = e;
      throw e;
    } finally {
      String uriPattern = matchingPattern(request);
      if (uriPattern != null) {
        sample.stop(Timer.builder(METRIC_NAME)
            .description("HTTP request duration compatible with the Tetris APM metric contract")
            .tags(
                "uriPattern", uriPattern,
                "method", requestMethod(request),
                "statusCode", statusClass(responseStatus(response, failure)),
                "upServiceName", "",
                "bizCode", "")
            .serviceLevelObjectives(LATENCY_SLOS)
            .register(registry));
      }
    }
  }

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    if (!enabled) {
      return true;
    }
    String path = pathWithinApplication(request);
    return path.equals("/actuator")
        || path.startsWith("/actuator/")
        || path.equals("/faros")
        || path.startsWith("/faros/");
  }

  private static String matchingPattern(HttpServletRequest request) {
    Object value = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
    if (value == null) {
      return null;
    }
    String pattern = value.toString().trim();
    return pattern.startsWith("/") ? pattern : null;
  }

  private static int responseStatus(HttpServletResponse response, Throwable failure) {
    if (failure instanceof ResponseStatusException statusException) {
      return statusException.getStatusCode().value();
    }
    int status = response.getStatus();
    return failure != null && status < 400 ? 500 : status;
  }

  private static String statusClass(int status) {
    if (status >= 100 && status <= 599) {
      return (status / 100) + "xx";
    }
    return "unknown";
  }

  private static String requestMethod(HttpServletRequest request) {
    String method = request.getMethod();
    return method == null || method.isBlank() ? "UNKNOWN" : method;
  }

  private static String pathWithinApplication(HttpServletRequest request) {
    String uri = request.getRequestURI();
    String contextPath = request.getContextPath();
    if (contextPath != null && !contextPath.isBlank() && uri.startsWith(contextPath)) {
      return uri.substring(contextPath.length());
    }
    return uri;
  }
}
