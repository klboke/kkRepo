package com.github.klboke.kkrepo.server.security;

import com.github.klboke.kkrepo.persistence.jdbc.api.model.RepositoryRecord;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** Bind the repository selected by MVC to the repository checked before handler dispatch. */
@Configuration(proxyBeanMethods = false)
class RepositoryRouteGuard implements WebMvcConfigurer {
  private final String repositoryPrefix;

  RepositoryRouteGuard(@Value("${kkrepo.compatibility.repository-url-prefix:/repository}") String repositoryPrefix) {
    this.repositoryPrefix = repositoryPrefix;
  }

  @Override
  public void addInterceptors(InterceptorRegistry registry) {
    register(registry, repositoryPrefix, "name");
    register(registry, "/service/rest/repository/browse", "repository");
  }

  private static void register(InterceptorRegistry registry, String prefix, String variable) {
    registry.addInterceptor(new CheckedRepository(prefix, variable))
        .addPathPatterns(prefix + "/{" + variable + "}/**");
  }

  private record CheckedRepository(String prefix, String variable) implements HandlerInterceptor {
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
        throws IOException {
      Object record = request.getAttribute(RepositorySecurityFilter.REPOSITORY_RECORD_ATTRIBUTE);
      Object variables = request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
      if (record instanceof RepositoryRecord repository && variables instanceof Map<?, ?> pathVariables
          && repository.name().equals(pathVariables.get(variable))) {
        // Protocols interpret the remaining asset path themselves. Only bind the routing segments.
        String root = request.getContextPath() + prefix + "/" + repository.name();
        String uri = request.getRequestURI();
        if (uri.equals(root) || uri.startsWith(root + "/")) {
          return true;
        }
      }
      response.sendError(HttpServletResponse.SC_NOT_FOUND);
      return false;
    }
  }
}
