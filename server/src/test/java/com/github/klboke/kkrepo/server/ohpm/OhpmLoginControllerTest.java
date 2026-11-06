package com.github.klboke.kkrepo.server.ohpm;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.github.klboke.kkrepo.auth.AccessDecision;
import com.github.klboke.kkrepo.auth.PermissionSubject;
import com.github.klboke.kkrepo.server.security.AuthenticatedSubject;
import com.github.klboke.kkrepo.server.security.SecurityAuthenticationService;
import com.github.klboke.kkrepo.server.security.SecurityManagementService;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class OhpmLoginControllerTest {
  private final OhpmLoginService login = mock(OhpmLoginService.class);
  private final SecurityAuthenticationService authentication = mock(SecurityAuthenticationService.class);
  private final SecurityManagementService security = mock(SecurityManagementService.class);
  private final AuthenticatedSubject subject = new AuthenticatedSubject("Local", "alice", "api-key", 1L,
      new PermissionSubject("Local", "alice", Set.of(), "1"));
  private MockMvc mvc;

  @BeforeEach
  void setup() {
    when(authentication.authenticateFresh(any())).thenReturn(Optional.of(subject));
    when(security.decideFresh(subject.permissionSubject(), "nexus:*")).thenReturn(AccessDecision.allow());
    mvc = MockMvcBuilders.standaloneSetup(new OhpmLoginController(login, authentication, security)).build();
  }

  @Test
  void keyPaginationRequiresAFreshAdministratorIdentity() throws Exception {
    when(login.keys(100)).thenReturn(List.of());
    mvc.perform(get("/internal/ohpm/login/keys").param("offset", "100"))
        .andExpect(status().isOk()).andExpect(result -> assertEquals("[]", result.getResponse().getContentAsString()));
    verify(login).keys(100);
    when(authentication.authenticateFresh(any())).thenReturn(Optional.empty());
    mvc.perform(get("/internal/ohpm/login/keys").param("offset", "200")).andExpect(status().isUnauthorized());
    verify(login, never()).keys(200);
  }

  @Test
  void rotationUsesTheSelectedIdentityAndRechecksAuthorization() throws Exception {
    doAnswer(invocation -> {
      invocation.getArgument(2, Runnable.class).run();
      return null;
    }).when(login).rotateKey(eq("publish-id"), eq("replacement"), any());
    mvc.perform(put("/internal/ohpm/login/keys/publish-id/public-key").contentType("application/json")
        .content("{\"publicKey\":\"replacement\"}")).andExpect(status().isOk());
    verify(authentication, times(2)).authenticateFresh(any());
    verify(login).rotateKey(eq("publish-id"), eq("replacement"), any());
    clearInvocations(login);
    when(authentication.authenticateFresh(any())).thenReturn(Optional.empty());
    mvc.perform(put("/internal/ohpm/login/keys/publish-id/public-key").contentType("application/json")
        .content("{\"publicKey\":\"replacement\"}")).andExpect(status().isUnauthorized());
    when(authentication.authenticateFresh(any())).thenReturn(Optional.of(subject));
    when(security.decideFresh(subject.permissionSubject(), "nexus:*"))
        .thenReturn(AccessDecision.deny("Administrator required"));
    mvc.perform(put("/internal/ohpm/login/keys/publish-id/public-key").contentType("application/json")
        .content("{\"publicKey\":\"replacement\"}")).andExpect(status().isForbidden());
    verifyNoInteractions(login);
  }

  @Test
  void malformedPublicKeysReturnAGenericErrorWithoutEchoingTheSuppliedKey() throws Exception {
    when(login.putKey(eq("Local"), eq("alice"), eq("invalid-key-material"), eq(true), any()))
        .thenThrow(new IllegalArgumentException("invalid-key-material"));
    mvc.perform(put("/internal/ohpm/login/keys").contentType("application/json")
        .content("{\"source\":\"Local\",\"userId\":\"alice\",\"publicKey\":\"invalid-key-material\",\"enabled\":true}"))
        .andExpect(status().isBadRequest())
        .andExpect(result -> assertEquals("{\"error\":\"Invalid OHPM public key or owner\"}",
            result.getResponse().getContentAsString()));
  }
}
