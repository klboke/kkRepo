package com.github.klboke.kkrepo.persistence.jdbc.api;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PersistenceApiContractsTest {
  @Test
  void sanitizesNullAuditQueryToTheDefaultPage() {
    SecurityAuditDao.AuditLogQuery sanitized = SecurityAuditDao.AuditLogQuery.sanitize(null);

    assertAll(
        () -> assertEquals(0, sanitized.page()),
        () -> assertEquals(50, sanitized.size()),
        () -> assertNull(sanitized.query()),
        () -> assertNull(sanitized.method()),
        () -> assertNull(sanitized.outcome()));
  }

  @Test
  void sanitizesAuditFiltersAndBoundsPagination() {
    LocalDateTime from = LocalDateTime.of(2026, 7, 1, 8, 30);
    LocalDateTime to = from.plusHours(2);
    SecurityAuditDao.AuditLogQuery sanitized = SecurityAuditDao.AuditLogQuery.sanitize(
        new SecurityAuditDao.AuditLogQuery(
            " package ", "  ", null, " 127.0.0.1 ", " get ", " /repository/releases ",
            " repository:read ", 200, " success ", from, to, -3, 500));

    assertAll(
        () -> assertEquals("package", sanitized.query()),
        () -> assertNull(sanitized.actorSource()),
        () -> assertNull(sanitized.actorUserId()),
        () -> assertEquals("127.0.0.1", sanitized.remoteAddr()),
        () -> assertEquals("GET", sanitized.method()),
        () -> assertEquals("/repository/releases", sanitized.path()),
        () -> assertEquals("repository:read", sanitized.permission()),
        () -> assertEquals(200, sanitized.status()),
        () -> assertEquals("SUCCESS", sanitized.outcome()),
        () -> assertSame(from, sanitized.from()),
        () -> assertSame(to, sanitized.to()),
        () -> assertEquals(0, sanitized.page()),
        () -> assertEquals(200, sanitized.size()));
  }

  @Test
  void appliesTheDefaultAuditPageSizeWhenTheRequestedSizeIsNotPositive() {
    SecurityAuditDao.AuditLogQuery sanitized = SecurityAuditDao.AuditLogQuery.sanitize(
        new SecurityAuditDao.AuditLogQuery(
            null, null, null, null, null, null, null, null, null, null, null, 2, 0));

    assertEquals(2, sanitized.page());
    assertEquals(50, sanitized.size());
  }

  @Test
  void normalizesSupportedUiLanguagesAtThePublicBoundary() {
    assertAll(
        () -> assertEquals("en", UiSettingsDao.normalizeDefaultLanguage(null)),
        () -> assertEquals("en", UiSettingsDao.normalizeDefaultLanguage("  ")),
        () -> assertEquals("browser", UiSettingsDao.normalizeDefaultLanguage(" BROWSER ")),
        () -> assertEquals("zh-CN", UiSettingsDao.normalizeDefaultLanguage("zh")),
        () -> assertEquals("zh-CN", UiSettingsDao.normalizeDefaultLanguage("ZH-CN")),
        () -> assertEquals("zh-CN", UiSettingsDao.normalizeDefaultLanguage("zh_CN")),
        () -> assertEquals("en", UiSettingsDao.normalizeDefaultLanguage("en-US")),
        () -> assertEquals("en", UiSettingsDao.normalizeDefaultLanguage("EN")));
  }

  @Test
  void rejectsUnsupportedUiLanguagesAtThePublicBoundary() {
    IllegalArgumentException thrown = assertThrows(
        IllegalArgumentException.class,
        () -> UiSettingsDao.normalizeDefaultLanguage("fr"));

    assertEquals("Unsupported UI default language: fr", thrown.getMessage());
  }

  @Test
  void browseChildIsALeafOnlyWhenItOwnsAnAssetAndHasNoChildren() {
    BrowseNodeDao.BrowseChild leaf = browseChild(10L, false);
    BrowseNodeDao.BrowseChild directoryWithAsset = browseChild(10L, true);
    BrowseNodeDao.BrowseChild emptyDirectory = browseChild(null, false);

    assertTrue(leaf.leaf());
    assertFalse(directoryWithAsset.leaf());
    assertFalse(emptyDirectory.leaf());
  }

  @Test
  void missingDockerManifestCarriesNoPersistentIdentifiers() {
    DockerRegistryDao.DeletedManifest missing = DockerRegistryDao.DeletedManifest.notFound();

    assertEquals(0, missing.deleted());
    assertNull(missing.assetId());
    assertNull(missing.assetBlobId());
  }

  @Test
  void conanRepositoryRevisionBatchIsDistinctNullSafeAndUsesPointLookups() {
    ArrayList<Long> lookups = new ArrayList<>();
    ConanRegistryDao conan = (ConanRegistryDao) Proxy.newProxyInstance(
        ConanRegistryDao.class.getClassLoader(),
        new Class<?>[] {ConanRegistryDao.class},
        (proxy, method, arguments) -> {
          if (method.isDefault()) {
            return InvocationHandler.invokeDefault(proxy, method, arguments);
          }
          if (method.getName().equals("currentRepositoryRevision")) {
            long repositoryId = (long) arguments[0];
            lookups.add(repositoryId);
            return repositoryId * 11;
          }
          throw new UnsupportedOperationException(method.getName());
        });

    assertEquals(Map.of(), conan.currentRepositoryRevisions(null));
    assertEquals(
        Map.of(1L, 11L, 2L, 22L),
        conan.currentRepositoryRevisions(List.of(1L, 2L, 1L)));
    assertEquals(List.of(1L, 2L), lookups);
  }

  private static BrowseNodeDao.BrowseChild browseChild(Long assetId, boolean hasChildren) {
    return new BrowseNodeDao.BrowseChild(
        1L,
        "org/example",
        "example",
        2,
        assetId,
        null,
        assetId == null ? null : 42L,
        assetId == null ? null : "application/octet-stream",
        null,
        Instant.parse("2026-07-01T00:00:00Z"),
        hasChildren,
        assetId != null);
  }
}
