package com.github.klboke.kkrepo.server.config;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.klboke.kkrepo.persistence.jdbc.api.AssetDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.BrowseNodeDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.ComponentDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.RepositoryDao;
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
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Real overlapping transactions, run by DatabaseServerSmokeTest on MySQL and PostgreSQL. */
final class BrowseDirectoryConcurrencySmokeChecks {
  private static final ObjectMapper JSON = new ObjectMapper();

  static void exercise(ConfigurableApplicationContext first, ConfigurableApplicationContext second) throws Exception {
    for (String operation : List.of("json", "multipart", "tag")) concurrentPackageMutation(first, second, operation);
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
