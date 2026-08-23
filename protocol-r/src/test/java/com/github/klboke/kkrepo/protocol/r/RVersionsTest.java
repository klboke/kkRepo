package com.github.klboke.kkrepo.protocol.r;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class RVersionsTest {
  @Test
  void followsRNumericVersionOrdering() {
    assertTrue(RVersions.compare("0.9", "0.75") < 0);
    assertTrue(RVersions.compare("1.2-9", "1.2-10") < 0);
    assertEquals(0, RVersions.compare("0.01.0", "0.1-0"));
    assertTrue(RVersions.compare("1.0", "1.0.0") < 0);
  }

  @Test
  void validatesPackageVersions() {
    assertTrue(RVersions.isValid("4.6.1"));
    assertTrue(RVersions.isValid("1-2"));
    assertFalse(RVersions.isValid("1"));
    assertFalse(RVersions.isValid("1.2rc1"));
    assertFalse(RVersions.isValid("1..2"));
  }

  @Test
  void emitsStableOrderKeys() {
    String small = new String(RVersions.orderKey("1.2"), StandardCharsets.US_ASCII);
    String large = new String(RVersions.orderKey("1.10"), StandardCharsets.US_ASCII);
    assertTrue(small.compareTo(large) < 0);
    assertTrue(
        new String(RVersions.orderKey("1.0"), StandardCharsets.US_ASCII).compareTo(
            new String(RVersions.orderKey("1.0.0"), StandardCharsets.US_ASCII)) < 0);
  }
}
