package com.github.klboke.kkrepo.server.npm;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.klboke.kkrepo.core.BlobStorage;
import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.core.RepositoryType;
import com.github.klboke.kkrepo.persistence.jdbc.api.AssetDao;
import com.github.klboke.kkrepo.protocol.npm.NpmPackageId;
import com.github.klboke.kkrepo.server.cache.AssetMetadataCache;
import com.github.klboke.kkrepo.server.maven.BlobStorageRegistry;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntime;
import com.github.klboke.kkrepo.server.transaction.TransientTransactionRetry;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class NpmHostedPublicationRetryTest {
  private static final NpmPackageId PACKAGE = NpmPackageId.parse("demo");
  private static final RepositoryRuntime RUNTIME = new RepositoryRuntime(10L, "npm-hosted", RepositoryFormat.NPM,
      RepositoryType.HOSTED, "npm-hosted", true, 1L, "ALLOW_ONCE", null, null, true,
      null, null, null, null, null, List.of());

  @Test
  void secondAttachmentFailureReplaysEntirePublicationInFreshTransactions(@TempDir Path temp) throws Exception {
    Fixture f = fixture(temp);
    var bodies = new ArrayList<String>();
    var calls = new AtomicInteger();
    when(f.writer.writeTarball(any(), any(), anyLong(), any(), any(), any(), any(), any(), any(), any(), any()))
        .thenAnswer(call -> {
          assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
          bodies.add(new String(call.getArgument(6, InputStream.class).readAllBytes(), StandardCharsets.UTF_8));
          if (calls.incrementAndGet() == 2) throw new CannotAcquireLockException("second attachment deadlock");
          return stored();
        });
    assertEquals(200, publish(f).status());
    assertEquals(List.of("one", "two", "one", "two"), bodies);
    assertEquals(2, f.transactions.begun);
    assertEquals(1, f.transactions.rolledBack);
    assertEquals(1, f.transactions.committed);
    verify(f.writer).writePackageRoot(any(), any(), anyLong(), any(), any(), any(), any());
    try (var files = Files.list(temp)) { assertEquals(0, files.count()); }
  }

  @Test
  void exhaustedAndUnexpectedFailuresRetainTheirOriginalEvidence(@TempDir Path temp) throws Exception {
    Fixture f = fixture(temp);
    var deadlock = new CannotAcquireLockException("exhausted deadlock");
    when(f.writer.writePackageRoot(any(), any(), anyLong(), any(), any(), any(), any())).thenThrow(deadlock);
    assertSame(deadlock, assertThrows(CannotAcquireLockException.class, () -> publish(f)));
    assertEquals(3, f.transactions.rolledBack);
    verify(f.writer, times(6)).writeTarball(any(), any(), anyLong(), any(), any(), any(), any(), any(), any(), any(), any());
    Fixture unexpected = fixture(temp);
    var original = new IllegalStateException("unexpected persistence failure");
    when(unexpected.writer.writePackageRoot(any(), any(), anyLong(), any(), any(), any(), any())).thenThrow(original);
    assertSame(original, assertThrows(IllegalStateException.class, () -> publish(unexpected)));
    assertEquals(1, unexpected.transactions.rolledBack);
    try (var files = Files.list(temp)) { assertEquals(0, files.count()); }
  }

  @Test
  void retryRevalidatesPolicyAndReportsExpectedConflictWithoutAnotherRetry(@TempDir Path temp) throws Exception {
    Fixture f = fixture(temp);
    var count = new AtomicInteger();
    when(f.assets.findAssetByPath(10L, "demo")).thenAnswer(call -> {
      if (count.incrementAndGet() == 3) throw new NpmExceptions.WritePolicyDenied("winner committed");
      return Optional.empty();
    });
    when(f.writer.writePackageRoot(any(), any(), anyLong(), any(), any(), any(), any()))
        .thenThrow(new CannotAcquireLockException("retry after winner"));
    var conflict = assertThrows(NpmExceptions.WritePolicyDenied.class, () -> publish(f));
    assertEquals("winner committed", conflict.getMessage());
    assertEquals(2, f.transactions.begun);
    verify(f.writer, times(2)).writeTarball(any(), any(), anyLong(), any(), any(), any(), any(), any(), any(), any(), any());
  }

  @Test
  void multipartStagesOnceAndReplaysTarballAndMetadataTogether(@TempDir Path temp) throws Exception {
    Fixture f = fixture(temp);
    var bodies = new ArrayList<byte[]>();
    when(f.writer.writeTarball(any(), any(), anyLong(), any(), any(), any(), any(), any(), any(), any(), any()))
        .thenAnswer(call -> {
          assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
          bodies.add(call.getArgument(6, InputStream.class).readAllBytes());
          return stored();
        });
    when(f.writer.writePackageRoot(any(), any(), anyLong(), any(), any(), any(), any()))
        .thenThrow(new CannotAcquireLockException("root deadlock")).thenReturn(stored());
    var streams = new AtomicInteger();
    byte[] bytes = archive();
    var upload = new MockMultipartFile("asset", "demo-1.0.0.tgz", "application/gzip", bytes) {
      @Override public InputStream getInputStream() { streams.incrementAndGet(); return new ByteArrayInputStream(bytes); }
    };
    assertEquals(201, f.service.uploadTarball(RUNTIME, upload, "publisher", null).status());
    assertEquals(1, streams.get());
    assertEquals(2, bodies.size());
    assertArrayEquals(bytes, bodies.get(0));
    assertArrayEquals(bytes, bodies.get(1));
    assertEquals(1, f.transactions.rolledBack);
    assertEquals(1, f.transactions.committed);
  }

  @Test
  void multipartCheckedFailureIsUnwrappedWithoutRetry(@TempDir Path temp) throws Exception {
    Fixture f = fixture(temp);
    var io = new IOException("staged archive read failure");
    when(f.writer.writeTarball(any(), any(), anyLong(), any(), any(), any(), any(), any(), any(), any(), any()))
        .thenThrow(new UncheckedIOException(io));
    assertSame(io, assertThrows(IOException.class, () -> f.service.uploadTarball(RUNTIME,
        new MockMultipartFile("asset", "demo-1.0.0.tgz", "application/gzip", archive()), "publisher", null)));
    assertEquals(1, f.transactions.rolledBack);
  }

  private static com.github.klboke.kkrepo.server.maven.MavenResponse publish(Fixture f) {
    String json = """
        {"name":"demo","versions":{
          "1.0.0":{"name":"demo","version":"1.0.0","dist":{"tarball":"demo-1.0.0.tgz"}},
          "2.0.0":{"name":"demo","version":"2.0.0","dist":{"tarball":"demo-2.0.0.tgz"}}},
          "_attachments":{"demo-1.0.0.tgz":{"data":"b25l"},"demo-2.0.0.tgz":{"data":"dHdv"}}}
        """;
    return f.service.putPackage(RUNTIME, PACKAGE, null,
        new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)), "publisher", null);
  }

  private static byte[] archive() throws IOException {
    var output = new ByteArrayOutputStream();
    try (var gzip = new org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream(output);
         var tar = new org.apache.commons.compress.archivers.tar.TarArchiveOutputStream(gzip)) {
      byte[] json = "{\"name\":\"demo\",\"version\":\"1.0.0\"}".getBytes(StandardCharsets.UTF_8);
      var entry = new org.apache.commons.compress.archivers.tar.TarArchiveEntry("package/package.json");
      entry.setSize(json.length); tar.putArchiveEntry(entry); tar.write(json); tar.closeArchiveEntry();
    }
    return output.toByteArray();
  }

  private static NpmAssetWriter.Stored stored() {
    return new NpmAssetWriter.Stored(null, null, new NpmAssetWriter.Digests("md5", "sha1", "sha256", "sha512", 3), true, null);
  }

  private static Fixture fixture(Path temp) {
    var assets = mock(AssetDao.class);
    var registry = mock(BlobStorageRegistry.class);
    when(registry.forBlobStoreId(1L)).thenReturn(mock(BlobStorage.class));
    var writer = mock(NpmAssetWriter.class);
    var tx = new Transactions();
    var mapper = new ObjectMapper();
    var target = new NpmHostedService(assets, registry, writer, mapper, mock(AssetMetadataCache.class), null,
        new NpmPublishParser(mapper, temp), new TransientTransactionRetry(tx, 3, 0));
    var proxy = new ProxyFactory(target);
    proxy.setProxyTargetClass(true);
    proxy.addAdvice(new TransactionInterceptor(tx, new AnnotationTransactionAttributeSource()));
    return new Fixture(assets, writer, (NpmHostedService) proxy.getProxy(), tx);
  }

  private record Fixture(AssetDao assets, NpmAssetWriter writer, NpmHostedService service, Transactions transactions) {}
  private static final class Transactions extends AbstractPlatformTransactionManager {
    int begun, committed, rolledBack;
    @Override protected Object doGetTransaction() { return new Object(); }
    @Override protected void doBegin(Object transaction, TransactionDefinition definition) { begun++; }
    @Override protected void doCommit(DefaultTransactionStatus status) { committed++; }
    @Override protected void doRollback(DefaultTransactionStatus status) { rolledBack++; }
  }
}
