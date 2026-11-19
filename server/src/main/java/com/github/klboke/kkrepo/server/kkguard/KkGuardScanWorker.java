package com.github.klboke.kkrepo.server.kkguard;

import com.github.klboke.kkrepo.core.BlobReference;
import com.github.klboke.kkrepo.core.BlobStorage;
import com.github.klboke.kkrepo.persistence.jdbc.api.KkGuardDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.KkGuardDao.Application;
import com.github.klboke.kkrepo.persistence.jdbc.api.KkGuardDao.Finding;
import com.github.klboke.kkrepo.persistence.jdbc.api.KkGuardDao.Scan;
import com.github.klboke.kkrepo.persistence.jdbc.api.KkGuardDao.ScanComponent;
import com.github.klboke.kkrepo.persistence.jdbc.api.KkGuardDao.Violation;
import com.github.klboke.kkrepo.server.blob.BlobReferenceCodec;
import com.github.klboke.kkrepo.server.kkguard.dependencytrack.DependencyTrackClient;
import com.github.klboke.kkrepo.server.kkguard.dependencytrack.DependencyTrackClient.Project;
import com.github.klboke.kkrepo.server.kkguard.dependencytrack.DependencyTrackException;
import com.github.klboke.kkrepo.server.maven.BlobStorageRegistry;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Drives kkGuard scans on every replica.
 *
 * <p>Each pass claims a bounded batch of scans with a conditional lease update, so two replicas can
 * never submit or complete the same scan: a lost lease makes {@code markScanSubmitted},
 * {@code markScanPolled} and {@code finishScan} no-ops. Every intermediate state lives in
 * {@code kkg_scan}, so a replica restart loses only the current pass and the next pass resumes
 * from the durable token. The SBOM blob is read back from shared object storage and its digest is
 * re-verified before submission.
 */
@Component
public class KkGuardScanWorker {
  private static final Logger log = LoggerFactory.getLogger(KkGuardScanWorker.class);
  private static final HexFormat HEX = HexFormat.of();
  private static final int MAX_SUBMIT_ATTEMPTS = 3;

  private final KkGuardDao dao;
  private final KkGuardProperties properties;
  private final DependencyTrackClient dependencyTrack;
  private final KkGuardScanService scans;
  private final BlobStorageRegistry storages;
  private final TransactionTemplate transactions;
  private final String owner = "kkg-" + UUID.randomUUID();

  public KkGuardScanWorker(
      KkGuardDao dao, KkGuardProperties properties, DependencyTrackClient dependencyTrack,
      KkGuardScanService scans, BlobStorageRegistry storages,
      PlatformTransactionManager transactionManager) {
    this.dao = dao;
    this.properties = properties;
    this.dependencyTrack = dependencyTrack;
    this.scans = scans;
    this.storages = storages;
    this.transactions = new TransactionTemplate(transactionManager);
  }

  @Scheduled(fixedDelayString = "${kkrepo.kkg.worker.idle-delay-ms:5000}")
  public void run() {
    try {
      runOnce(Instant.now());
    } catch (RuntimeException e) {
      log.warn("kkGuard scan worker pass failed", e);
    }
  }

  /** One bounded pass; package-visible so tests can drive it deterministically. */
  public void runOnce(Instant now) {
    KkGuardProperties.Worker worker = properties.getWorker();
    if (!properties.isEnabled() || !worker.isEnabled() || !dependencyTrack.isConfigured()) {
      return;
    }
    Instant leaseUntil = now.plus(worker.getLeaseDuration());
    for (Long scanId : dao.listClaimableScanIds(now, worker.getBatchSize())) {
      if (scanId == null || !dao.claimScan(scanId, owner, now, leaseUntil)) {
        continue;
      }
      try {
        process(scanId, now, leaseUntil);
      } catch (RuntimeException e) {
        log.warn("kkGuard scan {} failed: {}", scanId, e.getMessage());
        fail(scanId, e);
      } finally {
        dao.releaseScan(scanId, owner);
      }
    }
  }

  private void process(long scanId, Instant now, Instant leaseUntil) {
    Scan scan = dao.findScan(scanId)
        .orElseThrow(() -> new DependencyTrackException("Scan " + scanId + " disappeared"));
    if ("PENDING".equals(scan.status())) {
      submit(scan, now);
    } else if ("SCANNING".equals(scan.status())) {
      poll(scan, now);
    }
  }

  private void submit(Scan scan, Instant now) {
    Instant deadline = scan.createdAt().plus(properties.getDependencyTrack().getPollTimeout());
    if (now.isAfter(deadline)) {
      finish(scan, "FAILED", "Dependency-Track submission timed out", 0, 0);
      return;
    }
    Application application = dao.findApplication(scan.applicationId())
        .orElseThrow(() -> new DependencyTrackException("Application not found"));
    try {
      byte[] sbom = readSbom(scan);
      Project project = ensureProject(application, scan.sourceId(), now);
      String token = dependencyTrack.uploadBom(project.uuid(), sbom);
      dao.markScanSubmitted(scan.id(), owner, project.uuid(), token, Instant.now());
    } catch (DependencyTrackException | IOException e) {
      if (scan.attempts() + 1 >= MAX_SUBMIT_ATTEMPTS) {
        finish(scan, "FAILED", sanitize(e), 0, 0);
        return;
      }
      dao.markScanPolled(scan.id(), owner, scan.attempts() + 1, Instant.now());
      throw e instanceof RuntimeException runtime
          ? runtime : new DependencyTrackException("Scan submission failed", e);
    }
  }

