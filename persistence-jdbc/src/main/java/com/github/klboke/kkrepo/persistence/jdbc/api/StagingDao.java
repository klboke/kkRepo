package com.github.klboke.kkrepo.persistence.jdbc.api;

import com.github.klboke.kkrepo.persistence.jdbc.api.model.StagingItemRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.StagingGroupChangeRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.StagingPromotionRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.StagingRepositoryRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.StagingSessionRecord;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * Shared durable state for multi-repository staging sessions.
 *
 * <p>Every mutating method is safe to call from any replica. Lifecycle transitions use the session
 * revision and a locked current row so two promoters can never apply the same session twice.
 */
public interface StagingDao {
  String SESSION_OPEN = "OPEN";
  String SESSION_FROZEN = "FROZEN";
  String SESSION_PREFLIGHTED = "PREFLIGHTED";
  String SESSION_PROMOTING = "PROMOTING";
  String SESSION_PROMOTED = "PROMOTED";
  String SESSION_FAILED = "FAILED";
  String SESSION_CANCELLED = "CANCELLED";

  String ITEM_ADD = "ADD";
  String ITEM_REPLACE = "REPLACE";
  String ITEM_DELETE = "DELETE";

  String ITEM_STAGED = "STAGED";
  String ITEM_VALIDATED = "VALIDATED";
  String ITEM_CONFLICT = "CONFLICT";
  String ITEM_PROMOTED = "PROMOTED";
  String ITEM_FAILED = "FAILED";

  String PROMOTION_PREFLIGHT = "PREFLIGHT";
  String PROMOTION_RUNNING = "RUNNING";
  String PROMOTION_SUCCEEDED = "SUCCEEDED";
  String PROMOTION_FAILED = "FAILED";
  String PROMOTION_ROLLED_BACK = "ROLLED_BACK";

  String GROUP_ADD = "ADD";
  String GROUP_REMOVE = "REMOVE";
  String GROUP_STAGED = "STAGED";
  String GROUP_VALIDATED = "VALIDATED";
  String GROUP_CONFLICT = "CONFLICT";
  String GROUP_PROMOTED = "PROMOTED";
  String GROUP_FAILED = "FAILED";

  long insertSession(StagingSessionRecord record);

  Optional<StagingSessionRecord> findSessionById(long id);

  Optional<StagingSessionRecord> findSessionByName(String name);

  /** Locks the session row; call inside the transaction that owns the lifecycle transition. */
  Optional<StagingSessionRecord> lockSession(long id);

  Optional<StagingSessionRecord> lockSessionByName(String name);

  List<StagingSessionRecord> listSessions(String state, int limit);

  /** Returns active sessions whose expiry has passed, oldest deadline first. */
  List<StagingSessionRecord> listExpiredActive(Instant now, int limit);

  /**
   * Applies one lifecycle transition when the locked row still has the expected revision and one
   * of the allowed states.
   */
  boolean transitionSession(
      long id,
      long expectedRevision,
      Collection<String> allowedStates,
      String nextState,
      Instant frozenAt,
      Instant finishedAt,
      Long promotionId);

  boolean updateSessionMetadata(
      long id, long expectedRevision, Instant expiresAt, Map<String, Object> attributes);

  void addRepository(long sessionId, long repositoryId, String format);

  boolean removeRepository(long sessionId, long repositoryId);

  boolean hasRepository(long sessionId, long repositoryId);

  List<StagingRepositoryRecord> listRepositories(long sessionId);

  /**
   * Locks target repository rows in ascending id order, returning only ids that exist.
   *
   * <p>Promotions always acquire repository locks in this order, which keeps concurrent
   * multi-repository promotions from deadlocking each other.
   */
  List<Long> lockRepositories(Collection<Long> repositoryIds);

  OptionalLong tryInsertItem(StagingItemRecord record);

  Optional<StagingItemRecord> findItem(long sessionId, long repositoryId, byte[] logicalPathHash);

  Optional<StagingItemRecord> lockItem(long itemId);

  List<StagingItemRecord> listItems(long sessionId);

  List<StagingItemRecord> listItems(long sessionId, String status);

  int updateItem(StagingItemRecord record);

  int updateItemStatus(long itemId, String status);

  int deleteItem(long itemId);

  int deleteItems(long sessionId);

  Optional<StagingGroupChangeRecord> findGroupChange(
      long sessionId, long groupRepositoryId, long memberRepositoryId);

  List<StagingGroupChangeRecord> listGroupChanges(long sessionId);

  void upsertGroupChange(StagingGroupChangeRecord record);

  int updateGroupChangeStatus(long changeId, String status);

  int deleteGroupChange(long sessionId, long changeId);

  int deleteGroupChanges(long sessionId);

  Map<String, Long> countSessionsByState();

  Map<String, Long> countItemsByStatus();

  Map<String, Long> countGroupChangesByStatus();

  int deleteSession(long sessionId);


  long insertPromotion(StagingPromotionRecord record);

  Optional<StagingPromotionRecord> findPromotion(long promotionId);

  Optional<StagingPromotionRecord> lockPromotion(long promotionId);

  Optional<StagingPromotionRecord> findLatestPromotion(long sessionId);

  int updatePromotion(
      long promotionId,
      String state,
      String manifestSha256,
      Map<String, Object> report,
      Instant startedAt,
      Instant finishedAt);
}
