package com.github.klboke.kkrepo.security.scan;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.github.klboke.kkrepo.security.scan.PolicyEvaluator.FindingView;
import com.github.klboke.kkrepo.security.scan.PolicyEvaluator.Input;
import com.github.klboke.kkrepo.security.scan.PolicyEvaluator.Rule;
import com.github.klboke.kkrepo.security.scan.ScanEnums.PolicyAction;
import com.github.klboke.kkrepo.security.scan.ScanEnums.PolicyDecision;
import com.github.klboke.kkrepo.security.scan.ScanEnums.ScanCompleteness;
import com.github.klboke.kkrepo.security.scan.ScanEnums.ScanState;
import com.github.klboke.kkrepo.security.scan.ScanEnums.Severity;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class PolicyEvaluatorTest {
  private final Rule rule = new Rule(
      Severity.HIGH, false, true, true,
      PolicyAction.BLOCK, PolicyAction.BLOCK, PolicyAction.BLOCK);

  @Test
  void blocksUnwaivedHighFinding() {
    var result = PolicyEvaluator.evaluate(rule, new Input(
        ScanState.COMPLETE, ScanCompleteness.COMPLETE, true, false,
        List.of(
            new FindingView("one", Severity.HIGH, true, false),
            new FindingView("two", Severity.CRITICAL, true, true)),
        Instant.EPOCH));

    assertEquals(PolicyDecision.BLOCK_VULNERABILITY, result.decision());
    assertEquals(1, result.blockingFindings());
    assertEquals(1, result.waivedFindings());
  }

  @Test
  void pendingAndPartialActionsAreExplicit() {
    assertEquals(PolicyDecision.BLOCK_PENDING, PolicyEvaluator.evaluate(rule, new Input(
        ScanState.PENDING, ScanCompleteness.UNKNOWN, false, false, List.of(), Instant.EPOCH))
        .decision());
    assertEquals(PolicyDecision.BLOCK_PARTIAL, PolicyEvaluator.evaluate(rule, new Input(
        ScanState.PARTIAL, ScanCompleteness.PARTIAL, false, false, List.of(), Instant.EPOCH))
        .decision());
  }

  @Test
  void evaluatesFailureAllowAndFixabilityBranches() {
    assertEquals(PolicyDecision.BLOCK_SCAN_FAILED, PolicyEvaluator.evaluate(rule, new Input(
        ScanState.FAILED, ScanCompleteness.UNKNOWN, false, false, List.of(), Instant.EPOCH))
        .decision());

    Rule fixableOnly = new Rule(
        Severity.MEDIUM, true, false, false,
        PolicyAction.ALLOW, PolicyAction.ALLOW, PolicyAction.ALLOW);
    var allowed = PolicyEvaluator.evaluate(fixableOnly, new Input(
        ScanState.COMPLETE, ScanCompleteness.COMPLETE, true, false,
        List.of(
            new FindingView("unknown", Severity.UNKNOWN, true, false),
            new FindingView("not-fixable", Severity.HIGH, false, false)),
        Instant.EPOCH));
    assertEquals(PolicyDecision.ALLOW, allowed.decision());
    assertEquals(2, allowed.evaluatedFindings());
  }

  @Test
  void selectsTheStricterDecisionAcrossEveryRank() {
    assertEquals(PolicyDecision.ALLOW, PolicyEvaluator.stricter(null, null));
    assertEquals(
        PolicyDecision.BLOCK_PENDING,
        PolicyEvaluator.stricter(null, PolicyDecision.BLOCK_PENDING));
    assertEquals(
        PolicyDecision.BLOCK_PARTIAL,
        PolicyEvaluator.stricter(PolicyDecision.BLOCK_PARTIAL, null));
    assertEquals(
        PolicyDecision.BLOCK_SCAN_FAILED,
        PolicyEvaluator.stricter(
            PolicyDecision.BLOCK_PENDING, PolicyDecision.BLOCK_SCAN_FAILED));
    assertEquals(
        PolicyDecision.BLOCK_VULNERABILITY,
        PolicyEvaluator.stricter(
            PolicyDecision.BLOCK_VULNERABILITY, PolicyDecision.BLOCK_PARTIAL));
  }
}
