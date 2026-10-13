package com.github.klboke.kkrepo.persistence.jdbc.internal;

import static com.github.klboke.kkrepo.persistence.jdbc.internal.support.JdbcRows.nullableInstant;
import static com.github.klboke.kkrepo.persistence.jdbc.internal.support.JdbcRows.nullableLong;

import com.github.klboke.kkrepo.persistence.jdbc.api.BrowseNodeDao.BrowseChild;
import com.github.klboke.kkrepo.persistence.jdbc.internal.support.HashColumns;
import com.github.klboke.kkrepo.persistence.jdbc.internal.support.JdbcInserts;
import java.nio.ByteBuffer;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcBrowseNodeDao implements com.github.klboke.kkrepo.persistence.jdbc.api.BrowseNodeDao {
  private final JdbcTemplate jdbcTemplate;
  private final RowMapper<BrowseChild> childRowMapper = (rs, rowNum) -> new BrowseChild(
      rs.getLong("id"),
      rs.getString("path"),
      rs.getString("display_name"),
      rs.getInt("depth"),
      nullableLong(rs, "asset_id"),
      nullableLong(rs, "component_id"),
      nullableLong(rs, "asset_size"),
      rs.getString("asset_content_type"),
      rs.getString("asset_sha1"),
      nullableInstant(rs, "asset_last_updated_at"),
      rs.getBoolean("has_children"),
      rs.getBoolean("has_asset_subtree"));

  public JdbcBrowseNodeDao(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  /**
   * Ensures every ancestor directory of {@code fullPath} has a browse_node row, then ensures the
   * leaf row for the asset itself exists. Idempotent: re-running with the same arguments is a no-op
   * apart from refreshing the asset linkage.
   *
   * <p>Implementation note: the naive form (one SELECT + INSERT/UPDATE per segment) costs ~2N
   * round trips per asset, and we call it 5 times per primary PUT (main + 4 checksum siblings)
   * over the same directory chain. The batched form below issues a single
   * {@code WHERE path_hash IN (...)} lookup for all segments, then only INSERTs the missing ones
   * (in order, because each child needs its parent's auto-generated id) and updates the leaf row
   * if the asset/component linkage changed. Steady state cost drops from {@code 2N} to
   * {@code 1 select + at most 1 update}.
   */
  public void upsertPathAncestors(long repositoryId, String fullPath, Long assetId, Long componentId) {
    String[] segments = fullPath.split("/");
    List<Segment> levels = new ArrayList<>(segments.length);
    StringBuilder running = new StringBuilder();
    int depth = 0;
    for (String segment : segments) {
      if (segment.isEmpty()) continue;
      if (running.length() > 0) running.append('/');
      running.append(segment);
      String path = running.toString();
      levels.add(new Segment(path, segment, depth, HashColumns.pathHash(path)));
      depth++;
    }
    if (levels.isEmpty()) return;
    boolean containsAsset = assetId != null;

    Map<ByteBuffer, ExistingNode> existingByHash = batchFindIds(repositoryId, levels);
    int last = levels.size() - 1;
    Long parentId = null;
    for (int i = 0; i <= last; i++) {
      Segment level = levels.get(i);
      boolean leaf = (i == last);
      Long assetForRow = leaf ? assetId : null;
      Long componentForRow = leaf ? componentId : null;
      ExistingNode existing = existingByHash.get(ByteBuffer.wrap(level.pathHash));
      if (existing == null) {
        Long expectedParentId = parentId;
        OptionalLong inserted = JdbcInserts.tryInsert(jdbcTemplate, """
            INSERT INTO browse_node
              (repository_id, parent_id, component_id, asset_id, path, path_hash,
               display_name, depth, has_asset_subtree)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            """, ps -> {
          ps.setLong(1, repositoryId);
          if (expectedParentId == null) ps.setNull(2, java.sql.Types.BIGINT);
          else ps.setLong(2, expectedParentId);
          if (componentForRow == null) ps.setNull(3, java.sql.Types.BIGINT);
          else ps.setLong(3, componentForRow);
          if (assetForRow == null) ps.setNull(4, java.sql.Types.BIGINT);
          else ps.setLong(4, assetForRow);
          ps.setString(5, level.path);
          ps.setBytes(6, level.pathHash);
          ps.setString(7, level.displayName);
          ps.setInt(8, level.depth);
          ps.setBoolean(9, containsAsset);
        });
        if (inserted.isPresent()) {
          parentId = inserted.getAsLong();
        } else {
          // Another replica inserted this segment after the batch read. Reconcile the mutable
          // linkage, then continue from the winner's generated id.
          jdbcTemplate.update(
              """
              UPDATE browse_node
              SET parent_id = COALESCE(parent_id, ?),
                  component_id = CASE WHEN ? THEN ? ELSE component_id END,
                  asset_id = CASE WHEN ? THEN ? ELSE asset_id END,
                  has_asset_subtree = CASE WHEN ? THEN true ELSE has_asset_subtree END
              WHERE repository_id = ? AND path_hash = ?
              """,
              expectedParentId, assetForRow != null, componentForRow,
              assetForRow != null, assetForRow, containsAsset, repositoryId, level.pathHash);
          parentId = findIdByPathHash(repositoryId, level.pathHash);
        }
      } else {
        if (leaf && (assetForRow != null || componentForRow != null)) {
          jdbcTemplate.update(
              "UPDATE browse_node SET asset_id = ?, component_id = ? WHERE id = ?",
              assetForRow, componentForRow, existing.id());
        }
        if (containsAsset && !existing.hasAssetSubtree()) {
          jdbcTemplate.update(
              "UPDATE browse_node SET has_asset_subtree = ? WHERE id = ? AND has_asset_subtree = ?",
              true, existing.id(), false);
        }
        parentId = existing.id();
      }
    }
  }

  private Map<ByteBuffer, ExistingNode> batchFindIds(long repositoryId, List<Segment> levels) {
    StringBuilder placeholders = new StringBuilder(levels.size() * 2);
    for (int i = 0; i < levels.size(); i++) {
      if (i > 0) placeholders.append(',');
      placeholders.append('?');
    }
    Object[] args = new Object[levels.size() + 1];
    args[0] = repositoryId;
    for (int i = 0; i < levels.size(); i++) {
      args[i + 1] = levels.get(i).pathHash;
    }
    String sql = "SELECT id, path_hash, has_asset_subtree FROM browse_node WHERE repository_id = ? AND path_hash IN ("
        + placeholders + ")";
    Map<ByteBuffer, ExistingNode> map = new HashMap<>(levels.size() * 2);
    jdbcTemplate.query(sql, rs -> {
      byte[] hash = rs.getBytes("path_hash");
      map.put(ByteBuffer.wrap(hash), new ExistingNode(rs.getLong("id"), rs.getBoolean("has_asset_subtree")));
    }, args);
    return map;
  }

  private record Segment(String path, String displayName, int depth, byte[] pathHash) {}

  private record ExistingNode(long id, boolean hasAssetSubtree) {}

  private Long findIdByPathHash(long repositoryId, byte[] pathHash) {
    List<Long> ids = jdbcTemplate.queryForList(
        "SELECT id FROM browse_node WHERE repository_id = ? AND path_hash = ?",
        Long.class, repositoryId, pathHash);
    return ids.isEmpty() ? null : ids.get(0);
  }

  @org.springframework.transaction.annotation.Transactional
  public int deleteByAssetId(long assetId) {
    List<Long> ids = jdbcTemplate.queryForList(
        "SELECT id FROM browse_node WHERE asset_id = ? FOR UPDATE", Long.class, assetId);
    if (ids.isEmpty()) return 0;
    // An asset node may also be a directory. Clear only its binding; retain children
    // committed by another upload instead of cascading through their Browse entries.
    int deleted = jdbcTemplate.update(
        "UPDATE browse_node SET asset_id = NULL, component_id = NULL WHERE asset_id = ?", assetId);
    for (long id : ids) refreshAndPruneAncestors(id);
    return deleted;
  }

  public int deleteAllForRepository(long repositoryId) {
    return jdbcTemplate.update("DELETE FROM browse_node WHERE repository_id = ?", repositoryId);
  }

  public List<String> listChildPaths(long repositoryId, String parentPath) {
    // Root listing → filter by depth=0 directly; otherwise resolve parent id via the
    // (repository_id, path_hash) unique index so the child lookup hits idx_browse_node_parent
    // instead of scanning by parent.path (varchar(2048), no index).
    if (parentPath == null || parentPath.isEmpty()) {
      return jdbcTemplate.queryForList(
          "SELECT path FROM browse_node WHERE repository_id = ? AND depth = 0 ORDER BY path",
          String.class, repositoryId);
    }
    Long parentId = findIdByPathHash(repositoryId, HashColumns.pathHash(parentPath));
    if (parentId == null) return List.of();
    return jdbcTemplate.queryForList(
        "SELECT path FROM browse_node WHERE parent_id = ? ORDER BY path",
        String.class, parentId);
  }

  /**
   * Returns child browse_node rows under {@code parentPath} ({@code null} or empty for the root).
   * Leaf rows are joined with their asset + asset_blob so the UI can render size/sha1/contentType
   * in a single query.
   *
   * <p>Non-root listings resolve the parent id via the unique (repository_id, path_hash) index
   * first, then walk children through idx_browse_node_parent. Earlier versions joined on
   * parent.path which forced a full scan once browse_node grew into the millions.
   */
  public List<BrowseChild> listChildren(long repositoryId, String parentPath) {
    String join = "LEFT JOIN asset a ON a.id = bn.asset_id "
        + "LEFT JOIN asset_blob ab ON ab.id = a.asset_blob_id ";
    String columns = "bn.id AS id, bn.path AS path, bn.display_name AS display_name, "
        + "bn.depth AS depth, bn.asset_id AS asset_id, bn.component_id AS component_id, "
        + "a.size AS asset_size, a.content_type AS asset_content_type, "
        + "ab.sha1 AS asset_sha1, a.last_updated_at AS asset_last_updated_at, "
        + "EXISTS (SELECT 1 FROM browse_node child WHERE child.parent_id = bn.id) AS has_children, "
        + "bn.has_asset_subtree AS has_asset_subtree ";
    if (parentPath == null || parentPath.isEmpty()) {
      String sql = "SELECT " + columns + "FROM browse_node bn " + join
          + "WHERE bn.repository_id = ? AND bn.depth = 0 "
          + "ORDER BY bn.asset_id IS NULL DESC, bn.path";
      return jdbcTemplate.query(sql, childRowMapper, repositoryId);
    }
    Long parentId = findIdByPathHash(repositoryId, HashColumns.pathHash(parentPath));
    if (parentId == null) return List.of();
    String sql = "SELECT " + columns + "FROM browse_node bn " + join
        + "WHERE bn.parent_id = ? "
        + "ORDER BY bn.asset_id IS NULL DESC, bn.path";
    return jdbcTemplate.query(sql, childRowMapper, parentId);
  }

  @Override
  public Optional<BrowseChild> findNode(long repositoryId, String path) {
    if (path == null || path.isBlank()) return Optional.empty();
    String sql = "SELECT bn.id AS id, bn.path AS path, bn.display_name AS display_name, "
        + "bn.depth AS depth, bn.asset_id AS asset_id, bn.component_id AS component_id, "
        + "a.size AS asset_size, a.content_type AS asset_content_type, "
        + "ab.sha1 AS asset_sha1, a.last_updated_at AS asset_last_updated_at, "
        + "EXISTS (SELECT 1 FROM browse_node child WHERE child.parent_id = bn.id) AS has_children, "
        + "bn.has_asset_subtree AS has_asset_subtree "
        + "FROM browse_node bn "
        + "LEFT JOIN asset a ON a.id = bn.asset_id "
        + "LEFT JOIN asset_blob ab ON ab.id = a.asset_blob_id "
        + "WHERE bn.repository_id = ? AND bn.path_hash = ?";
    return jdbcTemplate.query(sql, childRowMapper, repositoryId, HashColumns.pathHash(path)).stream()
        .filter(node -> path.equals(node.path()))
        .findFirst();
  }

  @Override
  public Map<Long, String> findPathsByAssetIds(List<Long> assetIds) {
    List<Long> ids = assetIds == null
        ? List.of()
        : assetIds.stream().filter(java.util.Objects::nonNull).distinct().toList();
    if (ids.isEmpty()) return Map.of();
    String placeholders = String.join(",", java.util.Collections.nCopies(ids.size(), "?"));
    Map<Long, String> paths = new HashMap<>(ids.size() * 2);
    jdbcTemplate.query(
        "SELECT asset_id, path FROM browse_node WHERE asset_id IN (" + placeholders + ")",
        rs -> {
          paths.put(rs.getLong("asset_id"), rs.getString("path"));
        },
        ids.toArray());
    return Map.copyOf(paths);
  }

  @Override
  public List<DeletionNode> listDeletionChildren(long repositoryId, List<Long> parentIds, int limit) {
    if (parentIds == null || parentIds.isEmpty() || limit <= 0) return List.of();
    List<Object> args = new ArrayList<>();
    args.add(repositoryId);
    args.addAll(parentIds);
    args.add(limit);
    return jdbcTemplate.query(
        "SELECT id, path, asset_id FROM browse_node WHERE repository_id = ? AND parent_id IN ("
            + placeholders(parentIds.size()) + ") ORDER BY id LIMIT ?",
        (rs, rowNum) -> new DeletionNode(
            rs.getLong("id"), rs.getString("path"), nullableLong(rs, "asset_id")),
        args.toArray());
  }

  private void refreshAndPruneAncestors(Long nodeId) {
    while (nodeId != null) {
      // Lock first, then inspect children. An uploader's FK lock may make this wait;
      // the following current read must include its newly committed child on MySQL RR too.
      List<AncestorNode> rows = jdbcTemplate.query(
          "SELECT parent_id, asset_id, has_asset_subtree FROM browse_node WHERE id = ? FOR UPDATE",
          (rs, rowNum) -> new AncestorNode(nullableLong(rs, "parent_id"), nullableLong(rs, "asset_id"),
              rs.getBoolean("has_asset_subtree")), nodeId);
      if (rows.isEmpty()) return;
      AncestorNode node = rows.getFirst();
      List<Boolean> child = node.assetId() != null ? List.of(true) : jdbcTemplate.queryForList(
          "SELECT has_asset_subtree FROM browse_node WHERE parent_id = ? "
              + "ORDER BY has_asset_subtree DESC, id LIMIT 1 FOR UPDATE", Boolean.class, nodeId);
      if (child.isEmpty()) {
        jdbcTemplate.update("DELETE FROM browse_node WHERE id = ?", nodeId);
      } else {
        boolean hasAssetSubtree = child.getFirst();
        if (node.hasAssetSubtree() == hasAssetSubtree) return;
        jdbcTemplate.update("UPDATE browse_node SET has_asset_subtree = ? WHERE id = ?", hasAssetSubtree, nodeId);
      }
      nodeId = node.parentId();
    }
  }

  private String placeholders(int count) {
    StringBuilder builder = new StringBuilder(count * 2);
    for (int i = 0; i < count; i++) {
      if (i > 0) builder.append(',');
      builder.append('?');
    }
    return builder.toString();
  }

  private record AncestorNode(Long parentId, Long assetId, boolean hasAssetSubtree) {}
}
