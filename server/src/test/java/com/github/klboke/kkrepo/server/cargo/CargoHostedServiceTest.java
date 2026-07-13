package com.github.klboke.kkrepo.server.cargo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.core.RepositoryType;
import com.github.klboke.kkrepo.persistence.jdbc.api.ComponentDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.ComponentRecord;
import com.github.klboke.kkrepo.protocol.cargo.CargoPath;
import com.github.klboke.kkrepo.persistence.jdbc.api.PersistenceHashes;
import com.github.klboke.kkrepo.server.maven.MavenResponse;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntime;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CargoHostedServiceTest {
  private static final TypeReference<Map<String, Object>> JSON_MAP = new TypeReference<>() {
  };

  @Test
  void hostedConfigAlwaysAdvertisesAuthRequiredLikeNexus() throws Exception {
    ObjectMapper mapper = new ObjectMapper();
    CargoHostedService service = new CargoHostedService(null, null, null, null, null, null, mapper);

    MavenResponse response = service.get(
        runtime(false),
        new CargoPath(CargoPath.Kind.CONFIG, "config.json", null, null),
        "http://localhost/repository/cargo-hosted",
        new CargoSearchQuery("", 10, 1),
        false);

    Map<String, Object> config = mapper.readValue(
        new String(response.body().readAllBytes(), StandardCharsets.UTF_8),
        JSON_MAP);
    assertEquals("http://localhost/repository/cargo-hosted/crates", config.get("dl"));
    assertEquals("http://localhost/repository/cargo-hosted/", config.get("api"));
    assertEquals(true, config.get("auth-required"));
  }

  @Test
  @SuppressWarnings("unchecked")
  void hostedSearchReturnsCargoWebApiResultsFromCurrentRepositoryOnly() throws Exception {
    ObjectMapper mapper = new ObjectMapper();
    ComponentDao componentDao = mock(ComponentDao.class);
    ComponentRecord demo = component("demo-crate", "1.2.3", "A demo crate");
    when(componentDao.searchComponentsByRepositoryIds(
        List.of(1L), RepositoryFormat.CARGO, "demo", 40))
        .thenReturn(List.of(demo));
    CargoHostedService service = new CargoHostedService(null, componentDao, null, null, null, null, mapper);

    MavenResponse response = service.get(
        runtime(false),
        new CargoPath(CargoPath.Kind.SEARCH, "api/v1/crates", null, null),
        "http://localhost/repository/cargo-hosted",
        new CargoSearchQuery("demo", 10, 1),
        false);

    Map<String, Object> body = mapper.readValue(
        new String(response.body().readAllBytes(), StandardCharsets.UTF_8),
        JSON_MAP);
    List<Map<String, Object>> crates = (List<Map<String, Object>>) body.get("crates");
    assertEquals(1, crates.size());
    assertEquals("demo-crate", crates.getFirst().get("name"));
    assertEquals("1.2.3", crates.getFirst().get("max_version"));
    assertEquals("A demo crate", crates.getFirst().get("description"));
    assertEquals(Map.of("total", 1), body.get("meta"));
  }

  @Test
  @SuppressWarnings("unchecked")
  void hostedSearchDeduplicatesCrateNamesBeforePagination() throws Exception {
    ObjectMapper mapper = new ObjectMapper();
    ComponentDao componentDao = mock(ComponentDao.class);
    when(componentDao.searchComponentsByRepositoryIds(
        List.of(1L), RepositoryFormat.CARGO, "", 8))
        .thenReturn(List.of(
            component("demo-crate", "1.2.3", "first"),
            component("demo_crate", "1.2.2", "duplicate"),
            component("itoa", "1.0.15", "second")));
    CargoHostedService service = new CargoHostedService(null, componentDao, null, null, null, null, mapper);

    MavenResponse response = service.get(
        runtime(false),
        new CargoPath(CargoPath.Kind.SEARCH, "api/v1/crates", null, null),
        "http://localhost/repository/cargo-hosted",
        new CargoSearchQuery("", 1, 2),
        false);

    Map<String, Object> body = mapper.readValue(
        new String(response.body().readAllBytes(), StandardCharsets.UTF_8),
        JSON_MAP);
    List<Map<String, Object>> crates = (List<Map<String, Object>>) body.get("crates");
    assertEquals(1, crates.size());
    assertEquals("itoa", crates.getFirst().get("name"));
    assertEquals(Map.of("total", 2), body.get("meta"));
  }

  @Test
  void yankHonorsHostedWritePolicyDeny() {
    CargoHostedService service = new CargoHostedService(null, mock(ComponentDao.class), null, null, null, null,
        new ObjectMapper());

    assertThrows(CargoExceptions.WritePolicyDenied.class,
        () -> service.yank(runtime(false, "DENY"), "demo-crate", "1.2.3", true));
  }

  private static RepositoryRuntime runtime(boolean requireAuthentication) {
    return runtime(requireAuthentication, "ALLOW_ONCE");
  }

  private static RepositoryRuntime runtime(boolean requireAuthentication, String writePolicy) {
    return new RepositoryRuntime(
        1L,
        "cargo-hosted",
        RepositoryFormat.CARGO,
        RepositoryType.HOSTED,
        "cargo-hosted",
        true,
        1L,
        writePolicy,
        null,
        null,
        true,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        requireAuthentication,
        List.of());
  }

  private static ComponentRecord component(String name, String version, String description) {
    Map<String, Object> attrs = Map.of(
        "crateName", name,
        "normalizedName", name.replace('-', '_'),
        "version", version,
        "description", description);
    return new ComponentRecord(
        10L,
        1L,
        RepositoryFormat.CARGO,
        null,
        name.replace('-', '_'),
        version,
        "crate",
        PersistenceHashes.componentCoordinateHash(null, name.replace('-', '_'), version),
        attrs,
        Instant.parse("2026-06-28T00:00:00Z"));
  }
}
