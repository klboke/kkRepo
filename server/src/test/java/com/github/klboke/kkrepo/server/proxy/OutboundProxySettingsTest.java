package com.github.klboke.kkrepo.server.proxy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.github.klboke.kkrepo.core.RepositoryType;
import com.github.klboke.kkrepo.persistence.jdbc.api.OutboundProxySettingsDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.OutboundProxySettingsDao.Settings;
import com.github.klboke.kkrepo.persistence.jdbc.api.ProxyStateDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.RepositoryDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.RepositoryRecord;
import com.github.klboke.kkrepo.server.proxy.OutboundProxySettings.Command;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class OutboundProxySettingsTest {
  private final OutboundProxySettingsDao dao = mock(OutboundProxySettingsDao.class);
  private final RepositoryDao repositories = mock(RepositoryDao.class);
  private final ProxyStateDao backoff = mock(ProxyStateDao.class);
  private final OutboundProxySettings settings = new OutboundProxySettings(dao, repositories, backoff);

  @BeforeEach
  void savedProxy() {
    when(dao.read()).thenReturn(new Settings(3, true, "HTTP", "proxy.example", 3128, "user",
        "saved-secret", List.of()));
  }

  @Test
  void savesCanonicalSettingsAndResetsOnlyInheritingRepositoryBackoff() {
    var controller = new OutboundProxySettingsController(settings);
    assertTrue(controller.read().passwordConfigured());
    when(dao.replace(any())).thenReturn(true);
    when(repositories.list()).thenReturn(List.of(repository(1L, RepositoryType.PROXY, "INHERIT"),
        repository(2L, RepositoryType.PROXY, "DIRECT"), repository(3L, RepositoryType.HOSTED, "DIRECT")));

    var saved = controller.save(new Command(3L, true, " socks5 ", " proxy.example ", 1080,
        " user ", "", false, List.of("*.EXAMPLE.COM.", "EXACT.EXAMPLE.COM", "exact.example.com",
            "[2001:db8::1]")));

    var written = ArgumentCaptor.forClass(Settings.class);
    verify(dao).replace(written.capture());
    assertEquals("saved-secret", written.getValue().password());
    assertEquals(List.of("*.example.com", "exact.example.com", "2001:db8:0:0:0:0:0:1"), saved.bypassHosts());
    assertEquals("SOCKS", saved.type());
    assertEquals(4, saved.revision());
    assertTrue(saved.passwordConfigured());
    verify(backoff).clearBackoff(1L);
    org.mockito.Mockito.verifyNoMoreInteractions(backoff);
  }

  @Test
  void unsavedProbeCanClearOrReplaceCredentialsWithoutPersistingThem() {
    var runtime = settings.runtimeProxy();
    assertEquals("saved-secret", runtime.password());
    assertEquals(3L, runtime.systemRevision());
    var cleared = settings.testProxy(new Command(3L, false, null, null, null, "", null, true, null));
    assertFalse(cleared.enabled());
    assertNull(cleared.password());
    var replaced = settings.testProxy(new Command(3L, true, "HTTP", "2001:db8::3", 8080,
        "user", "new-secret", false, null));
    assertTrue(replaced.enabled());
    assertEquals("new-secret", replaced.password());
    verifyNoInteractions(repositories, backoff);
    org.mockito.Mockito.verify(dao, org.mockito.Mockito.never()).replace(any());
  }

  @Test
  void staleRevisionAndLostCompareAndSetAreConflicts() {
    assertStatus(HttpStatus.CONFLICT, new Command(2L, true, "HTTP", "proxy.example",
        3128, "user", null, false, null));
    assertStatus(HttpStatus.CONFLICT, new Command(3L, true, "HTTP", "proxy.example",
        3128, "user", null, false, null));
    verifyNoInteractions(repositories, backoff);
  }

  @Test
  void rejectsInvalidProxyAndBypassSettings() {
    assertStatus(HttpStatus.BAD_REQUEST, null);
    assertStatus(HttpStatus.BAD_REQUEST, new Command(null, true, "HTTP", null, null, null, null, false, null));
    assertStatus(HttpStatus.BAD_REQUEST, new Command(3L, true, "HTTPS", "proxy.example", 3128,
        "user", null, false, null));
    assertStatus(HttpStatus.BAD_REQUEST, new Command(3L, true, "HTTP", "x:y:z", 3128,
        "user", null, false, null));
    assertStatus(HttpStatus.BAD_REQUEST, new Command(3L, true, "HTTP", "proxy.example", 3128,
        null, "secret", false, null));
    assertStatus(HttpStatus.BAD_REQUEST, new Command(3L, false, null, null, null, "user", null,
        true, Collections.nCopies(101, "example.com")));
    for (String entry : new String[] {null, "*.localhost", "*.127.0.0.1", "a".repeat(256), "a..example.com"}) {
      assertStatus(HttpStatus.BAD_REQUEST, new Command(3L, true, "HTTP", "proxy.example", 3128,
          "user", null, false, Collections.singletonList(entry)));
    }
    org.mockito.Mockito.verify(dao, org.mockito.Mockito.never()).replace(any());
  }

  private void assertStatus(HttpStatus status, Command command) {
    assertEquals(status, assertThrows(ResponseStatusException.class, () -> settings.save(command)).getStatusCode());
  }

  private static RepositoryRecord repository(long id, RepositoryType type, String mode) {
    return new RepositoryRecord(id, "repository-" + id, null, type, null, true, null, null, null,
        null, null, null, false, Map.of("proxy", Map.of("outboundProxyMode", mode)));
  }
}
