package com.github.klboke.kkrepo.server.securityscan;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.persistence.jdbc.api.PersistenceHashes;
import com.github.klboke.kkrepo.persistence.jdbc.api.SecurityScanDao.ScanProfile;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetBlobRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetRecord;
import com.github.klboke.kkrepo.security.scan.ScanEnums.CandidateDisposition;
import com.github.klboke.kkrepo.security.scan.ScanEnums.OciPlatformPolicy;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

class SecurityScanCandidateClassifierTest {
  private final SecurityScanCandidateClassifier classifier = new SecurityScanCandidateClassifier();

  @ParameterizedTest
  @MethodSource("packages")
  void classifiesProtocolPackages(RepositoryFormat format, String path, String kind) {
    assertEquals(
        CandidateDisposition.SCANNABLE,
        classifier.classify(asset(format, path, kind), blob(42), profile(1024))
            .disposition());
  }

  static Stream<Arguments> packages() {
    return Stream.of(
        Arguments.of(RepositoryFormat.MAVEN2, "com/acme/demo/1/demo-1.jar", "artifact"),
        Arguments.of(RepositoryFormat.NPM, "demo/-/demo-1.0.0.tgz", "tarball"),
        Arguments.of(RepositoryFormat.PYPI, "packages/demo-1.0.0-py3-none-any.whl", "distribution"),
        Arguments.of(RepositoryFormat.GO, "example.com/demo/@v/v1.0.0.zip", "zip"),
        Arguments.of(RepositoryFormat.HELM, "demo-1.0.0.tgz", "chart"),
        Arguments.of(RepositoryFormat.CARGO, "api/v1/crates/demo/1.0.0/download.crate", "crate"),
        Arguments.of(RepositoryFormat.PUB, "packages/demo/versions/1.0.0.tar.gz", "archive"),
        Arguments.of(RepositoryFormat.COMPOSER, "dist/demo-1.0.0.zip", "dist"),
        Arguments.of(RepositoryFormat.TERRAFORM, "modules/acme/vpc/1.0.0.zip", "module"),
        Arguments.of(RepositoryFormat.SWIFT, "acme/demo/1.0.0.zip", "source-archive"),
        Arguments.of(RepositoryFormat.ANSIBLEGALAXY, "artifacts/acme-demo-1.0.0.tar.gz", "collection-artifact"),
        Arguments.of(RepositoryFormat.NUGET, "flat/demo/1.0.0/demo.1.0.0.nupkg", "package"),
        Arguments.of(RepositoryFormat.RUBYGEMS, "gems/demo-1.0.0.gem", "gem"),
        Arguments.of(RepositoryFormat.YUM, "packages/demo-1.0.0.x86_64.rpm", "package"),
        Arguments.of(RepositoryFormat.RAW, "release/demo.zip", "file"));
  }

  @ParameterizedTest
  @ValueSource(strings = {
      "modules/acme/vpc/1.0.0.tar.xz",
      "modules/acme/vpc/1.0.0.txz",
      "modules/acme/vpc/1.0.0.xz",
      "modules/acme/vpc/1.0.0.tar.bz2",
      "modules/acme/vpc/1.0.0.tbz2"
  })
  void classifiesEverySupportedTerraformArchive(String path) {
    assertEquals(
        CandidateDisposition.SCANNABLE,
        classifier.classify(
            asset(RepositoryFormat.TERRAFORM, path, "module"), blob(42), profile(1024))
            .disposition());
  }

  @ParameterizedTest
  @MethodSource("metadata")
  void skipsProtocolMetadata(RepositoryFormat format, String path, String kind) {
    assertEquals(
        CandidateDisposition.NOT_APPLICABLE,
        classifier.classify(asset(format, path, kind), blob(42), profile(1024))
            .disposition());
  }

  static Stream<Arguments> metadata() {
    return Stream.of(
        Arguments.of(RepositoryFormat.MAVEN2, "com/acme/maven-metadata.xml", "metadata"),
        Arguments.of(RepositoryFormat.NPM, "demo", "packument"),
        Arguments.of(RepositoryFormat.PYPI, "simple/demo/index.html", "index"),
        Arguments.of(RepositoryFormat.GO, "example.com/demo/@v/v1.0.0.info", "info"),
        Arguments.of(RepositoryFormat.HELM, "index.yaml", "index"),
        Arguments.of(RepositoryFormat.CARGO, "index/de/mo/demo", "index"),
        Arguments.of(RepositoryFormat.TERRAFORM, "providers/demo/1.0.0_SHA256SUMS", "checksum"),
        Arguments.of(RepositoryFormat.NUGET, "registration/demo/index.json", "metadata"),
        Arguments.of(RepositoryFormat.YUM, "repodata/primary.xml.gz", "metadata"));
  }

  @Test
  void rejectsOversizedInputExplicitly() {
    assertEquals(
        CandidateDisposition.REJECTED_BY_LIMIT,
        classifier.classify(
            asset(RepositoryFormat.MAVEN2, "demo.jar", "artifact"), blob(2048), profile(1024))
            .disposition());
  }

  private static AssetRecord asset(RepositoryFormat format, String path, String kind) {
    return new AssetRecord(
        1L, 1L, null, 1L, format, path, PersistenceHashes.pathHash(path),
        path.substring(path.lastIndexOf('/') + 1), kind, "application/octet-stream",
        42L, null, Instant.EPOCH, Map.of());
  }

  private static AssetBlobRecord blob(long size) {
    return new AssetBlobRecord(
        1L, 1L, "blob://test/object", PersistenceHashes.blobRefHash("blob://test/object"),
        "object", PersistenceHashes.objectKeyHash("object"), "1".repeat(40), "a".repeat(64),
        "2".repeat(32), size, "application/octet-stream", "test", "127.0.0.1",
        Instant.EPOCH, Instant.EPOCH, Map.of());
  }

  private static ScanProfile profile(long maxBytes) {
    return new ScanProfile(
        1L, "test", true, "syft", "grype", List.of("vuln"), Map.of(),
        maxBytes, 1000, maxBytes * 10, maxBytes, 2, 30,
        OciPlatformPolicy.REQUIRED_SET, List.of("linux/amd64"), "a".repeat(64), 1,
        Instant.EPOCH, Instant.EPOCH);
  }
}
