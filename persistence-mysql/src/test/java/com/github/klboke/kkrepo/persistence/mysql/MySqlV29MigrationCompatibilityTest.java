package com.github.klboke.kkrepo.persistence.mysql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.klboke.kkrepo.persistence.mysql.support.MySqlIntegrationTestSupport;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Guards the frozen V1-V29 chain and validates repeat startup after newer migrations. */
class MySqlV29MigrationCompatibilityTest extends MySqlIntegrationTestSupport {
  private static final Map<String, String> V29_SHA256 = checksums();

  @Test
  void relocatedV1ThroughV29RemainByteForByteFrozen() throws Exception {
    assertEquals(29, V29_SHA256.size());
    for (var entry : V29_SHA256.entrySet()) {
      try (InputStream stream = getClass().getResourceAsStream(
          "/db/migration/mysql/" + entry.getKey())) {
        assertTrue(stream != null, "missing migration " + entry.getKey());
        assertEquals(entry.getValue(), hex(MessageDigest.getInstance("SHA-256").digest(stream.readAllBytes())),
            "legacy migration checksum changed: " + entry.getKey());
      }
    }
  }

  @Test
  void existingV29HistoryUpgradesAndSecondMigrateHasNoPendingWork() {
    assertTrue(flyway().validateWithResult().validationSuccessful);
    var result = flyway().migrate();
    assertEquals(0, result.migrationsExecuted);
    assertEquals("34", flyway().info().current().getVersion().getVersion());
  }

  private static String hex(byte[] bytes) {
    return java.util.HexFormat.of().formatHex(bytes);
  }

  private static Map<String, String> checksums() {
    Map<String, String> values = new LinkedHashMap<>();
    values.put("V1__init_schema.sql", "6bc6b6b431ae9929ae237939826cdfd07b80a986b563f3af3363ce67e663ccc8");
    values.put("V2__maven_runtime.sql", "5a99f1057f0b98882b02ea2000e5754837c048e095a74ddee04790fef287da7f");
    values.put("V3__perf_indexes.sql", "fdf6b17ffc3b6d5869fc7210a56387690a57f2439fa539438f717bb5127c40af");
    values.put("V4__component_search_indexes.sql", "eee428d14ca0bbedf4f90082251fcd4de1a3473f9858158fdc0ae43d6e895e2a");
    values.put("V5__group_repository_blob_store.sql", "26c4100e2180776c9d504df8e1712ec14e2870fd595b927eafe3f9fe7aba8bae");
    values.put("V6__security_management_model.sql", "6e8e3a36a2524ee3e154da8880d1c6fa9f3917cc071f91bd5f22c75b7cfd010d");
    values.put("V7__nexus_builtin_security_seed.sql", "42ae6033fba7a889041728bc5202e6b35a063293bb096d46b607bc11e03ff19d");
    values.put("V8__unify_security_role_source.sql", "4b22ce252457493837114ec38498724341ca5706135d937edb3cbe1d45cd3465");
    values.put("V9__nexus_repository_dynamic_privileges.sql", "d6fb043806e970916307e01af1508da85271499631bfcf3464156531607f9a21");
    values.put("V10__nexus_blobstore_security_privileges.sql", "bc7a8f925380b9219cf8c3d9cfad59004c22ab8ad525946a5e8caf5ead056b32");
    values.put("V11__repository_format_maven2.sql", "7597cd971c49198c53e5fa6bd1169ee907047599c48d509b0d22145222b59be6");
    values.put("V12__spring_session_jdbc.sql", "d80f978b55c8666651849738bb784526c907db8c2abe5da70dd768ca24919b73");
    values.put("V13__local_security_source.sql", "60f177ee3e8a7600e71fc95b622fd8177f32f57c1cef6a19d650bf63d0cd6ba2");
    values.put("V14__security_hardening.sql", "12669d7a235536ebb9c0eaf628f63821e6cf22df17035eb96a8955e2d960f2a8");
    values.put("V15__performance_remediation.sql", "79bda29c6fc05a6738cd762a0af4fbeb2294ae64ffad6a91d6e3743c5fb7ac69");
    values.put("V16__maintenance_cursor.sql", "98a66065236077eca66358ee4263c22cca2af25e9aa8f6a9d1a58be97b24bd9e");
    values.put("V17__asset_blob_dedupe_index.sql", "ef3b49318282260811832b915c186908230d845b6960fab541c27c08abf253c5");
    values.put("V18__npm_tarball_content_type.sql", "634499bcc70716ca64d05d8d723f78624b4610139219020fb107d456cb482b83");
    values.put("V19__security_audit_log_search_indexes.sql", "3718833c5de23bd4eed50eec28b2e8b2fd5de872327ed013aa5f47a5c3b18920");
    values.put("V20__repository_data_migration.sql", "9851096fc65bd4bcd57e77074879a5a0745c4a6a0eeb285f6d829f48547a5ab5");
    values.put("V21__cache_version.sql", "d2e24e6b9480054d91cdc2e9e15c75fd4a7a64d793953c7a5023b374a2ac6a71");
    values.put("V22__auth_ticket.sql", "1930a1233d879e5492feb7925c3d06a89b01a814ecc1e76812a83c83f81f133a");
    values.put("V23__asset_blob_usage_index.sql", "645c743f279c1de2dff82ce8e18c6ef777ac9eb1e2f066959de91ba28901bb8a");
    values.put("V24__remove_legacy_oss_accelerator_engine.sql", "7d914dc257ff6ae344f469b41d970c4735782aace741cf9ac5fe61e236d81d65");
    values.put("V25__docker_registry.sql", "e4e6f4d64c349e5a43c1d3fc8d734c2a0f3d2bf559b951145b2d0d75b8576cf9");
    values.put("V26__docker_connector_port_unique.sql", "78f0475c2c6a54814999dfe70dac265aad79ce16ec78ad2132ea154456b9de07");
    values.put("V27__ui_settings.sql", "1eee32d44e667f9f2b8976720df38e95a72656f2352d0d151a2411ea97c36093");
    values.put("V28__pub_upload_session.sql", "903646bd97ae9910bd633637f367069d92d6a6f2ef21682a4331774f9ddb1a73");
    values.put("V29__disable_anonymous_for_uninitialized_installations.sql", "c3523c38f5ffc32debaf99b6501b599e9927e7951027a4ba7fe6b8368d431051");
    return Map.copyOf(values);
  }
}
