package com.github.klboke.kkrepo.server.arch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.ComponentRecord;
import com.github.klboke.kkrepo.protocol.arch.ArchDesc;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ArchDatabaseTest {
  @Test
  void buildsDeterministicDatabaseFromDurableComponents() {
    ArchDesc first = desc("zeta", "1.0.0-1", "x86_64");
    ArchDesc second = desc("alpha", "2.0.0-1", "any");
    ComponentRecord component = new ComponentRecord(
        1L, 10L, RepositoryFormat.ARCH, null, "zeta", "1.0.0-1@x86_64",
        ArchAssetSupport.KIND, new byte[32], Map.of("archDesc", first.render()), Instant.EPOCH);
    ComponentRecord component2 = new ComponentRecord(
        2L, 10L, RepositoryFormat.ARCH, null, "alpha", "2.0.0-1@any",
        ArchAssetSupport.KIND, new byte[32], Map.of("archDesc", second.render()), Instant.EPOCH);

    ArchDatabase database = new ArchDatabase();
    byte[] bytes = database.build(List.of(component, component2), false);
    byte[] again = database.build(List.of(component2, component), false);
    assertTrue(java.util.Arrays.equals(bytes, again));

    List<ArchDesc> parsed = database.parse(bytes, "core.db");
    assertEquals(2, parsed.size());
    assertEquals("alpha", parsed.getFirst().name());
    assertEquals("zeta", parsed.getLast().name());
  }

  @Test
  void mergesMemberDescriptionsWithoutRewritingIdentity() {
    ArchDatabase database = new ArchDatabase();
    byte[] bytes = database.buildDescs(List.of(desc("demo", "1.0.0-1", "any")), false);
    List<ArchDesc> parsed = database.parse(bytes, "demo.db");
    assertEquals(1, parsed.size());
    assertEquals("demo", parsed.getFirst().name());
  }

  @Test
  void detectsGzipContentEvenWhenTheRequestedAliasIsZstd() {
    ArchDatabase database = new ArchDatabase();
    byte[] bytes = database.buildDescs(List.of(desc("demo", "2.0.0-1", "x86_64")), false);
    List<ArchDesc> parsed = database.parse(bytes, "demo.db.tar.zst");
    assertEquals(1, parsed.size());
    assertEquals("demo", parsed.getFirst().name());
  }

  private static ArchDesc desc(String name, String version, String arch) {
    return new ArchDesc(
        name + "-" + version + "-" + arch + ".pkg.tar.zst", name, name, version, "desc",
        1L, 2L, "md5", "sha256", null, null, List.of("MIT"), arch, 1L, "packager",
        List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
  }
}
