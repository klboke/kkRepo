package com.github.klboke.kkrepo.protocol.nuget;

import static org.junit.jupiter.api.Assertions.assertEquals;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class NugetVersionsTest {
  @ParameterizedTest
  @CsvSource({"1.9.0,1.10.0,-1", "2.0,10.0,-1", "1.0,1.0.0,0", "1.0.0,1.0.0.0,0",
      "1.0.0.1,1.0.0,1", "01.0.0,1.0.0,0", "1.0.0+one,1.0.0+two,0", "1.0.0-alpha,1.0.0,-1",
      "1.0.0,1.0.0-alpha,1", "1.0.0-alpha.9,1.0.0-alpha.10,-1", "1.0.0-1,1.0.0-beta,-1",
      "1.0.0-beta,1.0.0-1,1", "1.0.0-alpha,1.0.0-alpha.1,-1", "1.0.0-BETA,1.0.0-beta,0",
      "1.0.0-rc,1.0.0-beta,1", "1.0.0-99999999999999999999,1.0.0-100000000000000000000,-1",
      "11a,2,1", "11a,10,1", "11a,20a,-1", "1..0,1.0.0,1"})
  void followsNugetVersionPrecedence(String left, String right, int expected) {
    assertEquals(expected, Integer.signum(NugetVersions.compare(left, right)));
    assertEquals(-expected, Integer.signum(NugetVersions.compare(right, left)));
  }
}
