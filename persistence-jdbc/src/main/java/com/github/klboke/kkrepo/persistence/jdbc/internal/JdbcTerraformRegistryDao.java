package com.github.klboke.kkrepo.persistence.jdbc.internal;

import static com.github.klboke.kkrepo.persistence.jdbc.internal.support.JdbcRows.nullableInstant;
import static com.github.klboke.kkrepo.persistence.jdbc.internal.support.JdbcRows.nullableTimestamp;

import com.github.klboke.kkrepo.persistence.jdbc.api.TerraformRegistryDao;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcTerraformRegistryDao implements TerraformRegistryDao {
  private final JdbcTemplate jdbc;

  public JdbcTerraformRegistryDao(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public Optional<SigningKey> findActiveSigningKey(long repositoryId) {
    return jdbc.query("""
        SELECT * FROM terraform_signing_key
        WHERE repository_id = ? AND active = TRUE
        ORDER BY revision DESC
        """, (rs, row) -> new SigningKey(
        rs.getLong("repository_id"), rs.getInt("revision"), rs.getString("key_id"),
        rs.getString("encrypted_private_key"), rs.getString("public_key"),
        nullableInstant(rs, "created_at")), repositoryId).stream().findFirst();
  }

  @Override
  public Optional<SigningKey> findSigningKey(long repositoryId, int revision) {
    return jdbc.query("""
        SELECT * FROM terraform_signing_key WHERE repository_id = ? AND revision = ?
        """, (rs, row) -> new SigningKey(
        rs.getLong("repository_id"), rs.getInt("revision"), rs.getString("key_id"),
        rs.getString("encrypted_private_key"), rs.getString("public_key"),
        nullableInstant(rs, "created_at")), repositoryId, revision).stream().findFirst();
  }

  @Override
  @org.springframework.transaction.annotation.Transactional
  public void insertSigningKey(SigningKey key) {
    jdbc.update("UPDATE terraform_signing_key SET active = FALSE WHERE repository_id = ?", key.repositoryId());
    jdbc.update("""
        INSERT INTO terraform_signing_key
          (repository_id, revision, key_id, encrypted_private_key, public_key, active, created_at)
        VALUES (?, ?, ?, ?, ?, TRUE, ?)
        """, key.repositoryId(), key.revision(), key.keyId(), key.encryptedPrivateKey(),
        key.publicKey(), nullableTimestamp(key.createdAt()));
  }

  @Override
  public Optional<ProviderState> findProviderState(
      long repositoryId, String namespace, String type, String version) {
    return jdbc.query("""
        SELECT * FROM terraform_provider_signing_state
        WHERE repository_id = ? AND namespace = ? AND provider_type = ? AND version = ?
        """, (rs, row) -> new ProviderState(
        rs.getLong("repository_id"), rs.getString("namespace"), rs.getString("provider_type"),
        rs.getString("version"), rs.getLong("revision"), rs.getString("shasums_path"),
        rs.getString("signature_path"), rs.getInt("signing_key_revision"),
        nullableInstant(rs, "updated_at")), repositoryId, namespace, type, version)
        .stream().findFirst();
  }

  @Override
  public List<ProviderPlatform> listProviderPlatforms(
      long repositoryId, String namespace, String type, String version) {
    return jdbc.query("""
        SELECT * FROM terraform_provider_platform
        WHERE repository_id = ? AND namespace = ? AND provider_type = ? AND version = ?
          AND status = 'READY'
        ORDER BY os, arch
        """, (rs, row) -> new ProviderPlatform(
        rs.getLong("repository_id"), rs.getString("namespace"), rs.getString("provider_type"),
        rs.getString("version"), rs.getString("os"), rs.getString("arch"),
        rs.getString("filename"), rs.getString("asset_path"), rs.getString("sha256"),
        rs.getString("protocols"), rs.getLong("revision"), nullableInstant(rs, "updated_at")),
        repositoryId, namespace, type, version);
  }

  @Override
  public List<ProviderPlatform> listProviderPlatformsForProvider(
      long repositoryId, String namespace, String type) {
    return jdbc.query("""
        SELECT p.*
        FROM terraform_provider_platform p
        JOIN terraform_provider_signing_state s
          ON s.repository_id = p.repository_id
         AND s.namespace = p.namespace
         AND s.provider_type = p.provider_type
         AND s.version = p.version
        WHERE p.repository_id = ? AND p.namespace = ? AND p.provider_type = ?
          AND p.status = 'READY'
        ORDER BY p.version, p.os, p.arch
        """, (rs, row) -> new ProviderPlatform(
        rs.getLong("repository_id"), rs.getString("namespace"), rs.getString("provider_type"),
        rs.getString("version"), rs.getString("os"), rs.getString("arch"),
        rs.getString("filename"), rs.getString("asset_path"), rs.getString("sha256"),
        rs.getString("protocols"), rs.getLong("revision"), nullableInstant(rs, "updated_at")),
        repositoryId, namespace, type);
  }

  @Override
  @org.springframework.transaction.annotation.Transactional
  public void publishProvider(ProviderPlatform platform, ProviderState state) {
    int updated = jdbc.update("""
        UPDATE terraform_provider_platform
        SET filename = ?, asset_path = ?, sha256 = ?, protocols = ?, status = 'READY',
            revision = ?, updated_at = ?
        WHERE repository_id = ? AND namespace = ? AND provider_type = ? AND version = ?
          AND os = ? AND arch = ?
        """, platform.filename(), platform.assetPath(), platform.sha256(), platform.protocols(),
        platform.revision(), nullableTimestamp(platform.updatedAt()), platform.repositoryId(),
        platform.namespace(), platform.type(), platform.version(), platform.os(), platform.arch());
    if (updated == 0) {
      jdbc.update("""
          INSERT INTO terraform_provider_platform
            (repository_id, namespace, provider_type, version, os, arch, filename, asset_path,
             sha256, protocols, status, revision, updated_at)
          VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'READY', ?, ?)
          """, platform.repositoryId(), platform.namespace(), platform.type(), platform.version(),
          platform.os(), platform.arch(), platform.filename(), platform.assetPath(), platform.sha256(),
          platform.protocols(), platform.revision(), nullableTimestamp(platform.updatedAt()));
    }
    updated = jdbc.update("""
        UPDATE terraform_provider_signing_state
        SET revision = ?, shasums_path = ?, signature_path = ?, signing_key_revision = ?, updated_at = ?
        WHERE repository_id = ? AND namespace = ? AND provider_type = ? AND version = ?
        """, state.revision(), state.shasumsPath(), state.signaturePath(), state.signingKeyRevision(),
        nullableTimestamp(state.updatedAt()), state.repositoryId(), state.namespace(), state.type(), state.version());
    if (updated == 0) {
      jdbc.update("""
          INSERT INTO terraform_provider_signing_state
            (repository_id, namespace, provider_type, version, revision, shasums_path,
             signature_path, signing_key_revision, updated_at)
          VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
          """, state.repositoryId(), state.namespace(), state.type(), state.version(), state.revision(),
          state.shasumsPath(), state.signaturePath(), state.signingKeyRevision(),
          nullableTimestamp(state.updatedAt()));
    }
  }

  @Override
  public boolean tryAcquirePublishLease(String leaseKey, String owner, Instant expiresAt) {
    Instant now = Instant.now();
    int updated = jdbc.update("""
        UPDATE terraform_publish_lease
        SET owner = ?, expires_at = ?, updated_at = ?
        WHERE lease_key = ? AND (expires_at < ? OR owner = ?)
        """, owner, nullableTimestamp(expiresAt), nullableTimestamp(now), leaseKey,
        nullableTimestamp(now), owner);
    if (updated > 0) return true;
    try {
      jdbc.update("""
          INSERT INTO terraform_publish_lease (lease_key, owner, expires_at, updated_at)
          VALUES (?, ?, ?, ?)
          """, leaseKey, owner, nullableTimestamp(expiresAt), nullableTimestamp(now));
      return true;
    } catch (DuplicateKeyException e) {
      return false;
    }
  }

  @Override
  public boolean renewPublishLease(String leaseKey, String owner, Instant expiresAt) {
    Instant now = Instant.now();
    return jdbc.update("""
        UPDATE terraform_publish_lease
        SET expires_at = ?, updated_at = ?
        WHERE lease_key = ? AND owner = ? AND expires_at >= ?
        """, nullableTimestamp(expiresAt), nullableTimestamp(now), leaseKey, owner,
        nullableTimestamp(now)) > 0;
  }

  @Override
  public void releasePublishLease(String leaseKey, String owner) {
    jdbc.update("DELETE FROM terraform_publish_lease WHERE lease_key = ? AND owner = ?", leaseKey, owner);
  }

  @Override
  public Optional<SourceBinding> findSourceBinding(long groupRepositoryId, String bindingKey) {
    return jdbc.query("""
        SELECT * FROM terraform_source_binding
        WHERE group_repository_id = ? AND binding_key = ? AND expires_at > ?
        """, (rs, row) -> new SourceBinding(
        rs.getLong("group_repository_id"), rs.getString("binding_key"),
        rs.getLong("member_repository_id"), rs.getLong("member_revision"),
        nullableInstant(rs, "expires_at"), nullableInstant(rs, "updated_at")),
        groupRepositoryId, bindingKey, nullableTimestamp(Instant.now())).stream().findFirst();
  }

  @Override
  public void upsertSourceBinding(SourceBinding binding) {
    int updated = jdbc.update("""
        UPDATE terraform_source_binding
        SET member_repository_id = ?, member_revision = ?, expires_at = ?, updated_at = ?
        WHERE group_repository_id = ? AND binding_key = ?
        """, binding.memberRepositoryId(), binding.memberRevision(), nullableTimestamp(binding.expiresAt()),
        nullableTimestamp(binding.updatedAt()), binding.groupRepositoryId(), binding.bindingKey());
    if (updated == 0) {
      try {
        jdbc.update("""
            INSERT INTO terraform_source_binding
              (group_repository_id, binding_key, member_repository_id, member_revision, expires_at, updated_at)
            VALUES (?, ?, ?, ?, ?, ?)
            """, binding.groupRepositoryId(), binding.bindingKey(), binding.memberRepositoryId(),
            binding.memberRevision(), nullableTimestamp(binding.expiresAt()), nullableTimestamp(binding.updatedAt()));
      } catch (DuplicateKeyException e) {
        upsertSourceBinding(binding);
      }
    }
  }

  @Override
  public void deleteSourceBindings(long groupRepositoryId) {
    jdbc.update("DELETE FROM terraform_source_binding WHERE group_repository_id = ?", groupRepositoryId);
  }
}
