package com.github.klboke.kkrepo.server.upload;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.klboke.kkrepo.server.security.ClientAddressFilter;
import com.github.klboke.kkrepo.server.security.ForwardedHeaderPolicy;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockMultipartHttpServletRequest;

class ComponentLegacyUiUploadControllerTest {
  @Test
  void legacyUploadUsesTheVerifiedClientAndPreservesItsIframeResponse() throws Exception {
    var service = mock(ComponentUploadService.class);
    when(service.upload(eq("raw"), any(), any(), eq("anonymous"), eq("203.0.113.45")))
        .thenReturn(new ComponentUploadService.UploadResult(List.of("demo.txt")));
    var controller = new ComponentLegacyUiUploadController(service, new ObjectMapper());
    var request = new MockMultipartHttpServletRequest();
    request.setRemoteAddr("127.0.0.1");
    request.addHeader("X-Forwarded-For", "198.51.100.1, 203.0.113.45");
    new ClientAddressFilter(new ForwardedHeaderPolicy("127.0.0.1"))
        .doFilter(request, new MockHttpServletResponse(), (req, res) -> {
          var response = controller.uploadFromUi("raw", request, request);
          assertTrue(response.getBody().contains("<textarea>"));
          assertTrue(response.getBody().contains("\"success\":true"), response.getBody());
        });
    verify(service).upload(eq("raw"), any(), any(), eq("anonymous"), eq("203.0.113.45"));
  }
}
