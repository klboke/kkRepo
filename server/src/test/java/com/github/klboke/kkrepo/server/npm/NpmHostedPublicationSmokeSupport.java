package com.github.klboke.kkrepo.server.npm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.klboke.kkrepo.core.BlobStorage;
import com.github.klboke.kkrepo.persistence.jdbc.api.AssetDao;
import com.github.klboke.kkrepo.protocol.npm.NpmPackageId;
import com.github.klboke.kkrepo.server.cache.AssetMetadataCache;
import com.github.klboke.kkrepo.server.maven.BlobStorageRegistry;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntime;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntimeRegistry;
import com.github.klboke.kkrepo.server.repositories.RepositoryCommands.CreateCommand;
import com.github.klboke.kkrepo.server.repositories.RepositoryCommands.HostedSettings;
import com.github.klboke.kkrepo.server.repositories.RepositoryService;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.mockito.AdditionalAnswers;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;

/** Runs the same deterministic publication rollback against both smoke-test database engines. */
public final class NpmHostedPublicationSmokeSupport {
  private static final NpmPackageId PACKAGE = NpmPackageId.parse("immutable-smoke");
  private static final ObjectMapper MAPPER = new ObjectMapper();

  private NpmHostedPublicationSmokeSupport() {}

  public static void verify(ConfigurableApplicationContext first, ConfigurableApplicationContext second)
      throws Exception {
    first.getBean(RepositoryService.class).create(new CreateCommand(
        "smoke-npm-immutable", "npm-hosted", true, "smoke-file", false,
        new HostedSettings("ALLOW_ONCE", null, null), null, null, null, null, null));
    RepositoryRuntime runtime = first.getBean(RepositoryRuntimeRegistry.class)
        .resolve("smoke-npm-immutable").orElseThrow();
    second.getBean(BlobStorageRegistry.class).refreshAll();
    var winnerService = second.getBean(NpmHostedService.class);
    var winnerRuntime = second.getBean(RepositoryRuntimeRegistry.class)
        .resolve("smoke-npm-immutable").orElseThrow();
    AssetDao assets = first.getBean(AssetDao.class);
    BlobStorage storage = first.getBean(BlobStorageRegistry.class).forBlobStoreId(runtime.blobStoreId());
    BlobStorage stagedStorage = mock(BlobStorage.class, AdditionalAnswers.delegatesTo(storage));
    var injected = new AtomicBoolean();
    var firstUpload = new java.util.concurrent.atomic.AtomicReference<com.github.klboke.kkrepo.core.BlobReference>();
    doAnswer(call -> {
      String path = call.getArgument(1);
      if (path.equals(PACKAGE.tarballPath("immutable-smoke-1.0.0.tgz")) && injected.compareAndSet(false, true)) {
        // All absence/policy checks have completed. Another replica commits version 2
        // before this publisher writes version 1, then fails when it reaches version 2.
        publish(winnerService, winnerRuntime, Map.of("2.0.0", "winner"));
      }
      var ref = storage.putFile(call.getArgument(0), path, call.getArgument(2, Path.class), call.getArgument(3));
      if (path.equals(PACKAGE.tarballPath("immutable-smoke-1.0.0.tgz"))) firstUpload.set(ref);
      return ref;
    }).when(stagedStorage).putFile(anyString(), anyString(), any(Path.class), anyString());
    BlobStorageRegistry registry = mock(BlobStorageRegistry.class);
    when(registry.forBlobStoreId(runtime.blobStoreId())).thenReturn(stagedStorage);
    var target = new NpmHostedService(assets, registry, first.getBean(NpmAssetWriter.class), MAPPER,
        first.getBean(AssetMetadataCache.class));
    var proxy = new ProxyFactory(target);
    proxy.setProxyTargetClass(true);
    proxy.addAdvice(new TransactionInterceptor(first.getBean(PlatformTransactionManager.class),
        new AnnotationTransactionAttributeSource()));
    var losingService = (NpmHostedService) proxy.getProxy();
    var batch = new LinkedHashMap<String, String>();
    batch.put("1.0.0", "uncommitted-first-attachment");
    batch.put("2.0.0", "losing-second-attachment");
    assertThrows(NpmExceptions.WritePolicyDenied.class, () -> publish(losingService, runtime, batch));
    assertTrue(injected.get(), "must exercise a winner committed after validation");
    assertTrue(assets.findAssetByPath(runtime.id(), PACKAGE.tarballPath("immutable-smoke-1.0.0.tgz")).isEmpty(),
        "rejection must roll back earlier attachments");
    assertFalse(storage.exists(firstUpload.get()), "rollback must clean the unreferenced first upload");
    var winner = assets.findAssetByPath(runtime.id(), PACKAGE.tarballPath("immutable-smoke-2.0.0.tgz")).orElseThrow();
    var winningBlob = assets.findBlobById(winner.assetBlobId()).orElseThrow();
    try (var body = storage.get(com.github.klboke.kkrepo.server.blob.BlobReferenceCodec.reference(
        winningBlob.blobRef(), winningBlob.objectKey(), winningBlob.sha256(), winningBlob.size())).orElseThrow()) {
      assertEquals("winner", new String(body.readAllBytes(), StandardCharsets.UTF_8));
    }
    Map<String, Object> root = winnerService.packageRoot(winnerRuntime, PACKAGE).orElseThrow();
    assertEquals(Map.of("2.0.0", version("2.0.0")), root.get("versions"));
    long rootBlob = assets.findAssetByPath(runtime.id(), PACKAGE.id()).orElseThrow().assetBlobId();
    assertThrows(NpmExceptions.WritePolicyDenied.class,
        () -> publish(first.getBean(NpmHostedService.class), runtime, Map.of("2.0.0", "stale-publisher")));
    assertEquals(rootBlob, assets.findAssetByPath(runtime.id(), PACKAGE.id()).orElseThrow().assetBlobId());
    // Multipart upload has a checked-I/O rollback contract as well as runtime conflict rollback.
    byte[] archive = archive("4.0.0");
    var multipart = mock(org.springframework.web.multipart.MultipartFile.class);
    when(multipart.isEmpty()).thenReturn(false);
    when(multipart.getOriginalFilename()).thenReturn("immutable-smoke-4.0.0.tgz");
    when(multipart.getContentType()).thenReturn("application/octet-stream");
    var streams = new java.util.concurrent.atomic.AtomicInteger();
    when(multipart.getInputStream()).thenAnswer(call -> {
      if (streams.incrementAndGet() == 1) return new ByteArrayInputStream(archive);
      return new java.io.FilterInputStream(new ByteArrayInputStream(archive)) {
        @Override public void close() throws java.io.IOException {
          super.close();
          throw new java.io.IOException("simulated upload close failure");
        }
      };
    });
    var io = assertThrows(java.io.IOException.class,
        () -> first.getBean(NpmHostedService.class).uploadTarball(runtime, multipart, "admin", null));
    assertEquals("simulated upload close failure", io.getMessage());
    assertTrue(assets.findAssetByPath(runtime.id(), PACKAGE.tarballPath("immutable-smoke-4.0.0.tgz")).isEmpty());
    assertEquals(rootBlob, assets.findAssetByPath(runtime.id(), PACKAGE.id()).orElseThrow().assetBlobId());
    publish(winnerService, winnerRuntime, Map.of("3.0.0", "new-version"));
    var updated = winnerService.packageRoot(winnerRuntime, PACKAGE).orElseThrow();
    assertTrue(com.github.klboke.kkrepo.protocol.npm.NpmMetadata.versions(updated).containsKey("2.0.0"));
    assertTrue(com.github.klboke.kkrepo.protocol.npm.NpmMetadata.versions(updated).containsKey("3.0.0"));
    assertFalse(com.github.klboke.kkrepo.protocol.npm.NpmMetadata.versions(updated).containsKey("1.0.0"));
    assertEquals(winningBlob.id(), assets.findAssetByPath(runtime.id(), winner.path()).orElseThrow().assetBlobId());
  }

