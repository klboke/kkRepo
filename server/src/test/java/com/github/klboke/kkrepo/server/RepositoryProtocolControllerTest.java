package com.github.klboke.kkrepo.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.github.klboke.kkrepo.server.routing.RepositoryProtocolDispatcher;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.multipart.MultipartFile;

class RepositoryProtocolControllerTest {
  @Test
  void delegatesEveryHttpEntryPointWithoutOwningProtocolLogic() throws Exception {
    RepositoryProtocolDispatcher dispatcher = mock(RepositoryProtocolDispatcher.class);
    RepositoryProtocolController controller = new RepositoryProtocolController(dispatcher);
    MockHttpServletRequest request = new MockHttpServletRequest();
    MultipartFile chart = mock(MultipartFile.class);
    ResponseEntity<?> get = ResponseEntity.status(200).build();
    ResponseEntity<?> head = ResponseEntity.status(204).build();
    ResponseEntity<?> put = ResponseEntity.status(201).build();
    ResponseEntity<?> typedPut = ResponseEntity.status(202).build();
    ResponseEntity<?> delete = ResponseEntity.status(203).build();
    ResponseEntity<?> post = ResponseEntity.status(205).build();
    ResponseEntity<?> helm = ResponseEntity.status(206).build();
    doReturn(get).when(dispatcher).dispatchRead("repo", request, HttpMethod.GET);
    doReturn(head).when(dispatcher).dispatchRead("repo", request, HttpMethod.HEAD);
    doReturn(put).when(dispatcher)
        .dispatch("repo", request, HttpMethod.PUT, (MultipartFile) null);
    doReturn(typedPut).when(dispatcher)
        .dispatch("repo", request, HttpMethod.PUT, "application/octet-stream", null);
    doReturn(delete).when(dispatcher)
        .dispatch("repo", request, HttpMethod.DELETE, (MultipartFile) null);
    doReturn(post).when(dispatcher)
        .dispatch("repo", request, HttpMethod.POST, (MultipartFile) null);
    doReturn(helm).when(dispatcher).dispatch("repo", request, HttpMethod.POST, chart);

    assertSame(get, controller.get("repo", request));
    assertSame(head, controller.head("repo", request));
    assertSame(put, controller.put("repo", request));
    assertSame(typedPut, controller.put("repo", request, "application/octet-stream"));
    assertSame(delete, controller.delete("repo", request));
    assertSame(post, controller.post("repo", request));
    assertSame(helm, controller.pushHelmChart("repo", chart, request));
    assertEquals(206, helm.getStatusCode().value());

    verify(dispatcher).dispatch("repo", request, HttpMethod.POST, chart);
  }

  @Test
  void translatesCheckedPostFailureAtTheHttpBoundary() throws Exception {
    RepositoryProtocolDispatcher dispatcher = mock(RepositoryProtocolDispatcher.class);
    RepositoryProtocolController controller = new RepositoryProtocolController(dispatcher);
    MockHttpServletRequest request = new MockHttpServletRequest();
    doThrow(new java.io.IOException("failed")).when(dispatcher)
        .dispatch("repo", request, HttpMethod.POST, (MultipartFile) null);

    IllegalStateException error = assertThrows(
        IllegalStateException.class, () -> controller.post("repo", request));

    assertEquals("Repository POST handler failed", error.getMessage());
    assertEquals("failed", error.getCause().getMessage());
  }
}
