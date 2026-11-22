package com.github.klboke.kkrepo.persistence.jdbc.internal;

import com.github.klboke.kkrepo.persistence.jdbc.api.MavenPublicationTimesDao;
import com.github.klboke.kkrepo.persistence.jdbc.internal.support.JdbcUpserts;
import java.time.Instant;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcMavenPublicationTimesDao implements MavenPublicationTimesDao {
  private final JdbcTemplate jdbc;

  public JdbcMavenPublicationTimesDao(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public Optional<PublicationTime> find(long repositoryId, byte[] sourceVersionHash) {
    return jdbc.query("""
        SELECT published_epoch_seconds, verified_epoch_seconds
        FROM maven_proxy_publication_time WHERE repository_id = ? AND source_version_hash = ?
        """, (rs, row) -> new PublicationTime(
        Instant.ofEpochSecond(rs.getLong("published_epoch_seconds")),
        Instant.ofEpochSecond(rs.getLong("verified_epoch_seconds"))),
        repositoryId, sourceVersionHash).stream().findFirst();
  }

  @Override
  public void record(long repositoryId, byte[] sourceVersionHash, PublicationTime time) {
    long published = time.publishedAt().getEpochSecond();
    long verified = time.verifiedAt().getEpochSecond();
    JdbcUpserts.updateThenInsert(jdbc, """
        UPDATE maven_proxy_publication_time SET published_epoch_seconds = ?, verified_epoch_seconds = ?
        WHERE repository_id = ? AND source_version_hash = ?
        """, new Object[] {published, verified, repositoryId, sourceVersionHash}, """
        INSERT INTO maven_proxy_publication_time
          (repository_id, source_version_hash, published_epoch_seconds, verified_epoch_seconds)
        VALUES (?, ?, ?, ?)
        """, new Object[] {repositoryId, sourceVersionHash, published, verified});
  }
}
