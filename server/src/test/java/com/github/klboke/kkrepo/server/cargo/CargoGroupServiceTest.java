package com.github.klboke.kkrepo.server.cargo;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.core.RepositoryType;
import com.github.klboke.kkrepo.protocol.cargo.CargoPath;
import com.github.klboke.kkrepo.server.maven.MavenResponse;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntime;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CargoGroupServiceTest {
  private static final TypeReference<Map<String, Object>> JSON_MAP = new TypeReference<>() {
  };

  @Test
  void groupConfigAdvertisesNexusCompatibleDownloadBase() throws Exception {
    ObjectMapper mapper = new ObjectMapper();
    CargoGroupService service = new CargoGroupService(null, null, mapper);

    MavenResponse response = service.get(
        runtime(),
        new CargoPath(CargoPath.Kind.CONFIG, "config.json", null, null),
        "http://localhost/repository/cargo-group",
        false);

    Map<String, Object> config = mapper.readValue(
        new String(response.body().readAllBytes(), StandardCharsets.UTF_8),
        JSON_MAP);
    assertEquals("http://localhost/repository/cargo-group/crates", config.get("dl"));
    assertEquals("http://localhost/repository/cargo-group/", config.get("api"));
  }

  @Test
  void groupIndexAndDownloadKeepFirstMemberForDuplicateVersion() throws Exception {
    ObjectMapper mapper = new ObjectMapper();
    StubCargoHostedService hosted = new StubCargoHostedService("""
        {"name":"demo","vers":"1.0.0","cksum":"hosted-checksum","yanked":false}
        """, "hosted-crate");
    StubCargoProxyService proxy = new StubCargoProxyService("""
        {"name":"demo","vers":"1.0.0","cksum":"proxy-checksum","yanked":false}
        {"name":"demo","vers":"1.1.0","cksum":"proxy-newer","yanked":false}
        """, "proxy-crate");
    CargoGroupService service = new CargoGroupService(hosted, proxy, mapper);
    RepositoryRuntime group = runtime(hostedRuntime(), proxyRuntime());

    MavenResponse index = service.index(group, "demo", false);
    String body = new String(index.body().readAllBytes(), StandardCharsets.UTF_8);
    assertEquals(true, body.contains("\"cksum\":\"hosted-checksum\""));
    assertEquals(false, body.contains("\"cksum\":\"proxy-checksum\""));
    assertEquals(true, body.contains("\"cksum\":\"proxy-newer\""));

    MavenResponse download = service.download(group, "demo", "1.0.0", false);
    assertEquals("hosted-crate", new String(download.body().readAllBytes(), StandardCharsets.UTF_8));
  }

  private static RepositoryRuntime runtime() {
    return runtime(new RepositoryRuntime[0]);
  }

  private static RepositoryRuntime runtime(RepositoryRuntime... members) {
    return new RepositoryRuntime(
        1L,
        "cargo-group",
        RepositoryFormat.CARGO,
        RepositoryType.GROUP,
        "cargo-group",
        true,
        1L,
        null,
        null,
        null,
        true,
        null,
        null,
        null,
        List.of(members));
  }

  private static RepositoryRuntime hostedRuntime() {
    return new RepositoryRuntime(
        2L,
        "cargo-hosted",
        RepositoryFormat.CARGO,
        RepositoryType.HOSTED,
        "cargo-hosted",
        true,
        1L,
        "ALLOW",
        null,
        null,
        true,
        null,
        null,
        null,
        List.of());
  }

  private static RepositoryRuntime proxyRuntime() {
    return new RepositoryRuntime(
        3L,
        "cargo-proxy",
        RepositoryFormat.CARGO,
        RepositoryType.PROXY,
        "cargo-proxy",
        true,
        1L,
        null,
        null,
        null,
        true,
        "https://index.crates.io/",
        1440,
        1440,
        List.of());
  }

  private static final class StubCargoHostedService extends CargoHostedService {
    private final String indexBody;
    private final String downloadBody;

    private StubCargoHostedService(String indexBody, String downloadBody) {
      super(null, null, null, null, null, null, new ObjectMapper());
      this.indexBody = indexBody;
      this.downloadBody = downloadBody;
    }

    @Override
    MavenResponse index(RepositoryRuntime runtime, String crateName, boolean headOnly) {
      byte[] bytes = indexBody.getBytes(StandardCharsets.UTF_8);
      return MavenResponse.ok(new ByteArrayInputStream(bytes), bytes.length, "text/plain", null, null);
    }

    @Override
    MavenResponse download(RepositoryRuntime runtime, String crateName, String version, boolean headOnly) {
      byte[] bytes = downloadBody.getBytes(StandardCharsets.UTF_8);
      return MavenResponse.ok(new ByteArrayInputStream(bytes), bytes.length, "application/x-tar", null, null);
    }
  }

  private static final class StubCargoProxyService extends CargoProxyService {
    private final String indexBody;
    private final String downloadBody;

    private StubCargoProxyService(String indexBody, String downloadBody) {
      super(null, null, null, null, null, null, null, null, new ObjectMapper());
      this.indexBody = indexBody;
      this.downloadBody = downloadBody;
    }

    @Override
    MavenResponse index(RepositoryRuntime runtime, String crateName, boolean headOnly) {
      byte[] bytes = indexBody.getBytes(StandardCharsets.UTF_8);
      return MavenResponse.ok(new ByteArrayInputStream(bytes), bytes.length, "text/plain", null, null);
    }

    @Override
    MavenResponse download(RepositoryRuntime runtime, String crateName, String version, boolean headOnly) {
      byte[] bytes = downloadBody.getBytes(StandardCharsets.UTF_8);
      return MavenResponse.ok(new ByteArrayInputStream(bytes), bytes.length, "application/x-tar", null, null);
    }
  }
}
