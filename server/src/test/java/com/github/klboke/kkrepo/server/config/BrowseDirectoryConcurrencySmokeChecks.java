package com.github.klboke.kkrepo.server.config;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.klboke.kkrepo.persistence.jdbc.api.AssetDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.BrowseNodeDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.ComponentDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.RepositoryDao;
import com.github.klboke.kkrepo.persistence.jdbc.internal.JdbcBrowseNodeDao;
import com.github.klboke.kkrepo.protocol.npm.NpmPackageId;
import com.github.klboke.kkrepo.server.browse.BrowseDirectoryDeletionService;
import com.github.klboke.kkrepo.server.browse.RepositoryContentDeletionService;
import com.github.klboke.kkrepo.server.cache.AssetMetadataCache;
import com.github.klboke.kkrepo.server.cleanup.CleanupSubjectScanner;
import com.github.klboke.kkrepo.server.maven.BlobStorageRegistry;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntime;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntimeRegistry;
import com.github.klboke.kkrepo.server.npm.NpmAssetWriter;
import com.github.klboke.kkrepo.server.npm.NpmHostedService;
import com.github.klboke.kkrepo.server.raw.RawHostedService;
import com.github.klboke.kkrepo.server.transaction.TransientTransactionRetry;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import org.mockito.AdditionalAnswers;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Real overlapping transactions, run by DatabaseServerSmokeTest on MySQL and PostgreSQL. */
final class BrowseDirectoryConcurrencySmokeChecks {
  private static final ObjectMapper JSON = new ObjectMapper();

  static void exercise(ConfigurableApplicationContext first, ConfigurableApplicationContext second) throws Exception {
    for (String operation : List.of("json", "multipart", "tag")) concurrentPackageMutation(first, second, operation);
    concurrentBrowseInsertion(first, second, false);
    concurrentBrowseInsertion(first, second, true);
  }

  private static void concurrentPackageMutation(ConfigurableApplicationContext first,
      ConfigurableApplicationContext second, String operation) throws Exception {
    String name = "concurrent-" + operation;
    var runtime = first.getBean(RepositoryRuntimeRegistry.class).resolve("directory-npm").orElseThrow();
    var otherRuntime = second.getBean(RepositoryRuntimeRegistry.class).resolve("directory-npm").orElseThrow();
    var initial = first.getBean(NpmHostedService.class);
    publish(initial, runtime, name, "1.0.0");
    var otherAssets = second.getBean(AssetDao.class);
    var root = otherAssets.findAssetByPath(runtime.id(), name).orElseThrow();
    var locking = new CountDownLatch(1);
    var guardedAssets = mock(AssetDao.class, AdditionalAnswers.delegatesTo(otherAssets));
    doAnswer(call -> {
      locking.countDown();
      return otherAssets.findAssetByIdForUpdate(root.id());
    }).when(guardedAssets).findAssetByIdForUpdate(root.id());
    var publisher = new NpmHostedService(guardedAssets, second.getBean(BlobStorageRegistry.class),
        second.getBean(NpmAssetWriter.class), JSON, second.getBean(AssetMetadataCache.class), null,
        second.getBean(TransientTransactionRetry.class));
    var deletion = first.getBean(RepositoryContentDeletionService.class);
    var guardedDeletion = mock(RepositoryContentDeletionService.class, AdditionalAnswers.delegatesTo(deletion));
    byte[] tarball = archive(name, "2.0.0");
    var publishing = new AtomicReference<Future<?>>();
    try (var executor = Executors.newSingleThreadExecutor()) {
      doAnswer(call -> {
        // Directory validation and locks have completed. Start publication now, before deletion.
        publishing.set(executor.submit(() -> {
          if (operation.equals("multipart")) publisher.uploadTarball(otherRuntime,
              new MockMultipartFile("asset", name + "-2.0.0.tgz", "application/octet-stream", tarball), "admin", null);
          else if (operation.equals("tag")) publisher.deleteDistTag(otherRuntime, NpmPackageId.parse(name), "beta", "admin", null);
          else publish(publisher, otherRuntime, name, "2.0.0");
          return null;
        }));
        assertTrue(locking.await(10, TimeUnit.SECONDS), "publisher must lock before reading its packument");
        assertThrows(TimeoutException.class, () -> publishing.get().get(250, TimeUnit.MILLISECONDS));
        return deletion.deleteBatchForCleanup(call.getArgument(0), call.getArgument(1), call.getArgument(2));
      }).when(guardedDeletion).deleteBatchForCleanup(anyString(), anyList(), anyString());
      var service = new BrowseDirectoryDeletionService(first.getBean(RepositoryDao.class),
          first.getBean(BrowseNodeDao.class), first.getBean(AssetDao.class), first.getBean(ComponentDao.class),
          first.getBean(CleanupSubjectScanner.class), guardedDeletion);
      var preview = service.preview("directory-npm", name + "/-");
      var transaction = transaction(first, TransactionDefinition.ISOLATION_READ_COMMITTED);
      transaction.executeWithoutResult(status -> service.delete("directory-npm",
          new BrowseDirectoryDeletionService.Command(preview.path(), preview.path(), preview.selectionToken()), "admin"));
      publishing.get().get(20, TimeUnit.SECONDS);
    }
    var response = BrowseDirectoryDeletionSmokeChecks.request(second, "GET", "/repository/directory-npm/" + name, null, true);
    assertEquals(200, response.statusCode(), response.body());
    var metadata = JSON.readTree(response.body());
    assertEquals(operation.equals("tag") ? List.of() : List.of("2.0.0"),
        metadata.path("versions").propertyStream().map(Map.Entry::getKey).toList(), response.body());
    assertEquals("3", metadata.path("_rev").asText(), "publication must read the post-deletion revision");
    assertEquals(404, BrowseDirectoryDeletionSmokeChecks.request(second, "GET",
        "/repository/directory-npm/" + name + "/-/" + name + "-1.0.0.tgz", null, true).statusCode());
    assertEquals(!operation.equals("tag"), otherAssets.findAssetByPath(runtime.id(), name + "/-/" + name + "-2.0.0.tgz").isPresent());
  }

