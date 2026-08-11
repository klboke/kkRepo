package com.github.klboke.kkrepo.server.repositories;

import com.github.klboke.kkrepo.auth.AccessDecision;
import com.github.klboke.kkrepo.auth.PermissionAction;
import com.github.klboke.kkrepo.auth.RepositoryPermission;
import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.core.RepositoryRecipe;
import com.github.klboke.kkrepo.core.RepositoryRecipes;
import com.github.klboke.kkrepo.core.RepositoryType;
import com.github.klboke.kkrepo.persistence.jdbc.api.ConanRegistryDao;
import com.github.klboke.kkrepo.server.apt.AptService;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntime;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntimeRegistry;
import com.github.klboke.kkrepo.server.security.AuthenticatedSubject;
import com.github.klboke.kkrepo.server.security.SecurityAuthenticationService;
import com.github.klboke.kkrepo.server.security.SecurityManagementService;
import com.github.klboke.kkrepo.server.repositories.RepositoryCommands.CreateCommand;
import com.github.klboke.kkrepo.server.repositories.RepositoryCommands.UpdateCommand;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/repositories")
public class RepositoriesController {
  private final RepositoryService service;
  private final SecurityAuthenticationService authenticationService;
  private final SecurityManagementService securityService;
  private AptService aptService;
  private ConanRegistryDao conanRegistry;
  private RepositoryRuntimeRegistry runtimeRegistry;

  public RepositoriesController(
      RepositoryService service,
      SecurityAuthenticationService authenticationService,
      SecurityManagementService securityService) {
    this.service = service;
    this.authenticationService = authenticationService;
    this.securityService = securityService;
  }

  @Autowired(required = false)
  void setAptManagement(
      AptService aptService, RepositoryRuntimeRegistry runtimeRegistry) {
    this.aptService = aptService;
    this.runtimeRegistry = runtimeRegistry;
  }

  @Autowired(required = false)
  void setConanManagement(ConanRegistryDao conanRegistry) {
    this.conanRegistry = conanRegistry;
  }

  @GetMapping
  public List<RepositoryView> list(
      @RequestParam(name = "purpose", required = false) String purpose,
      HttpServletRequest request) {
    AuthenticatedSubject subject = currentOrAnonymous(request).orElse(null);
    if (subject == null) {
      return List.of();
    }
    boolean adminPurpose = "admin".equalsIgnoreCase(purpose);
    List<RepositoryView> repositories = service.list();
    if (adminPurpose) {
      if (allRepositoryAdminAllowed(subject, "read")) {
        return repositories;
      }
      return repositories.stream()
          .filter(repository -> repositoryAdminAllowed(subject, repository.format(), repository.name(), "read"))
          .toList();
    }
    if (allRepositoryActionAllowed(subject, PermissionAction.BROWSE)) {
      return repositories;
    }
    return repositories.stream()
        .filter(repository -> repositoryBrowseAllowed(subject, repository))
        .toList();
  }

  @GetMapping("/uploadable")
  public List<RepositoryView> uploadable(HttpServletRequest request) {
    AuthenticatedSubject subject = requireAuthenticated(request);
    List<RepositoryView> candidates = service.list().stream()
        .filter(repository -> repository.online() && repository.type() == RepositoryType.HOSTED)
        .toList();
    if (allRepositoryActionAllowed(subject, PermissionAction.EDIT)) {
      return candidates;
    }
    return candidates.stream()
        .filter(repository -> repositoryActionAllowed(subject, repository, PermissionAction.EDIT))
        .toList();
  }

  @GetMapping("/recipes")
  public List<RepositoryRecipe> recipes(HttpServletRequest request) {
    requireAuthenticated(request);
    return service.recipes();
  }

  @GetMapping("/{name}")
  public RepositoryView get(@PathVariable("name") String name, HttpServletRequest request) {
    AuthenticatedSubject subject = requireAuthenticated(request);
    RepositoryView view = service.get(name);
    requireRepositoryAdmin(subject, view.format(), view.name(), "read");
    return view;
  }

  @PostMapping
  public ResponseEntity<RepositoryView> create(
      @RequestBody CreateCommand command,
      HttpServletRequest request) {
    AuthenticatedSubject subject = requireAuthenticated(request);
    requireRepositoryAdmin(subject, formatForCreate(command), command == null ? null : command.name(), "add");
    RepositoryView view = service.create(command);
    return ResponseEntity.status(HttpStatus.CREATED).body(view);
  }

