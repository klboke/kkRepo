package com.github.klboke.kkrepo.server.cleanup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.klboke.kkrepo.core.RepositoryFormat;
import org.junit.jupiter.api.Test;

class CleanupPolicyCapabilitiesTest {
  @Test
  void allRepositoryFormatsExposeTryRunLastDownloadAndExecution() {
    var capabilities = new CleanupPolicyCapabilities().all();

    assertEquals(RepositoryFormat.values().length, capabilities.size());
    assertTrue(capabilities.stream().allMatch(item -> item.tryRunSupported()
        && item.lastDownloadedSupported()
        && item.executeSupported()));
  }

  @Test
  void rRetainCountUsesNumericPackageVersionOrdering() {
    var comparator = new CleanupPolicyCapabilities()
        .versionComparator(RepositoryFormat.R).orElseThrow();

    assertTrue(comparator.compare("0.9", "0.75") < 0);
    assertTrue(comparator.compare("1.2-10", "1.2-9") > 0);
    assertEquals(0, comparator.compare("0.01.0", "0.1-0"));
  }
}
