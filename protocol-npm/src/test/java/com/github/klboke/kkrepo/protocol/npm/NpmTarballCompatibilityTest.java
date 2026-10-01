package com.github.klboke.kkrepo.protocol.npm;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class NpmTarballCompatibilityTest {
  @Test
  void legacyAliasesRequireAnUnambiguousBasenameOnlyRequest() {
    var identities = Arrays.asList(null, "signed/demo.tgz", "signed/demo.tgz");
    assertEquals("signed/demo.tgz", NpmTarballCompatibility.legacyBasenameAlias(identities, "demo.tgz"));
    assertNull(NpmTarballCompatibility.legacyBasenameAlias(identities, "invented/demo.tgz"));
    assertNull(NpmTarballCompatibility.legacyBasenameAlias(identities, null));
    assertNull(NpmTarballCompatibility.legacyBasenameAlias(identities, "missing.tgz"));
    assertNull(NpmTarballCompatibility.legacyBasenameAlias(
        List.of("old/demo.tgz", "young/demo.tgz"), "demo.tgz"));
    assertEquals("signed/%41.tgz", NpmTarballCompatibility.legacyBasenameAlias(
        List.of("signed/%41.tgz"), "%41.tgz"));
    assertNull(NpmTarballCompatibility.legacyBasenameAlias(List.of("signed/%41.tgz"), "A.tgz"));
  }

  @Test
  void requestPathFallbackCannotBypassKnownIdentityConflicts() {
    assertEquals(NpmTarballCompatibility.Fallback.REQUEST_PATH,
        NpmTarballCompatibility.fallback(List.of(), "@scope/demo.tgz"));
    assertEquals(NpmTarballCompatibility.Fallback.REQUEST_PATH,
        NpmTarballCompatibility.fallback(List.of("unrelated.tgz"), "demo.tgz"));
    assertEquals(NpmTarballCompatibility.Fallback.UNDECLARED_PATH,
        NpmTarballCompatibility.fallback(List.of("signed/demo.tgz"), "invented/demo.tgz"));
    assertEquals(NpmTarballCompatibility.Fallback.AMBIGUOUS_BASENAME,
        NpmTarballCompatibility.fallback(List.of("old/demo.tgz", "young/demo.tgz"), "demo.tgz"));
  }
}
