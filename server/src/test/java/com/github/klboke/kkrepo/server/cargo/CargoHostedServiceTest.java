package com.github.klboke.kkrepo.server.cargo;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.core.RepositoryType;
import com.github.klboke.kkrepo.protocol.cargo.CargoPath;
import com.github.klboke.kkrepo.server.maven.MavenResponse;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntime;
import java.nio.charset.StandardCharsets;
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
        false);

    Map<String, Object> config = mapper.readValue(
        new String(response.body().readAllBytes(), StandardCharsets.UTF_8),
        JSON_MAP);
    assertEquals("http://localhost/repository/cargo-hosted/crates", config.get("dl"));
    assertEquals("http://localhost/repository/cargo-hosted/", config.get("api"));
    assertEquals(true, config.get("auth-required"));
  }

  private static RepositoryRuntime runtime(boolean requireAuthentication) {
    return new RepositoryRuntime(
        1L,
        "cargo-hosted",
        RepositoryFormat.CARGO,
        RepositoryType.HOSTED,
        "cargo-hosted",
        true,
        1L,
        "ALLOW_ONCE",
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
}
