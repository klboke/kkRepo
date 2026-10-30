package com.github.klboke.kkrepo.server.apt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.core.RepositoryType;
import com.github.klboke.kkrepo.persistence.jdbc.api.AptRegistryDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.RepositoryDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.RepositoryRecord;
import com.github.klboke.kkrepo.protocol.apt.AptRelease;
import com.github.klboke.kkrepo.server.coordination.FencedLeaseManager;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntime;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntimeRegistry;
import com.github.klboke.kkrepo.server.raw.RawProxyService;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.bouncycastle.openpgp.PGPObjectFactory;
import org.bouncycastle.openpgp.PGPPublicKeyRingCollection;
import org.bouncycastle.openpgp.PGPSignatureList;
import org.bouncycastle.openpgp.PGPUtil;
import org.bouncycastle.openpgp.operator.jcajce.JcaKeyFingerprintCalculator;
import org.bouncycastle.openpgp.operator.jcajce.JcaPGPContentVerifierBuilderProvider;
import org.junit.jupiter.api.Test;

class AptMetadataRefreshWorkerTest {
  private static final Instant NOW = Instant.parse("2026-10-30T14:12:41Z");

  @Test
  void refreshesExpiredSignedMetadataWithoutPackageOrConfigurationChanges() throws Exception {
    Fixture fixture = new Fixture(30, NOW.minus(Duration.ofDays(31)));
    AptAssetSupport assets = mock(AptAssetSupport.class);
    Map<String, byte[]> stored = new HashMap<>();
    AtomicBoolean failNextWrite = new AtomicBoolean();
    doAnswer(call -> {
      if (failNextWrite.getAndSet(false)) throw new IllegalStateException("storage unavailable");
      stored.put(call.getArgument(1), call.getArgument(2));
      return null;
    }).when(assets).storeGenerated(any(), anyString(), any(), anyString(), any());
    doAnswer(call -> {
      stored.put(call.getArgument(1), Files.readAllBytes((Path) call.getArgument(2)));
      return null;
    }).when(assets).storeGeneratedFile(any(), anyString(), any(), anyString(), any());
    AtomicReference<AptRegistryDao.SigningKey> key = new AtomicReference<>();
    when(fixture.registry.findActiveSigningKey(1)).thenAnswer(call -> Optional.ofNullable(key.get()));
    doAnswer(call -> {
      key.set(call.getArgument(0));
      return null;
    }).when(fixture.registry).insertSigningKey(any());
    when(fixture.leases.acquire(anyString())).thenReturn(fixture.lease);
    AptSigningService signing = new AptSigningService(fixture.registry, fixture.leases);
    AptMetadataBuilder builder = new AptMetadataBuilder(fixture.registry, assets, signing);
    AptService service = new AptService(
        fixture.registry, new AptPublishedSnapshotCache(fixture.registry), fixture.settings,
        new AptDebPackageInspector(), new AptComponentFactory(), assets, builder, signing,
        fixture.leases, mock(RawProxyService.class), mock(AptProxyProjectionService.class));

    // Build the actual expired Release/InRelease, rather than waiting a month or only aging
    // a mocked lastPublishedAt. This follows the same publication path as an idle migration.
    var old = builder.build(fixture.runtime, fixture.settings.get(fixture.runtime),
        fixture.state.get(), signing.active(fixture.runtime));
    fixture.snapshot.set(new AptRegistryDao.Snapshot(
        1, "stable", 1, old.signingKeyRevision(), old.manifest(), old.releaseSha256(), old.createdAt()));
    AptRelease expired = release(stored, fixture.snapshot.get());
    assertTrue(date(expired, "Valid-Until").isBefore(NOW));
    // The original publisher has nothing to do when desired == published (the regression).
    assertTrue(service.publishPendingIfAvailable(fixture.runtime, "stable"));
    assertEquals(old.createdAt(), fixture.snapshot.get().createdAt());

    fixture.worker().refreshExpiringMetadata(NOW);
    // Another replica observes the same durable pending revision and does not bump it again.
    fixture.worker().refreshExpiringMetadata(NOW);
    assertEquals(2, fixture.state.get().desiredRevision());
    assertEquals(1, fixture.state.get().publishedRevision());
    when(fixture.registry.publishSnapshot(any(), any(), anyLong())).thenAnswer(call -> {
      AptRegistryDao.Snapshot next = call.getArgument(0);
      fixture.snapshot.set(next);
      fixture.state.set(state("stable", next.revision(), next.revision(), next.createdAt()));
      return true;
    });
    failNextWrite.set(true);
    assertThrows(IllegalStateException.class,
        () -> service.publishPendingIfAvailable(fixture.runtime, "stable"));
    verify(fixture.registry).recordBuildFailure(
        eq(1L), eq("stable"), eq(2L), eq("storage unavailable"), any());
    assertEquals(1, fixture.snapshot.get().revision(), "failure must retain the complete old snapshot");
    fixture.worker().refreshExpiringMetadata(NOW);
    assertEquals(2, fixture.state.get().desiredRevision(), "retry must keep the durable revision");
    assertTrue(service.publishPendingIfAvailable(fixture.runtime, "stable"));
    AptRegistryDao.Snapshot refreshed = fixture.snapshot.get();
    AptRelease release = release(stored, refreshed);
    assertEquals(NOW, date(release, "Date"));
    assertEquals(NOW.plus(Duration.ofDays(30)), date(release, "Valid-Until"));
    assertEquals(expired.checksums(), release.checksums(), "unchanged package indices");
    String inRelease = new String(stored.get(refreshed.manifest().get("dists/stable/InRelease")),
        StandardCharsets.UTF_8);
    assertTrue(inRelease.contains("Valid-Until: " + release.fields().get("Valid-Until")));
    assertTrue(inRelease.contains("BEGIN PGP SIGNATURE"));
    verifySignature(stored.get(refreshed.manifest().get("dists/stable/Release")),
        stored.get(refreshed.manifest().get("dists/stable/Release.gpg")), key.get().publicKey());
    fixture.worker().refreshExpiringMetadata(NOW);
    verify(fixture.registry, times(1)).markSuiteDirty(1, "stable", NOW);
  }

