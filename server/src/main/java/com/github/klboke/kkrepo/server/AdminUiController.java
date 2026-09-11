package com.github.klboke.kkrepo.server;

import com.github.klboke.kkrepo.server.security.AuthenticatedSubject;
import com.github.klboke.kkrepo.server.security.ForwardedHeaderPolicy;
import com.github.klboke.kkrepo.server.security.SecurityAuthenticationService;
import com.github.klboke.kkrepo.server.security.SecurityManagementService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class AdminUiController {
  private static final String BROWSE_WELCOME = "/browse/#browse/welcome";
  private static final String AUTH_REQUIRED_WELCOME = "/browse/?login=1#browse/welcome";
  private static final Resource ADMIN_INDEX =
      new ClassPathResource("META-INF/resources/admin/index.html");

  private final SecurityAuthenticationService authenticationService;
  private final SecurityManagementService securityService;
  private final ForwardedHeaderPolicy forwardedHeaderPolicy;

  public AdminUiController(
      SecurityAuthenticationService authenticationService,
      SecurityManagementService securityService,
      ForwardedHeaderPolicy forwardedHeaderPolicy) {
    this.authenticationService = authenticationService;
    this.securityService = securityService;
    this.forwardedHeaderPolicy = forwardedHeaderPolicy;
  }

  @GetMapping("/")
  public String index(HttpServletRequest request) {
    return redirect(request, BROWSE_WELCOME);
  }

  @GetMapping({"/admin", "/admin/", "/admin/index.html"})
  public Object admin(HttpServletRequest request) {
    AuthenticatedSubject subject = authenticationService.authenticate(request).orElse(null);
    if (subject == null) {
      return redirect(request, AUTH_REQUIRED_WELCOME);
    }
    boolean allowed = securityService.decide(subject.permissionSubject(), "nexus:*").allowed()
        || java.util.List.of("read", "create", "update", "delete").stream().anyMatch(action ->
            securityService.decide(subject.permissionSubject(), "nexus:selectors:" + action).allowed());
    if (!allowed) {
      return redirect(request, BROWSE_WELCOME);
    }
    request.setAttribute(AuthenticatedSubject.REQUEST_ATTRIBUTE, subject);
    return ResponseEntity.ok()
        .contentType(MediaType.TEXT_HTML)
        .cacheControl(CacheControl.noCache())
        .body(ADMIN_INDEX);
  }

  private String redirect(HttpServletRequest request, String path) {
    return "redirect:"
        + forwardedHeaderPolicy.serverBaseUrl(request)
        + request.getContextPath()
        + path;
  }
}
