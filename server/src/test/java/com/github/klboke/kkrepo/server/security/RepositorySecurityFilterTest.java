package com.github.klboke.kkrepo.server.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.klboke.kkrepo.auth.AccessDecision;
import com.github.klboke.kkrepo.auth.PermissionAction;
import com.github.klboke.kkrepo.auth.PermissionSubject;
import com.github.klboke.kkrepo.auth.RepositoryPermission;
import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.core.RepositoryType;
import com.github.klboke.kkrepo.persistence.jdbc.api.RepositoryDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.SecurityDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.RepositoryRecord;
import com.github.klboke.kkrepo.server.support.dao.AssetDaoAdapter;
import com.github.klboke.kkrepo.server.support.dao.RepositoryDaoAdapter;
import com.github.klboke.kkrepo.server.support.dao.SecurityDaoAdapter;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.core.annotation.Order;
import org.springframework.session.web.http.SessionRepositoryFilter;

class RepositorySecurityFilterTest {

  @Test
  void repositoryFilterRunsAfterSpringSessionRepositoryFilter() {
    Order order = RepositorySecurityFilter.class.getAnnotation(Order.class);

    assertEquals(RepositorySecurityFilter.FILTER_ORDER, order.value());
    assertTrue(order.value() > SessionRepositoryFilter.DEFAULT_ORDER);
  }

  @Test
  void readOnlyRequestsUseAnonymousSubjectPermissionsInsteadOfBypassingSecurity() throws Exception {
    StubAuthenticationService authentication = new StubAuthenticationService(subject("anonymous"));
    RecordingDecisionService decisions = new RecordingDecisionService(AccessDecision.allow());
    RepositorySecurityFilter filter = filter(
        authentication,
        decisions,
        new FakeRepositoryDao(repository("maven-public")),
        true);
    ResponseState response = new ResponseState();
    ChainState chain = new ChainState();

    filter.doFilter(
        request("GET", "/repository/maven-public/junit/junit/4.13.2/junit-4.13.2.pom"),
        response.proxy(),
        chain);

    assertEquals(1, authentication.anonymousCalls);
    assertEquals(1, chain.calls);
    assertEquals("anonymous", decisions.subject.userId());
    assertEquals("maven-public", decisions.permission.repository());
    assertEquals(PermissionAction.READ, decisions.permission.action());
  }

  @Test
  void readOnlyAnonymousRequestsAreForbiddenWhenAnonymousRoleLacksPermission() throws Exception {
    StubAuthenticationService authentication = new StubAuthenticationService(subject("anonymous"));
    RecordingDecisionService decisions = new RecordingDecisionService(AccessDecision.deny("missing permission"));
    RepositorySecurityFilter filter = filter(
        authentication,
        decisions,
        new FakeRepositoryDao(repository("maven-public")),
        true);
    ResponseState response = new ResponseState();
    ChainState chain = new ChainState();

    filter.doFilter(
        request("GET", "/repository/maven-public/junit/junit/4.13.2/junit-4.13.2.pom"),
        response.proxy(),
        chain);

    assertEquals(1, authentication.anonymousCalls);
    assertEquals(0, chain.calls);
    assertEquals(HttpServletResponse.SC_FORBIDDEN, response.status);
    assertEquals("missing permission", response.message);
  }

  @Test
  void cargoConfigUsesAnonymousReadPermissionsWhenAvailable() throws Exception {
    assertCargoReadUsesAnonymousPermissions("/repository/cargo-hosted/config.json");
  }

  @Test
  void cargoSparseIndexUsesAnonymousReadPermissionsWhenAvailable() throws Exception {
    assertCargoReadUsesAnonymousPermissions("/repository/cargo-hosted/kk/re/kkrepo_e2e");
  }

  @Test
  void cargoSearchUsesAnonymousReadPermissionsWhenAvailable() throws Exception {
    assertCargoReadUsesAnonymousPermissions("/repository/cargo-hosted/api/v1/crates");
  }

  @Test
  void cargoConfigRequiresAuthenticationWhenAnonymousReadIsDisabled() throws Exception {
    assertCargoReadRequiresAuthenticationWhenAnonymousAccessDisabled("/repository/cargo-hosted/config.json");
  }

  @Test
  void rubygemsRepositoriesUseRubygemsAuthentication() throws Exception {
    AuthenticatedSubject alice = subject("alice");
    StubAuthenticationService authentication = new StubAuthenticationService(Optional.empty());
    authentication.rubygemsAuthenticated = Optional.of(alice);
    RecordingDecisionService decisions = new RecordingDecisionService(AccessDecision.allow());
    RepositorySecurityFilter filter = filter(
        authentication,
        decisions,
        new FakeRepositoryDao(repository("rubygems-hosted", RepositoryFormat.RUBYGEMS)),
        false);
    ResponseState response = new ResponseState();
    ChainState chain = new ChainState();

    filter.doFilter(
        request("POST", "/repository/rubygems-hosted/api/v1/gems"),
        response.proxy(),
        chain);

    assertEquals(1, authentication.rubygemsCalls);
    assertEquals(1, chain.calls);
    assertSame(alice.permissionSubject(), decisions.subject);
    assertEquals(PermissionAction.ADD, decisions.permission.action());
  }

  @Test
  void pubRepositoriesUseBearerAuthentication() throws Exception {
    AuthenticatedSubject alice = subject("alice");
    StubAuthenticationService authentication = new StubAuthenticationService(Optional.empty());
    authentication.pubAuthenticated = Optional.of(alice);
    RecordingDecisionService decisions = new RecordingDecisionService(AccessDecision.allow());
    RepositorySecurityFilter filter = filter(
        authentication,
        decisions,
        new FakeRepositoryDao(repository("pub-hosted", RepositoryFormat.PUB, RepositoryType.HOSTED)),
        false);
    ResponseState response = new ResponseState();
    ChainState chain = new ChainState();

    filter.doFilter(
        request("GET", "/repository/pub-hosted/api/packages/example_package"),
        response.proxy(),
        chain);

    assertEquals(1, authentication.pubCalls);
    assertEquals(1, chain.calls);
    assertSame(alice.permissionSubject(), decisions.subject);
    assertEquals(PermissionAction.READ, decisions.permission.action());
  }

