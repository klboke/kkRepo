package com.github.klboke.kkrepo.server.cache;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.github.klboke.kkrepo.persistence.jdbc.api.CacheVersionDao;
import org.junit.jupiter.api.Test;

class JdbcVersionWatermarkTest {
  @Test
  void durableReadsBypassTheNodeLocalVersionCache() {
    CacheVersionDao dao = mock(CacheVersionDao.class);
    when(dao.current("repo:7:CONTENT")).thenReturn(1L, 2L);
    JdbcVersionWatermark watermark = new JdbcVersionWatermark(dao, 60);

    assertEquals(1L, watermark.current("repo:7:CONTENT"));
    assertEquals(1L, watermark.current("repo:7:CONTENT"));
    assertEquals(2L, watermark.currentDurable("repo:7:CONTENT"));
    assertEquals(1L, watermark.current("repo:7:CONTENT"));

    verify(dao, times(2)).current("repo:7:CONTENT");
  }
}
