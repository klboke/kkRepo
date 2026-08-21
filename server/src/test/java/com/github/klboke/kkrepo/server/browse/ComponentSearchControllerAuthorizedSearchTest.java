package com.github.klboke.kkrepo.server.browse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.klboke.kkrepo.auth.AccessDecision;
import com.github.klboke.kkrepo.auth.PermissionSubject;
import com.github.klboke.kkrepo.auth.RepositoryPermission;
import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.core.RepositoryType;
import com.github.klboke.kkrepo.persistence.jdbc.api.ComponentDao.ComponentSearchCursor;
import com.github.klboke.kkrepo.persistence.jdbc.api.ComponentDao.ComponentSearchRow;
import com.github.klboke.kkrepo.persistence.jdbc.api.RRegistryDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.RepositoryRecord;
import com.github.klboke.kkrepo.server.repositories.RepositoryCatalogCache;
import com.github.klboke.kkrepo.server.security.AuthenticatedSubject;
import com.github.klboke.kkrepo.server.security.SecurityAuthenticationService;
import com.github.klboke.kkrepo.server.security.SecurityManagementService;
import com.github.klboke.kkrepo.server.security.SecurityManagementService.RepositoryAccessMode;
import com.github.klboke.kkrepo.server.support.dao.AssetDaoAdapter;
import com.github.klboke.kkrepo.server.support.dao.ComponentDaoAdapter;
import com.github.klboke.kkrepo.server.support.dao.SecurityDaoAdapter;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletRequest;
import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;

class ComponentSearchControllerAuthorizedSearchTest {

  @Test
  void selectorOnlySearchAuthorizesRealAssetPathsWithoutLeakingSiblingComponents() {
    StubComponentDao components = new StubComponentDao(List.of(
        row(2L, 10L, "releases", "secret", 2),
        row(1L, 10L, "releases", "public", 1)));
    StubAssetDao assets = new StubAssetDao(Map.of(
        2L, List.of(asset(2L, 10L, "com/acme/private/secret.jar")),
        1L, List.of(asset(1L, 10L, "com/acme/public/app.jar"))));
    RecordingSecurityService security = new RecordingSecurityService(
        Map.of("releases", RepositoryAccessMode.CONTENT_SELECTOR),
        permission -> !permission.pathPattern().contains("/private/"));
    ComponentSearchController controller = controller(
        components, assets, security, catalog(List.of(hosted(10L, "releases")), Map.of()));

    ComponentSearchController.ComponentSearchResponse response = controller.search(
        null, "maven2", 10, request());

    assertEquals(1, response.count());
    assertEquals("public", response.items().getFirst().name());
    assertEquals("com/acme/public/app.jar", response.items().getFirst().browsePath());
    assertEquals(List.of(10L), components.calls.getFirst().repositoryIds());
    assertEquals(1, assets.calls.size());
    assertTrue(security.pathPermissions.stream()
        .anyMatch(permission -> permission.pathPattern().contains("/private/")));
    assertTrue(security.pathPermissions.stream()
        .allMatch(permission -> permission.pathPattern() != null
            && !permission.pathPattern().isBlank()));
  }

  @Test
  void selectorSearchContinuesAfterUnauthorizedFirstPage() {
    List<ComponentSearchRow> rows = new ArrayList<>();
    Map<Long, List<AssetRecord>> assetRows = new LinkedHashMap<>();
    for (long id = 202; id >= 3; id--) {
      rows.add(row(id, 10L, "releases", "private-" + id, id));
      assetRows.put(id, List.of(asset(id, 10L, "private/" + id + ".jar")));
    }
    rows.add(row(2L, 10L, "releases", "public-two", 2));
    rows.add(row(1L, 10L, "releases", "public-one", 1));
    assetRows.put(2L, List.of(asset(2L, 10L, "public/two.jar")));
    assetRows.put(1L, List.of(asset(1L, 10L, "public/one.jar")));
    StubComponentDao components = new StubComponentDao(rows);
    StubAssetDao assets = new StubAssetDao(assetRows);
    RecordingSecurityService security = new RecordingSecurityService(
        Map.of("releases", RepositoryAccessMode.CONTENT_SELECTOR),
        permission -> permission.pathPattern().startsWith("public/"));
    ComponentSearchController controller = controller(
        components, assets, security, catalog(List.of(hosted(10L, "releases")), Map.of()));

    ComponentSearchController.ComponentSearchResponse response = controller.search(
        null, "maven2", 2, request());

    assertEquals(List.of("public-two", "public-one"), response.items().stream()
        .map(ComponentSearchController.ComponentSearchItem::name)
        .toList());
    assertEquals(2, components.calls.size());
    assertEquals(200, components.calls.getFirst().limit());
    assertEquals(3L, components.calls.getFirst().lastReturnedId());
    assertEquals(3L, components.calls.get(1).after().id());
    assertEquals(2, assets.calls.size());
  }

