package com.github.klboke.kkrepo.server.pub;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.core.RepositoryType;
import com.github.klboke.kkrepo.persistence.jdbc.api.ComponentDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.PubUploadSessionDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.ComponentRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.PubUploadSessionRecord;
import com.github.klboke.kkrepo.protocol.pub.PubContentTypes;
import com.github.klboke.kkrepo.server.maven.MavenResponse;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntime;
import com.github.klboke.kkrepo.server.support.dao.ComponentDaoAdapter;
import java.io.IOException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class PubHostedServiceTest {
  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final TypeReference<Map<String, Object>> JSON_MAP = new TypeReference<>() {
  };

  @Test
  void packageMetadataUsesPubContentTypeStableArchiveUrlsAndSemverLatest() throws Exception {
    FakeComponentDao components = new FakeComponentDao(List.of(
        component("example_package", "1.0.0-dev.1", "aaa", Instant.parse("2026-07-01T00:00:00Z")),
        component("example_package", "1.0.0", "bbb", Instant.parse("2026-07-02T00:00:00Z")),
        component("example_package", "1.10.0", "ccc", Instant.parse("2026-07-03T00:00:00Z")),
        component("example_package", "1.2.0", "ddd", Instant.parse("2026-07-04T00:00:00Z")),
        component("example_package", "2.0.0-dev.1", "eee", Instant.parse("2026-07-05T00:00:00Z"))));
    PubHostedService service = new PubHostedService(
        null, components, null, null, null, null, null, MAPPER);

    MavenResponse response =
        service.packageMetadata(runtime(), "example_package", "https://repo.test/repository/pub-hosted", false);

    assertEquals(200, response.status());
    assertEquals(PubContentTypes.JSON, response.contentType());
    Map<String, Object> body = readJson(response);
    assertEquals("example_package", body.get("name"));
    assertEquals("1.10.0", ((Map<?, ?>) body.get("latest")).get("version"));
    List<?> versions = (List<?>) body.get("versions");
    assertEquals(List.of("1.0.0-dev.1", "1.0.0", "1.2.0", "1.10.0", "2.0.0-dev.1"),
        versions.stream().map(entry -> ((Map<?, ?>) entry).get("version")).toList());
    Map<?, ?> stable = (Map<?, ?>) versions.get(1);
    assertEquals("https://repo.test/repository/pub-hosted/api/archives/example_package-1.0.0.tar.gz",
        stable.get("archive_url"));
    assertEquals("bbb", stable.get("archive_sha256"));
  }

  @Test
  void versionJsonUsesGenericJsonContentType() throws Exception {
    FakeComponentDao components = new FakeComponentDao(List.of(
        component("example_package", "1.0.0", "bbb", Instant.parse("2026-07-02T00:00:00Z"))));
    PubHostedService service = new PubHostedService(
        null, components, null, null, null, null, null, MAPPER);

    MavenResponse response =
        service.versionJson(runtime(), "example_package", "1.0.0", "https://repo.test/repository/pub-hosted", false);

    assertEquals(200, response.status());
    assertEquals(PubContentTypes.VERSION_JSON, response.contentType());
    Map<String, Object> body = readJson(response);
    assertEquals("1.0.0", body.get("version"));
    assertEquals("https://repo.test/repository/pub-hosted/api/archives/example_package-1.0.0.tar.gz",
        body.get("archive_url"));
  }

  @Test
  void unknownHostedPackageReturnsPubNotFound() {
    PubHostedService service = new PubHostedService(
        null, new FakeComponentDao(List.of()), null, null, null, null, null, MAPPER);

    assertThrows(PubExceptions.PubNotFoundException.class,
        () -> service.packageMetadata(runtime(), "missing_package", "https://repo.test/repository/pub-hosted", false));
  }

  @Test
  void finalizedUploadSessionCanBeRetriedAfterTtlExpires() {
    PubUploadSessionRecord session = session(PubUploadSessionDao.STATUS_FINALIZED, Instant.EPOCH);

    PubHostedService.validateSessionUsable(
        session, PubUploadSessionDao.STATUS_UPLOADED, "alice", 42L);
  }

  @Test
  void uploadFormTokenIsRequired() {
    PubUploadSessionRecord session = session(PubUploadSessionDao.STATUS_NEW, Instant.now().plusSeconds(60));

    assertThrows(PubExceptions.BadRequestException.class,
        () -> PubHostedService.validateFieldToken(session, Map.of()));
    assertThrows(PubExceptions.BadRequestException.class,
        () -> PubHostedService.validateFieldToken(session, Map.of("token", "wrong")));
    PubHostedService.validateFieldToken(session, Map.of("token", "field-token"));
  }

  private static ComponentRecord component(String name, String version, String sha256, Instant updatedAt) {
    Map<String, Object> pubspec = new LinkedHashMap<>();
    pubspec.put("name", name);
    pubspec.put("version", version);
    Map<String, Object> attrs = new LinkedHashMap<>();
    attrs.put("archiveSha256", sha256);
    attrs.put("archivePath", "packages/" + name + "/versions/" + version + ".tar.gz");
    attrs.put("pubspec", pubspec);
    attrs.put("publishedAt", updatedAt.toString());
    return new ComponentRecord(
        null,
        1L,
        RepositoryFormat.PUB,
        null,
        name,
        version,
        "pub-package",
        new byte[] {1},
        attrs,
        updatedAt);
  }

  private static PubUploadSessionRecord session(String status, Instant expiresAt) {
    return new PubUploadSessionRecord(
        1L,
        1L,
        "session-1",
        "field-token",
        "alice",
        42L,
        status,
        expiresAt,
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
        Map.of(),
        null,
        PubUploadSessionDao.STATUS_FINALIZED.equals(status) ? Instant.now() : null,
        Instant.now(),
        Instant.now());
  }

  private static Map<String, Object> readJson(MavenResponse response) throws IOException {
    try (var body = response.body()) {
      return MAPPER.readValue(body, JSON_MAP);
    }
  }

  private static RepositoryRuntime runtime() {
    return new RepositoryRuntime(
        1L,
        "pub-hosted",
        RepositoryFormat.PUB,
        RepositoryType.HOSTED,
        "pub-hosted",
        true,
        1L,
        "ALLOW_ONCE",
        null,
        null,
        true,
        null,
        null,
        null,
        List.of());
  }

  private static final class FakeComponentDao extends ComponentDaoAdapter {
    private final List<ComponentRecord> components;

    FakeComponentDao(List<ComponentRecord> components) {
      super(null, null);
      this.components = components;
    }

    @Override
    public List<ComponentRecord> listByName(long repositoryId, String name) {
      return components.stream()
          .filter(component -> component.repositoryId() == repositoryId && component.name().equals(name))
          .toList();
    }

    @Override
    public Optional<ComponentRecord> findByNameAndVersion(long repositoryId, String name, String version) {
      return components.stream()
          .filter(component -> component.repositoryId() == repositoryId
              && component.name().equals(name)
              && component.version().equals(version))
          .findFirst();
    }
  }
}