  @Test
  void pubChallengeUsesBearerRealm() throws Exception {
    StubAuthenticationService authentication = new StubAuthenticationService(Optional.empty());
    RepositorySecurityFilter filter = filter(
        authentication,
        new RecordingDecisionService(AccessDecision.allow()),
        new FakeRepositoryDao(repository("pub-hosted", RepositoryFormat.PUB)),
        false);
    ResponseState response = new ResponseState();
    ChainState chain = new ChainState();

    filter.doFilter(
        request("GET", "/repository/pub-hosted/api/packages/example_package"),
        response.proxy(),
        chain);

    assertEquals(1, authentication.pubCalls);
    assertEquals(0, chain.calls);
    assertEquals(HttpServletResponse.SC_UNAUTHORIZED, response.status);
    assertEquals(
        "Bearer realm=\"pub\", message=\"Authentication required\"",
        response.headers.get("WWW-Authenticate"));
  }

  @Test
  void terraformUrlTokenIsAuthenticatedButCanonicalPathIsUsedForAuthorization() throws Exception {
    StubAuthenticationService authentication = new StubAuthenticationService(Optional.empty());
    authentication.terraformAuthenticated = Optional.of(subject("alice"));
    RecordingDecisionService decisions = new RecordingDecisionService(AccessDecision.allow());
    RepositorySecurityFilter filter = filter(
        authentication,
        decisions,
        new FakeRepositoryDao(repository(
            "terraform-private", RepositoryFormat.TERRAFORM, RepositoryType.HOSTED)),
        false);
    ResponseState response = new ResponseState();
    ChainState chain = new ChainState();
    HttpServletRequest request = request(
        "GET",
        "/repository/terraform-private/v1/modules/YWRtaW46QWRtaW4xMjM0/kkrepo/fixture/aws/versions");

    filter.doFilter(request, response.proxy(), chain);

    assertEquals(1, chain.calls);
    assertEquals("YWRtaW46QWRtaW4xMjM0", authentication.terraformToken);
    assertEquals(
        "YWRtaW46QWRtaW4xMjM0",
        request.getAttribute(RepositorySecurityFilter.TERRAFORM_URL_TOKEN_SEGMENT_ATTRIBUTE));
    assertEquals(
        "v1/modules/kkrepo/fixture/aws/versions",
        request.getAttribute(RepositorySecurityFilter.NORMALIZED_REPOSITORY_PATH_ATTRIBUTE));
    assertEquals("v1/modules/kkrepo/fixture/aws/versions", decisions.permission.pathPattern());
  }

  @Test
  void malformedTerraformUrlTokenPathIsNotMarkedSafeForFailureLogs() throws Exception {
    String token = "GenericToken.super-secret-value";
    HttpServletRequest request = request(
        "GET", "/repository/terraform-private/v1/modules/" + token + "/bad");
    RepositorySecurityFilter filter = filter(
        new StubAuthenticationService(Optional.of(subject("alice"))),
        new RecordingDecisionService(AccessDecision.allow()),
        new FakeRepositoryDao(repository(
            "terraform-private", RepositoryFormat.TERRAFORM, RepositoryType.HOSTED)),
        false);

    filter.doFilter(request, new ResponseState().proxy(), new ChainState());

    assertNull(request.getAttribute(RepositorySecurityFilter.NORMALIZED_REPOSITORY_PATH_ATTRIBUTE));
    assertNull(request.getAttribute(RepositorySecurityFilter.TERRAFORM_URL_TOKEN_SEGMENT_ATTRIBUTE));
  }

  @Test
  void rejectsUnsafeNonProtocolTerraformPathBeforeControllerDispatch() throws Exception {
    RepositorySecurityFilter filter = filter(
        new StubAuthenticationService(Optional.of(subject("alice"))),
        new RecordingDecisionService(AccessDecision.allow()),
        new FakeRepositoryDao(repository(
            "terraform-private", RepositoryFormat.TERRAFORM, RepositoryType.HOSTED)),
        false);
    ResponseState response = new ResponseState();
    ChainState chain = new ChainState();

    filter.doFilter(
        request("GET", "/repository/terraform-private/bad%2Fpath"), response.proxy(), chain);

    assertEquals(0, chain.calls);
    assertEquals(HttpServletResponse.SC_BAD_REQUEST, response.status);
    assertEquals("Invalid Terraform repository path", response.message);
  }

  @Test
  void embedsReplayableAuthenticationInPrivateTerraformDownloadUrls() throws Exception {
    StubAuthenticationService authentication =
        new StubAuthenticationService(Optional.of(subject("alice")));
    authentication.terraformReplayToken = "dXNlcjpwYXNz/w==";
    RepositorySecurityFilter filter = filter(
        authentication,
        new RecordingDecisionService(AccessDecision.allow()),
        new FakeRepositoryDao(repository(
            "terraform-private", RepositoryFormat.TERRAFORM, RepositoryType.HOSTED)),
        false);
    HttpServletRequest request = request(
        "GET", "/repository/terraform-private/v1/modules/acme/network/aws/1.2.3/download");
    ChainState chain = new ChainState();

    filter.doFilter(request, new ResponseState().proxy(), chain);

    assertEquals(1, chain.calls);
    assertEquals(
        "dXNlcjpwYXNz%2Fw%3D%3D",
        request.getAttribute(RepositorySecurityFilter.TERRAFORM_URL_TOKEN_SEGMENT_ATTRIBUTE));
  }

