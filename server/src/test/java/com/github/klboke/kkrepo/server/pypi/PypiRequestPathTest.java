package com.github.klboke.kkrepo.server.pypi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class PypiRequestPathTest {
  @Test
  void decodesPercentEscapesOnceAndPreservesLiteralPlus() {
    assertEquals("packages/demo/0.0.0+build/demo-0.0.0+build.whl",
        PypiRequestPath.decode(
            "packages/demo/0.0.0%2Bbuild/demo-0.0.0+build.whl"));
    assertEquals("demo-0.0.0+build.whl",
        PypiPaths.fileName("packages/demo/0.0.0/demo-0.0.0+build.whl"));
    assertEquals("demo-0.0.0+build.whl",
        PypiPaths.fileName("packages/demo/0.0.0/demo-0.0.0%2Bbuild.whl"));
    assertEquals("demo-0.0.0%2Bbuild.whl",
        PypiRequestPath.decodeSegment("demo-0.0.0%252Bbuild.whl"));
  }

  @Test
  void rejectsMalformedEscapesSeparatorsAndTraversalSegments() {
    assertThrows(PypiExceptions.BadRequestException.class,
        () -> PypiRequestPath.decode("packages/demo%2Fevil.whl"));
    assertThrows(PypiExceptions.BadRequestException.class,
        () -> PypiRequestPath.decode("packages/%2E%2E/evil.whl"));
    assertThrows(PypiExceptions.BadRequestException.class,
        () -> PypiRequestPath.decode("packages/demo%ZZ.whl"));
  }
}