  @PutMapping("/{name}")
  public RepositoryView update(
      @PathVariable("name") String name,
      @RequestBody UpdateCommand command,
      HttpServletRequest request) {
    AuthenticatedSubject subject = requireAuthenticated(request);
    RepositoryView existing = service.get(name);
    requireRepositoryAdmin(subject, existing.format(), existing.name(), "edit");
    return service.update(name, command);
  }

  @DeleteMapping("/{name}")
  public ResponseEntity<Void> delete(@PathVariable("name") String name, HttpServletRequest request) {
    AuthenticatedSubject subject = requireAuthenticated(request);
    RepositoryView existing = service.get(name);
    requireRepositoryAdmin(subject, existing.format(), existing.name(), "delete");
    service.delete(name);
    return ResponseEntity.noContent().build();
  }

  @PutMapping("/{name}/members")
  public RepositoryView replaceMembers(
      @PathVariable("name") String name,
      @RequestBody MembersRequest body,
      HttpServletRequest request) {
    AuthenticatedSubject subject = requireAuthenticated(request);
    RepositoryView existing = service.get(name);
    requireRepositoryAdmin(subject, existing.format(), existing.name(), "edit");
    return service.replaceMembers(name, body.memberNames());
  }

  @GetMapping("/{name}/apt/status")
  public AptService.Status aptStatus(
      @PathVariable("name") String name, HttpServletRequest request) {
    AuthenticatedSubject subject = requireAuthenticated(request);
    RepositoryView existing = requireAptRepository(name);
    requireRepositoryAdmin(subject, existing.format(), existing.name(), "read");
    return apt().status(aptRuntime(name));
  }

  @GetMapping("/{name}/conan/status")
  public ConanRegistryDao.RepositoryStatus conanStatus(
      @PathVariable("name") String name, HttpServletRequest request) {
    AuthenticatedSubject subject = requireAuthenticated(request);
    RepositoryView existing = service.get(name);
    if (existing.format() != RepositoryFormat.CONAN) {
      throw new RepositoryValidationException("Repository is not a Conan repository");
    }
    requireRepositoryAdmin(subject, existing.format(), existing.name(), "read");
    if (conanRegistry == null) {
      throw new ResponseStatusException(
          HttpStatus.SERVICE_UNAVAILABLE, "Conan repository service is unavailable");
    }
    return conanRegistry.status(existing.id());
  }

  @PostMapping("/{name}/apt/rebuild")
  public ResponseEntity<Void> rebuildApt(
      @PathVariable("name") String name,
      @RequestBody(required = false) AptRebuildRequest body,
      HttpServletRequest request) {
    AuthenticatedSubject subject = requireAuthenticated(request);
    RepositoryView existing = requireAptRepository(name);
    requireRepositoryAdmin(subject, existing.format(), existing.name(), "edit");
    apt().rebuild(aptRuntime(name), body == null ? null : body.distribution());
    return ResponseEntity.noContent().build();
  }

  @PutMapping("/{name}/apt/signing-key")
  public AptService.KeyStatus rotateAptSigningKey(
      @PathVariable("name") String name,
      @RequestBody AptSigningKeyRequest body,
      HttpServletRequest request) {
    AuthenticatedSubject subject = requireAuthenticated(request);
    RepositoryView existing = requireAptRepository(name);
    requireRepositoryAdmin(subject, existing.format(), existing.name(), "edit");
    if (body == null) {
      throw new RepositoryValidationException("APT signing key request is required");
    }
    boolean generate = Boolean.TRUE.equals(body.generate());
    if (!generate && (body.privateKey() == null || body.privateKey().isBlank())) {
      throw new RepositoryValidationException(
          "APT private signing key is required unless generate is true");
    }
    if (generate && body.privateKey() != null && !body.privateKey().isBlank()) {
      throw new RepositoryValidationException(
          "APT signing key request cannot import and generate a key at the same time");
    }
    var row = generate
        ? apt().rotateGeneratedKey(aptRuntime(name))
        : apt().rotateKey(aptRuntime(name), body.privateKey(), body.passphrase());
    return new AptService.KeyStatus(
        row.revision(), row.keyId(), row.fingerprint(), row.createdAt());
  }