  @Test
  void rejectsNonReplayableAuthenticationForPrivateTerraformDownloadMetadata() throws Exception {
    RepositorySecurityFilter filter = filter(
        new StubAuthenticationService(Optional.of(subject("alice"))),
        new RecordingDecisionService(AccessDecision.allow()),
        new FakeRepositoryDao(repository(
            "terraform-private", RepositoryFormat.TERRAFORM, RepositoryType.HOSTED)),
        false);
    ResponseState response = new ResponseState();
    ChainState chain = new ChainState();

    filter.doFilter(
        request("GET", "/repository/terraform-private/v1/providers/acme/cloud/1.2.3/download/linux/amd64"),
        response.proxy(), chain);

    assertEquals(0, chain.calls);
    assertEquals(HttpServletResponse.SC_UNAUTHORIZED, response.status);
  }

  @Test
  void rejectsNonReplayableAuthenticationEvenWhenAnonymousMetadataReadIsEnabled() throws Exception {
    StubAuthenticationService authentication =
        new StubAuthenticationService(Optional.of(subject("alice")), subject("anonymous"));
    authentication.anonymousEnabled = true;
    RepositorySecurityFilter filter = filter(
        authentication,
        new RecordingDecisionService(AccessDecision.allow()),
        new FakeRepositoryDao(repository(
            "terraform-public", RepositoryFormat.TERRAFORM, RepositoryType.HOSTED)),
        true);
    ResponseState response = new ResponseState();
    ChainState chain = new ChainState();

    filter.doFilter(
        request("GET", "/repository/terraform-public/v1/modules/acme/network/aws/1.2.3/download"),
        response.proxy(), chain);

    assertEquals(0, chain.calls);
    assertEquals(HttpServletResponse.SC_UNAUTHORIZED, response.status);
    assertEquals(0, authentication.anonymousCalls);
  }

  @Test
  void allowsAnonymousTerraformDownloadMetadataWithoutReplayToken() throws Exception {
    StubAuthenticationService authentication =
        new StubAuthenticationService(Optional.empty(), subject("anonymous"));
    authentication.anonymousEnabled = true;
    RepositorySecurityFilter filter = filter(
        authentication,
        new RecordingDecisionService(AccessDecision.allow()),
        new FakeRepositoryDao(repository(
            "terraform-public", RepositoryFormat.TERRAFORM, RepositoryType.HOSTED)),
        true);
    ChainState chain = new ChainState();

    filter.doFilter(
        request("GET", "/repository/terraform-public/v1/modules/acme/network/aws/1.2.3/download"),
        new ResponseState().proxy(), chain);

    assertEquals(1, chain.calls);
    assertEquals(1, authentication.anonymousCalls);
  }

  @Test
  void cargoSparseIndexRequiresAuthenticationWhenAnonymousReadIsDisabled() throws Exception {
    assertCargoReadRequiresAuthenticationWhenAnonymousAccessDisabled("/repository/cargo-hosted/kk/re/kkrepo_e2e");
  }

  @Test
  void cargoSearchRequiresAuthenticationWhenAnonymousReadIsDisabled() throws Exception {
    assertCargoReadRequiresAuthenticationWhenAnonymousAccessDisabled("/repository/cargo-hosted/api/v1/crates");
  }

  private static void assertCargoReadUsesAnonymousPermissions(String uri) throws Exception {
    StubAuthenticationService authentication = new StubAuthenticationService(subject("anonymous"));
    RecordingDecisionService decisions = new RecordingDecisionService(AccessDecision.allow());
    RepositorySecurityFilter filter = filter(
        authentication,
        decisions,
        new FakeRepositoryDao(cargoRepository("cargo-hosted", true)),
        true);
    ResponseState response = new ResponseState();
    ChainState chain = new ChainState();

    filter.doFilter(request("GET", uri), response.proxy(), chain);

    assertEquals(1, authentication.anonymousCalls);
    assertEquals(1, decisions.decisions);
    assertEquals("anonymous", decisions.subject.userId());
    assertEquals(PermissionAction.READ, decisions.permission.action());
    assertEquals(1, chain.calls);
    assertEquals(0, response.status);
  }

  private static void assertCargoReadRequiresAuthenticationWhenAnonymousAccessDisabled(String uri) throws Exception {
    StubAuthenticationService authentication = new StubAuthenticationService(subject("anonymous"));
    RepositorySecurityFilter filter = filter(
        authentication,
        new RecordingDecisionService(AccessDecision.allow()),
        new FakeRepositoryDao(cargoRepository("cargo-hosted", false)),
        false);
    ResponseState response = new ResponseState();
    ChainState chain = new ChainState();

    filter.doFilter(request("GET", uri), response.proxy(), chain);

    assertEquals(1, authentication.anonymousCalls);
    assertEquals(0, chain.calls);
    assertEquals(HttpServletResponse.SC_UNAUTHORIZED, response.status);
    assertEquals("Authentication required", response.message);
    assertEquals("Cargo login_url=\"/repository/cargo-hosted/me\"", response.headers.get("WWW-Authenticate"));
  }

  @Test
  void cargoChallengeUsesTrustedForwardedHeaders() throws Exception {
    StubAuthenticationService authentication = new StubAuthenticationService(subject("anonymous"));
    RepositorySecurityFilter filter = filter(
        authentication,
        new RecordingDecisionService(AccessDecision.allow()),
        new FakeRepositoryDao(cargoRepository("cargo-hosted", false)),
        new ForwardedHeaderPolicy("10.0.0.1"),
        false);
    ResponseState response = new ResponseState();
    ChainState chain = new ChainState();

    filter.doFilter(
        request(
            "GET",
            "/repository/cargo-hosted/config.json",
            Map.of(),
            Map.of(
                "X-Forwarded-Proto", "https",
                "X-Forwarded-Host", "repo.example.com",
                "X-Forwarded-Port", "443"),
            "10.0.0.1",
            "http",
            "internal.local",
            8081),
        response.proxy(),
        chain);

    assertEquals(0, chain.calls);
    assertEquals(HttpServletResponse.SC_UNAUTHORIZED, response.status);
    assertEquals(
        "Cargo login_url=\"https://repo.example.com/repository/cargo-hosted/me\"",
        response.headers.get("WWW-Authenticate"));
  }

