package com.github.klboke.kkrepo.migration.nexus.security;

import com.github.klboke.kkrepo.persistence.jdbc.api.SecurityDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.ApiKeyRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.SecurityAnonymousConfigRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.SecurityPrivilegeRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.SecurityRealmRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.SecurityRepositoryTargetRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.SecurityRoleRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.SecurityUserRecord;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class SecurityDaoMigrationWriter implements NexusSecurityMigrationWriter {
  private static final String STATUS_ACTIVE = "active";

  private final SecurityDao securityDao;

  public SecurityDaoMigrationWriter(SecurityDao securityDao) {
    this.securityDao = securityDao;
  }

  @Override
  public void upsertRepositoryTarget(SecurityRepositoryTargetRecord record) {
    securityDao.upsertRepositoryTarget(record);
  }

  @Override
  public void upsertPrivilege(SecurityPrivilegeRecord record) {
    securityDao.upsertPrivilege(record);
  }

  @Override
  public void upsertRole(SecurityRoleRecord record) {
    securityDao.upsertRole(record);
  }

  @Override
  public void replaceRolePrivileges(String roleId, List<String> privilegeIds) {
    securityDao.replaceRolePrivileges(roleId, privilegeIds);
  }

  @Override
  public void replaceRoleInheritance(String roleId, List<String> childRoleIds) {
    securityDao.replaceRoleInheritance(roleId, childRoleIds);
  }

  @Override
  public void upsertUser(SecurityUserRecord record) {
    securityDao.findUser(record.source(), record.userId()).ifPresentOrElse(
        existing -> securityDao.updateUser(new SecurityUserRecord(
            existing.id(),
            record.source(),
            record.userId(),
            valueOrExisting(record.firstName(), existing.firstName()),
            valueOrExisting(record.lastName(), existing.lastName()),
            valueOrExisting(record.email(), existing.email()),
            valueOrExisting(record.passwordHash(), existing.passwordHash()),
            valueOrExisting(record.status(), existing.status()),
            valueOrExisting(record.externalId(), existing.externalId()),
            mergeAttributes(existing.attributes(), record.attributes()))),
        () -> securityDao.insertUser(record));
  }

  @Override
  public void replaceUserRoles(String source, String userId, List<String> roleIds) {
    SecurityUserRecord user = securityDao.findUser(source, userId).orElseGet(() -> {
      SecurityUserRecord placeholder = new SecurityUserRecord(
          null,
          source,
          userId,
          null,
          null,
          null,
          null,
          STATUS_ACTIVE,
          null,
          Map.of("source", "nexus-user-role-mapping-placeholder"));
      long id = securityDao.insertUser(placeholder);
      return new SecurityUserRecord(
          id,
          placeholder.source(),
          placeholder.userId(),
          placeholder.firstName(),
          placeholder.lastName(),
          placeholder.email(),
          placeholder.passwordHash(),
          placeholder.status(),
          placeholder.externalId(),
          placeholder.attributes());
    });
    securityDao.replaceUserRoles(user.id(), roleIds);
  }

  @Override
  public void upsertRealm(SecurityRealmRecord record) {
    securityDao.upsertRealm(record);
  }

  @Override
  public void updateRealmConfig(List<String> activeRealmIds) {
    securityDao.updateRealmConfig(activeRealmIds);
  }

  @Override
  public void upsertAnonymousConfig(SecurityAnonymousConfigRecord record) {
    securityDao.upsertAnonymousConfig(record);
  }

  @Override
  public void upsertApiKey(ApiKeyRecord record) {
    ensureApiKeyOwner(record);
    securityDao.upsertApiKey(record);
  }

  private void ensureApiKeyOwner(ApiKeyRecord record) {
    if (securityDao.findUser(record.ownerSource(), record.ownerUserId()).isPresent()) {
      return;
    }
    LinkedHashMap<String, Object> attributes = new LinkedHashMap<>();
    attributes.put("source", "nexus-api-key-owner-placeholder");
    attributes.put("apiKeyDomain", record.domain());
    securityDao.insertUser(new SecurityUserRecord(
        null,
        record.ownerSource(),
        record.ownerUserId(),
        null,
        null,
        null,
        null,
        STATUS_ACTIVE,
        null,
        attributes));
  }

  private static String valueOrExisting(String incoming, String existing) {
    return incoming == null || incoming.isBlank() ? existing : incoming;
  }

  private static Map<String, Object> mergeAttributes(
      Map<String, Object> existing,
      Map<String, Object> incoming) {
    if ((existing == null || existing.isEmpty()) && (incoming == null || incoming.isEmpty())) {
      return Map.of();
    }
    LinkedHashMap<String, Object> merged = new LinkedHashMap<>();
    if (existing != null) {
      merged.putAll(existing);
    }
    if (incoming != null) {
      merged.putAll(incoming);
    }
    return Map.copyOf(merged);
  }
}
