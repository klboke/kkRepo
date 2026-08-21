package com.github.klboke.kkrepo.persistence.jdbc.api;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class RRegistryDaoDefaultsTest {
  @Test
  void defaultVisitorAndProxyObservationDelegateToPrimaryContracts() {
    RRegistryDao.PackageRecord row = row();
    AtomicReference<Object[]> observed = new AtomicReference<>();
    RRegistryDao dao = (RRegistryDao) Proxy.newProxyInstance(
        RRegistryDao.class.getClassLoader(),
        new Class<?>[] {RRegistryDao.class},
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
    ArrayList<RRegistryDao.PackageRecord> visited = new ArrayList<>();

    dao.visitPackages(1L, "src/contrib", "source", "source", visited::add);
    dao.visitPackages(1L, "src/contrib", "source", "source", null);
    dao.visitPackages(1L, "src/contrib", visited::add);
    dao.visitPackages(1L, "src/contrib", null);
    dao.observeProxyDistribution(1L, "src/contrib", "release", Instant.EPOCH);

    assertEquals(List.of(row, row), visited);
    assertArrayEquals(
        new Object[] {1L, "src/contrib", "release", Map.of(), false, Instant.EPOCH},
        observed.get());
  }

  @Test
  void packageRevisionPreservesOriginalTimestampsAndVersionKey() {
    Instant now = Instant.parse("2026-08-21T00:00:00Z");
    RRegistryDao.PackageRecord original = row();

    RRegistryDao.PackageRecord advanced = original.withRevision(4L, now);

    assertEquals(4L, advanced.revision());
    assertArrayEquals(original.versionOrderKey(), advanced.versionOrderKey());
    assertEquals(Instant.EPOCH, advanced.indexedAt());
    assertEquals(Instant.EPOCH, advanced.createdAt());
    assertEquals(now, advanced.updatedAt());

    RRegistryDao.PackageRecord missingTimes = new RRegistryDao.PackageRecord(
        original.id(), original.repositoryId(), original.distribution(), original.component(),
        original.architecture(), original.packageName(), original.version(),
        original.versionOrderKey(), original.packageArchitecture(), original.filename(),
        original.path(), original.controlFields(), original.identity(), original.dataSha256(),
        original.sha256(), original.size(), original.assetId(), original.componentId(),
        original.sourceKind(), 0L, null, null, null);
    RRegistryDao.PackageRecord filled = missingTimes.withRevision(1L, now);
    assertEquals(now, filled.indexedAt());
    assertEquals(now, filled.createdAt());
    assertEquals(now, filled.updatedAt());
  }

  private static RRegistryDao.PackageRecord row() {
    return new RRegistryDao.PackageRecord(
        1L, 1L, "src/contrib", "source", "source", "demo", "1.0.0",
        new byte[] {1, 2, 3}, "source", "demo_1.0.0.tar.gz",
        "src/contrib/demo_1.0.0.tar.gz", Map.of("Package", "demo"),
        "a".repeat(32), "b".repeat(64), "b".repeat(64), 7L, 2L, 3L,
        RRegistryDao.SOURCE_HOSTED, 1L, Instant.EPOCH, Instant.EPOCH, Instant.EPOCH);
  }
}
