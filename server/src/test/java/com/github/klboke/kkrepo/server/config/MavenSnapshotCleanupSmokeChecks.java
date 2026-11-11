package com.github.klboke.kkrepo.server.config;

import static org.junit.jupiter.api.Assertions.*;
import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.persistence.jdbc.api.*;
import com.github.klboke.kkrepo.server.cleanup.*;
import com.github.klboke.kkrepo.server.maven.*;
import com.github.klboke.kkrepo.server.maven.MavenExceptions.MavenNotFoundException;
import com.github.klboke.kkrepo.server.repositories.RepositoryService;
import com.github.klboke.kkrepo.server.repositories.RepositoryCommands.*;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.context.ConfigurableApplicationContext;

/** Real Spring transactions, live projection/backfill, queued worker and sibling-node cache checks. */
final class MavenSnapshotCleanupSmokeChecks {
  static void exercise(ConfigurableApplicationContext first, ConfigurableApplicationContext second,
      java.util.function.LongConsumer prepareLegacyInventory) throws Exception {
    var repo = first.getBean(RepositoryService.class).create(new CreateCommand(
        "snapshot-cleanup", "maven2-hosted", true, "smoke-file", true,
        new HostedSettings("ALLOW", "SNAPSHOT", "STRICT"), null, null, null, null, null));
    var runtime = first.getBean(RepositoryRuntimeRegistry.class).resolve(repo.name()).orElseThrow();
    var maven = first.getBean(MavenHostedService.class);
    String root = "com/example/demo/1.0-SNAPSHOT/";
    for (int build = 1; build <= 6; build++) {
      for (String suffix : List.of(".jar", ".pom", ".jar.asc", ".jar.sha1")) {
        String name = "demo-1.0-20260101.120000-" + build + suffix;
        String content = suffix.equals(".pom") ? "<project><modelVersion>4.0.0</modelVersion><groupId>com.example</groupId><artifactId>demo</artifactId><version>1.0-SNAPSHOT</version></project>" : name;
        put(maven, runtime, root + name, content);
      }
    }
    // A classifier last published by an older build must remain resolvable.
    put(maven, runtime, root + "demo-1.0-20260101.120000-1-sources.jar", "old-sources");
    String xml = """
        <metadata><groupId>com.example</groupId><artifactId>demo</artifactId><version>1.0-SNAPSHOT</version>
        <versioning><snapshot><timestamp>20260101.120000</timestamp><buildNumber>6</buildNumber></snapshot>
        <snapshotVersions><snapshotVersion><extension>jar</extension><value>1.0-20260101.120000-6</value></snapshotVersion>
        <snapshotVersion><extension>jar</extension><classifier>sources</classifier><value>1.0-20260101.120000-1</value></snapshotVersion>
        </snapshotVersions></versioning></metadata>
        """;
    put(maven, runtime, root + "maven-metadata.xml", xml);
    put(maven, runtime, root + "maven-metadata.xml.sha1", "metadata-checksum");
    var snapshots = second.getBean(MavenSnapshotDao.class);
    assertEquals(6, snapshots.page(repo.id(), 0, 100).size(), "Native writes must index atomically");
    assertEquals(25, snapshots.page(repo.id(), 0, 100).stream().mapToInt(MavenSnapshotDao.Build::assetCount).sum());
    // The database bootstrap fixture recreates assets imported before the inventory existed.
    prepareLegacyInventory.accept(repo.id());
    var cursors = first.getBean(MaintenanceCursorDao.class);
    cursors.ensureCursor(MavenSnapshotBackfillWorker.CURSOR);
    cursors.ensureCursor(MavenSnapshotBackfillWorker.READY);
    new org.springframework.transaction.support.TransactionTemplate(first.getBean(
        org.springframework.transaction.PlatformTransactionManager.class)).executeWithoutResult(status -> {
      cursors.updateLastSeenId(MavenSnapshotBackfillWorker.CURSOR, 0);
      cursors.updateLastSeenId(MavenSnapshotBackfillWorker.READY, 0);
    });
    var repository = first.getBean(RepositoryDao.class).findById(repo.id()).orElseThrow();
    var rules = Map.<String, Object>of("mode", "MAVEN_SNAPSHOT_BUILDS", "snapshot",
        Map.of("retainCount", 2, "publishedOlderThanDays", 10));
    var policies = first.getBean(CleanupPolicyService.class);
    var policy = policies.create(new CleanupPolicyService.PolicyCommand("snapshot-cleanup", RepositoryFormat.MAVEN2,
        "snapshot smoke", rules, List.of(repo.id()), 2, 10, null, null)).policy();
    assertEquals("SNAPSHOT_INDEX_WARMING_UP", first.getBean(CleanupSubjectScanner.class)
        .scan(repository, rules, 100, Instant.now()).safetyStatus());
    for (int batch = 0; batch < 100 && cursors.lastSeenId(MavenSnapshotBackfillWorker.READY) != 1; batch++) {
      (batch % 2 == 0 ? first : second).getBean(MavenSnapshotBackfillWorker.class).runBatch();
    }
    assertEquals(1, cursors.lastSeenId(MavenSnapshotBackfillWorker.READY));
    assertEquals(6, snapshots.page(repo.id(), 0, 100).size());
    assertEquals(25, snapshots.page(repo.id(), 0, 100).stream().mapToInt(MavenSnapshotDao.Build::assetCount).sum());
    var otherRuntime = second.getBean(RepositoryRuntimeRegistry.class).resolve(repo.name()).orElseThrow();
    var otherMaven = second.getBean(MavenHostedService.class);
    assertEquals("demo-1.0-20260101.120000-2.jar", get(otherMaven, otherRuntime, root + "demo-1.0-20260101.120000-2.jar"));
    // Cleanup wins the family lock, then an overwrite must read fresh state and recreate the
    // artifact successfully, rather than updating a deleted ID or failing the browse FK.
    verifyUploadAfterConcurrentCleanup(first, second, runtime, otherRuntime, root, 2,
        "demo-1.0-20260101.120000-2.jar", "overwritten-history");
    var runs = second.getBean(CleanupRunService.class);
    var preview = run(runs, policy.id(), policy.revision(), "TRY_RUN", 100);
    assertEquals(2, preview.run().scannedSubjects());
    assertEquals(1, preview.run().matchedSubjects(), preview.toString());
    long deleted = 0;
    for (int iteration = 0; iteration < 3; iteration++) {
      var execution = run(runs, policy.id(), policy.revision(), "EXECUTE", null);
      assertTrue(List.of("SUCCEEDED", "SUCCEEDED_TRUNCATED").contains(execution.run().state()), execution.toString());
      deleted += execution.run().deletedSubjects();
    }
    assertEquals(2, deleted, "Cursor must resume within the same base version across runs");
    assertEquals(List.of(1, 2, 5, 6), snapshots.page(repo.id(), 0, 100).stream().map(MavenSnapshotDao.Build::buildNumber).sorted().toList());
    for (int build : List.of(3, 4)) {
      for (String suffix : List.of(".jar", ".pom", ".jar.asc", ".jar.sha1")) {
        var path = otherMaven.parser().parsePath(root + "demo-1.0-20260101.120000-" + build + suffix);
        assertThrows(MavenNotFoundException.class, () -> otherMaven.get(otherRuntime, path, false));
      }
    }
    assertEquals("overwritten-history", get(otherMaven, otherRuntime, root + "demo-1.0-20260101.120000-2.jar"));
    assertEquals(xml, get(otherMaven, otherRuntime, root + "maven-metadata.xml"));
    assertEquals("metadata-checksum", get(otherMaven, otherRuntime, root + "maven-metadata.xml.sha1"));
    assertEquals("old-sources", get(otherMaven, otherRuntime, root + "demo-1.0-20260101.120000-1-sources.jar"));
    assertEquals("demo-1.0-20260101.120000-6.jar", get(otherMaven, otherRuntime, root + "demo-1.0-20260101.120000-6.jar"));

    // Checksums do not upsert/lock a component, so they need the same early family lock.
    var checksumRepo = first.getBean(RepositoryService.class).create(new CreateCommand(
        "snapshot-checksum-race", "maven2-hosted", true, "smoke-file", true,
        new HostedSettings("ALLOW", "SNAPSHOT", "STRICT"), null, null, null, null, null));
    var checksumRuntime = first.getBean(RepositoryRuntimeRegistry.class).resolve(checksumRepo.name()).orElseThrow();
    var otherChecksumRuntime = second.getBean(RepositoryRuntimeRegistry.class).resolve(checksumRepo.name()).orElseThrow();
    put(maven, checksumRuntime, root + "demo-1.0-20260101.120000-1.jar", "first");
    put(maven, checksumRuntime, root + "demo-1.0-20260101.120000-1.jar.sha1", "old-checksum");
    put(maven, checksumRuntime, root + "demo-1.0-20260101.120000-2.jar", "second");
    verifyUploadAfterConcurrentCleanup(first, second, checksumRuntime, otherChecksumRuntime, root, 1,
        "demo-1.0-20260101.120000-1.jar.sha1", "1111111111111111111111111111111111111111");
  }

