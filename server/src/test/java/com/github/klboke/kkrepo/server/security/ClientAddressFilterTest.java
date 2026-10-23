package com.github.klboke.kkrepo.server.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.klboke.kkrepo.server.proxy.ProxyRequestAudit;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

class ClientAddressFilterTest {
  @Test
  void uploadAndProxyProvenanceUseTrustedClientWithoutChangingTransportPeer() throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setRemoteAddr("127.0.0.1");
    request.addHeader("X-Forwarded-For", "198.51.100.1, 203.0.113.45, 10.0.0.2");
    ForwardedHeaderPolicy policy = new ForwardedHeaderPolicy("127.0.0.1,10.0.0.0/24");
    new ClientAddressFilter(policy).doFilter(request, new MockHttpServletResponse(), (req, res) -> {
      RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
      try {
        assertEquals("203.0.113.45", ClientAddress.from(request));
        assertEquals("203.0.113.45", ProxyRequestAudit.currentClientIp());
        assertEquals(policy.clientAddress(request), ClientAddress.from(request));
        assertEquals("127.0.0.1", request.getRemoteAddr());
        assertTrue(policy.trusted(request));
      } finally {
        RequestContextHolder.resetRequestAttributes();
      }
    });
  }

  @Test
  void ignoresForwardedAddressesFromUntrustedPeersAndInvalidValues() throws Exception {
    for (String peer : new String[] {"127.0.0.1", "198.51.100.7"}) {
      for (String value : new String[] {"localhost", "unknown", "203.0.113.1:80", "bad\nvalue", ""}) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr(peer);
        request.addHeader("X-Forwarded-For", value);
        new ClientAddressFilter(new ForwardedHeaderPolicy("127.0.0.1"))
            .doFilter(request, new MockHttpServletResponse(), (req, res) ->
                assertEquals(peer, ClientAddress.from(request)));
      }
    }
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setRemoteAddr("198.51.100.7");
    request.addHeader("X-Forwarded-For", "203.0.113.1");
    assertEquals("198.51.100.7", new ForwardedHeaderPolicy("127.0.0.1").clientAddress(request));
    request.setRemoteAddr("127.0.0.1");
    request.removeHeader("X-Forwarded-For");
    request.addHeader("X-Forwarded-For", "2001:db8::12");
    assertEquals("2001:db8::12", new ForwardedHeaderPolicy("127.0.0.1").clientAddress(request));
  }
}
