package com.github.klboke.kkrepo.scanner;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class ScannerRequestSecurityFilterTest {
  private ScannerAdapterProperties properties;
  private ScannerRequestSecurityFilter filter;

  @BeforeEach
  void setUp() {
    properties = new ScannerAdapterProperties();
    properties.setServiceCredential("secret");
    properties.setMaxOciRequestBytes(1024);
    filter = new ScannerRequestSecurityFilter(properties);
  }

  @Test
  void rejectsInvalidCredentialBeforeTheOciBodyIsRead() throws Exception {
    MockHttpServletRequest request = request("/v1/oci/scan", new byte[1025]);
    MockHttpServletResponse response = new MockHttpServletResponse();
    FilterChain chain = mock(FilterChain.class);

    filter.doFilter(request, response, chain);

    assertEquals(401, response.getStatus());
    assertTrue(response.getContentAsString().contains("SCANNER_UNAUTHORIZED"));
    verify(chain, never()).doFilter(any(), any());
  }

  @Test
  void normalizesMatrixParametersBeforeAuthenticationAndOciBodyLimiting()
      throws Exception {
    MockHttpServletResponse unauthorizedResponse = new MockHttpServletResponse();
    FilterChain unauthorizedChain = mock(FilterChain.class);

    filter.doFilter(
        request("/v1;x=1/oci/scan", new byte[1025]),
        unauthorizedResponse,
        unauthorizedChain);

    assertEquals(401, unauthorizedResponse.getStatus());
    verify(unauthorizedChain, never()).doFilter(any(), any());

    MockHttpServletResponse oversizedResponse = new MockHttpServletResponse();
    FilterChain oversizedChain = mock(FilterChain.class);

    filter.doFilter(
        authorized(request("/v1/oci/scan;x=1", new byte[1025])),
        oversizedResponse,
        oversizedChain);

    assertEquals(413, oversizedResponse.getStatus());
    verify(oversizedChain, never()).doFilter(any(), any());
  }

  @Test
  void rejectsKnownAndChunkedOversizedOciJsonBeforeMvc() throws Exception {
    FilterChain knownChain = mock(FilterChain.class);
    MockHttpServletResponse knownResponse = new MockHttpServletResponse();
    MockHttpServletRequest known = authorized(request("/v1/oci/scan", new byte[1025]));

    filter.doFilter(known, knownResponse, knownChain);

    assertEquals(413, knownResponse.getStatus());
    assertTrue(knownResponse.getContentAsString().contains("SCANNER_OCI_REQUEST_TOO_LARGE"));
    verify(knownChain, never()).doFilter(any(), any());

    FilterChain chunkedChain = mock(FilterChain.class);
    MockHttpServletResponse chunkedResponse = new MockHttpServletResponse();
    MockHttpServletRequest chunked = authorized(new MockHttpServletRequest() {
      @Override
      public int getContentLength() {
        return -1;
      }

      @Override
      public long getContentLengthLong() {
        return -1;
      }
    });
    chunked.setMethod("POST");
    chunked.setRequestURI("/v1/oci/scan");
    chunked.setContent(new byte[1025]);

    filter.doFilter(chunked, chunkedResponse, chunkedChain);

    assertEquals(413, chunkedResponse.getStatus());
    verify(chunkedChain, never()).doFilter(any(), any());
  }

  @Test
  void replaysBoundedOciJsonToMvcAndLeavesStreamingEndpointsUnbuffered()
      throws Exception {
    byte[] json = "{\"apiVersion\":\"v1\"}".getBytes(StandardCharsets.UTF_8);
    MockHttpServletRequest oci = authorized(request("/scanner/v1/oci/scan", json));
    oci.setContextPath("/scanner");
    MockHttpServletResponse ociResponse = new MockHttpServletResponse();
    FilterChain ociChain = mock(FilterChain.class);
    doAnswer(invocation -> {
      HttpServletRequest secured = invocation.getArgument(0);
      assertEquals(json.length, secured.getContentLength());
      assertEquals(json.length, secured.getContentLengthLong());
      var stream = secured.getInputStream();
      assertTrue(stream.isReady());
      assertFalse(stream.isFinished());
      assertEquals(json.length, stream.available());
      stream.setReadListener(null);
      assertArrayEquals(json, stream.readAllBytes());
      assertTrue(stream.isFinished());
      assertEquals("{\"apiVersion\":\"v1\"}", secured.getReader().readLine());
      return null;
    }).when(ociChain).doFilter(any(), any());

    filter.doFilter(oci, ociResponse, ociChain);

    verify(ociChain).doFilter(any(), any());

    byte[] artifact = new byte[2048];
    MockHttpServletRequest catalog = authorized(request("/v1/catalog", artifact));
    MockHttpServletResponse catalogResponse = new MockHttpServletResponse();
    FilterChain catalogChain = mock(FilterChain.class);

    filter.doFilter(catalog, catalogResponse, catalogChain);

    verify(catalogChain).doFilter(catalog, catalogResponse);
  }

  @Test
  void ignoresNonApiEndpointsAndRequiresCredentialAtStartup() throws Exception {
    MockHttpServletRequest health = request("/actuator/health/readiness", new byte[0]);
    MockHttpServletResponse response = new MockHttpServletResponse();
    FilterChain chain = mock(FilterChain.class);

    filter.doFilter(health, response, chain);

    verify(chain).doFilter(health, response);

    properties.setServiceCredential("");
    IllegalStateException failure =
        assertThrows(IllegalStateException.class, filter::requireConfiguredCredential);
    assertEquals(
        "kkrepo.scanner.service-credential must be configured",
        failure.getMessage());
  }

  private static MockHttpServletRequest request(String path, byte[] body) {
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setMethod("POST");
    request.setRequestURI(path);
    request.setContent(body);
    return request;
  }

  private static <T extends MockHttpServletRequest> T authorized(T request) {
    request.addHeader(ScannerRequestSecurityFilter.CREDENTIAL_HEADER, "secret");
    return request;
  }
}
