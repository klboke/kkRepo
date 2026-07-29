package com.github.klboke.kkrepo.server.management;

import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.core.RepositoryType;
import com.github.klboke.kkrepo.server.repositories.RepositoryNotFoundException;
import com.github.klboke.kkrepo.server.repositories.RepositoryService;
import com.github.klboke.kkrepo.server.repositories.RepositoryView;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/service/rest/v1/repositories")
public class NexusRepositoryManagementController {
  private final RepositoryService repositoryService;
  private final NexusRepositoryManagementAuthorizer authorizer;

  public NexusRepositoryManagementController(
      RepositoryService repositoryService,
      NexusRepositoryManagementAuthorizer authorizer) {
    this.repositoryService = repositoryService;
    this.authorizer = authorizer;
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
        repository.online(),
        new StorageAttributes(
            repository.blobStoreName(), repository.strictContentTypeValidation()),
        new GroupAttributes(repository.group().memberNames()));
  }

  @ExceptionHandler(RepositoryNotFoundException.class)
  public ResponseEntity<Void> notFound(RepositoryNotFoundException ignored) {
    return ResponseEntity.notFound().build();
  }

  public record MavenGroupRepositoryView(
      String name,
      boolean online,
      StorageAttributes storage,
      GroupAttributes group) {}

  public record StorageAttributes(
      String blobStoreName,
      boolean strictContentTypeValidation) {}

  public record GroupAttributes(List<String> memberNames) {}
}
