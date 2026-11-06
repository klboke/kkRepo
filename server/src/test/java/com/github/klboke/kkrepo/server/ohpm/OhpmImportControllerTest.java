package com.github.klboke.kkrepo.server.ohpm;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.github.klboke.kkrepo.auth.AccessDecision;
import com.github.klboke.kkrepo.auth.PermissionSubject;
import com.github.klboke.kkrepo.persistence.jdbc.api.OhpmImportDao.JobState;
import com.github.klboke.kkrepo.protocol.ohpm.OhpmException;
import com.github.klboke.kkrepo.protocol.ohpm.OhpmPackageMetadata;
import com.github.klboke.kkrepo.server.security.AuthenticatedSubject;
import com.github.klboke.kkrepo.server.security.SecurityAuthenticationService;
import com.github.klboke.kkrepo.server.security.SecurityManagementService;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class OhpmImportControllerTest {
  private final OhpmImportService imports = mock(OhpmImportService.class);
  private final SecurityAuthenticationService authentication = mock(SecurityAuthenticationService.class);
  private final SecurityManagementService security = mock(SecurityManagementService.class);
  private final OhpmImportController controller = new OhpmImportController(imports, authentication, security);
  private final AuthenticatedSubject subject = new AuthenticatedSubject("Local", "alice", "api-key", 1L,
      new PermissionSubject("Local", "alice", Set.of(), "1"));
  private MockMvc mvc;

  @BeforeEach
  void setup() {
    when(authentication.authenticateFresh(any())).thenReturn(Optional.of(subject));
    when(security.decideFresh(subject.permissionSubject(), "nexus:*")).thenReturn(AccessDecision.allow());
    mvc = MockMvcBuilders.standaloneSetup(controller).build();
  }

  @Test
  void listsJobsAndSourceVersionsThroughTheManagementRoutes() throws Exception {
    when(imports.jobs("target")).thenReturn(List.of());
    when(imports.sourceVersions("job", "@scope/pkg")).thenReturn(List.of("1.0.0", "2.0.0"));
    mvc.perform(get("/internal/ohpm/imports").param("repository", "target"))
        .andExpect(status().isOk()).andExpect(result -> assertEquals("[]", result.getResponse().getContentAsString()));
    mvc.perform(get("/internal/ohpm/imports/job/source-versions").param("name", "@scope/pkg"))
        .andExpect(status().isOk())
        .andExpect(result -> assertEquals("[\"1.0.0\",\"2.0.0\"]", result.getResponse().getContentAsString()));
    verify(imports).jobs("target");
    verify(imports).sourceVersions("job", "@scope/pkg");
  }

  @Test
  void registryOperationsRecheckAuthorizationInsideTheServiceCallback() throws Exception {
    when(imports.planRegistry(eq("job"), eq("@scope/pkg"), eq("1.0.0"), any())).thenAnswer(call -> {
      ((Runnable) call.getArgument(3)).run();
      return null;
    });
    when(imports.applyRegistry(eq("job"), eq("item"), eq("alice"), eq("127.0.0.1"), any())).thenAnswer(call -> {
      ((Runnable) call.getArgument(4)).run();
      return null;
    });
    mvc.perform(post("/internal/ohpm/imports/job/registry").contentType("application/json")
        .content("{\"name\":\"@scope/pkg\",\"version\":\"1.0.0\"}")).andExpect(status().isOk());
    mvc.perform(post("/internal/ohpm/imports/job/items/item/registry")).andExpect(status().isOk());
    verify(authentication, times(4)).authenticateFresh(any());
    verify(security, times(4)).decideFresh(subject.permissionSubject(), "nexus:*");
  }

  @Test
  void revokedIdentityStopsStateTransitionAtTheTransactionBoundary() throws Exception {
    when(authentication.authenticateFresh(any())).thenReturn(Optional.of(subject), Optional.empty());
    when(imports.transition(eq("job"), eq(JobState.READY), any())).thenAnswer(call -> {
      ((Runnable) call.getArgument(2)).run();
      fail("Revoked identity must not pass the callback");
      return null;
    });
    mvc.perform(post("/internal/ohpm/imports/job/state").contentType("application/json")
        .content("{\"state\":\"READY\"}"))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void directoryMetadataAndBothInputStreamsAreClosedOnServiceFailure() throws Exception {
    var harInput = spy(new ByteArrayInputStream(new byte[] {1}));
    var hspInput = spy(new ByteArrayInputStream(new byte[] {2}));
    var metadataInput = spy(new ByteArrayInputStream("{\"name\":\"@scope/pkg\",\"versions\":{},\"dist-tags\":{}}"
        .getBytes(StandardCharsets.UTF_8)));
    var har = file("asset", "pkg.har", harInput);
    var hsp = file("hsp", "pkg.hsp", hspInput);
    var metadata = file("metadata", "metadata.json", metadataInput);
    when(imports.planDirectory(eq("job"), eq("source"), same(harInput), same(hspInput),
        eq(false), any(OhpmPackageMetadata.class), any())).thenThrow(new IOException("Read failure"));
    assertThrows(IOException.class, () -> controller.planDirectory("job", "source", har, hsp,
        metadata, "@scope/pkg", new MockHttpServletRequest()));
    verify(metadataInput).close();
    verify(harInput).close();
    verify(hspInput).close();
  }

  @Test
  void rejectsAmbiguousBundlesAndWrongExtensionsBeforeCallingTheImporter() throws Exception {
    for (String filename : new String[] {"pkg.zip", "", "pkg.tgz", "pkg.tar.gz"}) {
      mvc.perform(multipart("/internal/ohpm/imports/job/directory")
          .file(new MockMultipartFile("asset", filename, "application/octet-stream", new byte[] {1}))
          .file(new MockMultipartFile("hsp", "pkg.hsp", "application/octet-stream", new byte[] {2}))
          .param("sourceKey", "source")).andExpect(status().isBadRequest());
    }
    mvc.perform(multipart("/internal/ohpm/imports/job/directory")
        .file(new MockMultipartFile("asset", "pkg.har", "application/octet-stream", new byte[] {1}))
        .file(new MockMultipartFile("hsp", "pkg.zip", "application/octet-stream", new byte[] {2}))
        .param("sourceKey", "source")).andExpect(status().isBadRequest());
    verifyNoInteractions(imports);
  }

  @Test
  void invalidInputAndStateConflictsReturnBoundedErrorsWithoutInternalDetails() throws Exception {
    when(imports.sourceVersions("job", "bad")).thenThrow(new IllegalArgumentException("private-source-url"));
    when(imports.transition(eq("job"), eq(JobState.READY), any()))
        .thenThrow(new IllegalStateException("private-database-error"));
    mvc.perform(get("/internal/ohpm/imports/job/source-versions").param("name", "bad"))
        .andExpect(status().isBadRequest())
        .andExpect(result -> assertEquals("{\"error\":\"Invalid OHPM import input\"}", result.getResponse().getContentAsString()));
    mvc.perform(post("/internal/ohpm/imports/job/state").contentType("application/json")
        .content("{\"state\":\"READY\"}")).andExpect(status().isConflict())
        .andExpect(result -> assertEquals("{\"error\":\"Import state or source changed; refresh the report\"}",
            result.getResponse().getContentAsString()));
  }

  private static MockMultipartFile file(String field, String name, InputStream input) {
    return new MockMultipartFile(field, name, "application/octet-stream", new byte[0]) {
      @Override public InputStream getInputStream() { return input; }
    };
  }
}
