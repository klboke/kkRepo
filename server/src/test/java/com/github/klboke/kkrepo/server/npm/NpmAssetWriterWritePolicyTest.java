package com.github.klboke.kkrepo.server.npm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.github.klboke.kkrepo.core.BlobReference;
import com.github.klboke.kkrepo.core.BlobStorage;
import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.core.RepositoryType;
import com.github.klboke.kkrepo.persistence.jdbc.api.AssetDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.BrowseNodeDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.ComponentDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetBlobRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetRecord;
import com.github.klboke.kkrepo.protocol.npm.NpmPackageId;
import com.github.klboke.kkrepo.server.cache.AssetMetadataCache;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntime;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

class NpmAssetWriterWritePolicyTest {
  private static final NpmPackageId PACKAGE = NpmPackageId.parse("demo");
  private static final BlobReference REF = new BlobReference("default", "uploaded-object", "sha256", 5);

  @Test
  void hostedExistingTarballIsImmutableUnderExplicitAndDefaultAllowOnce() {
    for (String policy : new String[] {null, "ALLOW_ONCE"}) {
      Fixture f = fixture();
      when(f.assets.findAssetByPath(anyLong(), any())).thenReturn(Optional.of(existing()));
      var error = assertThrows(NpmExceptions.WritePolicyDenied.class, () -> write(f, policy));
      assertEquals("Write policy ALLOW_ONCE forbids overwriting tarball", error.getMessage());
      verify(f.assets, never()).insertBlobOrFindExisting(any());
      verify(f.assets, never()).updateAssetBlobBindingAndMetadata(
          anyLong(), any(), anyLong(), any(), any(), anyLong(), any(), any());
    }
  }

  @Test
  void hostedDenyIsEnforcedBeforePersistingTarballsWithoutBlockingAdministrativeRootRewrites() {
    Fixture f = fixture();
    var error = assertThrows(NpmExceptions.WritePolicyDenied.class, () -> write(f, "DENY"));
    assertEquals("Write policy DENY forbids writing tarball", error.getMessage());
    verify(f.assets, never()).insertBlobOrFindExisting(any());
    assertEquals(true, f.writer.writePackageRoot(runtime("DENY"), f.storage, 1L,
        PACKAGE, new byte[0], "admin", null).created());
  }

  @Test
  void hostedAllowStillOverwritesAndAllowOnceStillUpdatesPackageRoot() {
    Fixture f = fixture();
    when(f.assets.findAssetByPath(anyLong(), any())).thenReturn(Optional.of(existing()));
    assertEquals(false, write(f, "ALLOW").created());
    assertEquals(false, f.writer.writePackageRoot(runtime("ALLOW_ONCE"), f.storage, 1L,
        PACKAGE, "{}".getBytes(StandardCharsets.UTF_8), "admin", null).created());
  }

  @Test
  void laterPublicationFailureCleansEarlierUploadsOnlyAfterRollbackAndPreservesTheCause() {
    Fixture f = fixture();
    var transaction = new TransactionTemplate(new TestTransactions());
    var failure = new IllegalStateException("later attachment failed");
    var caught = assertThrows(IllegalStateException.class, () -> transaction.execute(status -> {
      write(f, "ALLOW_ONCE");
      verify(f.storage, never()).delete(any());
      throw failure;
    }));
    assertSame(failure, caught);
    verify(f.storage).delete(REF);
  }

  @Test
  void committedPublicationAndReferencedObjectsAreNeverDeleted() {
    Fixture f = fixture();
    var transaction = new TransactionTemplate(new TestTransactions());
    transaction.execute(status -> write(f, "ALLOW_ONCE"));
    verify(f.storage, never()).delete(any());
    when(f.assets.hasLiveBlobForObjectKeyHash(anyLong(), any())).thenReturn(true);
    assertThrows(IllegalStateException.class, () -> transaction.execute(status -> {
      write(f, "ALLOW");
      throw new IllegalStateException("rollback with shared object");
    }));
    verify(f.storage, never()).delete(any());
  }

  private static NpmAssetWriter.Stored write(Fixture f, String policy) {
    return f.writer.writeTarball(runtime(policy), f.storage, 1L, PACKAGE, "1.0.0", "demo-1.0.0.tgz",
        new ByteArrayInputStream("bytes".getBytes(StandardCharsets.UTF_8)), "application/octet-stream",
        "admin", null, Map.of());
  }

  private static RepositoryRuntime runtime(String policy) {
    return new RepositoryRuntime(10L, "npm-hosted", RepositoryFormat.NPM, RepositoryType.HOSTED,
        "npm-hosted", true, 1L, policy, null, null, true, null, null, null, null, null, List.of());
  }

  private static AssetRecord existing() {
    return new AssetRecord(70L, 10L, 20L, 50L, RepositoryFormat.NPM, "demo/-/demo-1.0.0.tgz",
        new byte[32], "demo-1.0.0.tgz", "tarball", "application/octet-stream", 5L, null, Instant.EPOCH, Map.of());
  }

  private static Fixture fixture() {
    AssetDao assets = mock(AssetDao.class);
    BlobStorage storage = mock(BlobStorage.class);
    when(storage.putFile(any(), any(), any(), any())).thenReturn(REF);
    when(assets.tryInsertAsset(any())).thenReturn(OptionalLong.of(70));
    when(assets.insertBlobOrFindExisting(any())).thenAnswer(call -> {
      AssetBlobRecord blob = call.getArgument(0);
      return new AssetBlobRecord(50L, blob.blobStoreId(), blob.blobRef(), blob.blobRefHash(),
          blob.objectKey(), blob.objectKeyHash(), blob.sha1(), blob.sha256(), blob.md5(), blob.size(),
          blob.contentType(), blob.createdBy(), blob.createdByIp(), blob.blobCreatedAt(), blob.blobUpdatedAt(), blob.attributes());
    });
    return new Fixture(assets, storage, new NpmAssetWriter(assets, mock(ComponentDao.class),
        mock(BrowseNodeDao.class), null, mock(AssetMetadataCache.class), null, null));
  }

  private record Fixture(AssetDao assets, BlobStorage storage, NpmAssetWriter writer) {}

  private static final class TestTransactions extends AbstractPlatformTransactionManager {
    @Override protected Object doGetTransaction() { return new Object(); }
    @Override protected void doBegin(Object transaction, TransactionDefinition definition) {}
    @Override protected void doCommit(DefaultTransactionStatus status) {}
    @Override protected void doRollback(DefaultTransactionStatus status) {}
  }
}
