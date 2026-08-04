package com.github.klboke.kkrepo.server.cleanup;

import com.github.klboke.kkrepo.persistence.jdbc.api.CleanupPolicyDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.CleanupPolicyDao.CleanupProtection;
import com.github.klboke.kkrepo.persistence.jdbc.api.PersistenceHashes;
import com.github.klboke.kkrepo.persistence.jdbc.api.RepositoryDao;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Product API for durable cross-policy holds. */
@Service
public class CleanupProtectionService {
  private static final Set<String> SCOPES = Set.of("GLOBAL", "REPOSITORY", "SUBJECT");
  private static final Set<String> SOURCES =
      Set.of("MANUAL", "EXTERNAL", "LEGAL_HOLD", "SECURITY_HOLD", "SYSTEM");

  private final CleanupPolicyDao cleanupDao;
  private final RepositoryDao repositoryDao;
  private final Clock clock;

  @Autowired
  public CleanupProtectionService(CleanupPolicyDao cleanupDao, RepositoryDao repositoryDao) {
    this(cleanupDao, repositoryDao, Clock.systemUTC());
  }

  CleanupProtectionService(
      CleanupPolicyDao cleanupDao, RepositoryDao repositoryDao, Clock clock) {
    this.cleanupDao = cleanupDao;
    this.repositoryDao = repositoryDao;
    this.clock = clock;
  }

  public List<ProtectionView> list(long afterId, int limit, boolean activeOnly) {
    Instant activeAt = activeOnly ? databaseNow() : null;
    return cleanupDao.listProtections(
            Math.max(0, afterId), Math.min(Math.max(1, limit), 200), activeAt)
        .stream().map(ProtectionView::from).toList();
  }

  public ProtectionView get(long protectionId) {
    return ProtectionView.from(require(protectionId));
  }

  @Transactional
  public ProtectionView create(ProtectionCommand command, String actorId) {
    Instant now = databaseNow();
    CleanupProtection protection = validate(null, command, actorId, now, now);
    return get(cleanupDao.createProtection(protection));
  }

  @Transactional
  public ProtectionView update(long protectionId, ProtectionCommand command, String actorId) {
    CleanupProtection existing = require(protectionId);
    if (command == null || command.expectedUpdatedAt() == null) {
      throw new CleanupValidationException("expectedUpdatedAt is required");
    }
    Instant updatedAt = databaseNow();
    if (!updatedAt.isAfter(existing.updatedAt())) {
      updatedAt = existing.updatedAt().plusMillis(1);
    }
    CleanupProtection updated = validate(
        protectionId, command, existing.createdBy(), existing.createdAt(), updatedAt);
    if (!cleanupDao.updateProtection(updated, command.expectedUpdatedAt())) {
      CleanupProtection current = require(protectionId);
      throw new CleanupProtectionConflictException(protectionId, current.updatedAt());
    }
    return get(protectionId);
  }

  @Transactional
  public void delete(long protectionId, Instant expectedUpdatedAt) {
    require(protectionId);
    if (expectedUpdatedAt == null) {
      throw new CleanupValidationException("updatedAt is required");
    }
    if (!cleanupDao.deleteProtection(protectionId, expectedUpdatedAt)) {
      CleanupProtection current = require(protectionId);
      throw new CleanupProtectionConflictException(protectionId, current.updatedAt());
    }
  }

