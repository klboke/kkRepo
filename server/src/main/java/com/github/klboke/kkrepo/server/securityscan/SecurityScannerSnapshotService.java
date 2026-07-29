package com.github.klboke.kkrepo.server.securityscan;

import com.github.klboke.kkrepo.persistence.jdbc.api.SecurityScanDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.SecurityScanDao.ScannerSnapshot;
import com.github.klboke.kkrepo.security.scan.ScanFingerprints;
import com.github.klboke.kkrepo.security.scan.ScannerContract;
import com.github.klboke.kkrepo.security.scan.ScannerContract.Adapter;
import com.github.klboke.kkrepo.security.scan.ScannerContract.MatchResponse;
import com.github.klboke.kkrepo.security.scan.ScannerContract.Observation;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Service;

/** Persists scanner provenance so every replica schedules and evaluates against shared snapshots. */
@Service
public class SecurityScannerSnapshotService {
  private static final Duration OBSERVATION_TTL = Duration.ofSeconds(30);

  private final Adapter adapter;
  private final SecurityScanDao scans;
  private final SecurityScanningProperties properties;
  private final SecurityScanMetrics metrics;

  public SecurityScannerSnapshotService(
      Adapter adapter,
      SecurityScanDao scans,
      SecurityScanningProperties properties,
      SecurityScanMetrics metrics) {
    this.adapter = adapter;
    this.scans = scans;
    this.properties = properties;
    this.metrics = metrics;
  }

  public ScannerSnapshot readySnapshot() {
    Instant now = Instant.now();
    var recent = scans.latestScannerSnapshot()
        .filter(snapshot -> snapshot.observedAt() != null
            && !SecurityScannerStatus.observedTooFarInFuture(snapshot.observedAt(), now)
            && snapshot.observedAt().plus(OBSERVATION_TTL).isAfter(now));
    if (recent.isPresent()) {
      requireReady(recent.get(), now);
      ScannerSnapshot authoritative = authoritativeSnapshot(recent.get(), now);
      metrics.observeScanner(
          authoritative.ready(), authoritative.vulnerabilityDatabaseUpdatedAt());
      return authoritative;
    }

    final Observation observation;
    try {
      observation =
          java.util.Objects.requireNonNull(adapter.observation(), "scanner observation");
    } catch (ScannerAdapterException e) {
      observeFailure(e.code(), now);
      throw e;
    } catch (RuntimeException e) {
      observeFailure("SCANNER_OBSERVATION_FAILED", now);
      throw new ScannerAdapterException(
          "SCANNER_OBSERVATION_FAILED",
          "Scanner adapter observation failed",
          true,
          e);
    }
    ScannerContract.Capabilities capabilities = observation.capabilities();
    ScannerContract.Readiness readiness = observation.readiness();
    if (!ScannerContract.API_VERSION.equals(capabilities.apiVersion())) {
      observeFailure("SCANNER_API_UNSUPPORTED", now);
      throw new ScannerAdapterException(
          "SCANNER_API_UNSUPPORTED",
          "Scanner adapter API version is not supported",
          false);
    }
    Map<String, Object> details = new LinkedHashMap<>(readiness.details());
    if (readiness.observedAt() != null) {
      details.put("adapterObservedAt", readiness.observedAt().toString());
    }
    details.put("adapterVersion", capabilities.adapterVersion());
    details.put("operations", capabilities.operations());
    details.put("targetClassifications", capabilities.targetClassifications());
    details.put("maxInputBytes", capabilities.maxInputBytes());
    details.put("maxOutputBytes", capabilities.maxOutputBytes());
    Instant databaseUpdatedAt =
        ScannerContract.canonicalDatabaseTimestamp(readiness.vulnerabilityDatabaseUpdatedAt());
    String fingerprint = ScanFingerprints.sha256(
        capabilities.adapterName(),
        capabilities.adapterVersion(),
        capabilities.apiVersion(),
        readiness.engineName(),
        readiness.engineVersion(),
        readiness.vulnerabilityDatabaseRevision(),
        databaseTimestamp(databaseUpdatedAt),
        capabilities.capabilityDigest(),
        Boolean.toString(readiness.ready()));
    ScannerSnapshot proposed = new ScannerSnapshot(
        null,
        capabilities.adapterName(),
        capabilities.apiVersion(),
        readiness.engineName(),
        readiness.engineVersion(),
        readiness.vulnerabilityDatabaseRevision(),
        databaseUpdatedAt,
        capabilities.capabilityDigest(),
        fingerprint,
        now,
        readiness.ready(),
        details);
    rejectFutureDatabaseTimestamp(proposed, now);
    ScannerSnapshot observed = scans.insertSnapshotOrFindExisting(proposed);
    requireReady(observed, now);
    ScannerSnapshot authoritative = authoritativeSnapshot(observed, now);
    metrics.observeScanner(
        authoritative.ready(), authoritative.vulnerabilityDatabaseUpdatedAt());
    return authoritative;
  }

