package com.github.klboke.kkrepo.scanner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.github.klboke.kkrepo.security.scan.ScannerArtifactType;
import org.junit.jupiter.api.Test;

class ScannerEngineServiceTest {
  @Test
  void mapsArtifactPathsAndWireValuesToClosedTypes() {
    assertEquals(
        ScannerArtifactType.JAR,
        ScannerArtifactType.fromPath("org/acme/demo/1/demo-1.JAR"));
    assertEquals(
        ScannerArtifactType.TAR_GZ,
        ScannerArtifactType.fromPath("packages/demo-1.tar.gz"));
    assertEquals(
        ScannerArtifactType.NUPKG,
        ScannerArtifactType.fromWireValue("nupkg"));
    assertEquals(ScannerArtifactType.UNKNOWN, ScannerArtifactType.fromPath(null));
  }

  @Test
  void rejectsPathMaterialAndUnknownWireTypes() {
    assertThrows(
        IllegalArgumentException.class,
        () -> ScannerArtifactType.fromWireValue("../artifact.jar"));
    assertThrows(
        IllegalArgumentException.class,
        () -> ScannerArtifactType.fromWireValue("jar/other"));
  }
}
