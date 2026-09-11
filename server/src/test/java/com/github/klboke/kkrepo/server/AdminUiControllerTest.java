package com.github.klboke.kkrepo.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.klboke.kkrepo.auth.AccessDecision;
import com.github.klboke.kkrepo.auth.PermissionSubject;
import com.github.klboke.kkrepo.persistence.jdbc.api.SecurityDao;
import com.github.klboke.kkrepo.server.security.AuthenticatedSubject;
import com.github.klboke.kkrepo.server.security.ForwardedHeaderPolicy;
import com.github.klboke.kkrepo.server.security.SecurityAuthenticationService;
import com.github.klboke.kkrepo.server.security.SecurityManagementService;
import com.github.klboke.kkrepo.server.support.dao.SecurityDaoAdapter;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class AdminUiControllerTest {
  @Test
  void selectorManagerCanEnterAdministrationWithoutGlobalAdministratorGrant() {
    var security = org.mockito.Mockito.mock(SecurityManagementService.class);
    var subject = subject("selector-manager");
    org.mockito.Mockito.when(security.decide(org.mockito.ArgumentMatchers.eq(subject.permissionSubject()),
        org.mockito.ArgumentMatchers.anyString())).thenReturn(AccessDecision.deny("missing"));
    org.mockito.Mockito.when(security.decide(subject.permissionSubject(), "nexus:selectors:read"))
        .thenReturn(AccessDecision.allow());
    var controller = new AdminUiController(
        new StubAuthenticationService(Optional.of(subject)), security, new ForwardedHeaderPolicy(""));
    assertInstanceOf(ResponseEntity.class, controller.admin(request()));
  }

  @Test
  void rootRedirectUsesTrustedForwardedOrigin() throws Exception {
    AdminUiController controller = new AdminUiController(
        new StubAuthenticationService(Optional.empty()),
        new StubSecurityService(AccessDecision.deny("missing")),
        new ForwardedHeaderPolicy("10.0.0.1"));

    var response = MockMvcBuilders.standaloneSetup(controller).build()
        .perform(get("/")
            .with(request -> {
              request.setScheme("http");
              request.setServerName("kkrepo.internal");
              request.setServerPort(8080);
              request.setRemoteAddr("10.0.0.1");
              return request;
            })
            .header("X-Forwarded-Proto", "https")
            .header("X-Forwarded-Host", "nexus.example.com")
            .header("X-Forwarded-Port", "443"))
        .andReturn()
        .getResponse();

    assertEquals(HttpStatus.FOUND.value(), response.getStatus());
    assertEquals(
        "https://nexus.example.com/browse/#browse/welcome", response.getHeader("Location"));
  }

  @Test
  void rootRedirectPreservesTrustedIpv6ForwardedPort() throws Exception {
    AdminUiController controller = new AdminUiController(
        new StubAuthenticationService(Optional.empty()),
        new StubSecurityService(AccessDecision.deny("missing")),
        new ForwardedHeaderPolicy("10.0.0.1"));

    var response = MockMvcBuilders.standaloneSetup(controller).build()
        .perform(get("/")
            .with(request -> {
              request.setScheme("http");
              request.setServerName("kkrepo.internal");
              request.setServerPort(8080);
              request.setRemoteAddr("10.0.0.1");
              return request;
            })
            .header("X-Forwarded-Proto", "https")
            .header("X-Forwarded-Host", "[2001:db8::1]")
            .header("X-Forwarded-Port", "8443"))
        .andReturn()
        .getResponse();

    assertEquals(HttpStatus.FOUND.value(), response.getStatus());
    assertEquals(
        "https://[2001:db8::1]:8443/browse/#browse/welcome",
        response.getHeader("Location"));
  }

  @Test
  void rootRedirectIgnoresUntrustedForwardedOrigin() throws Exception {
    AdminUiController controller = new AdminUiController(
        new StubAuthenticationService(Optional.empty()),
        new StubSecurityService(AccessDecision.deny("missing")),
        new ForwardedHeaderPolicy("10.0.0.1"));

    MockHttpServletRequest directRequest = new MockHttpServletRequest();
    directRequest.setScheme("http");
    directRequest.setServerName("kkrepo.internal");
    directRequest.setServerPort(8080);
    directRequest.setRemoteAddr("192.0.2.10");
    directRequest.setContextPath("/kkrepo");
    directRequest.addHeader("X-Forwarded-Proto", "https");
    directRequest.addHeader("X-Forwarded-Host", "attacker.example.com");
    directRequest.addHeader("X-Forwarded-Port", "443");

    assertEquals("redirect:/browse/#browse/welcome", controller.index(directRequest));

    var response = MockMvcBuilders.standaloneSetup(controller).build()
        .perform(get("/")
            .with(request -> {
              request.setScheme("http");
              request.setServerName("kkrepo.internal");
              request.setServerPort(8080);
              request.setRemoteAddr("192.0.2.10");
              return request;
            })
            .header("X-Forwarded-Proto", "https")
            .header("X-Forwarded-Host", "attacker.example.com")
            .header("X-Forwarded-Port", "443"))
        .andReturn()
        .getResponse();

    assertEquals(HttpStatus.FOUND.value(), response.getStatus());
    assertEquals("/browse/#browse/welcome", response.getHeader("Location"));
  }

  @Test
  void rootRedirectRemainsRelativeForDirectIpv6Request() {
    AdminUiController controller = new AdminUiController(
        new StubAuthenticationService(Optional.empty()),
        new StubSecurityService(AccessDecision.deny("missing")),
        new ForwardedHeaderPolicy("10.0.0.1"));
    MockHttpServletRequest directRequest = new MockHttpServletRequest();
    directRequest.setServerName("2001:db8::1");
    directRequest.setRemoteAddr("2001:db8::2");
    directRequest.setContextPath("/kkrepo");

    assertEquals("redirect:/browse/#browse/welcome", controller.index(directRequest));
  }

  @Test
  void adminRedirectsWhenSessionIsMissing() {
    AdminUiController controller = new AdminUiController(
        new StubAuthenticationService(Optional.empty()),
        new StubSecurityService(AccessDecision.allow()),
        new ForwardedHeaderPolicy(""));

    assertEquals("redirect:/browse/?login=1#browse/welcome", controller.admin(request()));
  }

  @Test
  void adminRedirectsWhenSubjectIsNotAdministrator() {
    AdminUiController controller = new AdminUiController(
        new StubAuthenticationService(Optional.of(subject("alice"))),
        new StubSecurityService(AccessDecision.deny("missing nexus:*")),
        new ForwardedHeaderPolicy(""));

    assertEquals("redirect:/browse/#browse/welcome", controller.admin(request()));
  }

  @Test
  void adminReturnsIndexForAdministratorAndStoresRequestSubject() {
    AuthenticatedSubject subject = subject("admin");
    HttpServletRequest request = request();
    AdminUiController controller = new AdminUiController(
        new StubAuthenticationService(Optional.of(subject)),
        new StubSecurityService(AccessDecision.allow()),
        new ForwardedHeaderPolicy(""));

    Object result = controller.admin(request);

    ResponseEntity<?> response = assertInstanceOf(ResponseEntity.class, result);
    assertEquals(HttpStatus.OK, response.getStatusCode());
    assertEquals(MediaType.TEXT_HTML, response.getHeaders().getContentType());
    Resource body = assertInstanceOf(Resource.class, response.getBody());
    assertEquals("index.html", body.getFilename());
    assertSame(subject, request.getAttribute(AuthenticatedSubject.REQUEST_ATTRIBUTE));
  }

  private static AuthenticatedSubject subject(String userId) {
    return new AuthenticatedSubject(
        "Local",
        userId,
        "local",
        null,
        new PermissionSubject("Local", userId, Set.of("nx-admin"), null));
  }

  private static HttpServletRequest request() {
    return new MockHttpServletRequest();
  }

  private static class StubAuthenticationService extends SecurityAuthenticationService {
    private final Optional<AuthenticatedSubject> subject;

    private StubAuthenticationService(Optional<AuthenticatedSubject> subject) {
      super(new SecurityDaoAdapter(null, null), new ObjectMapper(), "X-Nexus-Plus-Token");
      this.subject = subject;
    }

    @Override
    public Optional<AuthenticatedSubject> authenticate(HttpServletRequest request) {
      return subject;
    }
  }

  private static class StubSecurityService extends SecurityManagementService {
    private final AccessDecision decision;

    private StubSecurityService(AccessDecision decision) {
      super(new SecurityDaoAdapter(null, null));
      this.decision = decision;
    }

    @Override
    public AccessDecision decide(PermissionSubject subject, String requestedPermission) {
      return decision;
    }
  }
}