  @Test
  void fullRepositoryAccessIsPushedIntoSqlAndSkipsAssetAuthorization() {
    StubComponentDao components = new StubComponentDao(List.of(
        row(2L, 20L, "denied", "secret", 2),
        row(1L, 10L, "public", "visible", 1)));
    StubAssetDao assets = new StubAssetDao(Map.of());
    RecordingSecurityService security = new RecordingSecurityService(
        Map.of(
            "public", RepositoryAccessMode.FULL,
            "denied", RepositoryAccessMode.DENIED),
        permission -> false);
    ComponentSearchController controller = controller(
        components,
        assets,
        security,
        catalog(List.of(hosted(10L, "public"), hosted(20L, "denied")), Map.of()));

    ComponentSearchController.ComponentSearchResponse response = controller.search(
        null, "maven2", 10, request());

    assertEquals(List.of("visible"), response.items().stream()
        .map(ComponentSearchController.ComponentSearchItem::name)
        .toList());
    assertEquals(List.of(10L), components.calls.getFirst().repositoryIds());
    assertEquals(List.of(), assets.calls);
    assertEquals(List.of(), security.pathPermissions);
  }

  @Test
  void selectorSearchReportsWhenItsBoundedCandidateScanIsTruncated() {
    List<ComponentSearchRow> rows = new ArrayList<>();
    Map<Long, List<AssetRecord>> assetRows = new LinkedHashMap<>();
    for (long id = 1_001; id >= 1; id--) {
      rows.add(row(id, 10L, "releases", "private-" + id, id));
      assetRows.put(id, List.of(asset(id, 10L, "private/" + id + ".jar")));
    }
    StubComponentDao components = new StubComponentDao(rows);
    StubAssetDao assets = new StubAssetDao(assetRows);
    RecordingSecurityService security = new RecordingSecurityService(
        Map.of("releases", RepositoryAccessMode.CONTENT_SELECTOR),
        permission -> false);
    ComponentSearchController controller = controller(
        components, assets, security, catalog(List.of(hosted(10L, "releases")), Map.of()));

    ComponentSearchController.ComponentSearchResponse response = controller.search(
        null, "maven2", 10, request());

    assertEquals(0, response.count());
    assertTrue(response.truncated());
    assertEquals(5, components.calls.size());
    assertEquals(5, assets.calls.size());
  }

  @Test
  void groupOnlyBrowsePermissionReturnsMemberComponentsThroughGroupName() {
    StubComponentDao components = new StubComponentDao(List.of(
        row(1L, 10L, "member", "visible", 1)));
    StubAssetDao assets = new StubAssetDao(Map.of());
    RecordingSecurityService security = new RecordingSecurityService(
        Map.of(
            "member", RepositoryAccessMode.DENIED,
            "public-group", RepositoryAccessMode.FULL),
        permission -> false);
    ComponentSearchController controller = controller(
        components,
        assets,
        security,
        catalog(
            List.of(hosted(10L, "member"), group(20L, "public-group")),
            Map.of(20L, List.of("member"))));

    ComponentSearchController.ComponentSearchItem item = controller.search(
        null, "maven2", 10, request()).items().getFirst();

    assertEquals("public-group", item.repository());
    assertEquals(List.of(20L, 10L), components.calls.getFirst().repositoryIds());
  }

  @Test
  void searchReturnsEmptyWithoutQueryingWhenNoRepositoryIsAuthorized() {
    StubComponentDao components = new StubComponentDao(List.of(
        row(1L, 10L, "private", "hidden", 1)));
    StubAssetDao assets = new StubAssetDao(Map.of());
    RecordingSecurityService security = new RecordingSecurityService(Map.of(), permission -> false);
    ComponentSearchController controller = controller(
        components, assets, security, catalog(List.of(hosted(10L, "private")), Map.of()));

    ComponentSearchController.ComponentSearchResponse response = controller.search(
        null, "maven2", 10, request());

    assertEquals(0, response.count());
    assertEquals(List.of(), components.calls);
    assertEquals(List.of(), assets.calls);
  }

