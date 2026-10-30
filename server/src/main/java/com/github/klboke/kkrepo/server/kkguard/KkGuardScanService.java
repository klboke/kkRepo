package com.github.klboke.kkrepo.server.kkguard;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.klboke.kkrepo.core.BlobReference;
import com.github.klboke.kkrepo.core.BlobStorage;
import com.github.klboke.kkrepo.persistence.jdbc.api.BlobStoreDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.KkGuardDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.KkGuardDao.Application;
import com.github.klboke.kkrepo.persistence.jdbc.api.KkGuardDao.Finding;
import com.github.klboke.kkrepo.persistence.jdbc.api.KkGuardDao.Policy;
import com.github.klboke.kkrepo.persistence.jdbc.api.KkGuardDao.Scan;
import com.github.klboke.kkrepo.persistence.jdbc.api.KkGuardDao.ScanComponent;
import com.github.klboke.kkrepo.persistence.jdbc.api.KkGuardDao.Violation;
import com.github.klboke.kkrepo.persistence.jdbc.api.RepositoryDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.BlobStoreRecord;
import com.github.klboke.kkrepo.server.blob.BlobReferenceCodec;
import com.github.klboke.kkrepo.server.maven.BlobStorageRegistry;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * kkGuard scan ingestion, status and report surface.
 *
 * <p>Ingestion stores the CycloneDX document as an immutable blob addressed by SHA-256 and inserts
 * a {@code PENDING} scan row. Submission to Dependency-Track, polling and normalization are driven
 * by {@link KkGuardScanWorker} so an HTTP request never blocks on a remote analysis; the scan row
 * and its lease are the only coordination state, which keeps every replica equivalent.
 */
@Service
public class KkGuardScanService {
  private static final HexFormat HEX = HexFormat.of();
  private static final int MAX_SOURCE_ID = 255;

  private final KkGuardDao dao;
  private final KkGuardProperties properties;
  private final RepositoryDao repositories;
  private final BlobStoreDao blobStores;
  private final BlobStorageRegistry storages;
  private final ObjectMapper objectMapper;

  public KkGuardScanService(
      KkGuardDao dao, KkGuardProperties properties, RepositoryDao repositories,
      BlobStoreDao blobStores, BlobStorageRegistry storages, ObjectMapper objectMapper) {
    this.dao = dao;
    this.properties = properties;
    this.repositories = repositories;
    this.blobStores = blobStores;
    this.storages = storages;
    this.objectMapper = objectMapper;
  }

  public record ScanStatusView(
      String scanId, String applicationId, String publicId, String sourceId, String status,
      String stage, int componentCount, int violationCount, String error, Instant createdAt,
      Instant startedAt, Instant completedAt) {}

  public record ComponentView(
      String componentRef, String purl, String name, String version, String highestSeverity) {}

  public record FindingView(
      String componentRef, String vulnerabilityId, String severity, BigDecimal cvssScore,
      String source, String description, String url) {}

  public record ViolationView(
      String componentRef, String policyId, String policyName, int threatLevel,
      Map<String, Object> constraint) {}

  public record ReportView(
      ScanStatusView scan, List<ComponentView> components, List<FindingView> findings,
      List<ViolationView> violations) {}

  @Transactional
  public ScanStatusView submit(
      String applicationId, String sourceId, String contentType, byte[] sbom) {
    Application application = requireApplication(applicationId);
    String source = normalizeSource(sourceId);
    byte[] document = validateSbom(contentType, sbom);
    String sha256 = digest(document);

    Optional<Scan> existing = dao.findCompletedScanBySbom(application.id(), source, sha256);
    if (existing.isPresent()) {
      return status(existing.get(), application);
    }

    BlobTarget target = blobTarget(application);
    BlobReference reference = target.storage().put(
        target.repositoryName(),
        ".kkrepo/kkguard/sbom/" + sha256 + ".json",
        new ByteArrayInputStream(document),
        document.length,
        sha256);

    Instant now = Instant.now();
    long scanId = dao.insertScan(new Scan(
        0L, application.id(), UUID.randomUUID().toString(), "PENDING", "build", source, sha256,
        null, 0, 0, null, null, null, 0, null, null, null, null, now, null, null));
    dao.attachScanSbom(
        scanId, target.blobStoreId(), BlobReferenceCodec.format(reference), sha256,
        document.length);
    return status(dao.findScan(scanId).orElseThrow(), application);
  }

