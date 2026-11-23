package com.github.klboke.kkrepo.server.offload;

import static com.github.klboke.kkrepo.server.offload.DownloadOffloadMethod.PROBE_HEADER;
import static com.github.klboke.kkrepo.server.offload.DownloadOffloadMethod.REQUEST_HEADER;
import static com.github.klboke.kkrepo.server.offload.DownloadOffloadMethod.VERIFIED_HEADER;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletResponseWrapper;
import java.io.IOException;
import java.util.Locale;
import java.util.Set;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Keeps proxy instructions out of responses unless the download path explicitly approved them. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 30)
class DownloadOffloadResponseFilter extends OncePerRequestFilter {
  private static final String APPROVED = DownloadOffloadResponseFilter.class.getName() + ".approved";
  private static final String CUSTOM_MARKER = "custom; header=";
  private static final Set<String> RESERVED = Set.of(
      "x-accel-redirect", "x-sendfile", "x-lighttpd-send-file", "x-lighttpd-send-file2",
      REQUEST_HEADER.toLowerCase(Locale.ROOT), PROBE_HEADER.toLowerCase(Locale.ROOT), VERIFIED_HEADER.toLowerCase(Locale.ROOT));

  private final DownloadOffloadSettings settings;

  DownloadOffloadResponseFilter(DownloadOffloadSettings settings) {
    this.settings = settings;
  }

  static void approve(HttpServletRequest request, String name, String value, String verified) {
    request.setAttribute(APPROVED, new Handoff(name, value, verified));
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    String custom = customHeader(request);
    chain.doFilter(request, new HttpServletResponseWrapper(response) {
      private boolean allowed(String name, String value) {
        if (request.getAttribute(APPROVED) instanceof Handoff handoff) {
          if (handoff.name().equalsIgnoreCase(name)) return handoff.value().equals(value);
          if (VERIFIED_HEADER.equalsIgnoreCase(name) && handoff.verified() != null) {
            return handoff.verified().equals(value);
          }
        }
        return !RESERVED.contains(name.toLowerCase(Locale.ROOT)) && !name.equalsIgnoreCase(custom);
      }

      @Override
      public void setHeader(String name, String value) {
        if (allowed(name, value)) super.setHeader(name, value);
      }

      @Override
      public void addHeader(String name, String value) {
        if (allowed(name, value)) super.addHeader(name, value);
      }

      @Override
      public void setIntHeader(String name, int value) {
        if (allowed(name, null)) super.setIntHeader(name, value);
      }

      @Override
      public void addIntHeader(String name, int value) {
        if (allowed(name, null)) super.addIntHeader(name, value);
      }
    });
  }

  private String customHeader(HttpServletRequest request) {
    String marker = request.getHeader(REQUEST_HEADER);
    if (marker == null || !marker.startsWith(CUSTOM_MARKER) || !settings.trusted(request)) {
      return null;
    }
    int end = marker.indexOf(';', CUSTOM_MARKER.length());
    if (end < 0) return null;
    String name = marker.substring(CUSTOM_MARKER.length(), end);
    return DownloadOffloadMethod.validCustomHeader(name) ? name : null;
  }

  private record Handoff(String name, String value, String verified) {}
}
