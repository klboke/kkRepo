package com.github.klboke.kkrepo.server.docker;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.github.klboke.kkrepo.core.*;
import com.github.klboke.kkrepo.persistence.jdbc.api.*;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.*;
import com.github.klboke.kkrepo.protocol.docker.*;
import com.github.klboke.kkrepo.server.cache.AssetMetadataCache;
import com.github.klboke.kkrepo.server.maven.*;
import com.github.klboke.kkrepo.server.transaction.TransientTransactionRetry;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;
import javax.sql.DataSource;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Both database smoke jobs run the actual DAOs, transaction manager and blob publication path. */
public final class DockerUploadTransactionSmokeChecks {
  public record Persistence(PersistenceStores stores, PlatformTransactionManager manager) {}

  public static void verify(HikariDataSource source, Function<DataSource, Persistence> factory) throws Exception {
    HikariConfig config = new HikariConfig();
    config.setJdbcUrl(source.getJdbcUrl());
    config.setUsername(source.getUsername());
    config.setPassword(source.getPassword());
    config.setMaximumPoolSize(1);
    config.setMinimumIdle(0);
    config.setConnectionTimeout(1000);
    config.setLeakDetectionThreshold(2000);
    config.setTransactionIsolation("TRANSACTION_READ_COMMITTED");
    try (var pool = new HikariDataSource(config)) {
      var persistence = factory.apply(pool);
      var fixture = new Fixture(pool, persistence);
      fixture.verifySlowIo();
      fixture.verifyLostAppend(false);
      fixture.verifyLostAppend(true);
      fixture.verifyCancelledCompletion();
      fixture.verifyChangedCompletion();
      fixture.verifyConcurrentCompletion();
      fixture.verifyReusableBlobCompletion();
      fixture.verifyRollbackAndRetry();
      fixture.verifyInvalidDigest();
      fixture.verifyOuterTransactionRejected();
    }
  }

  private static final class Fixture {
    final HikariDataSource pool;
    final PersistenceStores stores;
    final TransientTransactionRetry transactions;
    final RepositoryRuntime runtime;
    final DockerBlobStore blobs;
    final DockerUploadService first;
    final DockerUploadService second;
    final Map<String, byte[]> objects = new ConcurrentHashMap<>();
    final AtomicReference<Consumer<String>> intercept = new AtomicReference<>(ignored -> {});
    final AtomicInteger puts = new AtomicInteger();

    Fixture(HikariDataSource pool, Persistence persistence) throws Exception {
      this.pool = pool;
      stores = persistence.stores();
      transactions = new TransientTransactionRetry(persistence.manager(), 3, 0);
      long store = stores.blobStores().insert(new BlobStoreRecord(null, "docker-slow-storage", "file",
          null, null, null, null, Map.of()));
      long repository = stores.repositories().insert(new RepositoryRecord(null, "docker-slow-upload",
          RepositoryFormat.DOCKER, RepositoryType.HOSTED, "docker-hosted", true, store, null,
          null, null, null, null, false, Map.of()));
      runtime = new RepositoryRuntime(repository, "docker-slow-upload", RepositoryFormat.DOCKER,
          RepositoryType.HOSTED, "docker-hosted", true, store, "ALLOW", null, null, true,
          null, null, null, List.of());
      BlobStorage storage = mock(BlobStorage.class);
      when(storage.putFile(anyString(), anyString(), any(Path.class), anyString())).thenAnswer(call -> {
        io("chunk");
        byte[] bytes = Files.readAllBytes(call.getArgument(2));
        return save(bytes, call.getArgument(3));
      });
      when(storage.put(anyString(), anyString(), any(InputStream.class), anyLong(), anyString())).thenAnswer(call -> {
        io("blob"); puts.incrementAndGet();
        return save(((InputStream) call.getArgument(2)).readAllBytes(), call.getArgument(4));
      });
      when(storage.get(any())).thenAnswer(call -> {
        io("read");
        byte[] bytes = objects.get(((BlobReference) call.getArgument(0)).objectKey());
        return bytes == null ? Optional.empty() : Optional.of(new ByteArrayInputStream(bytes) {
          @Override public synchronized int read(byte[] b, int off, int len) { io("read-body"); return super.read(b, off, len); }
        });
      });
      doAnswer(call -> { io("delete"); objects.remove(((BlobReference) call.getArgument(0)).objectKey()); return null; })
          .when(storage).delete(any());
      var registry = mock(BlobStorageRegistry.class);
      when(registry.forBlobStoreId(store)).thenReturn(storage);
      blobs = new DockerBlobStore(stores.assets(), registry, mock(AssetMetadataCache.class), transactions);
      first = service(stores.dockerUploads(), persistence.manager());
      second = service(stores.dockerUploads(), persistence.manager());
    }

