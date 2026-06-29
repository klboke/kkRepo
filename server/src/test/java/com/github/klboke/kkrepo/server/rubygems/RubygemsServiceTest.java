package com.github.klboke.kkrepo.server.rubygems;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.core.RepositoryType;
import com.github.klboke.kkrepo.persistence.mysql.dao.AssetDao;
import com.github.klboke.kkrepo.persistence.mysql.dao.RepositoryIndexRebuildDao;
import com.github.klboke.kkrepo.persistence.mysql.model.AssetBlobRecord;
import com.github.klboke.kkrepo.persistence.mysql.model.AssetRecord;
import com.github.klboke.kkrepo.server.maven.MavenExceptions;
import com.github.klboke.kkrepo.server.maven.MavenResponse;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntime;
import com.github.klboke.kkrepo.server.raw.RawHostedService;
import com.github.klboke.kkrepo.server.raw.RawProxyService;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import java.util.zip.InflaterInputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.junit.jupiter.api.Test;

class RubygemsServiceTest {
  @Test
  void parsesGemMetadataYaml() {
    RubygemsService.GemMetadata metadata = RubygemsService.parseMetadataYaml("""
        --- !ruby/object:Gem::Specification
        name: demo
        version: !ruby/object:Gem::Version
          version: 1.2.3
        platform: ruby
        summary: Demo summary
        authors:
        - alice
        - bob
        dependencies:
        - !ruby/object:Gem::Dependency
          name: rack
          requirement: !ruby/object:Gem::Requirement
            requirements:
            - - ">="
              - !ruby/object:Gem::Version
                version: 2.0.0
        """);

    assertEquals("demo", metadata.name());
    assertEquals("1.2.3", metadata.version());
    assertEquals("ruby", metadata.platform());
    assertEquals("Demo summary", metadata.summary());
    assertEquals(List.of("alice", "bob"), metadata.authors());
    assertEquals("rack", metadata.dependencies().getFirst().name());
    assertEquals(List.of(">= 2.0.0"), metadata.dependencies().getFirst().requirements());
  }

  @Test
  void buildsLegacySpecsMarshalArray() {
    byte[] marshal = RubygemsService.rubyMarshalSpecs(List.of(
        new RubygemsService.GemMetadata("demo", "1.0.0", "ruby", List.of())));

    assertEquals(4, marshal[0]);
    assertEquals(8, marshal[1]);
    assertEquals('[', marshal[2]);
    assertTrue(new String(marshal).contains("Gem::Version"));
  }

  @Test
  void compactIndexEndpointsUseStoredGemMetadata() throws Exception {
    RubygemsService service = new RubygemsService(null, null, null, new FakeAssetDao(List.of(
        gemAsset(1L, "gems/demo-1.0.0.gem",
            new RubygemsService.GemMetadata("demo", "1.0.0", "ruby", List.of())),
        gemAsset(2L, "gems/demo-1.2.0.gem",
            new RubygemsService.GemMetadata("demo", "1.2.0", "ruby",
                List.of(new RubygemsService.GemDependency("rack", List.of(">= 2.0.0", "< 3.0.0"))))),
        gemAsset(3L, "gems/other-0.1.0-java.gem",
            new RubygemsService.GemMetadata("other", "0.1.0", "java", List.of())))));

    assertEquals("demo\nother\n", body(service.get(hosted(), "names", false)));
    String demoInfo = "---\n"
        + "1.0.0 |checksum:" + blobSha256(1L) + "\n"
        + "1.2.0 rack:>= 2.0.0&< 3.0.0|checksum:" + blobSha256(2L) + "\n";
    assertEquals(demoInfo, body(service.get(hosted(), "info/demo", false)));
    String otherInfo = "---\n0.1.0-java |checksum:" + blobSha256(3L) + "\n";
    assertEquals(otherInfo, body(service.get(hosted(), "info/other", false)));

    String versions = body(service.get(hosted(), "versions", false));
    assertTrue(versions.startsWith("created_at: "), versions);
    assertTrue(versions.contains("---\ndemo 1.0.0,1.2.0 " + md5(demoInfo) + "\n"), versions);
    assertTrue(versions.contains("other 0.1.0-java " + md5(otherInfo) + "\n"), versions);
  }

