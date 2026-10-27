package com.github.klboke.kkrepo.persistence.jdbc.internal;

import com.github.klboke.kkrepo.persistence.jdbc.api.YumMetadataCleanupDao;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Repository
@Transactional(propagation = Propagation.MANDATORY)
public class JdbcYumMetadataCleanupDao implements YumMetadataCleanupDao {
  private final JdbcTemplate jdbc;

  public JdbcYumMetadataCleanupDao(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public boolean lockRepository(long repositoryId) {
    // Same parent lock as claimYum. FK locks also fence new components/assets on every replica.
    return !jdbc.queryForList("""
        SELECT id FROM repository WHERE id = ? AND format = 'yum' AND type = 'hosted'
        FOR UPDATE SKIP LOCKED
        """, Long.class, repositoryId).isEmpty();
  }

  @Override
  public List<Long> lockComponents(long repositoryId, int limit) {
    return jdbc.queryForList("""
        SELECT id FROM component WHERE repository_id = ? ORDER BY id LIMIT ?
        FOR UPDATE SKIP LOCKED
        """, Long.class, repositoryId, Math.max(1, limit));
  }

  @Override
  public List<Long> lockAssets(long componentId, int limit) {
    // Never wait for an asset held by a writer/deleter waiting on our component or repository.
    return jdbc.queryForList("""
        SELECT id FROM asset WHERE component_id = ? ORDER BY id LIMIT ?
        FOR UPDATE SKIP LOCKED
        """, Long.class, componentId, Math.max(1, limit));
  }

  @Override
  public boolean lockBlob(long blobId) {
    // Writers can hold a shared blob before acquiring their component; do not invert that wait.
    return !jdbc.queryForList("SELECT id FROM asset_blob WHERE id = ? FOR UPDATE SKIP LOCKED",
        Long.class, blobId).isEmpty();
  }
}