    DockerUploadService service(DockerUploadDao dao, PlatformTransactionManager manager) {
      var target = new DockerUploadService(dao, blobs, null, 3600, null, transactions);
      var proxy = new ProxyFactory(target);
      proxy.setProxyTargetClass(true);
      proxy.addAdvice(new TransactionInterceptor(manager, new AnnotationTransactionAttributeSource()));
      return (DockerUploadService) proxy.getProxy();
    }

    void io(String operation) {
      assertFalse(TransactionSynchronizationManager.isActualTransactionActive(), operation + " must not hold a transaction");
      assertEquals(0, pool.getHikariPoolMXBean().getActiveConnections(), operation + " must not borrow a connection");
      intercept.get().accept(operation);
    }

    BlobReference save(byte[] bytes, String hash) {
      String key = UUID.randomUUID().toString();
      objects.put(key, bytes);
      return new BlobReference("test", key, hash, bytes.length);
    }

    String start() { return first.start(runtime, "test/image", null, null, null, "test", "127.0.0.1").uuid(); }
    void append(DockerUploadService service, String id, String data) {
      service.append(runtime, id, input(data), (long) data.length(), null);
    }
    DockerUploadService.CompleteResult complete(DockerUploadService service, String id, String data) {
      return service.complete(runtime, id, input(""), 0L, null, digest(data), "test", "127.0.0.1");
    }

    void verifySlowIo() throws Exception {
      String id = start();
      append(first, id, "slow-final");
      var logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger("com.zaxxer.hikari.pool.ProxyLeakTask");
      var logs = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
      logs.start(); logger.addAppender(logs);
      try {
        blocked("blob", () -> complete(first, id, "slow-final"), () -> {
          // Hold storage longer than the real Hikari threshold while another request uses the only connection.
          try (var connection = pool.getConnection(); var query = connection.createStatement()) {
            assertTrue(query.execute("SELECT 1"));
          }
          Thread.sleep(2200);
          assertEquals(0, pool.getHikariPoolMXBean().getActiveConnections());
        }, null);
        assertTrue(logs.list.stream().noneMatch(event -> event.getFormattedMessage().contains("Connection leak detection triggered")));
      } finally { logger.detachAppender(logs); logs.stop(); }
      assertEquals("COMPLETED", stores.dockerUploads().findSession(id).orElseThrow().status());
    }

    void verifyLostAppend(boolean emptyWinner) throws Exception {
      String id = start();
      int before = objects.size();
      blocked("chunk", () -> { append(first, id, "loser"); return null; },
          () -> append(second, id, emptyWinner ? "" : "winner"), DockerErrorCode.BLOB_UPLOAD_INVALID);
      assertEquals(1, stores.dockerUploads().listChunks(id).size());
      assertEquals(emptyWinner ? 0 : 6, stores.dockerUploads().findSession(id).orElseThrow().nextOffset());
      assertEquals(before + 1, objects.size(), "losing staged chunk must be discarded");
    }

    void verifyCancelledCompletion() throws Exception {
      String id = start(); append(first, id, "cancelled");
      int before = objects.size();
      blocked("blob", () -> complete(first, id, "cancelled"), () -> second.cancel(runtime, id), DockerErrorCode.BLOB_UPLOAD_UNKNOWN);
      assertEquals("CANCELLED", stores.dockerUploads().findSession(id).orElseThrow().status());
      assertTrue(blobs.findBlob(runtime, digest("cancelled")).isEmpty());
      assertEquals(before, objects.size());
    }

    void verifyChangedCompletion() throws Exception {
      String id = start(); append(first, id, "changing");
      blocked("blob", () -> complete(first, id, "changing"), () -> append(second, id, "tail"), DockerErrorCode.BLOB_UPLOAD_INVALID);
      assertEquals(12, stores.dockerUploads().findSession(id).orElseThrow().nextOffset());
      assertTrue(blobs.findBlob(runtime, digest("changing")).isEmpty());
      complete(second, id, "changingtail");
    }

    void verifyConcurrentCompletion() throws Exception {
      String id = start(); append(first, id, "race-final");
      int before = objects.size();
      blocked("blob", () -> complete(first, id, "race-final"), () -> complete(second, id, "race-final"), DockerErrorCode.BLOB_UPLOAD_UNKNOWN);
      assertTrue(blobs.findBlob(runtime, digest("race-final")).isPresent());
      assertEquals(before + 1, objects.size(), "cleanup must preserve the winner's object");
    }