  @Test
  void compactInfoBackfillsGeneratedInfoAssetWhenMissing() throws Exception {
    FakeRawHostedService hosted = new FakeRawHostedService(404);
    RubygemsService service = new RubygemsService(hosted, null, null, new FakeAssetDao(List.of(
        gemAsset(1L, "gems/demo-1.0.0.gem",
            new RubygemsService.GemMetadata("demo", "1.0.0", "ruby", List.of())),
        gemAsset(2L, "gems/demo-extra-2.0.0.gem",
            new RubygemsService.GemMetadata("demo-extra", "2.0.0", "ruby", List.of())))));

    String body = body(service.get(hosted(), "info/demo", false));

    assertEquals("---\n1.0.0 |checksum:" + blobSha256(1L) + "\n", body);
    assertEquals("info/demo", hosted.generatedPath);
    assertEquals(body, new String(hosted.generatedBytes, StandardCharsets.UTF_8));
  }

  @Test
  void pushUpdatesPerGemMetadataAndEnqueuesGlobalIndexRebuild() throws Exception {
    FakeRawHostedService hosted = new FakeRawHostedService(204);
    RecordingIndexRebuildDao indexRebuildDao = new RecordingIndexRebuildDao();
    RubygemsService.GemMetadata metadata =
        new RubygemsService.GemMetadata("demo", "1.2.0", "ruby", List.of());
    RubygemsService service = new RubygemsService(
        hosted,
        null,
        null,
        new FakeAssetDao(List.of(gemAsset(1L, "gems/demo-1.2.0.gem", metadata))),
        indexRebuildDao);

    MavenResponse response = service.push(hosted(),
        new ByteArrayInputStream(gemPackage("""
            --- !ruby/object:Gem::Specification
            name: demo
            version: !ruby/object:Gem::Version
              version: 1.2.0
            platform: ruby
            summary: Demo
            """)),
        null,
        "tester",
        "127.0.0.1");

    assertEquals(201, response.status());
    assertEquals("gems/demo-1.2.0.gem", hosted.putPath);
    assertTrue(hosted.generatedPaths.contains("info/demo"));
    assertTrue(hosted.generatedPaths.contains("quick/Marshal.4.8/demo-1.2.0.gemspec.rz"));
    assertFalse(hosted.generatedPaths.contains("specs.4.8.gz"));
    assertFalse(hosted.generatedPaths.contains("latest_specs.4.8.gz"));
    assertFalse(hosted.generatedPaths.contains("versions"));
    assertFalse(hosted.generatedPaths.contains("names"));
    assertEquals(List.of("1:" + RepositoryIndexRebuildDao.RUBYGEMS_METADATA + ":"),
        indexRebuildDao.enqueues);
  }

