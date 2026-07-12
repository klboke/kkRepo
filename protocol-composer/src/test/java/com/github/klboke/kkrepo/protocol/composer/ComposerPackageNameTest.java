package com.github.klboke.kkrepo.protocol.composer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ComposerPackageNameTest {
  @Test
  void acceptsComposerPackageNameGrammar() {
    assertTrue(ComposerPackageName.isValid("psr/log"));
    assertTrue(ComposerPackageName.isValid("vendor-name/package_name"));
    assertTrue(ComposerPackageName.isValid("vendor.name/package--name"));
    assertTrue(ComposerPackageName.isValid("0/0"));
  }

  @Test
  void rejectsInvalidSeparatorsAndCharacters() {
    assertFalse(ComposerPackageName.isValid(null));
    assertFalse(ComposerPackageName.isValid("Vendor/package"));
    assertFalse(ComposerPackageName.isValid("vendor"));
    assertFalse(ComposerPackageName.isValid("vendor/package/extra"));
    assertFalse(ComposerPackageName.isValid("vendor-/package"));
    assertFalse(ComposerPackageName.isValid("vendor--name/package"));
    assertFalse(ComposerPackageName.isValid("vendor/package---name"));
    assertFalse(ComposerPackageName.isValid("vendor/package._name"));
    assertFalse(ComposerPackageName.isValid("vendor/päckage"));
  }

  @Test
  void enforcesLengthBeforeScanningAdversarialInput() {
    assertTrue(ComposerPackageName.isValid("v/" + "0".repeat(253)));
    assertFalse(ComposerPackageName.isValid("v/" + "0".repeat(254)));
    assertFalse(ComposerPackageName.isValid("0/" + "0".repeat(252) + "."));
    assertFalse(ComposerPackageName.isValid("0/" + "0-".repeat(10_000)));
  }

  @Test
  void normalizesBeforeRequiringAName() {
    assertEquals("vendor/package", ComposerPackageName.require(" Vendor/Package "));
    assertThrows(IllegalArgumentException.class, () -> ComposerPackageName.require("vendor/package---name"));
  }
}
