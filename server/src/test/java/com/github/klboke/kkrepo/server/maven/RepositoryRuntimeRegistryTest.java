package com.github.klboke.kkrepo.server.maven;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.klboke.kkrepo.cache.InMemorySharedCache;
import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.core.RepositoryType;
import com.github.klboke.kkrepo.persistence.jdbc.api.RepositoryDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.RepositoryRecord;
import com.github.klboke.kkrepo.server.catalog.CatalogCacheBroadcaster;
import com.github.klboke.kkrepo.server.support.dao.RepositoryDaoAdapter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class RepositoryRuntimeRegistryTest {
  @Test
  void resolveTruncatesRecursiveGroupMembers() {
    FakeRepositoryDao dao = new FakeRepositoryDao();
    RepositoryRecord root = repo(1, "root", RepositoryType.GROUP);
    RepositoryRecord nested = repo(2, "nested", RepositoryType.GROUP);
    RepositoryRecord hosted = repo(3, "hosted", RepositoryType.HOSTED);
    dao.add(root, List.of(nested));
    dao.add(nested, List.of(root, hosted));
    dao.add(hosted, List.of());

    RepositoryRuntime runtime = new RepositoryRuntimeRegistry(dao, 0).resolve("root").orElseThrow();

    RepositoryRuntime nestedRuntime = runtime.members().get(0);
    assertEquals("nested", nestedRuntime.name());
    assertEquals("root", nestedRuntime.members().get(0).name());
    assertTrue(nestedRuntime.members().get(0).members().isEmpty());
    assertEquals("hosted", nestedRuntime.members().get(1).name());
  }

  @Test
  void catalogBroadcastFlushesCachedRuntimes() {
    FakeRepositoryDao dao = new FakeRepositoryDao();
    dao.add(repo(1, "hosted", RepositoryType.HOSTED), List.of());
    InMemoryBroadcaster broadcaster = new InMemoryBroadcaster();
    // Match Spring Boot's ObjectMapper, which ignores RepositoryRuntime's is-getter properties
    // (hosted/proxy/group) on read-back instead of failing.
    ObjectMapper mapper = new ObjectMapper()
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    RepositoryRuntimeRegistry registry = new RepositoryRuntimeRegistry(
        dao, new InMemorySharedCache(mapper, 1000, null), mapper, broadcaster, 300);
    registry.subscribeToCatalogBroadcast();

    registry.resolve("hosted").orElseThrow();
    registry.resolve("hosted").orElseThrow();
    assertEquals(1, dao.findByNameCalls); // second read served from the cached runtime

    broadcaster.publishRefresh("repository");

    registry.resolve("hosted").orElseThrow();
    assertEquals(2, dao.findByNameCalls); // broadcast flushed the cache → reloaded from MySQL
  }

  @Test
  void resolveReadsCargoRequireAuthenticationHintForProxyRepositories() {
    FakeRepositoryDao dao = new FakeRepositoryDao();
    dao.add(cargoRepo(4, "cargo-private", true), List.of());

    RepositoryRuntime runtime = new RepositoryRuntimeRegistry(dao, 0)
        .resolve("cargo-private")
        .orElseThrow();

    assertTrue(runtime.cargoRequireAuthenticationOrDefault());
  }

  @Test
  void resolveReadsProxyRemoteBearerTokenAttribute() {
    FakeRepositoryDao dao = new FakeRepositoryDao();
    dao.add(cargoProxyRepo(5, "cargo-proxy", "upstream-token"), List.of());

    RepositoryRuntime runtime = new RepositoryRuntimeRegistry(dao, 0)
        .resolve("cargo-proxy")
        .orElseThrow();

    assertEquals("upstream-token", runtime.proxyRemoteBearerToken());
  }

  @Test
  void runtimeWithProxyBearerTokenIsNotWrittenToSharedCache() {
    FakeRepositoryDao dao = new FakeRepositoryDao();
    dao.add(cargoProxyRepo(6, "cargo-private-proxy", "upstream-token"), List.of());
    ObjectMapper mapper = new ObjectMapper()
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    RepositoryRuntimeRegistry registry = new RepositoryRuntimeRegistry(
        dao, new InMemorySharedCache(mapper, 1000, null), mapper, (CatalogCacheBroadcaster) null, 300);

    registry.resolve("cargo-private-proxy").orElseThrow();
    registry.resolve("cargo-private-proxy").orElseThrow();

    assertEquals(2, dao.findByNameCalls);
  }

  @Test
  void resolveReadsOutboundProxyAttributes() {
    FakeRepositoryDao dao = new FakeRepositoryDao();
    dao.add(mavenProxyRepo(7, "maven-proxy", Map.of(
        "remoteUrl", "https://repo.maven.apache.org/maven2/",
        "outboundProxyType", "SOCKS",
        "outboundProxyHost", "192.168.1.10",
        "outboundProxyPort", 7891,
        "outboundProxyUsername", "clash-user",
        "outboundProxyPassword", "clash-pass")), List.of());

    RepositoryRuntime runtime = new RepositoryRuntimeRegistry(dao, 0)
        .resolve("maven-proxy")
        .orElseThrow();

    assertEquals(
        com.github.klboke.kkrepo.server.proxy.OutboundProxyConfig.Type.SOCKS,
        runtime.outboundProxy().type());
    assertEquals("192.168.1.10", runtime.outboundProxy().host());
    assertEquals(7891, runtime.outboundProxy().port());
    assertEquals("clash-user", runtime.outboundProxy().username());
    assertEquals("clash-pass", runtime.outboundProxy().password());
    assertTrue(runtime.outboundProxy().enabled());
  }

  @Test
  void resolveReturnsNullOutboundProxyWhenHostOrPortIsMissing() {
    FakeRepositoryDao dao = new FakeRepositoryDao();
    dao.add(mavenProxyRepo(8, "maven-proxy", Map.of(
        "remoteUrl", "https://repo.maven.apache.org/maven2/",
        "outboundProxyType", "HTTP",
        "outboundProxyPort", 7890)), List.of());

    RepositoryRuntime runtime = new RepositoryRuntimeRegistry(dao, 0)
        .resolve("maven-proxy")
        .orElseThrow();

    assertNull(runtime.outboundProxy());
  }

  @Test
  void runtimeWithOutboundProxyPasswordIsNotWrittenToSharedCache() {
    FakeRepositoryDao dao = new FakeRepositoryDao();
    dao.add(mavenProxyRepo(9, "maven-proxied-proxy", Map.of(
        "remoteUrl", "https://repo.maven.apache.org/maven2/",
        "outboundProxyType", "HTTP",
        "outboundProxyHost", "192.168.1.10",
        "outboundProxyPort", 7890,
        "outboundProxyUsername", "clash-user",
        "outboundProxyPassword", "clash-pass")), List.of());
    ObjectMapper mapper = new ObjectMapper()
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    RepositoryRuntimeRegistry registry = new RepositoryRuntimeRegistry(
        dao, new InMemorySharedCache(mapper, 1000, null), mapper, (CatalogCacheBroadcaster) null, 300);

    registry.resolve("maven-proxied-proxy").orElseThrow();
    registry.resolve("maven-proxied-proxy").orElseThrow();

    assertEquals(2, dao.findByNameCalls);
  }

  private static RepositoryRecord mavenProxyRepo(long id, String name, Map<String, Object> proxy) {
    return new RepositoryRecord(
        id,
        name,
        RepositoryFormat.MAVEN2,
        RepositoryType.PROXY,
        "maven2-proxy",
        true,
        1L,
        null,
        "https://repo.maven.apache.org/maven2/",
        null,
        null,
        "ALLOW",
        true,
        Map.of("proxy", proxy));
  }

  private static RepositoryRecord repo(long id, String name, RepositoryType type) {    return new RepositoryRecord(
        id,
        name,
        RepositoryFormat.MAVEN2,
        type,
        type == RepositoryType.GROUP ? "maven2-group" : "maven2-hosted",
        true,
        1L,
        null,
        null,
        "MIXED",
        "PERMISSIVE",
        "ALLOW",
        true,
        Map.of());
  }

  private static RepositoryRecord cargoRepo(long id, String name, boolean requireAuthentication) {
    return new RepositoryRecord(
        id,
        name,
        RepositoryFormat.CARGO,
        RepositoryType.PROXY,
        "cargo-proxy",
        true,
        1L,
        null,
        "https://index.crates.io/",
        null,
        null,
        "ALLOW",
        true,
        Map.of("cargo", Map.of("requireAuthentication", requireAuthentication)));
  }

  private static RepositoryRecord cargoProxyRepo(long id, String name, String bearerToken) {
    return new RepositoryRecord(
        id,
        name,
        RepositoryFormat.CARGO,
        RepositoryType.PROXY,
        "cargo-proxy",
        true,
        1L,
        null,
        "https://index.crates.io/",
        null,
        null,
        "ALLOW",
        true,
        Map.of("proxy", Map.of(
            "remoteUrl", "https://index.crates.io/",
            "remoteBearerToken", bearerToken)));
  }

  private static class FakeRepositoryDao extends RepositoryDaoAdapter {
    private final Map<Long, RepositoryRecord> byId = new HashMap<>();
    private final Map<String, RepositoryRecord> byName = new HashMap<>();
    private final Map<Long, List<RepositoryRecord>> members = new HashMap<>();
    private int findByNameCalls;

    FakeRepositoryDao() {
      super(null, null);
    }

    void add(RepositoryRecord record, List<RepositoryRecord> memberRecords) {
      byId.put(record.id(), record);
      byName.put(record.name(), record);
      members.put(record.id(), memberRecords);
    }

    @Override
    public Optional<RepositoryRecord> findById(long id) {
      return Optional.ofNullable(byId.get(id));
    }

    @Override
    public Optional<RepositoryRecord> findByName(String name) {
      findByNameCalls++;
      return Optional.ofNullable(byName.get(name));
    }

    @Override
    public List<RepositoryRecord> listMembers(long groupRepositoryId) {
      return members.getOrDefault(groupRepositoryId, List.of());
    }
  }

  private static final class InMemoryBroadcaster implements CatalogCacheBroadcaster {
    private final Map<String, List<Runnable>> listeners = new LinkedHashMap<>();

    @Override
    public void subscribe(String catalogName, Runnable refreshListener) {
      listeners.computeIfAbsent(catalogName, ignored -> new ArrayList<>()).add(refreshListener);
    }

    @Override
    public void publishRefresh(String catalogName) {
      List.copyOf(listeners.getOrDefault(catalogName, List.of())).forEach(Runnable::run);
    }
  }
}