  @Test
  void specsLatestAndPrereleaseModesFilterStoredGemMetadata() throws Exception {
    RubygemsService service = new RubygemsService(null, null, null, new FakeAssetDao(List.of(
        gemAsset(1L, "gems/demo-1.0.0.gem",
            new RubygemsService.GemMetadata("demo", "1.0.0", "ruby", List.of())),
        gemAsset(2L, "gems/demo-2.0.0.gem",
            new RubygemsService.GemMetadata("demo", "2.0.0", "ruby", List.of())),
        gemAsset(3L, "gems/demo-3.0.0-beta.gem",
            new RubygemsService.GemMetadata("demo", "3.0.0-beta", "ruby", List.of())))));

    byte[] latest = gunzip(bytes(service.get(hosted(), "latest_specs.4.8.gz", false)));
    String latestPayload = new String(latest, StandardCharsets.ISO_8859_1);
    assertEquals(6, latest[3]);
    assertTrue(latestPayload.contains("2.0.0"));
    assertFalse(latestPayload.contains("1.0.0"));
    assertFalse(latestPayload.contains("3.0.0-beta"));

    byte[] prerelease = gunzip(bytes(service.get(hosted(), "prerelease_specs.4.8.gz", false)));
    String prereleasePayload = new String(prerelease, StandardCharsets.ISO_8859_1);
    assertEquals(6, prerelease[3]);
    assertTrue(prereleasePayload.contains("3.0.0-beta"));
    assertFalse(prereleasePayload.contains("1.0.0"));
    assertFalse(prereleasePayload.contains("2.0.0"));
  }

  @Test
  void groupSpecsDeduplicateSameGemCoordinateAcrossMembers() throws Exception {
    RubygemsService service = new RubygemsService(null, null, null, new FakeAssetDao(List.of(
        gemAsset(11L, 101L, "gems/demo-1.0.0.gem",
            new RubygemsService.GemMetadata("demo", "1.0.0", "ruby", List.of())),
        gemAsset(12L, 201L, "gems/demo-1.0.0.gem",
            new RubygemsService.GemMetadata("demo", "1.0.0", "ruby", List.of())),
        gemAsset(12L, 202L, "gems/other-2.0.0.gem",
            new RubygemsService.GemMetadata("other", "2.0.0", "ruby", List.of())))));

    byte[] specs = gunzip(bytes(service.get(
        runtime(99L, RepositoryType.GROUP, null, List.of(
            runtime(11L, RepositoryType.HOSTED, null, List.of()),
            runtime(12L, RepositoryType.HOSTED, null, List.of()))),
        "specs.4.8.gz", false)));
    String payload = new String(specs, StandardCharsets.ISO_8859_1);

    assertEquals(7, specs[3]);
    assertEquals(1, countOccurrences(payload, "demo"));
    assertEquals(1, countOccurrences(payload, "other"));
  }

  @Test
  void quickMarshalGemspecHasStablePayloadShape() throws Exception {
    RubygemsService service = new RubygemsService(null, null, null, new FakeAssetDao(List.of()));

    byte[] deflated = bytes(service.get(hosted(), "quick/Marshal.4.8/demo-1.2.0.gemspec.rz", false));
    byte[] inflated = inflate(deflated);
    String payload = new String(inflated, StandardCharsets.ISO_8859_1);

    assertEquals("4acc63289ee87e0ae3d68974412dcbd69065935c40e03e565302cbf7fea60b3a", sha256(inflated));
    assertEquals(4, inflated[0]);
    assertEquals(8, inflated[1]);
    assertEquals('u', inflated[2]);
    assertTrue(payload.contains("Gem::Specification"));
    assertTrue(payload.contains("3.0.6"));
    assertTrue(payload.contains("Gem::Version"));
    assertTrue(payload.contains("demo"));
    assertTrue(payload.contains("1.2.0"));
    assertEquals(deflated.length,
        service.get(hosted(), "quick/Marshal.4.8/demo-1.2.0.gemspec.rz", true).contentLength());
  }

  @Test
  void quickMarshalGemspecUsesStoredDependencies() throws Exception {
    RubygemsService service = new RubygemsService(null, null, null, new FakeAssetDao(List.of(
        gemAsset(1L, "gems/demo-1.2.0.gem",
            new RubygemsService.GemMetadata("demo", "1.2.0", "ruby",
                List.of(new RubygemsService.GemDependency("rack", List.of(">= 2.0.0", "< 3.0.0"))))))));

    byte[] inflated = inflate(bytes(service.get(hosted(), "quick/Marshal.4.8/demo-1.2.0.gemspec.rz", false)));
    String payload = new String(inflated, StandardCharsets.ISO_8859_1);

    assertEquals('u', inflated[2]);
    assertTrue(payload.contains("Gem::Specification"));
    assertTrue(payload.contains("Gem::Dependency"));
    assertTrue(payload.contains("Gem::Requirement"));
    assertTrue(payload.contains("rack"));
    assertTrue(payload.contains(">="));
    assertTrue(payload.contains("2.0.0"));
    assertTrue(payload.contains("<"));
    assertTrue(payload.contains("3.0.0"));
  }