  private CleanupProtection validate(
      Long id,
      ProtectionCommand command,
      String createdBy,
      Instant createdAt,
      Instant now) {
    if (command == null) throw new CleanupValidationException("request body is required");
    String scope = enumValue(command.scope(), "scope", SCOPES);
    String source = enumValue(command.source(), "source", SOURCES);
    String reason = required(command.reason(), "reason", 1_024);
    String subjectKind = optional(command.subjectKind(), 32);
    String subjectKey = optional(command.subjectKey(), 2_048);
    String externalId = optional(command.externalId(), 255);
    Long repositoryId = command.repositoryId();

    switch (scope) {
      case "GLOBAL" -> {
        if (repositoryId != null || subjectKind != null || subjectKey != null) {
          throw new CleanupValidationException(
              "GLOBAL protection cannot specify repository or subject");
        }
      }
      case "REPOSITORY" -> {
        requireRepository(repositoryId);
        if (subjectKind != null || subjectKey != null) {
          throw new CleanupValidationException(
              "REPOSITORY protection cannot specify a subject");
        }
      }
      case "SUBJECT" -> {
        requireRepository(repositoryId);
        if (subjectKind == null || subjectKey == null) {
          throw new CleanupValidationException(
              "SUBJECT protection requires subjectKind and subjectKey");
        }
      }
      default -> throw new IllegalStateException("unreachable protection scope");
    }
    if ("EXTERNAL".equals(source)) {
      if (externalId == null || command.freshUntil() == null) {
        throw new CleanupValidationException(
            "EXTERNAL protection requires externalId and freshUntil");
      }
      if (!command.freshUntil().isAfter(now)) {
        throw new CleanupValidationException("freshUntil must be in the future");
      }
    } else if (externalId != null || command.freshUntil() != null) {
      throw new CleanupValidationException(
          "externalId and freshUntil are only valid for EXTERNAL protection");
    }
    if (command.expiresAt() != null && !command.expiresAt().isAfter(now)) {
      throw new CleanupValidationException("expiresAt must be in the future");
    }
    boolean enabled = command.enabled() == null || command.enabled();
    return new CleanupProtection(
        id,
        scope,
        repositoryId,
        subjectKind,
        subjectKey,
        subjectKey == null ? null : PersistenceHashes.sha256(subjectKey),
        source,
        externalId,
        reason,
        enabled,
        command.expiresAt(),
        command.freshUntil(),
        required(createdBy, "actor", 255),
        createdAt,
        now);
  }

  private void requireRepository(Long repositoryId) {
    if (repositoryId == null || repositoryId <= 0) {
      throw new CleanupValidationException("repositoryId is required");
    }
    if (repositoryDao.findById(repositoryId).isEmpty()) {
      throw new CleanupValidationException("target repository does not exist: " + repositoryId);
    }
  }

  private CleanupProtection require(long protectionId) {
    return cleanupDao.findProtection(protectionId)
        .orElseThrow(() -> new CleanupNotFoundException("cleanup protection", protectionId));
  }

  private Instant databaseNow() {
    Instant value = cleanupDao.currentTime();
    return value == null ? clock.instant() : value;
  }

  private static String enumValue(String value, String field, Set<String> allowed) {
    String normalized = required(value, field, 32).toUpperCase(Locale.ROOT);
    if (!allowed.contains(normalized)) {
      throw new CleanupValidationException(field + " must be one of " + allowed);
    }
    return normalized;
  }

  private static String required(String value, String field, int maxLength) {
    String result = optional(value, maxLength);
    if (result == null) throw new CleanupValidationException(field + " is required");
    return result;
  }

  private static String optional(String value, int maxLength) {
    if (value == null) return null;
    String result = value.trim();
    if (result.isEmpty()) return null;
    if (result.length() > maxLength) {
      throw new CleanupValidationException("value exceeds maximum length " + maxLength);
    }
    return result;
  }

  public record ProtectionCommand(
      String scope,
      Long repositoryId,
      String subjectKind,
      String subjectKey,
      String source,
      String externalId,
      String reason,
      Boolean enabled,
      Instant expiresAt,
      Instant freshUntil,
      Instant expectedUpdatedAt) {
  }

  public record ProtectionView(
      Long id,
      String scope,
      Long repositoryId,
      String subjectKind,
      String subjectKey,
      String source,
      String externalId,
      String reason,
      boolean enabled,
      Instant expiresAt,
      Instant freshUntil,
      String createdBy,
      Instant createdAt,
      Instant updatedAt) {
    static ProtectionView from(CleanupProtection protection) {
      return new ProtectionView(
          protection.id(),
          protection.scope(),
          protection.repositoryId(),
          protection.subjectKind(),
          protection.subjectKey(),
          protection.source(),
          protection.externalId(),
          protection.reason(),
          protection.enabled(),
          protection.expiresAt(),
          protection.freshnessAt(),
          protection.createdBy(),
          protection.createdAt(),
          protection.updatedAt());
    }
  }
}
