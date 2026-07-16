package com.github.klboke.kkrepo.persistence.jdbc.api;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Shared state that makes Terraform provider publication and group routing replica-safe. */
public interface TerraformRegistryDao {
  Optional<SigningKey> findActiveSigningKey(long repositoryId);

  Optional<SigningKey> findSigningKey(long repositoryId, int revision);

  void insertSigningKey(SigningKey key);

  Optional<ProviderState> findProviderState(
      long repositoryId, String namespace, String type, String version);

  List<ProviderPlatform> listProviderPlatforms(
      long repositoryId, String namespace, String type, String version);

  List<ProviderPlatform> listProviderPlatformsForProvider(
      long repositoryId, String namespace, String type);

  void publishProvider(ProviderPlatform platform, ProviderState state);

  boolean tryAcquirePublishLease(String leaseKey, String owner, Instant expiresAt);

  boolean renewPublishLease(String leaseKey, String owner, Instant expiresAt);

  void releasePublishLease(String leaseKey, String owner);

  Optional<SourceBinding> findSourceBinding(long groupRepositoryId, String bindingKey);

  void upsertSourceBinding(SourceBinding binding);

  void deleteSourceBindings(long groupRepositoryId);

  record SigningKey(
      long repositoryId,
      int revision,
      String keyId,
      String encryptedPrivateKey,
      String publicKey,
      Instant createdAt) {}

  record ProviderState(
      long repositoryId,
      String namespace,
      String type,
      String version,
      long revision,
      String shasumsPath,
      String signaturePath,
      int signingKeyRevision,
      Instant updatedAt) {}

  record ProviderPlatform(
      long repositoryId,
      String namespace,
      String type,
      String version,
      String os,
      String arch,
      String filename,
      String assetPath,
      String sha256,
      String protocols,
      long revision,
      Instant updatedAt) {}

  record SourceBinding(
      long groupRepositoryId,
      String bindingKey,
      long memberRepositoryId,
      long memberRevision,
      Instant expiresAt,
      Instant updatedAt) {}
}