  private static byte[] archive(String version) throws Exception {
    var output = new java.io.ByteArrayOutputStream();
    try (var gzip = new org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream(output);
         var tar = new org.apache.commons.compress.archivers.tar.TarArchiveOutputStream(gzip)) {
      byte[] json = MAPPER.writeValueAsBytes(Map.of("name", PACKAGE.id(), "version", version));
      var entry = new org.apache.commons.compress.archivers.tar.TarArchiveEntry("package/package.json");
      entry.setSize(json.length);
      tar.putArchiveEntry(entry);
      tar.write(json);
      tar.closeArchiveEntry();
    }
    return output.toByteArray();
  }

  private static Map<String, Object> version(String version) {
    return Map.of("name", PACKAGE.id(), "version", version,
        "dist", Map.of("tarball", "immutable-smoke-" + version + ".tgz"));
  }

  private static void publish(NpmHostedService service, RepositoryRuntime runtime, Map<String, String> versions)
      throws Exception {
    var docs = new LinkedHashMap<String, Object>();
    var attachments = new LinkedHashMap<String, Object>();
    for (var entry : versions.entrySet()) {
      docs.put(entry.getKey(), version(entry.getKey()));
      byte[] bytes = entry.getValue().getBytes(StandardCharsets.UTF_8);
      attachments.put("immutable-smoke-" + entry.getKey() + ".tgz", Map.of(
          "content_type", "application/octet-stream", "length", bytes.length,
          "data", Base64.getEncoder().encodeToString(bytes)));
    }
    byte[] json = MAPPER.writeValueAsBytes(Map.of("name", PACKAGE.id(), "versions", docs,
        "dist-tags", Map.of("latest", versions.keySet().stream().reduce((a, b) -> b).orElseThrow()),
        "_attachments", attachments));
    assertEquals(200, service.putPackage(runtime, PACKAGE, null, new ByteArrayInputStream(json), "admin", null).status());
  }
}
