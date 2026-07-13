package com.github.klboke.kkrepo.server.security;

import com.fasterxml.jackson.core.type.TypeReference;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.klboke.kkrepo.auth.PermissionSubject;
import com.github.klboke.kkrepo.cache.SharedCache;
import com.github.klboke.kkrepo.server.cache.VersionWatermark;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.SecurityPrivilegeRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.SecurityRepositoryTargetRecord;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
public class SecurityAuthorizationCache {
  private static final Logger log = LoggerFactory.getLogger(SecurityAuthorizationCache.class);
  private static final String VERSION_NAME = "authz:global";
  private static final String SNAPSHOT_NAMESPACE = "security-authorization-snapshot";
  private static final Object INVALIDATION_RESOURCE_KEY =
      SecurityAuthorizationCache.class.getName() + ".INVALIDATION";
  private static final TypeReference<AuthorizationSnapshot> SNAPSHOT_TYPE = new TypeReference<>() {
  };

  private final SharedCache sharedCache;
  private final VersionWatermark watermark;
  private final boolean enabled;
  private final Duration snapshotTtl;
  private final Cache<String, String> versionCache;

  @Autowired
  public SecurityAuthorizationCache(
      SharedCache sharedCache,
      VersionWatermark watermark,
      @Value("${kkrepo.security.authorization-cache.enabled:true}") boolean enabled,
      @Value("${kkrepo.security.authorization-cache.ttl-minutes:10}") long ttlMinutes,
      @Value("${kkrepo.security.authorization-cache.version-local-ttl-seconds:2}") long versionLocalTtlSeconds) {
    this.sharedCache = sharedCache;
    this.watermark = watermark;
    this.enabled = enabled;
    this.snapshotTtl = ttlMinutes <= 0 ? Duration.ZERO : Duration.ofMinutes(ttlMinutes);
    this.versionCache = Caffeine.newBuilder()
        .expireAfterWrite(Math.max(1, versionLocalTtlSeconds), TimeUnit.SECONDS)
        .maximumSize(1)
        .build();
  }

  public SecurityAuthorizationCache(
      SharedCache sharedCache,
      VersionWatermark watermark,
      boolean enabled,
      long ttlMinutes) {
    this(sharedCache, watermark, enabled, ttlMinutes, 2);
  }

  public AuthorizationSnapshot getOrLoad(PermissionSubject subject, Supplier<AuthorizationSnapshot> loader) {
    if (!enabled) {
      return loader.get();
    }
    String key;
    try {
      key = currentVersion() + ":" + subjectKey(subject);
      AuthorizationSnapshot cached = sharedCache.getJson(SNAPSHOT_NAMESPACE, key, SNAPSHOT_TYPE).orElse(null);
      if (cached != null) {
        return cached.normalized();
      }
    } catch (RuntimeException e) {
      log.warn("Failed reading authorization cache, falling back to MySQL", e);
      return loader.get();
    }

    AuthorizationSnapshot loaded = loader.get().normalized();
    try {
      sharedCache.putJson(SNAPSHOT_NAMESPACE, key, loaded, snapshotTtl);
    } catch (RuntimeException e) {
      log.warn("Failed writing authorization cache", e);
    }
    return loaded;
  }

  public void invalidateAllAfterCommit() {
    if (!enabled) {
      return;
    }
    if (!TransactionSynchronizationManager.isSynchronizationActive()) {
      invalidateAll();
      return;
    }
    if (TransactionSynchronizationManager.hasResource(INVALIDATION_RESOURCE_KEY)) {
      return;
    }
    TransactionSynchronizationManager.bindResource(INVALIDATION_RESOURCE_KEY, Boolean.TRUE);
    TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
      @Override
      public void afterCommit() {
        invalidateAll();
      }

      @Override
      public void afterCompletion(int status) {
        TransactionSynchronizationManager.unbindResourceIfPossible(INVALIDATION_RESOURCE_KEY);
      }
    });
  }

  public void invalidateAll() {
    if (!enabled) {
      return;
    }
    try {
      watermark.bump(VERSION_NAME);
      versionCache.invalidate(VERSION_NAME);
    } catch (RuntimeException e) {
      log.warn("Failed invalidating authorization cache", e);
    }
  }

  private String currentVersion() {
    try {
      return versionCache.get(VERSION_NAME, ignored -> loadVersion());
    } catch (RuntimeException e) {
      log.warn("Failed reading local authorization cache version", e);
      return "0";
    }
  }

  private String loadVersion() {
    return Long.toString(watermark.current(VERSION_NAME));
  }

  private static String subjectKey(PermissionSubject subject) {
    String source = subject == null ? "" : defaultString(subject.source(), "");
    String userId = subject == null ? "" : defaultString(subject.userId(), "");
    List<String> roleIds = new ArrayList<>();
    if (subject != null && subject.groupIds() != null) {
      roleIds.addAll(subject.groupIds().stream()
          .filter(roleId -> roleId != null && !roleId.isBlank())
          .sorted()
          .toList());
    }
    return SecurityHashing.sha256(source + "\u0000" + userId + "\u0000" + String.join("\u0000", roleIds));
  }

  private static String defaultString(String value, String fallback) {
    return value == null || value.isBlank() ? fallback : value;
  }

  public record AuthorizationSnapshot(
      Set<String> roleIds,
      List<SecurityPrivilegeRecord> privileges,
      Map<String, SecurityRepositoryTargetRecord> repositoryTargets) {

    AuthorizationSnapshot normalized() {
      Set<String> normalizedRoleIds = roleIds == null
          ? Set.of()
          : new LinkedHashSet<>(roleIds.stream()
              .filter(roleId -> roleId != null && !roleId.isBlank())
              .sorted()
              .toList());
      List<SecurityPrivilegeRecord> normalizedPrivileges = privileges == null
          ? List.of()
          : privileges.stream()
              .filter(privilege -> privilege != null && privilege.privilegeId() != null)
              .sorted(Comparator.comparing(SecurityPrivilegeRecord::privilegeId))
              .toList();
      Map<String, SecurityRepositoryTargetRecord> normalizedTargets = new LinkedHashMap<>();
      if (repositoryTargets != null) {
        repositoryTargets.entrySet().stream()
            .filter(entry -> entry.getKey() != null && entry.getValue() != null)
            .sorted(Map.Entry.comparingByKey())
            .forEach(entry -> normalizedTargets.put(entry.getKey(), entry.getValue()));
      }
      return new AuthorizationSnapshot(normalizedRoleIds, normalizedPrivileges, normalizedTargets);
    }
  }
}