  private static void verifyUploadAfterConcurrentCleanup(ConfigurableApplicationContext first,
      ConfigurableApplicationContext second, RepositoryRuntime runtime, RepositoryRuntime otherRuntime,
      String root, int buildNumber, String uploadName, String uploadContent) throws Exception {
    var writerAtFamily = new java.util.concurrent.CountDownLatch(1);
    var original = second.getBean(MavenSnapshotAssetObserver.class);
    var observer = org.mockito.Mockito.spy(original);
    org.mockito.Mockito.doAnswer(invocation -> {
      if (invocation.<String>getArgument(2).endsWith("/" + uploadName)) writerAtFamily.countDown();
      return invocation.callRealMethod();
    }).when(observer).beforeWrite(org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyString());
    var factory = new org.springframework.beans.factory.support.DefaultListableBeanFactory();
    factory.registerSingleton("snapshotObserver", observer);
    Object writer = org.springframework.test.util.AopTestUtils.getTargetObject(second.getBean(AssetDao.class));
    Object prior = org.springframework.test.util.ReflectionTestUtils.getField(writer, "mutationObservers");
    org.springframework.test.util.ReflectionTestUtils.setField(writer, "mutationObservers", factory.getBeanProvider(AssetMutationObserver.class));
    var snapshots = first.getBean(MavenSnapshotDao.class);
    var build = snapshots.page(runtime.id(), 0, 100).stream().filter(b -> b.buildNumber() == buildNumber).findFirst().orElseThrow();
    var family = snapshots.findFamily(build.familyId()).orElseThrow();
    var tx = new org.springframework.transaction.support.TransactionTemplate(first.getBean(org.springframework.transaction.PlatformTransactionManager.class));
    var upload = new java.util.concurrent.atomic.AtomicReference<java.util.concurrent.Future<?>>();
    try (var executor = java.util.concurrent.Executors.newSingleThreadExecutor()) {
      tx.executeWithoutResult(status -> {
        snapshots.lockFamily(runtime.id(), family.path(), family.groupId(), family.artifactId(), family.baseVersion());
        upload.set(executor.submit(() -> {
          try { put(second.getBean(MavenHostedService.class), otherRuntime, root + uploadName, uploadContent); }
          catch (Exception e) { throw new IllegalStateException(e); }
        }));
        try { assertTrue(writerAtFamily.await(10, java.util.concurrent.TimeUnit.SECONDS)); }
        catch (InterruptedException e) { throw new IllegalStateException(e); }
        assertFalse(upload.get().isDone(), "Upload must wait before reading the historical asset ID");
        assertEquals(build.assetCount(), first.getBean(MavenSnapshotDeletionService.class).delete(runtime.name(), build.id()));
      });
      upload.get().get(10, java.util.concurrent.TimeUnit.SECONDS);
      assertTrue(snapshots.findBuild(build.id()).isEmpty());
      var replacement = snapshots.page(runtime.id(), 0, 100).stream().filter(b -> b.buildNumber() == buildNumber).findFirst().orElseThrow();
      assertEquals(1, replacement.assetCount(), "The later upload recreates only its newly published file");
      assertEquals(uploadContent, get(second.getBean(MavenHostedService.class), otherRuntime, root + uploadName));
    } finally {
      org.springframework.test.util.ReflectionTestUtils.setField(writer, "mutationObservers", prior);
    }
  }

