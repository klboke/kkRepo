package com.github.klboke.kkrepo.server.repositories;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.klboke.kkrepo.auth.AccessDecision;
import com.github.klboke.kkrepo.auth.PermissionSubject;
import com.github.klboke.kkrepo.auth.RepositoryPermission;
import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.core.RepositoryRecipe;
import com.github.klboke.kkrepo.core.RepositoryRecipes;
import com.github.klboke.kkrepo.core.RepositoryType;
import com.github.klboke.kkrepo.persistence.mysql.dao.SecurityDao;
import com.github.klboke.kkrepo.server.repositories.RepositoryCommands.CreateCommand;
import com.github.klboke.kkrepo.server.repositories.RepositoryCommands.UpdateCommand;
import com.github.klboke.kkrepo.server.security.AuthenticatedSubject;
import com.github.klboke.kkrepo.server.security.SecurityAuthenticationService;
import com.github.klboke.kkrepo.server.security.SecurityManagementService;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletRequest;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class RepositoriesControllerSecurityTest {

  @Test
  void adminListOnlyReturnsRepositoriesWithRepositoryAdminReadPermission() {
    StubRepositoryService repositories = new StubRepositoryService();
    repositories.repositories = List.of(
        repo("maven-public", RepositoryFormat.MAVEN2),
        repo("npm-group", RepositoryFormat.NPM));
    RecordingSecurityService security = new RecordingSecurityService(permission ->
        permission.equals("nexus:repository-admin:maven2:maven-public:read")
            ? AccessDecision.allow()
            : AccessDecision.deny("missing permission"));
    RepositoriesController controller = controller(repositories, subject("alice"), security);

    List<RepositoryView> visible = controller.list("admin", request("GET", "/internal/repositories"));

    assertEquals(List.of("maven-public"), visible.stream().map(RepositoryView::name).toList());
    assertEquals(List.of(
            "nexus:repository-admin:*:*:read",
            "nexus:repository-admin:maven2:maven-public:read",
            "nexus:repository-admin:npm:npm-group:read"),
        security.permissions);
  }

  @Test
  void adminListSkipsPerRepositoryChecksWhenWildcardReadIsAllowed() {
    StubRepositoryService repositories = new StubRepositoryService();
    repositories.repositories = List.of(
        repo("maven-public", RepositoryFormat.MAVEN2),
        repo("npm-group", RepositoryFormat.NPM));
    RecordingSecurityService security = new RecordingSecurityService(permission ->
        permission.equals("nexus:repository-admin:*:*:read")
            ? AccessDecision.allow()
            : AccessDecision.deny("missing permission"));
    RepositoriesController controller = controller(repositories, subject("admin"), security);

    List<RepositoryView> visible = controller.list("admin", request("GET", "/internal/repositories"));

    assertEquals(List.of("maven-public", "npm-group"), visible.stream().map(RepositoryView::name).toList());
    assertEquals(List.of("nexus:repository-admin:*:*:read"), security.permissions);
  }

  @Test
  void browseListOnlyReturnsRepositoriesWithBrowsePermission() {
    StubRepositoryService repositories = new StubRepositoryService();
    repositories.repositories = List.of(
        repo("maven-public", RepositoryFormat.MAVEN2),
        repo("npm-group", RepositoryFormat.NPM));
    RecordingSecurityService security = new RecordingSecurityService(permission ->
        permission.equals("nexus:repository-view:npm:npm-group:browse")
            ? AccessDecision.allow()
            : AccessDecision.deny("missing permission"));
    RepositoriesController controller = controller(repositories, subject("alice"), security);

    List<RepositoryView> visible = controller.list(null, request("GET", "/internal/repositories"));

    assertEquals(List.of("npm-group"), visible.stream().map(RepositoryView::name).toList());
    assertEquals(List.of(
            "nexus:repository-view:*:*:browse",
            "nexus:repository-view:maven2:maven-public:browse",
            "nexus:repository-view:npm:npm-group:browse"),
        security.permissions);
  }

  @Test
  void browseListSkipsPerRepositoryChecksWhenWildcardBrowseIsAllowed() {
    StubRepositoryService repositories = new StubRepositoryService();
    repositories.repositories = List.of(
        repo("maven-public", RepositoryFormat.MAVEN2),
        repo("npm-group", RepositoryFormat.NPM));
    RecordingSecurityService security = new RecordingSecurityService(permission ->
        permission.equals("nexus:repository-view:*:*:browse")
            ? AccessDecision.allow()
            : AccessDecision.deny("missing permission"));
    RepositoriesController controller = controller(repositories, subject("alice"), security);

    List<RepositoryView> visible = controller.list(null, request("GET", "/internal/repositories"));

    assertEquals(List.of("maven-public", "npm-group"), visible.stream().map(RepositoryView::name).toList());
    assertEquals(List.of("nexus:repository-view:*:*:browse"), security.permissions);
  }

  @Test
  void uploadableRequiresRepositoryEditPermission() {
    StubRepositoryService repositories = new StubRepositoryService();
    repositories.repositories = List.of(
        repo("maven-releases", RepositoryFormat.MAVEN2),
        repo("npm-hosted", RepositoryFormat.NPM),
        repo("npm-proxy", RepositoryFormat.NPM, RepositoryType.PROXY, true),
        repo("pypi-hosted", RepositoryFormat.PYPI, RepositoryType.HOSTED, false));
    RecordingSecurityService security = new RecordingSecurityService(permission ->
        permission.equals("nexus:repository-view:npm:npm-hosted:edit")
            ? AccessDecision.allow()
            : AccessDecision.deny("missing permission"));
    RepositoriesController controller = controller(repositories, subject("alice"), security);

    List<RepositoryView> visible = controller.uploadable(request("GET", "/internal/repositories/uploadable"));

    assertEquals(List.of("npm-hosted"), visible.stream().map(RepositoryView::name).toList());
    assertEquals(List.of(
            "nexus:repository-view:*:*:edit",
            "nexus:repository-view:maven2:maven-releases:edit",
            "nexus:repository-view:npm:npm-hosted:edit"),
        security.permissions);
  }

  @Test
  void uploadableSkipsPerRepositoryChecksWhenWildcardEditIsAllowed() {
    StubRepositoryService repositories = new StubRepositoryService();
    repositories.repositories = List.of(
        repo("maven-releases", RepositoryFormat.MAVEN2),
        repo("npm-hosted", RepositoryFormat.NPM),
        repo("npm-proxy", RepositoryFormat.NPM, RepositoryType.PROXY, true),
        repo("pypi-hosted", RepositoryFormat.PYPI, RepositoryType.HOSTED, false));
    RecordingSecurityService security = new RecordingSecurityService(permission ->
        permission.equals("nexus:repository-view:*:*:edit")
            ? AccessDecision.allow()
            : AccessDecision.deny("missing permission"));
    RepositoriesController controller = controller(repositories, subject("alice"), security);

    List<RepositoryView> visible = controller.uploadable(request("GET", "/internal/repositories/uploadable"));

    assertEquals(List.of("maven-releases", "npm-hosted"), visible.stream().map(RepositoryView::name).toList());
    assertEquals(List.of("nexus:repository-view:*:*:edit"), security.permissions);
  }

  @Test
  void uploadableReturnsEmptyWhenRepositoryEditPermissionIsDenied() {
    StubRepositoryService repositories = new StubRepositoryService();
    repositories.repositories = List.of(repo("npm-hosted", RepositoryFormat.NPM));
    RecordingSecurityService security = new RecordingSecurityService(permission -> AccessDecision.deny("missing permission"));
    RepositoriesController controller = controller(repositories, subject("alice"), security);

    List<RepositoryView> visible = controller.uploadable(request("GET", "/internal/repositories/uploadable"));

    assertEquals(List.of(), visible);
    assertEquals(List.of(
            "nexus:repository-view:*:*:edit",
            "nexus:repository-view:npm:npm-hosted:edit"),
        security.permissions);
  }

  @Test
  void createRequiresRepositoryAdminAddForRecipeFormatAndName() {
    StubRepositoryService repositories = new StubRepositoryService();
    RecordingSecurityService security = new RecordingSecurityService(permission -> AccessDecision.allow());
    RepositoriesController controller = controller(repositories, subject("admin"), security);

    controller.create(new CreateCommand("npm-hosted", "npm-hosted", true, "default",
        true, null, null, null, null, null, null), request("POST", "/internal/repositories"));

    assertEquals("nexus:repository-admin:npm:npm-hosted:add", security.permissions.get(0));
    assertEquals("npm-hosted", repositories.created.name());
  }

  @Test
  void updateRequiresRepositoryAdminEditForExistingRepository() {
    StubRepositoryService repositories = new StubRepositoryService();
    repositories.repositories = List.of(repo("maven-public", RepositoryFormat.MAVEN2));
    RecordingSecurityService security = new RecordingSecurityService(permission -> AccessDecision.allow());
    RepositoriesController controller = controller(repositories, subject("admin"), security);

    controller.update("maven-public", new UpdateCommand(true, null, null, null, null, null, null, null, null),
        request("PUT", "/internal/repositories/maven-public"));

    assertEquals("nexus:repository-admin:maven2:maven-public:edit", security.permissions.get(0));
    assertEquals("maven-public", repositories.updatedName);
  }

  @Test
  void deleteDoesNotRunWhenRepositoryAdminDeletePermissionIsDenied() {
    StubRepositoryService repositories = new StubRepositoryService();
    repositories.repositories = List.of(repo("maven-public", RepositoryFormat.MAVEN2));
    RecordingSecurityService security = new RecordingSecurityService(permission -> AccessDecision.deny("missing permission"));
    RepositoriesController controller = controller(repositories, subject("admin"), security);

    ResponseStatusException thrown = assertThrows(ResponseStatusException.class,
        () -> controller.delete("maven-public", request("DELETE", "/internal/repositories/maven-public")));

    assertEquals(HttpStatus.FORBIDDEN, thrown.getStatusCode());
    assertEquals("nexus:repository-admin:maven2:maven-public:delete", security.permissions.get(0));
    assertEquals(null, repositories.deletedName);
  }

  @Test
  void unauthenticatedRepositoryManagementRequestsAreRejected() {
    RepositoriesController controller = controller(
        new StubRepositoryService(),
        null,
        new RecordingSecurityService(permission -> AccessDecision.allow()));

    ResponseStatusException thrown = assertThrows(ResponseStatusException.class,
        () -> controller.recipes(request("GET", "/internal/repositories/recipes")));

    assertEquals(HttpStatus.UNAUTHORIZED, thrown.getStatusCode());
  }

  private static RepositoriesController controller(
      StubRepositoryService repositories,
      AuthenticatedSubject subject,
      RecordingSecurityService security) {
    return new RepositoriesController(
        repositories,
        new StubAuthenticationService(subject),
        security);
  }

  private static RepositoryView repo(String name, RepositoryFormat format) {
    return repo(name, format, RepositoryType.HOSTED, true);
  }

  private static RepositoryView repo(String name, RepositoryFormat format, RepositoryType type, boolean online) {
    String recipe = RepositoryRecipes.list().stream()
        .filter(candidate -> candidate.format() == format)
        .filter(candidate -> candidate.type() == type)
        .findFirst()
        .map(RepositoryRecipe::name)
        .orElse(format.name().toLowerCase(java.util.Locale.ROOT) + "-hosted");
    return new RepositoryView(
        1L,
        name,
        recipe,
        format,
        type,
        online,
        "default",
        true,
        "/repository/" + name + "/",
        null,
        null,
        null,
        null,
        null,
        null);
  }

  private static AuthenticatedSubject subject(String userId) {
    return new AuthenticatedSubject(
        "Local",
        userId,
        "local",
        null,
        new PermissionSubject("Local", userId, Set.of(), null));
  }

  private static HttpServletRequest request(String method, String uri) {
    Map<String, Object> attributes = new LinkedHashMap<>();
    return (HttpServletRequest) Proxy.newProxyInstance(
        RepositoriesControllerSecurityTest.class.getClassLoader(),
        new Class<?>[] {HttpServletRequest.class},
        (proxy, invoked, args) -> switch (invoked.getName()) {
          case "getMethod" -> method;
          case "getRequestURI" -> uri;
          case "getContextPath" -> "";
          case "getDispatcherType" -> DispatcherType.REQUEST;
          case "getAttribute" -> attributes.get(String.valueOf(args[0]));
          case "setAttribute" -> {
            attributes.put(String.valueOf(args[0]), args[1]);
            yield null;
          }
          case "removeAttribute" -> {
            attributes.remove(String.valueOf(args[0]));
            yield null;
          }
          case "toString" -> method + " " + uri;
          default -> primitiveDefault(invoked.getReturnType());
        });
  }

  private static Object primitiveDefault(Class<?> type) {
    if (boolean.class.equals(type)) {
      return false;
    }
    if (int.class.equals(type) || long.class.equals(type) || short.class.equals(type) || byte.class.equals(type)) {
      return 0;
    }
    if (char.class.equals(type)) {
      return '\0';
    }
    return null;
  }

  private static class StubAuthenticationService extends SecurityAuthenticationService {
    private final AuthenticatedSubject subject;

    private StubAuthenticationService(AuthenticatedSubject subject) {
      super(new SecurityDao(null, null), new ObjectMapper(), "X-Nexus-Plus-Token");
      this.subject = subject;
    }

    @Override
    public Optional<AuthenticatedSubject> authenticate(HttpServletRequest request) {
      return Optional.ofNullable(subject);
    }

    @Override
    public Optional<AuthenticatedSubject> authenticateAnonymous() {
      return Optional.empty();
    }
  }

  private static class RecordingSecurityService extends SecurityManagementService {
    private final Function<String, AccessDecision> decisions;
    private final List<String> permissions = new ArrayList<>();

    private RecordingSecurityService(Function<String, AccessDecision> decisions) {
      super(new SecurityDao(null, null));
      this.decisions = decisions;
    }

    @Override
    public AccessDecision decide(PermissionSubject subject, String requestedPermission) {
      permissions.add(requestedPermission);
      return decisions.apply(requestedPermission);
    }

    @Override
    public AccessDecision decide(PermissionSubject subject, RepositoryPermission permission) {
      String requestedPermission = repositoryPermissionString(permission);
      permissions.add(requestedPermission);
      return decisions.apply(requestedPermission);
    }

    private static String repositoryPermissionString(RepositoryPermission permission) {
      String format;
      if (permission.format() == null) {
        format = "*";
      } else {
        format = permission.format().name().toLowerCase(java.util.Locale.ROOT);
      }
      String repository = permission.repository() == null || permission.repository().isBlank()
          ? "*"
          : permission.repository();
      String action = permission.action() == null ? "read" : permission.action().nexusAction();
      return "nexus:repository-view:" + format + ":" + repository + ":" + action;
    }
  }

  private static class StubRepositoryService extends RepositoryService {
    private List<RepositoryView> repositories = List.of();
    private CreateCommand created;
    private String updatedName;
    private String deletedName;

    private StubRepositoryService() {
      super(null, null, null, null, "/repository");
    }

    @Override
    public List<RepositoryView> list() {
      return repositories;
    }

    @Override
    public RepositoryView get(String name) {
      return repositories.stream()
          .filter(repository -> repository.name().equals(name))
          .findFirst()
          .orElseThrow(() -> new RepositoryNotFoundException(name));
    }

    @Override
    public RepositoryView create(CreateCommand command) {
      created = command;
      return repo(command.name(), RepositoryRecipes.byName(command.recipe()).map(RepositoryRecipe::format).orElse(RepositoryFormat.NPM));
    }

    @Override
    public RepositoryView update(String name, UpdateCommand command) {
      updatedName = name;
      return get(name);
    }

    @Override
    public void delete(String name) {
      deletedName = name;
    }

    @Override
    public RepositoryView replaceMembers(String name, List<String> memberNames) {
      updatedName = name;
      return get(name);
    }

    @Override
    public List<RepositoryRecipe> recipes() {
      return RepositoryRecipes.list();
    }
  }
}
