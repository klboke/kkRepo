package com.github.klboke.kkrepo.persistence.jdbc.internal;

import com.github.klboke.kkrepo.persistence.jdbc.api.OhpmRepositoryDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.PersistenceHashes;
import com.github.klboke.kkrepo.persistence.jdbc.internal.support.JsonColumns;
import com.github.klboke.kkrepo.persistence.jdbc.internal.support.JdbcInserts;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import org.springframework.jdbc.core.ArgumentPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcOhpmRepositoryDao implements OhpmRepositoryDao {
  private final JdbcTemplate jdbc;
  private final JsonColumns json;

  public JdbcOhpmRepositoryDao(JdbcTemplate jdbc, JsonColumns json) {
    this.jdbc = jdbc;
    this.json = json;
  }

  @Override
  public Optional<Version> findVersion(long repositoryId, String packageName, String version, boolean proxy) {
    String metadata = proxy ? "ohpmProxyMetadata" : "ohpmMetadata";
    String query = "SELECT " + json.extractTextAtKey("attributes_json", metadata, "versions")
        + " AS manifest, " + json.extractText("attributes_json", metadata, "packageType")
        + " AS package_type, " + json.extractText("attributes_json", metadata, "hspType")
        + " AS hsp_type, " + json.extractText("attributes_json", "ohpmProxySource")
        + " AS source, " + json.extractText("attributes_json", "cache")
        + " AS cache_info FROM asset WHERE repository_id = ? AND path_hash = ? AND format = 'ohpm'"
        + (proxy ? " AND " + json.extractText("attributes_json", "ohpmProxyMissing") + " IS NULL" : "");
    return jdbc.query(query, (row, index) -> new Version(json.read(row.getString("manifest")),
        row.getString("package_type"), row.getString("hsp_type"), row.getString("source"),
        json.read(row.getString("cache_info"))), version, repositoryId, PersistenceHashes.pathHash(packageName))
        .stream().findFirst();
  }

  @Override
  public Optional<Lease> acquire(String key, String owner, Instant now, Instant expiresAt) {
    int updated = jdbc.update("""
        UPDATE ohpm_fetch_lease SET owner = ?, fencing_token = fencing_token + 1, expires_at = ?
        WHERE lease_key = ? AND expires_at <= ?
        """, owner, utc(expiresAt), key, utc(now));
    if (updated == 0 && !JdbcInserts.tryUpdate(jdbc, """
        INSERT INTO ohpm_fetch_lease (lease_key, owner, fencing_token, expires_at) VALUES (?, ?, 1, ?)
        """, new ArgumentPreparedStatementSetter(new Object[] {key, owner, utc(expiresAt)}))) {
      return Optional.empty();
    }
    return jdbc.query("SELECT * FROM ohpm_fetch_lease WHERE lease_key = ? AND owner = ?",
        (row, index) -> new Lease(row.getString("lease_key"), row.getString("owner"),
            row.getLong("fencing_token"), row.getObject("expires_at", LocalDateTime.class).toInstant(ZoneOffset.UTC)),
        key, owner).stream().findFirst();
  }

  @Override
  public boolean renew(String key, String owner, long fence, Instant now, Instant expiresAt) {
    return jdbc.update("""
        UPDATE ohpm_fetch_lease SET expires_at = ?
        WHERE lease_key = ? AND owner = ? AND fencing_token = ? AND expires_at > ?
        """, utc(expiresAt), key, owner, fence, utc(now)) == 1;
  }

  @Override
  public void release(String key, String owner, long fence) {
    jdbc.update("UPDATE ohpm_fetch_lease SET expires_at = ? WHERE lease_key = ? AND owner = ? AND fencing_token = ?",
        utc(Instant.EPOCH), key, owner, fence);
  }

  private static LocalDateTime utc(Instant instant) {
    return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
  }
}
