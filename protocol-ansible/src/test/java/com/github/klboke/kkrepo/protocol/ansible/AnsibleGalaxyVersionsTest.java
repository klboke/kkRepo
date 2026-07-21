package com.github.klboke.kkrepo.protocol.ansible;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class AnsibleGalaxyVersionsTest {
  @Test
  void validatesNamesAndSemver() {
    assertTrue(AnsibleGalaxyNames.isValidNamespace("community"));
    assertTrue(AnsibleGalaxyNames.isValidCollection("cloud_tools"));
    assertFalse(AnsibleGalaxyNames.isValidCollection("Cloud-Tools"));
    assertFalse(AnsibleGalaxyNames.isValidCollection("cloud__tools"));
    assertTrue(AnsibleGalaxyVersions.isValid("1.2.3-rc.1+build.7"));
    assertFalse(AnsibleGalaxyVersions.isValid("01.2.3"));
  }

  @Test
  void sortsBySemverPrecedence() {
    assertEquals(List.of("2.0.0", "1.0.0", "1.0.0-rc.1", "1.0.0-beta.2"),
        AnsibleGalaxyVersions.sortDescending(
            List.of("1.0.0-beta.2", "2.0.0", "1.0.0-rc.1", "1.0.0")));
  }
}
