package com.github.klboke.kkrepo.persistence.jdbc.contract;

import static org.junit.jupiter.api.Assertions.*;

import com.github.klboke.kkrepo.persistence.jdbc.api.PersistenceStores;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.SecurityUserRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.ApiKeyRecord;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.TimeZone;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

public final class OhpmLoginDaoContract {
  private OhpmLoginDaoContract() {}

  public static void verify(JdbcTemplate jdbc, PersistenceStores stores) throws Exception {
    var transactions = new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource()));
    var keys = stores.ohpmLogin();
    long owner = stores.security().insertUser(new SecurityUserRecord(null, "Local", "publisher",
        "Publisher", "Example", "publisher@example.com", "hash", "ACTIVE", null, Map.of()));
    String fingerprint = "a".repeat(64);
    assertThrows(IllegalStateException.class,
        () -> keys.putKey("Local", "publisher", "public-key", fingerprint, true));
    assertThrows(IllegalStateException.class, () -> keys.lockKey("missing"));
    assertThrows(IllegalStateException.class, () -> keys.deleteKey("missing"));
    assertThrows(IllegalArgumentException.class, () -> transactions.execute(status ->
        keys.putKey("Local", "missing", "public-key", fingerprint, true)));

    var original = transactions.execute(status ->
        keys.putKey("Local", "publisher", "public-key", fingerprint, true));
    assertEquals(owner, original.userNumericId());
    assertEquals("ACTIVE", original.userStatus());
    assertTrue(original.enabled());
    assertEquals(original, keys.findKey(original.publishId()).orElseThrow());
    assertEquals(original, keys.listKeys(0, 100).getFirst());
    assertTrue(keys.listKeys(1, 100).isEmpty());

    Instant now = Instant.parse("2026-11-06T00:00:00Z");
    verifyTimezoneIndependence(transactions, stores, original.publishId(), now);
    String nonce = "1".repeat(32);
    assertThrows(IllegalStateException.class, () -> keys.recordNonce(original.publishId(), nonce, now));
    transactions.executeWithoutResult(status -> {
      assertTrue(keys.recordNonce(original.publishId(), nonce, now));
      status.setRollbackOnly();
    });
    try (var executor = Executors.newFixedThreadPool(4)) {
      var attempts = IntStream.range(0, 8).mapToObj(index -> (Callable<Boolean>) () ->
          transactions.execute(status -> keys.recordNonce(original.publishId(), nonce, now))).toList();
      int winners = 0;
      for (var completed : executor.invokeAll(attempts)) if (completed.get()) winners++;
      assertEquals(1, winners);
    }
    assertTrue(transactions.<Boolean>execute(status ->
        keys.recordNonce(original.publishId(), "2".repeat(32), now.plusSeconds(30))));
    assertEquals(0, keys.deleteExpiredNonces(now.minusSeconds(1)));
    assertEquals(1, keys.deleteExpiredNonces(now));
    assertEquals(0, keys.deleteExpiredNonces(now));
    assertFalse(transactions.<Boolean>execute(status ->
        keys.recordNonce(original.publishId(), "2".repeat(32), now.plusSeconds(60))));

    transactions.executeWithoutResult(status -> {
      keys.putKey("Local", "publisher", "rolled-back-key", "b".repeat(64), false);
      status.setRollbackOnly();
    });
    assertEquals(original, keys.findKey(original.publishId()).orElseThrow());
    var rotated = transactions.execute(status ->
        keys.putKey("Local", "publisher", "rotated-key", "b".repeat(64), false));
    assertEquals(original.publishId(), rotated.publishId());
    assertFalse(rotated.enabled());
    assertEquals("rotated-key", rotated.publicKey());
    assertEquals(1, keys.listKeys(0, 100).size());
    assertFalse(transactions.<Boolean>execute(status ->
        keys.recordNonce(original.publishId(), "2".repeat(32), now.plusSeconds(60))));

    CountDownLatch snapshot = new CountDownLatch(1);
    CountDownLatch committed = new CountDownLatch(1);
    try (var executor = Executors.newSingleThreadExecutor()) {
      var reader = executor.submit(() -> transactions.execute(status -> {
        assertFalse(keys.findKey(original.publishId()).orElseThrow().enabled());
        snapshot.countDown();
        try {
          assertTrue(committed.await(10, TimeUnit.SECONDS));
        } catch (InterruptedException interrupted) {
          Thread.currentThread().interrupt();
          throw new IllegalStateException(interrupted);
        }
        assertTrue(keys.lockKey(original.publishId()).orElseThrow().enabled());
        return keys.recordNonce(original.publishId(), "3".repeat(32), now.plusSeconds(60));
      }));
      assertTrue(snapshot.await(10, TimeUnit.SECONDS));
      try {
        transactions.executeWithoutResult(status -> {
          keys.putKey("Local", "publisher", "current-key", "c".repeat(64), true);
          assertTrue(keys.recordNonce(original.publishId(), "3".repeat(32), now.plusSeconds(60)));
        });
      } finally {
        committed.countDown();
      }
      assertFalse(reader.get(10, TimeUnit.SECONDS));
    }
    stores.security().updateUser(new SecurityUserRecord(owner, "Local", "publisher",
        "Publisher", "Example", "publisher@example.com", "hash", "DISABLED", null, Map.of()));
    assertEquals("DISABLED", keys.findKey(original.publishId()).orElseThrow().userStatus());
    transactions.executeWithoutResult(status -> {
      assertTrue(keys.deleteKey(original.publishId()));
      status.setRollbackOnly();
    });
    assertTrue(keys.findKey(original.publishId()).isPresent());
    assertTrue(transactions.<Boolean>execute(status -> keys.deleteKey(original.publishId())));
    assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM ohpm_login_nonce", Integer.class));
    var recreated = transactions.execute(status ->
        keys.putKey("Local", "publisher", "new-key", fingerprint, false));
    assertNotEquals(original.publishId(), recreated.publishId());
    assertTrue(transactions.<Boolean>execute(status ->
        keys.recordNonce(recreated.publishId(), nonce, now)));
    verifyTokenRevocation(transactions, stores);
    stores.security().deleteUser("Local", "publisher");
    assertTrue(keys.findKey(recreated.publishId()).isEmpty());
    assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM ohpm_login_nonce", Integer.class));
  }

  private static void verifyTimezoneIndependence(TransactionTemplate transactions, PersistenceStores stores,
      String publishId, Instant now) {
    var keys = stores.ohpmLogin();
    TimeZone original = TimeZone.getDefault();
    try {
      TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
      var binding = keys.findKey(publishId).orElseThrow();
      assertTrue(transactions.<Boolean>execute(status ->
          keys.recordNonce(publishId, "f".repeat(32), now.plusSeconds(301))));
      TimeZone.setDefault(TimeZone.getTimeZone("Asia/Shanghai"));
      assertEquals(0, keys.deleteExpiredNonces(now), "Another timezone must not reopen the replay window");
      assertEquals(binding, keys.findKey(publishId).orElseThrow());
      assertFalse(transactions.<Boolean>execute(status ->
          keys.recordNonce(publishId, "f".repeat(32), now.plusSeconds(301))));
      TimeZone.setDefault(TimeZone.getTimeZone("America/Los_Angeles"));
      assertEquals(1, keys.deleteExpiredNonces(now.plusSeconds(302)), "Expired nonces must remain reclaimable");
    } finally {
      TimeZone.setDefault(original);
    }
  }

  private static void verifyTokenRevocation(TransactionTemplate transactions, PersistenceStores stores) throws Exception {
    var security = stores.security();
    LocalDateTime expiry = LocalDateTime.of(2026, 11, 6, 1, 2, 3);
    security.upsertApiKey(new ApiKeyRecord(null, "GenericToken", "Local", "publisher", "Generic", "ACTIVE",
        "generic-hash", "GenericToken", Map.of(), "encrypted", null, null, expiry, null));
    assertEquals(expiry, security.findApiKey("GenericToken", "Local", "publisher").orElseThrow().expiresAt());
    CountDownLatch snapshot = new CountDownLatch(1);
    CountDownLatch committed = new CountDownLatch(1);
    try (var executor = Executors.newSingleThreadExecutor()) {
      var revocation = executor.submit(() -> transactions.execute(status -> {
        assertTrue(security.findApiKey("OhpmLogin", "Local", "publisher").isEmpty());
        snapshot.countDown();
        try {
          assertTrue(committed.await(10, TimeUnit.SECONDS));
        } catch (InterruptedException interrupted) {
          Thread.currentThread().interrupt();
          throw new IllegalStateException(interrupted);
        }
        return security.deleteApiKey("OhpmLogin", "Local", "publisher");
      }));
      assertTrue(snapshot.await(10, TimeUnit.SECONDS));
      try {
        security.upsertApiKey(new ApiKeyRecord(null, "OhpmLogin", "Local", "publisher", "Signed", "ACTIVE",
            "login-hash", "OhpmLogin", Map.of(), "encrypted", null, null, expiry, null));
      } finally {
        committed.countDown();
      }
      assertEquals(1, revocation.get(10, TimeUnit.SECONDS));
    }
    assertTrue(security.findApiKey("OhpmLogin", "Local", "publisher").isEmpty());
    assertTrue(security.findApiKey("GenericToken", "Local", "publisher").isPresent());
  }
}
