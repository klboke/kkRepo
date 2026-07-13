package com.github.klboke.kkrepo.server.security;

import com.github.klboke.kkrepo.auth.PermissionSubject;
import com.github.klboke.kkrepo.persistence.jdbc.api.SecurityDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.SecurityAnonymousConfigRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.SecurityPrivilegeRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.SecurityRepositoryTargetRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.SecurityRoleRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.SecurityUserRecord;
import com.github.klboke.kkrepo.server.catalog.CatalogCacheBroadcaster;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
public class SecurityCatalogCache {
  private static final Logger log = LoggerFactory.getLogger(SecurityCatalogCache.class);
  private static final String CATALOG_NAME = "security";
  private static final String LOCAL_SOURCE = "Local";
  private static final String DEFAULT_ANONYMOUS_USER_ID = "anonymous";
  private static final Object REFRESH_RESOURCE_KEY =
      SecurityCatalogCache.class.getName() + ".REFRESH";

  private final SecurityDao securityDao;
  private final boolean enabled;
  private final CatalogCacheBroadcaster broadcaster;
  private final AtomicReference<SecurityCatalog> catalog = new AtomicReference<>();
  private final AtomicBoolean subscribed = new AtomicBoolean();
  private final ReentrantLock refreshLock = new ReentrantLock();

  @Autowired
  public SecurityCatalogCache(
      SecurityDao securityDao,
      @Value("${kkrepo.catalog-cache.enabled:true}") boolean enabled,
      ObjectProvider<CatalogCacheBroadcaster> broadcasterProvider) {
    this(securityDao, enabled, broadcasterProvider == null ? null : broadcasterProvider.getIfAvailable());
  }

  SecurityCatalogCache(
      SecurityDao securityDao,
      boolean enabled,
      CatalogCacheBroadcaster broadcaster) {
    this.securityDao = securityDao;
    this.enabled = enabled;
    this.broadcaster = broadcaster;
  }

  SecurityCatalogCache(SecurityDao securityDao, boolean enabled) {
    this(securityDao, enabled, (CatalogCacheBroadcaster) null);
  }

  public Optional<SecurityCatalog> current() {
    if (!enabled) {
      return Optional.empty();
    }
    SecurityCatalog local = catalog.get();
    if (local != null) {
      return Optional.of(local);
    }
    return Optional.ofNullable(refreshBlocking());
  }

  @EventListener(ApplicationReadyEvent.class)
  public void warmUp() {
    if (!enabled) {
      return;
    }
    subscribeToBroadcast();
    refreshSafely("startup");
  }

  @Scheduled(
      fixedDelayString = "${kkrepo.catalog-cache.refresh-interval-ms:60000}",
      initialDelayString = "${kkrepo.catalog-cache.initial-delay-ms:60000}")
  public void syncDatabaseToMemory() {
    if (!enabled) {
      return;
    }
    if (!refreshLock.tryLock()) {
      return;
    }
    try {
      refreshLocked("scheduled");
    } catch (RuntimeException e) {
      log.warn("Failed refreshing security catalog from MySQL; keeping previous in-memory snapshot", e);
    } finally {
      refreshLock.unlock();
    }
  }

