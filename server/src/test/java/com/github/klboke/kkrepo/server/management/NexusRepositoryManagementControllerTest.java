package com.github.klboke.kkrepo.server.management;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.core.RepositoryType;
import com.github.klboke.kkrepo.server.repositories.RepositoryCommands.GroupSettings;
import com.github.klboke.kkrepo.server.repositories.RepositoryNotFoundException;
import com.github.klboke.kkrepo.server.repositories.RepositoryService;
import com.github.klboke.kkrepo.server.repositories.RepositoryView;
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
    NexusRepositoryManagementController controller =
        new NexusRepositoryManagementController(service, authorizer);
    MockHttpServletRequest request = new MockHttpServletRequest();
    RepositoryView repository = view(RepositoryFormat.MAVEN2, RepositoryType.GROUP);
    when(service.get("maven-public")).thenReturn(repository);

    var response = controller.getMavenGroup("maven-public", request);

    assertEquals("maven-public", response.name());
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
        new NexusRepositoryManagementController(service, authorizer);
    when(service.get("maven-public")).thenReturn(
        view(RepositoryFormat.RAW, RepositoryType.GROUP));

    assertThrows(RepositoryNotFoundException.class,
        () -> controller.getMavenGroup("maven-public", new MockHttpServletRequest()));
  }

  private static RepositoryView view(RepositoryFormat format, RepositoryType type) {
    return new RepositoryView(
        1L, "maven-public", "maven2-group", format, type, true, "default", true,
        "/repository/maven-public/", null, null, null, null, null,
        new GroupSettings(List.of("maven-releases", "maven-central")));
  }
}
