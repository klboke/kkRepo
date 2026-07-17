package com.github.klboke.kkrepo.server.proxy;

import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.junit.jupiter.api.Test;

class ProxiedHttpClientFactoryTest {

  @Test
  void clientForAcceptsUsernameOnlyHttpProxyWithoutPassword() throws Exception {
    ProxiedHttpClientFactory factory = new ProxiedHttpClientFactory();
    // authenticated() is true (username present) but the password is null — previously this threw an
    // NPE inside buildHttp because config.password().toCharArray() was dereferenced unconditionally.
    OutboundProxyConfig usernameOnly =
        new OutboundProxyConfig(OutboundProxyConfig.Type.HTTP, "127.0.0.1", 7890, "clash-user", null);
    try (CloseableHttpClient client = factory.clientFor(usernameOnly)) {
      assertNotNull(client);
    }
  }

  @Test
  void clientForAcceptsUsernameOnlySocksProxyWithoutPassword() throws Exception {
    ProxiedHttpClientFactory factory = new ProxiedHttpClientFactory();
    OutboundProxyConfig usernameOnly =
        new OutboundProxyConfig(OutboundProxyConfig.Type.SOCKS, "127.0.0.1", 7890, "clash-user", null);
    try (CloseableHttpClient client = factory.clientFor(usernameOnly)) {
      assertNotNull(client);
    }
  }

  @Test
  void clientForBuildsFullyConfiguredHttpProxy() throws Exception {
    ProxiedHttpClientFactory factory = new ProxiedHttpClientFactory();
    OutboundProxyConfig config =
        new OutboundProxyConfig(OutboundProxyConfig.Type.HTTP, "127.0.0.1", 7890, "clash-user", "clash-pass");
    try (CloseableHttpClient client = factory.clientFor(config)) {
      assertNotNull(client);
    }
  }
}
