package com.github.klboke.kkrepo.server.kkguard;

import com.github.klboke.kkrepo.persistence.jdbc.api.KkGuardDao.Finding;
import com.github.klboke.kkrepo.persistence.jdbc.api.KkGuardDao.Policy;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Deterministic local policy evaluation for kkGuard.
 *
 * <p>Policies are {@code kkg_policy} rows scoped to an organization. A policy matches a finding
 * when every recognized constraint matches (logical AND); unrecognized constraints are ignored and
 * a policy with no recognized constraint never matches, so a malformed policy cannot silently
 * violate every component. Supported constraints:
 *
 * <ul>
 *   <li>{@code severity}: minimum severity ({@code LOW}, {@code MEDIUM}, {@code HIGH},
 *       {@code CRITICAL})
 *   <li>{@code cvssScore}: minimum CVSS base score
 *   <li>{@code vulnerabilityId}: exact vulnerability identifier
 *   <li>{@code componentRef}: component reference prefix (usually a purl)
 * </ul>
 *
 * <p>Evaluation is local on purpose: results stay independent of the Dependency-Track policy
 * configuration, so a Dependency-Track upgrade cannot change kkGuard violations.
 */
public final class KkGuardPolicyEvaluator {
  static final List<String> SEVERITIES = List.of("LOW", "MEDIUM", "HIGH", "CRITICAL");

  private KkGuardPolicyEvaluator() {
  }

  /** A local violation produced by one policy and one finding. */
  public record Match(
      long policyId, String policyPublicId, String policyName, int threatLevel,
      String componentRef, Map<String, Object> constraint) {}

  public static List<Match> evaluate(List<Policy> policies, List<Finding> findings) {
    List<Match> matches = new ArrayList<>();
    for (Policy policy : policies) {
      Map<String, Object> constraints = policy.constraints() == null
          ? Map.of() : policy.constraints();
      if (!hasRecognizedConstraint(constraints)) {
        continue;
      }
      for (Finding finding : findings) {
        if (matches(constraints, finding)) {
          matches.add(new Match(
              policy.id(), policy.publicId(), policy.name(), policy.threatLevel(),
              finding.componentRef(), describe(finding)));
        }
      }
    }
    return matches;
  }

  private static boolean hasRecognizedConstraint(Map<String, Object> constraints) {
    if (constraints.containsKey("severity") && severityRank(text(constraints.get("severity"))) == 0) {
      // An unparseable severity would otherwise match every finding and violate everything.
      return false;
    }
    return constraints.containsKey("severity") || constraints.containsKey("cvssScore")
        || constraints.containsKey("vulnerabilityId") || constraints.containsKey("componentRef");
  }

  private static boolean matches(Map<String, Object> constraints, Finding finding) {
    if (constraints.containsKey("severity")
        && severityRank(finding.severity()) < severityRank(text(constraints.get("severity")))) {
      return false;
    }
    if (constraints.containsKey("cvssScore")) {
      BigDecimal minimum = decimal(constraints.get("cvssScore"));
      if (minimum == null || finding.cvssScore() == null
          || finding.cvssScore().compareTo(minimum) < 0) {
        return false;
      }
    }
    if (constraints.containsKey("vulnerabilityId")) {
      String expected = text(constraints.get("vulnerabilityId"));
      if (expected == null || finding.vulnerabilityId() == null
          || !expected.equalsIgnoreCase(finding.vulnerabilityId())) {
        return false;
      }
    }
    if (constraints.containsKey("componentRef")) {
      String prefix = text(constraints.get("componentRef"));
      if (prefix == null || finding.componentRef() == null
          || !finding.componentRef().startsWith(prefix)) {
        return false;
      }
    }
    return true;
  }

  private static Map<String, Object> describe(Finding finding) {
    Map<String, Object> description = new LinkedHashMap<>();
    description.put("vulnerabilityId", finding.vulnerabilityId());
    description.put("severity", finding.severity());
    if (finding.cvssScore() != null) {
      description.put("cvssScore", finding.cvssScore());
    }
    description.put("componentRef", finding.componentRef());
    return description;
  }

  static int severityRank(String severity) {
    int index = severity == null ? -1 : SEVERITIES.indexOf(severity.toUpperCase(Locale.ROOT));
    return index < 0 ? 0 : index + 1;
  }

  private static String text(Object value) {
    if (value == null) {
      return null;
    }
    String text = value.toString().trim();
    return text.isEmpty() ? null : text;
  }

  private static BigDecimal decimal(Object value) {
    if (value instanceof Number number) {
      return new BigDecimal(number.toString());
    }
    String text = text(value);
    if (text == null) {
      return null;
    }
    try {
      return new BigDecimal(text);
    } catch (NumberFormatException e) {
      return null;
    }
  }
}
