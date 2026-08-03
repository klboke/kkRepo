package com.github.klboke.kkrepo.server.management;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.core.RepositoryType;
import com.github.klboke.kkrepo.server.repositories.RepositoryCommands.GroupSettings;
import com.github.klboke.kkrepo.server.repositories.RepositoryCommands.HostedSettings;
import com.github.klboke.kkrepo.server.repositories.RepositoryNotFoundException;
import com.github.klboke.kkrepo.server.repositories.RepositoryService;
import com.github.klboke.kkrepo.server.repositories.RepositoryView;
import com.github.klboke.kkrepo.server.security.ForwardedHeaderPolicy;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.mock.web.MockHttpServletRequest;

class NexusRepositoryManagementControllerTest {
  @Test
  void returnsNexusMavenGroupShapeAndAuthorizesBeforeLookup() {
    RepositoryService service = mock(RepositoryService.class);
    NexusRepositoryManagementAuthorizer authorizer =
        mock(NexusRepositoryManagementAuthorizer.class);
    ForwardedHeaderPolicy forwarded = mock(ForwardedHeaderPolicy.class);
    NexusRepositoryManagementController controller =
        new NexusRepositoryManagementController(service, authorizer, forwarded);
    MockHttpServletRequest request = new MockHttpServletRequest();
    RepositoryView repository = view(RepositoryFormat.MAVEN2, RepositoryType.GROUP);
    when(service.get("maven-public")).thenReturn(repository);
    when(forwarded.serverBaseUrl(request)).thenReturn("https://repo.example");

    var response = (NexusRepositoryManagementController.MavenGroupRepositoryView)
        controller.getMavenGroup("maven-public", request);

    assertEquals("maven-public", response.name());
    assertEquals("maven2", response.format());
    assertEquals("group", response.type());
    assertEquals("https://repo.example/repository/maven-public", response.url());
    assertEquals(true, response.online());
    assertEquals("default", response.storage().blobStoreName());
    assertEquals(true, response.storage().strictContentTypeValidation());
    assertEquals(List.of("maven-releases", "maven-central"), response.group().memberNames());
    InOrder order = inOrder(authorizer, service);
    order.verify(authorizer).requireRepositoryAdmin(
        request, RepositoryFormat.MAVEN2, "maven-public", "read");
    order.verify(service).get("maven-public");
  }

  @Test
  void wrongRecipeIsNotExposedThroughMavenGroupRoute() {
    RepositoryService service = mock(RepositoryService.class);
    NexusRepositoryManagementAuthorizer authorizer =
        mock(NexusRepositoryManagementAuthorizer.class);
    NexusRepositoryManagementController controller =
        new NexusRepositoryManagementController(
            service, authorizer, mock(ForwardedHeaderPolicy.class));
    when(service.get("maven-public")).thenReturn(
        view(RepositoryFormat.RAW, RepositoryType.GROUP));

    assertThrows(RepositoryNotFoundException.class,
        () -> controller.getMavenGroup("maven-public", new MockHttpServletRequest()));
  }

  @Test
  void nexusTypedGroupRouteReturnsTheActualMavenHostedShape() {
    RepositoryService service = mock(RepositoryService.class);
    NexusRepositoryManagementAuthorizer authorizer =
        mock(NexusRepositoryManagementAuthorizer.class);
    ForwardedHeaderPolicy forwarded = mock(ForwardedHeaderPolicy.class);
    NexusRepositoryManagementController controller =
        new NexusRepositoryManagementController(service, authorizer, forwarded);
    MockHttpServletRequest request = new MockHttpServletRequest();
    RepositoryView repository = new RepositoryView(
        1L, "maven-releases", "maven2-hosted", RepositoryFormat.MAVEN2,
        RepositoryType.HOSTED, true, "default", false,
        "/repository/maven-releases/",
        new HostedSettings("ALLOW_ONCE", "RELEASE", "STRICT"),
        null, null, null, null, null);
    when(service.get("maven-releases")).thenReturn(repository);
    when(forwarded.serverBaseUrl(request)).thenReturn("https://repo.example");

    var response = controller.getMavenGroup("maven-releases", request);
    var hosted = (NexusRepositoryManagementController.MavenHostedRepositoryView) response;

    assertEquals("maven-releases", hosted.name());
    assertEquals("https://repo.example/repository/maven-releases", hosted.url());
    assertEquals("hosted", hosted.type());
    assertEquals("ALLOW_ONCE", hosted.storage().writePolicy());
    assertEquals("RELEASE", hosted.maven().versionPolicy());
    assertEquals("STRICT", hosted.maven().layoutPolicy());
    assertEquals(null, hosted.cleanup());

    ObjectMapper mapper = new ObjectMapper()
        .setSerializationInclusion(JsonInclude.Include.NON_NULL);
    JsonNode json = mapper.valueToTree(response);
    assertEquals(8, json.size());
    assertTrue(json.has("cleanup"));
    assertTrue(json.path("cleanup").isNull());
  }

  private static RepositoryView view(RepositoryFormat format, RepositoryType type) {
    return new RepositoryView(
        1L, "maven-public", "maven2-group", format, type, true, "default", true,
        "/repository/maven-public/", null, null, null, null, null,
        new GroupSettings(List.of("maven-releases", "maven-central")));
  }
}
