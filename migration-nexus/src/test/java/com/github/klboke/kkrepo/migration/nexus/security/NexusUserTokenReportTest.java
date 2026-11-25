package com.github.klboke.kkrepo.migration.nexus.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;

class NexusUserTokenReportTest {
  // What an encrypted user_token.user_name column returns to a plain JDBC read.
  private static final String CIPHERTEXT = "{b7wqvCyGAOYGfIXc0dmM3zNRpFR6Lq3eDOn9X1zB2Mw=}";
  private final ObjectMapper mapper = new ObjectMapper();

  @Test
  void legacyOwnersAreShownOnlyWhenTheyNameASourceUser() throws Exception {
    NexusUserTokenReport report = NexusUserTokenReport.of(export("""
        {"users": [{"id": "alice", "source": "default"}],
         "userRoleMappings": [{"userId": "bob", "source": "LDAP", "roles": []}],
         "legacyUserTokens": {"count": 4, "users": ["alice", "bob", "%s"]}}
        """.formatted(CIPHERTEXT)));

    assertEquals(4, report.legacyTableTokens());
    assertEquals(List.of("alice", "bob"), report.legacyTableOwners());
    assertTrue(report.legacyTableOwnersUnverified());
    assertTrue(report.found());
    assertEquals(List.of("userTokens=0", "legacyUserTokens=4", "legacy-user-token:alice", "legacy-user-token:bob",
        NexusUserTokenReport.OWNERS_UNVERIFIED), report.details());
    assertTrue(report.message().endsWith(NexusUserTokenReport.OWNERS_UNVERIFIED_MESSAGE), report.message());
    assertFalse(mapper.writeValueAsString(report).contains(CIPHERTEXT));
  }

  @Test
  void ownersOfRowsWithoutAnOwnerColumnAreUnverified() throws Exception {
    NexusUserTokenReport report = NexusUserTokenReport.of(export("""
        {"legacyUserTokens": {"count": 2, "users": []}}
        """));

    assertEquals(2, report.legacyTableTokens());
    assertEquals(List.of(), report.legacyTableOwners());
    assertTrue(report.legacyTableOwnersUnverified());
  }

  @Test
  void ownersMatchingSourceUsersAreVerified() throws Exception {
    NexusUserTokenReport report = NexusUserTokenReport.of(export("""
        {"apiKeys": [{"domain": "NpmToken", "ownerUserId": "carol", "ownerSource": "default"}],
         "legacyUserTokens": {"count": 1, "users": ["carol"]}}
        """));

    assertEquals(List.of("carol"), report.legacyTableOwners());
    assertFalse(report.legacyTableOwnersUnverified());
    assertEquals(NexusUserTokenReport.MESSAGE, report.message());
  }

  private NexusSecurityExport export(String json) throws Exception {
    return mapper.readValue(json, NexusSecurityExport.class);
  }
}