  private static void concurrentBrowseInsertion(ConfigurableApplicationContext first,
      ConfigurableApplicationContext second, boolean assetParent) throws Exception {
    String directory = "concurrent-index-" + (assetParent ? "bound" : "folder");
    String oldPath = assetParent ? directory : directory + "/old.txt";
    String newPath = directory + "/new.txt";
    var runtime = first.getBean(RepositoryRuntimeRegistry.class).resolve("directory-raw").orElseThrow();
    var otherRuntime = second.getBean(RepositoryRuntimeRegistry.class).resolve("directory-raw").orElseThrow();
    put(first, runtime, oldPath);
    var oldAsset = first.getBean(AssetDao.class).findAssetByPath(runtime.id(), oldPath).orElseThrow();
    var parent = first.getBean(BrowseNodeDao.class).findNode(runtime.id(), directory).orElseThrow();
    var uploaded = new CountDownLatch(1);
    var commitUpload = new CountDownLatch(1);
    var pruning = new CountDownLatch(1);
    JdbcTemplate jdbc = new JdbcTemplate(first.getBean(JdbcTemplate.class).getDataSource()) {
      @Override public <T> List<T> query(String sql, RowMapper<T> mapper, Object... args) {
        if (sql.contains("FROM browse_node WHERE id = ? FOR UPDATE") && args[0].equals(parent.id())) pruning.countDown();
        return super.query(sql, mapper, args);
      }
      @Override public <T> List<T> queryForList(String sql, Class<T> type, Object... args) {
        if (assetParent && sql.contains("WHERE asset_id = ? FOR UPDATE")) pruning.countDown();
        return super.queryForList(sql, type, args);
      }
      @Override public int update(String sql, Object... args) {
        // Also reaches the former unsafe implementation, after it computed stale child counts.
        if (sql.startsWith("DELETE FROM browse_node WHERE id IN")
            || (assetParent && sql.equals("DELETE FROM browse_node WHERE asset_id = ?"))) pruning.countDown();
        return super.update(sql, args);
      }
    };
    try (var executor = Executors.newFixedThreadPool(2)) {
      var upload = executor.submit(() -> transaction(second, TransactionDefinition.ISOLATION_DEFAULT)
          .execute(status -> {
            put(second, otherRuntime, newPath);
            uploaded.countDown();
            await(commitUpload);
            return null;
          }));
      try {
        assertTrue(uploaded.await(10, TimeUnit.SECONDS), "upload holds an uncommitted Browse child");
        var delete = executor.submit(() -> transaction(first, TransactionDefinition.ISOLATION_DEFAULT)
            .execute(status -> {
              // Establish an old MySQL snapshot: only locking reads can observe the later commit.
              jdbc.queryForObject("SELECT COUNT(*) FROM browse_node", Long.class);
              return new JdbcBrowseNodeDao(jdbc).deleteByAssetId(oldAsset.id());
            }));
        try {
          assertTrue(pruning.await(10, TimeUnit.SECONDS), "pruning must reach the contested parent");
          assertThrows(TimeoutException.class, () -> delete.get(250, TimeUnit.MILLISECONDS));
        } finally { commitUpload.countDown(); }
        upload.get(20, TimeUnit.SECONDS);
        assertEquals(1, delete.get(20, TimeUnit.SECONDS));
      } finally { commitUpload.countDown(); }
    }
    var nodes = first.getBean(BrowseNodeDao.class);
    var retainedParent = nodes.findNode(runtime.id(), directory).orElseThrow();
    assertNull(retainedParent.assetId());
    assertTrue(retainedParent.hasAssetSubtree());
    assertTrue(nodes.listChildren(runtime.id(), directory).stream().anyMatch(node -> newPath.equals(node.path()) && node.leaf()));
    assertTrue(first.getBean(AssetDao.class).findAssetByPath(runtime.id(), newPath).isPresent());
    assertEquals("retained", BrowseDirectoryDeletionSmokeChecks.request(first, "GET",
        "/repository/directory-raw/" + newPath, null, true).body());
  }

