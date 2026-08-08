package com.github.klboke.kkrepo.protocol.conda;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class CondaVersionsTest {
  @Test
  void followsCondaVersionOrderExamples() {
    List<String> ordered = List.of(
        "0.4",
        "0.4.1.rc",
        "0.4.1",
        "0.5a1",
        "0.5b3",
        "0.5C1",
        "0.5",
        "0.9.6",
        "0.960923",
        "1.0",
        "1.1dev1",
        "1.1_",
        "1.1a1",
        "1.1.0dev1",
        "1.1.a1",
        "1.1.0rc1",
        "1.1.0",
        "1.1.0post1",
        "1.1post1",
        "1996.07.12",
        "1!0.4.1",
        "2!0.4.1");
    for (int i = 1; i < ordered.size(); i++) {
      assertTrue(
          CondaVersions.compare(ordered.get(i - 1), ordered.get(i)) < 0,
          ordered.get(i - 1) + " should precede " + ordered.get(i));
    }
  }

  @Test
  void normalizesCaseZeroComponentsAndDashOnlyVersions() {
    assertEquals(0, CondaVersions.compare("1.1.0RC1", "1.1.0rc1"));
    assertEquals(0, CondaVersions.compare("1.1", "1.1.0"));
    assertEquals(0, CondaVersions.compare("1.1.0dev1", "1.1.dev1"));
    assertEquals(0, CondaVersions.compare("1.1.0post1", "1.1.post1"));
    assertEquals(0, CondaVersions.compare("1.0-rc1", "1.0_rc1"));
    assertTrue(CondaVersions.compare("1.0+2", "1.0+10") < 0);
    assertTrue(CondaVersions.compare("1.0.1_", "1.0.1a") < 0);
    assertTrue(CondaVersions.compare("1!0.4.1", "1!3.1.1.6") < 0);
  }

  @Test
  void rejectsInvalidVersions() {
    assertThrows(IllegalArgumentException.class, () -> CondaVersions.require(""));
    assertThrows(IllegalArgumentException.class, () -> CondaVersions.require("1..0"));
    assertThrows(IllegalArgumentException.class, () -> CondaVersions.require("1!2!3"));
    assertThrows(IllegalArgumentException.class, () -> CondaVersions.require("1.0-alpha_beta"));
    assertThrows(IllegalArgumentException.class, () -> CondaVersions.require("1".repeat(65)));
  }
}
