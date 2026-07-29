package com.github.klboke.kkrepo.server.management;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.github.klboke.kkrepo.persistence.jdbc.api.AssetDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetPublicIdentifierRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetPublicIdentifierRecord.IdentifierType;
import com.github.klboke.kkrepo.server.management.AssetPublicIdService.PublicIdConflictException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class AssetPublicIdServiceTest {
  private static final String REPOSITORY = "windows-artifacts";
  private static final long REPOSITORY_ID = 7L;
  private static final long ASSET_ID = 226063L;

  private final AssetDao assetDao = mock(AssetDao.class);
  private final NexusAssetIdCodec codec = new NexusAssetIdCodec();
  private final AssetPublicIdService service = new AssetPublicIdService(assetDao, codec);

  @Test
  void allocatesPreferredNativeIdAndReusesConcurrentWinner() {
    when(assetDao.lockNativePublicIdentifier(ASSET_ID))
        .thenReturn(Optional.empty())
        .thenReturn(Optional.of(nativeRecord("0123456789abcdef0123456789abcdef")));
    when(assetDao.tryInsertPublicIdentifier(any())).thenReturn(false);

    String encoded = service.nativePublicId(REPOSITORY, REPOSITORY_ID, ASSET_ID);

    assertEquals(
        "0123456789abcdef0123456789abcdef",
        codec.decodeAssetId(encoded).opaqueId());
  }

  @Test
  void nativePreferredValueCollisionAllocatesRandomOpaqueId() {
    when(assetDao.lockNativePublicIdentifier(ASSET_ID)).thenReturn(Optional.empty());
    when(assetDao.tryInsertPublicIdentifier(any())).thenReturn(false, true);

    String encoded = service.nativePublicId(REPOSITORY, REPOSITORY_ID, ASSET_ID);

    assertNotEquals(String.format("%032x", ASSET_ID), codec.decodeAssetId(encoded).opaqueId());
  }

  @Test
  void nexusAliasRegistrationIsIdempotentForTheSameAsset() {
    String opaque = "fedcba98765432100123456789abcdef";
    when(assetDao.lockNativePublicIdentifier(ASSET_ID))
        .thenReturn(Optional.of(nativeRecord(String.format("%032x", ASSET_ID))));
    when(assetDao.lockPublicIdentifier(REPOSITORY_ID, opaque))
        .thenReturn(Optional.of(aliasRecord(opaque, ASSET_ID)));

    service.registerNexusAlias(
        codec.encodeAssetId(REPOSITORY, opaque),
        REPOSITORY,
        REPOSITORY_ID,
        ASSET_ID,
        "http://nexus.example/",
        99L);

    verify(assetDao, never()).tryInsertPublicIdentifier(any());
  }

  @Test
  void nexusAliasRegistrationAllocatesNativeIdBeforeAlias() {
    String opaque = "fedcba98765432100123456789abcdef";
    when(assetDao.lockNativePublicIdentifier(ASSET_ID)).thenReturn(Optional.empty());
    when(assetDao.lockPublicIdentifier(REPOSITORY_ID, opaque)).thenReturn(Optional.empty());
    when(assetDao.tryInsertPublicIdentifier(any())).thenReturn(true, true);

    service.registerNexusAlias(
        codec.encodeAssetId(REPOSITORY, opaque),
        REPOSITORY,
        REPOSITORY_ID,
        ASSET_ID,
        "http://nexus.example/",
        99L);

    ArgumentCaptor<AssetPublicIdentifierRecord> inserted =
        ArgumentCaptor.forClass(AssetPublicIdentifierRecord.class);
    verify(assetDao, times(2)).tryInsertPublicIdentifier(inserted.capture());
    assertEquals(
        List.of(IdentifierType.KKREPO_NATIVE, IdentifierType.NEXUS_ALIAS),
        inserted.getAllValues().stream()
            .map(AssetPublicIdentifierRecord::identifierType)
            .toList());
  }

  @Test
  void aliasCannotOverrideAnotherAssetOrDeletionTombstone() {
    String opaque = "fedcba98765432100123456789abcdef";
    String encoded = codec.encodeAssetId(REPOSITORY, opaque);
    when(assetDao.lockNativePublicIdentifier(ASSET_ID))
        .thenReturn(Optional.of(nativeRecord(String.format("%032x", ASSET_ID))));
    when(assetDao.lockPublicIdentifier(REPOSITORY_ID, opaque))
        .thenReturn(Optional.of(aliasRecord(opaque, 12L)));

    assertThrows(PublicIdConflictException.class, () -> service.registerNexusAlias(
        encoded, REPOSITORY, REPOSITORY_ID, ASSET_ID, "http://nexus.example/", 99L));

    when(assetDao.lockPublicIdentifier(REPOSITORY_ID, opaque))
        .thenReturn(Optional.of(aliasRecord(opaque, null)));
    assertThrows(PublicIdConflictException.class, () -> service.registerNexusAlias(
        encoded, REPOSITORY, REPOSITORY_ID, ASSET_ID, "http://nexus.example/", 99L));
  }

  @Test
  void aliasRepositoryNameMustMatchTargetRepository() {
    String encoded = codec.encodeAssetId("other", "fedcba98765432100123456789abcdef");

    assertThrows(PublicIdConflictException.class, () -> service.registerNexusAlias(
        encoded, REPOSITORY, REPOSITORY_ID, ASSET_ID, "http://nexus.example/", 99L));
    verify(assetDao, never()).lockPublicIdentifier(
        REPOSITORY_ID, "fedcba98765432100123456789abcdef");
  }

  private static AssetPublicIdentifierRecord nativeRecord(String opaque) {
    return new AssetPublicIdentifierRecord(
        1L, REPOSITORY_ID, opaque, ASSET_ID, ASSET_ID,
        IdentifierType.KKREPO_NATIVE, null, null, Instant.EPOCH);
  }

  private static AssetPublicIdentifierRecord aliasRecord(String opaque, Long assetId) {
    return new AssetPublicIdentifierRecord(
        1L, REPOSITORY_ID, opaque, assetId, null,
        IdentifierType.NEXUS_ALIAS, "http://nexus.example/", 99L, Instant.EPOCH);
  }
}
