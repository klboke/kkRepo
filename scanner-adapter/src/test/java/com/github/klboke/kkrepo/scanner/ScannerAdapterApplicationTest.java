package com.github.klboke.kkrepo.scanner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.health.actuate.endpoint.HttpCodeStatusMapper;
import org.springframework.boot.health.actuate.endpoint.StatusAggregator;
import org.springframework.boot.health.contributor.Status;
import org.springframework.boot.health.registry.HealthContributorRegistry;

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = "kkrepo.scanner.service-credential=test-secret")
class ScannerAdapterApplicationTest {
  @Autowired
  private ScannerEngineService engine;

  @Autowired
  private ObjectMapper scannerDocumentObjectMapper;

  @Autowired
  private HealthContributorRegistry healthContributors;

  @Autowired
  private StatusAggregator healthStatusAggregator;

  @Autowired
  private HttpCodeStatusMapper healthHttpCodeStatusMapper;

  @Test
  void startsWithScannerDocumentMapper() {
    assertNotNull(engine);
    assertNotNull(scannerDocumentObjectMapper);
    assertNotNull(healthContributors.getContributor("scanner"));
    Status degraded = new Status("DEGRADED");
    assertEquals(
        degraded,
        healthStatusAggregator.getAggregateStatus(Set.of(Status.UP, degraded)));
    assertEquals(503, healthHttpCodeStatusMapper.getStatusCode(degraded));
  }
}