  public void refreshAfterCommit() {
    if (!enabled) {
      return;
    }
    if (!TransactionSynchronizationManager.isSynchronizationActive()) {
      refreshSafely("mutation");
      publishRefresh();
      return;
    }
    if (TransactionSynchronizationManager.hasResource(REFRESH_RESOURCE_KEY)) {
      return;
    }
    TransactionSynchronizationManager.bindResource(REFRESH_RESOURCE_KEY, Boolean.TRUE);
    TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
      @Override
      public void afterCommit() {
        refreshSafely("mutation");
        publishRefresh();
      }

      @Override
      public void afterCompletion(int status) {
        TransactionSynchronizationManager.unbindResourceIfPossible(REFRESH_RESOURCE_KEY);
      }
    });
  }

  private void subscribeToBroadcast() {
    if (broadcaster == null || !subscribed.compareAndSet(false, true)) {
      return;
    }
    broadcaster.subscribe(CATALOG_NAME, this::refreshFromBroadcast);
  }

  private void refreshFromBroadcast() {
    refreshLock.lock();
    try {
      refreshLocked("catalog-broadcast");
    } finally {
      refreshLock.unlock();
    }
  }

  private void publishRefresh() {
    if (broadcaster == null) {
      return;
    }
    broadcaster.publishRefresh(CATALOG_NAME);
  }

  private SecurityCatalog refreshBlocking() {
    refreshLock.lock();
    try {
      return refreshLocked("lazy");
    } catch (RuntimeException e) {
      log.warn("Failed loading security catalog from MySQL; falling back to direct authorization queries", e);
      return null;
    } finally {
      refreshLock.unlock();
    }
  }

  private void refreshSafely(String reason) {
    refreshLock.lock();
    try {
      refreshLocked(reason);
    } catch (RuntimeException e) {
      log.warn("Failed refreshing security catalog from MySQL; keeping previous in-memory snapshot", e);
    } finally {
      refreshLock.unlock();
    }
  }

  private SecurityCatalog refreshLocked(String reason) {
    SecurityCatalog loaded = loadCatalog();
    catalog.set(loaded);
    log.debug(
        "Refreshed security catalog from MySQL by {}: users={}, roles={}, privileges={}, repositoryTargets={}",
        reason,
        loaded.userCount(),
        loaded.roleCount(),
        loaded.privilegeCount(),
        loaded.repositoryTargetCount());
    return loaded;
  }

  private SecurityCatalog loadCatalog() {
    SecurityAnonymousConfigRecord anonymousConfig = securityDao.findAnonymousConfig().orElse(null);
    String anonymousUserId = anonymousConfig == null
        ? null
        : defaultString(anonymousConfig.userId(), DEFAULT_ANONYMOUS_USER_ID);
    SecurityUserRecord anonymousUser = null;
    Map<String, List<String>> userRoleIds = new LinkedHashMap<>();
    List<SecurityUserRecord> users = securityDao.listUsers();
    for (SecurityUserRecord user : users) {
      if (user.id() == null) {
        continue;
      }
      userRoleIds.put(subjectKey(user.source(), user.userId()), securityDao.listUserRoleIds(user.id()));
      if (anonymousUserId != null
          && LOCAL_SOURCE.equals(user.source())
          && anonymousUserId.equals(user.userId())) {
        anonymousUser = user;
      }
    }

    Map<String, List<String>> roleChildIds = new LinkedHashMap<>();
    Map<String, List<String>> rolePrivilegeIds = new LinkedHashMap<>();
    List<SecurityRoleRecord> roles = securityDao.listRoles();
    for (SecurityRoleRecord role : roles) {
      roleChildIds.put(role.roleId(), securityDao.listRoleChildIds(role.roleId()));
      rolePrivilegeIds.put(role.roleId(), securityDao.listRolePrivilegeIds(role.roleId()));
    }

    Map<String, SecurityPrivilegeRecord> privilegesById = new LinkedHashMap<>();
    List<SecurityPrivilegeRecord> privileges = securityDao.listPrivileges();
    privileges.stream()
        .filter(privilege -> privilege != null && privilege.privilegeId() != null)
        .sorted(Comparator.comparing(SecurityPrivilegeRecord::privilegeId))
        .forEach(privilege -> privilegesById.put(privilege.privilegeId(), privilege));

    Map<String, SecurityRepositoryTargetRecord> repositoryTargets = new LinkedHashMap<>();
    securityDao.listRepositoryTargets().stream()
        .filter(target -> target != null && target.targetId() != null)
        .sorted(Comparator.comparing(SecurityRepositoryTargetRecord::targetId))
        .forEach(target -> repositoryTargets.put(target.targetId(), target));

    return new SecurityCatalog(
        Instant.now(),
        users.size(),
        roles.size(),
        anonymousConfig,
        anonymousUser,
        immutableStringListMap(userRoleIds),
        immutableStringListMap(roleChildIds),
        immutableStringListMap(rolePrivilegeIds),
        Collections.unmodifiableMap(privilegesById),
        List.copyOf(privilegesById.values()),
        Collections.unmodifiableMap(repositoryTargets));
  }

  private static Map<String, List<String>> immutableStringListMap(Map<String, List<String>> values) {
    Map<String, List<String>> copy = new LinkedHashMap<>();
    values.forEach((key, value) -> copy.put(key, normalizedStringList(value)));
    return Collections.unmodifiableMap(copy);
  }

  private static List<String> normalizedStringList(Collection<String> values) {
    if (values == null || values.isEmpty()) {
      return List.of();
    }
    return values.stream()
        .filter(value -> value != null && !value.isBlank())
        .distinct()
        .sorted()
        .toList();
  }

  private static String subjectKey(String source, String userId) {
    return SecurityManagementService.normalizeSource(source) + "\u0000" + defaultString(userId, "");
  }

  private static String defaultString(String value, String fallback) {
    return value == null || value.isBlank() ? fallback : value.trim();
  }

  public record SecurityCatalog(
      Instant loadedAt,
      int userCount,
      int roleCount,
      SecurityAnonymousConfigRecord anonymousConfig,
      SecurityUserRecord anonymousUser,
      Map<String, List<String>> userRoleIds,
      Map<String, List<String>> roleChildIds,
      Map<String, List<String>> rolePrivilegeIds,
      Map<String, SecurityPrivilegeRecord> privilegesById,
      List<SecurityPrivilegeRecord> privileges,
      Map<String, SecurityRepositoryTargetRecord> repositoryTargets) {

    public int privilegeCount() {
      return privileges.size();
    }

    public int repositoryTargetCount() {
      return repositoryTargets.size();
    }

    public List<String> userRoleIds(String source, String userId) {
      return userRoleIds.getOrDefault(subjectKey(source, userId), List.of());
    }

    public Set<String> effectiveRoleIds(PermissionSubject subject) {
      if (subject == null) {
        return Set.of();
      }
      LinkedHashSet<String> roles = new LinkedHashSet<>();
      if (subject.groupIds() != null) {
        roles.addAll(normalizedStringList(subject.groupIds()));
      }
      if (subject.userId() != null && !subject.userId().isBlank()) {
        roles.addAll(userRoleIds.getOrDefault(subjectKey(subject.source(), subject.userId()), List.of()));
      }

      ArrayDeque<String> queue = new ArrayDeque<>(roles);
      while (!queue.isEmpty()) {
        String roleId = queue.removeFirst();
        for (String childRoleId : roleChildIds.getOrDefault(roleId, List.of())) {
          if (roles.add(childRoleId)) {
            queue.addLast(childRoleId);
          }
        }
      }
      return Collections.unmodifiableSet(roles);
    }

    public List<SecurityPrivilegeRecord> privilegesForRoles(Collection<String> roleIds) {
      if (roleIds == null || roleIds.isEmpty()) {
        return List.of();
      }
      LinkedHashSet<String> privilegeIds = new LinkedHashSet<>();
      for (String roleId : roleIds) {
        privilegeIds.addAll(rolePrivilegeIds.getOrDefault(roleId, List.of()));
      }
      if (privilegeIds.isEmpty()) {
        return List.of();
      }
      List<SecurityPrivilegeRecord> result = new ArrayList<>();
      for (String privilegeId : privilegeIds) {
        SecurityPrivilegeRecord privilege = privilegesById.get(privilegeId);
        if (privilege != null) {
          result.add(privilege);
        }
      }
      result.sort(Comparator.comparing(SecurityPrivilegeRecord::privilegeId));
      return List.copyOf(result);
    }
  }
}
