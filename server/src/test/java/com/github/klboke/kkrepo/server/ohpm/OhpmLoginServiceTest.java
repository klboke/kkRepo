package com.github.klboke.kkrepo.server.ohpm;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.github.klboke.kkrepo.persistence.jdbc.api.OhpmLoginDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.SecurityDao;
import com.github.klboke.kkrepo.protocol.ohpm.OhpmException;
import com.github.klboke.kkrepo.protocol.ohpm.OhpmLoginRequest;
import com.github.klboke.kkrepo.server.security.OhpmLoginTokenPolicy;
import com.github.klboke.kkrepo.server.transaction.TransientTransactionRetry;
import java.security.KeyPairGenerator;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import org.mockito.ArgumentCaptor;
import org.junit.jupiter.api.Test;

class OhpmLoginServiceTest {
  private final OhpmLoginDao keys = mock(OhpmLoginDao.class);
  private final SecurityDao security = mock(SecurityDao.class);
  private final OhpmLoginTokenPolicy policy = mock(OhpmLoginTokenPolicy.class);
  private final TransientTransactionRetry transactions = mock(TransientTransactionRetry.class);

  @Test
  void rotationLocksTheCurrentBindingBeforePreservingItsStateAndRevokingTokens() throws Exception {
    var generator = KeyPairGenerator.getInstance("RSA");
    generator.initialize(2048);
    String pem = "-----BEGIN PUBLIC KEY-----\n"
        + Base64.getEncoder().encodeToString(generator.generateKeyPair().getPublic().getEncoded())
        + "\n-----END PUBLIC KEY-----\n";
    when(transactions.execute(anyString(), any())).thenAnswer(invocation ->
        invocation.getArgument(1, Supplier.class).get());
    var service = new OhpmLoginService(keys, security, policy, transactions, true, 300, 300);
    Runnable authorize = mock(Runnable.class);
    for (boolean enabled : new boolean[] {true, false}) {
      var binding = new OhpmLoginDao.Key("publish-id", 1L, "Local", "alice", "ACTIVE",
          "previous-key", "previous-fingerprint", enabled, Instant.EPOCH);
      when(keys.lockKey("publish-id")).thenReturn(Optional.of(binding));
      when(keys.putKey(eq("Local"), eq("alice"), anyString(), anyString(), eq(enabled)))
          .thenAnswer(invocation -> new OhpmLoginDao.Key("publish-id", 1L, "Local", "alice", "ACTIVE",
              invocation.getArgument(2), invocation.getArgument(3), enabled, Instant.now()));
      var result = service.rotateKey("publish-id", pem, authorize);
      assertEquals("publish-id", result.publishId());
      assertEquals(enabled, result.enabled());
      assertNotEquals("previous-fingerprint", result.fingerprint());
      var ordered = inOrder(authorize, keys, security);
      ordered.verify(authorize).run();
      ordered.verify(keys).lockKey("publish-id");
      ordered.verify(keys).putKey(eq("Local"), eq("alice"), anyString(), anyString(), eq(enabled));
      ordered.verify(security).deleteApiKey(OhpmLoginTokenPolicy.DOMAIN, "Local", "alice");
      clearInvocations(authorize, keys, security);
    }
    when(keys.lockKey("missing")).thenReturn(Optional.empty());
    assertEquals(404, assertThrows(OhpmException.class,
        () -> service.rotateKey("missing", pem, authorize)).status());
    verify(keys, never()).putKey(anyString(), anyString(), anyString(), anyString(), anyBoolean());
    verifyNoInteractions(security);
    doThrow(new OhpmException(403, "Administrator required")).when(authorize).run();
    clearInvocations(keys);
    assertEquals(403, assertThrows(OhpmException.class,
        () -> service.rotateKey("publish-id", pem, authorize)).status());
    verifyNoInteractions(keys, security);
  }

  @Test
  void disabledLoginDoesNotReadCredentialsOrRecordNonce() {
    var service = new OhpmLoginService(keys, security, policy, transactions, false, 300, 300);
    assertFalse(service.enabled());
    assertEquals(new OhpmLoginService.Configuration(false, 300, 300), service.configuration());
    assertEquals(404, assertThrows(OhpmException.class, () -> service.login(null)).status());
    verifyNoInteractions(keys, security, policy, transactions);
  }

  @Test
  void staleLoginFailsBeforeOpeningTransaction() {
    var service = new OhpmLoginService(keys, security, policy, transactions, true, 300, 300);
    var request = new OhpmLoginRequest("publisher", 0, "1".repeat(32),
        Base64.getEncoder().encodeToString(new byte[256]));
    assertEquals(401, assertThrows(OhpmException.class, () -> service.login(request)).status());
    verifyNoInteractions(keys, security, policy, transactions);
  }

  @Test
  void invalidTimeSettingsFailAtStartup() {
    assertThrows(IllegalArgumentException.class,
        () -> new OhpmLoginService(keys, security, policy, transactions, true, 0, 300));
    assertThrows(IllegalArgumentException.class,
        () -> new OhpmLoginService(keys, security, policy, transactions, true, 601, 300));
    assertThrows(IllegalArgumentException.class,
        () -> new OhpmLoginService(keys, security, policy, transactions, true, 300, 0));
    assertThrows(IllegalArgumentException.class,
        () -> new OhpmLoginService(keys, security, policy, transactions, true, 300, 3601));
  }

  @Test
  void publicKeyRegistrationRejectsSmallRsaAndNonRsaKeys() throws Exception {
    var rsa = KeyPairGenerator.getInstance("RSA");
    rsa.initialize(1024);
    var elliptic = KeyPairGenerator.getInstance("EC");
    elliptic.initialize(256);
    for (var generator : new KeyPairGenerator[] {rsa, elliptic}) {
      String pem = "-----BEGIN PUBLIC KEY-----\n"
          + Base64.getEncoder().encodeToString(generator.generateKeyPair().getPublic().getEncoded())
          + "\n-----END PUBLIC KEY-----\n";
      assertThrows(IllegalArgumentException.class, () -> OhpmLoginService.publicKey(pem));
    }
    assertThrows(IllegalArgumentException.class, () -> OhpmLoginService.publicKey(null));
    assertThrows(IllegalArgumentException.class, () -> OhpmLoginService.publicKey("x".repeat(16385)));
  }

  @Test
  void listingKeysDoesNotExposePublicKeyMaterialAndCleanupUsesCurrentTime() {
    var service = new OhpmLoginService(keys, security, policy, transactions, true, 300, 300);
    var binding = new OhpmLoginDao.Key("publish-id", 1L, "Local", "alice", "ACTIVE",
        "public-key", "a".repeat(64), true, Instant.EPOCH);
    when(keys.listKeys(100, 100)).thenReturn(List.of(binding));
    assertEquals(List.of(new OhpmLoginService.KeyView("publish-id", "Local", "alice", "a".repeat(64),
        true, Instant.EPOCH)), service.keys(100));
    Instant before = Instant.now();
    service.cleanup();
    var cutoff = ArgumentCaptor.forClass(Instant.class);
    verify(keys).deleteExpiredNonces(cutoff.capture());
    assertFalse(cutoff.getValue().isBefore(before));
    assertFalse(cutoff.getValue().isAfter(Instant.now()));
    verifyNoInteractions(security, policy, transactions);
  }
}
