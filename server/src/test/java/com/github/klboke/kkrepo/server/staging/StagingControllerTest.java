package com.github.klboke.kkrepo.server.staging;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.github.klboke.kkrepo.auth.PermissionSubject;
import com.github.klboke.kkrepo.persistence.jdbc.api.StagingDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.StagingSessionRecord;
import com.github.klboke.kkrepo.server.security.AuthenticatedSubject;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class StagingControllerTest {
  private final StagingService service = mock(StagingService.class);
  private final MockMvc mvc = MockMvcBuilders
      .standaloneSetup(new StagingController(service))
      .build();
  private final PermissionSubject alice =
      new PermissionSubject("Local", "alice", Set.of(), null);
  private final AuthenticatedSubject subject =
      new AuthenticatedSubject("Local", "alice", "local", null, alice);

  @Test
  void createsASession() throws Exception {
    when(service.createWithCredential(any(), eq(alice), any())).thenReturn(new StagingService.CreateResult(view(), null));
    String body = mvc.perform(post("/internal/staging/sessions")
            .requestAttr(AuthenticatedSubject.REQUEST_ATTRIBUTE, subject)
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"name":"release","repositories":["raw-release"],"approvalRequired":true}
                """))
        .andExpect(status().isOk())
        .andReturn().getResponse().getContentAsString();
    assertTrue(body.contains("\"release\""));
    assertTrue(body.contains("\"itemCount\":0"));
  }

  @Test
  void returnsAdminMetrics() throws Exception {
    when(service.metrics(alice)).thenReturn(new StagingService.StagingMetrics(
        Map.of(StagingDao.SESSION_OPEN, 2L), Map.of(), Map.of(), Instant.now()));
    String body = mvc.perform(get("/internal/staging/metrics")
            .requestAttr(AuthenticatedSubject.REQUEST_ATTRIBUTE, subject))
        .andExpect(status().isOk())
        .andReturn().getResponse().getContentAsString();
    assertTrue(body.contains("\"OPEN\":2"));
  }

  @Test
  void requiresAuthentication() throws Exception {
    mvc.perform(get("/internal/staging/sessions"))
        .andExpect(status().isUnauthorized());
  }

  private static StagingService.StagingSessionView view() {
    Instant now = Instant.now();
    return new StagingService.StagingSessionView(
        new StagingSessionRecord(
            1L, "release", StagingDao.SESSION_OPEN, 0, "Local", "alice", now, now, null,
            null, now.plusSeconds(3600), null, Map.of("approvalRequired", true)),
        List.of(new StagingService.StagingRepositoryView(1L, "raw-release", "raw")),
        0);
  }
}
