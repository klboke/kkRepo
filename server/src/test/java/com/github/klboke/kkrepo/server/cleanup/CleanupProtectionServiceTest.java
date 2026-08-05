package com.github.klboke.kkrepo.server.cleanup;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
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
import java.util.List;
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

  @Test
  void listsWithBoundsAndSuccessfullyUpdatesUsingAMonotonicTimestamp() {
    when(cleanupDao.listProtections(0, 200, now)).thenReturn(List.of(protection(9, now)));
    when(cleanupDao.listProtections(5, 1, null)).thenReturn(List.of());

    assertEquals(1, service.list(-1, 500, true).size());
    assertEquals(0, service.list(5, 0, false).size());

    Instant monotonic = now.plusMillis(1);
    when(cleanupDao.findProtection(9)).thenReturn(
        Optional.of(protection(9, now)), Optional.of(protection(9, monotonic)));
    when(cleanupDao.updateProtection(any(), eq(now))).thenReturn(true);
    var updated = service.update(9, new ProtectionCommand(
        "subject", 1L, "ASSET", "asset:5", "manual", null, "updated",
        null, now.plusSeconds(7_200), null, now), "ignored actor");

    assertEquals(monotonic, updated.updatedAt());
    ArgumentCaptor<CleanupProtection> value = ArgumentCaptor.forClass(CleanupProtection.class);
    verify(cleanupDao).updateProtection(value.capture(), eq(now));
    assertEquals(monotonic, value.getValue().updatedAt());
    assertEquals("admin", value.getValue().createdBy());
  }

  @Test
  void deleteRequiresVersionAndReportsConflictsBeforeSucceeding() {
    Instant current = now.plusSeconds(2);
    when(cleanupDao.findProtection(9)).thenReturn(
        Optional.of(protection(9, now)),
        Optional.of(protection(9, now)),
        Optional.of(protection(9, current)),
        Optional.of(protection(9, current)));
    assertThrows(CleanupValidationException.class, () -> service.delete(9, null));

    when(cleanupDao.deleteProtection(9, now)).thenReturn(false);
    CleanupProtectionConflictException conflict = assertThrows(
        CleanupProtectionConflictException.class, () -> service.delete(9, now));
    assertEquals(current, conflict.currentUpdatedAt());

    when(cleanupDao.deleteProtection(9, current)).thenReturn(true);
    service.delete(9, current);
    verify(cleanupDao, times(2)).deleteProtection(eq(9L), any());
  }

  @Test
  void validatesScopeSourceRepositoryAndLengthConstraints() {
    assertInvalid(new ProtectionCommand(
        "GLOBAL", 1L, null, null, "MANUAL", null, "hold",
        true, null, null, null));
    assertInvalid(new ProtectionCommand(
        "REPOSITORY", 1L, "ASSET", "asset:5", "MANUAL", null, "hold",
        true, null, null, null));
    assertInvalid(new ProtectionCommand(
        "SUBJECT", 1L, null, null, "MANUAL", null, "hold",
        true, null, null, null));
    assertInvalid(new ProtectionCommand(
        "REPOSITORY", null, null, null, "MANUAL", null, "hold",
        true, null, null, null));
    assertInvalid(new ProtectionCommand(
        "REPOSITORY", 2L, null, null, "MANUAL", null, "hold",
        true, null, null, null));
    assertInvalid(new ProtectionCommand(
        "GLOBAL", null, null, null, "MANUAL", "external", "hold",
        true, null, null, null));
    assertInvalid(new ProtectionCommand(
        "GLOBAL", null, null, null, "MANUAL", null, "hold",
        true, now, null, null));
    assertInvalid(new ProtectionCommand(
        "INVALID", null, null, null, "MANUAL", null, "hold",
        true, null, null, null));
    assertInvalid(new ProtectionCommand(
        "GLOBAL", null, null, null, "INVALID", null, "hold",
        true, null, null, null));
    assertInvalid(new ProtectionCommand(
        "GLOBAL", null, null, null, "MANUAL", null, "x".repeat(1_025),
        true, null, null, null));
  }

  private void assertInvalid(ProtectionCommand command) {
    assertThrows(
        CleanupValidationException.class,
        () -> service.create(command, "admin"));
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
