package com.github.klboke.kkrepo.migration.nexus.security;

import com.github.klboke.kkrepo.migration.nexus.security.NexusSecurityRecordMapper.NexusApiKey;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Nexus Repository Pro user tokens found on the source, which this version does not migrate.
 * Owners ({@code source/userId}) and counts only: never name codes, pass codes, raw keys or hashes.
 *
 * @param tokens {@code User-Token-Realm} records in the source API key store (Nexus Pro 3.71+)
 * @param owners distinct owners of those records
 * @param legacyTableTokens rows of the pre-3.71 {@code user_token} table, when the source script could see it
 * @param legacyTableOwners distinct owner user names of those rows that match a source user (their realm is unknown)
 * @param legacyTableOwnersUnverified some rows have an owner that matches no source user (Nexus may store it
 *     encrypted) or no owner column: those names are not shown
 * @param legacyTableUnreadable the source script found that table but could not read it: its token count is unknown
 */
public record NexusUserTokenReport(
    int tokens,
    List<String> owners,
    int legacyTableTokens,
    List<String> legacyTableOwners,
    boolean legacyTableOwnersUnverified,
    boolean legacyTableUnreadable,
    String message) {

  // Nexus Pro (3.71+) keeps its user tokens in the API key store under this domain.
  public static final String NEXUS_DOMAIN = "User-Token-Realm";
  static final String MESSAGE = "Nexus Repository Pro user tokens (User Token Realm) are not migrated by this "
      + "version, so the migration is incomplete; affected users generate a new kkRepo user token under My Token "
      + "after cutover.";
  static final String UNREADABLE = "legacy user_token table unreadable: token count unknown";
  static final String OWNERS_UNVERIFIED = "legacy user_token owners unverified";
  static final String OWNERS_UNVERIFIED_MESSAGE = "Owners of some legacy user_token rows match no source user, "
      + "so they are not shown (unverified).";
  static final String UNREADABLE_MESSAGE = "The source Nexus legacy (pre-3.71) Pro user_token table could not be "
      + "read, so the source user token count is unknown and the migration is incomplete (unverified).";

  public NexusUserTokenReport {
    owners = owners == null ? List.of() : List.copyOf(owners);
    legacyTableOwners = legacyTableOwners == null ? List.of() : List.copyOf(legacyTableOwners);
  }

  public static boolean isNexusUserToken(NexusApiKey apiKey) {
    return apiKey.domain() != null && NEXUS_DOMAIN.equalsIgnoreCase(apiKey.domain().trim());
  }

  public static NexusUserTokenReport of(NexusSecurityExport export) {
    NexusSecurityExport source = export == null ? NexusSecurityExport.empty() : export;
    NexusSecurityMigrationBatch batch = new NexusSecurityExportReader().read(source);
    List<NexusApiKey> records = batch.apiKeys().stream()
        .filter(NexusUserTokenReport::isNexusUserToken)
        .toList();
    TreeSet<String> owners = new TreeSet<>();
    records.forEach(record ->
        owners.add(NexusSecuritySources.normalizeSource(record.ownerSource()) + "/" + record.ownerUserId()));
    // A legacy owner is shown only when it names a source user: the column may hold ciphertext instead.
    Set<String> sourceUserIds = new HashSet<>();
    batch.users().forEach(user -> sourceUserIds.add(user.id()));
    batch.userRoleMappings().forEach(mapping -> sourceUserIds.add(mapping.userId()));
    batch.apiKeys().forEach(apiKey -> sourceUserIds.add(apiKey.ownerUserId()));
    Map<String, Object> legacy = source.legacyUserTokens();
    TreeSet<String> legacyNames = new TreeSet<>();
    if (legacy.get("users") instanceof List<?> users) {
      users.stream().filter(user -> user != null && !String.valueOf(user).isBlank())
          .forEach(user -> legacyNames.add(String.valueOf(user).trim()));
    }
    List<String> legacyOwners = legacyNames.stream().filter(sourceUserIds::contains).toList();
    int legacyCount = legacy.get("count") instanceof Number count ? count.intValue() : 0;
    legacyCount = Math.max(legacyCount, legacyNames.size());
    boolean ownersUnverified = legacyCount > 0
        && (legacyNames.isEmpty() || legacyOwners.size() < legacyNames.size());
    boolean unreadable = "unreadable".equals(legacy.get("status"));
    String message = unreadable
        ? (records.isEmpty() ? UNREADABLE_MESSAGE : UNREADABLE_MESSAGE + " " + MESSAGE)
        : !records.isEmpty() || legacyCount > 0 ? MESSAGE : null;
    if (ownersUnverified) {
      message = message + " " + OWNERS_UNVERIFIED_MESSAGE;
    }
    return new NexusUserTokenReport(records.size(), List.copyOf(owners), legacyCount, legacyOwners,
        ownersUnverified, unreadable, message);
  }

  public boolean found() {
    return tokens > 0 || legacyTableTokens > 0 || legacyTableUnreadable;
  }

  /** Validation details: counts and owners, no secrets. */
  public List<String> details() {
    ArrayList<String> details = new ArrayList<>();
    details.add("userTokens=" + tokens);
    owners.forEach(owner -> details.add("user-token:" + owner));
    if (legacyTableTokens > 0) {
      details.add("legacyUserTokens=" + legacyTableTokens);
      legacyTableOwners.forEach(owner -> details.add("legacy-user-token:" + owner));
      if (legacyTableOwnersUnverified) {
        details.add(OWNERS_UNVERIFIED);
      }
    }
    if (legacyTableUnreadable) {
      details.add(UNREADABLE);
    }
    return details;
  }
}
