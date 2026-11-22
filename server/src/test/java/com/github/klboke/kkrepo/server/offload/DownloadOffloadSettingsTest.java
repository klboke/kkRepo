package com.github.klboke.kkrepo.server.offload;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.github.klboke.kkrepo.persistence.jdbc.api.BlobStoreDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.DownloadOffloadSettingsDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.DownloadOffloadSettingsDao.Settings;
import com.github.klboke.kkrepo.persistence.jdbc.api.RepositoryDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.BlobStoreRecord;
import com.github.klboke.kkrepo.server.cache.VersionWatermark;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.server.ResponseStatusException;

class DownloadOffloadSettingsTest {
  private final DownloadOffloadSettingsDao dao = mock(DownloadOffloadSettingsDao.class);
  private final BlobStoreDao stores = mock(BlobStoreDao.class);
  private final RepositoryDao repositories = mock(RepositoryDao.class);
  private final VersionWatermark versions = mock(VersionWatermark.class);
  private final DownloadOffloadSettings settings =
      new DownloadOffloadSettings(dao, stores, repositories, versions, "10.20.0.10/32");

  @Test
  void reusesSnapshotUntilTheVersionChanges() {
    var before = new Settings(0, false, "nginx", "X-KkRepo-File", "internal-uri", List.of());
    var enabled = new Settings(1, true, "nginx", "X-KkRepo-File", "internal-uri", List.of(12L));
    when(versions.current(DownloadOffloadSettings.VERSION)).thenReturn(0L, 0L, 1L);
    when(dao.read()).thenReturn(before, enabled);

    assertFalse(settings.runtime().enabled());
    assertFalse(settings.runtime().enabled());
    assertTrue(settings.runtime().enabled());
    verify(dao, times(2)).read();
  }

  @Test
  void requiresAFileStoreAndPublishesTheVersionOnlyAfterAValidUpdate() {
    when(stores.list()).thenReturn(List.of(
        new BlobStoreRecord(12L, "shared", "file", null, null, null, null, Map.of()),
        new BlobStoreRecord(90L, "cloud", "s3", null, null, null, null, Map.of())));
    var invalid = assertThrows(ResponseStatusException.class,
        () -> settings.save(command(90L, "X-KkRepo-File")));
    assertEquals(HttpStatus.BAD_REQUEST, invalid.getStatusCode());
    assertThrows(ResponseStatusException.class, () -> settings.save(command(12L, "Location")));
    var row = new Settings(0, true, "custom", "X-KkRepo-File", "internal-uri", List.of(12L));
    when(dao.replace(row)).thenReturn(true, false);

    assertEquals(List.of(12L), settings.save(command(12L, "X-KkRepo-File")).storeIds());
    verify(versions).bump(DownloadOffloadSettings.VERSION);
    var stale = assertThrows(ResponseStatusException.class, () -> settings.save(command(12L, "X-KkRepo-File")));
    assertEquals(HttpStatus.CONFLICT, stale.getStatusCode());
  }

  @Test
  void trustUsesTheImmediatePeer() {
    var request = new MockHttpServletRequest();
    request.setRemoteAddr("10.20.0.11");
    request.addHeader("X-Forwarded-For", "10.20.0.10");
    assertFalse(settings.trusted(request));
    request.setRemoteAddr("10.20.0.10");
    assertTrue(settings.trusted(request));
  }

  private static DownloadOffloadSettings.Command command(long id, String header) {
    return new DownloadOffloadSettings.Command(0L, true, "custom", header, "internal-uri", List.of(id));
  }
}
