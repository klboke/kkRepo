package com.github.klboke.kkrepo.server.routing.helm;

import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.core.RepositoryType;
import com.github.klboke.kkrepo.server.helm.HelmHostedService;
import com.github.klboke.kkrepo.server.maven.MavenExceptions;
import com.github.klboke.kkrepo.server.maven.MavenResponse;
import com.github.klboke.kkrepo.server.routing.RepositoryProtocolHandler;
import com.github.klboke.kkrepo.server.routing.RepositoryProtocolRequest;
import com.github.klboke.kkrepo.server.routing.RepositoryProtocolRoute;
import java.io.IOException;
import java.util.Collection;
import java.util.List;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;

/** Exact multipart upload route, kept separate from the catch-all built-in protocol handler. */
@Component
public class HelmChartUploadProtocolHandler implements RepositoryProtocolHandler {
  private static final List<RepositoryProtocolRoute> ROUTES = List.of(
      RepositoryProtocolRoute.exactPath(
          RepositoryFormat.HELM,
          RepositoryType.HOSTED,
          HttpMethod.POST,
          "api/charts",
          0));

  private final HelmHostedService helm;

  public HelmChartUploadProtocolHandler(HelmHostedService helm) {
    this.helm = helm;
  }

  @Override
  public Collection<RepositoryProtocolRoute> routes() {
    return ROUTES;
  }

  @Override
  public ResponseEntity<?> handle(RepositoryProtocolRequest request) throws IOException {
    if (request.multipartFile() == null) {
      throw new MavenExceptions.BadRequestException(
          "Helm chart upload requires multipart field 'chart'");
    }
    MavenResponse response = helm.push(
        request.runtime(),
        request.multipartFile(),
        "anonymous",
        request.servletRequest().getRemoteAddr());
    return ResponseEntity.status(response.status()).build();
  }
}