    void verifyReusableBlobCompletion() throws Exception {
      String original = start(); append(first, original, "reusable");
      complete(first, original, "reusable");
      var originalBlob = blobs.findBlob(runtime, digest("reusable")).orElseThrow().blob();
      int priorPuts = puts.get();

      String reused = start(); append(second, reused, "reusable");
      complete(second, reused, "reusable");
      assertEquals("COMPLETED", stores.dockerUploads().findSession(reused).orElseThrow().status());
      assertEquals(originalBlob.id(), blobs.findBlob(runtime, digest("reusable")).orElseThrow().blob().id());
      assertEquals(priorPuts, puts.get(), "reuse must verify uploaded chunks without writing another final blob");

      String cancelled = start(); append(first, cancelled, "reusable");
      int before = objects.size();
      // Even without a final storage PUT, reuse must revalidate the session after reading/verifying bytes.
      blocked("read-body", () -> complete(first, cancelled, "reusable"),
          () -> second.cancel(runtime, cancelled), DockerErrorCode.BLOB_UPLOAD_UNKNOWN);
      assertEquals("CANCELLED", stores.dockerUploads().findSession(cancelled).orElseThrow().status());
      assertEquals(priorPuts, puts.get());
      assertEquals(before, objects.size(), "a rejected reuse must preserve the published blob and its chunks");
      assertTrue(objects.containsKey(originalBlob.objectKey()));

      String invalid = start(); append(first, invalid, "tampered");
      var failure = assertThrows(DockerProtocolException.class, () -> complete(first, invalid, "reusable"));
      assertEquals(DockerErrorCode.DIGEST_INVALID, failure.code());
      assertEquals("STARTED", stores.dockerUploads().findSession(invalid).orElseThrow().status());
      assertEquals(originalBlob.id(), blobs.findBlob(runtime, digest("reusable")).orElseThrow().blob().id());
      assertEquals(priorPuts, puts.get(), "reuse must reject invalid bytes before publishing metadata");
    }

    void verifyRollbackAndRetry() {
      String id = start();
      var dao = spy(stores.dockerUploads());
      doThrow(new org.springframework.dao.DataIntegrityViolationException("forced publication failure"))
          .when(dao).completeSession(eq(id), anyString(), anyString());
      var failing = new DockerUploadService(dao, blobs, null, 3600, null, transactions);
      int before = objects.size();
      assertThrows(org.springframework.dao.DataIntegrityViolationException.class, () -> failing.complete(runtime, id,
          input("rollback"), 8L, null, digest("rollback"), "test", "127.0.0.1"));
      assertTrue(stores.dockerUploads().listChunks(id).isEmpty());
      assertEquals(0, stores.dockerUploads().findSession(id).orElseThrow().nextOffset());
      assertTrue(blobs.findBlob(runtime, digest("rollback")).isEmpty());
      assertEquals(before, objects.size(), "failed publication must clean both staged objects");
      doThrow(new org.springframework.dao.CannotAcquireLockException("transient publication failure"))
          .doCallRealMethod().when(dao).completeSession(eq(id), anyString(), anyString());
      int priorPuts = puts.get();
      failing.complete(runtime, id, input("rollback"), 8L, null, digest("rollback"), "test", "127.0.0.1");
      assertEquals(1, puts.get() - priorPuts, "transaction retries must not replay storage I/O");
      assertEquals("COMPLETED", stores.dockerUploads().findSession(id).orElseThrow().status());
    }

    void verifyInvalidDigest() {
      String id = start(); int before = objects.size();
      assertThrows(DockerProtocolException.class, () -> first.complete(runtime, id, input("bad"), 3L,
          null, digest("different"), "test", "127.0.0.1"));
      assertEquals(0, stores.dockerUploads().findSession(id).orElseThrow().nextOffset());
      assertTrue(stores.dockerUploads().listChunks(id).isEmpty());
      assertEquals(before, objects.size());
    }

    void verifyOuterTransactionRejected() {
      String id = start();
      assertThrows(IllegalStateException.class, () -> transactions.execute("outer", () -> {
        append(first, id, "forbidden"); return null;
      }));
      assertThrows(IllegalStateException.class, () -> transactions.execute("outer", () -> complete(first, id, "")));
    }

    void blocked(String operation, Callable<?> request, CheckedRunnable competing, DockerErrorCode expected) throws Exception {
      var entered = new CountDownLatch(1); var release = new CountDownLatch(1); var once = new AtomicBoolean();
      intercept.set(current -> {
        if (operation.equals(current) && once.compareAndSet(false, true)) {
          entered.countDown();
          try { assertTrue(release.await(15, TimeUnit.SECONDS)); }
          catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new AssertionError(failure); }
        }
      });
      try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
        var task = executor.submit(request);
        try { assertTrue(entered.await(10, TimeUnit.SECONDS)); competing.run(); }
        finally { release.countDown(); }
        if (expected == null) task.get(10, TimeUnit.SECONDS);
        else {
          var failure = assertThrows(ExecutionException.class, () -> task.get(10, TimeUnit.SECONDS));
          assertInstanceOf(DockerProtocolException.class, failure.getCause());
          assertEquals(expected, ((DockerProtocolException) failure.getCause()).code());
        }
      } finally { intercept.set(ignored -> {}); }
    }
  }

  @FunctionalInterface private interface CheckedRunnable { void run() throws Exception; }
  private static ByteArrayInputStream input(String value) { return new ByteArrayInputStream(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)); }
  private static DockerDigest digest(String value) {
    try { return DockerDigest.parse("sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)))); }
    catch (Exception failure) { throw new IllegalStateException(failure); }
  }
}
