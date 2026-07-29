package com.github.klboke.kkrepo.server.securityscan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.github.klboke.kkrepo.persistence.jdbc.api.SecurityScanDao.ScanTask;
import org.junit.jupiter.api.Test;

class SecurityScanExecutionIdTest {
  @Test
  void isolatesClaimsEvenWhenAdministrativeRetryResetsTheAttemptCounter() {
    ScanTask oldClaim = claimedTask("old-lease");
    ScanTask newClaim = claimedTask("new-lease");

    assertEquals("5:old-lease", SecurityScanExecutionId.from(oldClaim));
    assertEquals("5:new-lease", SecurityScanExecutionId.from(newClaim));
    assertNotEquals(
        SecurityScanExecutionId.from(oldClaim),
        SecurityScanExecutionId.from(newClaim));
  }

  @Test
  void rejectsAnUnclaimedTask() {
    ScanTask task = mock(ScanTask.class);
    when(task.id()).thenReturn(5L);

    assertThrows(IllegalArgumentException.class, () -> SecurityScanExecutionId.from(task));
  }

  private static ScanTask claimedTask(String leaseToken) {
    ScanTask task = mock(ScanTask.class);
    when(task.id()).thenReturn(5L);
    when(task.attempts()).thenReturn(1);
    when(task.leaseToken()).thenReturn(leaseToken);
    return task;
  }
}
