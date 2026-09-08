package com.github.klboke.kkrepo.server.security;

import com.github.klboke.kkrepo.server.repositories.RepositoryCatalogCache;
import com.github.klboke.kkrepo.server.security.ContentSelectorPreviewService.PreviewRequest;
import com.github.klboke.kkrepo.server.security.ContentSelectorPreviewService.PreviewResult;
import jakarta.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
public class ContentSelectorManagementController {
  private final ContentSelectorPreviewService previews;
  private final SecurityManagementService security;
  private final RepositoryCatalogCache repositories;

  public ContentSelectorManagementController(ContentSelectorPreviewService previews,
      SecurityManagementService security, RepositoryCatalogCache repositories) {
    this.previews = previews;
    this.security = security;
    this.repositories = repositories;
  }

  @PostMapping("/service/rest/internal/ui/content-selectors/preview")
  public PreviewResult preview(HttpServletRequest request, @RequestBody PreviewRequest input) {
    var subject = subject(request);
    if (!allowed(subject, "selectors:create") && !allowed(subject, "selectors:update")) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Selector create or update permission required");
    }
    return previews.preview(input);
  }

  @GetMapping("/internal/security/content-selectors/options")
  public Options options(HttpServletRequest request) {
    var subject = subject(request);
    Map<String, Boolean> permissions = new LinkedHashMap<>();
    for (String action : List.of("read", "create", "update", "delete")) {
      permissions.put(action, allowed(subject, "selectors:" + action));
    }
    if (permissions.values().stream().noneMatch(Boolean::booleanValue)) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Selector permission required");
    }
    permissions.put("createPrivilege", allowed(subject, "privileges:create"));
    boolean readPrivileges = allowed(subject, "privileges:read");
    Map<String, List<String>> usedBy = new LinkedHashMap<>();
    if (permissions.get("read") && readPrivileges) {
      for (var privilege : security.listPrivileges()) {
        if (!"repository-content-selector".equals(privilege.type())) continue;
        Object selector = null;
        for (String key : List.of("contentSelector", "selector", "selectorName", "contentSelectorName")) {
          if (privilege.properties().get(key) != null) { selector = privilege.properties().get(key); break; }
        }
        if (selector != null) usedBy.computeIfAbsent(String.valueOf(selector), key -> new java.util.ArrayList<>())
            .add(privilege.name());
      }
    }
    // Repository choices are needed for preview/privilege creation. Plain selector readers
    // cannot use those actions and must not gain a repository configuration listing here.
    List<RepositoryOption> repositoryOptions = permissions.get("create") || permissions.get("update")
        || permissions.get("createPrivilege") || readPrivileges
        ? repositories.snapshot().records().stream()
            .map(repository -> new RepositoryOption(repository.name(), repository.format().id(),
                repository.type().name().toLowerCase(java.util.Locale.ROOT))).toList()
        : List.of();
    return new Options(permissions, repositoryOptions, usedBy);
  }

  private boolean allowed(AuthenticatedSubject subject, String permission) {
    return security.decide(subject.permissionSubject(), "nexus:" + permission).allowed();
  }

  private AuthenticatedSubject subject(HttpServletRequest request) {
    if (request.getAttribute(AuthenticatedSubject.REQUEST_ATTRIBUTE) instanceof AuthenticatedSubject subject) {
      return subject;
    }
    throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required");
  }

  public record RepositoryOption(String name, String format, String type) {}
  public record Options(Map<String, Boolean> permissions, List<RepositoryOption> repositories,
      Map<String, List<String>> usedBy) {}
}
