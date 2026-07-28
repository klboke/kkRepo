package com.github.klboke.kkrepo.security.scan;

import com.github.klboke.kkrepo.security.scan.ScanEnums.PolicyAction;
import com.github.klboke.kkrepo.security.scan.ScanEnums.PolicyDecision;
import com.github.klboke.kkrepo.security.scan.ScanEnums.ScanCompleteness;
import com.github.klboke.kkrepo.security.scan.ScanEnums.ScanState;
import com.github.klboke.kkrepo.security.scan.ScanEnums.Severity;
import java.time.Instant;
import java.util.List;

/** Pure policy calculation; persistence materializes the result for the download hot path. */
public final class PolicyEvaluator {
  private PolicyEvaluator() {}

  public record Rule(
      Severity blockAtOrAbove,
      boolean onlyFixable,
      boolean blockUnknownSeverity,
      boolean requireCompleteInventory,
      PolicyAction pendingAction,
      PolicyAction failureAction,
      PolicyAction partialAction,
      boolean enabled,
      List<String> requiredPlatforms) {
    public Rule {
      blockAtOrAbove = blockAtOrAbove == null ? Severity.CRITICAL : blockAtOrAbove;
      pendingAction = pendingAction == null ? PolicyAction.ALLOW : pendingAction;
      failureAction = failureAction == null ? PolicyAction.ALLOW : failureAction;
      partialAction = partialAction == null ? PolicyAction.ALLOW : partialAction;
      requiredPlatforms =
          requiredPlatforms == null ? List.of() : List.copyOf(requiredPlatforms);
    }

    public Rule(
        Severity blockAtOrAbove,
        boolean onlyFixable,
        boolean blockUnknownSeverity,
        boolean requireCompleteInventory,
        PolicyAction pendingAction,
        PolicyAction failureAction,
        PolicyAction partialAction) {
      this(
          blockAtOrAbove,
          onlyFixable,
          blockUnknownSeverity,
          requireCompleteInventory,
          pendingAction,
          failureAction,
          partialAction,
          true,
          List.of());
    }
  }

  public record FindingView(
      String findingKey,
      Severity severity,
      boolean fixable,
      boolean waived) {}

  public record Input(
      ScanState state,
      ScanCompleteness completeness,
      boolean inventoryComplete,
      boolean stale,
      List<FindingView> findings,
      Instant evaluatedAt,
      boolean ociSubject,
      List<String> scannedPlatforms) {
    public Input {
      state = state == null ? ScanState.PENDING : state;
      completeness = completeness == null ? ScanCompleteness.UNKNOWN : completeness;
      findings = findings == null ? List.of() : List.copyOf(findings);
      evaluatedAt = evaluatedAt == null ? Instant.now() : evaluatedAt;
      scannedPlatforms =
          scannedPlatforms == null ? List.of() : List.copyOf(scannedPlatforms);
    }

    public Input(
        ScanState state,
        ScanCompleteness completeness,
        boolean inventoryComplete,
        boolean stale,
        List<FindingView> findings,
        Instant evaluatedAt) {
      this(
          state,
          completeness,
          inventoryComplete,
          stale,
          findings,
          evaluatedAt,
          false,
          List.of());
    }
  }

  public record Evaluation(
      PolicyDecision decision,
      String reasonCode,
      int evaluatedFindings,
      int waivedFindings,
      int blockingFindings,
      Severity maxBlockingSeverity) {}

  public static Evaluation evaluate(Rule rule, Input input) {
    if (input.stale() || input.state() == ScanState.PENDING || input.state() == ScanState.RUNNING
        || input.state() == ScanState.STALE) {
      return decision(rule.pendingAction(), PolicyDecision.BLOCK_PENDING, "SCAN_PENDING");
    }
    if (input.state() == ScanState.FAILED || input.state() == ScanState.CANCELLED) {
      return decision(rule.failureAction(), PolicyDecision.BLOCK_SCAN_FAILED, "SCAN_FAILED");
    }
    if (!rule.enabled()) {
      return new Evaluation(
          PolicyDecision.ALLOW,
          "POLICY_DISABLED",
          0,
          0,
          0,
          Severity.UNKNOWN);
    }
    boolean requiredPlatformMissing = input.ociSubject()
        && rule.requiredPlatforms().stream()
            .anyMatch(platform -> !input.scannedPlatforms().contains(platform));
    if (input.state() == ScanState.PARTIAL
        || input.completeness() != ScanCompleteness.COMPLETE
        || (rule.requireCompleteInventory() && !input.inventoryComplete())
        || requiredPlatformMissing) {
      Evaluation partial = decision(
          rule.partialAction(),
          PolicyDecision.BLOCK_PARTIAL,
          requiredPlatformMissing ? "REQUIRED_PLATFORMS_MISSING" : "SCAN_PARTIAL");
      if (partial.decision().blocked()) {
        return partial;
      }
    }

    int waived = 0;
    int blocking = 0;
    Severity maximum = Severity.UNKNOWN;
    for (FindingView finding : input.findings()) {
      if (finding.waived()) {
        waived++;
        continue;
      }
      Severity severity = finding.severity() == null ? Severity.UNKNOWN : finding.severity();
      boolean matches = severity == Severity.UNKNOWN
          ? rule.blockUnknownSeverity()
          : severity.atLeast(rule.blockAtOrAbove());
      if (rule.onlyFixable() && !finding.fixable()) {
        matches = false;
      }
      if (matches) {
        blocking++;
        if (severity.rank() > maximum.rank()) {
          maximum = severity;
        }
      }
    }
    if (blocking > 0) {
      return new Evaluation(
          PolicyDecision.BLOCK_VULNERABILITY,
          "VULNERABILITY_THRESHOLD",
          input.findings().size(),
          waived,
          blocking,
          maximum);
    }
    return new Evaluation(
        PolicyDecision.ALLOW,
        "POLICY_PASSED",
        input.findings().size(),
        waived,
        0,
        Severity.UNKNOWN);
  }

  public static PolicyDecision stricter(PolicyDecision first, PolicyDecision second) {
    if (first == null) return second == null ? PolicyDecision.ALLOW : second;
    if (second == null) return first;
    return rank(first) >= rank(second) ? first : second;
  }

  private static Evaluation decision(
      PolicyAction action, PolicyDecision blockedDecision, String reasonCode) {
    PolicyDecision decision = action == PolicyAction.BLOCK ? blockedDecision : PolicyDecision.ALLOW;
    return new Evaluation(decision, reasonCode, 0, 0, 0, Severity.UNKNOWN);
  }

  private static int rank(PolicyDecision decision) {
    return switch (decision) {
      case ALLOW -> 0;
      case BLOCK_PENDING -> 1;
      case BLOCK_PARTIAL -> 2;
      case BLOCK_SCAN_FAILED -> 3;
      case BLOCK_VULNERABILITY -> 4;
    };
  }
}