  @Test
  void queuesAtHalfLifeIncludingOneDayValidityButNotBefore() {
    for (int days : List.of(1, 7, 30, 3650)) {
      Instant boundary = NOW.minus(Duration.ofDays(days).dividedBy(2));
      Fixture fresh = new Fixture(days, boundary.plusSeconds(1));
      fresh.worker().refreshExpiringMetadata(NOW);
      verify(fresh.leases, never()).tryAcquire(anyString());
      Fixture due = new Fixture(days, boundary);
      due.worker().refreshExpiringMetadata(NOW);
      verify(due.registry).markSuiteDirty(1, "stable", NOW);
    }
  }

  @Test
  void skipsDisabledExpirationUnpublishedAndAlreadyPendingSuites() {
    for (Integer days : new Integer[] {null, 0}) {
      Fixture fixture = new Fixture(days, Instant.EPOCH);
      fixture.worker().refreshExpiringMetadata(NOW);
      verify(fixture.registry, never()).listSuites(anyLong());
    }
    Fixture fixture = new Fixture(30, Instant.EPOCH);
    fixture.state.set(state("stable", 1, 0, Instant.EPOCH));
    fixture.worker().refreshExpiringMetadata(NOW);
    fixture.state.set(state("stable", 2, 1, Instant.EPOCH));
    fixture.worker().refreshExpiringMetadata(NOW);
    verify(fixture.leases, never()).tryAcquire(anyString());
    new AptMetadataRefreshWorker(fixture.repositories, fixture.runtimes, fixture.settings,
        fixture.registry, fixture.leases, false).refreshExpiringMetadata();
    verify(fixture.repositories, times(2)).list();
  }

