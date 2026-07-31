package com.github.klboke.kkrepo.server.management;

import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.core.RepositoryType;
import com.github.klboke.kkrepo.server.repositories.RepositoryNotFoundException;
import com.github.klboke.kkrepo.server.repositories.RepositoryService;
import com.github.klboke.kkrepo.server.repositories.RepositoryView;
import com.github.klboke.kkrepo.server.security.ForwardedHeaderPolicy;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriUtils;

@RestController
@RequestMapping("/service/rest/v1/repositories")
public class NexusRepositoryManagementController {
  private final RepositoryService repositoryService;
  private final NexusRepositoryManagementAuthorizer authorizer;
  private final ForwardedHeaderPolicy forwardedHeaderPolicy;

  public NexusRepositoryManagementController(
      RepositoryService repositoryService,
      NexusRepositoryManagementAuthorizer authorizer,
      ForwardedHeaderPolicy forwardedHeaderPolicy) {
    this.repositoryService = repositoryService;
    this.authorizer = authorizer;
    this.forwardedHeaderPolicy = forwardedHeaderPolicy;
  }

  @GetMapping("/maven/group/{repositoryName}")
  public MavenGroupRepositoryView getMavenGroup(
      @PathVariable("repositoryName") String repositoryName,
      HttpServletRequest request) {
    authorizer.requireRepositoryAdmin(
        request, RepositoryFormat.MAVEN2, repositoryName, "read");
    RepositoryView repository = repositoryService.get(repositoryName);
    if (repository.format() != RepositoryFormat.MAVEN2
        || repository.type() != RepositoryType.GROUP
        || repository.group() == null) {
      throw new RepositoryNotFoundException(repositoryName);
    }
    return new MavenGroupRepositoryView(
        repository.name(),
        repository.format().id(),
        repositoryUrl(repository.name(), request),
        repository.online(),
        new StorageAttributes(
            repository.blobStoreName(), repository.strictContentTypeValidation()),
        new GroupAttributes(repository.group().memberNames()),
        repository.type().name().toLowerCase(Locale.ROOT));
  }

  private String repositoryUrl(String repositoryName, HttpServletRequest request) {
    String contextPath = request.getContextPath() == null ? "" : request.getContextPath();
    return forwardedHeaderPolicy.serverBaseUrl(request)
        + contextPath
        + "/repository/"
        + UriUtils.encodePathSegment(repositoryName, StandardCharsets.UTF_8);
  }

  @ExceptionHandler(RepositoryNotFoundException.class)
  public ResponseEntity<Void> notFound(RepositoryNotFoundException ignored) {
    return ResponseEntity.notFound().build();
  }

  public record MavenGroupRepositoryView(
      String name,
      String format,
      String url,
      boolean online,
      StorageAttributes storage,
      GroupAttributes group,
      String type) {}

  public record StorageAttributes(
      String blobStoreName,
      boolean strictContentTypeValidation) {}

  public record GroupAttributes(List<String> memberNames) {}
}
