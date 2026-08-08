package com.github.klboke.kkrepo.compat;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.klboke.kkrepo.protocol.apt.AptDeb822;
import com.github.klboke.kkrepo.protocol.apt.AptRelease;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.StreamSupport;
import org.apache.commons.compress.archivers.ar.ArArchiveInputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream;
import org.bouncycastle.openpgp.PGPObjectFactory;
import org.bouncycastle.openpgp.PGPPublicKey;
import org.bouncycastle.openpgp.PGPPublicKeyRingCollection;
import org.bouncycastle.openpgp.PGPSignature;
import org.bouncycastle.openpgp.PGPSignatureList;
import org.bouncycastle.openpgp.PGPUtil;
import org.bouncycastle.openpgp.operator.jcajce.JcaKeyFingerprintCalculator;
import org.bouncycastle.openpgp.operator.jcajce.JcaPGPContentVerifierBuilderProvider;
import org.junit.jupiter.api.Test;

/** Opt-in hosted APT contract against a live Nexus reference and kkRepo candidate. */
class AptRepositoryBlackBoxCompatibilityTest {
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final HttpClient HTTP = HttpClient.newBuilder()
      .connectTimeout(Duration.ofSeconds(20))
      .followRedirects(HttpClient.Redirect.NORMAL)
      .build();

  @Test
  void generatedDebianFixtureHasCanonicalIdentity() throws Exception {
    AptCompatibilityFixtures.DebianPackage fixture = AptCompatibilityFixtures.deb(
        "kkrepo-apt-fixture", "1:1.2.3~rc1-2", "amd64", null, "fixture");
    assertEquals("kkrepo-apt-fixture_1.2.3~rc1-2_amd64.deb", fixture.filename());
    assertTrue(fixture.bytes().length > 100);
    assertEquals("!<arch>\n", new String(fixture.bytes(), 0, 8, StandardCharsets.US_ASCII));
    assertEquals(64, fixture.sha256().length());
    assertInstallableDataLayout(fixture);
  }

  private static void assertInstallableDataLayout(
      AptCompatibilityFixtures.DebianPackage fixture) throws Exception {
    byte[] dataArchive = null;
    try (ArArchiveInputStream archive = new ArArchiveInputStream(
        new ByteArrayInputStream(fixture.bytes()))) {
      org.apache.commons.compress.archivers.ArchiveEntry entry;
      while ((entry = archive.getNextEntry()) != null) {
        if ("data.tar.gz".equals(entry.getName())) {
          dataArchive = archive.readNBytes(Math.toIntExact(entry.getSize()));
          break;
        }
      }
    }
    assertNotNull(dataArchive, "fixture must contain data.tar.gz");
    List<String> paths = new ArrayList<>();
    try (TarArchiveInputStream archive = new TarArchiveInputStream(
        new GzipCompressorInputStream(new ByteArrayInputStream(dataArchive)))) {
      org.apache.commons.compress.archivers.ArchiveEntry entry;
      while ((entry = archive.getNextEntry()) != null) {
        paths.add(entry.getName());
      }
    }
    assertTrue(paths.contains("usr/"));
    assertTrue(paths.contains("usr/share/"));
    assertTrue(paths.contains("usr/share/kkrepo-apt/"));
    assertTrue(paths.contains("usr/share/kkrepo-apt/" + fixture.name() + ".txt"));
  }

  @Test
  void hostedUploadMetadataSignaturesAndHttpMatchNexusWhenConfigured() throws Exception {
    Config config = configured();
    String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 10);
    String repository = "apt-compat-" + suffix;
    AptCompatibilityFixtures.SigningKey key = AptCompatibilityFixtures.signingKey(
        "kkRepo APT compatibility <apt-compat@kkrepo.invalid>");
    AptCompatibilityFixtures.DebianPackage first = AptCompatibilityFixtures.deb(
        "kkrepo-apt-" + suffix, "1:1.0~rc1-1", "amd64", null, "first");
    AptCompatibilityFixtures.DebianPackage second = AptCompatibilityFixtures.deb(
        "kkrepo-apt-" + suffix + "-data", "2.0-1", "all",
        first.name() + " (>= 1:1.0~rc1-1)", "all-architecture");

