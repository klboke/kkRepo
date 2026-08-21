package com.github.klboke.kkrepo.server.r;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.github.klboke.kkrepo.persistence.jdbc.api.RRegistryDao;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.mockito.Answers;

class RRegistryDaoPagingTest {

  @Test
  void defaultVisitorsAdvanceTheirKeysetCursorsAcrossFullPages() {
    RRegistryDao dao = mock(RRegistryDao.class, Answers.CALLS_REAL_METHODS);
    RRegistryDao.PackageRecord cursor = row();
    List<RRegistryDao.PackageRecord> fullPage =
        Collections.nCopies(RRegistryDao.PACKAGE_PAGE_SIZE, cursor);
    when(dao.listPackagePage(
        1L, "src/contrib", "source", "source", "", 0L,
        RRegistryDao.PACKAGE_PAGE_SIZE))
        .thenReturn(fullPage);
    when(dao.listPackagePage(
        1L, "src/contrib", "source", "source", "demo", 99L,
        RRegistryDao.PACKAGE_PAGE_SIZE))
        .thenReturn(List.of());
    when(dao.listPackagePage(
        1L, "src/contrib", "", 0L, RRegistryDao.PACKAGE_PAGE_SIZE))
        .thenReturn(fullPage);
    when(dao.listPackagePage(
        1L, "src/contrib", "demo", 99L, RRegistryDao.PACKAGE_PAGE_SIZE))
        .thenReturn(List.of());
    AtomicInteger typedVisits = new AtomicInteger();
    AtomicInteger allVisits = new AtomicInteger();

    dao.visitPackages(
        1L, "src/contrib", "source", "source", ignored -> typedVisits.incrementAndGet());
    dao.visitPackages(1L, "src/contrib", ignored -> allVisits.incrementAndGet());

    assertEquals(RRegistryDao.PACKAGE_PAGE_SIZE, typedVisits.get());
    assertEquals(RRegistryDao.PACKAGE_PAGE_SIZE, allVisits.get());
    verify(dao).listPackagePage(
        1L, "src/contrib", "source", "source", "demo", 99L,
        RRegistryDao.PACKAGE_PAGE_SIZE);
    verify(dao).listPackagePage(
        1L, "src/contrib", "demo", 99L, RRegistryDao.PACKAGE_PAGE_SIZE);
  }

  private static RRegistryDao.PackageRecord row() {
    return new RRegistryDao.PackageRecord(
        99L, 1L, "src/contrib", "source", "source", "demo", "1.0.0",
        "r1|1.0.0".getBytes(StandardCharsets.US_ASCII), "source",
        "demo_1.0.0.tar.gz", "src/contrib/demo_1.0.0.tar.gz",
        Map.of("Package", "demo", "Version", "1.0.0"),
        "a".repeat(32), "b".repeat(64), "b".repeat(64), 10L,
        2L, 3L, RRegistryDao.SOURCE_HOSTED, 1L,
        Instant.EPOCH, Instant.EPOCH, Instant.EPOCH);
  }
}
