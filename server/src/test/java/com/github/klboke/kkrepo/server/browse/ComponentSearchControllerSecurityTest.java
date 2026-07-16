package com.github.klboke.kkrepo.server.browse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.klboke.kkrepo.auth.AccessDecision;
import com.github.klboke.kkrepo.auth.PermissionSubject;
import com.github.klboke.kkrepo.auth.RepositoryPermission;
import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.persistence.jdbc.api.ComponentDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.SecurityDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.SwiftRegistryDao;
import com.github.klboke.kkrepo.server.security.AuthenticatedSubject;
import com.github.klboke.kkrepo.server.security.SecurityAuthenticationService;
import com.github.klboke.kkrepo.server.security.SecurityManagementService;
import com.github.klboke.kkrepo.server.support.dao.ComponentDaoAdapter;
import com.github.klboke.kkrepo.server.support.dao.SecurityDaoAdapter;
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

class ComponentSearchControllerSecurityTest {

  @Test
  void searchRequiresGlobalSearchReadPermission() {
    StubComponentDao components = new StubComponentDao();
    RecordingSecurityService security = new RecordingSecurityService(permission ->
        AccessDecision.deny("missing permission"));
    ComponentSearchController controller = controller(components, subject("alice"), null, security);

    ResponseStatusException thrown = assertThrows(ResponseStatusException.class,
        () -> controller.search("junit", null, null, request("GET", "/internal/search/components")));

    assertEquals(HttpStatus.FORBIDDEN, thrown.getStatusCode());
    assertEquals(List.of("nexus:search:read"), security.permissions);
    assertEquals(List.of(), components.calls);
  }

  @Test
  void searchFiltersRowsByRepositoryBrowsePermission() {
    StubComponentDao components = new StubComponentDao();
    components.rows = List.of(
        row(1L, "maven-public", RepositoryFormat.MAVEN2, "junit", "junit", "4.13.2"),
        row(2L, "npm-group", RepositoryFormat.NPM, null, "is-number", "7.0.0"));
    RecordingSecurityService security = new RecordingSecurityService(permission ->
        permission.equals("nexus:search:read")
            || permission.equals("nexus:repository-view:npm:npm-group:browse")
            ? AccessDecision.allow()
            : AccessDecision.deny("missing permission"));
    ComponentSearchController controller = controller(components, subject("alice"), null, security);

    ComponentSearchController.ComponentSearchResponse response = controller.search(
        "is",
        "custom",
        25,
        request("GET", "/internal/search/components"));

    assertEquals(25, response.limit());
    assertEquals(1, response.count());
    assertEquals(List.of("npm-group"), response.items().stream()
        .map(ComponentSearchController.ComponentSearchItem::repository)
        .toList());
    assertEquals(List.of("is|null|25"), components.calls);
    assertEquals(List.of(
            "nexus:search:read",
            "nexus:repository-view:maven2:maven-public:browse",
            "nexus:repository-view:npm:npm-group:browse"),
        security.permissions);
  }

  @Test
  void searchHidesComposerInternalRouteComponents() {
    StubComponentDao components = new StubComponentDao();
    components.rows = List.of(
        row(1L, "composer-proxy", RepositoryFormat.COMPOSER, "/_composer/routes",
            "_composer/routes/route-token.json", null),
        row(2L, "composer-proxy", RepositoryFormat.COMPOSER, "/p2/psr", "p2/psr/log.json", null),
        row(3L, "composer-hosted", RepositoryFormat.COMPOSER, "psr", "psr/log", "3.0.2",
            "composer-package", "psr/log/3.0.2/psr-log-3.0.2.zip"));
    RecordingSecurityService security = new RecordingSecurityService(permission -> AccessDecision.allow());
    ComponentSearchController controller = controller(components, subject("alice"), null, security);

    ComponentSearchController.ComponentSearchResponse response = controller.search(
        null,
        "composer",
        null,
        request("GET", "/internal/search/components"));

    assertEquals(2, response.count());
    assertEquals(List.of("p2/psr/log.json", "psr/log"), response.items().stream()
        .map(ComponentSearchController.ComponentSearchItem::name)
        .toList());
    assertEquals(List.of("p2/psr/log.json", "psr/log/3.0.2/psr-log-3.0.2.zip"), response.items().stream()
        .map(ComponentSearchController.ComponentSearchItem::browsePath)
        .toList());
  }

