package com.github.klboke.kkrepo.server.routing;

import com.github.klboke.kkrepo.core.BlobStorage;
import com.github.klboke.kkrepo.plugin.api.PluginHostService;
import com.github.klboke.kkrepo.plugin.api.RepositoryContext;
import com.github.klboke.kkrepo.plugin.api.RepositoryDescriptor;
import com.github.klboke.kkrepo.plugin.api.RepositoryPolicy;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntime;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Host implementation of the plugin {@link RepositoryContext} for one routed request.
 *
 * <p>Plugins only see the neutral interface. Host services that receive the context back unwrap it
 * with {@link #of(RepositoryContext)} to reach the repository runtime snapshot the dispatcher routed
 * on and the authenticated servlet request, from which they derive caller identity, client address
 * and public URLs.
 */
public final class PluginRepositoryContext implements RepositoryContext {
  private final RepositoryDescriptor repository;
  private final List<PluginHostService> services;
  private final RepositoryRuntime runtime;
  private final HttpServletRequest servletRequest;

  PluginRepositoryContext(RepositoryDescriptor repository, List<PluginHostService> services,
      RepositoryRuntime runtime, HttpServletRequest servletRequest) {
    this.repository = Objects.requireNonNull(repository, "repository");
    this.services = List.copyOf(services);
    this.runtime = Objects.requireNonNull(runtime, "runtime");
    this.servletRequest = Objects.requireNonNull(servletRequest, "servletRequest");
  }

  /** The host context behind {@code context}; fails when a plugin forged its own context. */
  public static PluginRepositoryContext of(RepositoryContext context) {
    if (context instanceof PluginRepositoryContext host) return host;
    throw new IllegalStateException("Repository context was not created by the host routing layer");
  }

  @Override
  public RepositoryDescriptor repository() {
    return repository;
  }

  @Override
  public Optional<BlobStorage> blobs() {
    return Optional.empty();
  }

  @Override
  public <T> Optional<T> service(Class<T> type) {
    return services.stream().filter(type::isInstance).map(type::cast).findFirst();
  }

  @Override
  public RepositoryPolicy policy() {
    return new RepositoryPolicy(
        repository.writePolicy(), repository.versionPolicy(), repository.layoutPolicy());
  }

  @Override
  public Object hostContext() {
    return servletRequest;
  }

  /** Repository runtime snapshot the request was routed on. */
  public RepositoryRuntime runtime() {
    return runtime;
  }

  /** The authenticated request, for host services only. */
  public HttpServletRequest servletRequest() {
    return servletRequest;
  }
}
