package com.github.klboke.kkrepo.persistence.jdbc.contract;

import static org.junit.jupiter.api.Assertions.*;

import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.persistence.jdbc.api.PersistenceHashes;
import com.github.klboke.kkrepo.persistence.jdbc.api.PersistenceStores;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetRecord;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.Callable;
import java.util.stream.IntStream;

public final class OhpmRepositoryDaoContract {
  private OhpmRepositoryDaoContract() {}

  public static void verify(PersistenceStores stores, long repository) throws Exception {
    var dao = stores.ohpmRepository();
    String packageName = "@scope/compact";
    Map<String, Object> versions = new LinkedHashMap<>();
    for (int version = 0; version < 1000; version++) {
      versions.put("1.0." + version, Map.of("name", packageName, "version", "1.0." + version));
    }
    versions.put("1.2.3-rc.1+build.7", Map.of("version", "1.2.3-rc.1+build.7"));
    versions.put("quote\".key", Map.of("marker", "literal"));
    var metadata = Map.of("name", packageName, "versions", versions, "packageType", "InterfaceHar", "hspType", "bundle_app");
    var attributes = Map.<String, Object>of("ohpmMetadata", metadata, "ohpmProxyMetadata", metadata,
        "ohpmProxySource", "source", "cache", Map.of("cache_token", "generation"));
    stores.assets().tryInsertAsset(new AssetRecord(null, repository, null, null, RepositoryFormat.OHPM,
        packageName, PersistenceHashes.pathHash(packageName), "compact", "ohpm-metadata",
        "application/json", null, null, Instant.now(), attributes));
    for (boolean proxy : new boolean[] {false, true}) {
      var selected = dao.findVersion(repository, packageName, "1.0.0", proxy).orElseThrow();
      assertEquals(Map.of("name", packageName, "version", "1.0.0"), selected.manifest());
      assertEquals("InterfaceHar", selected.packageType());
      assertEquals("bundle_app", selected.hspType());
      assertEquals("source", selected.source());
      assertEquals(Map.of("cache_token", "generation"), selected.cache());
      assertTrue(dao.findVersion(repository, packageName, "2.0.0", proxy).orElseThrow().manifest().isEmpty());
      assertTrue(dao.findVersion(repository, "missing", "1.0.0", proxy).isEmpty());
      assertEquals(Map.of("version", "1.2.3-rc.1+build.7"),
          dao.findVersion(repository, packageName, "1.2.3-rc.1+build.7", proxy).orElseThrow().manifest());
      assertEquals(Map.of("marker", "literal"),
          dao.findVersion(repository, packageName, "quote\".key", proxy).orElseThrow().manifest());
    }
    var root = stores.assets().findAssetByPath(repository, packageName).orElseThrow();
    var removed = new LinkedHashMap<>(attributes);
    removed.remove("ohpmProxyMetadata");
    removed.put("ohpmProxyMissing", 451);
    stores.assets().updateAssetAttributes(root.id(), removed);
    assertTrue(dao.findVersion(repository, packageName, "1.0.0", true).isEmpty());
    assertFalse(dao.findVersion(repository, packageName, "1.0.0", false).orElseThrow().manifest().isEmpty());
    String key = "e".repeat(64);
    Instant now = Instant.now();
    try (var executor = Executors.newFixedThreadPool(8)) {
      var tasks = IntStream.range(0, 8).mapToObj(index -> (Callable<Boolean>) () ->
          dao.acquire(key, UUID.randomUUID().toString(), now, now.plusSeconds(30)).isPresent()).toList();
      int winners = 0;
      for (var result : executor.invokeAll(tasks)) if (result.get()) winners++;
      assertEquals(1, winners);
    }
    var recovered = dao.acquire(key, "replacement", now.plusSeconds(31), now.plusSeconds(61)).orElseThrow();
    assertEquals(2, recovered.fence());
    assertFalse(dao.renew(key, "old-owner", 1, now.plusSeconds(32), now.plusSeconds(90)));
    assertTrue(dao.renew(key, recovered.owner(), recovered.fence(), now.plusSeconds(32), now.plusSeconds(90)));
    dao.release(key, "old-owner", 1);
    assertTrue(dao.acquire(key, "contender", now.plusSeconds(33), now.plusSeconds(60)).isEmpty());
    dao.release(key, recovered.owner(), recovered.fence());
    var next = dao.acquire(key, "next", now, now.plusSeconds(30)).orElseThrow();
    assertEquals(3, next.fence());
    assertFalse(dao.renew(key, next.owner(), next.fence(), now.plusSeconds(31), now.plusSeconds(60)));
  }
}
