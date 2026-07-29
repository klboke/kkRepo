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
            && snapshot.observedAt().plus(OBSERVATION_TTL).isAfter(now));
    if (recent.isPresent()) {
      metrics.observeScanner(
          recent.get().ready(), recent.get().vulnerabilityDatabaseUpdatedAt());
      requireReady(recent.get(), now);
      return recent.get();
    }

    final Observation observation;
    try {
      observation = adapter.observation();
    } catch (ScannerAdapterException e) {
      observeFailure(e.code(), now);
      throw e;
    } catch (RuntimeException e) {
      observeFailure("SCANNER_OBSERVATION_FAILED", now);
      throw e;
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
    details.put("adapterVersion", capabilities.adapterVersion());
    details.put("operations", capabilities.operations());
    details.put("targetClassifications", capabilities.targetClassifications());
    details.put("maxInputBytes", capabilities.maxInputBytes());
    details.put("maxOutputBytes", capabilities.maxOutputBytes());
    String fingerprint = ScanFingerprints.sha256(
        capabilities.adapterName(),
        capabilities.adapterVersion(),
        capabilities.apiVersion(),
        readiness.engineName(),
        readiness.engineVersion(),
        readiness.vulnerabilityDatabaseRevision(),
        capabilities.capabilityDigest(),
        Boolean.toString(readiness.ready()));
    ScannerSnapshot snapshot = scans.insertSnapshotOrFindExisting(new ScannerSnapshot(
        null,
        capabilities.adapterName(),
        capabilities.apiVersion(),
        readiness.engineName(),
        readiness.engineVersion(),
        readiness.vulnerabilityDatabaseRevision(),
        readiness.vulnerabilityDatabaseUpdatedAt(),
        capabilities.capabilityDigest(),
        fingerprint,
        readiness.observedAt() == null ? now : readiness.observedAt(),
        readiness.ready(),
        details));
    metrics.observeScanner(snapshot.ready(), snapshot.vulnerabilityDatabaseUpdatedAt());
    requireReady(snapshot, now);
    return snapshot;
  }

  public ScannerSnapshot snapshotFor(
      MatchResponse response, ScannerSnapshot readinessSnapshot) {
    Instant now = Instant.now();
    String fingerprint = ScanFingerprints.sha256(
        response.adapterName(),
        response.adapterVersion(),
        ScannerContract.API_VERSION,
        response.engineName(),
        response.engineVersion(),
        response.vulnerabilityDatabaseRevision(),
        response.capabilityDigest(),
        "true");
    Map<String, Object> details = new LinkedHashMap<>();
    if (readinessSnapshot != null
        && fingerprint.equals(readinessSnapshot.snapshotFingerprint())) {
      details.putAll(readinessSnapshot.details());
    }
    details.put("adapterVersion", response.adapterVersion());
    ScannerSnapshot snapshot = scans.insertSnapshotOrFindExisting(new ScannerSnapshot(
        null,
        response.adapterName(),
        ScannerContract.API_VERSION,
        response.engineName(),
        response.engineVersion(),
        response.vulnerabilityDatabaseRevision(),
        response.vulnerabilityDatabaseUpdatedAt(),
        response.capabilityDigest(),
        fingerprint,
        now,
        true,
        details));
    requireReady(snapshot, now);
    return snapshot;
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
}
