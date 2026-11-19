package com.github.klboke.kkrepo.server.kkguard.dependencytrack;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

/**
 * Minimal Dependency-Track API surface used by kkGuard.
 *
 * <p>kkGuard owns the compatibility API, application catalogue and policy evaluation; this
 * interface is the only place that knows about Dependency-Track. Implementations must never leak
 * the API key through exception messages or logs.
 */
public interface DependencyTrackClient {
  /** A Dependency-Track project; {@code uuid} is the stable Dependency-Track identifier. */
  record Project(String uuid, String name, String version) {}

  /** A normalized finding: one vulnerable component and one vulnerability. */
  record Finding(
      String componentRef, String purl, String componentName, String componentVersion,
      String vulnerabilityId, String severity, BigDecimal cvssScore, String source,
      String description, String url, boolean suppressed) {}

  /** A Dependency-Track policy violation retained as upstream evidence. */
  record Violation(
      String type, String componentRef, String policyName, String condition, String state) {}

  /** True when a URL and an API key are configured. */
  boolean isConfigured();

  Optional<Project> findProject(String name, String version);

  Project createProject(String name, String version);

  /** Uploads a CycloneDX document and returns the Dependency-Track processing token. */
  String uploadBom(String projectUuid, byte[] bom);

  /** True while Dependency-Track still processes the tasks associated with the token. */
  boolean isProcessing(String token);

  List<Finding> listFindings(String projectUuid);

  List<Violation> listViolations(String projectUuid);
}
