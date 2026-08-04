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
}
