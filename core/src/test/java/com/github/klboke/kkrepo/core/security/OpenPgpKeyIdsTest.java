package com.github.klboke.kkrepo.core.security;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class OpenPgpKeyIdsTest {
  @Test
  void formatsUnsignedUppercaseHexWithoutPadding() {
    assertEquals("2CDAD660D39D164", OpenPgpKeyIds.format(0x02CDAD660D39D164L));
    assertEquals("FEDCBA9876543210", OpenPgpKeyIds.format(0xFEDCBA9876543210L));
  }
}
