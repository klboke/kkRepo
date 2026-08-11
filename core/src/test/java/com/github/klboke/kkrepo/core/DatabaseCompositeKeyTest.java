package com.github.klboke.kkrepo.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class DatabaseCompositeKeyTest {
  @Test
  void preservesPartBoundariesAndNullsWithoutDatabaseControlCharacters() {
    assertNotEquals(
        DatabaseCompositeKey.of("ab", "c"),
        DatabaseCompositeKey.of("a", "bc"));
    assertNotEquals(DatabaseCompositeKey.of((String) null), DatabaseCompositeKey.of(""));
    assertEquals("v1|2:ab|1:c", DatabaseCompositeKey.of("ab", "c"));
    assertThrows(
        IllegalArgumentException.class,
        () -> DatabaseCompositeKey.of("unsafe\0value"));
  }
}
