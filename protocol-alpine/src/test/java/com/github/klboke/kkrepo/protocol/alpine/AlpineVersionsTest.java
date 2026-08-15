package com.github.klboke.kkrepo.protocol.alpine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class AlpineVersionsTest {
  @Test
  void matchesApkToolsTokenOrderingCorpus() {
    List<Comparison> corpus = List.of(
        new Comparison("1.0", "1.0_alpha", 1),
        new Comparison("1.0_alpha2", "1.0_alpha", 1),
        new Comparison("1.0_alpha", "1.0_beta", -1),
        new Comparison("1.0_beta", "1.0_pre", -1),
        new Comparison("1.0_pre", "1.0_rc", -1),
        new Comparison("1.0_rc1", "1.0", -1),
        new Comparison("1.0", "1.0_cvs", -1),
        new Comparison("1.0_git", "1.0_p", -1),
        new Comparison("1.0_p1", "1.0_p2", -1),
        new Comparison("1.0-r9", "1.0-r10", -1),
        new Comparison("1.06-r6", "006", -1),
        new Comparison("1.2.10", "1.2.2", 1),
        new Comparison("2.5.1-r8", "2.5.1a-r1", -1),
        new Comparison("1.0.01", "1.0.1", -1),
        new Comparison("1.0~dead", "1.0~beef", 1));
    corpus.forEach(comparison -> {
      int actual = Integer.signum(AlpineVersions.compare(comparison.left(), comparison.right()));
      assertEquals(comparison.expected(), actual, comparison.toString());
      assertEquals(-comparison.expected(), Integer.signum(
          AlpineVersions.compare(comparison.right(), comparison.left())), comparison.toString());
    });
  }

  @Test
  void validatesOfficialVersionGrammar() {
    assertTrue(AlpineVersions.isValid("3.0.0_rc9-r2"));
    assertTrue(AlpineVersions.isValid("1.2.3~deadBEEF-r0"));
    assertTrue(AlpineVersions.isValid("006"));
    assertFalse(AlpineVersions.isValid("v1.0"));
    assertFalse(AlpineVersions.isValid("1.0_foo"));
    assertFalse(AlpineVersions.isValid("1.0-r"));
    assertFalse(AlpineVersions.isValid("1.0+meta"));
  }

  private record Comparison(String left, String right, int expected) {
  }
}