  @Test
  void cargoChallengeIgnoresUntrustedForwardedHeaders() throws Exception {
    StubAuthenticationService authentication = new StubAuthenticationService(subject("anonymous"));
    RepositorySecurityFilter filter = filter(
        authentication,
        new RecordingDecisionService(AccessDecision.allow()),
        new FakeRepositoryDao(cargoRepository("cargo-hosted", false)),
        new ForwardedHeaderPolicy("10.0.0.1"),
        false);
    ResponseState response = new ResponseState();
    ChainState chain = new ChainState();

    filter.doFilter(
        request(
            "GET",
            "/repository/cargo-hosted/config.json",
            Map.of(),
            Map.of(
                "X-Forwarded-Proto", "https",
                "X-Forwarded-Host", "repo.example.com",
                "X-Forwarded-Port", "443"),
            "192.0.2.10",
            "http",
            "internal.local",
            8081),
        response.proxy(),
        chain);

    assertEquals(0, chain.calls);
    assertEquals(HttpServletResponse.SC_UNAUTHORIZED, response.status);
    assertEquals(
        "Cargo login_url=\"http://internal.local:8081/repository/cargo-hosted/me\"",
        response.headers.get("WWW-Authenticate"));
  }

  @Test
  void cargoDownloadsUseAnonymousAccessLikeOtherRepositories() throws Exception {
    StubAuthenticationService authentication = new StubAuthenticationService(subject("anonymous"));
    RepositorySecurityFilter filter = filter(
        authentication,
        new RecordingDecisionService(AccessDecision.allow()),
        new FakeRepositoryDao(cargoRepository("cargo-hosted", true)),
        true);
    ResponseState response = new ResponseState();
    ChainState chain = new ChainState();

    filter.doFilter(
        request("GET", "/repository/cargo-hosted/api/v1/crates/kkrepo_e2e/0.1.0/download"),
        response.proxy(),
        chain);

    assertEquals(1, authentication.anonymousCalls);
    assertEquals(1, chain.calls);
    assertEquals(0, response.status);
  }

  @Test
  void nexusCompatibleCargoDownloadsUseAnonymousAccessLikeOtherRepositories() throws Exception {
    StubAuthenticationService authentication = new StubAuthenticationService(subject("anonymous"));
    RepositorySecurityFilter filter = filter(
        authentication,
        new RecordingDecisionService(AccessDecision.allow()),
        new FakeRepositoryDao(cargoRepository("cargo-hosted", true)),
        true);
    ResponseState response = new ResponseState();
    ChainState chain = new ChainState();

    filter.doFilter(
        request("GET", "/repository/cargo-hosted/crates/kkrepo_e2e/0.1.0/download"),
        response.proxy(),
        chain);

    assertEquals(1, authentication.anonymousCalls);
    assertEquals(1, chain.calls);
    assertEquals(0, response.status);
  }

  @Test
  void writeRequestsDoNotUseAnonymousAccess() throws Exception {
    StubAuthenticationService authentication = new StubAuthenticationService(subject("anonymous"));
    RepositorySecurityFilter filter = filter(
        authentication,
        new RecordingDecisionService(AccessDecision.allow()),
        new FakeRepositoryDao(repository("maven-public")),
        true);
    ResponseState response = new ResponseState();
    ChainState chain = new ChainState();

    filter.doFilter(
        request("PUT", "/repository/maven-public/com/acme/app/1.0/app-1.0.jar"),
        response.proxy(),
        chain);

    assertEquals(0, authentication.anonymousCalls);
    assertEquals(0, chain.calls);
    assertEquals(HttpServletResponse.SC_UNAUTHORIZED, response.status);
    assertEquals("Basic realm=\"kkrepo\"", response.headers.get("WWW-Authenticate"));
  }

  @Test
  void npmTokenLoginRouteBypassesRepositoryContentPermissionFilter() throws Exception {
    StubAuthenticationService authentication = new StubAuthenticationService(subject("anonymous"));
    RecordingDecisionService decisions = new RecordingDecisionService(AccessDecision.deny("should not be checked"));
    RepositorySecurityFilter filter = filter(
        authentication,
        decisions,
        new FakeRepositoryDao(repository("npm-hosted", RepositoryFormat.NPM)),
        false);
    ResponseState response = new ResponseState();
    ChainState chain = new ChainState();

    filter.doFilter(
        request("PUT", "/repository/npm-hosted/-/user/org.couchdb.user:alice"),
        response.proxy(),
        chain);

    assertEquals(0, authentication.anonymousCalls);
    assertEquals(1, chain.calls);
    assertEquals(0, response.status);
  }

  @Test
  void browseRestRouteRequiresBrowsePermission() throws Exception {
    StubAuthenticationService authentication = new StubAuthenticationService(Optional.of(subject("alice")));
    RecordingDecisionService decisions = new RecordingDecisionService(AccessDecision.allow());
    RepositorySecurityFilter filter = filter(
        authentication,
        decisions,
        new FakeRepositoryDao(repository("maven-public")),
        false);
    ResponseState response = new ResponseState();
    ChainState chain = new ChainState();

    filter.doFilter(
        request("GET", "/service/rest/repository/browse/maven-public/junit/junit/"),
        response.proxy(),
        chain);

    assertEquals(1, chain.calls);
    assertEquals(PermissionAction.BROWSE, decisions.permission.action());
    assertEquals("junit/junit/", decisions.permission.pathPattern());
  }

  @Test
  void repositoryContentRoutesUseNexusBreadActionMapping() throws Exception {
    assertRepositoryContentAction("GET", PermissionAction.READ);
    assertRepositoryContentAction("HEAD", PermissionAction.READ);
    assertRepositoryContentAction("POST", PermissionAction.ADD);
    assertRepositoryContentAction("PATCH", PermissionAction.ADD);
    assertRepositoryContentAction("PUT", PermissionAction.EDIT);
    assertRepositoryContentAction("DELETE", PermissionAction.DELETE);
  }

