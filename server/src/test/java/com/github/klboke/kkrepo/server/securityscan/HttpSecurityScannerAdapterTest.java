package com.github.klboke.kkrepo.server.securityscan;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.github.klboke.kkrepo.security.scan.ScanEnums.SubjectKind;
import com.github.klboke.kkrepo.security.scan.ScanEnums.TargetClassification;
import com.github.klboke.kkrepo.security.scan.ScanSubject;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class HttpSecurityScannerAdapterTest {
  @ParameterizedTest
  @CsvSource({
      "org/acme/demo/1.0/demo-1.0.jar,.jar",
      "packages/demo-1.0.0.tar.gz,.tar.gz",
      "flat/demo/1.0.0/demo.1.0.0.NUPKG,.nupkg",
      "release/no-extension,''",
      "release/invalid.jar?download=true,''"
  })
  void derivesOnlyBoundedArtifactSuffixes(String path, String expected) {
    ScanSubject subject = new ScanSubject(
        SubjectKind.ASSET_BLOB,
        1L,
        2L,
        3L,
        "sha256:" + "a".repeat(64),
        "a".repeat(64),
        42L,
        "MAVEN2",
        "artifact",
        "application/octet-stream",
        TargetClassification.ARCHIVE,
        List.of(),
        Map.of("path", path));

    assertEquals(expected, HttpSecurityScannerAdapter.artifactSuffix(subject));
  }
}
