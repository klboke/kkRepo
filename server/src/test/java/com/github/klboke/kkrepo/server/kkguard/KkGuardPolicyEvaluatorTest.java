package com.github.klboke.kkrepo.server.kkguard;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.klboke.kkrepo.persistence.jdbc.api.KkGuardDao.Finding;
import com.github.klboke.kkrepo.persistence.jdbc.api.KkGuardDao.Policy;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class KkGuardPolicyEvaluatorTest {
  private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

  @Test
  void severityConstraintsMatchAtOrAboveTheThreshold() {
    List<Policy> policies = List.of(policy(1, Map.of("severity", "HIGH")));

    var matches = KkGuardPolicyEvaluator.evaluate(policies, findings("HIGH", "9.8"));

    assertEquals(1, matches.size());
    assertEquals("high severity", matches.get(0).policyName());
    assertEquals("pkg:npm/lodash@4.17.20", matches.get(0).componentRef());
    assertEquals("HIGH", matches.get(0).constraint().get("severity"));
  }

  @Test
  void lowSeverityFindingsDoNotViolateHigherThresholds() {
    List<Policy> policies = List.of(policy(1, Map.of("severity", "CRITICAL")));

    assertTrue(KkGuardPolicyEvaluator.evaluate(policies, findings("MEDIUM", "5.0")).isEmpty());
  }

  @Test
  void multipleConstraintsAreCombinedWithAnd() {
    List<Policy> policies = List.of(policy(1, Map.of(
        "severity", "high", "cvssScore", 8.0, "componentRef", "pkg:npm/")));

    assertEquals(1, KkGuardPolicyEvaluator.evaluate(policies, findings("HIGH", "9.1")).size());
    assertTrue(KkGuardPolicyEvaluator.evaluate(policies, findings("HIGH", "7.1")).isEmpty());
    assertTrue(KkGuardPolicyEvaluator.evaluate(
        List.of(policy(1, Map.of("cvssScore", 9.0, "componentRef", "pkg:maven/"))),
        findings("HIGH", "9.5")).isEmpty());
  }

  @Test
  void vulnerabilityIdsMatchExactlyIgnoringCase() {
    List<Policy> policies = List.of(policy(1, Map.of("vulnerabilityId", "cve-2099-0002")));

    var matches = KkGuardPolicyEvaluator.evaluate(policies, findings("LOW", "1.0"));

    assertEquals(1, matches.size());
    assertEquals("CVE-2099-0002", matches.get(0).constraint().get("vulnerabilityId"));
  }

  @Test
  void malformedOrEmptyPoliciesNeverProduceViolations() {
    List<Policy> policies = List.of(
        policy(1, Map.of()),
        policy(2, Map.of("severity", "not-a-severity")),
        policy(3, Map.of("unknown", "value")));

    assertTrue(KkGuardPolicyEvaluator.evaluate(policies, findings("CRITICAL", "10.0")).isEmpty());
  }

  private static List<Finding> findings(String severity, String cvss) {
    return List.of(
        new Finding(7L, "pkg:npm/lodash@4.17.20", "CVE-2099-0002", severity,
            new BigDecimal(cvss), "NVD", "demo", null));
  }

  private static Policy policy(long id, Map<String, Object> constraints) {
    return new Policy(id, 3L, "ext-" + id, "public-" + id, "high severity", 8, constraints,
        NOW, NOW);
  }
}