  public ScannerSnapshot snapshotFor(
      MatchResponse response, ScannerSnapshot readinessSnapshot) {
    Instant now = Instant.now();
    Instant databaseUpdatedAt =
        ScannerContract.canonicalDatabaseTimestamp(response.vulnerabilityDatabaseUpdatedAt());
    String fingerprint = ScanFingerprints.sha256(
        response.adapterName(),
        response.adapterVersion(),
        ScannerContract.API_VERSION,
        response.engineName(),
        response.engineVersion(),
        response.vulnerabilityDatabaseRevision(),
        databaseTimestamp(databaseUpdatedAt),
        response.capabilityDigest(),
        "true");
    Map<String, Object> details = new LinkedHashMap<>();
    if (readinessSnapshot != null
        && fingerprint.equals(readinessSnapshot.snapshotFingerprint())) {
      details.putAll(readinessSnapshot.details());
    }
    details.put("adapterVersion", response.adapterVersion());
    ScannerSnapshot proposed = new ScannerSnapshot(
        null,
        response.adapterName(),
        ScannerContract.API_VERSION,
        response.engineName(),
        response.engineVersion(),
        response.vulnerabilityDatabaseRevision(),
        databaseUpdatedAt,
        response.capabilityDigest(),
        fingerprint,
        now,
        true,
        details);
    rejectFutureDatabaseTimestamp(proposed, now);
    ScannerSnapshot snapshot = scans.insertSnapshotOrFindExisting(proposed);
    requireReady(snapshot, now);
    return snapshot;
  }

  private ScannerSnapshot authoritativeSnapshot(ScannerSnapshot fallback, Instant now) {
    return scans.latestReadyScannerSnapshot(
            SecurityScannerStatus.maximumProvenanceTimestamp(now))
        .orElse(fallback);
  }

  private static void rejectFutureDatabaseTimestamp(ScannerSnapshot snapshot, Instant now) {
    if (snapshot.vulnerabilityDatabaseUpdatedAt() != null
        && SecurityScannerStatus.databaseTooFarInFuture(
            snapshot.vulnerabilityDatabaseUpdatedAt(), now)) {
      throw new ScannerAdapterException(
          "SCANNER_DATABASE_STALE",
          "Scanner vulnerability database update time is in the future",
          true);
    }
  }

  private void requireReady(ScannerSnapshot snapshot, Instant now) {
    if (!snapshot.ready()) {
      throw new ScannerAdapterException(
          "SCANNER_NOT_READY", "Scanner adapter is not ready", true);
    }
    if (snapshot.vulnerabilityDatabaseRevision() == null
        || snapshot.vulnerabilityDatabaseRevision().isBlank()) {
      throw new ScannerAdapterException(
          "SCANNER_DATABASE_UNKNOWN",
          "Scanner vulnerability database revision is unavailable",
          true);
    }
    if (snapshot.vulnerabilityDatabaseUpdatedAt() == null) {
      throw new ScannerAdapterException(
          "SCANNER_DATABASE_AGE_UNKNOWN",
          "Scanner vulnerability database update time is unavailable",
          true);
    }
    rejectFutureDatabaseTimestamp(snapshot, now);
    Duration maxAge = properties.getScannerDatabaseMaxAge();
    if (maxAge != null && !maxAge.isZero() && !maxAge.isNegative()
        && snapshot.vulnerabilityDatabaseUpdatedAt().plus(maxAge).isBefore(now)) {
      throw new ScannerAdapterException(
          "SCANNER_DATABASE_STALE",
          "Scanner vulnerability database is older than the configured maximum",
          true);
    }
  }

  private void observeFailure(String reasonCode, Instant observedAt) {
    String digest = ScanFingerprints.sha256("scanner-observation-failure", reasonCode);
    scans.insertSnapshotOrFindExisting(new ScannerSnapshot(
        null,
        "configured-adapter",
        ScannerContract.API_VERSION,
        "unavailable",
        "unavailable",
        null,
        null,
        digest,
        digest,
        observedAt,
        false,
        Map.of("reasonCode", reasonCode)));
    metrics.observeScanner(false, null);
  }

  private static String databaseTimestamp(Instant value) {
    return value == null ? "" : value.toString();
  }
}
