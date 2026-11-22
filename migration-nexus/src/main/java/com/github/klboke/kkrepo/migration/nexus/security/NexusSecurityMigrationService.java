package com.github.klboke.kkrepo.migration.nexus.security;

import com.github.klboke.kkrepo.migration.nexus.security.NexusSecurityRecordMapper.MappedRole;
import com.github.klboke.kkrepo.migration.nexus.security.NexusSecurityRecordMapper.MappedUserRoleMapping;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.SecurityRealmRecord;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

public class NexusSecurityMigrationService {
  // Mirrors the server's API key domain identity rule (UserTokens.canonicalApiKeyDomain).
  private static final Pattern API_KEY_DOMAIN = Pattern.compile("[A-Za-z0-9._-]{1,100}");
  private static final String USER_TOKEN_DOMAIN = "UserToken";
  // Nexus Pro (3.71+) keeps its user tokens in the API key store under this domain.
  private static final String NEXUS_USER_TOKEN_DOMAIN = "User-Token-Realm";

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

    List<NexusSecurityRecordMapper.NexusApiKey> apiKeys = source.apiKeys().stream()
        .filter(NexusSecurityMigrationService::importableApiKey)
        .toList();
    apiKeys.stream()
        .map(mapper::mapApiKey)
        .forEach(writer::upsertApiKey);

    return new NexusSecurityMigrationResult(
        source.contentSelectors().size(),
        source.privileges().size() - ignoredRepositoryTargetPrivileges.size(),
        source.roles().size(),
        source.users().size(),
        source.userRoleMappings().size(),
        realms.size(),
        apiKeys.size(),
        source.anonymousConfig() == null ? 0 : 1);
  }

  /**
   * kkRepo user tokens are issued only to their owner, so a migration source (which an administrator
   * chooses) must not set one. Other spellings are skipped too: on MySQL the collation could make the
   * upsert overwrite the owner's stored user token. Nexus API key domains are plain ASCII names.
   * Nexus Pro user tokens are not imported either: their format differs, and owners generate new
   * kkRepo user tokens.
   * Preflight plans apply the same rule, so they list only the keys a run will write.
   */
  public static boolean importableApiKey(NexusSecurityRecordMapper.NexusApiKey apiKey) {
    String domain = apiKey.domain() == null ? "" : apiKey.domain().trim();
    return API_KEY_DOMAIN.matcher(domain).matches()
        && !USER_TOKEN_DOMAIN.equalsIgnoreCase(domain)
        && !NEXUS_USER_TOKEN_DOMAIN.equalsIgnoreCase(domain);
  }
}
