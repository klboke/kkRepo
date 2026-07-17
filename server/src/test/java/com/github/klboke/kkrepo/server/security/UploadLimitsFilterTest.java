package com.github.klboke.kkrepo.server.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.core.RepositoryType;
import com.github.klboke.kkrepo.persistence.jdbc.api.RepositoryDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.RepositoryRecord;
import jakarta.servlet.FilterChain;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class UploadLimitsFilterTest {

  @Test
  void oversizedSwiftUploadUsesProblemDetailsWithoutReadingBody() throws Exception {
    RepositoryDao repositories = mock(RepositoryDao.class);
    when(repositories.findByName("swift-hosted")).thenReturn(Optional.of(new RepositoryRecord(
        1L, "swift-hosted", RepositoryFormat.SWIFT, RepositoryType.HOSTED, "swift-hosted",
        true, 1L, null, null, null, null, "ALLOW_ONCE", true, Map.of())));
    UploadLimitsFilter filter = new UploadLimitsFilter(
        8L, new NexusLegacyUiCompatibility(false), repositories);
    MockHttpServletRequest request = new MockHttpServletRequest(
        "PUT", "/repository/swift-hosted/Acme/Demo/1.2.3");
    request.setContent(new byte[9]);
    MockHttpServletResponse response = new MockHttpServletResponse();
    FilterChain chain = mock(FilterChain.class);

    filter.doFilter(request, response, chain);

    assertEquals(413, response.getStatus());
    assertEquals("application/problem+json", response.getContentType());
    assertEquals("1", response.getHeader("Content-Version"));
    assertTrue(response.getContentAsString().contains("\"status\":413"));
    verify(chain, never()).doFilter(request, response);
  }
}