  private static CleanupRunService.RunView run(CleanupRunService runs, long policy, long revision, String mode, Integer limit) throws Exception {
    var started = runs.startManual(policy, new CleanupRunService.RunCommand(mode, revision, limit), "snapshot-smoke");
    long deadline = System.nanoTime() + java.time.Duration.ofSeconds(30).toNanos();
    while (System.nanoTime() < deadline) {
      var result = runs.getRun(started.run().id());
      if (List.of("SUCCEEDED", "SUCCEEDED_TRUNCATED", "PARTIAL", "PARTIAL_LIMIT_REACHED", "FAILED", "CANCELLED").contains(result.run().state())) return result;
      Thread.sleep(50);
    }
    throw new AssertionError("Snapshot cleanup did not finish");
  }
  private static void put(MavenHostedService maven, RepositoryRuntime runtime, String path, String content) throws Exception {
    assertEquals(201, maven.put(runtime, maven.parser().parsePath(path),
        new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8)), "application/octet-stream", "admin", "127.0.0.1").status());
  }
  private static String get(MavenHostedService maven, RepositoryRuntime runtime, String path) throws Exception {
    try (var body = maven.get(runtime, maven.parser().parsePath(path), false).body()) {
      return new String(body.readAllBytes(), StandardCharsets.UTF_8);
    }
  }
}
