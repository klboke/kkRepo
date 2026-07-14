package com.github.klboke.kkrepo.persistence.jdbc.internal;

import static com.github.klboke.kkrepo.persistence.jdbc.internal.support.JdbcRows.nullableInstant;
import static com.github.klboke.kkrepo.persistence.jdbc.internal.support.JdbcRows.nullableLong;

import com.github.klboke.kkrepo.persistence.jdbc.api.BrowseNodeDao.BrowseChild;
import com.github.klboke.kkrepo.persistence.jdbc.internal.support.HashColumns;
import com.github.klboke.kkrepo.persistence.jdbc.internal.support.JdbcUpserts;
import java.nio.ByteBuffer;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
        Object[] updateArguments = {
            parentId, assetForRow != null, componentForRow, assetForRow != null, assetForRow,
            containsAsset,
            repositoryId, level.pathHash
        };
        JdbcUpserts.updateThenInsert(
            jdbcTemplate,
            """
            UPDATE browse_node
            SET parent_id = COALESCE(parent_id, ?),
                component_id = CASE WHEN ? THEN ? ELSE component_id END,
                asset_id = CASE WHEN ? THEN ? ELSE asset_id END,
                has_asset_subtree = CASE WHEN ? THEN true ELSE has_asset_subtree END
            WHERE repository_id = ? AND path_hash = ?
            """,
            updateArguments,
            """
            INSERT INTO browse_node
              (repository_id, parent_id, component_id, asset_id, path, path_hash,
               display_name, depth, has_asset_subtree)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            """,
            new Object[]{repositoryId, parentId, componentForRow, assetForRow, level.path,
                level.pathHash, level.displayName, level.depth, containsAsset});
        parentId = findIdByPathHash(repositoryId, level.pathHash);
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

  public int deleteByAssetId(long assetId) {
    Optional<NodeRef> leaf = findNodeByAssetId(assetId);
    int deleted = jdbcTemplate.update("DELETE FROM browse_node WHERE asset_id = ?", assetId);
    if (deleted > 0 && leaf.isPresent()) {
      refreshAndPruneAncestors(leaf.get().parentId());
    }
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

  private Optional<NodeRef> findNodeByAssetId(long assetId) {
    List<NodeRef> rows = jdbcTemplate.query(
        "SELECT parent_id FROM browse_node WHERE asset_id = ?",
        (rs, rowNum) -> new NodeRef(nullableLong(rs, "parent_id")),
        assetId);
    return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
  }

  private void refreshAndPruneAncestors(Long nodeId) {
    if (nodeId == null) return;
    List<AncestorNode> ancestors = listAncestorsWithChildStats(nodeId);
    if (ancestors.isEmpty()) return;

    List<Long> deleteIds = new ArrayList<>();
    List<AncestorUpdate> updates = new ArrayList<>();
    AncestorChange changedChild = null;
    for (AncestorNode node : ancestors) {
      long effectiveChildCount = node.childCount();
      long effectiveSubtreeChildCount = node.childSubtreeCount();
      if (changedChild != null) {
        if (changedChild.deleted()) {
          effectiveChildCount--;
        }
        if (changedChild.previousHasAssetSubtree()) {
          effectiveSubtreeChildCount--;
        }
        if (!changedChild.deleted() && changedChild.hasAssetSubtree()) {
          effectiveSubtreeChildCount++;
        }
      }
      effectiveChildCount = Math.max(0, effectiveChildCount);
      effectiveSubtreeChildCount = Math.max(0, effectiveSubtreeChildCount);

      boolean hasAssetSubtree = node.assetId() != null || effectiveSubtreeChildCount > 0;
      boolean delete = node.assetId() == null && effectiveChildCount == 0;
      if (delete) {
        deleteIds.add(node.id());
      } else if (node.hasAssetSubtree() != hasAssetSubtree) {
        updates.add(new AncestorUpdate(node.id(), hasAssetSubtree));
      }
      changedChild = new AncestorChange(delete, hasAssetSubtree, node.hasAssetSubtree());
    }

    deleteAncestors(deleteIds);
    updateAncestorSubtreeFlags(updates);
  }

  private List<AncestorNode> listAncestorsWithChildStats(long nodeId) {
    return jdbcTemplate.query("""
        WITH RECURSIVE ancestors AS (
          SELECT id, parent_id, asset_id, has_asset_subtree, 0 AS distance
          FROM browse_node
          WHERE id = ?
          UNION ALL
          SELECT parent_node.id, parent_node.parent_id, parent_node.asset_id,
                 parent_node.has_asset_subtree,
                 ancestors.distance + 1
          FROM browse_node parent_node
          JOIN ancestors ON parent_node.id = ancestors.parent_id
        ),
        child_stats AS (
          SELECT child_node.parent_id,
                 COUNT(child_node.id) AS child_count,
                 SUM(CASE WHEN child_node.has_asset_subtree THEN 1 ELSE 0 END) AS child_subtree_count
          FROM browse_node child_node
          JOIN ancestors ON child_node.parent_id = ancestors.id
          GROUP BY child_node.parent_id
        )
        SELECT ancestors.id,
               ancestors.asset_id,
               ancestors.has_asset_subtree,
               COALESCE(child_stats.child_count, 0) AS child_count,
               COALESCE(child_stats.child_subtree_count, 0) AS child_subtree_count
        FROM ancestors
        LEFT JOIN child_stats ON child_stats.parent_id = ancestors.id
        ORDER BY ancestors.distance
        """,
        (rs, rowNum) -> new AncestorNode(
            rs.getLong("id"),
            nullableLong(rs, "asset_id"),
            rs.getBoolean("has_asset_subtree"),
            rs.getLong("child_count"),
            rs.getLong("child_subtree_count")),
        nodeId);
  }

  private void deleteAncestors(List<Long> deleteIds) {
    if (deleteIds.isEmpty()) return;
    jdbcTemplate.update("DELETE FROM browse_node WHERE id IN (" + placeholders(deleteIds.size()) + ")",
        deleteIds.toArray());
  }

  private void updateAncestorSubtreeFlags(List<AncestorUpdate> updates) {
    if (updates.isEmpty()) return;
    StringBuilder sql = new StringBuilder("UPDATE browse_node SET has_asset_subtree = CASE id ");
    List<Object> args = new ArrayList<>(updates.size() * 3);
    for (AncestorUpdate update : updates) {
      sql.append("WHEN ? THEN ? ");
      args.add(update.id());
      args.add(update.hasAssetSubtree());
    }
    sql.append("END WHERE id IN (").append(placeholders(updates.size())).append(')');
    for (AncestorUpdate update : updates) {
      args.add(update.id());
    }
    jdbcTemplate.update(sql.toString(), args.toArray());
  }

  private String placeholders(int count) {
    StringBuilder builder = new StringBuilder(count * 2);
    for (int i = 0; i < count; i++) {
      if (i > 0) builder.append(',');
      builder.append('?');
    }
    return builder.toString();
  }

  private record NodeRef(Long parentId) {}

  private record AncestorNode(
      long id,
      Long assetId,
      boolean hasAssetSubtree,
      long childCount,
      long childSubtreeCount) {}

  private record AncestorUpdate(long id, boolean hasAssetSubtree) {}

  private record AncestorChange(
      boolean deleted,
      boolean hasAssetSubtree,
      boolean previousHasAssetSubtree) {}

}
