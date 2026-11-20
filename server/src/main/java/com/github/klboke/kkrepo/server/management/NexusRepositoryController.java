package com.github.klboke.kkrepo.server.management;

import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.server.repositories.RepositoryService;
import com.github.klboke.kkrepo.server.repositories.RepositoryView;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Nexus-compatible repository listing. When {@code stagingSession} is present the list is scoped
 * to the repositories participating in that multi-repository session.
 */
@RestController
@RequestMapping("/service/rest/v1/repositories")
public class NexusRepositoryController {
  private final RepositoryService repositories;
  private final NexusStagingManagementService staging;
  private final NexusAssetAuthorizer authorizer;

  public NexusRepositoryController(
      RepositoryService repositories,
      NexusStagingManagementService staging,
      NexusAssetAuthorizer authorizer) {
    this.repositories = repositories;
    this.staging = staging;
    this.authorizer = authorizer;
  }

  @GetMapping
  public List<RepositoryXo> list(
      @RequestParam(value = "stagingSession", required = false) String stagingSession,
      HttpServletRequest request) {
    if (stagingSession != null && !stagingSession.isBlank()) {
      return staging.repositories(stagingSession, request).stream()
          .map(RepositoryXo::from)
          .toList();
    }
    authorizer.requireSearch(request);
    return repositories.list().stream().map(RepositoryXo::from).toList();
  }

  @GetMapping("/{name}")
  public RepositoryXo get(
      @PathVariable("name") String name,
      @RequestParam(value = "stagingSession", required = false) String stagingSession,
      HttpServletRequest request) {
    if (stagingSession != null && !stagingSession.isBlank()) {
      return staging.repositories(stagingSession, request).stream()
          .filter(repository -> repository.name().equals(name))
          .map(RepositoryXo::from)
          .findFirst()
          .orElseThrow(() -> new NexusAssetManagementService.AssetNotFoundException());
    }
    authorizer.requireSearch(request);
    RepositoryView view = repositories.get(name);
    return RepositoryXo.from(view);
  }

  public record RepositoryXo(
      String name,
      String format,
      String type,
      String url,
      Boolean online,
      Map<String, Object> attributes) {
    static RepositoryXo from(NexusStagingManagementService.RepositoryView view) {
      return new RepositoryXo(
          view.name(), view.format(), view.type(), view.url(), view.online(), view.attributes());
    }

    static RepositoryXo from(RepositoryView view) {
      RepositoryFormat format = view.format();
      return new RepositoryXo(
          view.name(),
          format == null ? null : format.id(),
          view.type() == null ? null : view.type().name().toLowerCase(java.util.Locale.ROOT),
          view.url(),
          view.online(),
          Map.of());
    }
  }
}
