package com.github.klboke.kkrepo.server.management;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.github.klboke.kkrepo.server.management.NexusAssetManagementService.InvalidSearchRequestException;
import com.github.klboke.kkrepo.server.management.NexusComponentSearchService.ComponentPage;
import com.github.klboke.kkrepo.server.management.NexusComponentSearchService.SearchRequest;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class NexusComponentSearchControllerTest {
  @Test
  void mapsCompanySearchParametersToTheService() {
    NexusComponentSearchService service = mock(NexusComponentSearchService.class);
    NexusComponentSearchController controller = new NexusComponentSearchController(service);
    MockHttpServletRequest request = new MockHttpServletRequest();
    SearchRequest expected = new SearchRequest(
        "demo", "maven-releases", "maven2", "com.acme", "demo", "1.0", "next");
    ComponentPage page = new ComponentPage(List.of(), null);
    when(service.search(expected, request)).thenReturn(page);

    assertEquals(page, controller.search(Map.of(
        "q", "demo",
        "repository", "maven-releases",
        "format", "maven2",
        "group", "com.acme",
        "name", "demo",
        "version", "1.0",
        "continuationToken", "next"), request));
    verify(service).search(expected, request);
  }

  @Test
  void rejectsUnsupportedFiltersInsteadOfReturningIncorrectResults() {
    NexusComponentSearchController controller =
        new NexusComponentSearchController(mock(NexusComponentSearchService.class));

    assertThrows(InvalidSearchRequestException.class,
        () -> controller.search(Map.of("sha1", "abc"), new MockHttpServletRequest()));
  }
}