  @Test
  void terraformModuleCreatesRequireAddAndOverwritesRequireEdit() throws Exception {
    String path = "v1/modules/acme/network/aws/1.2.3/module.zip";
    assertTerraformModuleAction(path, Set.of(), PermissionAction.ADD);
    assertTerraformModuleAction(path, Set.of(path), PermissionAction.EDIT);
    assertTerraformModuleAction(
        "v1/modules/acme/network/aws/1.2.3/alternate.zip", Set.of(path), PermissionAction.EDIT);
  }

  @Test
  void terraformModuleOverwriteDoesNotFallBackToAddWhenEditIsDenied() throws Exception {
    String path = "v1/modules/acme/network/aws/1.2.3/module.zip";
    StubAuthenticationService authentication =
        new StubAuthenticationService(Optional.of(subject("alice")));
    RecordingDecisionService decisions = new RecordingDecisionService(AccessDecision.deny("missing edit")) {
      @Override
      public AccessDecision decide(PermissionSubject subject, RepositoryPermission permission) {
        super.decide(subject, permission);
        return permission.action() == PermissionAction.ADD
            ? AccessDecision.allow()
            : AccessDecision.deny("missing edit");
      }
    };
    RepositorySecurityFilter filter = new RepositorySecurityFilter(
        authentication,
        decisions,
        new FakeRepositoryDao(repository(
            "terraform-hosted", RepositoryFormat.TERRAFORM, RepositoryType.HOSTED)),
        new FakeAssetDao(Set.of(path)),
        new ForwardedHeaderPolicy(""),
        new NexusLegacyUiCompatibility(false));
    ResponseState response = new ResponseState();
    ChainState chain = new ChainState();

    filter.doFilter(
        request("PUT", "/repository/terraform-hosted/" + path), response.proxy(), chain);

    assertEquals(0, chain.calls);
    assertEquals(1, decisions.decisions);
    assertEquals(PermissionAction.EDIT, decisions.permission.action());
    assertEquals(HttpServletResponse.SC_FORBIDDEN, response.status);
  }

  @Test
  void cargoPublishRouteRequiresAddPermission() throws Exception {
    assertCargoRouteAction("PUT", "/repository/cargo-hosted/api/v1/crates/new", PermissionAction.ADD);
    assertCargoPublishRouteDoesNotFallBackToEditWhenAddDenied();
  }

  @Test
  void pubPublishRoutesRequireAddPermission() throws Exception {
    assertPubRouteAction("GET", "/repository/pub-hosted/api/packages/versions/new", PermissionAction.ADD);
    assertPubRouteAction("POST", "/repository/pub-hosted/api/packages/versions/upload/session-1", PermissionAction.ADD);
    assertPubRouteAction("GET", "/repository/pub-hosted/api/packages/versions/finalize/session-1", PermissionAction.ADD);
  }

  @Test
  void malformedPubPublishRoutesUseNormalPermissionMapping() throws Exception {
    assertPubRouteAction("GET", "/repository/pub-hosted/api/packages/versions/finalize/", PermissionAction.READ);
  }

  @Test
  void pubProxyAndGroupPublishRoutesReachProtocolLayerWithoutChallenge() throws Exception {
    assertInvalidPubPublishRoutePassesThrough(repository(
        "pub-proxy", RepositoryFormat.PUB, RepositoryType.PROXY));
    assertInvalidPubPublishRoutePassesThrough(repository(
        "pub-group", RepositoryFormat.PUB, RepositoryType.GROUP));
  }

  @Test
  void cargoYankRoutesUseEditPermission() throws Exception {
    assertCargoRouteAction(
        "DELETE",
        "/repository/cargo-hosted/api/v1/crates/my-crate/1.0.0/yank",
        PermissionAction.EDIT);
    assertCargoRouteAction(
        "PUT",
        "/repository/cargo-hosted/api/v1/crates/my-crate/1.0.0/unyank",
        PermissionAction.EDIT);
  }

  @Test
  void cargoPermissionOverridesDoNotApplyToNonCargoRepositories() throws Exception {
    assertRepositoryPathAction(
        repository("raw-hosted", RepositoryFormat.RAW),
        "PUT",
        "/repository/raw-hosted/api/v1/crates/new",
        PermissionAction.EDIT);
    assertRepositoryPathAction(
        repository("raw-hosted", RepositoryFormat.RAW),
        "DELETE",
        "/repository/raw-hosted/api/v1/crates/my-crate/1.0.0/yank",
        PermissionAction.DELETE);
  }

  @Test
  void npmAuditRoutesUseReadPermission() throws Exception {
    assertNpmAuditRouteUsesReadPermission("-/npm/v1/security/audits");
    assertNpmAuditRouteUsesReadPermission("-/npm/v1/security/audits/quick");
    assertNpmAuditRouteUsesReadPermission("-/npm/v1/security/advisories/bulk");
  }

  @Test
  void npmAuditPermissionOverrideDoesNotApplyToNonNpmRepositories() throws Exception {
    assertRepositoryPathAction(
        repository("raw-hosted", RepositoryFormat.RAW),
        "POST",
        "/repository/raw-hosted/-/npm/v1/security/audits",
        PermissionAction.ADD);
  }

  private void assertNpmAuditRouteUsesReadPermission(String path) throws Exception {
    StubAuthenticationService authentication = new StubAuthenticationService(subject("anonymous"));
    RecordingDecisionService decisions = new RecordingDecisionService(AccessDecision.allow());
    RepositorySecurityFilter filter = filter(
        authentication,
        decisions,
        new FakeRepositoryDao(repository("npm-example", RepositoryFormat.NPM)),
        true);
    ResponseState response = new ResponseState();
    ChainState chain = new ChainState();

    filter.doFilter(
        request("POST", "/repository/npm-example/" + path),
        response.proxy(),
        chain);

    assertEquals(1, authentication.anonymousCalls);
    assertEquals(1, chain.calls);
    assertEquals(PermissionAction.READ, decisions.permission.action());
    assertEquals(path, decisions.permission.pathPattern());
  }