  private void poll(Scan scan, Instant now) {
    if (scan.dtBomToken() == null || scan.dtProjectUuid() == null) {
      finish(scan, "FAILED", "Dependency-Track submission state is incomplete", 0, 0);
      return;
    }
    Instant deadline = scan.submittedAt() == null
        ? now : scan.submittedAt().plus(properties.getDependencyTrack().getPollTimeout());
    if (now.isAfter(deadline)) {
      finish(scan, "FAILED", "Dependency-Track analysis timed out", 0, 0);
      return;
    }
    if (dependencyTrack.isProcessing(scan.dtBomToken())) {
      dao.markScanPolled(scan.id(), owner, scan.attempts() + 1, now);
      return;
    }
    List<Finding> findings = dependencyTrack.listFindings(scan.dtProjectUuid()).stream()
        .map(finding -> new Finding(
            scan.id(), finding.componentRef(), finding.vulnerabilityId(), finding.severity(),
            finding.cvssScore(), finding.source(), finding.description(), finding.url()))
        .toList();
    List<Violation> violations = scans.evaluatePolicies(scan.applicationId(), scan.id(), findings);
    List<ScanComponent> components = KkGuardScanService.componentsOf(scan.id(), findings);
    // One transaction for the whole snapshot and the terminal transition: every write is fenced by
    // the lease owner, and a replica whose lease expired half-way rolls back instead of leaving a
    // partial snapshot behind the replica that took over.
    transactions.executeWithoutResult(status -> {
      if (!dao.replaceScanComponents(scan.id(), owner, components)
          || !dao.replaceScanFindings(scan.id(), owner, findings)
          || !dao.replaceViolations(scan.id(), owner, violations)) {
        throw new DependencyTrackException(
            "Scan " + scan.id() + " is no longer leased to this replica; snapshot discarded");
      }
      if (!finish(scan, "COMPLETE", null, findings.size(), violations.size())) {
        throw new DependencyTrackException(
            "Scan " + scan.id() + " lost its lease before completion");
      }
    });
  }

  private Project ensureProject(Application application, String sourceId, Instant now) {
    if (application.dtProjectUuid() != null && !application.dtProjectUuid().isBlank()) {
      return new Project(application.dtProjectUuid(), application.publicId(),
          projectVersion(application, sourceId));
    }
    String version = projectVersion(application, sourceId);
    Optional<Project> existing =
        dependencyTrack.findProject(application.publicId(), version);
    Project project = existing.orElseGet(() -> {
      if (!properties.getDependencyTrack().isAutoCreateProjects()) {
        throw new DependencyTrackException(
            "Dependency-Track project " + application.publicId() + ":" + version
                + " does not exist and auto-creation is disabled");
      }
      return dependencyTrack.createProject(application.publicId(), version);
    });
    dao.updateApplicationDependencyTrackProject(
        application.id(), project.uuid(), project.name(), now);
    return project;
  }

  private String projectVersion(Application application, String sourceId) {
    return properties.getDependencyTrack().getProjectVersionTemplate()
        .replace("{sourceId}", sourceId == null ? "cli" : sourceId)
        .replace("{publicId}", application.publicId());
  }

  private byte[] readSbom(Scan scan) throws IOException {
    var sbom = dao.findScanSbom(scan.id()).orElseThrow(
        () -> new DependencyTrackException("Scan " + scan.id() + " has no stored SBOM"));
    if (sbom.size() > properties.getMaxSbomBytes()) {
      throw new DependencyTrackException("Stored SBOM exceeds the configured size limit");
    }
    BlobStorage storage = storages.forBlobStoreId(sbom.blobStoreId());
    BlobReference reference = BlobReferenceCodec.reference(
        sbom.blobRef(), null, sbom.sha256(), sbom.size());
    try (InputStream input = storage.get(reference).orElseThrow(
        () -> new DependencyTrackException("Stored SBOM blob is missing"))) {
      byte[] bytes = input.readNBytes((int) Math.min(sbom.size() + 1, Integer.MAX_VALUE));
      if (bytes.length != sbom.size() || !digest(bytes).equalsIgnoreCase(sbom.sha256())) {
        throw new DependencyTrackException("Stored SBOM failed its integrity check");
      }
      return bytes;
    }
  }

  private void fail(long scanId, RuntimeException error) {
    dao.findScan(scanId).ifPresent(scan -> finish(scan, "FAILED", sanitize(error), 0, 0));
  }

  /** Returns {@code false} when the lease was lost, i.e. the transition was a no-op. */
  private boolean finish(Scan scan, String status, String error, int components, int violations) {
    return dao.finishScan(scan.id(), owner, status, error, components, violations, Instant.now());
  }

  private static String sanitize(Exception error) {
    String message = error.getMessage();
    if (message == null || message.isBlank()) {
      return error.getClass().getSimpleName();
    }
    return message.length() > 512 ? message.substring(0, 512) : message;
  }

  private static String digest(byte[] bytes) {
    try {
      return HEX.formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  /** Exposed for diagnostics: the per-replica lease owner identity. */
  public String owner() {
    return owner;
  }

  /** Lease duration applied to every claimed scan. */
  public Duration leaseDuration() {
    return properties.getWorker().getLeaseDuration();
  }
}
