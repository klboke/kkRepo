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
import java.util.Base64;
import org.junit.jupiter.api.Test;

class OhpmLoginServiceTest {
  private final OhpmLoginDao keys = mock(OhpmLoginDao.class);
  private final SecurityDao security = mock(SecurityDao.class);
  private final OhpmLoginTokenPolicy policy = mock(OhpmLoginTokenPolicy.class);
  private final TransientTransactionRetry transactions = mock(TransientTransactionRetry.class);

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
}