  @Test
  void readsGemMetadataFromTarMetadataGz() throws Exception {
    Path gem = Files.createTempFile("kkrepo-rubygems-test-", ".gem");
    try {
      try (TarArchiveOutputStream tar = new TarArchiveOutputStream(Files.newOutputStream(gem))) {
        tarEntry(tar, "metadata.gz", gzip("""
            --- !ruby/object:Gem::Specification
            name: tar-demo
            version: !ruby/object:Gem::Version
              version: 2.3.4
            platform: ruby
            summary: Tar demo summary
            authors:
            - codex
            dependencies:
            - !ruby/object:Gem::Dependency
              name: rake
              requirement: !ruby/object:Gem::Requirement
                requirements:
                - - "~>"
                  - !ruby/object:Gem::Version
                    version: 13.0
            """.getBytes(StandardCharsets.UTF_8)));
        tarEntry(tar, "data.tar.gz", gzip(new byte[0]));
      }

      RubygemsService.GemMetadata metadata = RubygemsService.readGemMetadata(gem);

      assertEquals("tar-demo", metadata.name());
      assertEquals("2.3.4", metadata.version());
      assertEquals("ruby", metadata.platform());
      assertEquals("Tar demo summary", metadata.summary());
      assertEquals(List.of("codex"), metadata.authors());
      assertEquals("rake", metadata.dependencies().getFirst().name());
      assertEquals(List.of("~> 13.0"), metadata.dependencies().getFirst().requirements());
    } finally {
      Files.deleteIfExists(gem);
    }
  }

  @Test
  void yankDeletesGemCoordinateAndRejectsMissingIdentity() {
    FakeRawHostedService hosted = new FakeRawHostedService(204);
    RecordingIndexRebuildDao indexRebuildDao = new RecordingIndexRebuildDao();
    RubygemsService service = new RubygemsService(hosted, null, null, new FakeAssetDao(List.of()), indexRebuildDao);

    MavenResponse response = service.delete(hosted(), "api/v1/gems/yank?gem_name=demo&version=1.2.0");

    assertEquals(204, response.status());
    assertEquals("gems/demo-1.2.0.gem", hosted.deletedPaths.get(0));
    assertTrue(hosted.deletedPaths.contains("info/demo"));
    assertTrue(hosted.deletedPaths.contains("quick/Marshal.4.8/demo-1.2.0.gemspec.rz"));
    assertFalse(hosted.generatedPaths.contains("specs.4.8.gz"));
    assertFalse(hosted.generatedPaths.contains("latest_specs.4.8.gz"));
    assertFalse(hosted.generatedPaths.contains("versions"));
    assertFalse(hosted.generatedPaths.contains("names"));
    assertEquals(List.of("1:" + RepositoryIndexRebuildDao.RUBYGEMS_METADATA + ":"),
        indexRebuildDao.enqueues);
    assertThrows(MavenExceptions.MethodNotAllowed.class,
        () -> service.delete(hosted(), "api/v1/gems/yank?gem_name=demo"));
  }

  @Test
  void proxyReadPropagatesBadUpstream() {
    FakeRawProxyService proxy = new FakeRawProxyService(
        new MavenExceptions.BadUpstreamException("upstream timeout"));
    RubygemsService service = new RubygemsService(null, proxy, null, null);

    MavenExceptions.BadUpstreamException error = assertThrows(MavenExceptions.BadUpstreamException.class,
        () -> service.get(proxy(), "api/v1/dependencies?gems=rack", false));

    assertEquals("upstream timeout", error.getMessage());
    assertEquals("https://rubygems.org/api/v1/dependencies?gems=rack", proxy.lastRemoteUrl);
  }

