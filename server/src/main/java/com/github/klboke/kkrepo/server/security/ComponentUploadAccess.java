package com.github.klboke.kkrepo.server.security;

import com.github.klboke.kkrepo.auth.AccessDecisionService;
import com.github.klboke.kkrepo.auth.PermissionAction;
import com.github.klboke.kkrepo.auth.RepositoryPermission;
import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntime;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component
public class ComponentUploadAccess {
  private final AccessDecisionService decisions;

  public ComponentUploadAccess(AccessDecisionService decisions) {
    this.decisions = decisions;
  }

  static PermissionAction action(RepositoryFormat format) {
    return switch (format) {
      case SWIFT, ANSIBLEGALAXY, APT, ALPINE, R -> PermissionAction.ADD;
      default -> PermissionAction.EDIT;
    };
  }

  public void require(AuthenticatedSubject subject, RepositoryRuntime repository) {
    if (subject == null || subject.permissionSubject() == null) {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required");
    }
    // OHPM checks ADD and, for an existing package, EDIT against the parsed package identity.
    if (repository.format() == RepositoryFormat.OHPM) {
      return;
    }
    var permission = new RepositoryPermission(repository.name(), repository.format(), "", action(repository.format()));
    var decision = decisions.decideAllFresh(subject.permissionSubject(), List.of(permission)).get(permission);
    if (decision == null || !decision.allowed()) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Repository upload permission required");
    }
  }
}
