package com.github.klboke.kkrepo.server.pub;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.core.RepositoryType;
import com.github.klboke.kkrepo.protocol.pub.PubContentTypes;
import com.github.klboke.kkrepo.protocol.pub.PubPath;
import com.github.klboke.kkrepo.server.maven.MavenResponse;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntime;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PubGroupServiceTest {
  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final TypeReference<Map<String, Object>> JSON_MAP = new TypeReference<>() {
  };

  @Test
  void groupMergesMemberVersionsByOrderAndRewritesArchiveUrls() throws Exception {
    FakeHostedService hosted = new FakeHostedService(Map.of(
        "pub-a", metadata("example_package", List.of(
            version("1.0.0", "first"),
            version("2.0.0", "first"))),
        "pub-b", metadata("example_package", List.of(
            version("1.0.0", "second"),
            version("3.0.0", "second")))));
    PubGroupService service = new PubGroupService(hosted, null, MAPPER);

    MavenResponse response =
        service.packageMetadata(groupRuntime(), "example_package", "https://repo.test/repository/pub-group", false);

    Map<String, Object> body = readJson(response);
    assertEquals("3.0.0", ((Map<?, ?>) body.get("latest")).get("version"));
    List<?> versions = (List<?>) body.get("versions");
    assertEquals(List.of("1.0.0", "2.0.0", "3.0.0"),
        versions.stream().map(entry -> ((Map<?, ?>) entry).get("version")).toList());
    assertEquals("first", ((Map<?, ?>) ((Map<?, ?>) versions.get(0)).get("pubspec")).get("source"));
    assertEquals("https://repo.test/repository/pub-group/api/archives/example_package-1.0.0.tar.gz",
        ((Map<?, ?>) versions.get(0)).get("archive_url"));
  }

  @Test
  void groupMergesNestedGroupMembers() throws Exception {
    FakeHostedService hosted = new FakeHostedService(Map.of(
        "pub-a", metadata("example_package", List.of(version("1.0.0", "root"))),
        "pub-b", metadata("example_package", List.of(version("2.0.0", "nested")))));
    PubGroupService service = new PubGroupService(hosted, null, MAPPER);
    RepositoryRuntime nested = groupRuntime("pub-nested", 13L, List.of(hostedMember("pub-b", 12L)));
    RepositoryRuntime root = groupRuntime("pub-root", 10L, List.of(hostedMember("pub-a", 11L), nested));

    MavenResponse response =
        service.packageMetadata(root, "example_package", "https://repo.test/repository/pub-root", false);

    Map<String, Object> body = readJson(response);
    List<?> versions = (List<?>) body.get("versions");
    assertEquals(List.of("1.0.0", "2.0.0"),
        versions.stream().map(entry -> ((Map<?, ?>) entry).get("version")).toList());
    assertEquals("nested", ((Map<?, ?>) ((Map<?, ?>) versions.get(1)).get("pubspec")).get("source"));
    assertEquals("https://repo.test/repository/pub-root/api/archives/example_package-2.0.0.tar.gz",
        ((Map<?, ?>) versions.get(1)).get("archive_url"));
  }

  @Test
  void groupVersionJsonRoutesToFirstMemberContainingVersionWithoutGroupRewrite() throws Exception {
    FakeHostedService hosted = new FakeHostedService(Map.of(
        "pub-a", metadata("example_package", List.of(version("1.0.0", "first"))),
        "pub-b", metadata("example_package", List.of(version("1.0.0", "second")))));
    PubGroupService service = new PubGroupService(hosted, null, MAPPER);

    MavenResponse response = service.versionJson(
        groupRuntime(), "example_package", "1.0.0", "https://repo.test/repository/pub-group", false);

    assertEquals(PubContentTypes.VERSION_JSON, response.contentType());
    Map<String, Object> body = readJson(response);
    assertEquals("1.0.0", body.get("version"));
    assertEquals("https://repo.test/repository/pub-a/api/archives/example_package-1.0.0.tar.gz",
        body.get("archive_url"));
    assertEquals("first", ((Map<?, ?>) body.get("pubspec")).get("source"));
  }

  @Test
  void groupDownloadRoutesToFirstMemberContainingVersion() throws Exception {
    FakeHostedService hosted = new FakeHostedService(Map.of(
        "pub-a", metadata("example_package", List.of(version("1.0.0", "first"))),
        "pub-b", metadata("example_package", List.of(version("1.0.0", "second")))));
    PubGroupService service = new PubGroupService(hosted, null, MAPPER);

    MavenResponse response = service.download(groupRuntime(), "example_package", "1.0.0", false);

    assertEquals("pub-a:example_package:1.0.0", new String(response.body().readAllBytes(), StandardCharsets.UTF_8));
  }

  @Test
  void groupDownloadRoutesThroughNestedGroup() throws Exception {
    FakeHostedService hosted = new FakeHostedService(Map.of(
        "pub-b", metadata("example_package", List.of(version("2.0.0", "nested")))));
    PubGroupService service = new PubGroupService(hosted, null, MAPPER);
    RepositoryRuntime nested = groupRuntime("pub-nested", 13L, List.of(hostedMember("pub-b", 12L)));
    RepositoryRuntime root = groupRuntime("pub-root", 10L, List.of(nested));

    MavenResponse response = service.download(root, "example_package", "2.0.0", false);

    assertEquals("pub-b:example_package:2.0.0", new String(response.body().readAllBytes(), StandardCharsets.UTF_8));
  }

  @Test
  void groupPackageNamesAggregatesHostedAndNestedMembers() throws Exception {
    FakeHostedService hosted = new FakeHostedService(Map.of(
        "pub-a", metadata("alpha_package", List.of(version("1.0.0", "root"))),
        "pub-b", metadata("beta_package", List.of(version("2.0.0", "nested")))));
    PubGroupService service = new PubGroupService(hosted, null, MAPPER);
    RepositoryRuntime nested = groupRuntime("pub-nested", 13L, List.of(hostedMember("pub-b", 12L)));
    RepositoryRuntime root = groupRuntime("pub-root", 10L, List.of(hostedMember("pub-a", 11L), nested));

    MavenResponse response = service.get(root,
        new PubPath(PubPath.Kind.PACKAGE_NAMES, "api/package-names", null, null, null),
        "https://repo.test/repository/pub-root",
        false);

    Map<String, Object> body = readJson(response);
    assertEquals(List.of("alpha_package", "beta_package"), body.get("packages"));
  }

  @Test
  void groupCycleIsSkippedInsteadOfRecursingForever() {
    FakeHostedService hosted = new FakeHostedService(Map.of());
    PubGroupService service = new PubGroupService(hosted, null, MAPPER);
    List<RepositoryRuntime> members = new ArrayList<>();
    RepositoryRuntime root = groupRuntime("pub-root", 10L, members);
    members.add(root);

    org.junit.jupiter.api.Assertions.assertThrows(PubExceptions.PubNotFoundException.class,
        () -> service.packageMetadata(root, "example_package", "https://repo.test/repository/pub-root", false));
  }

  private static Map<String, Object> metadata(String name, List<Map<String, Object>> versions) {
    return Map.of("name", name, "latest", versions.get(versions.size() - 1), "versions", versions);
  }

  private static Map<String, Object> version(String version, String source) {
    return Map.of(
        "version", version,
        "archive_url", "https://member.test/packages/example_package/versions/" + version + ".tar.gz",
        "archive_sha256", source + "-sha",
        "pubspec", Map.of("name", "example_package", "version", version, "source", source));
  }

  private static Map<String, Object> readJson(MavenResponse response) throws IOException {
    try (var body = response.body()) {
      return MAPPER.readValue(body, JSON_MAP);
    }
  }

  private static RepositoryRuntime groupRuntime() {
    return groupRuntime("pub-group", 10L, List.of(hostedMember("pub-a", 11L), hostedMember("pub-b", 12L)));
  }

  private static RepositoryRuntime groupRuntime(String name, long id, List<RepositoryRuntime> members) {
    return new RepositoryRuntime(
        id,
        name,
        RepositoryFormat.PUB,
        RepositoryType.GROUP,
        "pub-group",
        true,
        1L,
        "ALLOW_ONCE",
        null,
        null,
        true,
        null,
        null,
        null,
        members);
  }

  private static RepositoryRuntime hostedMember(String name, long id) {
    return new RepositoryRuntime(
        id,
        name,
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

  private static final class FakeHostedService extends PubHostedService {
    private final Map<String, Map<String, Object>> metadataByRepository;

    FakeHostedService(Map<String, Map<String, Object>> metadataByRepository) {
      super(null, null, null, null, null, null, null, MAPPER);
      this.metadataByRepository = metadataByRepository;
    }

    @Override
    public MavenResponse get(RepositoryRuntime runtime, PubPath path, String baseUrl, boolean headOnly) {
      if (path.kind() == PubPath.Kind.PACKAGE_NAMES) {
        Map<String, Object> body = metadataByRepository.get(runtime.name());
        List<String> packages = body == null ? List.of() : List.of(String.valueOf(body.get("name")));
        return PubResponses.json(MAPPER, Map.of("packages", packages), 200, headOnly);
      }
      return super.get(runtime, path, baseUrl, headOnly);
    }

    @Override
    MavenResponse packageMetadata(RepositoryRuntime runtime, String packageName, String baseUrl, boolean headOnly) {
      Map<String, Object> body = metadataByRepository.get(runtime.name());
      if (body == null) {
        throw new PubExceptions.PubNotFoundException(packageName);
      }
      return PubResponses.json(MAPPER, body, 200, null, Instant.parse("2026-07-08T00:00:00Z"), headOnly);
    }

    @Override
    MavenResponse versionJson(
        RepositoryRuntime runtime,
        String packageName,
        String version,
        String baseUrl,
        boolean headOnly) {
      Map<String, Object> body = metadataByRepository.get(runtime.name());
      if (body == null) {
        throw new PubExceptions.PubNotFoundException(packageName);
      }
      return PubProxyService.versions(body).stream()
          .filter(entry -> version.equals(String.valueOf(entry.get("version"))))
          .findFirst()
          .map(entry -> {
            java.util.LinkedHashMap<String, Object> rewritten = new java.util.LinkedHashMap<>(entry);
            rewritten.put("archive_url", baseUrl + "/api/archives/" + packageName + "-" + version + ".tar.gz");
            return PubResponses.json(MAPPER, rewritten, 200, null,
                Instant.parse("2026-07-08T00:00:00Z"), PubContentTypes.VERSION_JSON, headOnly);
          })
          .orElseThrow(() -> new PubExceptions.PubNotFoundException(packageName + " " + version));
    }

    @Override
    MavenResponse download(RepositoryRuntime runtime, String packageName, String version, boolean headOnly) {
      String body = runtime.name() + ":" + packageName + ":" + version;
      return MavenResponse.ok(
          new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)),
          body.length(),
          "application/octet-stream",
          null,
          Instant.parse("2026-07-08T00:00:00Z"));
    }
  }
}
