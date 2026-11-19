package com.github.klboke.kkrepo.server.kkguard;

import java.util.List;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Nexus IQ-compatible {@code /api/v2} catalogue surface for kkGuard.
 *
 * <p>Status codes and payload shapes follow the IQ clients documented in
 * {@code docs/en/dev/kkguard-design.md}. Authorization is enforced by
 * {@code SecurityManagementFilter} with {@code nexus:lifecycle:read} and
 * {@code nexus:lifecycle:update}.
 */
@RestController
@RequestMapping("/api/v2")
public class KkGuardApiController {
  private final KkGuardCatalogService catalog;
  private final KkGuardScanService scans;
  private final KkGuardProperties properties;

  public KkGuardApiController(
      KkGuardCatalogService catalog, KkGuardScanService scans, KkGuardProperties properties) {
    this.catalog = catalog;
    this.scans = scans;
    this.properties = properties;
  }

  /**
   * IQ list contract: the payload is an envelope object, not a bare array. Clients generated from
   * the IQ spec deserialize {@code ApiOrganizationListDTO}/{@code ApiApplicationListDTO}, so a bare
   * array fails at the first call of every integration.
   */
  public record OrganizationListResponse(
      List<KkGuardCatalogService.OrganizationView> organizations) {}

  public record ApplicationListResponse(
      List<KkGuardCatalogService.ApplicationView> applications) {}

  @GetMapping("/organizations")
  public OrganizationListResponse organizations() {
    ensureEnabled();
    return new OrganizationListResponse(catalog.listOrganizations());
  }

  @PostMapping("/organizations")
  public KkGuardCatalogService.OrganizationView createOrganization(
      @RequestBody(required = false) KkGuardCatalogService.OrganizationInput input) {
    ensureEnabled();
    return catalog.createOrganization(input);
  }

  @GetMapping("/organizations/{id}")
  public KkGuardCatalogService.OrganizationView organization(@PathVariable("id") String id) {
    ensureEnabled();
    return catalog.organization(id);
  }

  @DeleteMapping("/organizations/{id}")
  public ResponseEntity<Void> deleteOrganization(@PathVariable("id") String id) {
    ensureEnabled();
    catalog.deleteOrganization(id);
    return ResponseEntity.noContent().build();
  }

  @GetMapping("/applications")
  public ApplicationListResponse applications() {
    ensureEnabled();
    return new ApplicationListResponse(catalog.listApplications());
  }

  @PostMapping("/applications")
  public KkGuardCatalogService.ApplicationView createApplication(
      @RequestBody(required = false) KkGuardCatalogService.ApplicationInput input) {
    ensureEnabled();
    return catalog.createApplication(input);
  }

  @GetMapping("/applications/organization/{organizationId}")
  public ApplicationListResponse applicationsByOrganization(
      @PathVariable("organizationId") String organizationId) {
    ensureEnabled();
    return new ApplicationListResponse(
        catalog.listApplicationsByOrganization(organizationId));
  }

  @GetMapping("/applications/{id}")
  public KkGuardCatalogService.ApplicationView application(@PathVariable("id") String id) {
    ensureEnabled();
    return catalog.application(id);
  }

  @PutMapping("/applications/{id}")
  public KkGuardCatalogService.ApplicationView updateApplication(
      @PathVariable("id") String id,
      @RequestBody(required = false) KkGuardCatalogService.ApplicationInput input) {
    ensureEnabled();
    // IQ answers 200 with the updated DTO, not 204: clients chain create/update and read back the
    // effective name, tags and organization.
    return catalog.updateApplication(id, input);
  }

  @DeleteMapping("/applications/{id}")
  public ResponseEntity<Void> deleteApplication(@PathVariable("id") String id) {
    ensureEnabled();
    catalog.deleteApplication(id);
    return ResponseEntity.noContent().build();
  }

  @PostMapping(
      value = "/scan/applications/{applicationId}/sources/{sourceId}",
      consumes = {
          MediaType.APPLICATION_JSON_VALUE,
          MediaType.APPLICATION_OCTET_STREAM_VALUE,
          "application/cyclonedx+json",
          "application/vnd.cyclonedx+json"
      })
  public KkGuardScanService.ScanStatusView submitScan(
      @PathVariable("applicationId") String applicationId,
      @PathVariable("sourceId") String sourceId,
      @RequestHeader(value = HttpHeaders.CONTENT_TYPE, required = false) String contentType,
      @RequestBody(required = false) byte[] sbom) {
    ensureEnabled();
    return scans.submit(applicationId, sourceId, contentType, sbom);
  }

  @GetMapping("/scan/applications/{applicationId}/sources/{sourceId}/status")
  public KkGuardScanService.ScanStatusView scanStatus(
      @PathVariable("applicationId") String applicationId,
      @PathVariable("sourceId") String sourceId) {
    ensureEnabled();
    return scans.status(applicationId, sourceId);
  }

  @GetMapping("/applications/{applicationId}/reports/{scanId}")
  public KkGuardScanService.ReportView report(
      @PathVariable("applicationId") String applicationId,
      @PathVariable("scanId") String scanId) {
    ensureEnabled();
    return scans.report(applicationId, scanId);
  }

  /**
   * {@code kkrepo.kkg.enabled=false} disables the product surface, not only the background worker.
   * Without this the REST API would keep accepting scans on a replica that is meant to be dark.
   */
  private void ensureEnabled() {
    if (!properties.isEnabled()) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "kkGuard is disabled");
    }
  }
}
