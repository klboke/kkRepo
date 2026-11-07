package com.github.klboke.kkrepo.server.kkquality;

import com.github.klboke.kkrepo.auth.PermissionAction;
import com.github.klboke.kkrepo.auth.RepositoryPermission;
import com.github.klboke.kkrepo.persistence.jdbc.api.KkQualityDao.Project;
import com.github.klboke.kkrepo.persistence.jdbc.api.RepositoryDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.RepositoryRecord;
import com.github.klboke.kkrepo.server.security.AuthenticatedSubject;
import com.github.klboke.kkrepo.server.security.SecurityManagementService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/**
 * Authorization for the kkQuality surface.
 *
 * <p>A project bound to a kkRepo repository inherits that repository's read permission, which is
 * what makes the Sonar-compatible API multi-tenant: two teams cannot see each other's projects,
 * file paths or issue messages. Projects without a repository binding are only visible through the
 * application-wide {@code nx-kkquality-read} privilege. Ingestion requires either the
 * {@code nx-kkquality-update} privilege or edit rights on the bound repository, so a CI token that
 * can already publish artifacts can submit analyses for its own repository.
 *
 * <p>Decisions always go through {@link SecurityManagementService}, i.e. the durable grants shared
 * by every replica; nothing is cached locally here.
 */
@Component
public class KkQualityAuthorizer {
  public static final String READ_PRIVILEGE = "nx-kkquality-read";
  public static final String UPDATE_PRIVILEGE = "nx-kkquality-update";

  private final SecurityManagementService security;
  private final RepositoryDao repositories;

  public KkQualityAuthorizer(SecurityManagementService security, RepositoryDao repositories) {
    this.security = security;
    this.repositories = repositories;
  }

  public AuthenticatedSubject requireSubject(HttpServletRequest request) {
    Object attribute = request.getAttribute(AuthenticatedSubject.REQUEST_ATTRIBUTE);
    if (attribute instanceof AuthenticatedSubject subject) {
      return subject;
    }
    throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required");
  }

  public boolean hasGlobalRead(AuthenticatedSubject subject) {
    return decision(subject, READ_PRIVILEGE);
  }

  public boolean hasGlobalUpdate(AuthenticatedSubject subject) {
    return decision(subject, UPDATE_PRIVILEGE);
  }

  public boolean canReadProject(AuthenticatedSubject subject, Project project) {
    if (hasGlobalRead(subject)) {
      return true;
    }
    return project.repositoryId() != null
        && repositoryAction(subject, project.repositoryId(), PermissionAction.READ);
  }

  public void requireProjectRead(AuthenticatedSubject subject, Project project) {
    if (!canReadProject(subject, project)) {
      throw new ResponseStatusException(
          HttpStatus.FORBIDDEN, "No read access to project " + project.key());
    }
  }

  /** Ingestion is allowed with the application privilege or edit rights on the bound repository. */
  public boolean canIngest(AuthenticatedSubject subject, Long repositoryId) {
    if (hasGlobalUpdate(subject)) {
      return true;
    }
    return repositoryId != null
        && repositoryAction(subject, repositoryId, PermissionAction.EDIT);
  }

  public void requireIngest(AuthenticatedSubject subject, Long repositoryId) {
    if (!canIngest(subject, repositoryId)) {
      throw new ResponseStatusException(
          HttpStatus.FORBIDDEN, "No rights to submit kkQuality analyses for this project");
    }
  }

  /**
   * Repository ids the subject may read. Empty when the subject holds the global read privilege,
   * in which case callers must not filter by repository at all.
   */
  public List<Long> readableRepositoryIds(AuthenticatedSubject subject) {
    List<Long> ids = new ArrayList<>();
    for (RepositoryRecord repository : repositories.list()) {
      if (security.decide(
          subject.permissionSubject(),
          new RepositoryPermission(
              repository.name(), repository.format(), "", PermissionAction.READ)).allowed()) {
        ids.add(repository.id());
      }
    }
    return ids;
  }

  public Optional<RepositoryRecord> findRepository(String name) {
    return name == null || name.isBlank()
        ? Optional.empty() : repositories.findByName(name.trim());
  }

  private boolean repositoryAction(
      AuthenticatedSubject subject, long repositoryId, PermissionAction action) {
    Optional<RepositoryRecord> repository = repositories.findById(repositoryId);
    return repository.isPresent()
        && security.decide(
            subject.permissionSubject(),
            new RepositoryPermission(
                repository.get().name(), repository.get().format(), "", action)).allowed();
  }

  private boolean decision(AuthenticatedSubject subject, String permission) {
    return security.decide(subject.permissionSubject(), permission).allowed();
  }
}
