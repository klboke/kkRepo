package com.github.klboke.kkrepo.server.staging;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * Resolves the staging session a request belongs to, whatever selector the client used.
 *
 * <p>A request may select a session with the {@code X-KkRepo-Staging} header, with the
 * {@code stagingSession} query parameter, or implicitly through a {@value
 * StagingCredentialService#DOMAIN} API key whose scopes were bound to the request by {@link
 * StagingCredentialService#bind}. Every protocol handler must call this resolver before choosing a
 * live publication path, otherwise one selector silently falls back to live content.
 *
 * <p>Conflicting explicit selectors are rejected with 400. An explicit selector that contradicts
 * the session bound to the presented credential is rejected with 403, which prevents a staging
 * token from being replayed against another session of the same owner. When no selector is present
 * the request is a normal live request and the resolver returns {@code null}.
 */
public final class StagingSelector {
  public static final String QUERY_PARAMETER = "stagingSession";

  private StagingSelector() {
  }

  /**
   * @return the selected session key, or {@code null} when the request is not staged
   * @throws ResponseStatusException 400 for contradictory explicit selectors, 403 for a selector
   *     that does not match the session bound to the presented credential
   */
  public static String resolve(HttpServletRequest request) {
    if (request == null) {
      return null;
    }
    String header = text(request.getHeader(StagingService.HEADER));
    String query = text(request.getParameter(QUERY_PARAMETER));
    if (header != null && query != null && !header.equals(query)) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "Conflicting staging session selectors");
    }
    String explicit = header != null ? header : query;
    String bound = boundSession(request);
    if (explicit != null && bound != null && !explicit.equals(bound)) {
      throw new ResponseStatusException(
          HttpStatus.FORBIDDEN, "Staging session does not match the presented credential");
    }
    return explicit != null ? explicit : bound;
  }

  private static String boundSession(HttpServletRequest request) {
    Object value = request.getAttribute(StagingCredentialService.SESSION_ATTRIBUTE);
    return value == null ? null : text(value.toString());
  }

  private static String text(String value) {
    if (value == null) {
      return null;
    }
    String trimmed = value.trim();
    return trimmed.isEmpty() ? null : trimmed;
  }
}