  @Test
  void parsesDependencyApiGemFilter() {
    assertEquals(
        java.util.Set.of("rack", "rails"),
        RubygemsService.requestedGemNames("api/v1/dependencies?gems=rack,rails"));
  }

  @Test
  void dependencyApiUsesBundlerDependencyConstraintShape() throws Exception {
    RubygemsService service = new RubygemsService(null, null, null, new FakeAssetDao(List.of(
        gemAsset(1L, "gems/demo-1.2.0.gem",
            new RubygemsService.GemMetadata("demo", "1.2.0", "ruby",
                List.of(new RubygemsService.GemDependency("rack", List.of(">= 2.0.0", "< 3.0.0"))))))));

    byte[] marshal = bytes(service.get(hosted(), "api/v1/dependencies?gems=demo", false));
    String payload = new String(marshal, StandardCharsets.ISO_8859_1);

    assertEquals(4, marshal[0]);
    assertEquals(8, marshal[1]);
    assertTrue(payload.contains("rack"));
    assertTrue(payload.contains(">= 2.0.0, < 3.0.0"));
    assertDependencyApiUsesRequirementMap(marshal, "rack");
  }

  @Test
  void proxyRemoteUrlPreservesDependencyQuery() {
    assertEquals(
        "https://rubygems.org/api/v1/dependencies?gems=rack",
        RubygemsService.remoteUrlForPath(proxy(), "api/v1/dependencies?gems=rack"));
  }

  private static String body(MavenResponse response) throws Exception {
    return new String(bytes(response), StandardCharsets.UTF_8);
  }

  private static byte[] bytes(MavenResponse response) throws Exception {
    try (InputStream body = response.body()) {
      return body.readAllBytes();
    }
  }

  private static byte[] inflate(byte[] bytes) throws Exception {
    try (InflaterInputStream inflater = new InflaterInputStream(new ByteArrayInputStream(bytes))) {
      return inflater.readAllBytes();
    }
  }

  private static byte[] gunzip(byte[] bytes) throws Exception {
    try (GZIPInputStream gzip = new GZIPInputStream(new ByteArrayInputStream(bytes))) {
      return gzip.readAllBytes();
    }
  }

  private static byte[] gzip(byte[] bytes) throws Exception {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    try (GZIPOutputStream gzip = new GZIPOutputStream(out)) {
      gzip.write(bytes);
    }
    return out.toByteArray();
  }

  private static byte[] gemPackage(String metadataYaml) throws Exception {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    try (TarArchiveOutputStream tar = new TarArchiveOutputStream(out)) {
      tarEntry(tar, "metadata.gz", gzip(metadataYaml.getBytes(StandardCharsets.UTF_8)));
      tarEntry(tar, "data.tar.gz", gzip(new byte[0]));
    }
    return out.toByteArray();
  }

  private static void tarEntry(TarArchiveOutputStream tar, String name, byte[] bytes) throws Exception {
    TarArchiveEntry entry = new TarArchiveEntry(name);
    entry.setSize(bytes.length);
    tar.putArchiveEntry(entry);
    tar.write(bytes);
    tar.closeArchiveEntry();
  }

  private static String md5(String value) throws Exception {
    return digest("MD5", value.getBytes(StandardCharsets.UTF_8));
  }

  private static String sha256(byte[] bytes) throws Exception {
    return digest("SHA-256", bytes);
  }

  private static String blobSha256(long blobId) {
    return "%064x".formatted(blobId);
  }

  private static String digest(String algorithm, byte[] bytes) throws Exception {
    return HexFormat.of().formatHex(MessageDigest.getInstance(algorithm).digest(bytes));
  }

