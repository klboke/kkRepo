package com.github.klboke.kkrepo.migration.nexus.security;

import com.github.klboke.kkrepo.migration.nexus.security.NexusSecurityRecordMapper.MappedRole;
import com.github.klboke.kkrepo.migration.nexus.security.NexusSecurityRecordMapper.MappedUserRoleMapping;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.SecurityRealmRecord;
import java.util.List;
import java.util.Set;

public class NexusSecurityMigrationService {
  private final NexusSecurityRecordMapper mapper;
  private final NexusSecurityMigrationWriter writer;

  public NexusSecurityMigrationService(
      NexusSecurityRecordMapper mapper,
      NexusSecurityMigrationWriter writer) {
    this.mapper = mapper;
    this.writer = writer;
  }

  public NexusSecurityMigrationResult migrate(NexusSecurityMigrationBatch batch) {
    NexusSecurityMigrationBatch source = batch == null ? NexusSecurityMigrationBatch.empty() : batch;
    Set<String> ignoredRepositoryTargetPrivileges = source.privileges().stream()
        .filter(privilege -> "repository-target".equals(privilege.type()))
        .map(NexusSecurityRecordMapper.NexusPrivilege::id)
        .collect(java.util.stream.Collectors.toUnmodifiableSet());

    source.contentSelectors().stream()
        .map(mapper::mapContentSelector)
        .forEach(writer::upsertRepositoryTarget);

    source.privileges().stream()
        .filter(privilege -> !"repository-target".equals(privilege.type()))
        .map(mapper::mapPrivilege)
        .forEach(writer::upsertPrivilege);

    List<MappedRole> roles = source.roles().stream()
        .map(mapper::mapRole)
        .map(role -> new MappedRole(
            role.record(),
            role.privileges().stream()
                .filter(privilegeId -> !ignoredRepositoryTargetPrivileges.contains(privilegeId))
                .toList(),
            role.childRoles()))
        .toList();
    roles.forEach(role -> writer.upsertRole(role.record()));
    roles.forEach(role -> {
      writer.replaceRolePrivileges(role.record().roleId(), role.privileges());
      writer.replaceRoleInheritance(role.record().roleId(), role.childRoles());
    });

    source.users().stream()
        .map(mapper::mapUser)
        .forEach(writer::upsertUser);

    List<MappedUserRoleMapping> userRoleMappings = source.userRoleMappings().stream()
        .map(mapper::mapUserRoleMapping)
        .toList();
    userRoleMappings.forEach(mapping ->
        writer.replaceUserRoles(mapping.source(), mapping.userId(), mapping.roles()));

    List<SecurityRealmRecord> realms = mapper.mapRealmOrder(source.realmOrder());
    if (!source.realmOrder().isEmpty()) {
      realms.forEach(writer::upsertRealm);
      writer.updateRealmConfig(realms.stream()
          .filter(SecurityRealmRecord::enabled)
          .sorted((left, right) -> Integer.compare(left.priority(), right.priority()))
          .map(SecurityRealmRecord::realmId)
          .toList());
    }

    if (source.anonymousConfig() != null) {
      writer.upsertAnonymousConfig(mapper.mapAnonymousConfig(source.anonymousConfig()));
    }

    source.apiKeys().stream()
        .map(mapper::mapApiKey)
        .forEach(writer::upsertApiKey);

    return new NexusSecurityMigrationResult(
        source.contentSelectors().size(),
        source.privileges().size() - ignoredRepositoryTargetPrivileges.size(),
        source.roles().size(),
        source.users().size(),
        source.userRoleMappings().size(),
        realms.size(),
        source.apiKeys().size(),
        source.anonymousConfig() == null ? 0 : 1);
  }
}