  private static void put(ConfigurableApplicationContext context, RepositoryRuntime runtime, String path) {
    context.getBean(RawHostedService.class).put(runtime, path,
        new ByteArrayInputStream("retained".getBytes(StandardCharsets.UTF_8)), "text/plain", "admin", null);
  }

  private static void await(CountDownLatch latch) {
    try { assertTrue(latch.await(30, TimeUnit.SECONDS)); }
    catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError(e); }
  }

  private static TransactionTemplate transaction(ConfigurableApplicationContext context, int isolation) {
    var transaction = new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
    transaction.setIsolationLevel(isolation);
    return transaction;
  }

  private static void publish(NpmHostedService service, RepositoryRuntime runtime, String name, String version) throws Exception {
    String file = name + "-" + version + ".tgz";
    byte[] payload = JSON.writeValueAsBytes(Map.of("name", name,
        "versions", Map.of(version, Map.of("name", name, "version", version, "dist", Map.of("tarball", file))),
        "dist-tags", Map.of("latest", version), "_attachments", Map.of(file, Map.of("content_type", "application/octet-stream",
            "data", Base64.getEncoder().encodeToString(archive(name, version))))));
    service.putPackage(runtime, NpmPackageId.parse(name), null, new ByteArrayInputStream(payload), "admin", null);
  }

  private static byte[] archive(String name, String version) throws Exception {
    var out = new java.io.ByteArrayOutputStream();
    try (var gzip = new org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream(out);
         var tar = new org.apache.commons.compress.archivers.tar.TarArchiveOutputStream(gzip)) {
      byte[] json = JSON.writeValueAsBytes(Map.of("name", name, "version", version));
      var entry = new org.apache.commons.compress.archivers.tar.TarArchiveEntry("package/package.json");
      entry.setSize(json.length);
      tar.putArchiveEntry(entry); tar.write(json); tar.closeArchiveEntry();
    }
    return out.toByteArray();
  }
}
