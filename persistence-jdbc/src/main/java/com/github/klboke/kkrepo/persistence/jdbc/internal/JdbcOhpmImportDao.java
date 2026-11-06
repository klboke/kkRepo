package com.github.klboke.kkrepo.persistence.jdbc.internal;

import com.github.klboke.kkrepo.persistence.jdbc.api.OhpmImportDao;
import com.github.klboke.kkrepo.persistence.jdbc.internal.support.JsonColumns;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Repository
public class JdbcOhpmImportDao implements OhpmImportDao {
  private final JdbcTemplate jdbc;
  private final JsonColumns json;
  private final RowMapper<Job> jobMapper = (row, index) -> new Job(
      row.getString("job_id"), row.getLong("repository_id"), Source.valueOf(row.getString("source_type")),
      row.getObject("source_repository_id") == null ? null : row.getLong("source_repository_id"),
      row.getString("requester"), JobState.valueOf(row.getString("state")),
      row.getTimestamp("created_at").toInstant(), row.getTimestamp("updated_at").toInstant());
  private final RowMapper<Item> itemMapper;

  public JdbcOhpmImportDao(JdbcTemplate jdbc, JsonColumns json) {
    this.jdbc = jdbc;
    this.json = json;
    this.itemMapper = (row, index) -> new Item(row.getString("job_id"), row.getString("item_key"),
        row.getString("package_name"), row.getString("version"), json.read(row.getString("plan_json")),
        ItemState.valueOf(row.getString("state")), row.getString("detail"),
        row.getTimestamp("updated_at").toInstant());
  }

  @Override
  public Job create(long repositoryId, Source source, Long sourceRepositoryId, String requester) {
    if (source == null || (source == Source.REGISTRY) != (sourceRepositoryId != null)
        || requester == null || requester.isBlank()) {
      throw new IllegalArgumentException("OHPM import requires a source and requester");
    }
    String id = UUID.randomUUID().toString();
    Timestamp now = Timestamp.from(Instant.now());
    jdbc.update("""
        INSERT INTO ohpm_import_job
          (job_id, repository_id, source_type, source_repository_id, requester, state, created_at, updated_at)
        VALUES (?, ?, ?, ?, ?, 'PLANNING', ?, ?)
        """, id, repositoryId, source.name(), sourceRepositoryId, requester, now, now);
    return findJob(id).orElseThrow();
  }

  @Override
  public Optional<Job> findJob(String id) {
    return jdbc.query("SELECT * FROM ohpm_import_job WHERE job_id = ?", jobMapper, id).stream().findFirst();
  }

  @Override
  public List<Job> listJobs(long repositoryId, int limit) {
    return jdbc.query("""
        SELECT * FROM ohpm_import_job WHERE repository_id = ? ORDER BY created_at DESC, job_id DESC LIMIT ?
        """, jobMapper, repositoryId, Math.max(1, Math.min(100, limit)));
  }

  @Override
  public Optional<Job> lockJob(String id) {
    requireTransaction();
    return jdbc.query("SELECT * FROM ohpm_import_job WHERE job_id = ? FOR UPDATE", jobMapper, id)
        .stream().findFirst();
  }