  @Test
  void searchHidesTerraformPhysicalComponentsAndReturnsLogicalBrowsePath() {
    StubComponentDao components = new StubComponentDao();
    components.rows = List.of(
        row(1L, "terraform-proxy", RepositoryFormat.TERRAFORM, null,
            "route-token.json", null, ".terraform/routes/route-token.json"),
        row(2L, "terraform-proxy", RepositoryFormat.TERRAFORM, "acme",
            "v1/providers/acme/cloud/1.2.3/package/linux/provider.zip", "1.2.3"),
        row(3L, "terraform-proxy", RepositoryFormat.TERRAFORM, "acme",
            "cloud", "1.2.3", "terraform-provider", "v1/providers/acme/cloud/1.2.3"));
    RecordingSecurityService security = new RecordingSecurityService(permission -> AccessDecision.allow());
    ComponentSearchController controller = controller(components, subject("alice"), null, security);

    ComponentSearchController.ComponentSearchResponse response = controller.search(
        null,
        "terraform",
        null,
        request("GET", "/internal/search/components"));

    assertEquals(1, response.count());
    assertEquals(List.of("cloud"),
        response.items().stream().map(ComponentSearchController.ComponentSearchItem::name).toList());
    assertEquals(List.of("v1/providers/acme/cloud/1.2.3"),
        response.items().stream()
            .map(ComponentSearchController.ComponentSearchItem::browsePath)
            .toList());
  }

  @Test
  void searchCanUseAnonymousSubjectWhenAnonymousAccessIsConfigured() {
    StubComponentDao components = new StubComponentDao();
    components.rows = List.of(row(1L, "pypi-group", RepositoryFormat.PYPI, null, "sample", "1.0.0"));
    RecordingSecurityService security = new RecordingSecurityService(permission -> AccessDecision.allow());
    ComponentSearchController controller = controller(components, null, subject("anonymous"), security);

    ComponentSearchController.ComponentSearchResponse response = controller.search(
        null,
        "pypi",
        null,
        request("GET", "/internal/search/components"));

    assertEquals(1, response.count());
    assertEquals(List.of("pypi-group"), response.items().stream()
        .map(ComponentSearchController.ComponentSearchItem::repository)
        .toList());
    assertEquals(List.of("|pypi|300"), components.calls);
    assertEquals(List.of(
            "nexus:search:read",
            "nexus:repository-view:pypi:pypi-group:browse"),
        security.permissions);
  }

  @Test
  void searchParsesNugetPubRubygemsYumTerraformAndSwiftFormats() {
    StubComponentDao components = new StubComponentDao();
    RecordingSecurityService security = new RecordingSecurityService(permission -> AccessDecision.allow());
    ComponentSearchController controller = controller(components, subject("alice"), null, security);

    controller.search(null, "nuget", null, request("GET", "/internal/search/components"));
    controller.search(null, "pub", null, request("GET", "/internal/search/components"));
    controller.search(null, "rubygems", null, request("GET", "/internal/search/components"));
    controller.search(null, "yum", null, request("GET", "/internal/search/components"));
    controller.search(null, "terraform", null, request("GET", "/internal/search/components"));
    controller.search(null, "swift", null, request("GET", "/internal/search/components"));

    assertEquals(
        List.of(
            "|nuget|300", "|pub|300", "|rubygems|300", "|yum|300", "|terraform|300", "|swift|300"),
        components.calls);
  }