  public ScanStatusView status(String applicationId, String sourceId) {
    Application application = requireApplication(applicationId);
    String source = normalizeSource(sourceId);
    Scan scan = dao.findLatestScanByApplicationAndSource(application.id(), source)
        .orElseThrow(() -> notFound("No scan exists for this application and source"));
    return status(scan, application);
  }

  public ReportView report(String applicationId, String scanId) {
    Application application = resolveApplication(applicationId);
    Scan scan = dao.findScanByScanId(required(scanId, "scanId"))
        .filter(candidate -> candidate.applicationId() == application.id())
        .orElseThrow(() -> notFound("Scan not found"));
    List<ComponentView> components = dao.listScanComponents(scan.id()).stream()
        .map(component -> new ComponentView(
            component.ref(), component.purl(), component.name(), component.version(),
            component.highestSeverity()))
        .toList();
    List<FindingView> findings = dao.listScanFindings(scan.id()).stream()
        .map(finding -> new FindingView(
            finding.componentRef(), finding.vulnerabilityId(), finding.severity(),
            finding.cvssScore(), finding.source(), finding.description(), finding.url()))
        .toList();
    List<ViolationView> violations = violations(scan.id());
    return new ReportView(status(scan, application), components, findings, violations);
  }

  /** Local policy evaluation for a completed scan; used by the worker and by report reads. */
  public List<Violation> evaluatePolicies(long applicationId, long scanId, List<Finding> findings) {
    Application application = dao.findApplication(applicationId).orElseThrow(
        () -> notFound("Application not found"));
    List<Policy> policies = dao.listPolicies(application.organizationId());
    List<Violation> violations = new ArrayList<>();
    for (KkGuardPolicyEvaluator.Match match :
        KkGuardPolicyEvaluator.evaluate(policies, findings)) {
      violations.add(new Violation(
          scanId, match.policyId(), match.componentRef(), match.threatLevel(),
          match.constraint()));
    }
    return violations;
  }

  /** Derives the stored component inventory from normalized findings. */
  public static List<ScanComponent> componentsOf(long scanId, List<Finding> findings) {
    Map<String, ScanComponent> components = new LinkedHashMap<>();
    for (Finding finding : findings) {
      ScanComponent current = components.get(finding.componentRef());
      int candidate = KkGuardPolicyEvaluator.severityRank(finding.severity());
      int existing = current == null
          ? -1 : KkGuardPolicyEvaluator.severityRank(current.highestSeverity());
      if (current == null || candidate > existing) {
        components.put(finding.componentRef(), new ScanComponent(
            scanId, finding.componentRef(), null, null, null, null,
            finding.severity() == null ? null : finding.severity().toUpperCase(Locale.ROOT)));
      }
    }
    return List.copyOf(components.values());
  }

  private List<ViolationView> violations(long scanId) {
    Map<Long, Policy> policies = new LinkedHashMap<>();
    List<ViolationView> views = new ArrayList<>();
    for (Violation violation : dao.listViolations(scanId)) {
      Policy policy = policies.computeIfAbsent(
          violation.policyId(), id -> dao.findPolicy(id).orElse(null));
      views.add(new ViolationView(
          violation.componentRef(),
          policy == null ? Long.toString(violation.policyId()) : policy.externalId(),
          policy == null ? null : policy.name(), violation.threatLevel(),
          violation.constraint()));
    }
    return views;
  }

