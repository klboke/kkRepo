package com.github.klboke.kkrepo.server.blob;

import com.github.klboke.kkrepo.server.config.RepositoryUrlPrefix;
import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;

final class RepositoryClientAbortSupport {

  private RepositoryClientAbortSupport() {
  }

  static boolean shouldHandle(HttpServletRequest request, Throwable exception) {
    return isRepositoryRequest(request)
        && (TempBlobFiles.hasHandledClientAbort(request) || TempBlobFiles.isClientAbort(exception));
  }

  static boolean isRepositoryRequest(HttpServletRequest request) {
    String uri = requestUri(request);
    return uri != null && uri.startsWith(RepositoryUrlPrefix.path(request));
  }

  static String requestUri(HttpServletRequest request) {
    if (request == null) {
      return null;
    }
    Object errorUri = request.getAttribute(RequestDispatcher.ERROR_REQUEST_URI);
    String uri = errorUri instanceof String value ? value : request.getRequestURI();
    if (uri == null) {
      return null;
    }
    String contextPath = request.getContextPath();
    if (contextPath != null && !contextPath.isBlank() && uri.startsWith(contextPath)) {
      uri = uri.substring(contextPath.length());
    }
    return uri.isEmpty() ? "/" : uri;
  }
}
