package com.github.klboke.kkrepo.server.arch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.core.RepositoryType;
import com.github.klboke.kkrepo.persistence.jdbc.api.ArchSigningKeyDao;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntime;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import org.bouncycastle.openpgp.PGPPublicKeyRingCollection;
import org.bouncycastle.openpgp.PGPSignature;
import org.bouncycastle.openpgp.PGPSignatureList;
import org.bouncycastle.openpgp.PGPUtil;
import org.bouncycastle.openpgp.operator.jcajce.JcaKeyFingerprintCalculator;
import org.bouncycastle.openpgp.operator.jcajce.JcaPGPContentVerifierBuilderProvider;
import org.junit.jupiter.api.Test;

class ArchSigningServiceTest {
  @Test
  void generatesRotatesAndSignsDatabases() throws Exception {
    InMemoryKeys keys = new InMemoryKeys();
    ArchSigningService service = new ArchSigningService(keys);
    RepositoryRuntime runtime = runtime();

    assertTrue(service.active(runtime).isEmpty());
    assertThrows(com.github.klboke.kkrepo.server.maven.MavenExceptions.MavenNotFoundException.class,
        () -> service.publicKeyArmor(runtime));

    ArchSigningKeyDao.SigningKey first = service.rotateGenerated(runtime);
    assertEquals(1, first.revision());
    assertTrue(first.publicKey().contains("BEGIN PGP PUBLIC KEY BLOCK"));
    assertTrue(service.active(runtime).isPresent());

    byte[] database = "generated pacman database".getBytes(StandardCharsets.UTF_8);
    byte[] signature = service.signIfConfigured(runtime, database, Instant.EPOCH).orElseThrow();
    assertTrue(new String(signature, StandardCharsets.UTF_8).contains("BEGIN PGP SIGNATURE"));
    verify(database, signature, first.publicKey());

    ArchSigningKeyDao.SigningKey second = service.rotateGenerated(runtime);
    assertEquals(2, second.revision());
    assertNotEquals(first.fingerprint(), second.fingerprint());
    assertEquals(second.fingerprint(), service.active(runtime).orElseThrow().fingerprint());
    assertEquals(2, service.retained(runtime).size());
    assertTrue(service.publicKeyArmor(runtime).contains(first.publicKey().split("\n")[1]));
  }

  @Test
  void rejectsMalformedKeys() {
    ArchSigningService service = new ArchSigningService(new InMemoryKeys());
    assertThrows(IllegalArgumentException.class,
        () -> service.rotate(runtime(), " ", null));
    assertThrows(IllegalArgumentException.class,
        () -> service.rotate(runtime(), "not a key", ""));
  }

  private static void verify(byte[] data, byte[] signature, String publicArmor) throws Exception {
    PGPPublicKeyRingCollection keys = new PGPPublicKeyRingCollection(
        PGPUtil.getDecoderStream(new ByteArrayInputStream(
            publicArmor.getBytes(StandardCharsets.UTF_8))),
        new JcaKeyFingerprintCalculator());
    PGPSignatureList signatures = (PGPSignatureList) new org.bouncycastle.openpgp.PGPObjectFactory(
        PGPUtil.getDecoderStream(new ByteArrayInputStream(signature)),
        new JcaKeyFingerprintCalculator()).nextObject();
    PGPSignature parsed = signatures.get(0);
    parsed.init(new JcaPGPContentVerifierBuilderProvider(), keys.getPublicKey(parsed.getKeyID()));
    parsed.update(data);
    assertTrue(parsed.verify());
  }

  private static RepositoryRuntime runtime() {
    return new RepositoryRuntime(
        7L, "arch-hosted", RepositoryFormat.ARCH, RepositoryType.HOSTED, "arch-hosted", true,
        1L, "ALLOW", null, null, true, null, null, null, null, null, List.of());
  }

  private static final class InMemoryKeys implements ArchSigningKeyDao {
    private final List<SigningKey> rows = new ArrayList<>();

    @Override
    public Optional<SigningKey> findActiveSigningKey(long repositoryId) {
      return rows.stream().filter(key -> key.repositoryId() == repositoryId && key.active())
          .max(Comparator.comparingInt(SigningKey::revision));
    }

    @Override
    public Optional<SigningKey> findSigningKey(long repositoryId, int revision) {
      return rows.stream()
          .filter(key -> key.repositoryId() == repositoryId && key.revision() == revision)
          .findFirst();
    }

    @Override
    public List<SigningKey> listSigningKeys(long repositoryId, int limit) {
      return rows.stream().filter(key -> key.repositoryId() == repositoryId)
          .sorted(Comparator.comparingInt(SigningKey::revision).reversed())
          .limit(Math.max(1, Math.min(limit, 16))).toList();
    }

    @Override
    public int nextRevisionLocked(long repositoryId) {
      return rows.stream().filter(key -> key.repositoryId() == repositoryId)
          .mapToInt(SigningKey::revision).max().orElse(0) + 1;
    }

    @Override
    public void insertSigningKey(SigningKey key) {
      for (int index = 0; index < rows.size(); index++) {
        SigningKey existing = rows.get(index);
        if (existing.repositoryId() == key.repositoryId()) {
          rows.set(index, new SigningKey(existing.repositoryId(), existing.revision(),
              existing.keyId(), existing.fingerprint(), existing.encryptedPrivateKey(),
              existing.publicKey(), false, existing.createdAt()));
        }
      }
      rows.add(key);
    }
  }
}
