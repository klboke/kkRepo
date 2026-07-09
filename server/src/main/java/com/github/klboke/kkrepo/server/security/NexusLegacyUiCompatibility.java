package com.github.klboke.kkrepo.server.security;

import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Coarse-grained compatibility switch for Nexus web UI internals.
 *
 * <p>This intentionally gates the whole legacy Nexus UI surface together: Rapture, ExtDirect,
 * Wonderland, internal UI REST routes, and iframe-style component upload. These endpoints are kept
 * for Nexus reference compatibility tests, not for native kkrepo management flows.
 */
@Component
public class NexusLegacyUiCompatibility {
  static final String INTERNAL_SERVICE_PREFIX = "/service/rest/internal/";
  static final String INTERNAL_UI_PREFIX = INTERNAL_SERVICE_PREFIX + "ui/";
  private static final String INTERNAL_UI_UPLOAD_PREFIX = INTERNAL_UI_PREFIX + "upload/";
  private static final String INTERNAL_UI_SECURITY_PREFIX = INTERNAL_UI_PREFIX + "security/";
  private static final String INTERNAL_UI_ANONYMOUS_SETTINGS = INTERNAL_UI_PREFIX + "anonymous-settings";
  private static final String INTERNAL_UI_USER = INTERNAL_UI_PREFIX + "user";
  private static final String EXT_DIRECT = "/service/extdirect";
  private static final String RAPTURE_SESSION = "/service/rapture/session";
  private static final String WONDERLAND_AUTHENTICATE = "/service/rest/wonderland/authenticate";

  private final boolean enabled;

  public NexusLegacyUiCompatibility(
      @Value("${kkrepo.nexus.legacy-ui.enabled:false}") boolean enabled) {
    this.enabled = enabled;
  }

  public boolean enabled() {
    return enabled;
  }

  boolean serviceRestInternalPath(String uri) {
    return enabled && uri.startsWith(INTERNAL_SERVICE_PREFIX);
  }

  boolean internalUiAnonymousSettingsPath(String uri) {
    return enabled && uri.equals(INTERNAL_UI_ANONYMOUS_SETTINGS);
  }

  boolean internalUiUploadPath(String uri) {
    return enabled && uri.startsWith(INTERNAL_UI_UPLOAD_PREFIX);
  }

  boolean internalUiUserPath(String uri) {
    return enabled && uri.equals(INTERNAL_UI_USER);
  }

  boolean internalUiUserChildPath(String uri) {
    return enabled && uri.startsWith(INTERNAL_UI_USER + "/");
  }

  boolean internalUiSecurityPath(String uri) {
    return enabled && uri.startsWith(INTERNAL_UI_SECURITY_PREFIX);
  }

  Optional<String> internalUiUploadRepository(String uri) {
    if (!internalUiUploadPath(uri)) {
      return Optional.empty();
    }
    String repository = uri.substring(INTERNAL_UI_UPLOAD_PREFIX.length());
    int slash = repository.indexOf('/');
    if (slash >= 0) {
      repository = repository.substring(0, slash);
    }
    return repository.isBlank() ? Optional.empty() : Optional.of(repository);
  }

  String internalUiSecuritySubpath(String uri) {
    return uri.substring(INTERNAL_UI_SECURITY_PREFIX.length());
  }

  boolean raptureSessionPath(String uri) {
    return enabled && uri.equals(RAPTURE_SESSION);
  }

  boolean extDirectPath(String uri) {
    return enabled && uri.startsWith(EXT_DIRECT);
  }

  boolean extDirectRootPath(String uri) {
    return enabled && uri.equals(EXT_DIRECT);
  }

  boolean wonderlandAuthenticatePath(String uri) {
    return enabled && uri.equals(WONDERLAND_AUTHENTICATE);
  }

  boolean csrfProtectedPath(String uri) {
    return serviceRestInternalPath(uri)
        || extDirectPath(uri)
        || raptureSessionPath(uri)
        || wonderlandAuthenticatePath(uri);
  }

  boolean csrfTokenBootstrapPath(String uri) {
    return raptureSessionPath(uri);
  }

  boolean loginPath(String uri) {
    return raptureSessionPath(uri)
        || wonderlandAuthenticatePath(uri)
        || extDirectRootPath(uri);
  }

  boolean managementAuthPath(String uri) {
    return internalUiSecurityPath(uri) || extDirectRootPath(uri);
  }

  boolean auditedMutationPath(String uri) {
    return serviceRestInternalPath(uri)
        || extDirectRootPath(uri)
        || raptureSessionPath(uri)
        || wonderlandAuthenticatePath(uri);
  }
}
