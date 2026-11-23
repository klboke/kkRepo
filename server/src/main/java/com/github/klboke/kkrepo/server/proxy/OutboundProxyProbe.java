package com.github.klboke.kkrepo.server.proxy;

import com.github.klboke.kkrepo.server.security.OutboundRequestPolicy;
import com.github.klboke.kkrepo.server.security.SecurityValidationException;
import java.io.IOException;
import java.net.SocketTimeoutException;
import java.util.Map;
import org.apache.hc.client5.http.impl.TunnelRefusedException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class OutboundProxyProbe {
  private final OutboundProxySettings settings;
  private final OutboundRequestPolicy outboundPolicy;
  private final ProxiedHttpClientFactory clients;

  public OutboundProxyProbe(OutboundProxySettings settings, OutboundRequestPolicy outboundPolicy,
                            ProxiedHttpClientFactory clients) {
    this.settings = settings;
    this.outboundPolicy = outboundPolicy;
    this.clients = clients;
  }

  /** Test the unsaved setting against the configured upstream without sending repository secrets. */
  public Result test(String url, OutboundProxySettings.Command command) {
    OutboundProxyConfig proxy = OutboundProxyConfig.forTarget(settings.testProxy(command), url);
    boolean proxied = proxy != null;
    try {
      var target = outboundPolicy.resolveHttpTarget(url, "Proxy connection test", proxied);
      try (var response = clients.execute("proxy-settings-probe", proxy, "HEAD", target, Map.of(), 5000)) {
        return status(response.status(), proxied);
      }
    } catch (SecurityValidationException e) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
    } catch (IOException e) {
      for (Throwable cause = e; cause != null; cause = cause.getCause()) {
        if (cause instanceof TunnelRefusedException refused && refused.getResponse() != null) {
          int code = refused.getResponse().getCode();
          return new Result(code == 407 ? "PROXY_AUTH_REQUIRED" : "PROXY_CONNECTION_FAILED", code, true);
        }
        if (cause instanceof SocketTimeoutException) {
          return new Result("TIMEOUT", null, proxied);
        }
      }
      return new Result("CONNECTION_FAILED", null, proxied);
    } finally {
      // Unsaved credentials shouldn't live in a pool after the probe returns.
      clients.invalidate("proxy-settings-probe", proxy);
    }
  }

  private static Result status(int code, boolean proxied) {
    return new Result(switch (code) {
      case 401, 403 -> "UPSTREAM_AUTH_REQUIRED";
      case 407 -> "PROXY_AUTH_REQUIRED";
      default -> "UPSTREAM_REACHED";
    }, code, proxied);
  }

  public record Result(String outcome, Integer status, boolean proxied) {}
}
