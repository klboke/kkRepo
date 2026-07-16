package com.github.klboke.kkrepo.server.terraform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.core.RepositoryType;
import com.github.klboke.kkrepo.server.cache.NexusCacheType;
import com.github.klboke.kkrepo.server.cache.NexusLikeCacheController;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntime;
import com.github.klboke.kkrepo.server.support.InMemorySharedCache;
import com.github.klboke.kkrepo.server.support.InMemoryVersionWatermark;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TerraformMetadataCacheTest {
  @Test
  void providerSnapshotRoundTripsInstantAndInvalidatesAfterRepositoryChange() {
    InMemorySharedCache shared = new InMemorySharedCache();
    NexusLikeCacheController controller =
        new NexusLikeCacheController(new InMemoryVersionWatermark(), 60);
    TerraformMetadataCache cache = new TerraformMetadataCache(shared, controller, true);
    RepositoryRuntime runtime = runtime(1, "terraform-proxy", RepositoryType.PROXY, List.of());
    Instant verifiedAt = Instant.parse("2026-07-16T08:00:00Z");
    Map<String, Object> body = Map.of(
        "protocols", List.of("5.0", "6.0"),
        "signing_keys", Map.of("gpg_public_keys", List.of(Map.of("ascii_armor", "PUBLIC"))));

    cache.putProvider(
        runtime, "provider\0https://registry.example", 10, verifiedAt, body,
        "download.zip", "SHA256SUMS", "SHA256SUMS.sig");

    TerraformMetadataCache.ProviderSnapshot snapshot = cache.findProvider(
        runtime, "provider\0https://registry.example", 10, verifiedAt.plusSeconds(1))
        .orElseThrow();
    assertEquals(body, snapshot.body());
    assertEquals(verifiedAt, snapshot.cacheInfo().lastVerified());
    assertEquals(NexusCacheType.METADATA, snapshot.cacheInfo().type());

    cache.invalidateMemberAfterCommit(runtime.id());

    assertTrue(cache.findProvider(
        runtime, "provider\0https://registry.example", 10, verifiedAt.plusSeconds(1)).isEmpty());
  }

  @Test
  void groupSnapshotRequiresAllCurrentMemberTokens() {
    InMemorySharedCache shared = new InMemorySharedCache();
    NexusLikeCacheController controller =
        new NexusLikeCacheController(new InMemoryVersionWatermark(), 60);
    TerraformMetadataCache cache = new TerraformMetadataCache(shared, controller, true);
    RepositoryRuntime first = runtime(11, "first", RepositoryType.HOSTED, List.of());
    RepositoryRuntime second = runtime(12, "second", RepositoryType.HOSTED, List.of());
    RepositoryRuntime group = runtime(10, "terraform-group", RepositoryType.GROUP, List.of(first, second));
    Instant verifiedAt = Instant.parse("2026-07-16T08:00:00Z");
    Map<String, Object> body = Map.of(
        "modules", List.of(Map.of("source", "acme/network/aws", "versions", List.of())));

    cache.putGroup(group, List.of(first, second), "versions", 10, verifiedAt, body);

    assertEquals(body, cache.findGroup(
        group, List.of(first, second), "versions", 10, verifiedAt.plusSeconds(1))
        .orElseThrow().body());

    controller.invalidate(first.id(), NexusCacheType.METADATA);

    assertTrue(cache.findGroup(
        group, List.of(first, second), "versions", 10, verifiedAt.plusSeconds(1)).isEmpty());
  }

  private static RepositoryRuntime runtime(
      long id, String name, RepositoryType type, List<RepositoryRuntime> members) {
    return new RepositoryRuntime(
        id, name, RepositoryFormat.TERRAFORM, type, "terraform-" + type.name().toLowerCase(),
        true, 1L, "ALLOW", null, null, true, "https://registry.example",
        null, 10, true, null, members);
  }
}
