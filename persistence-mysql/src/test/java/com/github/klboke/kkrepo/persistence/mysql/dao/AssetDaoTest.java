package com.github.klboke.kkrepo.persistence.jdbc.internal;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.klboke.kkrepo.persistence.jdbc.api.*;
import org.junit.jupiter.api.Test;

class AssetDaoTest {
  @Test
  void reusableBlobIdSqlSeparatesDeletedPredicateWithoutGapLocking() {
    String liveSql = JdbcAssetDao.reusableBlobIdSql(false);
    String deletedOnlySql = JdbcAssetDao.reusableBlobIdSql(true);

    assertFalse(liveSql.contains("ANDdeleted_at"));
    assertFalse(deletedOnlySql.contains("ANDdeleted_at"));
    assertFalse(liveSql.contains("NULLORDER"));
    assertFalse(deletedOnlySql.contains("NULLORDER"));
    assertFalse(liveSql.contains("FOR UPDATE"));
    assertFalse(deletedOnlySql.contains("FOR UPDATE"));
    assertTrue(liveSql.contains("SELECT id"));
    assertTrue(liveSql.contains("AND deleted_at IS NULL\n"));
    assertTrue(deletedOnlySql.contains("AND deleted_at IS NOT NULL\n"));
  }
}