  @Test
  void restComponentUploadRequiresRepositoryEditPermission() throws Exception {
    StubAuthenticationService authentication = new StubAuthenticationService(Optional.of(subject("alice")));
    RecordingDecisionService decisions = new RecordingDecisionService(AccessDecision.allow());
    RepositorySecurityFilter filter = filter(
        authentication,
        decisions,
        new FakeRepositoryDao(repository("maven-releases")),
        false,
        true);
    ResponseState response = new ResponseState();
    ChainState chain = new ChainState();

    filter.doFilter(
        request("POST", "/service/rest/v1/components", Map.of("repository", "maven-releases")),
        response.proxy(),
        chain);

    assertEquals(1, chain.calls);
    assertEquals(PermissionAction.EDIT, decisions.permission.action());
    assertEquals("maven-releases", decisions.permission.repository());
  }

  @Test
  void nonPostComponentsEndpointIsNotTreatedAsUploadPermission() throws Exception {
    StubAuthenticationService authentication = new StubAuthenticationService(Optional.of(subject("alice")));
    RecordingDecisionService decisions = new RecordingDecisionService(AccessDecision.deny("should not be checked"));
    RepositorySecurityFilter filter = filter(
        authentication,
        decisions,
        new FakeRepositoryDao(repository("maven-releases")),
        false,
        true);
    ResponseState response = new ResponseState();
    ChainState chain = new ChainState();

    filter.doFilter(
        request("GET", "/service/rest/v1/components", Map.of("repository", "maven-releases")),
        response.proxy(),
        chain);

    assertEquals(1, chain.calls);
    assertEquals(0, decisions.decisions);
    assertEquals(0, response.status);
  }

  @Test
  void internalUiComponentUploadRequiresRepositoryEditPermission() throws Exception {
    StubAuthenticationService authentication = new StubAuthenticationService(Optional.of(subject("alice")));
    RecordingDecisionService decisions = new RecordingDecisionService(AccessDecision.allow());
    RepositorySecurityFilter filter = filter(
        authentication,
        decisions,
        new FakeRepositoryDao(repository("maven-releases")),
        false,
        true);
    ResponseState response = new ResponseState();
    ChainState chain = new ChainState();

    filter.doFilter(
        request("POST", "/service/rest/internal/ui/upload/maven-releases"),
        response.proxy(),
        chain);

    assertEquals(1, chain.calls);
    assertEquals(PermissionAction.EDIT, decisions.permission.action());
    assertEquals("maven-releases", decisions.permission.repository());
  }

  @Test
  void internalUiComponentUploadPassesThroughWhenLegacyUiDisabled() throws Exception {
    StubAuthenticationService authentication = new StubAuthenticationService(Optional.empty());
    RecordingDecisionService decisions = new RecordingDecisionService(AccessDecision.deny("should not be checked"));
    RepositorySecurityFilter filter = filter(
        authentication,
        decisions,
        new FakeRepositoryDao(repository("maven-releases")),
        false,
        false);
    ResponseState response = new ResponseState();
    ChainState chain = new ChainState();

    filter.doFilter(
        request("POST", "/service/rest/internal/ui/upload/maven-releases"),
        response.proxy(),
        chain);

    assertEquals(1, chain.calls);
    assertEquals(0, authentication.calls);
    assertEquals(0, decisions.decisions);
    assertEquals(0, response.status);
  }

  private static AuthenticatedSubject subject(String userId) {
    return new AuthenticatedSubject(
        "Local",
        userId,
        "NexusAuthorizingRealm",
        null,
        new PermissionSubject("Local", userId, Set.of("nx-anonymous"), null));
  }

  private static RepositorySecurityFilter filter(
      SecurityAuthenticationService authenticationService,
      com.github.klboke.kkrepo.auth.AccessDecisionService accessDecisionService,
      RepositoryDao repositoryDao,
      boolean anonymousAccessEnabled) {
    return filter(
        authenticationService,
        accessDecisionService,
        repositoryDao,
        new ForwardedHeaderPolicy(""),
        anonymousAccessEnabled);
  }

  private static RepositorySecurityFilter filter(
      SecurityAuthenticationService authenticationService,
      com.github.klboke.kkrepo.auth.AccessDecisionService accessDecisionService,
      RepositoryDao repositoryDao,
      ForwardedHeaderPolicy forwardedHeaderPolicy,
      boolean anonymousAccessEnabled) {
    if (authenticationService instanceof StubAuthenticationService stub) {
      stub.anonymousEnabled = anonymousAccessEnabled;
    }
    return new RepositorySecurityFilter(
        authenticationService,
        accessDecisionService,
        repositoryDao,
        new FakeAssetDao(Set.of()),
        forwardedHeaderPolicy,
        new NexusLegacyUiCompatibility(false));
  }

  private static RepositorySecurityFilter filter(
      SecurityAuthenticationService authenticationService,
      com.github.klboke.kkrepo.auth.AccessDecisionService accessDecisionService,
      RepositoryDao repositoryDao,
      boolean anonymousAccessEnabled,
      boolean legacyUiEnabled) {
    if (authenticationService instanceof StubAuthenticationService stub) {
      stub.anonymousEnabled = anonymousAccessEnabled;
    }
    return new RepositorySecurityFilter(
        authenticationService,
        accessDecisionService,
        repositoryDao,
        new FakeAssetDao(Set.of()),
        new ForwardedHeaderPolicy(""),
        new NexusLegacyUiCompatibility(legacyUiEnabled));
  }

  private static void assertRepositoryContentAction(String method, PermissionAction action) throws Exception {
    assertRepositoryPathAction(
        repository("maven-releases"),
        method,
        "/repository/maven-releases/com/acme/app/1.0/app-1.0.jar",
        action);
  }

