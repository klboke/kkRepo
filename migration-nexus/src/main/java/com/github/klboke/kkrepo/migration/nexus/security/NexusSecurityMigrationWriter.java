package com.github.klboke.kkrepo.migration.nexus.security;

import com.github.klboke.kkrepo.persistence.jdbc.api.model.ApiKeyRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.SecurityAnonymousConfigRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.SecurityPrivilegeRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.SecurityRealmRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.SecurityRepositoryTargetRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.SecurityRoleRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.SecurityUserRecord;
import java.util.List;

public interface NexusSecurityMigrationWriter {
  void upsertRepositoryTarget(SecurityRepositoryTargetRecord record);

  void upsertPrivilege(SecurityPrivilegeRecord record);

  void upsertRole(SecurityRoleRecord record);

  void replaceRolePrivileges(String roleId, List<String> privilegeIds);

  void replaceRoleInheritance(String roleId, List<String> childRoleIds);

  void upsertUser(SecurityUserRecord record);

  void replaceUserRoles(String source, String userId, List<String> roleIds);

  void upsertRealm(SecurityRealmRecord record);

  void updateRealmConfig(List<String> activeRealmIds);

  void upsertAnonymousConfig(SecurityAnonymousConfigRecord record);

  void upsertApiKey(ApiKeyRecord record);
}
