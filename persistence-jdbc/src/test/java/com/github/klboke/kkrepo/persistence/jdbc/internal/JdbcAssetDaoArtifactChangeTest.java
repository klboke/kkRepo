package com.github.klboke.kkrepo.persistence.jdbc.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.github.klboke.kkrepo.persistence.jdbc.api.ArtifactChangeDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.ArtifactChangeDao.ArtifactChange;
import com.github.klboke.kkrepo.persistence.jdbc.api.ArtifactChangeDao.ChangeKind;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

class JdbcAssetDaoArtifactChangeTest {
  @Test
  void doesNotAppendEventsWhenTheRuntimeCapabilityIsDisabled() {
    RecordingArtifactChanges changes = new RecordingArtifactChanges();
    JdbcAssetDao assets = new JdbcAssetDao(new JdbcTemplate(), null, changes, false);

    assets.appendArtifactChange(change());

    assertEquals(0, changes.appended);
  }

  @Test
  void appendsEventsWhenTheRuntimeCapabilityIsEnabled() {
    RecordingArtifactChanges changes = new RecordingArtifactChanges();
    JdbcAssetDao assets = new JdbcAssetDao(new JdbcTemplate(), null, changes, true);

    assets.appendArtifactChange(change());

    assertEquals(1, changes.appended);
  }

  @Test
  void disabledContentReplacementDoesNotTakeTheEventOnlyRowLock() {
    RecordingJdbcTemplate jdbc = new RecordingJdbcTemplate();
    RecordingArtifactChanges changes = new RecordingArtifactChanges();
    JdbcAssetDao assets = new JdbcAssetDao(jdbc, null, changes, false);

    assertEquals(
        1,
        assets.updateAssetBlobBinding(
            2, 3, "application/java-archive", 1024, java.time.Instant.EPOCH));

    assertEquals(0, jdbc.queries);
    assertEquals(0, changes.appended);
  }

  private static ArtifactChange change() {
    return new ArtifactChange(null, 1, 2, null, 3, ChangeKind.CONTENT_CREATED, null);
  }

  private static final class RecordingArtifactChanges implements ArtifactChangeDao {
    private int appended;

    @Override
    public long append(ArtifactChange change) {
      appended++;
      return appended;
    }

    @Override
    public List<ArtifactChange> listAfter(long lastSeenId, int maxItems) {
      return List.of();
    }

    @Override
    public int deleteThrough(long consumedThroughId, int maxItems) {
      return 0;
    }

    @Override
    public Optional<EventRange> retainedRange() {
      return Optional.empty();
    }
  }

  private static final class RecordingJdbcTemplate extends JdbcTemplate {
    private int queries;

    @Override
    public <T> List<T> query(String sql, RowMapper<T> rowMapper, Object... args) {
      queries++;
      return List.of();
    }

    @Override
    public int update(String sql, Object... args) {
      return 1;
    }
  }
}