  private static void assertTerraformModuleAction(
      String path, Set<String> existingPaths, PermissionAction expected) throws Exception {
    StubAuthenticationService authentication =
        new StubAuthenticationService(Optional.of(subject("alice")));
    RecordingDecisionService decisions = new RecordingDecisionService(AccessDecision.allow());
    RepositorySecurityFilter filter = new RepositorySecurityFilter(
        authentication,
        decisions,
        new FakeRepositoryDao(repository(
            "terraform-hosted", RepositoryFormat.TERRAFORM, RepositoryType.HOSTED)),
        new FakeAssetDao(existingPaths),
        new ForwardedHeaderPolicy(""),
        new NexusLegacyUiCompatibility(false));
    ResponseState response = new ResponseState();
    ChainState chain = new ChainState();

    filter.doFilter(
        request("PUT", "/repository/terraform-hosted/" + path), response.proxy(), chain);

    assertEquals(1, chain.calls);
    assertEquals(expected, decisions.permission.action());
  }

  private static void assertRepositoryPathAction(
      RepositoryRecord repository,
      String method,
      String uri,
      PermissionAction action) throws Exception {
    StubAuthenticationService authentication = new StubAuthenticationService(Optional.of(subject("alice")));
    RecordingDecisionService decisions = new RecordingDecisionService(AccessDecision.allow());
    RepositorySecurityFilter filter = filter(
        authentication,
        decisions,
        new FakeRepositoryDao(repository),
        false);
    ResponseState response = new ResponseState();
    ChainState chain = new ChainState();

    filter.doFilter(request(method, uri), response.proxy(), chain);

    assertEquals(1, chain.calls);
    assertEquals(action, decisions.permission.action());
  }

  private static void assertCargoRouteAction(String method, String uri, PermissionAction action) throws Exception {
    StubAuthenticationService authentication = new StubAuthenticationService(Optional.of(subject("alice")));
    RecordingDecisionService decisions = new RecordingDecisionService(AccessDecision.allow());
    RepositorySecurityFilter filter = filter(
        authentication,
        decisions,
        new FakeRepositoryDao(cargoRepository("cargo-hosted", false)),
        false);
    ResponseState response = new ResponseState();
    ChainState chain = new ChainState();

    filter.doFilter(request(method, uri), response.proxy(), chain);

    assertEquals(1, chain.calls);
    assertEquals(action, decisions.permission.action());
  }

  private static void assertPubRouteAction(String method, String uri, PermissionAction action) throws Exception {
    StubAuthenticationService authentication = new StubAuthenticationService(Optional.empty());
    authentication.pubAuthenticated = Optional.of(subject("alice"));
    RecordingDecisionService decisions = new RecordingDecisionService(AccessDecision.allow());
    RepositorySecurityFilter filter = filter(
        authentication,
        decisions,
        new FakeRepositoryDao(repository("pub-hosted", RepositoryFormat.PUB, RepositoryType.HOSTED)),
        false);
    ResponseState response = new ResponseState();
    ChainState chain = new ChainState();

    filter.doFilter(request(method, uri), response.proxy(), chain);

    assertEquals(1, chain.calls);
    assertEquals(action, decisions.permission.action());
  }

  private static void assertInvalidPubPublishRoutePassesThrough(RepositoryRecord repository) throws Exception {
    StubAuthenticationService authentication = new StubAuthenticationService(Optional.empty());
    RecordingDecisionService decisions = new RecordingDecisionService(AccessDecision.deny("should not decide"));
    RepositorySecurityFilter filter = filter(
        authentication,
        decisions,
        new FakeRepositoryDao(repository),
        false);
    ResponseState response = new ResponseState();
    ChainState chain = new ChainState();

    filter.doFilter(
        request("GET", "/repository/" + repository.name() + "/api/packages/versions/new"),
        response.proxy(),
        chain);

    assertEquals(0, authentication.pubCalls);
    assertEquals(0, decisions.decisions);
    assertEquals(1, chain.calls);
    assertEquals(0, response.status);
  }

  private static void assertCargoPublishRouteDoesNotFallBackToEditWhenAddDenied() throws Exception {
    StubAuthenticationService authentication = new StubAuthenticationService(Optional.of(subject("alice")));
    RecordingDecisionService decisions = new RecordingDecisionService(AccessDecision.deny("missing add")) {
      @Override
      public AccessDecision decide(PermissionSubject subject, RepositoryPermission permission) {
        super.decide(subject, permission);
        return permission.action() == PermissionAction.EDIT
            ? AccessDecision.allow()
            : AccessDecision.deny("missing add");
      }
    };
    RepositorySecurityFilter filter = filter(
        authentication,
        decisions,
        new FakeRepositoryDao(cargoRepository("cargo-hosted", false)),
        false);
    ResponseState response = new ResponseState();
    ChainState chain = new ChainState();

    filter.doFilter(request("PUT", "/repository/cargo-hosted/api/v1/crates/new"), response.proxy(), chain);

    assertEquals(0, chain.calls);
    assertEquals(1, decisions.decisions);
    assertEquals(PermissionAction.ADD, decisions.permission.action());
    assertEquals(HttpServletResponse.SC_FORBIDDEN, response.status);
  }

  private static RepositoryRecord repository(String name) {
    return repository(name, RepositoryFormat.MAVEN2);
  }

  private static RepositoryRecord repository(String name, RepositoryFormat format) {
    return repository(name, format, RepositoryType.GROUP);
  }