  @ExceptionHandler(RepositoryNotFoundException.class)
  public ResponseEntity<Map<String, String>> handleNotFound(RepositoryNotFoundException e) {
    return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("message", e.getMessage()));
  }

  @ExceptionHandler(RepositoryValidationException.class)
  public ResponseEntity<Map<String, String>> handleValidation(RepositoryValidationException e) {
    return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("message", e.getMessage()));
  }

  public record MembersRequest(List<String> memberNames) {
  }

  public record AptRebuildRequest(String distribution) {
  }

  public record AptSigningKeyRequest(String privateKey, String passphrase, Boolean generate) {
  }

  private RepositoryView requireAptRepository(String name) {
    RepositoryView view = service.get(name);
    if (view.format() != RepositoryFormat.APT || view.type() == RepositoryType.GROUP) {
      throw new RepositoryValidationException("Repository is not an APT hosted/proxy repository");
    }
    return view;
  }

  private AptService apt() {
    if (aptService == null) {
      throw new ResponseStatusException(
          HttpStatus.SERVICE_UNAVAILABLE, "APT repository service is unavailable");
    }
    return aptService;
  }

  private RepositoryRuntime aptRuntime(String name) {
    if (runtimeRegistry == null) {
      throw new ResponseStatusException(
          HttpStatus.SERVICE_UNAVAILABLE, "Repository runtime registry is unavailable");
    }
    return runtimeRegistry.resolve(name)
        .orElseThrow(() -> new RepositoryNotFoundException("Repository not found: " + name));
  }

  private AuthenticatedSubject requireAuthenticated(HttpServletRequest request) {
    Optional<AuthenticatedSubject> authenticated = currentSubject(request)
        .or(() -> authenticationService.authenticate(request));
    if (authenticated.isEmpty()) {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required");
    }
    request.setAttribute(AuthenticatedSubject.REQUEST_ATTRIBUTE, authenticated.get());
    return authenticated.get();
  }

  private Optional<AuthenticatedSubject> currentOrAnonymous(HttpServletRequest request) {
    Optional<AuthenticatedSubject> authenticated = currentSubject(request)
        .or(() -> authenticationService.authenticate(request));
    if (authenticated.isPresent()) {
      request.setAttribute(AuthenticatedSubject.REQUEST_ATTRIBUTE, authenticated.get());
      return authenticated;
    }
    return authenticationService.authenticateAnonymous();
  }

  private Optional<AuthenticatedSubject> currentSubject(HttpServletRequest request) {
    Object subject = request.getAttribute(AuthenticatedSubject.REQUEST_ATTRIBUTE);
    if (subject instanceof AuthenticatedSubject authenticated
        && authenticated.userId() != null
        && !authenticated.userId().isBlank()) {
      return Optional.of(authenticated);
    }
    return Optional.empty();
  }

  private void requireRepositoryAdmin(
      AuthenticatedSubject subject,
      RepositoryFormat format,
      String repository,
      String action) {
    AccessDecision decision = repositoryAdminDecision(subject, format, repository, action);
    if (!decision.allowed()) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, decision.reason());
    }
  }

  private boolean repositoryAdminAllowed(
      AuthenticatedSubject subject,
      RepositoryFormat format,
      String repository,
      String action) {
    return repositoryAdminDecision(subject, format, repository, action).allowed();
  }

  private boolean repositoryBrowseAllowed(AuthenticatedSubject subject, RepositoryView repository) {
    return repositoryActionAllowed(subject, repository, PermissionAction.BROWSE);
  }

  private boolean allRepositoryAdminAllowed(AuthenticatedSubject subject, String action) {
    return repositoryAdminDecision(subject, null, null, action).allowed();
  }

  private boolean allRepositoryActionAllowed(AuthenticatedSubject subject, PermissionAction action) {
    String permission = "nexus:repository-view:*:*:" + action.nexusAction();
    return securityService.decide(subject.permissionSubject(), permission).allowed();
  }

  private boolean repositoryActionAllowed(
      AuthenticatedSubject subject,
      RepositoryView repository,
      PermissionAction action) {
    return securityService.decide(
        subject.permissionSubject(),
        new RepositoryPermission(repository.name(), repository.format(), "", action)).allowed();
  }

  private boolean applicationPermissionAllowed(AuthenticatedSubject subject, String permission) {
    return securityService.decide(subject.permissionSubject(), permission).allowed();
  }

  private AccessDecision repositoryAdminDecision(
      AuthenticatedSubject subject,
      RepositoryFormat format,
      String repository,
      String action) {
    String permission = "nexus:repository-admin:"
        + nexusFormat(format)
        + ":"
        + defaultString(repository, "*")
        + ":"
        + defaultString(action, "*");
    return securityService.decide(subject.permissionSubject(), permission);
  }

  private static String nexusFormat(RepositoryFormat format) {
    if (format == null) {
      return "*";
    }
    return format.name().toLowerCase(java.util.Locale.ROOT);
  }

  private static RepositoryFormat formatForCreate(CreateCommand command) {
    if (command == null) {
      return null;
    }
    return RepositoryRecipes.byName(command.recipe())
        .map(RepositoryRecipe::format)
        .orElse(null);
  }

  private static String defaultString(String value, String fallback) {
    return value == null || value.isBlank() ? fallback : value;
  }
}
