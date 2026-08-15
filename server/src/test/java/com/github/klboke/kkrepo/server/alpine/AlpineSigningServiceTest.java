package com.github.klboke.kkrepo.server.alpine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.core.RepositoryType;
import com.github.klboke.kkrepo.persistence.jdbc.api.AlpineRegistryDao;
import com.github.klboke.kkrepo.protocol.alpine.AlpineSignature;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntime;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class AlpineSigningServiceTest {

  @Test
  void generatesEncryptedRepositoryKeysRotatesAndSignsExactBytes() {
    AtomicReference<AlpineRegistryDao.SigningKey> stored = new AtomicReference<>();
    AlpineRegistryDao registry = registry(stored);
    AlpineSigningService service = new AlpineSigningService(
        registry, new AlpineLeaseManager(registry), mock(AlpineRepositorySettings.class));
    RepositoryRuntime runtime = runtime();

    AlpineRegistryDao.SigningKey first = service.rotateGenerated(
        runtime, "alpine-test.rsa.pub", "RSA256");
    assertEquals(1, first.revision());
    assertTrue(first.publicKey().contains("BEGIN PUBLIC KEY"));
    assertTrue(!first.encryptedPrivateKey().contains("BEGIN PRIVATE KEY"));

    AlpineSigningService.SigningMaterial material = service.active(runtime);
    byte[] payload = "signed APKINDEX member".getBytes(StandardCharsets.UTF_8);
    AlpineSignature signature = service.sign(payload, material);
    assertEquals(".SIGN.RSA256.alpine-test.rsa.pub", signature.entryName());
    assertTrue(AlpineSigningService.verify(
        payload, signature.bytes(), material.publicKey(), AlpineSignature.Type.RSA256));

    AlpineRegistryDao.SigningKey second = service.rotateGenerated(
        runtime, "alpine-test.rsa.pub", "RSA512");
    assertEquals(2, second.revision());
    assertNotEquals(first.fingerprint(), second.fingerprint());
    assertEquals(2, service.active(runtime).revision());
    assertThrows(IllegalArgumentException.class,
        () -> service.rotateGenerated(runtime, "../bad.rsa.pub", "RSA"));
    assertThrows(IllegalArgumentException.class,
        () -> service.rotateGenerated(runtime, "alpine-test.rsa.pub", "DSA"));
  }

  @Test
  void rejectsMissingOrMalformedImportedKeys() {
    AlpineRegistryDao registry = registry(new AtomicReference<>());
    AlpineSigningService service = new AlpineSigningService(
        registry, new AlpineLeaseManager(registry), mock(AlpineRepositorySettings.class));
    assertThrows(IllegalArgumentException.class,
        () -> service.rotate(runtime(), " ", "key.rsa.pub", "RSA"));
    assertThrows(IllegalArgumentException.class,
        () -> service.rotate(runtime(), "not a key", "key.rsa.pub", "RSA"));
  }

  private static AlpineRegistryDao registry(
      AtomicReference<AlpineRegistryDao.SigningKey> stored) {
    AlpineRegistryDao registry = mock(AlpineRegistryDao.class);
    when(registry.findActiveSigningKey(anyLong()))
        .thenAnswer(ignored -> Optional.ofNullable(stored.get()));
    when(registry.tryAcquireLease(anyString(), anyString(), any(), any())).thenAnswer(invocation ->
        Optional.of(new AlpineRegistryDao.Lease(
            invocation.getArgument(0), invocation.getArgument(1), 1, 1,
            invocation.getArgument(3), invocation.getArgument(2))));
    when(registry.renewLease(anyString(), anyString(), anyLong(), any(), any())).thenReturn(true);
    doAnswer(invocation -> {
      stored.set(invocation.getArgument(0));
      return null;
    }).when(registry).insertSigningKey(any());
    return registry;
  }

  private static RepositoryRuntime runtime() {
    return new RepositoryRuntime(
        7, "alpine-hosted", RepositoryFormat.ALPINE, RepositoryType.HOSTED,
        "alpine-hosted", true, 1L, "ALLOW", null, null, true,
        null, null, null, null, null, List.of());
  }
}