  @Test
  void rechecksDurableStateAfterAcquiringLeaseAndSkipsBusySuites() {
    Fixture fixture = new Fixture(30, Instant.EPOCH);
    when(fixture.leases.tryAcquire(anyString())).thenReturn(Optional.empty());
    fixture.worker().refreshExpiringMetadata(NOW);
    verify(fixture.registry, never()).markSuiteDirty(anyLong(), anyString(), any());
    when(fixture.leases.tryAcquire(anyString())).thenAnswer(call -> {
      fixture.state.set(state("stable", 2, 2, NOW));
      return Optional.of(fixture.lease);
    });
    fixture.worker().refreshExpiringMetadata(NOW);
    verify(fixture.registry, never()).markSuiteDirty(anyLong(), anyString(), any());
    verify(fixture.lease).close();
  }

  @Test
  void checksSnapshotDateAndDoesNotCreateWorkForMissingSnapshots() {
    Fixture fixture = new Fixture(30, Instant.EPOCH);
    fixture.snapshot.set(null);
    fixture.worker().refreshExpiringMetadata(NOW);
    fixture.snapshot.set(new AptRegistryDao.Snapshot(1, "stable", 1, 1, Map.of(), "hash", NOW));
    fixture.worker().refreshExpiringMetadata(NOW);
    verify(fixture.registry, never()).markSuiteDirty(anyLong(), anyString(), any());
  }

  @Test
  void skipsOfflineProxyGroupAndNonAptRepositoriesAndRechecksRuntime() {
    Fixture fixture = new Fixture(30, Instant.EPOCH);
    when(fixture.repositories.list()).thenReturn(List.of(
        repository(RepositoryFormat.APT, RepositoryType.HOSTED, false),
        repository(RepositoryFormat.APT, RepositoryType.PROXY, true),
        repository(RepositoryFormat.APT, RepositoryType.GROUP, true),
        repository(RepositoryFormat.RAW, RepositoryType.HOSTED, true)));
    fixture.worker().refreshExpiringMetadata(NOW);
    verify(fixture.runtimes, never()).resolveFreshById(anyLong());
    when(fixture.repositories.list()).thenReturn(List.of(
        repository(RepositoryFormat.APT, RepositoryType.HOSTED, true)));
    when(fixture.runtimes.resolveFreshById(1)).thenReturn(Optional.empty());
    fixture.worker().refreshExpiringMetadata(NOW);
    RepositoryRuntime offline = mock(RepositoryRuntime.class);
    when(fixture.runtimes.resolveFreshById(1)).thenReturn(Optional.of(offline));
    fixture.worker().refreshExpiringMetadata(NOW);
    verify(fixture.registry, never()).listSuites(anyLong());
  }

  @Test
  void leaseLossDoesNotDirtySuiteAndOtherSuitesStillRefresh() {
    Fixture fixture = new Fixture(30, Instant.EPOCH);
    FencedLeaseManager.Lease lost = mock(FencedLeaseManager.Lease.class);
    doThrow(new IllegalStateException("lease lost")).when(lost).assertHeld();
    when(fixture.registry.listSuites(1)).thenReturn(List.of(
        state("broken", 1, 1, Instant.EPOCH), fixture.state.get()));
    when(fixture.registry.findSuite(1, "broken"))
        .thenReturn(Optional.of(state("broken", 1, 1, Instant.EPOCH)));
    when(fixture.registry.findPublishedSnapshot(1, "broken"))
        .thenReturn(Optional.of(fixture.snapshot.get()));
    when(fixture.leases.tryAcquire("apt:publish:1:broken")).thenReturn(Optional.of(lost));
    fixture.worker().refreshExpiringMetadata(NOW);
    verify(fixture.registry, never()).markSuiteDirty(eq(1L), eq("broken"), any());
    verify(fixture.registry).markSuiteDirty(1, "stable", NOW);
    verify(lost).close();
  }

