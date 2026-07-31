package com.github.klboke.kkrepo.security.scan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import org.junit.jupiter.api.Test;

class ScanTaskPrioritiesTest {
  @Test
  void exposesEverySupportedPriorityInSchedulingOrder() {
    assertEquals(
        List.of(
            ScanTaskPriorities.MANUAL,
            ScanTaskPriorities.VULNERABILITY_DATABASE,
            ScanTaskPriorities.POLICY,
            ScanTaskPriorities.CONTENT),
        ScanTaskPriorities.descending());
    assertEquals(
        ScanTaskPriorities.MANUAL,
        ScanTaskPriorities.requireSupported(ScanTaskPriorities.MANUAL));
  }

  @Test
  void rejectsPrioritiesThatCannotUseABoundedClaimRange() {
    assertThrows(
        IllegalArgumentException.class,
        () -> ScanTaskPriorities.requireSupported(50));
  }
}
