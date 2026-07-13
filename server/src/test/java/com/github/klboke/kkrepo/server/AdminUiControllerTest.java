package com.github.klboke.kkrepo.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.klboke.kkrepo.auth.AccessDecision;
import com.github.klboke.kkrepo.auth.PermissionSubject;
import com.github.klboke.kkrepo.persistence.jdbc.api.SecurityDao;
import com.github.klboke.kkrepo.server.security.AuthenticatedSubject;
import com.github.klboke.kkrepo.server.security.SecurityAuthenticationService;
import com.github.klboke.kkrepo.server.security.SecurityManagementService;
import com.github.klboke.kkrepo.server.support.dao.SecurityDaoAdapter;
import jakarta.servlet.http.HttpServletRequest;
import java.lang.reflect.Proxy;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

class AdminUiControllerTest {
  @Test
  void rootRedirectsToBrowseWelcome() {
    AdminUiController controller = new AdminUiController(
        new StubAuthenticationService(Optional.empty()),
        new StubSecurityService(AccessDecision.deny("missing")));

    assertEquals("redirect:/browse/#browse/welcome", controller.index());
  }

  @Test
  void adminRedirectsWhenSessionIsMissing() {
    AdminUiController controller = new AdminUiController(
        new StubAuthenticationService(Optional.empty()),
        new StubSecurityService(AccessDecision.allow()));

    assertEquals("redirect:/browse/?login=1#browse/welcome", controller.admin(request()));
  }

  @Test
  void adminRedirectsWhenSubjectIsNotAdministrator() {
    AdminUiController controller = new AdminUiController(
        new StubAuthenticationService(Optional.of(subject("alice"))),
        new StubSecurityService(AccessDecision.deny("missing nexus:*")));

    assertEquals("redirect:/browse/#browse/welcome", controller.admin(request()));
  }

  @Test
  void adminReturnsIndexForAdministratorAndStoresRequestSubject() {
    AuthenticatedSubject subject = subject("admin");
    HttpServletRequest request = request();
    AdminUiController controller = new AdminUiController(
        new StubAuthenticationService(Optional.of(subject)),
        new StubSecurityService(AccessDecision.allow()));

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
    Map<String, Object> attributes = new LinkedHashMap<>();
    return (HttpServletRequest) Proxy.newProxyInstance(
        AdminUiControllerTest.class.getClassLoader(),
        new Class<?>[] {HttpServletRequest.class},
        (proxy, invoked, args) -> switch (invoked.getName()) {
          case "getAttribute" -> attributes.get(String.valueOf(args[0]));
          case "setAttribute" -> {
            attributes.put(String.valueOf(args[0]), args[1]);
            yield null;
          }
          case "toString" -> "AdminUiControllerTest request";
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
