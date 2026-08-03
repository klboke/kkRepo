package com.github.klboke.kkrepo.server.management;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.github.klboke.kkrepo.server.management.NexusAssetIdCodec.InvalidContinuationTokenException;
import com.github.klboke.kkrepo.server.management.NexusAssetManagementService.AssetPage;
import com.github.klboke.kkrepo.server.management.NexusAssetManagementService.InvalidSearchRequestException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;

class NexusAssetControllerTest {

  @Test
  void searchDelegatesSupportedParametersAndRejectsUnsupportedParameters() {
    NexusAssetManagementService service = mock(NexusAssetManagementService.class);
    NexusAssetController controller = new NexusAssetController(service);
    MockHttpServletRequest request = new MockHttpServletRequest();
    AssetPage expected = new AssetPage(List.of(), null);
    when(service.search("raw-hosted", "tool", "raw", "next", request)).thenReturn(expected);

    AssetPage actual = controller.search(Map.of(
        "repository", "raw-hosted",
        "name", "tool",
        "format", "raw",
        "continuationToken", "next"), request);

    assertSame(expected, actual);
    verify(service).search("raw-hosted", "tool", "raw", "next", request);

    InvalidSearchRequestException failure = assertThrows(
        InvalidSearchRequestException.class,
        () -> controller.search(Map.of(
            "repository", "raw-hosted",
            "q", "tool"), request));
    assertEquals("Unsupported asset search parameter: q", failure.getMessage());
  }

  @Test
  void getDeleteAndExceptionHandlersPreserveHttpContracts() {
    NexusAssetManagementService service = mock(NexusAssetManagementService.class);
    NexusAssetController controller = new NexusAssetController(service);
    MockHttpServletRequest request = new MockHttpServletRequest();
    when(service.delete("asset-id", request)).thenReturn(HttpStatus.NO_CONTENT.value());

    assertNull(controller.get("asset-id", request));
    assertEquals(HttpStatus.NO_CONTENT, controller.delete("asset-id", request).getStatusCode());
    verify(service).get("asset-id", request);
    verify(service).delete("asset-id", request);

    assertEquals(HttpStatus.UNPROCESSABLE_ENTITY,
        controller.invalidAssetId(null).getStatusCode());
    var invalidContinuation = controller.invalidContinuation(
        new InvalidContinuationTokenException("Invalid continuation token"));
    assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, invalidContinuation.getStatusCode());
    assertEquals("text/plain;charset=utf-8",
        invalidContinuation.getHeaders().getContentType().toString()
            .toLowerCase(java.util.Locale.ROOT));
    assertTrue(invalidContinuation.getBody().contains("java.lang.IllegalArgumentException"));
    assertEquals(HttpStatus.BAD_REQUEST, controller.invalidSearch(null).getStatusCode());
    assertEquals(HttpStatus.NOT_FOUND, controller.notFound(null).getStatusCode());
    assertEquals(HttpStatus.METHOD_NOT_ALLOWED,
        controller.unsupportedDelete(null).getStatusCode());
  }
}