  private static int countOccurrences(String value, String needle) {
    int count = 0;
    int index = 0;
    while ((index = value.indexOf(needle, index)) >= 0) {
      count++;
      index += needle.length();
    }
    return count;
  }

  private static void assertDependencyApiUsesRequirementMap(byte[] marshal, String dependencyName) {
    byte[] needle = dependencyName.getBytes(StandardCharsets.ISO_8859_1);
    int index = indexOf(marshal, needle);
    assertTrue(index >= 0, "dependency name not found in marshal payload");
    int afterName = index + needle.length;
    assertTrue(index > 0 && marshal[index - 1] == dependencyName.length() + 5,
        "dependency name should be encoded as a Ruby string key");
    while (afterName < marshal.length && marshal[afterName] != 'I') {
      afterName++;
    }
    assertTrue(afterName < marshal.length, "dependency requirement string not found");
  }

  private static int indexOf(byte[] value, byte[] needle) {
    outer:
    for (int i = 0; i <= value.length - needle.length; i++) {
      for (int j = 0; j < needle.length; j++) {
        if (value[i + j] != needle[j]) {
          continue outer;
        }
      }
      return i;
    }
    return -1;
  }

  private static RepositoryRuntime hosted() {
    return runtime(1L, RepositoryType.HOSTED, null, List.of());
  }

  private static RepositoryRuntime proxy() {
    return runtime(2L, RepositoryType.PROXY, "https://rubygems.org/", List.of());
  }

  private static RepositoryRuntime runtime(
      long id, RepositoryType type, String remoteUrl, List<RepositoryRuntime> members) {
    return new RepositoryRuntime(
        id,
        "rubygems-" + type.name().toLowerCase(),
        RepositoryFormat.RUBYGEMS,
        type,
        "rubygems-" + type.name().toLowerCase(),
        true,
        1L,
        null,
        null,
        null,
        true,
        remoteUrl,
        1440,
        1440,
        true,
        null,
        members);
  }

  private static AssetRecord gemAsset(long blobId, String path, RubygemsService.GemMetadata metadata) {
    return gemAsset(1L, blobId, path, metadata);
  }

  private static AssetRecord gemAsset(
      long repositoryId,
      long blobId,
      String path,
      RubygemsService.GemMetadata metadata) {
    return new AssetRecord(
        blobId,
        repositoryId,
        null,
        blobId,
        RepositoryFormat.RUBYGEMS,
        path,
        new byte[] {1},
        path,
        metadata.name(),
        "application/octet-stream",
        1234L,
        null,
        Instant.parse("2026-06-09T00:00:00Z"),
        Map.of(
            "rubygems.name", metadata.name(),
            "rubygems.version", metadata.version(),
            "rubygems.platform", metadata.platform(),
            "rubygems.dependencies", dependenciesAttr(metadata.dependencies())));
  }

  private static String dependenciesAttr(List<RubygemsService.GemDependency> dependencies) {
    StringBuilder out = new StringBuilder();
    for (RubygemsService.GemDependency dep : dependencies) {
      if (!out.isEmpty()) out.append('\n');
      out.append(dep.name()).append('|').append(String.join(",", dep.requirements()));
    }
    return out.toString();
  }

  private static final class FakeAssetDao extends AssetDao {
    private final List<AssetRecord> assets;

    FakeAssetDao(List<AssetRecord> assets) {
      super(null, null);
      this.assets = assets;
    }

    @Override
    public List<AssetRecord> listAssetsByPrefix(long repositoryId, String pathPrefix) {
      return assets.stream()
          .filter(asset -> asset.repositoryId() == repositoryId)
          .filter(asset -> asset.path().startsWith(pathPrefix))
          .toList();
    }

