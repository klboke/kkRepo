package com.github.klboke.kkrepo.protocol.conda;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class CondaPackageIdentifiersTest {
  @Test
  void acceptsCep26PackageNamesAndBuildStrings() {
    assertTrue(CondaPackageIdentifiers.isName("demo-package_py3"));
    assertTrue(CondaPackageIdentifiers.isName("_compiler_stub"));
    assertTrue(CondaPackageIdentifiers.isBuild("py312h4ab18f5_1+cuda.12"));
  }

  @Test
  void rejectsVirtualOrAmbiguousNamesAndNonCanonicalBuilds() {
    assertFalse(CondaPackageIdentifiers.isName("__linux"));
    assertFalse(CondaPackageIdentifiers.isName("demo--gpu"));
    assertFalse(CondaPackageIdentifiers.isName("Demo"));
    assertFalse(CondaPackageIdentifiers.isName("a".repeat(65)));
    assertFalse(CondaPackageIdentifiers.isBuild("py312-0"));
    assertFalse(CondaPackageIdentifiers.isBuild("py312 0"));
    assertFalse(CondaPackageIdentifiers.isBuild("a".repeat(65)));
  }

  @Test
  void acceptsOnlyTheDocumentedLegacyDefaultsNameForUpstreamRepodata() {
    assertFalse(CondaPackageIdentifiers.isName("__anaconda_core_depends"));
    assertTrue(CondaPackageIdentifiers.isUpstreamName("__anaconda_core_depends"));
    assertFalse(CondaPackageIdentifiers.isUpstreamName("__linux"));
    assertFalse(CondaPackageIdentifiers.isUpstreamName("__another_legacy_name"));
  }
}