  private ScanStatusView status(Scan scan, Application application) {
    return new ScanStatusView(
        scan.scanId(), application.externalId(), application.publicId(), scan.sourceId(),
        scan.status(), scan.stage(), scan.componentCount(), scan.violationCount(), scan.error(),
        scan.createdAt(), scan.startedAt(), scan.completedAt());
  }

  private byte[] validateSbom(String contentType, byte[] sbom) {
    if (sbom == null || sbom.length == 0) {
      throw badRequest("A CycloneDX SBOM document is required");
    }
    if (sbom.length > properties.getMaxSbomBytes()) {
      throw new ResponseStatusException(
          HttpStatus.PAYLOAD_TOO_LARGE, "SBOM exceeds the configured size limit");
    }
    if (contentType != null && !contentType.isBlank()
        && !contentType.toLowerCase(Locale.ROOT).contains("json")
        && !contentType.toLowerCase(Locale.ROOT).contains("xml")
        && !contentType.toLowerCase(Locale.ROOT).contains("octet-stream")) {
      throw badRequest("Unsupported SBOM content type: " + contentType);
    }
    try {
      JsonNode root = objectMapper.readTree(sbom);
      String format = root.path("bomFormat").asText("");
      if (!"CycloneDX".equalsIgnoreCase(format)) {
        throw badRequest("The SBOM must be a CycloneDX document");
      }
      if (root.path("components").isMissingNode() && root.path("metadata").isMissingNode()) {
        throw badRequest("The CycloneDX document has no components");
      }
    } catch (IOException e) {
      throw badRequest("The SBOM is not valid JSON");
    }
    return sbom;
  }

  private BlobTarget blobTarget(Application application) {
    Long blobStoreId = application.repositoryId() == null
        ? null
        : repositories.findById(application.repositoryId())
            .map(repository -> repository.blobStoreId())
            .orElse(null);
    BlobStoreRecord store = blobStoreId == null
        ? blobStores.list().stream().findFirst().orElseThrow(
            () -> new IllegalStateException("No blob store is configured for kkGuard scans"))
        : blobStores.findById(blobStoreId).orElseThrow(
            () -> new IllegalStateException("Blob store not found: " + blobStoreId));
    String repositoryName = application.repositoryId() == null
        ? "kkguard"
        : repositories.findById(application.repositoryId())
            .map(repository -> repository.name())
            .orElse("kkguard");
    return new BlobTarget(store.id(), repositoryName, storages.forBlobStoreId(store.id()));
  }

  private Application requireApplication(String applicationId) {
    return dao.findApplicationByExternalId(required(applicationId, "applicationId"))
        .orElseThrow(() -> notFound("Application not found"));
  }

  private Application resolveApplication(String applicationId) {
    String id = required(applicationId, "applicationId");
    return dao.findApplicationByExternalId(id).stream().findFirst()
        .or(() -> dao.findApplicationByPublicId(id))
        .orElseThrow(() -> notFound("Application not found"));
  }

  private static String normalizeSource(String sourceId) {
    if (sourceId == null || sourceId.isBlank()) {
      return "cli";
    }
    String source = sourceId.trim();
    if (source.length() > MAX_SOURCE_ID) {
      throw badRequest("sourceId must be at most " + MAX_SOURCE_ID + " characters");
    }
    return source;
  }

  private static String required(String value, String field) {
    if (value == null || value.isBlank()) {
      throw badRequest(field + " is required");
    }
    return value.trim();
  }

  private static String digest(byte[] document) {
    try {
      return HEX.formatHex(MessageDigest.getInstance("SHA-256").digest(document));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  private static ResponseStatusException notFound(String message) {
    return new ResponseStatusException(HttpStatus.NOT_FOUND, message);
  }

  private static ResponseStatusException badRequest(String message) {
    return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
  }

  private record BlobTarget(long blobStoreId, String repositoryName, BlobStorage storage) {}
}
