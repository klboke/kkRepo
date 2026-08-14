package com.github.klboke.kkrepo.server.proxy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

class ProxyRequestAuditTest {
  @AfterEach
  void resetRequestContext() {
    RequestContextHolder.resetRequestAttributes();
  }

  @Test
  void returnsCurrentRequestRemoteAddress() {
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setRemoteAddr("2001:db8::209");
    RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));

    assertEquals("2001:db8::209", ProxyRequestAudit.currentClientIp());
  }

  @Test
  void returnsNullOutsideAClientRequest() {
    assertNull(ProxyRequestAudit.currentClientIp());
  }
}