  private static RepositoryRecord repository(String name, RepositoryFormat format, RepositoryType type) {
    return new RepositoryRecord(
        1L,
        name,
        format,
        type,
        format.name().toLowerCase() + "-" + type.name().toLowerCase(),
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

  private static RepositoryRecord cargoRepository(String name, boolean requireAuthentication) {
    return new RepositoryRecord(
        1L,
        name,
        RepositoryFormat.CARGO,
        RepositoryType.HOSTED,
        "cargo-hosted",
        true,
        1L,
        null,
        null,
        null,
        null,
        null,
        true,
        Map.of("cargo", Map.of("requireAuthentication", requireAuthentication)));
  }

  private static HttpServletRequest request(String method, String uri) {
    return request(method, uri, Map.of());
  }

  private static HttpServletRequest request(String method, String uri, Map<String, String> parameters) {
    return request(method, uri, parameters, Map.of(), null, null, null, 0);
  }

  private static HttpServletRequest request(
      String method,
      String uri,
      Map<String, String> parameters,
      Map<String, String> headers,
      String remoteAddr,
      String scheme,
      String serverName,
      int serverPort) {
    Map<String, Object> attributes = new LinkedHashMap<>();
    return (HttpServletRequest) Proxy.newProxyInstance(
        RepositorySecurityFilterTest.class.getClassLoader(),
        new Class<?>[] {HttpServletRequest.class},
        (proxy, invoked, args) -> switch (invoked.getName()) {
          case "getMethod" -> method;
          case "getRequestURI" -> uri;
          case "getContextPath" -> "";
          case "getParameter" -> parameters.get(String.valueOf(args[0]));
          case "getHeader" -> headers.get(String.valueOf(args[0]));
          case "getRemoteAddr" -> remoteAddr;
          case "getScheme" -> scheme;
          case "getServerName" -> serverName;
          case "getServerPort" -> serverPort;
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
    private final Optional<AuthenticatedSubject> authenticated;
    private final AuthenticatedSubject anonymous;
    private int calls;
    private int anonymousCalls;
    private Optional<AuthenticatedSubject> rubygemsAuthenticated = Optional.empty();
    private int rubygemsCalls;
    private Optional<AuthenticatedSubject> pubAuthenticated = Optional.empty();
    private int pubCalls;
    private Optional<AuthenticatedSubject> terraformAuthenticated = Optional.empty();
    private String terraformToken;
    private String terraformReplayToken;
    private boolean anonymousEnabled;

    private StubAuthenticationService(AuthenticatedSubject anonymous) {
      this(Optional.empty(), anonymous);
    }

    private StubAuthenticationService(Optional<AuthenticatedSubject> authenticated) {
      this(authenticated, null);
    }

    private StubAuthenticationService(Optional<AuthenticatedSubject> authenticated, AuthenticatedSubject anonymous) {
      super(new SecurityDaoAdapter(null, null), new ObjectMapper(), "X-Nexus-Plus-Token");
      this.authenticated = authenticated;
      this.anonymous = anonymous;
    }

    @Override
    public Optional<AuthenticatedSubject> authenticate(HttpServletRequest request) {
      calls++;
      return authenticated;
    }

    @Override
    public Optional<AuthenticatedSubject> authenticateRubygems(HttpServletRequest request) {
      rubygemsCalls++;
      return rubygemsAuthenticated;
    }

    @Override
    public Optional<AuthenticatedSubject> authenticatePub(HttpServletRequest request) {
      pubCalls++;
      return pubAuthenticated;
    }

    @Override
    public Optional<AuthenticatedSubject> authenticateTerraformUrlToken(String token) {
      terraformToken = token;
      return terraformAuthenticated;
    }

    @Override
    public Optional<String> replayableTerraformUrlToken(
        HttpServletRequest request, AuthenticatedSubject authenticated) {
      return Optional.ofNullable(terraformReplayToken);
    }

    @Override
    public Optional<AuthenticatedSubject> authenticateAnonymous() {
      anonymousCalls++;
      return anonymousEnabled && anonymous != null ? Optional.of(anonymous) : Optional.empty();
    }
  }

  private static class RecordingDecisionService implements com.github.klboke.kkrepo.auth.AccessDecisionService {
    private final AccessDecision decision;
    private int decisions;
    private PermissionSubject subject;
    private RepositoryPermission permission;

    private RecordingDecisionService(AccessDecision decision) {
      this.decision = decision;
    }

    @Override
    public AccessDecision decide(PermissionSubject subject, RepositoryPermission permission) {
      decisions++;
      this.subject = subject;
      this.permission = permission;
      return decision;
    }
  }

  private static class FakeRepositoryDao extends RepositoryDaoAdapter {
    private final RepositoryRecord repository;

    private FakeRepositoryDao(RepositoryRecord repository) {
      super(null, null);
      this.repository = repository;
    }

    @Override
    public Optional<RepositoryRecord> findByName(String name) {
      return repository.name().equals(name) ? Optional.of(repository) : Optional.empty();
    }
  }

  private static final class FakeAssetDao extends AssetDaoAdapter {
    private final Set<String> existingPaths;

    private FakeAssetDao(Set<String> existingPaths) {
      this.existingPaths = existingPaths;
    }

    @Override
    public List<AssetRecord> listAssetsByPrefix(long repositoryId, String prefix) {
      return existingPaths.stream()
          .filter(path -> path.startsWith(prefix))
          .map(path -> new AssetRecord(
              1L, repositoryId, null, null, RepositoryFormat.TERRAFORM, path, new byte[32],
              path.substring(path.lastIndexOf('/') + 1), "terraform", "application/zip", 1L,
              null, Instant.EPOCH, Map.of()))
          .toList();
    }
  }

  private static class ResponseState {
    private int status;
    private String message;
    private final Map<String, String> headers = new LinkedHashMap<>();

    private HttpServletResponse proxy() {
      return (HttpServletResponse) Proxy.newProxyInstance(
          RepositorySecurityFilterTest.class.getClassLoader(),
          new Class<?>[] {HttpServletResponse.class},
          (proxy, invoked, args) -> {
            if ("sendError".equals(invoked.getName())) {
              status = (Integer) args[0];
              message = args.length > 1 ? String.valueOf(args[1]) : null;
              return null;
            }
            if ("setHeader".equals(invoked.getName())) {
              headers.put(String.valueOf(args[0]), String.valueOf(args[1]));
              return null;
            }
            if ("toString".equals(invoked.getName())) {
              return "ResponseState";
            }
            return primitiveDefault(invoked.getReturnType());
          });
    }
  }

  private static class ChainState implements FilterChain {
    private int calls;

    @Override
    public void doFilter(jakarta.servlet.ServletRequest request, jakarta.servlet.ServletResponse response) {
      calls++;
      assertSame(request.getAttribute(AuthenticatedSubject.REQUEST_ATTRIBUTE), request.getAttribute(AuthenticatedSubject.REQUEST_ATTRIBUTE));
    }
  }
}
