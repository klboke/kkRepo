package com.github.klboke.kkrepo.server.alpine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
import java.nio.file.Files;
import java.lang.reflect.InvocationTargetException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.util.Base64;
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

  @Test
  void importsPkcs8KeySignsFilesAndRejectsInvalidVerificationInputs() throws Exception {
    AtomicReference<AlpineRegistryDao.SigningKey> stored = new AtomicReference<>();
    AlpineRegistryDao registry = registry(stored);
    AlpineSigningService service = new AlpineSigningService(
        registry, new AlpineLeaseManager(registry), mock(AlpineRepositorySettings.class));
    KeyPair pair = rsa();
    String privatePem = pem("PRIVATE KEY", pair.getPrivate().getEncoded());

    AlpineRegistryDao.SigningKey imported = service.rotate(
        runtime(), privatePem, "imported.rsa.pub", "RSA512");
    assertEquals("RSA512", imported.signatureType());
    AlpineSigningService.SigningMaterial material = service.active(runtime());
    var file = Files.createTempFile("alpine-signing-", ".index");
    try {
      Files.writeString(file, "index", StandardCharsets.US_ASCII);
      AlpineSignature signature = service.sign(file, material);
      assertTrue(AlpineSigningService.verify(
          "index".getBytes(StandardCharsets.US_ASCII), signature.bytes(),
          material.publicKey(), material.type()));
      assertFalse(AlpineSigningService.verify(
          "tampered".getBytes(StandardCharsets.US_ASCII), signature.bytes(),
          material.publicKey(), material.type()));
    } finally {
      Files.deleteIfExists(file);
    }

    assertThrows(IllegalArgumentException.class, () -> service.sign(new byte[0], material));
    assertThrows(IllegalArgumentException.class, () -> service.sign((java.nio.file.Path) null, material));
    assertThrows(IllegalArgumentException.class, () -> AlpineSigningService.parsePublic("bad"));
  }

  @Test
  void activeKeyIsCreatedFromRepositoryDefaultsWhenMissing() {
    AtomicReference<AlpineRegistryDao.SigningKey> stored = new AtomicReference<>();
    AlpineRegistryDao registry = registry(stored);
    AlpineRepositorySettings settings = mock(AlpineRepositorySettings.class);
    when(settings.get(any())).thenReturn(new AlpineRepositorySettings.Settings(
        List.of(), List.of(), List.of(), true, true, true, "generated.rsa.pub", "RSA256",
        "test", List.of()));
    AlpineSigningService service =
        new AlpineSigningService(registry, new AlpineLeaseManager(registry), settings);

    AlpineSigningService.SigningMaterial material = service.active(runtime());

    assertEquals(1, material.revision());
    assertEquals("generated.rsa.pub", material.keyFilename());
    assertEquals(AlpineSignature.Type.RSA256, material.type());
    assertFalse(stored.get().encryptedPrivateKey().contains("BEGIN PRIVATE KEY"));
  }

  @Test
  void wrapsSigningKeyAndPemFailuresWithStableErrors() throws Exception {
    AlpineRegistryDao registry = registry(new AtomicReference<>());
    AlpineSigningService service = new AlpineSigningService(
        registry, new AlpineLeaseManager(registry), mock(AlpineRepositorySettings.class));
    KeyPair pair = rsa();
    PrivateKey invalidPrivate = new PrivateKey() {
      @Override
      public String getAlgorithm() {
        return "RSA";
      }

      @Override
      public String getFormat() {
        return "PKCS#8";
      }

      @Override
      public byte[] getEncoded() {
        return new byte[] {1};
      }
    };
    PublicKey invalidPublic = new PublicKey() {
      @Override
      public String getAlgorithm() {
        return "RSA";
      }

      @Override
      public String getFormat() {
        return "X.509";
      }

      @Override
      public byte[] getEncoded() {
        return new byte[] {1};
      }
    };
    AlpineSigningService.SigningMaterial invalidMaterial =
        new AlpineSigningService.SigningMaterial(
            1, "fixture.rsa.pub", "fingerprint", "public", invalidPrivate,
            pair.getPublic(), AlpineSignature.Type.RSA256);
    assertThrows(IllegalStateException.class,
        () -> service.sign("payload".getBytes(StandardCharsets.US_ASCII), invalidMaterial));
    assertThrows(IllegalArgumentException.class, () -> AlpineSigningService.verify(
        new byte[] {1}, new byte[] {2}, invalidPublic, AlpineSignature.Type.RSA256));

    AlpineSigningService.SigningMaterial validMaterial = serviceMaterial(pair);
    var directory = Files.createTempDirectory("alpine-signing-directory-");
    try {
      assertThrows(IllegalStateException.class, () -> service.sign(directory, validMaterial));
    } finally {
      Files.deleteIfExists(directory);
    }

    assertThrows(IllegalArgumentException.class, () -> AlpineSigningService.parsePublic(
        "-----BEGIN PUBLIC KEY-----\nAQ==\n-----END PUBLIC KEY-----"));
    assertThrows(IllegalArgumentException.class, () -> AlpineSigningService.parsePublic(
        "-----BEGIN PUBLIC KEY-----\n***\n-----END PUBLIC KEY-----"));
    assertThrows(IllegalArgumentException.class, () -> service.rotate(
        runtime(), "-----BEGIN PRIVATE KEY-----\nAQ==\n-----END PRIVATE KEY-----",
        "fixture.rsa.pub", "RSA"));

    var derive = AlpineSigningService.class.getDeclaredMethod("derivePublic", PrivateKey.class);
    derive.setAccessible(true);
    InvocationTargetException failure = assertThrows(
        InvocationTargetException.class, () -> derive.invoke(null, invalidPrivate));
    assertTrue(failure.getCause() instanceof IllegalArgumentException);
  }

  @Test
  void concurrentGeneratedKeyCreationRecoversTheWinningRow() {
    AtomicReference<AlpineRegistryDao.SigningKey> seed = new AtomicReference<>();
    AlpineRegistryDao seedRegistry = registry(seed);
    AlpineSigningService seedService = new AlpineSigningService(
        seedRegistry, new AlpineLeaseManager(seedRegistry), mock(AlpineRepositorySettings.class));
    AlpineRegistryDao.SigningKey winner = seedService.rotateGenerated(
        runtime(), "winner.rsa.pub", "RSA256");

    AlpineRegistryDao race = registry(new AtomicReference<>());
    when(race.findActiveSigningKey(anyLong()))
        .thenReturn(Optional.empty(), Optional.empty(), Optional.empty(), Optional.of(winner));
    doAnswer(invocation -> {
      throw new org.springframework.dao.DuplicateKeyException("fixture race");
    }).when(race).insertSigningKey(any());
    AlpineRepositorySettings settings = mock(AlpineRepositorySettings.class);
    when(settings.get(any())).thenReturn(new AlpineRepositorySettings.Settings(
        List.of(), List.of(), List.of(), true, true, true, "loser.rsa.pub", "RSA256",
        "test", List.of()));
    AlpineSigningService service =
        new AlpineSigningService(race, new AlpineLeaseManager(race), settings);

    assertEquals("winner.rsa.pub", service.active(runtime()).keyFilename());
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

  private static KeyPair rsa() throws Exception {
    KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
    generator.initialize(2048);
    return generator.generateKeyPair();
  }

  private static String pem(String type, byte[] encoded) {
    String body = Base64.getMimeEncoder(64, new byte[] {'\n'}).encodeToString(encoded);
    return "-----BEGIN " + type + "-----\n" + body + "\n-----END " + type + "-----\n";
  }

  private static AlpineSigningService.SigningMaterial serviceMaterial(KeyPair pair) {
    return new AlpineSigningService.SigningMaterial(
        1, "fixture.rsa.pub", "fingerprint", publicPem(pair), pair.getPrivate(), pair.getPublic(),
        AlpineSignature.Type.RSA256);
  }

  private static String publicPem(KeyPair pair) {
    return pem("PUBLIC KEY", pair.getPublic().getEncoded());
  }
}
