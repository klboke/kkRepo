package com.github.klboke.kkrepo.plugin.helm;

import com.github.klboke.kkrepo.plugin.api.FormatId;
import com.github.klboke.kkrepo.plugin.api.KkRepoPlugin;
import com.github.klboke.kkrepo.plugin.api.PluginApiVersion;
import com.github.klboke.kkrepo.plugin.api.PluginContext;
import com.github.klboke.kkrepo.plugin.api.PluginDescriptor;
import com.github.klboke.kkrepo.plugin.api.RepositoryKind;
import com.github.klboke.kkrepo.plugin.api.RepositoryProtocolHandler;
import com.github.klboke.kkrepo.plugin.api.RepositoryProtocolRoute;
import com.github.klboke.kkrepo.plugin.api.RepositoryRequest;
import com.github.klboke.kkrepo.plugin.api.RepositoryResponse;
import com.github.klboke.kkrepo.protocol.helm.HelmChartUploader;
import java.io.IOException;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * Extracted Helm chart upload route. Routing and multipart validation live in the plugin; chart
 * parsing, asset writes, index updates and audit stay behind the host {@link HelmChartUploader}
 * service.
 */
public final class HelmProtocolPlugin implements KkRepoPlugin, RepositoryProtocolHandler {
  private static final List<RepositoryProtocolRoute> ROUTES = List.of(
      RepositoryProtocolRoute.exactPath(
          new FormatId("helm"), RepositoryKind.HOSTED, "POST", "api/charts", -100));

  @Override
  public PluginDescriptor descriptor() {
    return new PluginDescriptor(
        "helm-protocol",
        "1.0.0",
        PluginApiVersion.CURRENT,
        getClass().getName(),
        List.of("repository.handler"));
  }

  @Override
  public void contribute(PluginContext context) {
    context.registerRepositoryProtocolHandler(this);
  }

  @Override
  public Collection<RepositoryProtocolRoute> routes() {
    return ROUTES;
  }

  @Override
  public RepositoryResponse handle(RepositoryRequest request) throws IOException {
    if (request.multipart() == null) {
      return new RepositoryResponse(
          400, Map.of(), "Helm chart upload requires multipart field 'chart'"
              .getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
    HelmChartUploader uploader = request.context().service(HelmChartUploader.class)
        .orElseThrow(() -> new IllegalStateException("Helm upload service is not available"));
    int status = uploader.upload(
        request.repository(),
        request.multipart(),
        "anonymous",
        request.context().clientAddress());
    return new RepositoryResponse(status, Map.of(), new byte[0]);
  }
}
