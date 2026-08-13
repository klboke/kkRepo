package com.github.klboke.kkrepo.server.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.github.klboke.kkrepo.auth.PermissionSubject;
import com.github.klboke.kkrepo.server.security.SecurityPayloads.AdminBootstrapStatus;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class SecurityUiContextControllerTest {

  @Test
  void anonymousContextContainsBootstrapStateWithoutPermissions() {
    SecurityAuthenticationService authentication = mock(SecurityAuthenticationService.class);
    SecurityManagementService security = mock(SecurityManagementService.class);
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/internal/security/context");
    AdminBootstrapStatus bootstrap = bootstrap(true);
    when(authentication.authenticate(request)).thenReturn(Optional.empty());
    when(security.adminBootstrapStatus()).thenReturn(bootstrap);

    var context = new SecurityUiContextController(authentication, security).context(request);

    assertEquals(bootstrap, context.bootstrap());
    assertNull(context.session());
    assertEquals(List.of(), context.permissions());
  }

  @Test
  void authenticatedContextReusesFilterSubjectAndReturnsPermissions() {
    SecurityAuthenticationService authentication = mock(SecurityAuthenticationService.class);
    SecurityManagementService security = mock(SecurityManagementService.class);
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/internal/security/context");
    AuthenticatedSubject subject = new AuthenticatedSubject(
        "Local",
        "admin",
        "local",
        null,
        new PermissionSubject("Local", "admin", Set.of("nx-admin"), null));
    request.setAttribute(AuthenticatedSubject.REQUEST_ATTRIBUTE, subject);
    when(security.adminBootstrapStatus()).thenReturn(bootstrap(false));
    when(security.listEffectivePermissions(subject.permissionSubject()))
        .thenReturn(List.of("nexus:*"));

    var context = new SecurityUiContextController(authentication, security).context(request);

    verify(authentication, never()).authenticate(request);
    assertEquals("admin", context.session().userId());
    assertEquals(List.of("nx-admin"), context.session().roles());
    assertEquals(List.of("nexus:*"), context.permissions());
  }

  private static AdminBootstrapStatus bootstrap(boolean required) {
    return new AdminBootstrapStatus(required, "Local", "admin", "nx-admin", 8, false);
  }
}
