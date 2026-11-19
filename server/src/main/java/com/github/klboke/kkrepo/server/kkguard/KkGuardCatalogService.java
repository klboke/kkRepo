package com.github.klboke.kkrepo.server.kkguard;

import com.github.klboke.kkrepo.persistence.jdbc.api.KkGuardDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.KkGuardDao.Application;
import com.github.klboke.kkrepo.persistence.jdbc.api.KkGuardDao.ApplicationTag;
import com.github.klboke.kkrepo.persistence.jdbc.api.KkGuardDao.Organization;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Organization and application catalogue for the Nexus IQ-compatible kkGuard surface.
 *
 * <p>Public identifiers are UUIDs so existing IQ clients keep working; the numeric database keys
 * never leave the service. Deleting an organization cascades to its applications and scans in the
 * database; a Dependency-Track project is never deleted implicitly.
 */
@Service
public class KkGuardCatalogService {
  static final int MAX_NAME = 255;
  static final int MAX_PUBLIC_ID = 200;
  static final int MAX_CONTACT = 255;
  static final int MAX_DESCRIPTION = 1024;
  static final int MAX_TAG_NAME = 200;

  private final KkGuardDao dao;
  private final KkGuardProperties properties;

  public KkGuardCatalogService(KkGuardDao dao, KkGuardProperties properties) {
    this.dao = dao;
    this.properties = properties;
  }

  public record OrganizationView(String id, String name, String description) {}

  public record TagView(String id, String name, String description) {}

  public record ApplicationView(
      String id, String publicId, String name, String organizationId, String contactUserName,
      List<TagView> applicationTags) {}

  public record OrganizationInput(String name, String description) {}

  public record ApplicationInput(
      String publicId, String name, String organizationId, String contactUserName,
      List<TagView> applicationTags) {}

  public List<OrganizationView> listOrganizations() {
    return dao.listOrganizations().stream().map(KkGuardCatalogService::organization).toList();
  }

  public OrganizationView organization(String id) {
    return organization(requireOrganization(id));
  }

  @Transactional
  public OrganizationView createOrganization(OrganizationInput input) {
    String name = required(input == null ? null : input.name(), "name", MAX_NAME);
    String description = optional(input.description(), MAX_DESCRIPTION);
    Instant now = Instant.now();
    try {
      long id = dao.insertOrganization(
          UUID.randomUUID().toString(), name, description, now);
      return organization(dao.findOrganization(id).orElseThrow());
    } catch (DataIntegrityViolationException e) {
      throw conflict("An organization named '" + name + "' already exists");
    }
  }

  @Transactional
  public void deleteOrganization(String id) {
    Organization organization = requireOrganization(id);
    dao.deleteOrganization(organization.id());
  }

  public List<ApplicationView> listApplications() {
    return dao.listApplications().stream().map(this::application).toList();
  }

  public List<ApplicationView> listApplicationsByOrganization(String organizationId) {
    Organization organization = requireOrganization(organizationId);
    return dao.listApplicationsByOrganization(organization.id()).stream()
        .map(this::application).toList();
  }

  public ApplicationView application(String id) {
    return application(requireApplication(id));
  }

  @Transactional
  public ApplicationView createApplication(ApplicationInput input) {
    Validated validated = validate(input);
    Organization organization = requireOrganization(input.organizationId());
    Instant now = Instant.now();
    long id;
    try {
      id = dao.insertApplication(
          organization.id(), UUID.randomUUID().toString(), validated.publicId(),
          validated.name(), null, validated.contactUserName(), now);
    } catch (DataIntegrityViolationException e) {
      throw conflict(
          "An application with publicId '" + validated.publicId()
              + "' already exists in this organization");
    }
    dao.replaceApplicationTags(id, withOwner(id, validated.tags()));
    return application(dao.findApplication(id).orElseThrow());
  }

  @Transactional
  public ApplicationView updateApplication(String id, ApplicationInput input) {
    Application current = requireApplication(id);
    Validated validated = validate(input);
    Organization organization = requireOrganization(input.organizationId());
    try {
      dao.updateApplication(
          current.id(), organization.id(), current.externalId(), validated.publicId(),
          validated.name(), current.repositoryId(), validated.contactUserName(), Instant.now());
    } catch (DataIntegrityViolationException e) {
      throw conflict(
          "An application with publicId '" + validated.publicId()
              + "' already exists in this organization");
    }
    dao.replaceApplicationTags(current.id(), withOwner(current.id(), validated.tags()));
    return application(dao.findApplication(current.id()).orElseThrow());
  }

