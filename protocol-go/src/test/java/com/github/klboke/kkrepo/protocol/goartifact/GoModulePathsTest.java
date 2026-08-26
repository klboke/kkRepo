package com.github.klboke.kkrepo.protocol.goartifact;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class GoModulePathsTest {
  @Test
  void escapesUppercaseAndRoundTripsCanonicalProxyPath() {
    assertEquals("example.com/!acme/!demo", GoModulePaths.escape("example.com/Acme/Demo"));
    assertEquals("example.com/Acme/Demo", GoModulePaths.unescape("example.com/!acme/!demo"));
  }

  @Test
  void validatesMajorVersionSuffix() {
    GoModulePaths.requireVersionSuffix("example.com/acme/demo/v2", "v2.1.0");
    GoModulePaths.requireVersionSuffix("example.com/acme/demo", "v2.1.0+incompatible");
    GoModulePaths.requireVersionSuffix("gopkg.in/yaml.v3", "v3.0.1");
    GoModulePaths.requireVersionSuffix(
        "gopkg.in/check.v1", "v0.0.0-20161208181325-20d25e280405");

    assertThrows(IllegalArgumentException.class,
        () -> GoModulePaths.requireVersionSuffix("example.com/acme/demo", "v2.1.0"));
    assertThrows(IllegalArgumentException.class,
        () -> GoModulePaths.requireVersionSuffix("example.com/acme/demo/v3", "v2.1.0"));
    assertThrows(IllegalArgumentException.class,
        () -> GoModulePaths.requireVersionSuffix("gopkg.in/yaml.v1", "v0.1.0"));
  }

  @Test
  void rejectsUnsafeAndNonCanonicalPaths() {
    assertThrows(IllegalArgumentException.class, () -> GoModulePaths.require("../demo"));
    assertThrows(IllegalArgumentException.class, () -> GoModulePaths.require("localhost/demo"));
    assertThrows(IllegalArgumentException.class, () -> GoModulePaths.require("Example.com/demo"));
    assertThrows(IllegalArgumentException.class, () -> GoModulePaths.require("example.com//demo"));
    assertThrows(IllegalArgumentException.class, () -> GoModulePaths.require("example.com/.demo"));
    assertThrows(IllegalArgumentException.class, () -> GoModulePaths.require("example.com/demo~1"));
    assertThrows(IllegalArgumentException.class, () -> GoModulePaths.require("example.com/demo/v1"));
    assertThrows(IllegalArgumentException.class, () -> GoModulePaths.require("example.com/demo/v02"));
    assertThrows(IllegalArgumentException.class, () -> GoModulePaths.require("gopkg.in/yaml"));
    assertThrows(IllegalArgumentException.class, () -> GoModulePaths.unescape("example.com/Acme"));
    assertThrows(IllegalArgumentException.class, () -> GoModulePaths.unescape("example.com/!A"));
  }

  @Test
  void rejectsAdditionalEscapingAndPathMajorEdgeCases() {
    assertThrows(IllegalArgumentException.class, () -> GoModulePaths.require("example.com/dem%c"));
    assertThrows(IllegalArgumentException.class, () -> GoModulePaths.require("example.com/con"));
    assertThrows(IllegalArgumentException.class, () -> GoModulePaths.require("gopkg.in/yaml.v01"));
    assertThrows(IllegalArgumentException.class, () -> GoModulePaths.unescape(null));
    assertThrows(IllegalArgumentException.class, () -> GoModulePaths.unescape("example.com/demo!"));
    assertThrows(IllegalArgumentException.class, () -> GoModulePaths.unescape("example.com/!1demo"));

    assertThrows(IllegalArgumentException.class,
        () -> GoModulePaths.requireVersionSuffix("example.com/acme/demo/v2", "v1.0.0"));
    assertThrows(IllegalArgumentException.class,
        () -> GoModulePaths.requireVersionSuffix(
            "example.com/acme/demo/v999999999999999999999999999999", "v2.0.0"));
    GoModulePaths.requireVersionSuffix("gopkg.in/yaml.v2-unstable", "v2.0.0");
  }
}