  @Test
  void selectorSearchStopsCleanlyWhenTheFirstPageIsEmpty() {
    StubComponentDao components = new StubComponentDao(List.of());
    StubAssetDao assets = new StubAssetDao(Map.of());
    RecordingSecurityService security = new RecordingSecurityService(
        Map.of("releases", RepositoryAccessMode.CONTENT_SELECTOR), permission -> false);
    ComponentSearchController controller = controller(
        components, assets, security, catalog(List.of(hosted(10L, "releases")), Map.of()));

    ComponentSearchController.ComponentSearchResponse response = controller.search(
        null, "maven2", 10, request());

    assertEquals(0, response.count());
    assertFalse(response.truncated());
    assertEquals(1, components.calls.size());
    assertEquals(List.of(), assets.calls);
  }

  @Test
  void nestedGroupsIgnoreMissingMembersAndCycles() {
    StubComponentDao components = new StubComponentDao(List.of(
        row(1L, 10L, "member", "visible", 1)));
    StubAssetDao assets = new StubAssetDao(Map.of());
    RecordingSecurityService security = new RecordingSecurityService(
        Map.of(
            "member", RepositoryAccessMode.DENIED,
            "outer", RepositoryAccessMode.FULL,
            "nested", RepositoryAccessMode.DENIED),
        permission -> false);
    ComponentSearchController controller = controller(
        components,
        assets,
        security,
        catalog(
            List.of(
                hosted(10L, "member"),
                group(20L, "outer"),
                group(30L, "nested")),
            Map.of(
                20L, List.of("missing", "nested"),
                30L, List.of("outer", "member"))));

    ComponentSearchController.ComponentSearchItem item = controller.search(
        null, "maven2", 10, request()).items().getFirst();

    assertEquals("outer", item.repository());
    assertEquals(Set.of(10L, 20L, 30L), Set.copyOf(components.calls.getFirst().repositoryIds()));
  }

  @Test
  void rSearchDetailsUseTheDurablePackageProjection() {
    String path = "src/contrib/demo_1.2.3.tar.gz";
    StubComponentDao components = new StubComponentDao(List.of(rRow(path)));
    StubAssetDao assets = new StubAssetDao(Map.of());
    RecordingSecurityService security = new RecordingSecurityService(
        Map.of("cran", RepositoryAccessMode.FULL), permission -> false);
    ComponentSearchController controller = controller(
        components, assets, security, catalog(List.of(rHosted(10L, "cran")), Map.of()));

    assertTrue(controller.search(null, "r", 10, request())
        .items().getFirst().details().isEmpty());

    RRegistryDao r = mock(RRegistryDao.class);
    controller.setRRegistry(r);
    assertTrue(controller.search(null, "r", 10, request())
        .items().getFirst().details().isEmpty());
    when(r.findPackageByPath(10L, path)).thenReturn(Optional.of(rPackage(path)));

    Map<String, Object> details = controller.search(null, "r", 10, request())
        .items().getFirst().details();

    assertEquals("src/contrib", details.get("namespace"));
    assertEquals("demo_1.2.3.tar.gz", details.get("filename"));
    assertEquals("a".repeat(32), details.get("md5"));
    assertEquals("b".repeat(64), details.get("sha256"));
    assertEquals(512L, details.get("size"));
    assertEquals(RRegistryDao.SOURCE_HOSTED, details.get("sourceKind"));
    assertEquals("cran", details.get("sourceRepository"));
    assertEquals("MIT", details.get("License"));
    assertEquals("R (>= 4.3)", details.get("Depends"));
    assertEquals("methods", details.get("Imports"));
    assertEquals("Rcpp", details.get("LinkingTo"));
    assertEquals("testthat", details.get("Suggests"));
    assertEquals("parallel", details.get("Enhances"));
    assertEquals("yes", details.get("NeedsCompilation"));
  }

  @Test
  void legacyResponseConstructorDefaultsTruncationToFalse() {
    ComponentSearchController.ComponentSearchResponse response =
        new ComponentSearchController.ComponentSearchResponse(10, 0, List.of());

    assertFalse(response.truncated());
  }

  private static ComponentSearchController controller(
      StubComponentDao components,
      StubAssetDao assets,
      RecordingSecurityService security,
      RepositoryCatalogCache.RepositoryCatalog repositoryCatalog) {
    RepositoryCatalogCache catalogCache = mock(RepositoryCatalogCache.class);
    when(catalogCache.snapshot()).thenReturn(repositoryCatalog);
    return new ComponentSearchController(
        components,
        assets,
        new StubAuthenticationService(subject()),
        security,
        null,
        catalogCache);
  }

