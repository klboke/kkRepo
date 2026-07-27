package com.github.klboke.kkrepo.scanner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class ScannerEngineServiceTest {
  @Test
  void normalizesSafeArtifactSuffix() {
    assertEquals(".jar", ScannerEngineService.normalizeArtifactSuffix(".JAR"));
    assertEquals(".tar.gz", ScannerEngineService.normalizeArtifactSuffix(".tar.gz"));
    assertEquals("", ScannerEngineService.normalizeArtifactSuffix(null));
  }

  @Test
  void rejectsPathMaterialInArtifactSuffix() {
    assertThrows(
        ScannerRequestException.class,
        () -> ScannerEngineService.normalizeArtifactSuffix("../artifact.jar"));
    assertThrows(
        ScannerRequestException.class,
        () -> ScannerEngineService.normalizeArtifactSuffix(".jar/other"));
  }
}