  @Test
  void swiftSearchIncludesChecksumSignatureToolsAndSourceDetails() {
    StubComponentDao components = new StubComponentDao();
    components.rows = List.of(row(
        42L,
        "swift-hosted",
        RepositoryFormat.SWIFT,
        "Acme",
        "Demo",
        "1.2.3",
        "swift-package-release",
        "acme/demo/1.2.3.zip"));
    SwiftRegistryDao swift = mock(SwiftRegistryDao.class);
    Instant now = Instant.parse("2026-07-16T08:00:00Z");
    SwiftRegistryDao.Release release = new SwiftRegistryDao.Release(
        42L, 42L, 42L, "acme", "Acme", "demo", "Demo", "1.2.3", now,
        "{}", "a".repeat(64), 100L, "cms-1.0.0", 101L, null,
        "HOSTED", 7L, SwiftRegistryDao.RELEASE_READY, now, now);
    when(swift.findRelease(42L, "acme", "demo", "1.2.3"))
        .thenReturn(Optional.of(release));
    when(swift.listManifests(42L)).thenReturn(List.of(
        new SwiftRegistryDao.Manifest(1L, "Package.swift", "", 102L, "b".repeat(64)),
        new SwiftRegistryDao.Manifest(
            2L, "Package@swift-5.9.swift", "5.9", 103L, "c".repeat(64))));
    RecordingSecurityService security =
        new RecordingSecurityService(permission -> AccessDecision.allow());
    ComponentSearchController controller = new ComponentSearchController(
        components,
        new StubAuthenticationService(subject("alice"), null),
        security,
        swift);

    ComponentSearchController.ComponentSearchItem item = controller.search(
        "demo", "swift", 10, request("GET", "/internal/search/components"))
        .items().getFirst();

    assertEquals("a".repeat(64), item.details().get("checksum"));
    assertEquals("signed", item.details().get("signatureStatus"));
    assertEquals("cms-1.0.0", item.details().get("signatureFormat"));
    assertEquals("HOSTED", item.details().get("sourceKind"));
    assertEquals("swift-hosted", item.details().get("sourceRepository"));
    assertEquals(List.of("5.9"), item.details().get("swiftToolsVersions"));
  }

  @Test
  void searchRejectsWhenNoAuthenticatedOrAnonymousSubjectExists() {
    StubComponentDao components = new StubComponentDao();
    RecordingSecurityService security = new RecordingSecurityService(permission -> AccessDecision.allow());
    ComponentSearchController controller = controller(components, null, null, security);

    ResponseStatusException thrown = assertThrows(ResponseStatusException.class,
        () -> controller.search(null, null, null, request("GET", "/internal/search/components")));

    assertEquals(HttpStatus.UNAUTHORIZED, thrown.getStatusCode());
    assertEquals(List.of(), security.permissions);
    assertEquals(List.of(), components.calls);
  }

  private static ComponentSearchController controller(
      StubComponentDao components,
      AuthenticatedSubject authenticated,
      AuthenticatedSubject anonymous,
      RecordingSecurityService security) {
    return new ComponentSearchController(
        components,
        new StubAuthenticationService(authenticated, anonymous),
        security);
  }

  private static ComponentDao.ComponentSearchRow row(
      long id,
      String repositoryName,
      RepositoryFormat format,
      String namespace,
      String name,
      String version) {
    return row(id, repositoryName, format, namespace, name, version,
        format == RepositoryFormat.COMPOSER ? name : null);
  }

  private static ComponentDao.ComponentSearchRow row(
      long id,
      String repositoryName,
      RepositoryFormat format,
      String namespace,
      String name,
      String version,
      String browsePath) {
    return row(id, repositoryName, format, namespace, name, version, "component", browsePath);
  }

  private static ComponentDao.ComponentSearchRow row(
      long id,
      String repositoryName,
      RepositoryFormat format,
      String namespace,
      String name,
      String version,
      String kind,
      String browsePath) {
    return new ComponentDao.ComponentSearchRow(
        id,
        id,
        repositoryName,
        format,
        namespace,
        name,
        version,
        kind,
        Instant.parse("2026-01-01T00:00:00Z"),
        browsePath);
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
        ComponentSearchControllerSecurityTest.class.getClassLoader(),
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

  private static class StubComponentDao extends ComponentDaoAdapter {
    private List<ComponentSearchRow> rows = List.of();
    private final List<String> calls = new ArrayList<>();

    private StubComponentDao() {
      super(null, null);
    }

    @Override
    public List<ComponentSearchRow> search(String keyword, RepositoryFormat format, int limit) {
      calls.add((keyword == null ? "" : keyword) + "|" + format + "|" + limit);
      return rows;
    }
  }

  private static class StubAuthenticationService extends SecurityAuthenticationService {
    private final AuthenticatedSubject authenticated;
    private final AuthenticatedSubject anonymous;

    private StubAuthenticationService(AuthenticatedSubject authenticated, AuthenticatedSubject anonymous) {
      super(new SecurityDaoAdapter(null, null), new ObjectMapper(), "X-Nexus-Plus-Token");
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

    private RecordingSecurityService(Function<String, AccessDecision> decisions) {
      super(new SecurityDaoAdapter(null, null));
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
