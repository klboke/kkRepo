package com.github.klboke.kkrepo.server.conan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.core.RepositoryType;
import com.github.klboke.kkrepo.persistence.jdbc.api.ComponentDao;
import com.github.klboke.kkrepo.protocol.conan.ConanReference;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntime;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class ConanComponentServiceTest {
  private final ComponentDao components = mock(ComponentDao.class);
  private final ConanComponentService service = new ConanComponentService(components);

  @Test
  void projectsStableRecipeComponentsFromPublicationCoordinates() {
    RepositoryRuntime runtime = runtime(RepositoryFormat.CONAN, RepositoryType.HOSTED);
    ConanReference reference = new ConanReference(
        "demo", "1.0", "acme", "stable", "rrev", "pkg", "prev");
    Instant updated = Instant.parse("2026-08-01T00:00:00Z");

    var component = service.component(runtime, reference, updated);

    assertEquals(RepositoryFormat.CONAN, component.format());
    assertEquals("acme/stable", component.namespace());
    assertEquals("demo", component.name());
    assertEquals("1.0", component.version());
    assertEquals("demo/1.0@acme/stable", component.attributes().get("recipe"));
    assertEquals(updated, component.lastUpdatedAt());
    assertNotEquals(0, component.coordinateHash().length);
  }

  @Test
  void rejectsGroupsAndNonConanRepositoriesAndDeletesOnlyPersistedIds() {
    assertThrows(IllegalArgumentException.class, () -> service.component(
        null, new ConanReference("demo", "1.0", null, null, "r", null, null), null));
    assertThrows(IllegalArgumentException.class, () -> service.component(
        runtime(RepositoryFormat.MAVEN2, RepositoryType.HOSTED),
        new ConanReference("demo", "1.0", null, null, "r", null, null), null));
    assertThrows(IllegalArgumentException.class, () -> service.component(
        runtime(RepositoryFormat.CONAN, RepositoryType.GROUP),
        new ConanReference("demo", "1.0", null, null, "r", null, null), null));

    service.deleteIfNoAssets(0);
    service.deleteIfNoAssets(-1);
    verify(components, never()).deleteIfNoAssets(0);
    service.deleteIfNoAssets(9);
    verify(components).deleteIfNoAssets(9);
  }

  private static RepositoryRuntime runtime(RepositoryFormat format, RepositoryType type) {
    return new RepositoryRuntime(
        1L, "conan", format, type, "conan-hosted", true, 1L, "ALLOW",
        null, null, true, null, null, null, List.of());
  }
}
