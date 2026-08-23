package com.github.klboke.kkrepo.server.securityscan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.persistence.jdbc.api.PersistenceHashes;
import com.github.klboke.kkrepo.persistence.jdbc.api.ConanRegistryDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.SecurityScanDao.ScanProfile;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetBlobRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetRecord;
import com.github.klboke.kkrepo.security.scan.ScanEnums.CandidateDisposition;
import com.github.klboke.kkrepo.security.scan.ScanEnums.OciPlatformPolicy;
import com.github.klboke.kkrepo.security.scan.ScanEnums.SubjectKind;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
        Arguments.of(RepositoryFormat.CONDA, "noarch/demo-1.0-py_0.conda", "package"),
        Arguments.of(RepositoryFormat.CONDA, "linux-64/demo-1.0-py_0.tar.bz2", "package"),
        Arguments.of(
            RepositoryFormat.CONAN,
            "conans/demo/1.0/acme/stable/revisions/rrev/packages/package/revisions/prev/files/conan_package.tzst",
            "conan"),
        Arguments.of(RepositoryFormat.APT, "pool/d/demo/demo_1.0_amd64.deb", "package"),
        Arguments.of(RepositoryFormat.R, "src/contrib/demo_1.0.0.tar.gz", "r"),
        Arguments.of(
            RepositoryFormat.HUGGINGFACE,
            "openai/model/resolve/" + "a".repeat(40) + "/model.safetensors",
            "huggingface"),
        Arguments.of(
            RepositoryFormat.HUGGINGFACE,
            "openai/model/resolve/" + "a".repeat(40)
                + "/model.safetensors.index.json",
            "huggingface"),
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

  @Test
  void isolatesCondaCatalogResultsFromGenericArchiveSbomReuse() {
    var classification = classifier.classify(
        asset(RepositoryFormat.CONDA, "noarch/demo-1.0-py_0.tar.bz2", "package"),
        blob(42),
        profile(1024));

    assertEquals(SubjectKind.CONDA_PACKAGE, classification.subjectKind());
  }

  @Test
  void bindsConanPackageScanningToArchiveAndConanInfo() {
    SecurityScanCandidateClassifier conanClassifier = new SecurityScanCandidateClassifier();
    AssetRecord archiveAsset = asset(
        RepositoryFormat.CONAN,
        "conans/demo/1.0/acme/stable/revisions/rrev/packages/package/revisions/prev/files/conan_package.tgz",
        "conan");
    assertFalse(conanClassifier.subjectIdentity(archiveAsset, blob(42)).complete());
    assertTrue(conanClassifier.conanPackageScanContext(archiveAsset.id()).isEmpty());

    ConanRegistryDao registry = mock(ConanRegistryDao.class);
    conanClassifier.setConanRegistryDao(registry);
    ConanRegistryDao.RevisionFile archive = revisionFile(
        archiveAsset.id(), "conan_package.tgz", "a".repeat(64));
    ConanRegistryDao.RevisionFile info = revisionFile(
        2L, "conaninfo.txt", "b".repeat(64));
    ConanRegistryDao.AssetFile identity = new ConanRegistryDao.AssetFile(
        archive,
        new ConanRegistryDao.RecipeCoordinate(1L, "demo", "1.0", "acme", "stable"),
        "rrev", "package", "prev");
    when(registry.findPackageScanContext(archiveAsset.id())).thenReturn(Optional.of(
        new ConanRegistryDao.PackageScanContext(identity, info)));

    var classification = conanClassifier.classify(archiveAsset, blob(42), profile(1024));
    var subject = conanClassifier.subjectIdentity(archiveAsset, blob(42));

    assertEquals(SubjectKind.CONAN_PACKAGE, classification.subjectKind());
    assertTrue(subject.complete());
    assertEquals(
        "conan-package:sha256:" + "a".repeat(64) + ":conaninfo-sha256:" + "b".repeat(64),
        subject.key());
    assertEquals("package", subject.attributes().get("packageId"));

    when(registry.findPackageScanContext(archiveAsset.id())).thenReturn(Optional.empty());
    assertFalse(conanClassifier.subjectIdentity(archiveAsset, blob(42)).complete());

    AssetRecord recipeArchive = asset(
        RepositoryFormat.CONAN,
        "conans/demo/1.0/acme/stable/revisions/rrev/files/conan_export.tgz",
        "recipe");
    assertEquals(
        "sha256:" + "a".repeat(64),
        conanClassifier.subjectIdentity(recipeArchive, blob(42)).key());
    assertTrue(conanClassifier.subjectIdentity(recipeArchive, blob(42)).complete());
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
        Arguments.of(RepositoryFormat.CONDA, "noarch/repodata.json", "repodata"),
        Arguments.of(RepositoryFormat.CONDA, "channeldata.json", "channeldata"),
        Arguments.of(
            RepositoryFormat.CONAN,
            "conans/demo/1.0/acme/stable/revisions/rrev/files/conan_export.tgz",
            "recipe"),
        Arguments.of(
            RepositoryFormat.CONAN,
            "conans/demo/1.0/acme/stable/revisions/rrev/files/conanmanifest.txt",
            "manifest"),
        Arguments.of(RepositoryFormat.APT, ".apt/snapshots/stable/1/Packages.deb", "metadata"),
        Arguments.of(RepositoryFormat.R, "src/contrib/PACKAGES.gz", "r"),
        Arguments.of(RepositoryFormat.R, "src/contrib/Archive/demo/demo_1.0.0.tar.gz", "r"),
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

  @Test
  void givesModelFilesAnIsolatedNonExecutingScanIdentity() {
    AssetRecord model = asset(
        RepositoryFormat.HUGGINGFACE,
        "openai/model/resolve/" + "a".repeat(40) + "/model.gguf",
        "huggingface");
    var classification = classifier.classify(model, blob(42), profile(1024));
    var subject = classifier.subjectIdentity(model, blob(42));

    assertEquals(SubjectKind.HF_MODEL_FILE, classification.subjectKind());
    assertEquals(
        com.github.klboke.kkrepo.security.scan.ScanEnums.TargetClassification.MODEL,
        classification.targetClassification());
    assertEquals("hf-model-file:sha256:" + "a".repeat(64), subject.key());
  }

  @Test
  void exposesRDescriptionIdentityAndPartialCoverageWithoutScanningStaticTarballs() {
    AssetRecord source = asset(
        RepositoryFormat.R, "src/contrib/demo_1.0.0.tar.gz", "r");
    var subject = classifier.subjectIdentity(source, blob(42));

    assertEquals("demo", subject.attributes().get("package"));
    assertEquals("1.0.0", subject.attributes().get("version"));
    assertEquals("PARTIAL", subject.attributes().get("vulnerabilityCoverage"));

    AssetRecord unprojected = new AssetRecord(
        source.id(), source.repositoryId(), source.componentId(), source.assetBlobId(),
        source.format(), source.path(), source.pathHash(), source.name(), source.kind(),
        source.contentType(), source.size(), source.lastDownloadedAt(), source.lastUpdatedAt(),
        Map.of());
    assertEquals(
        CandidateDisposition.NOT_APPLICABLE,
        classifier.classify(unprojected, blob(42), profile(1024)).disposition());
  }

  private static AssetRecord asset(RepositoryFormat format, String path, String kind) {
    Map<String, Object> attributes = format == RepositoryFormat.R
        && path.equals("src/contrib/demo_1.0.0.tar.gz")
        ? Map.of(
            "rInputSchema", "r-source-package-v1",
            "rPackage", "demo",
            "rVersion", "1.0.0",
            "rNamespace", "src/contrib",
            "rSource", "hosted")
        : Map.of();
    return new AssetRecord(
        1L, 1L, null, 1L, format, path, PersistenceHashes.pathHash(path),
        path.substring(path.lastIndexOf('/') + 1), kind, "application/octet-stream",
        42L, null, Instant.EPOCH, attributes);
  }

  private static AssetBlobRecord blob(long size) {
    return new AssetBlobRecord(
        1L, 1L, "blob://test/object", PersistenceHashes.blobRefHash("blob://test/object"),
        "object", PersistenceHashes.objectKeyHash("object"), "1".repeat(40), "a".repeat(64),
        "2".repeat(32), size, "application/octet-stream", "test", "127.0.0.1",
        Instant.EPOCH, Instant.EPOCH, Map.of());
  }

  private static ConanRegistryDao.RevisionFile revisionFile(
      Long assetId, String path, String sha256) {
    return new ConanRegistryDao.RevisionFile(
        assetId, ConanRegistryDao.OWNER_PACKAGE, 10L, path, assetId,
        "2".repeat(32), "1".repeat(40), sha256, 42L,
        "application/octet-stream", 1L, Instant.EPOCH, Instant.EPOCH);
  }

  private static ScanProfile profile(long maxBytes) {
    return new ScanProfile(
        1L, "test", true, "syft", "grype", List.of("vuln"), Map.of(),
        maxBytes, 1000, maxBytes * 10, maxBytes, 2, 30,
        OciPlatformPolicy.REQUIRED_SET, List.of("linux/amd64"), "a".repeat(64), 1,
        Instant.EPOCH, Instant.EPOCH);
  }
}
