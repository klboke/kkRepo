package com.github.klboke.kkrepo.server.browse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.klboke.kkrepo.auth.AccessDecision;
import com.github.klboke.kkrepo.auth.PermissionSubject;
import com.github.klboke.kkrepo.auth.RepositoryPermission;
import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.core.RepositoryType;
import com.github.klboke.kkrepo.persistence.jdbc.api.AssetDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.ComponentDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.ComponentDao.ComponentSearchCursor;
import com.github.klboke.kkrepo.persistence.jdbc.api.AnsibleGalaxyRegistryDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.AptRegistryDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.AlpineRegistryDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.CondaRegistryDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.SwiftRegistryDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.RepositoryRecord;
import com.github.klboke.kkrepo.server.repositories.RepositoryCatalogCache;
import com.github.klboke.kkrepo.server.security.AuthenticatedSubject;
import com.github.klboke.kkrepo.server.security.SecurityAuthenticationService;
import com.github.klboke.kkrepo.server.security.SecurityManagementService;
import com.github.klboke.kkrepo.server.security.SecurityManagementService.RepositoryAccessMode;
import com.github.klboke.kkrepo.server.support.dao.ComponentDaoAdapter;
import com.github.klboke.kkrepo.server.support.dao.SecurityDaoAdapter;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletRequest;
import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
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
    assertEquals(List.of("psr/log", "p2/psr/log.json"), response.items().stream()
        .map(ComponentSearchController.ComponentSearchItem::name)
        .toList());
    assertEquals(List.of("psr/log/3.0.2/psr-log-3.0.2.zip", "p2/psr/log.json"), response.items().stream()
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
  void searchParsesEveryRepositoryFormatIncludingConan() {
    StubComponentDao components = new StubComponentDao();
    RecordingSecurityService security = new RecordingSecurityService(permission -> AccessDecision.allow());
    ComponentSearchController controller = controller(components, subject("alice"), null, security);

    List<String> expectedCalls = new ArrayList<>();
    for (RepositoryFormat format : RepositoryFormat.values()) {
      controller.search(null, format.id(), null, request("GET", "/internal/search/components"));
      expectedCalls.add("|" + format.id() + "|300");
    }
    controller.search(null, "ansible", null, request("GET", "/internal/search/components"));
    expectedCalls.add("|ansiblegalaxy|300");

    assertEquals(expectedCalls, components.calls);
  }

  @Test
  void searchRejectsUnknownFormatsInsteadOfFallingBackToAllFormats() {
    StubComponentDao components = new StubComponentDao();
    RecordingSecurityService security = new RecordingSecurityService(permission -> AccessDecision.allow());
    ComponentSearchController controller = controller(components, subject("alice"), null, security);

    ResponseStatusException thrown = assertThrows(ResponseStatusException.class, () ->
        controller.search(null, "not-a-format", null,
            request("GET", "/internal/search/components")));

    assertEquals(HttpStatus.BAD_REQUEST, thrown.getStatusCode());
    assertEquals("Unsupported repository format: not-a-format", thrown.getReason());
    assertEquals(List.of(), components.calls);
  }

  @Test
  void ansibleSearchIncludesArtifactDependenciesSignaturesAndSourceDetails() {
    StubComponentDao components = new StubComponentDao();
    components.rows = List.of(
        row(42L, "ansible-hosted", RepositoryFormat.ANSIBLEGALAXY,
            "acme", "tools", "1.2.3", "ansible-collection",
            "acme/tools/1.2.3/acme-tools-1.2.3.tar.gz"),
        row(43L, "ansible-hosted", RepositoryFormat.ANSIBLEGALAXY,
            "acme", "minimal", "1.0.0", "ansible-collection",
            "acme/minimal/1.0.0/acme-minimal-1.0.0.tar.gz"),
        row(44L, "ansible-hosted", RepositoryFormat.ANSIBLEGALAXY,
            "acme", "missing", "2.0.0", "ansible-collection",
            "acme/missing/2.0.0/acme-missing-2.0.0.tar.gz"));
    AnsibleGalaxyRegistryDao ansible = mock(AnsibleGalaxyRegistryDao.class);
    Instant now = Instant.parse("2026-07-21T08:00:00Z");
    AnsibleGalaxyRegistryDao.CollectionVersion version =
        new AnsibleGalaxyRegistryDao.CollectionVersion(
            50L, 42L, 42L, 60L,
            "acme", "acme", "tools", "tools", "1.2.3", "1.2.3",
            "acme-tools-1.2.3.tar.gz", "a".repeat(64), 1024L, Map.of(),
            Map.of("acme.base", ">=1.0.0"), ">=2.15", "HOSTED", 7L,
            AnsibleGalaxyRegistryDao.VERSION_READY, now, now, now);
    AnsibleGalaxyRegistryDao.CollectionVersion minimalVersion =
        new AnsibleGalaxyRegistryDao.CollectionVersion(
            51L, 43L, 43L, 61L,
            "acme", "acme", "minimal", "minimal", "1.0.0", "1.0.0",
            "acme-minimal-1.0.0.tar.gz", "b".repeat(64), 512L, Map.of(),
            Map.of(), null, "HOSTED", 8L,
            AnsibleGalaxyRegistryDao.VERSION_READY, now, now, now);
    when(ansible.findVersion(42L, "acme", "tools", "1.2.3"))
        .thenReturn(Optional.of(version));
    when(ansible.findVersion(43L, "acme", "minimal", "1.0.0"))
        .thenReturn(Optional.of(minimalVersion));
    when(ansible.findVersion(44L, "acme", "missing", "2.0.0"))
        .thenReturn(Optional.empty());
    when(ansible.listSignatures(version.id())).thenReturn(List.of(
        new AnsibleGalaxyRegistryDao.Signature(
            1L, version.id(), null, "b".repeat(64), null, "HOSTED", now)));
    RecordingSecurityService security =
        new RecordingSecurityService(permission -> AccessDecision.allow());
    ComponentSearchController controller = controller(
        components, subject("alice"), null, security);
    controller.setAnsibleGalaxyRegistry(ansible);

    ComponentSearchController.ComponentSearchResponse response = controller.search(
        "tools", "ansiblegalaxy", 10, request("GET", "/internal/search/components"));

    Map<String, ComponentSearchController.ComponentSearchItem> byName = response.items().stream()
        .collect(java.util.stream.Collectors.toMap(
            ComponentSearchController.ComponentSearchItem::name,
            item -> item));
    Map<String, Object> details = byName.get("tools").details();
    assertEquals("a".repeat(64), details.get("artifactSha256"));
    assertEquals(1024L, details.get("artifactSize"));
    assertEquals(Map.of("acme.base", ">=1.0.0"), details.get("dependencies"));
    assertEquals(">=2.15", details.get("requiresAnsible"));
    assertEquals(1, details.get("signatureCount"));
    assertEquals("HOSTED", details.get("sourceKind"));
    assertEquals("ansible-hosted", details.get("sourceRepository"));
    assertFalse(byName.get("minimal").details().containsKey("requiresAnsible"));
    assertEquals(Map.of(), byName.get("missing").details());
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
    ComponentSearchController controller = controller(
        components, subject("alice"), null, security, swift);

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
  void condaSearchIncludesPackageCoordinatesAndHandlesMissingProjection() {
    StubComponentDao components = new StubComponentDao();
    components.rows = List.of(
        row(80L, "conda-hosted", RepositoryFormat.CONDA, "main/noarch", "demo", "1.0",
            "conda-package", "main/noarch/demo/1.0/demo-1.0-0.conda"),
        row(81L, "conda-hosted", RepositoryFormat.CONDA, null, "unprojected", null,
            "conda-package", "not-a-package"),
        row(82L, "conda-hosted", RepositoryFormat.CONDA, null, "missing", null,
            "conda-package", null));
    CondaRegistryDao conda = mock(CondaRegistryDao.class);
    when(conda.findPackage(80L, "main", "noarch", "demo-1.0-0.conda"))
        .thenReturn(Optional.of(new CondaRegistryDao.PackageRecord(
            83L, 80L, "main", "noarch", "demo-1.0-0.conda", "demo", "1.0", "0",
            0L, "conda", Map.of("depends", List.of("python >=3.12")), "fingerprint",
            "a".repeat(32), "b".repeat(64), 123L, 84L, 85L,
            CondaRegistryDao.SOURCE_HOSTED, 4L, Instant.EPOCH, Instant.EPOCH)));
    RecordingSecurityService security =
        new RecordingSecurityService(permission -> AccessDecision.allow());
    ComponentSearchController controller = controller(
        components, subject("alice"), null, security);
    controller.setCondaRegistry(conda);

    ComponentSearchController.ComponentSearchResponse response = controller.search(
        "demo", "conda", 10, request("GET", "/internal/search/components"));

    assertEquals("demo|conda|10", components.calls.getFirst());
    Map<String, Object> details = response.items().stream()
        .filter(item -> "demo".equals(item.name()))
        .findFirst()
        .orElseThrow()
        .details();
    assertEquals("main", details.get("channel"));
    assertEquals("noarch", details.get("subdir"));
    assertEquals("0", details.get("build"));
    assertEquals(0L, details.get("buildNumber"));
    assertEquals("conda", details.get("archiveFormat"));
    assertEquals("a".repeat(32), details.get("md5"));
    assertEquals("b".repeat(64), details.get("sha256"));
    assertEquals(123L, details.get("size"));
    assertEquals(CondaRegistryDao.SOURCE_HOSTED, details.get("sourceKind"));
    assertEquals("conda-hosted", details.get("sourceRepository"));
    assertEquals(List.of("python >=3.12"), details.get("depends"));
    assertEquals(List.of(Map.of(), Map.of()), response.items().stream()
        .filter(item -> !"demo".equals(item.name()))
        .map(ComponentSearchController.ComponentSearchItem::details)
        .toList());

    ComponentSearchController withoutRegistry = controller(
        components, subject("alice"), null, security);
    assertEquals(Map.of(), withoutRegistry.search(
        null, "conda", 10, request("GET", "/internal/search/components"))
        .items().getFirst().details());
  }

  @Test
  void aptSearchIncludesPackageCoordinatesChecksumsAndSourceDetails() {
    StubComponentDao components = new StubComponentDao();
    String packagePath = "pool/main/d/demo/demo_1.0_amd64.deb";
    components.rows = List.of(row(
        90L, "apt-hosted", RepositoryFormat.APT, "stable/main", "demo", "1.0",
        "apt-package", packagePath));
    AptRegistryDao apt = mock(AptRegistryDao.class);
    Instant now = Instant.parse("2026-08-08T08:00:00Z");
    when(apt.findPackageByPath(90L, packagePath)).thenReturn(Optional.of(
        new AptRegistryDao.PackageRecord(
            91L, 90L, "stable", "main", "amd64", "demo", "1.0", "demo-source",
            "demo_1.0_amd64.deb", packagePath,
            Map.of("Section", "utils", "Description", "Demo package"),
            "a".repeat(32), "b".repeat(40), "c".repeat(64), 123L,
            92L, 90L, AptRegistryDao.SOURCE_HOSTED, 1L, now, now, now)));
    RecordingSecurityService security =
        new RecordingSecurityService(permission -> AccessDecision.allow());
    ComponentSearchController controller = controller(
        components, subject("alice"), null, security);
    controller.setAptRegistry(apt);

    Map<String, Object> details = controller.search(
        "demo", "apt", 10, request("GET", "/internal/search/components"))
        .items().getFirst().details();

    assertEquals("stable", details.get("distribution"));
    assertEquals("main", details.get("component"));
    assertEquals("amd64", details.get("architecture"));
    assertEquals("demo-source", details.get("sourcePackage"));
    assertEquals("demo_1.0_amd64.deb", details.get("filename"));
    assertEquals("c".repeat(64), details.get("sha256"));
    assertEquals(123L, details.get("size"));
    assertEquals(AptRegistryDao.SOURCE_HOSTED, details.get("sourceKind"));
    assertEquals("apt-hosted", details.get("sourceRepository"));
    assertEquals("utils", details.get("section"));
    assertEquals("Demo package", details.get("description"));

    ComponentSearchController.ComponentSearchResponse filtered = controller.search(
        "demo", "apt", 10, "stable", "main", "amd64", "demo-source", "cccccccc",
        request("GET", "/internal/search/components"));
    assertEquals(1, filtered.count());
    assertEquals("demo", filtered.items().getFirst().name());

    ResponseStatusException wrongFormat = assertThrows(
        ResponseStatusException.class,
        () -> controller.search(
            "demo", "maven2", 10, "stable", null, null, null, null,
            request("GET", "/internal/search/components")));
    assertEquals(HttpStatus.BAD_REQUEST, wrongFormat.getStatusCode());
  }

  @Test
  void alpineSearchIncludesPackageCoordinatesChecksumsAndSourceDetails() {
    StubComponentDao components = new StubComponentDao();
    String packagePath = "v3.23/main/x86_64/demo-1.0.0-r0.apk";
    components.rows = List.of(row(
        95L, "alpine-hosted", RepositoryFormat.ALPINE, "v3.23/main/x86_64", "demo",
        "1.0.0-r0", "alpine-apk-v2", packagePath));
    AlpineRegistryDao alpine = mock(AlpineRegistryDao.class);
    Instant now = Instant.parse("2026-08-15T08:00:00Z");
    when(alpine.findPackageByPath(95L, packagePath)).thenReturn(Optional.of(
        new AlpineRegistryDao.PackageRecord(
            96L, 95L, "v3.23", "main", "x86_64", "demo", "1.0.0-r0", "noarch",
            "demo-1.0.0-r0.apk", packagePath,
            Map.of("T", "Demo package", "D", "musl"),
            "Q1AAAAAAAAAAAAAAAAAAAAAAAAAAA=", "a".repeat(64), "b".repeat(64), 123L,
            97L, 95L, AlpineRegistryDao.SOURCE_HOSTED, 1L, now, now, now)));
    RecordingSecurityService security =
        new RecordingSecurityService(permission -> AccessDecision.allow());
    ComponentSearchController controller = controller(
        components, subject("alice"), null, security);
    controller.setAlpineRegistry(alpine);

    ComponentSearchController.ComponentSearchItem item = controller.search(
        "demo", "alpine", 10, request("GET", "/internal/search/components"))
        .items().getFirst();
    Map<String, Object> details = item.details();

    assertEquals(packagePath, item.browsePath());
    assertEquals("v3.23", details.get("namespace"));
    assertEquals("main", details.get("channel"));
    assertEquals("x86_64", details.get("repositoryArchitecture"));
    assertEquals("noarch", details.get("packageArchitecture"));
    assertEquals("Q1AAAAAAAAAAAAAAAAAAAAAAAAAAA=", details.get("identity"));
    assertEquals("a".repeat(64), details.get("dataSha256"));
    assertEquals("b".repeat(64), details.get("sha256"));
    assertEquals("Demo package", details.get("description"));
    assertEquals("musl", details.get("depends"));
    assertEquals(AlpineRegistryDao.SOURCE_HOSTED, details.get("sourceKind"));
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
    return controller(components, authenticated, anonymous, security, null);
  }

  private static ComponentSearchController controller(
      StubComponentDao components,
      AuthenticatedSubject authenticated,
      AuthenticatedSubject anonymous,
      RecordingSecurityService security,
      SwiftRegistryDao swiftRegistry) {
    RepositoryCatalogCache catalogCache = mock(RepositoryCatalogCache.class);
    when(catalogCache.snapshot()).thenAnswer(ignored -> catalog(components.rows));
    return new ComponentSearchController(
        components,
        mock(AssetDao.class),
        new StubAuthenticationService(authenticated, anonymous),
        security,
        swiftRegistry,
        catalogCache);
  }

  private static RepositoryCatalogCache.RepositoryCatalog catalog(
      List<ComponentDao.ComponentSearchRow> rows) {
    List<RepositoryRecord> repositories = new ArrayList<>();
    if (rows.isEmpty()) {
      for (RepositoryFormat format : RepositoryFormat.values()) {
        repositories.add(repository(-(format.ordinal() + 1L), format.id() + "-hosted", format));
      }
    } else {
      rows.forEach(row -> repositories.add(
          repository(row.repositoryId(), row.repositoryName(), row.format())));
    }
    return new RepositoryCatalogCache.RepositoryCatalog(
        Instant.EPOCH, List.copyOf(repositories), Map.of(), Map.of());
  }

  private static RepositoryRecord repository(
      long id,
      String name,
      RepositoryFormat format) {
    return new RepositoryRecord(
        id,
        name,
        format,
        RepositoryType.HOSTED,
        format.id() + "-hosted",
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
    public List<ComponentSearchRow> searchPageByRepositoryIds(
        List<Long> repositoryIds,
        RepositoryFormat format,
        String keyword,
        ComponentSearchCursor after,
        int limit) {
      calls.add((keyword == null ? "" : keyword) + "|" + format + "|" + limit);
      return rows.stream()
          .filter(row -> repositoryIds.contains(row.repositoryId()))
          .filter(row -> format == null || row.format() == format)
          .limit(limit)
          .toList();
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

    @Override
    public Map<RepositoryPermission, RepositoryAccessMode> repositoryAccessModes(
        PermissionSubject subject,
        Collection<RepositoryPermission> requestedPermissions) {
      Map<RepositoryPermission, RepositoryAccessMode> modes = new LinkedHashMap<>();
      requestedPermissions.forEach(permission -> {
        String requestedPermission = repositoryPermissionString(permission);
        permissions.add(requestedPermission);
        modes.put(
            permission,
            decisions.apply(requestedPermission).allowed()
                ? RepositoryAccessMode.FULL
                : RepositoryAccessMode.DENIED);
      });
      return modes;
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