  @Transactional
  public void deleteApplication(String id) {
    Application application = requireApplication(id);
    dao.deleteApplication(application.id());
  }

  /** True when the Dependency-Track backend is configured for scan submission. */
  public boolean dependencyTrackConfigured() {
    return properties.getDependencyTrack().isConfigured();
  }

  private ApplicationView application(Application application) {
    Organization organization = dao.findOrganization(application.organizationId())
        .orElseThrow(() -> notFound("Organization not found"));
    List<TagView> tags = new ArrayList<>();
    for (ApplicationTag tag : dao.listApplicationTags(application.id())) {
      tags.add(new TagView(tagId(application.externalId(), tag.name()), tag.name(),
          tag.description()));
    }
    return new ApplicationView(
        application.externalId(), application.publicId(), application.name(),
        organization.externalId(), application.contactUserName(), tags);
  }

  private static OrganizationView organization(Organization organization) {
    return new OrganizationView(
        organization.externalId(), organization.name(), organization.description());
  }

  private Validated validate(ApplicationInput input) {
    if (input == null) {
      throw badRequest("An application payload is required");
    }
    String publicId = required(input.publicId(), "publicId", MAX_PUBLIC_ID);
    if (publicId.chars().anyMatch(Character::isWhitespace)) {
      throw badRequest("publicId must not contain whitespace");
    }
    String name = required(input.name(), "name", MAX_NAME);
    required(input.organizationId(), "organizationId", 36);
    String contactUserName = optional(input.contactUserName(), MAX_CONTACT);
    List<ApplicationTag> tags = new ArrayList<>();
    if (input.applicationTags() != null) {
      List<String> seen = new ArrayList<>();
      for (TagView tag : input.applicationTags()) {
        if (tag == null) {
          continue;
        }
        String tagName = required(tag.name(), "applicationTags[].name", MAX_TAG_NAME);
        String normalized = tagName.toLowerCase(Locale.ROOT);
        if (seen.contains(normalized)) {
          throw badRequest("Duplicate application tag '" + tagName + "'");
        }
        seen.add(normalized);
        tags.add(new ApplicationTag(0L, tagName, optional(tag.description(), MAX_DESCRIPTION)));
      }
    }
    return new Validated(publicId, name, contactUserName, tags);
  }

  private Organization requireOrganization(String id) {
    String uuid = required(id, "id", 36);
    return dao.findOrganizationByExternalId(uuid)
        .orElseThrow(() -> notFound("Organization not found"));
  }

  private Application requireApplication(String id) {
    String uuid = required(id, "id", 36);
    Optional<Application> application = dao.findApplicationByExternalId(uuid);
    return application.orElseThrow(() -> notFound("Application not found"));
  }

  private static List<ApplicationTag> withOwner(long applicationId, List<ApplicationTag> tags) {
    return tags.stream()
        .map(tag -> new ApplicationTag(applicationId, tag.name(), tag.description()))
        .toList();
  }

  /** Stable tag identifier derived from the owning application and the tag name. */
  private static String tagId(String applicationExternalId, String tagName) {
    String seed = applicationExternalId + ":" + tagName.toLowerCase(Locale.ROOT);
    return UUID.nameUUIDFromBytes(seed.getBytes(StandardCharsets.UTF_8)).toString();
  }

  private static String required(String value, String field, int max) {
    if (value == null || value.isBlank()) {
      throw badRequest(field + " is required");
    }
    String trimmed = value.trim();
    if (trimmed.length() > max) {
      throw badRequest(field + " must be at most " + max + " characters");
    }
    return trimmed;
  }

  private static String optional(String value, int max) {
    if (value == null || value.isBlank()) {
      return null;
    }
    String trimmed = value.trim();
    if (trimmed.length() > max) {
      throw badRequest("value must be at most " + max + " characters");
    }
    return trimmed;
  }

  private static ResponseStatusException notFound(String message) {
    return new ResponseStatusException(HttpStatus.NOT_FOUND, message);
  }

  private static ResponseStatusException conflict(String message) {
    return new ResponseStatusException(HttpStatus.CONFLICT, message);
  }

  private static ResponseStatusException badRequest(String message) {
    return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
  }

  private record Validated(
      String publicId, String name, String contactUserName, List<ApplicationTag> tags) {}
}
