package com.github.klboke.kkrepo.persistence.jdbc.api;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class AlpineRegistryDaoDefaultsTest {
  @Test
  void namespaceRejectsUnsafeSegmentsAndJoinsValidatedValues() {
    assertEquals("v3.20/main/x86_64", AlpineRegistryDao.namespace("v3.20", "main", "x86_64"));
    assertThrows(IllegalArgumentException.class,
        () -> AlpineRegistryDao.namespace(null, "main", "x86_64"));
    assertThrows(IllegalArgumentException.class,
        () -> AlpineRegistryDao.namespace("", "main", "x86_64"));
    assertThrows(IllegalArgumentException.class,
        () -> AlpineRegistryDao.namespace("v3.20/bad", "main", "x86_64"));
    assertThrows(IllegalArgumentException.class,
        () -> AlpineRegistryDao.namespace("v3.20", "ma\0in", "x86_64"));
  }

  @Test
  void defaultVisitorAndProxyObservationDelegateToPrimaryContracts() {
    AlpineRegistryDao.PackageRecord row = row();
    AtomicReference<Object[]> observed = new AtomicReference<>();
    AlpineRegistryDao dao = (AlpineRegistryDao) Proxy.newProxyInstance(
        AlpineRegistryDao.class.getClassLoader(),
        new Class<?>[] {AlpineRegistryDao.class},
        (proxy, method, arguments) -> {
          if (method.isDefault()) {
            return InvocationHandler.invokeDefault(proxy, method, arguments);
          }
          if (method.getName().equals("listPackagePage") && method.getParameterCount() == 7) {
            return ((Long) arguments[5]) == 0 ? List.of(row) : List.of();
          }
          if (method.getName().equals("listPackagePage") && method.getParameterCount() == 5) {
            return ((Long) arguments[3]) == 0 ? List.of(row) : List.of();
          }
          if (method.getName().equals("observeProxyDistribution")
              && method.getParameterCount() == 6) {
            observed.set(arguments);
            return null;
          }
          throw new UnsupportedOperationException(method.toString());
        });
    ArrayList<AlpineRegistryDao.PackageRecord> visited = new ArrayList<>();

    dao.visitPackages(1L, "v3.20/main/x86_64", "main", "x86_64", visited::add);
    dao.visitPackages(1L, "v3.20/main/x86_64", "main", "x86_64", null);
    dao.visitPackages(1L, "v3.20/main/x86_64", visited::add);
    dao.visitPackages(1L, "v3.20/main/x86_64", null);
    dao.observeProxyDistribution(1L, "v3.20/main/x86_64", "release", Instant.EPOCH);

    assertEquals(List.of(row, row), visited);
    assertArrayEquals(
        new Object[] {1L, "v3.20/main/x86_64", "release", Map.of(), false, Instant.EPOCH},
        observed.get());
  }

  @Test
  void packageRevisionPreservesOriginalTimestampsAndFillsMissingOnes() {
    Instant now = Instant.parse("2026-08-15T00:00:00Z");
    AlpineRegistryDao.PackageRecord original = row();
    AlpineRegistryDao.PackageRecord advanced = original.withRevision(4L, now);
    assertEquals(4L, advanced.revision());
    assertEquals(Instant.EPOCH, advanced.indexedAt());
    assertEquals(Instant.EPOCH, advanced.createdAt());
    assertEquals(now, advanced.updatedAt());

    AlpineRegistryDao.PackageRecord missingTimes = new AlpineRegistryDao.PackageRecord(
        original.id(), original.repositoryId(), original.distribution(), original.component(),
        original.architecture(), original.packageName(), original.version(),
        original.packageArchitecture(), original.filename(), original.path(),
        original.controlFields(), original.identity(), original.dataSha256(), original.sha256(),
        original.size(), original.assetId(), original.componentId(), original.sourceKind(), 0L,
        null, null, null);
    AlpineRegistryDao.PackageRecord filled = missingTimes.withRevision(1L, now);
    assertEquals(now, filled.indexedAt());
    assertEquals(now, filled.createdAt());
    assertEquals(now, filled.updatedAt());
  }

  private static AlpineRegistryDao.PackageRecord row() {
    return new AlpineRegistryDao.PackageRecord(
        1L, 1L, "v3.20/main/x86_64", "main", "x86_64", "demo", "1-r0", "x86_64",
        "demo-1-r0.apk", "v3.20/main/x86_64/demo-1-r0.apk", Map.of("I", "7"),
        "Q1AAAAAAAAAAAAAAAAAAAAAAAAAAA=", "a".repeat(64), "b".repeat(64), 7L,
        2L, 3L, AlpineRegistryDao.SOURCE_HOSTED, 1L, Instant.EPOCH, Instant.EPOCH,
        Instant.EPOCH);
  }
}