  private static final class Fixture {
    final RepositoryDao repositories = mock(RepositoryDao.class);
    final RepositoryRuntimeRegistry runtimes = mock(RepositoryRuntimeRegistry.class);
    final AptRepositorySettings settings = mock(AptRepositorySettings.class);
    final AptRegistryDao registry = mock(AptRegistryDao.class);
    final AptLeaseManager leases = mock(AptLeaseManager.class);
    final FencedLeaseManager.Lease lease = mock(FencedLeaseManager.Lease.class);
    final RepositoryRuntime runtime = new RepositoryRuntime(
        1, "apt", RepositoryFormat.APT, RepositoryType.HOSTED, "apt-hosted", true, 1L,
        "ALLOW", null, null, true, null, null, null, null, null, List.of());
    final AtomicReference<AptRegistryDao.SuiteState> state;
    final AtomicReference<AptRegistryDao.Snapshot> snapshot;

    Fixture(Integer validDays, Instant createdAt) {
      state = new AtomicReference<>(state("stable", 1, 1, createdAt));
      snapshot = new AtomicReference<>(new AptRegistryDao.Snapshot(
          1, "stable", 1, 1, Map.of(), "hash", createdAt));
      when(repositories.list()).thenReturn(List.of(
          repository(RepositoryFormat.APT, RepositoryType.HOSTED, true)));
      when(runtimes.resolveFreshById(1)).thenReturn(Optional.of(runtime));
      when(settings.get(runtime)).thenReturn(new AptRepositorySettings.Settings(
          "stable", "main", List.of("amd64"), false, true, true, validDays, "kkRepo", "kkRepo"));
      when(registry.listSuites(1)).thenAnswer(call -> List.of(state.get()));
      when(registry.findSuite(1, "stable")).thenAnswer(call -> Optional.of(state.get()));
      when(registry.findPublishedSnapshot(1, "stable"))
          .thenAnswer(call -> Optional.ofNullable(snapshot.get()));
      when(leases.tryAcquire(anyString())).thenReturn(Optional.of(lease));
      when(lease.owner()).thenReturn("owner");
      when(lease.fencingToken()).thenReturn(9L);
      when(registry.markSuiteDirty(eq(1L), eq("stable"), any())).thenAnswer(call -> {
        var previous = state.get();
        state.set(state("stable", previous.desiredRevision() + 1,
            previous.publishedRevision(), call.getArgument(2)));
        return state.get().desiredRevision();
      });
    }

    AptMetadataRefreshWorker worker() {
      return new AptMetadataRefreshWorker(repositories, runtimes, settings, registry, leases, true);
    }
  }

  private static RepositoryRecord repository(RepositoryFormat format, RepositoryType type, boolean online) {
    return new RepositoryRecord(1L, "apt", format, type, "apt-hosted", online,
        1L, null, null, null, null, "ALLOW", true, Map.of());
  }

  private static AptRegistryDao.SuiteState state(
      String distribution, long desired, long published, Instant createdAt) {
    return new AptRegistryDao.SuiteState(1, distribution, desired, createdAt, published,
        1, NOW, null, null, NOW);
  }

  private static AptRelease release(Map<String, byte[]> stored, AptRegistryDao.Snapshot snapshot) {
    return AptRelease.parse(new String(stored.get(snapshot.manifest().get("dists/stable/Release")),
        StandardCharsets.UTF_8));
  }

  private static Instant date(AptRelease release, String field) {
    return ZonedDateTime.parse(release.fields().get(field), DateTimeFormatter.RFC_1123_DATE_TIME)
        .toInstant();
  }

  private static void verifySignature(byte[] release, byte[] signature, String publicKey)
      throws Exception {
    var calculator = new JcaKeyFingerprintCalculator();
    var keys = new PGPPublicKeyRingCollection(PGPUtil.getDecoderStream(
        new ByteArrayInputStream(publicKey.getBytes(StandardCharsets.UTF_8))), calculator);
    var packets = new PGPObjectFactory(PGPUtil.getDecoderStream(
        new ByteArrayInputStream(signature)), calculator);
    var signed = ((PGPSignatureList) packets.nextObject()).get(0);
    signed.init(new JcaPGPContentVerifierBuilderProvider().setProvider(AptCryptoProvider.current()),
        keys.getPublicKey(signed.getKeyID()));
    signed.update(release);
    assertTrue(signed.verify(), "refreshed Release.gpg must verify with the unchanged repository key");
  }
}
