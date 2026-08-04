package com.github.klboke.kkrepo.server.cleanup;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.core.RepositoryType;
import com.github.klboke.kkrepo.persistence.jdbc.api.CleanupPolicyDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.CleanupPolicyDao.CleanupProtection;
import com.github.klboke.kkrepo.persistence.jdbc.api.PersistenceHashes;
import com.github.klboke.kkrepo.persistence.jdbc.api.RepositoryDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.RepositoryRecord;
import com.github.klboke.kkrepo.server.cleanup.CleanupProtectionService.ProtectionCommand;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class CleanupProtectionServiceTest {
  private final Instant now = Instant.parse("2026-08-01T00:00:00Z");
  private CleanupPolicyDao cleanupDao;
  private RepositoryDao repositoryDao;
  private CleanupProtectionService service;

  @BeforeEach
  void setUp() {
    cleanupDao = mock(CleanupPolicyDao.class);
    repositoryDao = mock(RepositoryDao.class);
    service = new CleanupProtectionService(
        cleanupDao, repositoryDao, Clock.fixed(now, ZoneOffset.UTC));
    when(repositoryDao.findById(1)).thenReturn(Optional.of(repository()));
  }

  @Test
  void subjectProtectionPersistsTheCanonicalHashAndActor() {
    when(cleanupDao.createProtection(any())).thenReturn(9L);
    when(cleanupDao.findProtection(9)).thenReturn(Optional.of(protection(9, now)));

    service.create(new ProtectionCommand(
        "SUBJECT", 1L, "ASSET", "asset:5", "MANUAL", null, "release hold",
        true, now.plusSeconds(3_600), null, null), "admin");

    ArgumentCaptor<CleanupProtection> value = ArgumentCaptor.forClass(CleanupProtection.class);
    verify(cleanupDao).createProtection(value.capture());
    assertArrayEquals(PersistenceHashes.sha256("asset:5"), value.getValue().subjectKeyHash());
    assertEquals("admin", value.getValue().createdBy());
  }

  @Test
  void externalProtectionRequiresAFutureFreshnessDeadline() {
    assertThrows(CleanupValidationException.class, () -> service.create(new ProtectionCommand(
        "GLOBAL", null, null, null, "EXTERNAL", "ticket-1", "hold",
        true, null, null, null), "provider"));
    assertThrows(CleanupValidationException.class, () -> service.create(new ProtectionCommand(
        "GLOBAL", null, null, null, "EXTERNAL", "ticket-1", "hold",
        true, null, now, null), "provider"));
  }

  @Test
  void optimisticUpdateReportsTheCurrentVersion() {
    Instant current = now.plusSeconds(2);
    when(cleanupDao.findProtection(9))
        .thenReturn(Optional.of(protection(9, now)), Optional.of(protection(9, current)));
    when(cleanupDao.updateProtection(any(), any())).thenReturn(false);

    CleanupProtectionConflictException conflict = assertThrows(
        CleanupProtectionConflictException.class,
        () -> service.update(9, new ProtectionCommand(
            "SUBJECT", 1L, "ASSET", "asset:5", "MANUAL", null, "new reason",
            true, now.plusSeconds(3_600), null, now), "admin"));

    assertEquals(current, conflict.currentUpdatedAt());
  }

  private CleanupProtection protection(long id, Instant updatedAt) {
    return new CleanupProtection(
        id,
        "SUBJECT",
        1L,
        "ASSET",
        "asset:5",
        PersistenceHashes.sha256("asset:5"),
        "MANUAL",
        null,
        "release hold",
        true,
        now.plusSeconds(3_600),
        null,
        "admin",
        now,
        updatedAt);
  }

  private RepositoryRecord repository() {
    return new RepositoryRecord(
        1L,
        "raw-releases",
        RepositoryFormat.RAW,
        RepositoryType.HOSTED,
        "raw-hosted",
        true,
        1L,
        null,
        null,
        null,
        null,
        null,
        true,
        Map.of());
  }
}
