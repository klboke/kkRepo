package com.github.klboke.kkrepo.migration.nexus.security;

import com.github.klboke.kkrepo.migration.nexus.security.NexusSecurityRecordMapper.NexusApiKey;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * Nexus Repository Pro user tokens found on the source, which this version does not migrate.
 * Owners ({@code source/userId}) and counts only: never name codes, pass codes, raw keys or hashes.
 *
 * @param tokens {@code User-Token-Realm} records in the source API key store (Nexus Pro 3.71+)
 * @param owners distinct owners of those records
 * @param legacyTableTokens rows of the pre-3.71 {@code user_token} table, when the source script could see it
 * @param legacyTableOwners distinct owner user names of those rows (their realm is unknown)
 */
public record NexusUserTokenReport(
    int tokens,
    List<String> owners,
    int legacyTableTokens,
    List<String> legacyTableOwners,
    String message) {

  // Nexus Pro (3.71+) keeps its user tokens in the API key store under this domain.
  public static final String NEXUS_DOMAIN = "User-Token-Realm";
  static final String MESSAGE = "Nexus Repository Pro user tokens (User Token Realm) are not migrated by this "
      + "version, so the migration is incomplete; affected users generate a new kkRepo user token under My Token "
      + "after cutover.";

  public NexusUserTokenReport {
    owners = owners == null ? List.of() : List.copyOf(owners);
    legacyTableOwners = legacyTableOwners == null ? List.of() : List.copyOf(legacyTableOwners);
  }

  public static boolean isNexusUserToken(NexusApiKey apiKey) {
    return apiKey.domain() != null && NEXUS_DOMAIN.equalsIgnoreCase(apiKey.domain().trim());
  }

  public static NexusUserTokenReport of(NexusSecurityExport export) {
    NexusSecurityExport source = export == null ? NexusSecurityExport.empty() : export;
    List<NexusApiKey> records = new NexusSecurityExportReader().read(source).apiKeys().stream()
        .filter(NexusUserTokenReport::isNexusUserToken)
        .toList();
    TreeSet<String> owners = new TreeSet<>();
    records.forEach(record ->
        owners.add(NexusSecuritySources.normalizeSource(record.ownerSource()) + "/" + record.ownerUserId()));
    Map<String, Object> legacy = source.legacyUserTokens();
    TreeSet<String> legacyOwners = new TreeSet<>();
    if (legacy.get("users") instanceof List<?> users) {
      users.stream().filter(user -> user != null && !String.valueOf(user).isBlank())
          .forEach(user -> legacyOwners.add(String.valueOf(user).trim()));
    }
    int legacyCount = legacy.get("count") instanceof Number count ? count.intValue() : 0;
    legacyCount = Math.max(legacyCount, legacyOwners.size());
    boolean found = !records.isEmpty() || legacyCount > 0;
    return new NexusUserTokenReport(records.size(), List.copyOf(owners), legacyCount, List.copyOf(legacyOwners),
        found ? MESSAGE : null);
  }

  public boolean found() {
    return tokens > 0 || legacyTableTokens > 0;
  }

  /** Validation details: counts and owners, no secrets. */
  public List<String> details() {
    ArrayList<String> details = new ArrayList<>();
    details.add("userTokens=" + tokens);
    owners.forEach(owner -> details.add("user-token:" + owner));
    if (legacyTableTokens > 0) {
      details.add("legacyUserTokens=" + legacyTableTokens);
      legacyTableOwners.forEach(owner -> details.add("legacy-user-token:" + owner));
    }
    return details;
  }
}
