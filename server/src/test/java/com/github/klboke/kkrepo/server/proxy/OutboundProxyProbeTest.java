package com.github.klboke.kkrepo.server.proxy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.github.klboke.kkrepo.server.proxy.OutboundProxyProbe.Result;
import com.github.klboke.kkrepo.server.proxy.ProxiedHttpClientFactory.ProxiedResponse;
import com.github.klboke.kkrepo.server.security.OutboundRequestPolicy;
import com.github.klboke.kkrepo.server.security.OutboundRequestPolicy.ResolvedHttpTarget;
import com.github.klboke.kkrepo.server.security.SecurityValidationException;
import java.io.IOException;
import java.net.SocketTimeoutException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.hc.client5.http.impl.TunnelRefusedException;
import org.apache.hc.core5.http.message.BasicHttpResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class OutboundProxyProbeTest {
  private static final String UPSTREAM = "https://upstream.example.org/repository";
  private final OutboundProxySettings settings = mock(OutboundProxySettings.class);
  private final OutboundRequestPolicy policy = mock(OutboundRequestPolicy.class);
  private final ProxiedHttpClientFactory clients = mock(ProxiedHttpClientFactory.class);
  private final OutboundProxyProbe probe = new OutboundProxyProbe(settings, policy, clients);
  private final OutboundProxySettings.Command command = new OutboundProxySettings.Command(
      1L, true, "HTTP", "proxy.example.org", 3128, null, null, false, List.of());
  private final OutboundProxyConfig proxy = new OutboundProxyConfig(
      OutboundProxyConfig.Type.HTTP, "proxy.example.org", 3128, null, null);
  private final ResolvedHttpTarget target = mock(ResolvedHttpTarget.class);

  @ParameterizedTest
  @CsvSource({"200,UPSTREAM_REACHED", "401,UPSTREAM_AUTH_REQUIRED", "403,UPSTREAM_AUTH_REQUIRED",
      "407,PROXY_AUTH_REQUIRED"})
  void reportsHttpResponsesAndClosesProbeConnections(int status, String outcome) throws Exception {
    when(settings.testProxy(command)).thenReturn(proxy);
    when(policy.resolveHttpTarget(UPSTREAM, "Proxy connection test", true)).thenReturn(target);
    var response = mock(ProxiedResponse.class);
    when(clients.execute("proxy-settings-probe", proxy, "HEAD", target, Map.of(), 5000)).thenReturn(response);
    when(response.status()).thenReturn(status);

    assertEquals(new Result(outcome, status, true), probe.test(UPSTREAM, command));
    verify(response).close();
    verify(clients).invalidate("proxy-settings-probe", proxy);
  }

  @Test
  void bypassHonorsDirectPolicyAndNeverSendsProxyCredentials() throws Exception {
    var bypassing = new OutboundProxyConfig(OutboundProxyConfig.Type.HTTP, "proxy.example.org", 3128,
        "proxy-user", "secret", Set.of("*.example.org"), 1L);
    when(settings.testProxy(command)).thenReturn(bypassing);
    when(policy.resolveHttpTarget(UPSTREAM, "Proxy connection test", false)).thenReturn(target);
    when(clients.execute("proxy-settings-probe", null, "HEAD", target, Map.of(), 5000))
        .thenThrow(new SocketTimeoutException("upstream took too long"));

    assertEquals(new Result("TIMEOUT", null, false), probe.test(UPSTREAM, command));
    verify(clients).invalidate("proxy-settings-probe", null);
  }

  @Test
  void rejectedOutboundTargetReturnsBadRequest() {
    when(settings.testProxy(command)).thenReturn(proxy);
    when(policy.resolveHttpTarget(UPSTREAM, "Proxy connection test", true))
        .thenThrow(new SecurityValidationException("upstream is blocked"));

    assertEquals(HttpStatus.BAD_REQUEST, assertThrows(ResponseStatusException.class,
        () -> probe.test(UPSTREAM, command)).getStatusCode());
    verify(clients).invalidate("proxy-settings-probe", proxy);
  }

  @ParameterizedTest
  @CsvSource({"407,PROXY_AUTH_REQUIRED", "502,PROXY_CONNECTION_FAILED"})
  void distinguishesProxyTunnelErrorsFromUpstreamResponses(int status, String outcome) throws Exception {
    when(settings.testProxy(command)).thenReturn(proxy);
    when(policy.resolveHttpTarget(UPSTREAM, "Proxy connection test", true)).thenReturn(target);
    when(clients.execute("proxy-settings-probe", proxy, "HEAD", target, Map.of(), 5000))
        .thenThrow(new IOException("CONNECT failed",
            new TunnelRefusedException(new BasicHttpResponse(status))));

    assertEquals(new Result(outcome, status, true), probe.test(UPSTREAM, command));
  }

  @Test
  void reportsTransportFailuresWithoutExposingExceptionText() throws Exception {
    when(settings.testProxy(command)).thenReturn(proxy);
    when(policy.resolveHttpTarget(UPSTREAM, "Proxy connection test", true)).thenReturn(target);
    when(clients.execute("proxy-settings-probe", proxy, "HEAD", target, Map.of(), 5000))
        .thenThrow(new IOException("connection error details"));

    assertEquals(new Result("CONNECTION_FAILED", null, true), probe.test(UPSTREAM, command));
  }
}