    @Override
    public Optional<AssetRecord> findAssetByPath(long repositoryId, String path) {
      return assets.stream()
          .filter(asset -> asset.repositoryId() == repositoryId)
          .filter(asset -> asset.path().equals(path))
          .findFirst();
    }

    @Override
    public Optional<AssetBlobRecord> findBlobById(long assetBlobId) {
      return Optional.of(blob(assetBlobId));
    }

    @Override
    public Map<Long, AssetBlobRecord> findBlobsByIds(java.util.Collection<Long> assetBlobIds) {
      Map<Long, AssetBlobRecord> blobs = new java.util.LinkedHashMap<>();
      for (Long assetBlobId : assetBlobIds) {
        if (assetBlobId != null) {
          blobs.put(assetBlobId, blob(assetBlobId));
        }
      }
      return blobs;
    }

    private AssetBlobRecord blob(long assetBlobId) {
      AssetRecord asset = assets.stream()
          .filter(candidate -> candidate.assetBlobId() != null && candidate.assetBlobId() == assetBlobId)
          .findFirst()
          .orElseThrow();
      return new AssetBlobRecord(
          assetBlobId,
          1L,
          "blob",
          new byte[] {1},
          "object",
          new byte[] {1},
          "sha1",
          blobSha256(assetBlobId),
          "md5",
          asset.size(),
          asset.contentType(),
          "test",
          "127.0.0.1",
          Instant.parse("2026-06-09T00:00:00Z"),
          Instant.parse("2026-06-09T00:00:00Z"),
          asset.attributes());
    }
  }

  private static final class FakeRawHostedService extends RawHostedService {
    private final int deleteStatus;
    private String deletedPath;
    private String putPath;
    private String generatedPath;
    private byte[] generatedBytes;
    private final List<String> deletedPaths = new ArrayList<>();
    private final List<String> generatedPaths = new ArrayList<>();

    FakeRawHostedService(int deleteStatus) {
      super(null, null, null, null, null);
      this.deleteStatus = deleteStatus;
    }

    @Override
    public MavenResponse get(RepositoryRuntime runtime, String rawPath, boolean headOnly) {
      throw new MavenExceptions.MavenNotFoundException(rawPath);
    }

    @Override
    public MavenResponse putWithAttributes(RepositoryRuntime runtime, String rawPath, InputStream body,
        String contentType, Map<String, ?> blobAttributes, String createdBy, String createdByIp) {
      putPath = rawPath;
      return MavenResponse.created();
    }

    @Override
    public MavenResponse putGenerated(RepositoryRuntime runtime, String rawPath, InputStream body,
        String contentType, String createdBy, String createdByIp) {
      try {
        generatedPath = rawPath;
        generatedBytes = body.readAllBytes();
        generatedPaths.add(rawPath);
        return MavenResponse.created();
      } catch (Exception e) {
        throw new IllegalStateException(e);
      }
    }

    @Override
    public MavenResponse delete(RepositoryRuntime runtime, String rawPath) {
      deletedPath = rawPath;
      deletedPaths.add(rawPath);
      return MavenResponse.noBody(deleteStatus);
    }
  }

  private static final class RecordingIndexRebuildDao extends RepositoryIndexRebuildDao {
    private final List<String> enqueues = new ArrayList<>();

    RecordingIndexRebuildDao() {
      super(null);
    }

    @Override
    public void enqueue(long repositoryId, String indexKind) {
      enqueues.add(repositoryId + ":" + indexKind + ":");
    }
  }

  private static final class FakeRawProxyService extends RawProxyService {
    private final RuntimeException failure;
    private String lastRemoteUrl;

    FakeRawProxyService(RuntimeException failure) {
      super(null, null, null, null, null, null, null, null);
      this.failure = failure;
    }

    @Override
    public MavenResponse getAssetFromUrl(RepositoryRuntime runtime, String path, String remoteUrl, boolean headOnly) {
      lastRemoteUrl = remoteUrl;
      throw failure;
    }
  }
}
