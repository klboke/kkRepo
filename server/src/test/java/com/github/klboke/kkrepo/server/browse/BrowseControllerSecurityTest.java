package com.github.klboke.kkrepo.server.browse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.klboke.kkrepo.auth.AccessDecision;
import com.github.klboke.kkrepo.auth.PermissionSubject;
import com.github.klboke.kkrepo.auth.RepositoryPermission;
import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.core.RepositoryType;
import com.github.klboke.kkrepo.persistence.mysql.dao.BrowseNodeDao;
import com.github.klboke.kkrepo.persistence.mysql.dao.RepositoryDao;
import com.github.klboke.kkrepo.persistence.mysql.dao.SecurityDao;
import com.github.klboke.kkrepo.persistence.mysql.model.RepositoryRecord;
import com.github.klboke.kkrepo.server.security.AuthenticatedSubject;
import com.github.klboke.kkrepo.server.security.SecurityAuthenticationService;
import com.github.klboke.kkrepo.server.security.SecurityManagementService;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletRequest;
import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class BrowseControllerSecurityTest {

  @Test
  void listRequiresRepositoryBrowsePermissionForRequestedPath() {
    RepositoryRecord repository = repo(1L, "maven-public", RepositoryFormat.MAVEN2, RepositoryType.GROUP);
    RepositoryRecord member = repo(2L, "maven-central", RepositoryFormat.MAVEN2, RepositoryType.PROXY);
    StubRepositoryDao repositories = new StubRepositoryDao(Map.of(repository.name(), repository));
    repositories.members.put(repository.id(), List.of(member));
    StubBrowseNodeDao browseNodes = new StubBrowseNodeDao();
    browseNodes.children.put(key(member.id(), "junit/junit"), List.of(child(
        "junit/junit/4.13.2",
        "4.13.2",
        false)));
    RecordingSecurityService security = new RecordingSecurityService(permission ->
        permission.equals("nexus:repository-view:maven2:maven-public:browse")
            ? AccessDecision.allow()
            : AccessDecision.deny("missing permission"));
    BrowseController controller = controller(repositories, browseNodes, subject("alice"), null, security);

    BrowseController.BrowseListing listing = controller.list(
        "maven-public",
        "/junit/junit/",
        request("GET", "/internal/browse/maven-public"));

    assertEquals("junit/junit", listing.path());
    assertEquals(List.of("4.13.2"), listing.entries().stream().map(BrowseController.BrowseEntry::name).toList());
    assertEquals(List.of(key(member.id(), "junit/junit")), browseNodes.calls);
    assertEquals("junit/junit", security.repositoryPermissions.get(0).pathPattern());
    assertEquals(List.of("nexus:repository-view:maven2:maven-public:browse"), security.permissions);
  }

  @Test
  void listDoesNotReadBrowseNodesWhenBrowsePermissionIsDenied() {
    RepositoryRecord repository = repo(1L, "npm-group", RepositoryFormat.NPM, RepositoryType.GROUP);
    StubRepositoryDao repositories = new StubRepositoryDao(Map.of(repository.name(), repository));
    StubBrowseNodeDao browseNodes = new StubBrowseNodeDao();
    RecordingSecurityService security = new RecordingSecurityService(permission ->
        AccessDecision.deny("missing permission"));
    BrowseController controller = controller(repositories, browseNodes, subject("alice"), null, security);

    ResponseStatusException thrown = assertThrows(ResponseStatusException.class,
        () -> controller.list("npm-group", "", request("GET", "/internal/browse/npm-group")));

    assertEquals(HttpStatus.FORBIDDEN, thrown.getStatusCode());
    assertEquals(List.of(), browseNodes.calls);
    assertEquals(List.of("nexus:repository-view:npm:npm-group:browse"), security.permissions);
  }

  @Test
  void listCanUseAnonymousSubjectWhenAnonymousAccessIsConfigured() {
    RepositoryRecord repository = repo(1L, "pypi-group", RepositoryFormat.PYPI, RepositoryType.GROUP);
    RepositoryRecord member = repo(2L, "pypi-proxy", RepositoryFormat.PYPI, RepositoryType.PROXY);
    StubRepositoryDao repositories = new StubRepositoryDao(Map.of(repository.name(), repository));
    repositories.members.put(repository.id(), List.of(member));
    StubBrowseNodeDao browseNodes = new StubBrowseNodeDao();
    browseNodes.children.put(key(member.id(), "packages"), List.of(child("packages/sample", "sample", false)));
    RecordingSecurityService security = new RecordingSecurityService(permission -> AccessDecision.allow());
    BrowseController controller = controller(repositories, browseNodes, null, subject("anonymous"), security);

    BrowseController.BrowseListing listing = controller.list(
        "pypi-group",
        "",
        request("GET", "/internal/browse/pypi-group"));

    assertEquals("", listing.path());
    assertEquals(List.of("sample"), listing.entries().stream().map(BrowseController.BrowseEntry::name).toList());
    assertEquals(List.of(key(member.id(), "packages")), browseNodes.calls);
    assertEquals(List.of("nexus:repository-view:pypi:pypi-group:browse"), security.permissions);
  }

  @Test
  void cargoRootBrowseIncludesDynamicConfigEntry() {
    RepositoryRecord repository = repo(1L, "cargo-hosted", RepositoryFormat.CARGO, RepositoryType.HOSTED);
    StubRepositoryDao repositories = new StubRepositoryDao(Map.of(repository.name(), repository));
    StubBrowseNodeDao browseNodes = new StubBrowseNodeDao();
    browseNodes.children.put(key(repository.id(), ""), List.of(child("kk", "kk", false)));
    RecordingSecurityService security = new RecordingSecurityService(permission -> AccessDecision.allow());
    BrowseController controller = controller(repositories, browseNodes, subject("alice"), null, security);

    BrowseController.BrowseListing listing = controller.list(
        "cargo-hosted",
        "",
        request("GET", "/internal/browse/cargo-hosted"));

    assertEquals("", listing.path());
    assertEquals(List.of("kk", "config.json"), listing.entries().stream().map(BrowseController.BrowseEntry::name).toList());
    BrowseController.BrowseEntry config = listing.entries().stream()
        .filter(entry -> "config.json".equals(entry.name()))
        .findFirst()
        .orElseThrow();
    assertTrue(config.leaf());
    assertEquals("/repository/cargo-hosted/config.json", config.downloadUrl());
    assertEquals("application/json", config.contentType());
    assertEquals(List.of(key(repository.id(), "")), browseNodes.calls);
  }

  @Test
  void cargoDynamicConfigIsOnlyAddedAtRoot() {
    RepositoryRecord repository = repo(1L, "cargo-hosted", RepositoryFormat.CARGO, RepositoryType.HOSTED);
    StubRepositoryDao repositories = new StubRepositoryDao(Map.of(repository.name(), repository));
    StubBrowseNodeDao browseNodes = new StubBrowseNodeDao();
    browseNodes.children.put(key(repository.id(), "kk"), List.of(child("kk/re", "re", false)));
    RecordingSecurityService security = new RecordingSecurityService(permission -> AccessDecision.allow());
    BrowseController controller = controller(repositories, browseNodes, subject("alice"), null, security);

    BrowseController.BrowseListing listing = controller.list(
        "cargo-hosted",
        "kk",
        request("GET", "/internal/browse/cargo-hosted"));

    assertEquals("kk", listing.path());
    assertEquals(List.of("re"), listing.entries().stream().map(BrowseController.BrowseEntry::name).toList());
  }

  @Test
  void composerBrowseHidesInternalRouteAssetsAndRejectsDirectAccess() {
    RepositoryRecord repository = repo(1L, "composer-proxy", RepositoryFormat.COMPOSER, RepositoryType.PROXY);
    StubRepositoryDao repositories = new StubRepositoryDao(Map.of(repository.name(), repository));
    StubBrowseNodeDao browseNodes = new StubBrowseNodeDao();
    browseNodes.children.put(key(repository.id(), ""), List.of(
        child("_composer", "_composer", false),
        child("dists", "dists", false),
        child("packages.json", "packages.json", true)));
    RecordingSecurityService security = new RecordingSecurityService(permission -> AccessDecision.allow());
    BrowseController controller = controller(repositories, browseNodes, subject("alice"), null, security);

    BrowseController.BrowseListing listing = controller.list(
        "composer-proxy", "", request("GET", "/internal/browse/composer-proxy"));

    assertEquals(List.of("dists", "packages.json"),
        listing.entries().stream().map(BrowseController.BrowseEntry::name).toList());
    ResponseStatusException listingError = assertThrows(ResponseStatusException.class,
        () -> controller.list(
            "composer-proxy", "_composer/routes", request("GET", "/internal/browse/composer-proxy")));
    assertEquals(HttpStatus.NOT_FOUND, listingError.getStatusCode());
    ResponseStatusException detailError = assertThrows(ResponseStatusException.class,
        () -> controller.attributes(
            "composer-proxy", "_composer/routes/token.json", null,
            request("GET", "/internal/browse/composer-proxy/attributes")));
    assertEquals(HttpStatus.NOT_FOUND, detailError.getStatusCode());
  }

  @Test
  void listRejectsWhenNoAuthenticatedOrAnonymousSubjectExists() {
    RepositoryRecord repository = repo(1L, "maven-public", RepositoryFormat.MAVEN2, RepositoryType.GROUP);
    StubRepositoryDao repositories = new StubRepositoryDao(Map.of(repository.name(), repository));
    RecordingSecurityService security = new RecordingSecurityService(permission -> AccessDecision.allow());
    BrowseController controller = controller(repositories, new StubBrowseNodeDao(), null, null, security);

    ResponseStatusException thrown = assertThrows(ResponseStatusException.class,
        () -> controller.list("maven-public", "", request("GET", "/internal/browse/maven-public")));

    assertEquals(HttpStatus.UNAUTHORIZED, thrown.getStatusCode());
    assertEquals(List.of(), security.permissions);
  }

  private static BrowseController controller(
      StubRepositoryDao repositories,
      StubBrowseNodeDao browseNodes,
      AuthenticatedSubject authenticated,
      AuthenticatedSubject anonymous,
      RecordingSecurityService security) {
    return new BrowseController(
        repositories,
        browseNodes,
        null,
        null,
        new StubAuthenticationService(authenticated, anonymous),
        security);
  }

  private static BrowseNodeDao.BrowseChild child(String path, String displayName, boolean leaf) {
    return new BrowseNodeDao.BrowseChild(
        1L,
        path,
        displayName,
        path.isBlank() ? 0 : path.split("/").length - 1,
        leaf ? 1L : null,
        leaf ? 1L : null,
        leaf ? 100L : null,
        leaf ? "application/octet-stream" : null,
        leaf ? "sha1" : null,
        leaf ? Instant.parse("2026-01-01T00:00:00Z") : null,
        !leaf,
        true);
  }

  private static RepositoryRecord repo(long id, String name, RepositoryFormat format, RepositoryType type) {
    return new RepositoryRecord(
        id,
        name,
        format,
        type,
        format.name().toLowerCase(Locale.ROOT) + "-" + type.name().toLowerCase(Locale.ROOT),
        true,
        1L,
        null,
        null,
        null,
        null,
        null,
        true,
        Map.of());
  }

  private static AuthenticatedSubject subject(String userId) {
    return new AuthenticatedSubject(
        "Local",
        userId,
        "local",
        null,
        new PermissionSubject("Local", userId, Set.of(), null));
  }

  private static String key(long repositoryId, String path) {
    return repositoryId + "|" + path;
  }

  private static HttpServletRequest request(String method, String uri) {
    Map<String, Object> attributes = new LinkedHashMap<>();
    return (HttpServletRequest) Proxy.newProxyInstance(
        BrowseControllerSecurityTest.class.getClassLoader(),
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

  private static class StubRepositoryDao extends RepositoryDao {
    private final Map<String, RepositoryRecord> repositories;
    private final Map<Long, List<RepositoryRecord>> members = new LinkedHashMap<>();

    private StubRepositoryDao(Map<String, RepositoryRecord> repositories) {
      super(null, null);
      this.repositories = repositories;
    }

    @Override
    public Optional<RepositoryRecord> findByName(String name) {
      return Optional.ofNullable(repositories.get(name));
    }

    @Override
    public List<RepositoryRecord> listMembers(long groupRepositoryId) {
      return members.getOrDefault(groupRepositoryId, List.of());
    }
  }

  private static class StubBrowseNodeDao extends BrowseNodeDao {
    private final Map<String, List<BrowseChild>> children = new LinkedHashMap<>();
    private final List<String> calls = new ArrayList<>();

    private StubBrowseNodeDao() {
      super(null);
    }

    @Override
    public List<BrowseChild> listChildren(long repositoryId, String parentPath) {
      String key = key(repositoryId, parentPath == null ? "" : parentPath);
      calls.add(key);
      return children.getOrDefault(key, List.of());
    }
  }

  private static class StubAuthenticationService extends SecurityAuthenticationService {
    private final AuthenticatedSubject authenticated;
    private final AuthenticatedSubject anonymous;

    private StubAuthenticationService(AuthenticatedSubject authenticated, AuthenticatedSubject anonymous) {
      super(new SecurityDao(null, null), new ObjectMapper(), "X-Nexus-Plus-Token");
      this.authenticated = authenticated;
      this.anonymous = anonymous;
    }

    @Override
    public Optional<AuthenticatedSubject> authenticate(HttpServletRequest request) {
      return Optional.ofNullable(authenticated);
    }

    @Override
    public Optional<AuthenticatedSubject> authenticateAnonymous() {
      return Optional.ofNullable(anonymous);
    }
  }

  private static class RecordingSecurityService extends SecurityManagementService {
    private final Function<String, AccessDecision> decisions;
    private final List<String> permissions = new ArrayList<>();
    private final List<RepositoryPermission> repositoryPermissions = new ArrayList<>();

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
      repositoryPermissions.add(permission);
      String requestedPermission = repositoryPermissionString(permission);
      permissions.add(requestedPermission);
      return decisions.apply(requestedPermission);
    }

    private static String repositoryPermissionString(RepositoryPermission permission) {
      String format;
      if (permission.format() == null) {
        format = "*";
      } else {
        format = permission.format().name().toLowerCase(Locale.ROOT);
      }
      String repository = permission.repository() == null || permission.repository().isBlank()
          ? "*"
          : permission.repository();
      String action = permission.action() == null ? "read" : permission.action().nexusAction();
      return "nexus:repository-view:" + format + ":" + repository + ":" + action;
    }
  }
}
