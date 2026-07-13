package com.github.klboke.kkrepo.persistence.jdbc.api;

import com.github.klboke.kkrepo.persistence.jdbc.api.model.ApiKeyRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.SecurityAnonymousConfigRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.SecurityPrivilegeRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.SecurityRealmRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.SecurityRepositoryTargetRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.SecurityRoleRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.SecurityUserRecord;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface SecurityDao {
  long insertUser(SecurityUserRecord record);

  void updateUser(SecurityUserRecord record);

  void updatePasswordHash(String source, String userId, String passwordHash);

  Optional<SecurityUserRecord> findUser(String source, String userId);

  List<SecurityUserRecord> listUsers();

  int deleteUser(String source, String userId);

  void upsertRole(SecurityRoleRecord record);

  Optional<SecurityRoleRecord> findRole(String roleId);

  List<SecurityRoleRecord> listRoles();

  int deleteRole(String roleId);

  void removeRoleReferences(String roleId);

  void upsertPrivilege(SecurityPrivilegeRecord record);

  void insertPrivilegeIfAbsent(SecurityPrivilegeRecord record);

  Optional<SecurityPrivilegeRecord> findPrivilege(String privilegeId);

  List<SecurityPrivilegeRecord> listPrivileges();

  List<SecurityPrivilegeRecord> listPrivilegesForRoles(List<String> roleIds);

  int deletePrivilege(String privilegeId);

  void removePrivilegeReferences(String privilegeId);

  void assignRole(long userNumericId, String roleId);

  void replaceUserRoles(long userNumericId, List<String> roleIds);

  List<String> listUserRoleIds(long userNumericId);

  List<String> listUserRoleIds(String source, String userId);

  void grantPrivilege(String roleId, String privilegeId);

  void replaceRolePrivileges(String roleId, List<String> privilegeIds);

  List<String> listRolePrivilegeIds(String roleId);

  void inheritRole(String roleId, String childRoleId);

  void replaceRoleInheritance(String roleId, List<String> childRoleIds);

  List<String> listRoleChildIds(String roleId);

  List<SecurityRealmRecord> listRealms();

  Optional<SecurityRealmRecord> findRealm(String realmId);

  void upsertRealm(SecurityRealmRecord record);

  void updateRealmConfig(List<String> activeRealmIds);

  Optional<SecurityAnonymousConfigRecord> findAnonymousConfig();

  void upsertAnonymousConfig(SecurityAnonymousConfigRecord record);

  List<SecurityRepositoryTargetRecord> listRepositoryTargets();

  Optional<SecurityRepositoryTargetRecord> findRepositoryTarget(String targetId);

  void upsertRepositoryTarget(SecurityRepositoryTargetRecord record);

  int deleteRepositoryTarget(String targetId);

  List<ApiKeyRecord> listApiKeys();

  List<ApiKeyRecord> listApiKeysForOwner(String ownerSource, String ownerUserId);

  Optional<ApiKeyRecord> findApiKey(long id);

  Optional<ApiKeyRecord> findApiKeyForOwner(long id, String ownerSource, String ownerUserId);

  Optional<ApiKeyRecord> findApiKey(String domain, String ownerSource, String ownerUserId);

  Optional<ApiKeyRecord> findApiKeyByHash(String apiKeyHash);

  Optional<ApiKeyRecord> findApiKeyByDomainAndHash(String domain, String apiKeyHash);

  void upsertApiKey(ApiKeyRecord record);

  int deleteApiKey(long id);

  int deleteApiKeysForOwner(String ownerSource, String ownerUserId);

  void markApiKeyUsed(long id, LocalDateTime usedAt);
}