  @Override
  public Item addPlan(String jobId, String key, String packageName, String version,
      Map<String, Object> plan, ItemState state, String detail) {
    Job job = lockJob(jobId).orElseThrow();
    if (job.state() != JobState.PLANNING) throw new IllegalStateException("OHPM import plan is sealed");
    if (key == null || !key.matches("[a-f0-9]{64}") || plan == null
        || state != ItemState.PLANNED && state != ItemState.MANUAL) {
      throw new IllegalArgumentException("Invalid OHPM import plan");
    }
    Optional<Item> existing = findItemCurrent(jobId, key);
    if (existing.isPresent()) {
      Item item = existing.get();
      if (!Objects.equals(packageName, item.packageName()) || !Objects.equals(version, item.version())
          || !json.read(json.write(plan)).equals(item.plan()) || state != item.state()) {
        throw new IllegalStateException("OHPM source changed; create a new import plan");
      }
      return item;
    }
    String serialized = json.write(plan);
    int bytes = serialized.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
    if (bytes > 2 * 1024 * 1024) throw new IllegalArgumentException("OHPM import item metadata exceeds 2 MiB");
    List<Integer> sizes = jdbc.queryForList("SELECT plan_bytes FROM ohpm_import_item WHERE job_id = ? FOR UPDATE",
        Integer.class, jobId);
    if (sizes.size() >= 1000 || sizes.stream().mapToLong(Integer::longValue).sum() + bytes > 16 * 1024 * 1024) {
      throw new IllegalStateException("OHPM import plan exceeds 1000 items or 16 MiB of metadata");
    }
    jdbc.update("""
        INSERT INTO ohpm_import_item
          (job_id, item_key, package_name, version, plan_json, plan_bytes, state, detail, updated_at)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
        """, jobId, key, packageName, version, json.serializedParameter(serialized), bytes, state.name(), detail,
        Timestamp.from(Instant.now()));
    return findItemCurrent(jobId, key).orElseThrow();
  }

  @Override
  public Optional<Item> findItem(String jobId, String key) {
    return jdbc.query("SELECT * FROM ohpm_import_item WHERE job_id = ? AND item_key = ?",
        itemMapper, jobId, key).stream().findFirst();
  }

  private Optional<Item> findItemCurrent(String jobId, String key) {
    return jdbc.query("SELECT * FROM ohpm_import_item WHERE job_id = ? AND item_key = ? FOR UPDATE",
        itemMapper, jobId, key).stream().findFirst();
  }

  @Override
  public Optional<Item> lockItem(String jobId, String key) {
    lockJob(jobId).orElseThrow();
    return findItemCurrent(jobId, key);
  }

  @Override
  public List<Item> items(String jobId) {
    return jdbc.query("SELECT * FROM ohpm_import_item WHERE job_id = ? ORDER BY item_key", itemMapper, jobId);
  }

  @Override
  public boolean transition(String jobId, JobState expected, JobState next) {
    Job job = lockJob(jobId).orElseThrow();
    if (job.state() != expected) return false;
    boolean valid = next == JobState.CANCELLED && expected != JobState.COMPLETED && expected != JobState.CANCELLED
        || expected == JobState.PLANNING && next == JobState.READY
        || expected == JobState.READY && next == JobState.APPLYING
        || expected == JobState.APPLYING && next == JobState.COMPLETED;
    if (!valid) throw new IllegalArgumentException("Invalid OHPM import transition");
    if (next == JobState.READY || next == JobState.COMPLETED) {
      List<Item> items = jdbc.query("SELECT * FROM ohpm_import_item WHERE job_id = ? FOR UPDATE", itemMapper, jobId);
      if (items.isEmpty() || next == JobState.COMPLETED && items.stream().anyMatch(item -> !item.succeeded())) {
        return false;
      }
    }
    return jdbc.update("UPDATE ohpm_import_job SET state = ?, updated_at = ? WHERE job_id = ? AND state = ?",
        next.name(), Timestamp.from(Instant.now()), jobId, expected.name()) == 1;
  }

  @Override
  public boolean result(String jobId, String key, ItemState state, String detail) {
    Job job = lockJob(jobId).orElseThrow();
    if (job.state() != JobState.APPLYING) throw new IllegalStateException("OHPM import is not applying");
    if (state == null || state == ItemState.PLANNED) throw new IllegalArgumentException("Invalid OHPM import result");
    return jdbc.update("""
        UPDATE ohpm_import_item SET state = ?, detail = ?, updated_at = ?
        WHERE job_id = ? AND item_key = ? AND state NOT IN ('IMPORTED', 'SKIPPED')
        """, state.name(), detail, Timestamp.from(Instant.now()), jobId, key) == 1;
  }

  private static void requireTransaction() {
    if (!TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalStateException("OHPM import mutations require an enclosing transaction");
    }
  }
}