  private static RepositoryCatalogCache.RepositoryCatalog catalog(
      List<RepositoryRecord> repositories,
      Map<Long, List<String>> members) {
    return new RepositoryCatalogCache.RepositoryCatalog(
        Instant.EPOCH, repositories, Map.of(), members);
  }

  private static RepositoryRecord hosted(long id, String name) {
    return repository(id, name, RepositoryType.HOSTED);
  }

  private static RepositoryRecord rHosted(long id, String name) {
    return new RepositoryRecord(
        id, name, RepositoryFormat.R, RepositoryType.HOSTED, "r-hosted", true, 1L,
        null, null, null, null, "ALLOW_ONCE", true, Map.of());
  }

  private static RepositoryRecord group(long id, String name) {
    return repository(id, name, RepositoryType.GROUP);
  }

  private static RepositoryRecord repository(long id, String name, RepositoryType type) {
    return new RepositoryRecord(
        id,
        name,
        RepositoryFormat.MAVEN2,
        type,
        "maven2-" + type.name().toLowerCase(java.util.Locale.ROOT),
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

  private static ComponentSearchRow row(
      long id,
      long repositoryId,
      String repositoryName,
      String name,
      long timestampSeconds) {
    return new ComponentSearchRow(
        id,
        repositoryId,
        repositoryName,
        RepositoryFormat.MAVEN2,
        "com.acme",
        name,
        "1.0.0",
        "component",
        Instant.ofEpochSecond(timestampSeconds),
        null);
  }

  private static ComponentSearchRow rRow(String path) {
    return new ComponentSearchRow(
        1L, 10L, "cran", RepositoryFormat.R, "source", "demo", "1.2.3",
        "r-source-package", Instant.EPOCH, path);
  }

  private static RRegistryDao.PackageRecord rPackage(String path) {
    return new RRegistryDao.PackageRecord(
        1L, 10L, "src/contrib", "source", "source", "demo", "1.2.3",
        "r1|0000000001.0000000002.0000000003".getBytes(java.nio.charset.StandardCharsets.US_ASCII),
        "source", "demo_1.2.3.tar.gz", path,
        Map.of(
            "Package", "demo", "Version", "1.2.3", "License", "MIT",
            "Depends", "R (>= 4.3)", "Imports", "methods", "LinkingTo", "Rcpp",
            "Suggests", "testthat", "Enhances", "parallel", "NeedsCompilation", "yes"),
        "a".repeat(32), "b".repeat(64), "b".repeat(64), 512L,
        2L, 1L, RRegistryDao.SOURCE_HOSTED, 7L,
        Instant.EPOCH, Instant.EPOCH, Instant.EPOCH);
  }

  private static AssetRecord asset(long componentId, long repositoryId, String path) {
    return new AssetRecord(
        componentId,
        repositoryId,
        componentId,
        componentId,
        RepositoryFormat.MAVEN2,
        path,
        new byte[] {(byte) componentId},
        path.substring(path.lastIndexOf('/') + 1),
        "asset",
        "application/octet-stream",
        1L,
        null,
        Instant.EPOCH,
        Map.of());
  }

  private static AuthenticatedSubject subject() {
    return new AuthenticatedSubject(
        "Local",
        "alice",
        "local",
        null,
        new PermissionSubject("Local", "alice", Set.of(), null));
  }

  private static HttpServletRequest request() {
    Map<String, Object> attributes = new LinkedHashMap<>();
    return (HttpServletRequest) Proxy.newProxyInstance(
        ComponentSearchControllerAuthorizedSearchTest.class.getClassLoader(),
        new Class<?>[] {HttpServletRequest.class},
        (proxy, invoked, args) -> switch (invoked.getName()) {
          case "getMethod" -> "GET";
          case "getRequestURI" -> "/internal/search/components";
          case "getContextPath" -> "";
          case "getDispatcherType" -> DispatcherType.REQUEST;
          case "getAttribute" -> attributes.get(String.valueOf(args[0]));
          case "setAttribute" -> {
            attributes.put(String.valueOf(args[0]), args[1]);
            yield null;
          }
          case "toString" -> "GET /internal/search/components";
          default -> primitiveDefault(invoked.getReturnType());
        });
  }

  private static Object primitiveDefault(Class<?> type) {
    if (boolean.class.equals(type)) return false;
    if (int.class.equals(type) || long.class.equals(type)
        || short.class.equals(type) || byte.class.equals(type)) return 0;
    if (char.class.equals(type)) return '\0';
    return null;
  }

  private static final class StubComponentDao extends ComponentDaoAdapter {
    private final List<ComponentSearchRow> rows;
    private final List<SearchCall> calls = new ArrayList<>();

    private StubComponentDao(List<ComponentSearchRow> rows) {
      this.rows = rows.stream()
          .sorted(Comparator
              .comparing(ComponentSearchRow::lastUpdatedAt).reversed()
              .thenComparing(ComponentSearchRow::id, Comparator.reverseOrder()))
          .toList();
    }

    @Override
    public List<ComponentSearchRow> searchPageByRepositoryIds(
        List<Long> repositoryIds,
        RepositoryFormat format,
        String keyword,
        ComponentSearchCursor after,
        int limit) {
      List<ComponentSearchRow> page = rows.stream()
          .filter(row -> repositoryIds.contains(row.repositoryId()))
          .filter(row -> format == null || row.format() == format)
          .filter(row -> after == null
              || row.lastUpdatedAt().isBefore(after.lastUpdatedAt())
              || (row.lastUpdatedAt().equals(after.lastUpdatedAt()) && row.id() < after.id()))
          .limit(limit)
          .toList();
      calls.add(new SearchCall(
          List.copyOf(repositoryIds), after, limit,
          page.isEmpty() ? null : page.getLast().id()));
      return page;
    }
  }

  private static final class StubAssetDao extends AssetDaoAdapter {
    private final Map<Long, List<AssetRecord>> assets;
    private final List<List<Long>> calls = new ArrayList<>();

    private StubAssetDao(Map<Long, List<AssetRecord>> assets) {
      this.assets = assets;
    }

    @Override
    public List<AssetRecord> listAssetsByComponents(Collection<Long> componentIds) {
      List<Long> ids = List.copyOf(componentIds);
      calls.add(ids);
      return ids.stream().flatMap(id -> assets.getOrDefault(id, List.of()).stream()).toList();
    }
  }

  private static final class RecordingSecurityService extends SecurityManagementService {
    private final Map<String, RepositoryAccessMode> modes;
    private final Predicate<RepositoryPermission> pathDecision;
    private final List<RepositoryPermission> pathPermissions = new ArrayList<>();

    private RecordingSecurityService(
        Map<String, RepositoryAccessMode> modes,
        Predicate<RepositoryPermission> pathDecision) {
      super(new SecurityDaoAdapter(null, null));
      this.modes = modes;
      this.pathDecision = pathDecision;
    }

    @Override
    public AccessDecision decide(PermissionSubject subject, String permission) {
      return AccessDecision.allow();
    }

    @Override
    public Map<RepositoryPermission, RepositoryAccessMode> repositoryAccessModes(
        PermissionSubject subject,
        Collection<RepositoryPermission> permissions) {
      Map<RepositoryPermission, RepositoryAccessMode> result = new LinkedHashMap<>();
      permissions.forEach(permission -> result.put(
          permission,
          modes.getOrDefault(permission.repository(), RepositoryAccessMode.DENIED)));
      return result;
    }

    @Override
    public Map<RepositoryPermission, AccessDecision> decideAll(
        PermissionSubject subject,
        Collection<RepositoryPermission> permissions) {
      Map<RepositoryPermission, AccessDecision> result = new LinkedHashMap<>();
      permissions.forEach(permission -> {
        pathPermissions.add(permission);
        result.put(permission, pathDecision.test(permission)
            ? AccessDecision.allow()
            : AccessDecision.deny("denied"));
      });
      return result;
    }
  }

  private static final class StubAuthenticationService extends SecurityAuthenticationService {
    private final AuthenticatedSubject subject;

    private StubAuthenticationService(AuthenticatedSubject subject) {
      super(new SecurityDaoAdapter(null, null), new ObjectMapper(), "X-Nexus-Plus-Token");
      this.subject = subject;
    }

    @Override
    public Optional<AuthenticatedSubject> authenticate(HttpServletRequest request) {
      return Optional.of(subject);
    }
  }

  private record SearchCall(
      List<Long> repositoryIds,
      ComponentSearchCursor after,
      int limit,
      Long lastReturnedId) {
  }
}