    try {
      createNexusHosted(config, repository, key);
      if (!config.referenceOnly()) createCandidateHosted(config, repository, key);

      Exchange nexusUpload = rawRootUpload(config.nexus(repository), first);
      assertTrue(nexusUpload.status() >= 200 && nexusUpload.status() < 300,
          () -> "Nexus root upload failed: " + nexusUpload.status() + " " + nexusUpload.text());
      Exchange candidateUpload = null;
      if (!config.referenceOnly()) {
        candidateUpload = rawRootUpload(config.candidate(repository), first);
        assertEquals(nexusUpload.status(), candidateUpload.status(), "root POST status");
      }

      SnapshotShape nexus = waitForSnapshot(config.nexus(repository), first, key.publicArmor());
      assertSnapshot(repository, nexus, first);
      if (!config.referenceOnly()) {
        SnapshotShape candidate = waitForSnapshot(config.candidate(repository), first, null);
        assertSnapshot(repository, candidate, first);
        assertEquals(nexus.packagePath(), candidate.packagePath(), "canonical pool path");
        assertTrue(candidate.representations().containsAll(nexus.representations()),
            () -> "candidate must serve every Nexus Packages representation: reference="
                + nexus.representations() + " candidate=" + candidate.representations());
      }

      Exchange nexusComponent = componentUpload(config.nexusAdmin(), repository, second);
      assertEquals(204, nexusComponent.status(), "Nexus Components API upload status");
      if (!config.referenceOnly()) {
        Exchange candidateComponent = componentUpload(config.candidateAdmin(), repository, second);
        assertEquals(nexusComponent.status(), candidateComponent.status(),
            "Components API upload status");
      }
      waitForSnapshot(config.nexus(repository), second, key.publicArmor());
      if (!config.referenceOnly()) waitForSnapshot(config.candidate(repository), second, null);

      assertPackageHttp(config.nexus(repository), nexus.packagePath(), first.bytes());
      if (!config.referenceOnly()) {
        assertPackageHttp(config.candidate(repository), nexus.packagePath(), first.bytes());
        assertBrowseAndSearch(config, repository, first, nexus.packagePath());
      }
    } finally {
      if (!config.keepRepositories()) {
        deleteRepository(config.nexusAdmin(), repository);
        if (!config.referenceOnly()) deleteRepository(config.candidateAdmin(), repository);
      }
    }
  }

  @Test
  void preparesNexusHostedMigrationFixtureWhenConfigured() throws Exception {
    assumeTrue(Boolean.parseBoolean(System.getenv().getOrDefault(
        "APT_MIGRATION_PREP_ENABLED", "false")),
        "Set APT_MIGRATION_PREP_ENABLED=true to prepare the migration fixture");
    String fixtureDirectory = System.getenv("APT_MIGRATION_FIXTURE_DIR");
    assumeTrue(fixtureDirectory != null && !fixtureDirectory.isBlank(),
        "Set APT_MIGRATION_FIXTURE_DIR to a private temporary directory");

    Config config = Config.load();
    assumeTrue(config.enabled(), "Set APT_COMPAT_ENABLED=true to prepare the fixture");
    assumeTrue(reachable(config.nexusAdmin()),
        "Nexus APT reference is not reachable at " + config.nexusBase());

    String repository = System.getenv().getOrDefault(
        "APT_MIGRATION_NEXUS_REPOSITORY", "apt-hosted");
    String packageName = System.getenv().getOrDefault(
        "APT_MIGRATION_PACKAGE", "kkrepo-apt-migration");
    String version = System.getenv().getOrDefault(
        "APT_MIGRATION_VERSION", "1:1.2.3~rc1-2");
    String architecture = System.getenv().getOrDefault(
        "APT_MIGRATION_ARCHITECTURE", "amd64");
    String marker = System.getenv().getOrDefault(
        "APT_MIGRATION_MARKER", "kkRepo Nexus APT migration E2E");
    Path directory = Path.of(fixtureDirectory).toAbsolutePath().normalize();
    Files.createDirectories(directory);

    AptCompatibilityFixtures.SigningKey key = AptCompatibilityFixtures.signingKey(
        "kkRepo APT migration E2E <apt-migration@kkrepo.invalid>");
    AptCompatibilityFixtures.DebianPackage fixture = AptCompatibilityFixtures.deb(
        packageName, version, architecture, null, marker);

    deleteRepository(config.nexusAdmin(), repository);
    createNexusHosted(config, repository, key);
    Exchange upload = rawRootUpload(config.nexus(repository), fixture);
    assertTrue(upload.status() >= 200 && upload.status() < 300,
        () -> "Nexus APT migration fixture upload failed: "
            + upload.status() + " " + upload.text());
    SnapshotShape snapshot = waitForSnapshot(config.nexus(repository), fixture, key.publicArmor());
    assertSnapshot(repository, snapshot, fixture);

    Files.writeString(directory.resolve("private.asc"), key.privateArmor(), StandardCharsets.UTF_8);
    Files.writeString(directory.resolve("public.asc"), key.publicArmor(), StandardCharsets.UTF_8);
    Files.write(directory.resolve(fixture.filename()), fixture.bytes());
    var manifest = JSON.createObjectNode();
    manifest.put("repository", repository);
    manifest.put("package", fixture.name());
    manifest.put("version", fixture.version());
    manifest.put("architecture", fixture.architecture());
    manifest.put("filename", fixture.filename());
    manifest.put("packagePath", snapshot.packagePath());
    manifest.put("sha256", fixture.sha256());
    manifest.put("size", fixture.bytes().length);
    manifest.put("marker", marker);
    Files.writeString(
        directory.resolve("fixture.json"),
        JSON.writerWithDefaultPrettyPrinter().writeValueAsString(manifest) + "\n",
        StandardCharsets.UTF_8);
  }

  private static void assertSnapshot(
      String repository,
      SnapshotShape snapshot,
      AptCompatibilityFixtures.DebianPackage fixture) throws Exception {
    AptDeb822.Stanza stanza = snapshot.packages().stream()
        .filter(value -> fixture.name().equals(value.get("Package")))
        .findFirst()
        .orElseThrow(() -> new AssertionError("Packages does not contain " + fixture.name()));
    assertEquals(fixture.version(), stanza.get("Version"));
    assertEquals(fixture.architecture(), stanza.get("Architecture"));
    assertEquals(fixture.sha256(), stanza.get("SHA256"));
    assertEquals(Integer.toString(fixture.bytes().length), stanza.get("Size"));
    assertEquals(snapshot.packagePath(), stanza.get("Filename"));
    assertTrue(snapshot.packagePath().startsWith("pool/"));
    String canonicalFilename = fixture.name() + "_" + fixture.version() + "_"
        + fixture.architecture() + ".deb";
    assertTrue(snapshot.packagePath().endsWith("/" + canonicalFilename),
        () -> "unexpected Nexus-compatible package path: " + snapshot.packagePath());

    AptRelease release = AptRelease.parse(snapshot.releaseText());
    String acquireByHash = release.fields().get("Acquire-By-Hash");
    assertTrue(acquireByHash == null || "yes".equals(acquireByHash),
        "Acquire-By-Hash must either be omitted like Nexus or safely enabled");
    for (String filename : List.of("Packages", "Packages.gz", "Packages.bz2")) {
      assertTrue(release.checksums().stream().anyMatch(checksum ->
          "SHA256".equals(checksum.algorithm())
              && checksum.path().endsWith("/" + filename)),
          () -> "Release must contain SHA256 for " + filename);
    }
    assertTrue(verifyDetached(
        snapshot.releaseBytes(), snapshot.releaseSignature(), snapshot.publicKey()),
        repository + " Release.gpg must verify with gpg.key");
    assertTrue(verifyInRelease(snapshot.inRelease(), snapshot.publicKey()),
        repository + " InRelease must verify with gpg.key");
  }

  private static SnapshotShape waitForSnapshot(
      Endpoint endpoint,
      AptCompatibilityFixtures.DebianPackage expected,
      String publicKeyFallback) throws Exception {
    Exchange last = null;
    for (int attempt = 0; attempt < 120; attempt++) {
      last = get(endpoint, "dists/stable/Release");
      if (last.status() == 200) {
        LinkedHashMap<String, byte[]> representations = new LinkedHashMap<>();
        List<AptDeb822.Stanza> packages = List.of();
        for (String filename : List.of("Packages", "Packages.gz", "Packages.bz2", "Packages.xz")) {
          Exchange response = get(endpoint, "dists/stable/main/binary-"
              + expected.architecture() + "/" + filename);
          if (response.status() != 200) continue;
          representations.put(filename, response.body());
          if (filename.equals("Packages")) {
            packages = AptDeb822.parse(new String(response.body(), StandardCharsets.UTF_8));
          }
        }
        if (packages.isEmpty()) {
          for (Map.Entry<String, byte[]> entry : representations.entrySet()) {
            byte[] decoded = decompress(entry.getKey(), entry.getValue());
            packages = AptDeb822.parse(new String(decoded, StandardCharsets.UTF_8));
            if (!packages.isEmpty()) break;
          }
        }
        String packagePath = packages.stream()
            .filter(stanza -> expected.name().equals(stanza.get("Package")))
            .map(stanza -> stanza.get("Filename"))
            .findFirst().orElse(null);
        if (packagePath != null) {
          Exchange signature = get(endpoint, "dists/stable/Release.gpg");
          Exchange inRelease = get(endpoint, "dists/stable/InRelease");
          Exchange publicKey = get(endpoint, "gpg.key");
          if (signature.status() != 200
              || inRelease.status() != 200
              || (publicKey.status() != 200 && publicKeyFallback == null)) {
            Thread.sleep(500L);
            continue;
          }
          String publicArmor;
          if (publicKey.status() == 200) {
            publicArmor = publicKey.text();
          } else {
            assertNotNull(publicKeyFallback,
                "repository must expose gpg.key or provide its configured public key");
            publicArmor = publicKeyFallback;
          }
          return new SnapshotShape(
              last.body(),
              last.text(),
              signature.body(),
              inRelease.text(),
              packages,
              packagePath,
              List.copyOf(representations.keySet()),
              publicArmor);
        }
      }
      Thread.sleep(500L);
    }
    throw new AssertionError("APT snapshot did not publish " + expected.filename() + ": "
        + (last == null ? "no response" : last.status() + " " + last.text()));
  }

  private static void assertPackageHttp(Endpoint endpoint, String path, byte[] expected)
      throws Exception {
    Exchange get = get(endpoint, path);
    assertEquals(200, get.status());
    assertArrayEquals(expected, get.body());
    Exchange head = send(endpoint.request(path)
        .method("HEAD", HttpRequest.BodyPublishers.noBody()));
    assertEquals(200, head.status());
    assertEquals(Integer.toString(expected.length), head.header("content-length"));
    Exchange range = send(endpoint.request(path)
        .header("Range", "bytes=0-7")
        .GET());
    assertEquals(206, range.status());
    assertArrayEquals(java.util.Arrays.copyOf(expected, 8), range.body());
    assertFalse(head.header("etag").isBlank());
    Exchange conditional = send(endpoint.request(path)
        .header("If-None-Match", head.header("etag"))
        .GET());
    assertEquals(304, conditional.status());
  }

  private static void assertBrowseAndSearch(
      Config config,
      String repository,
      AptCompatibilityFixtures.DebianPackage fixture,
      String canonicalPackagePath) throws Exception {
    Exchange search = send(config.candidateAdmin().request(
        "/internal/search/components?format=apt&q=" + fixture.name()).GET());
    assertEquals(200, search.status());
    JsonNode items = JSON.readTree(search.body()).path("items");
    assertTrue(items.isArray() && items.size() >= 1);
    JsonNode item = StreamSupport.stream(items.spliterator(), false)
        .filter(candidate -> repository.equals(candidate.path("repository").asText()))
        .filter(candidate -> fixture.name().equals(candidate.path("name").asText()))
        .findFirst()
        .orElseThrow(() -> new AssertionError(
            "APT search did not return the exact package " + fixture.name()));
    assertEquals(repository, item.path("repository").asText());
    assertEquals(fixture.name(), item.path("name").asText());
    assertEquals(fixture.version(), item.path("version").asText());
    assertEquals(fixture.architecture(), item.path("details").path("architecture").asText());
    assertEquals(fixture.sha256(), item.path("details").path("sha256").asText());

    String browsePath = "stable/main/" + fixture.name() + "/" + fixture.version()
        + "/" + fixture.architecture();
    Exchange browse = send(config.candidateAdmin().request(
        "/internal/browse/" + repository + "?path=" + encode(browsePath)).GET());
    assertEquals(200, browse.status());
    assertTrue(browse.text().contains(
        canonicalPackagePath.substring(canonicalPackagePath.lastIndexOf('/') + 1)));
  }

  private static void createNexusHosted(
      Config config, String repository, AptCompatibilityFixtures.SigningKey key) throws Exception {
    var body = JSON.createObjectNode();
    body.put("name", repository);
    body.put("online", true);
    var storage = body.putObject("storage");
    storage.put("blobStoreName", config.nexusBlobStore());
    storage.put("strictContentTypeValidation", true);
    storage.put("writePolicy", "ALLOW");
    body.putObject("apt").put("distribution", "stable");
    var signing = body.putObject("aptSigning");
    signing.put("keypair", key.privateArmor());
    signing.put("passphrase", "");
    Exchange response = send(config.nexusAdmin().request("/service/rest/v1/repositories/apt/hosted")
        .header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body))));
    assertEquals(201, response.status(), () -> "Nexus APT create: " + response.text());
  }

  private static void createCandidateHosted(
      Config config, String repository, AptCompatibilityFixtures.SigningKey key) throws Exception {
    var body = JSON.createObjectNode();
    body.put("name", repository);
    body.put("recipe", "apt-hosted");
    body.put("online", true);
    body.put("blobStoreName", config.candidateBlobStore());
    body.put("strictContentTypeValidation", true);
    body.putObject("hosted").put("writePolicy", "ALLOW");
    var apt = body.putObject("apt");
    apt.put("distribution", "stable");
    apt.put("component", "main");
    apt.putArray("architectures").add("amd64");
    apt.put("metadataMode", "RESIGN");
    apt.put("validUntilDays", 30);
    Exchange created = send(config.candidateAdmin().request("/internal/repositories")
        .header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body))));
    assertEquals(201, created.status(), () -> "kkRepo APT create: " + created.text());

    var signing = JSON.createObjectNode();
    signing.put("privateKey", key.privateArmor());
    signing.put("passphrase", "");
    Exchange rotated = send(config.candidateAdmin().request(
            "/internal/repositories/" + repository + "/apt/signing-key")
        .header("Content-Type", "application/json")
        .PUT(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(signing))));
    assertEquals(200, rotated.status(), () -> "kkRepo APT key import: " + rotated.text());
  }

  private static Exchange rawRootUpload(
      Endpoint endpoint, AptCompatibilityFixtures.DebianPackage fixture) throws Exception {
    return send(endpoint.request("")
        .header("Content-Type", "multipart/form-data")
        .POST(HttpRequest.BodyPublishers.ofByteArray(fixture.bytes())));
  }

  private static Exchange componentUpload(
      AdminEndpoint endpoint,
      String repository,
      AptCompatibilityFixtures.DebianPackage fixture) throws Exception {
    Multipart multipart = new Multipart().file(
        "apt.asset", fixture.filename(), "application/vnd.debian.binary-package", fixture.bytes());
    return send(endpoint.request(
            "/service/rest/v1/components?repository=" + encode(repository))
        .header("Content-Type", multipart.contentType())
        .POST(HttpRequest.BodyPublishers.ofByteArray(multipart.body())));
  }

  private static void deleteRepository(AdminEndpoint endpoint, String repository) {
    try {
      send(endpoint.request("/service/rest/v1/repositories/" + encode(repository))
          .DELETE());
    } catch (Exception ignored) {
      try {
        send(endpoint.request("/internal/repositories/" + encode(repository)).DELETE());
      } catch (Exception ignoredAgain) {
        // Best-effort cleanup for opt-in live fixtures.
      }
    }
  }

  private static boolean verifyDetached(byte[] content, byte[] signature, String publicArmor)
      throws Exception {
    PGPPublicKeyRingCollection keys = new PGPPublicKeyRingCollection(
        PGPUtil.getDecoderStream(new ByteArrayInputStream(
            publicArmor.getBytes(StandardCharsets.UTF_8))),
        new JcaKeyFingerprintCalculator());
    PGPObjectFactory objects = new PGPObjectFactory(
        PGPUtil.getDecoderStream(new ByteArrayInputStream(signature)),
        new JcaKeyFingerprintCalculator());
    Object value = objects.nextObject();
    assertTrue(value instanceof PGPSignatureList, "detached signature packet expected");
    PGPSignature signed = ((PGPSignatureList) value).get(0);
    PGPPublicKey key = keys.getPublicKey(signed.getKeyID());
    assertNotNull(key, "signature key must exist in gpg.key");
    signed.init(new JcaPGPContentVerifierBuilderProvider().setProvider("BC"), key);
    signed.update(content);
    return signed.verify();
  }

  private static boolean verifyInRelease(String inRelease, String publicArmor) throws Exception {
    PGPPublicKeyRingCollection keys = new PGPPublicKeyRingCollection(
        PGPUtil.getDecoderStream(new ByteArrayInputStream(
            publicArmor.getBytes(StandardCharsets.UTF_8))),
        new JcaKeyFingerprintCalculator());
    ClearSignedMessage message = clearSignedMessage(inRelease);
    PGPObjectFactory objects = new PGPObjectFactory(
        PGPUtil.getDecoderStream(new ByteArrayInputStream(message.signatureArmor())),
        new JcaKeyFingerprintCalculator());
    Object value = objects.nextObject();
    assertTrue(value instanceof PGPSignatureList, "InRelease signature packet expected");
    PGPSignature signature = ((PGPSignatureList) value).get(0);
    PGPPublicKey key = keys.getPublicKey(signature.getKeyID());
    assertNotNull(key, "InRelease signature key must exist in gpg.key");
    signature.init(new JcaPGPContentVerifierBuilderProvider().setProvider("BC"), key);
    updateCleartextSignature(signature, message.cleartext());
    return signature.verify();
  }

  private static ClearSignedMessage clearSignedMessage(String armored) {
    byte[] bytes = armored.getBytes(StandardCharsets.UTF_8);
    byte[] separator = "\n\n".getBytes(StandardCharsets.US_ASCII);
    byte[] signatureMarker = "-----BEGIN PGP SIGNATURE-----"
        .getBytes(StandardCharsets.US_ASCII);
    int headerEnd = indexOf(bytes, separator, 0);
    int signatureStart = indexOf(bytes, signatureMarker, headerEnd + separator.length);
    if (headerEnd < 0 || signatureStart < 0) {
      throw new IllegalArgumentException("Invalid InRelease armor");
    }
    ByteArrayOutputStream cleartext = new ByteArrayOutputStream();
    int offset = headerEnd + separator.length;
    while (offset < signatureStart) {
      int end = offset;
      while (end < signatureStart && bytes[end] != '\n') end++;
      int contentStart = offset;
      if (end - offset >= 2 && bytes[offset] == '-' && bytes[offset + 1] == ' ') {
        contentStart += 2;
      }
      cleartext.write(bytes, contentStart, end - contentStart);
      if (end < signatureStart) cleartext.write('\n');
      offset = end + 1;
    }
    return new ClearSignedMessage(
        cleartext.toByteArray(),
        java.util.Arrays.copyOfRange(bytes, signatureStart, bytes.length));
  }

  private static int indexOf(byte[] source, byte[] target, int from) {
    if (from < 0) return -1;
    outer:
    for (int index = from; index <= source.length - target.length; index++) {
      for (int offset = 0; offset < target.length; offset++) {
        if (source[index + offset] != target[offset]) continue outer;
      }
      return index;
    }
    return -1;
  }

  private static void updateCleartextSignature(PGPSignature signature, byte[] cleartext)
      throws Exception {
    int offset = 0;
    boolean firstLine = true;
    while (offset < cleartext.length) {
      int end = offset;
      while (end < cleartext.length && cleartext[end] != '\r' && cleartext[end] != '\n') end++;
      if (!firstLine) {
        signature.update((byte) '\r');
        signature.update((byte) '\n');
      }
      int contentEnd = end;
      while (contentEnd > offset
          && (cleartext[contentEnd - 1] == ' ' || cleartext[contentEnd - 1] == '\t')) {
        contentEnd--;
      }
      if (contentEnd > offset) signature.update(cleartext, offset, contentEnd - offset);
      firstLine = false;
      if (end == cleartext.length) break;
      if (cleartext[end] == '\r' && end + 1 < cleartext.length && cleartext[end + 1] == '\n') {
        offset = end + 2;
      } else {
        offset = end + 1;
      }
    }
  }

  private static byte[] decompress(String filename, byte[] bytes) throws Exception {
    if (filename.endsWith(".gz")) {
      try (var input = new GzipCompressorInputStream(new ByteArrayInputStream(bytes))) {
        return input.readAllBytes();
      }
    }
    if (filename.endsWith(".bz2")) {
      try (var input = new BZip2CompressorInputStream(new ByteArrayInputStream(bytes))) {
        return input.readAllBytes();
      }
    }
    if (filename.endsWith(".xz")) {
      try (var input = new XZCompressorInputStream(new ByteArrayInputStream(bytes))) {
        return input.readAllBytes();
      }
    }
    return bytes;
  }

  private static Exchange get(Endpoint endpoint, String path) throws Exception {
    return send(endpoint.request(path).GET());
  }

  private static Exchange send(HttpRequest.Builder request) throws Exception {
    HttpResponse<byte[]> response = HTTP.send(
        request.header("User-Agent", "kkrepo-apt-compat-test/1")
            .timeout(Duration.ofSeconds(180)).build(),
        HttpResponse.BodyHandlers.ofByteArray());
    return new Exchange(response.statusCode(), response.body(), response.headers().map());
  }

  private static Config configured() throws Exception {
    Config config = Config.load();
    assumeTrue(config.enabled(), "Set APT_COMPAT_ENABLED=true to run APT compatibility");
    assumeTrue(reachable(config.nexusAdmin()),
        "Nexus APT reference is not reachable at " + config.nexusBase());
    if (!config.referenceOnly()) {
      assumeTrue(reachable(config.candidateAdmin()),
          "kkRepo candidate is not reachable at " + config.candidateBase());
    }
    return config;
  }

  private static boolean reachable(AdminEndpoint endpoint) {
    try {
      return send(endpoint.request("/service/rest/v1/status").GET()).status() > 0;
    } catch (Exception ignored) {
      return false;
    }
  }

  private static String basic(String username, String password) {
    return "Basic " + Base64.getEncoder().encodeToString(
        (username + ":" + password).getBytes(StandardCharsets.UTF_8));
  }

  private static String encode(String value) {
    return java.net.URLEncoder.encode(value, StandardCharsets.UTF_8);
  }

  private record SnapshotShape(
      byte[] releaseBytes,
      String releaseText,
      byte[] releaseSignature,
      String inRelease,
      List<AptDeb822.Stanza> packages,
      String packagePath,
      List<String> representations,
      String publicKey) {
  }

  private record ClearSignedMessage(byte[] cleartext, byte[] signatureArmor) {
  }

  private record Exchange(int status, byte[] body, Map<String, List<String>> headers) {
    String text() {
      return new String(body, StandardCharsets.UTF_8);
    }

    String header(String name) {
      return headers.entrySet().stream()
          .filter(entry -> entry.getKey().equalsIgnoreCase(name))
          .flatMap(entry -> entry.getValue().stream())
          .findFirst().orElse("");
    }
  }

  private record Endpoint(String base, String authorization) {
    HttpRequest.Builder request(String path) {
      String suffix = path == null || path.isBlank() ? "/" : "/" + path;
      return HttpRequest.newBuilder(URI.create(base + suffix))
          .header("Authorization", authorization);
    }
  }

  private record AdminEndpoint(String base, String authorization) {
    HttpRequest.Builder request(String path) {
      return HttpRequest.newBuilder(URI.create(base + path))
          .header("Authorization", authorization);
    }
  }

  private record Config(
      boolean enabled,
      boolean referenceOnly,
      boolean keepRepositories,
      String nexusBase,
      String candidateBase,
      String nexusAuthorization,
      String candidateAuthorization,
      String nexusBlobStore,
      String candidateBlobStore) {
    static Config load() {
      String nexusUser = CompatDefaults.setting(
          "compat.apt.nexus.username", "APT_NEXUS_COMPAT_USERNAME")
          .orElseGet(() -> CompatDefaults.nexusUsername().orElse(""));
      String nexusPassword = CompatDefaults.setting(
          "compat.apt.nexus.password", "APT_NEXUS_COMPAT_PASSWORD")
          .orElseGet(() -> CompatDefaults.nexusPassword().orElse(""));
      String candidateUser = CompatDefaults.setting(
          "compat.apt.kkrepo.username", "APT_KKREPO_COMPAT_USERNAME")
          .orElseGet(() -> CompatDefaults.nexusPlusUsername().orElse(""));
      String candidatePassword = CompatDefaults.setting(
          "compat.apt.kkrepo.password", "APT_KKREPO_COMPAT_PASSWORD")
          .orElseGet(() -> CompatDefaults.nexusPlusPassword().orElse(""));
      return new Config(
          CompatDefaults.setting("compat.apt.enabled", "APT_COMPAT_ENABLED")
              .map(Boolean::parseBoolean).orElse(false),
          CompatDefaults.setting("compat.apt.referenceOnly", "APT_COMPAT_REFERENCE_ONLY")
              .map(Boolean::parseBoolean).orElse(false),
          CompatDefaults.setting("compat.apt.keepRepositories", "APT_COMPAT_KEEP_REPOSITORIES")
              .map(Boolean::parseBoolean).orElse(false),
          CompatDefaults.setting("compat.apt.nexus.baseUrl", "APT_NEXUS_COMPAT_BASE_URL")
              .map(CompatDefaults::stripTrailingSlash)
              .orElseGet(() -> CompatDefaults.nexusBaseUrl().orElse("")),
          CompatDefaults.setting("compat.apt.kkrepo.baseUrl", "APT_KKREPO_COMPAT_BASE_URL")
              .map(CompatDefaults::stripTrailingSlash)
              .orElseGet(() -> CompatDefaults.nexusPlusBaseUrl().orElse("")),
          basic(nexusUser, nexusPassword),
          basic(candidateUser, candidatePassword),
          CompatDefaults.setting("compat.apt.nexus.blobStore", "APT_NEXUS_COMPAT_BLOB_STORE")
              .orElse("default"),
          CompatDefaults.setting("compat.apt.kkrepo.blobStore", "APT_KKREPO_COMPAT_BLOB_STORE")
              .orElse("default"));
    }

    Endpoint nexus(String repository) {
      return new Endpoint(nexusBase + "/repository/" + repository, nexusAuthorization);
    }

    Endpoint candidate(String repository) {
      return new Endpoint(candidateBase + "/repository/" + repository, candidateAuthorization);
    }

    AdminEndpoint nexusAdmin() {
      return new AdminEndpoint(nexusBase, nexusAuthorization);
    }

    AdminEndpoint candidateAdmin() {
      return new AdminEndpoint(candidateBase, candidateAuthorization);
    }
  }

  private static final class Multipart {
    private final String boundary = "kkrepo-apt-" + UUID.randomUUID();
    private final ByteArrayOutputStream body = new ByteArrayOutputStream();

    Multipart file(String field, String filename, String contentType, byte[] bytes)
        throws Exception {
      write("--" + boundary + "\r\n");
      write("Content-Disposition: form-data; name=\"" + field + "\"; filename=\""
          + filename + "\"\r\n");
      write("Content-Type: " + contentType + "\r\n\r\n");
      body.write(bytes);
      write("\r\n--" + boundary + "--\r\n");
      return this;
    }

    String contentType() {
      return "multipart/form-data; boundary=" + boundary;
    }

    byte[] body() {
      return body.toByteArray();
    }

    private void write(String value) throws Exception {
      body.write(value.getBytes(StandardCharsets.UTF_8));
    }
  }
}
